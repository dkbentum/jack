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

@Composable
fun AdminPinDialog(
    context: Context,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    val showError = errorText != null
    val usingDefaultPin = remember { AdminSession.isDefaultPin(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Admin Authentication", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (usingDefaultPin) {
                        "Enter the Admin PIN to access management features (default: 1234 — please change it in Settings)."
                    } else {
                        "Enter the Admin PIN to access management features."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, if (showError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (enteredPin.isEmpty()) "Enter PIN..." else "*".repeat(enteredPin.length),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (enteredPin.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                CustomKeypad(
                    isNumericOnly = true,
                    onCharClick = { digit ->
                        if (enteredPin.length < AdminPinPolicy.MAX_LENGTH) {
                            enteredPin += digit
                            errorText = null
                        }
                    },
                    onBackspace = { if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1) },
                    onClear = { enteredPin = "" }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val wait = AdminSession.lockoutRemainingMs()
                    when {
                        wait > 0 -> errorText = "Too many wrong attempts. Try again in ${(wait + 999) / 1000} s."
                        AdminSession.tryUnlock(context, enteredPin) -> onSuccess()
                        else -> {
                            enteredPin = ""
                            val lockout = AdminSession.lockoutRemainingMs()
                            errorText = if (lockout > 0) {
                                "Too many wrong attempts. Try again in ${(lockout + 999) / 1000} s."
                            } else {
                                "Incorrect PIN. Try again."
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** Change the admin PIN: current PIN, then the new PIN twice. Uses the on-screen number pad (kiosk-friendly). */
@Composable
fun ChangePinDialog(
    context: Context,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var activeField by remember { mutableStateOf("current") }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun edit(transform: (String) -> String) {
        errorText = null
        when (activeField) {
            "current" -> currentPin = transform(currentPin).take(AdminPinPolicy.MAX_LENGTH)
            "new" -> newPin = transform(newPin).take(AdminPinPolicy.MAX_LENGTH)
            else -> confirmPin = transform(confirmPin).take(AdminPinPolicy.MAX_LENGTH)
        }
    }

    @Composable
    fun PinField(label: String, key: String, value: String) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { activeField = key },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(2.dp, if (activeField == key) MaterialTheme.colorScheme.primary else Color.Transparent)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = if (value.isEmpty()) "Tap to enter…" else "•".repeat(value.length),
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = if (value.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Admin PIN", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.adminActivity().fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PinField("Current PIN", "current", currentPin)
                PinField("New PIN (${AdminPinPolicy.MIN_LENGTH}–${AdminPinPolicy.MAX_LENGTH} digits)", "new", newPin)
                PinField("Repeat new PIN", "confirm", confirmPin)
                errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                CustomKeypad(
                    isNumericOnly = true,
                    onCharClick = { d -> edit { it + d } },
                    onBackspace = { edit { it.dropLast(1) } },
                    onClear = { edit { "" } }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val problem = when {
                        !AdminSession.verify(context, currentPin) -> "Current PIN is incorrect."
                        else -> AdminPinPolicy.validateNewPin(newPin, confirmPin)
                    }
                    if (problem != null) {
                        errorText = problem
                    } else {
                        AdminSession.setPin(context, newPin)
                        onChanged()
                    }
                },
                shape = RoundedCornerShape(10.dp)
            ) { Text("Save PIN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
