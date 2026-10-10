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

// Settings dialog and its sections (battery, service, kiosk, export & sync, users, PIN, reset).

@Composable
fun NumberStepper(
    label: String,
    sublabel: String,
    value: Int,
    range: IntRange,
    badgeColor: Color,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(sublabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = { if (value > range.first) onValueChange(value - 1) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Decrease")
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = badgeColor.copy(alpha = 0.15f),
                modifier = Modifier.widthIn(min = 52.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "$value%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
            }
            IconButton(
                onClick = { if (value < range.last) onValueChange(value + 1) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Increase")
            }
        }
    }
}

@Composable
fun SettingsDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val activity = context as? ComponentActivity
    val prefs = remember { context.getSharedPreferences("esp32_prefs", Context.MODE_PRIVATE) }

    var minBattery by remember { mutableStateOf(prefs.getInt("pref_min_battery", 20)) }
    var maxBattery by remember { mutableStateOf(prefs.getInt("pref_max_battery", 80)) }

    val isKioskActive = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
    }
    var kioskEnabled by remember { mutableStateOf(isKioskActive) }
    var bgServiceEnabled by remember { mutableStateOf(Esp32BackgroundService.isRunning) }

    val batteryStatus = remember {
        IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
    }
    val batteryLevel = batteryStatus?.let {
        val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else -1
    } ?: -1
    val isCharging = batteryStatus?.let {
        val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    } ?: false

    AlertDialog(
        onDismissRequest = {
            prefs.edit().putInt("pref_min_battery", minBattery).putInt("pref_max_battery", maxBattery).apply()
            onDismiss()
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Jack Settings", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                // App version — bumped on every upgrade so the installed build is easy to confirm.
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.adminActivity()
                    .fillMaxWidth()
                    .height(400.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    // Section 1: Battery & Charge/Discharge Automation
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("1. Auto-Charge & Discharge", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = if (batteryLevel >= 0) "$batteryLevel% ${if (isCharging) "⚡" else "🔋"}" else "--%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            NumberStepper(
                                label = "Min Charge Threshold",
                                sublabel = "Sends 'CHARGE'",
                                value = minBattery,
                                range = 5..49,
                                badgeColor = MaterialTheme.colorScheme.primary,
                                onValueChange = { if (it < maxBattery) minBattery = it }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            NumberStepper(
                                label = "Max Discharge Threshold",
                                sublabel = "Sends 'DISCHARGE'",
                                value = maxBattery,
                                range = 51..100,
                                badgeColor = MaterialTheme.colorScheme.error,
                                onValueChange = { if (it > minBattery) maxBattery = it }
                            )
                        }
                    }
                }

                item {
                    // Section 2: Permissions
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("2. Required Permissions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Bluetooth Scan, Connect & USB Host", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Open App Settings")
                            }
                        }
                    }
                }

                item {
                    // Section 3: Background Service & Notifications
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("3. Notifications & 24/7 Running", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("24/7 Background Service", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = bgServiceEnabled,
                                    onCheckedChange = { enabled ->
                                        bgServiceEnabled = enabled
                                        if (enabled) {
                                            Esp32BackgroundService.startService(context)
                                        } else {
                                            Esp32BackgroundService.stopService(context)
                                        }
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Disable Battery Optimization (Prevent App Closing)")
                            }
                        }
                    }
                }

                item {
                    // Section 4: Kiosk Mode
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("4. Kiosk Mode (Screen Pinning)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Lock Task Mode", style = MaterialTheme.typography.bodyMedium)
                                    Text("Locks app to screen until deactivated", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = kioskEnabled,
                                    onCheckedChange = { enabled ->
                                        kioskEnabled = enabled
                                        try {
                                            if (enabled) {
                                                activity?.startLockTask()
                                            } else {
                                                activity?.stopLockTask()
                                            }
                                        } catch (_: Exception) {}
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    // Section 5: Attendance export (daily CSV) & Google Sheets sync
                    AttendanceExportSyncCard(context)
                }

                item {
                    // Section 6: Hardware Shortcut Keys
                    var shortcutsEnabled by remember { mutableStateOf(prefs.getBoolean("pref_hardware_shortcuts", true)) }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("6. Hardware Shortcut Keys", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text("Vol Up 🟢", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                                        Text("Attendance", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF2196F3).copy(alpha = 0.15f),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text("Vol Down 🔵", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2196F3))
                                        Text("Ping ESP32 • Camera shutter", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Enable Volume Shortcuts", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = shortcutsEnabled,
                                    onCheckedChange = { enabled ->
                                        shortcutsEnabled = enabled
                                        prefs.edit().putBoolean("pref_hardware_shortcuts", enabled).apply()
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    // Section 7: Device Status & Operator Diagnostics
                    val kioskMgr = remember { KioskManager(context) }
                    val isOwner = kioskMgr.isDeviceOwner
                    val isLocked = kioskMgr.isLockTaskActive

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("7. Device Status & Diagnostics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Device Owner: ${if (isOwner) "YES" else "NO"}", style = MaterialTheme.typography.bodySmall)
                            Text("Lock Task Mode: ${if (isLocked) "ACTIVE" else "INACTIVE"}", style = MaterialTheme.typography.bodySmall)
                            Text("Package: ${context.packageName}", style = MaterialTheme.typography.bodySmall)
                            Text("App Version: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Text("Android SDK: ${Build.VERSION.SDK_INT}", style = MaterialTheme.typography.bodySmall)
                            Text("Foreground Service: ${if (Esp32BackgroundService.isRunning) "RUNNING" else "STOPPED"}", style = MaterialTheme.typography.bodySmall)
                            Text("Boot Receiver: REGISTERED", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                item {
                    // Section 8: Users, Workers & Subscribers Management
                    val db = remember { AttendanceDatabase.get(context) }
                    var userCount by remember { mutableStateOf(db.getAllUsers().size) }
                    var showUserManagementDialog by remember { mutableStateOf(false) }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("8. Users, Workers & Subscribers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Registered users: $userCount", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { showUserManagementDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Manage Users & Subscribers")
                            }
                        }
                    }

                    if (showUserManagementDialog) {
                        UserManagementDialog(
                            context = context,
                            onDismiss = {
                                userCount = db.getAllUsers().size
                                showUserManagementDialog = false
                            }
                        )
                    }
                }

                item {
                    // Section 9: Admin PIN & security
                    var showChangePin by remember { mutableStateOf(false) }
                    var pinStatus by remember { mutableStateOf<String?>(null) }
                    val usingDefault = remember(pinStatus) { AdminSession.isDefaultPin(context) }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("9. Admin PIN & Security", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (usingDefault) "⚠️ Still using the default PIN (1234). Please change it." else "✅ Custom PIN set.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (usingDefault) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Admin screens lock when closed, or after 2 minutes without use.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            pinStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { showChangePin = true },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Change Admin PIN")
                            }
                        }
                    }

                    if (showChangePin) {
                        ChangePinDialog(
                            context = context,
                            onChanged = {
                                showChangePin = false
                                pinStatus = "✅ Admin PIN changed."
                                JackCore.addLog("🔑 Admin PIN changed.")
                            },
                            onDismiss = { showChangePin = false }
                        )
                    }
                }

                item {
                    // Section 10: Test & Reset Utilities
                    val db = remember { AttendanceDatabase.get(context) }
                    var clearStatus by remember { mutableStateOf<String?>(null) }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("10. Test & Reset Utilities", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        db.clearAttendance()
                                        clearStatus = "Attendance records cleared!"
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Clear Attendance", color = MaterialTheme.colorScheme.error)
                                }
                                Button(
                                    onClick = {
                                        db.clearAttendance()
                                        db.writableDatabase.execSQL("DELETE FROM users")
                                        prefs.edit().clear().apply()
                                        clearStatus = "App reset! Restarting..."
                                        android.os.Handler(context.mainLooper).postDelayed({
                                            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                            }
                                            if (intent != null) context.startActivity(intent)
                                        }, 1000)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Reset App")
                                }
                            }
                            if (clearStatus != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = clearStatus!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (BuildConfig.DEBUG) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("🛠️ Developer Tools", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Temporarily exit Lock Task mode without losing Device Owner status.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        val km = KioskManager(context)
                                        if (activity != null) {
                                            km.stopLockTask(activity)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Exit Kiosk Mode (Stop Lock Task)")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    prefs.edit().putInt("pref_min_battery", minBattery).putInt("pref_max_battery", maxBattery).apply()
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save & Close")
            }
        }
    )
}

/**
 * Settings section 5: daily CSV files on the tablet + Google Sheets sync.
 * (Workshop management app integration is planned for later.)
 */
@Composable
fun AttendanceExportSyncCard(context: Context) {
    val scope = rememberCoroutineScope()
    var showLogsDialog by remember { mutableStateOf(false) }

    val saved = remember { AttendanceSync.loadSheetsConfig(context) }
    var sheetsEnabled by remember { mutableStateOf(saved.enabled) }
    var sheetsUrl by remember { mutableStateOf(saved.url) }
    var sheetsSecret by remember { mutableStateOf(saved.secret) }
    var busy by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val exportStatus by AttendanceSync.lastExportStatus
    val syncStatus by AttendanceSync.lastSyncStatus
    val unsynced by AttendanceSync.unsyncedCount

    fun currentConfig() = AttendanceSync.SheetsConfig(sheetsEnabled, sheetsUrl.trim(), sheetsSecret.trim())

    // Android 9 and older need storage permission to save into the public Documents folder.
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        scope.launch { AttendanceSync.exportDay(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())) }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("5. Attendance Export & Sync", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

            // ---- Daily file ----
            Text("📁 Daily file (automatic)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(
                "Saved to Documents/Jack/attendance_<date>.csv and updated after every check-in/out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(exportStatus, style = MaterialTheme.typography.bodySmall)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showLogsDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("View Logs") }
                Button(
                    onClick = {
                        if (!AttendanceSync.hasLegacyWritePermission()) {
                            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            scope.launch {
                                AttendanceSync.exportDay(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Export Today") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ---- Google Sheets ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("☁️ Google Sheets sync", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Switch(
                    checked = sheetsEnabled,
                    onCheckedChange = {
                        sheetsEnabled = it
                        AttendanceSync.saveSheetsConfig(context, currentConfig())
                    }
                )
            }
            Text(
                "Paste the Apps Script web app URL and the secret from your sheet's script (see GOOGLE_SHEETS_SETUP.md).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = sheetsUrl,
                onValueChange = { sheetsUrl = it },
                label = { Text("Web app URL") },
                placeholder = { Text("https://script.google.com/macros/s/…/exec") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = sheetsSecret,
                onValueChange = { sheetsSecret = it },
                label = { Text("Secret") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Text("Waiting to sync: $unsynced visit(s)", style = MaterialTheme.typography.bodySmall)
            Text(syncStatus, style = MaterialTheme.typography.bodySmall)
            testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        AttendanceSync.saveSheetsConfig(context, currentConfig())
                        busy = true
                        testResult = "Testing…"
                        scope.launch {
                            testResult = AttendanceSync.testSheetsConnection(currentConfig())
                            busy = false
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Save & Test") }
                Button(
                    enabled = !busy && sheetsEnabled,
                    onClick = {
                        AttendanceSync.saveSheetsConfig(context, currentConfig())
                        busy = true
                        testResult = null
                        scope.launch {
                            AttendanceSync.syncToSheets(manual = true)
                            busy = false
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Sync Now") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                "🏭 Workshop management app — live \"Who is in the Building\" feed: coming later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showLogsDialog) {
        AttendanceLogsDialog(
            context = context,
            onDismiss = { showLogsDialog = false }
        )
    }
}
