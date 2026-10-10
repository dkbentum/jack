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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.vector.ImageVector
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

// v3.1 — Settings as a full-screen page, grouped:
// ESP32 & Power · Attendance & Sync · Users · Alerts · Security · Device · Danger zone.

// =================================================================================================
// Settings building blocks
// =================================================================================================

/** One group of settings: a section label above a white card. */
@Composable
private fun SettingsGroup(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = JackColors.Ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            SectionLabel(text = title)
        }
        JackCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), content = content)
        }
    }
}

/** A row inside a group: title, optional explanation, optional control on the right. */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    titleColor: Color = JackColors.Ink,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
            if (subtitle != null) Text(subtitle, style = HintStyle)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = JackColors.Paper,
                checkedTrackColor = JackColors.Blue,
                uncheckedThumbColor = JackColors.Gray,
                uncheckedTrackColor = JackColors.Panel,
                uncheckedBorderColor = JackColors.Line
            )
        )
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = JackColors.Line, thickness = 1.dp)

/** Label/value pair for the diagnostics list. */
@Composable
private fun InfoLine(label: String, value: String, valueColor: Color = JackColors.Ink) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = JackColors.Graphite, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = valueColor)
    }
}

/** − value + stepper for the battery thresholds. */
@Composable
fun NumberStepper(
    label: String,
    sublabel: String,
    value: Int,
    range: IntRange,
    badgeColor: Color,
    onValueChange: (Int) -> Unit
) {
    SettingRow(title = label, subtitle = sublabel) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedIconButton(
                onClick = { if (value > range.first) onValueChange(value - 1) },
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, JackColors.Line),
                modifier = Modifier.size(40.dp)
            ) { Icon(Icons.Default.Remove, contentDescription = "Decrease") }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.widthIn(min = 64.dp)
            ) {
                Text("$value%", style = MaterialTheme.typography.titleMedium, color = badgeColor)
            }
            OutlinedIconButton(
                onClick = { if (value < range.last) onValueChange(value + 1) },
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, JackColors.Line),
                modifier = Modifier.size(40.dp)
            ) { Icon(Icons.Default.Add, contentDescription = "Increase") }
        }
    }
}

// =================================================================================================
// The Settings page
// =================================================================================================

/**
 * Full-screen Settings (admin only). Name kept as SettingsDialog so the admin-lock wiring in
 * MainActivity is unchanged; it now fills the screen like a normal page.
 */
@Composable
fun SettingsDialog(
    context: Context,
    onDismiss: () -> Unit,
    onOpenConnection: () -> Unit = {},
    onOpenAlerts: () -> Unit = {}
) {
    JackFullScreenDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .adminActivity(),
            color = JackColors.Concrete
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsTopBar(onBack = onDismiss)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    item { Esp32AndPowerGroup(context, onOpenConnection) }
                    item { AttendanceExportSyncCard(context) }
                    item { UsersGroup(context) }
                    item { AlertsGroup(onOpenAlerts) }
                    item { SecurityGroup(context) }
                    item { DeviceGroup(context) }
                    item { DangerZoneGroup(context) }
                    item {
                        Text(
                            "Triple Dimension · Jack v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = JackColors.Gray,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Surface(color = JackColors.Paper) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                JackIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("SETTINGS", style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 1.sp), color = JackColors.Ink)
                    Text("ADMIN · LOCKS WHEN CLOSED", style = MaterialTheme.typography.labelSmall, color = JackColors.Graphite)
                }
                // App version — bumped on every upgrade so the installed build is easy to confirm.
                StatusChip("v${BuildConfig.VERSION_NAME}", JackColors.Ink, filled = true)
            }
            StripeBar()
        }
    }
}

// ---------------- ESP32 & Power ----------------
@Composable
private fun Esp32AndPowerGroup(context: Context, onOpenConnection: () -> Unit) {
    val prefs = remember { context.getSharedPreferences(JackCore.PREF_NAME, Context.MODE_PRIVATE) }
    var minBattery by remember { mutableStateOf(prefs.getInt("pref_min_battery", 20)) }
    var maxBattery by remember { mutableStateOf(prefs.getInt("pref_max_battery", 80)) }

    val batteryStatus = remember { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
    val batteryLevel = batteryStatus?.let {
        val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else -1
    } ?: -1
    val isCharging = batteryStatus?.let {
        val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    } ?: false

    val status = JackCore.connectionStatus.value
    val statusColor = when (status) {
        ConnectionState.CONNECTED -> JackColors.Green
        ConnectionState.DISCONNECTED -> JackColors.Red
        else -> JackColors.Amber
    }

    SettingsGroup("ESP32 & Power", Icons.Default.Power) {
        SettingRow(
            title = "ESP32 connection",
            subtitle = "${JackCore.deviceName.value} · ${if (JackCore.transportMode.value == TransportMode.BLE) "Bluetooth" else "USB"}"
        ) {
            StatusChip(status.name, statusColor, filled = status == ConnectionState.CONNECTED)
        }
        JackOutlineButton(
            text = "Connection options",
            onClick = onOpenConnection,
            icon = Icons.Default.Power,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        RowDivider()
        SettingRow(
            title = "Tablet battery",
            subtitle = "Jack tells the ESP32 to switch the charger on and off."
        ) {
            StatusChip(
                if (batteryLevel >= 0) "$batteryLevel%${if (isCharging) " · charging" else ""}" else "--%",
                if (isCharging) JackColors.Green else JackColors.Ink
            )
        }
        NumberStepper(
            label = "Start charging at",
            sublabel = "Sends CHARGE",
            value = minBattery,
            range = 5..49,
            badgeColor = JackColors.Ink,
            onValueChange = {
                if (it < maxBattery) {
                    minBattery = it
                    prefs.edit().putInt("pref_min_battery", it).apply()   // saved immediately
                }
            }
        )
        RowDivider()
        NumberStepper(
            label = "Stop charging at",
            sublabel = "Sends DISCHARGE",
            value = maxBattery,
            range = 51..100,
            badgeColor = JackColors.Blue,
            onValueChange = {
                if (it > minBattery) {
                    maxBattery = it
                    prefs.edit().putInt("pref_max_battery", it).apply()
                }
            }
        )
    }
}

// ---------------- Users ----------------
@Composable
private fun UsersGroup(context: Context) {
    val db = remember { AttendanceDatabase.get(context) }
    var userCount by remember { mutableStateOf(db.getAllUsers().size) }
    var showUserManagementDialog by remember { mutableStateOf(false) }

    SettingsGroup("Users", Icons.Default.People) {
        SettingRow(
            title = "Workers & subscribers",
            subtitle = "People who can check in with an Attendee ID."
        ) {
            Text("$userCount", style = MaterialTheme.typography.headlineSmall, color = JackColors.Ink)
        }
        JackOutlineButton(
            text = "Manage users",
            onClick = { showUserManagementDialog = true },
            icon = Icons.Default.PersonAdd,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        )
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

// ---------------- Alerts ----------------
@Composable
private fun AlertsGroup(onOpenAlerts: () -> Unit) {
    val count = JackCore.alertRules.size
    SettingsGroup("Alerts", Icons.Default.Notifications) {
        SettingRow(
            title = "Alert rules",
            subtitle = "Play a sound when an ESP32 message contains a trigger phrase."
        ) {
            Text("$count", style = MaterialTheme.typography.headlineSmall, color = JackColors.Ink)
        }
        JackOutlineButton(
            text = "Manage alerts",
            onClick = onOpenAlerts,
            icon = Icons.Default.Notifications,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        )
    }
}

// ---------------- Security ----------------
@Composable
private fun SecurityGroup(context: Context) {
    var showChangePin by remember { mutableStateOf(false) }
    var pinStatus by remember { mutableStateOf<String?>(null) }
    val usingDefault = remember(pinStatus) { AdminSession.isDefaultPin(context) }

    SettingsGroup("Security", Icons.Default.Lock) {
        SettingRow(
            title = "Admin PIN",
            subtitle = pinStatus ?: if (usingDefault) "Still the default (1234) — please change it." else "Custom PIN set.",
            titleColor = JackColors.Ink
        ) {
            if (usingDefault) StatusChip("Default", JackColors.Red) else StatusChip("Set", JackColors.Green)
        }
        JackOutlineButton(
            text = "Change admin PIN",
            onClick = { showChangePin = true },
            icon = Icons.Default.Lock,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Admin screens lock when closed, or after 2 minutes without use.",
            style = HintStyle,
            modifier = Modifier.padding(vertical = 10.dp)
        )
    }

    if (showChangePin) {
        ChangePinDialog(
            context = context,
            onChanged = {
                showChangePin = false
                pinStatus = "Admin PIN changed."
                JackCore.addLog("🔑 Admin PIN changed.")
            },
            onDismiss = { showChangePin = false }
        )
    }
}

// ---------------- Device ----------------
@Composable
private fun DeviceGroup(context: Context) {
    val activity = context as? ComponentActivity
    val prefs = remember { context.getSharedPreferences(JackCore.PREF_NAME, Context.MODE_PRIVATE) }
    var bgServiceEnabled by remember { mutableStateOf(Esp32BackgroundService.isRunning) }
    var shortcutsEnabled by remember { mutableStateOf(prefs.getBoolean("pref_hardware_shortcuts", true)) }
    val kioskMgr = remember { KioskManager(context) }
    var kioskEnabled by remember { mutableStateOf(kioskMgr.isLockTaskActive) }

    SettingsGroup("Device", Icons.Default.Settings) {
        SettingSwitch(
            title = "24/7 background service",
            subtitle = "Keeps the ESP32 connected when the screen is closed.",
            checked = bgServiceEnabled
        ) { enabled ->
            bgServiceEnabled = enabled
            if (enabled) Esp32BackgroundService.startService(context) else Esp32BackgroundService.stopService(context)
        }
        RowDivider()
        SettingSwitch(
            title = "Volume shortcuts",
            subtitle = "Vol Up: check in/out · Vol Down: ping ESP32 / camera shutter",
            checked = shortcutsEnabled
        ) { enabled ->
            shortcutsEnabled = enabled
            prefs.edit().putBoolean("pref_hardware_shortcuts", enabled).apply()
        }
        RowDivider()
        SettingSwitch(
            title = "Kiosk lock (Lock Task)",
            subtitle = "Pins Jack to the screen until switched off.",
            checked = kioskEnabled
        ) { enabled ->
            kioskEnabled = enabled
            try {
                if (enabled) activity?.startLockTask() else activity?.stopLockTask()
            } catch (_: Exception) {}
        }
        RowDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JackOutlineButton(
                text = "App permissions",
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    })
                },
                modifier = Modifier.weight(1f)
            )
            JackOutlineButton(
                text = "Battery optimisation",
                onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        })
                    } catch (_: Exception) {}
                },
                modifier = Modifier.weight(1f)
            )
        }
        RowDivider()
        // Diagnostics
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Text("DIAGNOSTICS", style = MaterialTheme.typography.labelMedium, color = JackColors.Ink)
            Spacer(Modifier.height(4.dp))
            InfoLine("App version", "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            InfoLine("Device owner", if (kioskMgr.isDeviceOwner) "YES" else "NO")
            InfoLine("Lock Task", if (kioskMgr.isLockTaskActive) "ACTIVE" else "INACTIVE")
            InfoLine(
                "Background service",
                if (Esp32BackgroundService.isRunning) "RUNNING" else "STOPPED",
                if (Esp32BackgroundService.isRunning) JackColors.Green else JackColors.Red
            )
            InfoLine("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            InfoLine("Package", context.packageName)
        }
    }
}

// ---------------- Danger zone ----------------
@Composable
private fun DangerZoneGroup(context: Context) {
    val activity = context as? ComponentActivity
    val db = remember { AttendanceDatabase.get(context) }
    var clearStatus by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }   // "clear" or "reset"

    SettingsGroup("Danger zone", Icons.Default.Delete) {
        Text(
            "These can't be undone. The daily CSV files and Google Sheet are not affected.",
            style = HintStyle,
            modifier = Modifier.padding(top = 10.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JackOutlineButton(
                text = "Clear attendance",
                onClick = { confirm = "clear" },
                color = JackColors.Red,
                modifier = Modifier.weight(1f)
            )
            JackOutlineButton(
                text = "Reset app",
                onClick = { confirm = "reset" },
                color = JackColors.Red,
                modifier = Modifier.weight(1f)
            )
        }
        clearStatus?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = JackColors.Red, modifier = Modifier.padding(bottom = 10.dp))
        }
        if (BuildConfig.DEBUG) {
            RowDivider()
            SettingRow(title = "Developer: exit kiosk", subtitle = "Debug builds only. Keeps device-owner status.") {
                JackOutlineButton(
                    text = "Exit",
                    onClick = { if (activity != null) KioskManager(context).stopLockTask(activity) },
                    color = JackColors.Red
                )
            }
        }
    }

    // Confirmation before anything destructive (previously these ran on a single tap).
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = JackColors.Paper,
            shape = MaterialTheme.shapes.medium,
            title = {
                Text(
                    if (what == "clear") "CLEAR ALL ATTENDANCE?" else "RESET THE APP?",
                    style = MaterialTheme.typography.titleLarge,
                    color = JackColors.Red
                )
            },
            text = {
                Text(
                    if (what == "clear") {
                        "Deletes every check-in and check-out stored on this tablet. Users, alerts and settings are kept."
                    } else {
                        "Deletes all attendance, all users and every setting (including the admin PIN, which goes back to 1234). Jack restarts afterwards."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                JackPrimaryButton(
                    text = if (what == "clear") "Clear" else "Reset",
                    color = JackColors.Red,
                    height = 48.dp,
                    onClick = {
                        confirm = null
                        if (what == "clear") {
                            db.clearAttendance()
                            clearStatus = "Attendance records cleared."
                        } else {
                            db.clearAttendance()
                            db.writableDatabase.execSQL("DELETE FROM users")
                            context.getSharedPreferences(JackCore.PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
                            clearStatus = "App reset. Restarting…"
                            android.os.Handler(context.mainLooper).postDelayed({
                                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                }
                                if (intent != null) context.startActivity(intent)
                            }, 1000)
                        }
                    }
                )
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("CANCEL", style = MaterialTheme.typography.labelLarge, color = JackColors.Ink) } }
        )
    }
}

// =================================================================================================
// Attendance & Sync group (daily CSV + Google Sheets)
// =================================================================================================

/**
 * Daily CSV files on the tablet + Google Sheets sync.
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
    fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    // Android 9 and older need storage permission to save into the public Documents folder.
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        scope.launch { AttendanceSync.exportDay(today()) }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = JackColors.Blue,
        unfocusedBorderColor = JackColors.Line,
        focusedLabelColor = JackColors.Blue,
        cursorColor = JackColors.Blue
    )

    SettingsGroup("Attendance & Sync", Icons.Default.CheckCircle) {
        // ---- Daily file ----
        SettingRow(
            title = "Daily file (automatic)",
            subtitle = "Documents/Jack/attendance_<date>.csv — updated after every check-in/out."
        )
        Text(exportStatus, style = MaterialTheme.typography.labelMedium, color = JackColors.Graphite)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JackOutlineButton(text = "View logs", onClick = { showLogsDialog = true }, modifier = Modifier.weight(1f))
            JackOutlineButton(
                text = "Export today",
                onClick = {
                    if (!AttendanceSync.hasLegacyWritePermission()) {
                        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        scope.launch { AttendanceSync.exportDay(today()) }
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
        RowDivider()

        // ---- Google Sheets ----
        SettingSwitch(
            title = "Google Sheets sync",
            subtitle = "Paste the Apps Script URL and secret (see GOOGLE_SHEETS_SETUP.md).",
            checked = sheetsEnabled
        ) {
            sheetsEnabled = it
            AttendanceSync.saveSheetsConfig(context, currentConfig())
        }
        OutlinedTextField(
            value = sheetsUrl,
            onValueChange = { sheetsUrl = it },
            label = { Text("Web app URL") },
            placeholder = { Text("https://script.google.com/macros/s/…/exec") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = sheetsSecret,
            onValueChange = { sheetsSecret = it },
            label = { Text("Secret") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = fieldColors,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("WAITING TO SYNC", style = MaterialTheme.typography.labelSmall, color = JackColors.Graphite, modifier = Modifier.weight(1f))
            StatusChip("$unsynced visit(s)", if (unsynced > 0) JackColors.Amber else JackColors.Green)
        }
        Text(syncStatus, style = MaterialTheme.typography.labelMedium, color = JackColors.Graphite, modifier = Modifier.padding(top = 4.dp))
        testResult?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = if (it.startsWith("✅")) JackColors.Green else JackColors.Red)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JackOutlineButton(
                text = "Save & test",
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
                modifier = Modifier.weight(1f)
            )
            JackPrimaryButton(
                text = "Sync now",
                enabled = !busy && sheetsEnabled,
                height = 48.dp,
                onClick = {
                    AttendanceSync.saveSheetsConfig(context, currentConfig())
                    busy = true
                    testResult = null
                    scope.launch {
                        AttendanceSync.syncToSheets(manual = true)
                        busy = false
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
        RowDivider()
        SettingRow(
            title = "Workshop management app",
            subtitle = "Live \"Who is in the Building\" feed — coming later."
        ) { StatusChip("Planned", JackColors.Gray) }
    }

    if (showLogsDialog) {
        AttendanceLogsDialog(
            context = context,
            onDismiss = { showLogsDialog = false }
        )
    }
}
