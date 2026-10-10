package com.example.jack

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.util.Log
import com.example.jack.BuildConfig
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.example.jack.ui.theme.JackTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.Charset
import java.util.UUID

// Admin PIN entry and Change Admin PIN dialogs.

/**
 * v3.4: full-screen "ADMIN ACCESS" lock page. Same rules as before (salted PIN, 5 tries then a
 * 30-second lockout) — now with PIN dots, a shake on a wrong PIN and a live lockout countdown.
 */
@Composable
fun AdminPinDialog(
    context: Context,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    val usingDefaultPin = remember { AdminSession.isDefaultPin(context) }
    val scope = rememberCoroutineScope()
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }

    // Live lockout countdown (keypad and Unlock are disabled while it runs).
    var lockoutMs by remember { mutableLongStateOf(AdminSession.lockoutRemainingMs()) }
    LaunchedEffect(lockoutMs > 0) {
        while (lockoutMs > 0) {
            kotlinx.coroutines.delay(250)
            lockoutMs = AdminSession.lockoutRemainingMs()
        }
    }
    val lockedOut = lockoutMs > 0

    fun shakeIt() {
        scope.launch {
            for (x in listOf(18f, -16f, 12f, -8f, 4f, 0f)) {
                shake.animateTo(x, androidx.compose.animation.core.tween(45))
            }
        }
    }

    fun submit() {
        if (lockedOut || enteredPin.isEmpty()) return
        if (AdminSession.tryUnlock(context, enteredPin)) {
            onSuccess()
        } else {
            enteredPin = ""
            lockoutMs = AdminSession.lockoutRemainingMs()
            errorText = if (lockoutMs > 0) null else "Incorrect PIN. Try again."
            shakeIt()
        }
    }

    JackFullScreenDialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxSize(), color = JackColors.Concrete) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---------- Top bar ----------
                Surface(color = JackColors.Paper) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            JackIconButton(Icons.Default.Close, "Cancel", onDismiss)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("ADMIN ACCESS", style = MaterialTheme.typography.headlineSmall, color = JackColors.Ink)
                                Text("STAFF ONLY · ENTER PIN TO CONTINUE", style = MaterialTheme.typography.labelSmall, color = JackColors.Graphite)
                            }
                            BrandLogo(size = 36.dp)
                        }
                        StripeBar()
                    }
                }

                // ---------- Body ----------
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(if (lockedOut) JackColors.Red else JackColors.Ink, MaterialTheme.shapes.medium),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = JackColors.Paper, modifier = Modifier.size(36.dp))
                    }

                    PinDots(
                        length = enteredPin.length,
                        error = errorText != null || lockedOut,
                        modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(shake.value.dp.roundToPx(), 0) }
                    )

                    // Status line: lockout, error, or default-PIN warning
                    when {
                        lockedOut -> PinNotice(
                            text = "Too many wrong attempts. Try again in ${(lockoutMs + 999) / 1000} s.",
                            color = JackColors.Red, tint = JackColors.RedTint
                        )
                        errorText != null -> PinNotice(text = errorText!!, color = JackColors.Red, tint = JackColors.RedTint)
                        usingDefaultPin -> PinNotice(
                            text = "Default PIN is 1234 — change it in Settings → Security.",
                            color = JackColors.Amber, tint = JackColors.AmberTint
                        )
                        else -> Text(
                            "${AdminPinPolicy.MIN_LENGTH}–${AdminPinPolicy.MAX_LENGTH} digits",
                            style = HintStyle
                        )
                    }

                    Box(modifier = Modifier.widthIn(max = 420.dp)) {
                        CustomKeypad(
                            isNumericOnly = true,
                            onCharClick = { digit ->
                                if (!lockedOut && enteredPin.length < AdminPinPolicy.MAX_LENGTH) {
                                    enteredPin += digit
                                    errorText = null
                                }
                            },
                            onBackspace = { if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1) },
                            onClear = { enteredPin = "" }
                        )
                    }
                }

                // ---------- Action ----------
                Surface(color = JackColors.Paper, border = BorderStroke(1.dp, JackColors.Line)) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        JackPrimaryButton(
                            text = if (lockedOut) "Locked" else "Unlock",
                            onClick = { submit() },
                            enabled = !lockedOut && enteredPin.length >= AdminPinPolicy.MIN_LENGTH,
                            icon = Icons.Default.Lock,
                            color = JackColors.Ink,
                            height = 64.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/** Row of PIN dots: filled for each digit typed, empty slots up to the minimum length. */
@Composable
fun PinDots(length: Int, error: Boolean, modifier: Modifier = Modifier) {
    val slots = maxOf(AdminPinPolicy.MIN_LENGTH, length)
    val fill = if (error) JackColors.Red else JackColors.Ink
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(slots) { i ->
            val filled = i < length
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (filled) fill else JackColors.Paper)
                    .border(2.dp, if (filled) fill else JackColors.Gray, CircleShape)
            )
        }
    }
}

/** A short coloured notice under the PIN dots. */
@Composable
private fun PinNotice(text: String, color: Color, tint: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .background(tint, MaterialTheme.shapes.small)
            .border(1.dp, color.copy(alpha = 0.5f), MaterialTheme.shapes.small)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/**
 * v3.5: Change the admin PIN as a full-screen, three-step page:
 * 1 current PIN → 2 new PIN → 3 repeat new PIN. Same rules as before (AdminPinPolicy).
 */
@Composable
fun ChangePinDialog(
    context: Context,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var step by remember { mutableIntStateOf(1) }          // 1 current, 2 new, 3 repeat
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }

    val value = when (step) { 1 -> currentPin; 2 -> newPin; else -> confirmPin }
    fun setValue(v: String) {
        val t = v.take(AdminPinPolicy.MAX_LENGTH)
        when (step) { 1 -> currentPin = t; 2 -> newPin = t; else -> confirmPin = t }
        errorText = null
    }

    fun fail(message: String) {
        errorText = message
        scope.launch {
            for (x in listOf(18f, -16f, 12f, -8f, 4f, 0f)) {
                shake.animateTo(x, androidx.compose.animation.core.tween(45))
            }
        }
    }

    fun next() {
        when (step) {
            1 -> if (AdminSession.verify(context, currentPin)) { step = 2; errorText = null } else {
                currentPin = ""
                fail("Current PIN is incorrect.")
            }
            2 -> if (newPin.length < AdminPinPolicy.MIN_LENGTH) {
                fail("Use ${AdminPinPolicy.MIN_LENGTH}–${AdminPinPolicy.MAX_LENGTH} digits.")
            } else { step = 3; errorText = null }
            else -> {
                val problem = AdminPinPolicy.validateNewPin(newPin, confirmPin)
                if (problem != null) {
                    // Start the new PIN again so the two entries can't drift apart.
                    newPin = ""; confirmPin = ""; step = 2
                    fail(problem)
                } else {
                    AdminSession.setPin(context, newPin)
                    onChanged()
                }
            }
        }
    }

    fun back() {
        errorText = null
        if (step == 1) onDismiss() else step -= 1
    }

    val stepTitle = when (step) {
        1 -> "Enter the current PIN"
        2 -> "Choose a new PIN"
        else -> "Repeat the new PIN"
    }

    JackFullScreenDialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxSize().adminActivity(), color = JackColors.Concrete) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---------- Top bar ----------
                Surface(color = JackColors.Paper) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            JackIconButton(Icons.Default.Close, "Cancel", onDismiss)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("CHANGE ADMIN PIN", style = MaterialTheme.typography.headlineSmall, color = JackColors.Ink)
                                Text("STEP $step OF 3", style = MaterialTheme.typography.labelSmall, color = JackColors.Graphite)
                            }
                            StatusChip("$step / 3", JackColors.Blue, filled = true)
                        }
                        StripeBar()
                    }
                }

                // ---------- Body ----------
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StepProgress(step = step)

                    Text(
                        stepTitle.uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = JackColors.Ink
                    )

                    PinDots(
                        length = value.length,
                        error = errorText != null,
                        modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(shake.value.dp.roundToPx(), 0) }
                    )

                    if (errorText != null) {
                        PinNotice(text = errorText!!, color = JackColors.Red, tint = JackColors.RedTint)
                    } else {
                        Text(
                            when (step) {
                                1 -> "Prove it's you before changing anything."
                                2 -> "${AdminPinPolicy.MIN_LENGTH}–${AdminPinPolicy.MAX_LENGTH} digits. Avoid 1234 or your birthday."
                                else -> "Type the same new PIN again."
                            },
                            style = HintStyle,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    Box(modifier = Modifier.widthIn(max = 420.dp)) {
                        CustomKeypad(
                            isNumericOnly = true,
                            onCharClick = { d -> setValue(value + d) },
                            onBackspace = { setValue(value.dropLast(1)) },
                            onClear = { setValue("") }
                        )
                    }
                }

                // ---------- Actions ----------
                Surface(color = JackColors.Paper, border = BorderStroke(1.dp, JackColors.Line)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        JackOutlineButton(
                            text = if (step == 1) "Cancel" else "Back",
                            onClick = { back() },
                            modifier = Modifier.weight(1f).height(64.dp)
                        )
                        JackPrimaryButton(
                            text = if (step == 3) "Save PIN" else "Next",
                            onClick = { next() },
                            enabled = value.length >= AdminPinPolicy.MIN_LENGTH,
                            icon = if (step == 3) Icons.Default.Lock else null,
                            color = if (step == 3) JackColors.Ink else JackColors.Blue,
                            height = 64.dp,
                            modifier = Modifier.weight(2f)
                        )
                    }
                }
            }
        }
    }
}

/** Three numbered blocks joined by lines: done (black), current (blue), to do (outline). */
@Composable
private fun StepProgress(step: Int) {
    val labels = listOf("Current", "New", "Repeat")
    Row(
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { i, label ->
            val n = i + 1
            val color = when {
                n < step -> JackColors.Ink
                n == step -> JackColors.Blue
                else -> JackColors.Gray
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(if (n <= step) color else JackColors.Paper, MaterialTheme.shapes.extraSmall)
                        .border(2.dp, color, MaterialTheme.shapes.extraSmall),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (n < step) "✓" else "$n",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (n <= step) JackColors.Paper else JackColors.Gray
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
            }
            if (n < labels.size) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                        .padding(bottom = 18.dp)
                        .height(2.dp)
                        .background(if (n < step) JackColors.Ink else JackColors.Line)
                )
            }
        }
    }
}
