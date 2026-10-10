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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
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
 * v3.2 look: black workshop frame, CHECK IN / CHECK OUT tag + the person's name on top,
 * a face guide over the preview, a big shutter at the bottom and a "VOL ▼" key hint.
 *
 * The photo is taken with the on-screen shutter button or the tablet's **Volume Down** key.
 * [onCaptured] receives an upright photo, scaled to at most 720 px on its longest side.
 */
@Composable
fun SelfieCaptureDialog(
    personName: String,
    isCheckOut: Boolean,
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
    var flash by remember { mutableStateOf(false) }
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
        flash = true
        error = null
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

    // Short white flash so people know the photo was taken.
    val flashAlpha by animateFloatAsState(
        targetValue = if (flash) 0.85f else 0f,
        animationSpec = tween(durationMillis = if (flash) 60 else 260),
        label = "flash"
    )
    LaunchedEffect(flash) {
        if (flash) { delay(90); flash = false }
    }

    val actionColor = if (isCheckOut) JackColors.Amber else JackColors.Blue

    JackFullScreenDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(JackColors.Ink)
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

            // ---------- Top: who and what ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandLogo(size = 32.dp, tint = JackColors.Paper)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    StatusChip(if (isCheckOut) "Check out" else "Check in", actionColor, filled = true)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = personName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = JackColors.Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = JackColors.Paper)
                }
            }
            StripeBar(stripe = actionColor, gap = JackColors.Steel)

            // ---------- Middle: live preview + face guide ----------
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
            ) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                FaceGuide(color = actionColor, modifier = Modifier.fillMaxSize())

                Text(
                    text = "FIT YOUR FACE IN THE FRAME",
                    style = MaterialTheme.typography.labelLarge,
                    color = JackColors.Paper,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(JackColors.Ink.copy(alpha = 0.7f), MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )

                if (!cameraReady && error == null) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = JackColors.Paper, strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("STARTING CAMERA…", style = MaterialTheme.typography.labelLarge, color = JackColors.Paper)
                    }
                }

                error?.let {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp)
                            .background(JackColors.Red, MaterialTheme.shapes.small)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = JackColors.Paper)
                        Spacer(Modifier.width(10.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = JackColors.Paper)
                    }
                }

                // Flash overlay
                if (flashAlpha > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = flashAlpha))
                    )
                }
            }

            // ---------- Bottom: shutter ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, JackColors.Graphite),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = JackColors.Paper),
                        modifier = Modifier.height(48.dp)
                    ) { Text("CANCEL", style = MaterialTheme.typography.labelLarge) }
                }

                ShutterButton(
                    enabled = cameraReady && !capturing,
                    busy = capturing,
                    color = actionColor,
                    onClick = { capture() }
                )

                // Reminder that the tablet's Volume Down key also takes the photo.
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .border(1.dp, JackColors.Graphite, MaterialTheme.shapes.extraSmall)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("VOL ▼", style = MaterialTheme.typography.labelLarge, color = JackColors.Paper)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("ALSO SNAPS", style = MaterialTheme.typography.labelSmall, color = JackColors.Gray)
                    }
                }
            }
        }
    }
}

/** Big round shutter: white ring, coloured centre. */
@Composable
private fun ShutterButton(enabled: Boolean, busy: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(88.dp)
            .border(4.dp, if (enabled || busy) JackColors.Paper else JackColors.Graphite, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(if (enabled) color else JackColors.Steel)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "Take photo" }
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp), color = JackColors.Paper, strokeWidth = 3.dp)
        } else {
            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = JackColors.Paper, modifier = Modifier.size(32.dp))
        }
    }
}

/** Darkens everything outside an oval face guide and marks its corners like a viewfinder. */
@Composable
private fun FaceGuide(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val guideW = minOf(size.width * 0.70f, size.height * 0.55f)
        val guideH = guideW * 1.3f
        val left = (size.width - guideW) / 2f
        val top = (size.height - guideH) / 2f - size.height * 0.03f
        val oval = Rect(left, top, left + guideW, top + guideH)

        // Dim outside the oval
        val shade = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addOval(oval)
        }
        drawPath(shade, Color.Black.copy(alpha = 0.40f))
        drawOval(
            color = Color.White.copy(alpha = 0.85f),
            topLeft = oval.topLeft,
            size = oval.size,
            style = Stroke(width = 2.dp.toPx())
        )

        // Viewfinder corner brackets
        val arm = guideW * 0.14f
        val w = 4.dp.toPx()
        val pad = 10.dp.toPx()
        val l = oval.left - pad; val t = oval.top - pad; val r = oval.right + pad; val b = oval.bottom + pad
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx, y), strokeWidth = w, cap = StrokeCap.Square)
            drawLine(color, Offset(x, y), Offset(x, y + dy), strokeWidth = w, cap = StrokeCap.Square)
        }
        corner(l, t, arm, arm)
        corner(r, t, -arm, arm)
        corner(l, b, arm, -arm)
        corner(r, b, -arm, -arm)
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
