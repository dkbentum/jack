package com.example.jack

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.max

private const val TAG = "SelfieCamera"
private const val MAX_SELFIE_SIDE_PX = 720   // big enough to recognise a face, small enough to store thousands

/** True if Jack may use the camera. As device owner (kiosk) it grants itself the permission silently. */
fun ensureCameraPermission(context: Context): Boolean {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
        return true
    }
    return try {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isDeviceOwnerApp(context.packageName) && dpm.setPermissionGrantState(
            ComponentName(context, JackDeviceAdminReceiver::class.java),
            context.packageName,
            Manifest.permission.CAMERA,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
        )
    } catch (_: Exception) {
        false
    }
}

/**
 * Full-screen selfie camera that runs inside Jack (no external camera app, so it works in
 * kiosk / Lock Task mode). Uses the front camera, or the back one if the tablet has no front camera.
 *
 * The photo is taken with the on-screen shutter button or the tablet's **Volume Down** key.
 * [onCaptured] receives an upright photo, scaled to at most 720 px on its longest side.
 */
@Composable
fun SelfieCaptureDialog(
    title: String,
    onCaptured: (Bitmap) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE  // works inside dialogs on older tablets
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    var cameraReady by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    // Bind the camera while this dialog is on screen; release it when it closes.
    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            try {
                val p = providerFuture.get()
                provider = p
                val selector = when {
                    p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    else -> null
                }
                if (selector == null) {
                    error = "No camera found on this device."
                    return@addListener
                }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                cameraReady = true
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                error = "Camera could not start: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            try { provider?.unbindAll() } catch (_: Exception) {}
        }
    }

    fun capture() {
        if (!cameraReady || capturing) return
        capturing = true
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = try {
                        image.toUprightScaledBitmap(MAX_SELFIE_SIDE_PX)
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not read photo", e)
                        null
                    } finally {
                        image.close()
                    }
                    capturing = false
                    if (bitmap != null) onCaptured(bitmap) else error = "Could not read the photo. Please try again."
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    capturing = false
                    error = "Capture failed: ${exception.localizedMessage}. Please try again."
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Volume Down = shutter. Handled here because the camera dialog has its own window.
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.VolumeDown) {
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) capture()
                        true  // consume it: no volume change while the camera is open
                    } else {
                        false
                    }
                }
                .focusRequester(focusRequester)
                .focusable()
        ) {
            LaunchedEffect(Unit) {
                try { focusRequester.requestFocus() } catch (_: Exception) {}
            }

            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            // Title / instructions
            Text(
                text = "$title\nLook at the camera — press Volume Down or tap the button",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            error?.let {
                Text(
                    text = it,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .background(Color(0xCCB00020), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 40.dp, start = 24.dp, end = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Cancel") }

                // Big round shutter button
                Button(
                    onClick = { capture() },
                    enabled = cameraReady && !capturing,
                    shape = CircleShape,
                    modifier = Modifier.size(84.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    if (capturing) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
                    } else {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Capture", modifier = Modifier.size(40.dp))
                    }
                }

                Spacer(modifier = Modifier.width(80.dp))  // balances the Cancel button
            }

            if (!cameraReady && error == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
            }
        }
    }
}

/** Converts a CameraX frame to an upright bitmap no larger than [maxSide] px. */
private fun ImageProxy.toUprightScaledBitmap(maxSide: Int): Bitmap {
    val source = toBitmap()
    val rotation = imageInfo.rotationDegrees
    val scale = minOf(1f, maxSide.toFloat() / max(source.width, source.height))

    val matrix = Matrix().apply {
        postScale(scale, scale)
        if (rotation != 0) postRotate(rotation.toFloat())
    }
    val result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (result !== source) source.recycle()
    return result
}
