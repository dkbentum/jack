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

enum class TransportMode {
    USB,
    BLE
}

// AlertRule lives in AlertRules.kt (with the matching logic).

data class ToneOption(
    val title: String,
    val uri: String
)

class MainActivity : ComponentActivity() {

    private companion object {
        const val PREF_NAME = JackCore.PREF_NAME
    }

    // The connection and its state live in JackCore (kept alive by the background service),
    // so this screen can be closed or recreated without dropping the ESP32.
    private val connectionManager: ConnectionManager? get() = JackCore.connectionManager
    private val _connectionStatus get() = JackCore.connectionStatus
    private val _deviceName get() = JackCore.deviceName
    private val _logLines get() = JackCore.logLines
    private val _transportMode get() = JackCore.transportMode
    private val _hasUsbHost get() = JackCore.hasUsbHost
    private val _alertRules get() = JackCore.alertRules

    private fun addLog(message: String) = JackCore.addLog(message)

    private var _showAttendanceDialog = mutableStateOf(false)
    private var _volumeShortcutFeedback = mutableStateOf<Pair<String, Color>?>(null)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val shortcutsEnabled = prefs.getBoolean("pref_hardware_shortcuts", true)
        if (shortcutsEnabled) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    _showAttendanceDialog.value = true
                    _volumeShortcutFeedback.value = "Vol Up: Attendance Triggered" to Color(0xFF4CAF50)
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(2500)
                        if (_volumeShortcutFeedback.value?.first?.contains("Vol Up") == true) {
                            _volumeShortcutFeedback.value = null
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val cmd = "PING\n"
                    connectionManager?.sendData(cmd.toByteArray())
                    addLog("📡 Sent 'PING' command to ESP32.")
                    _volumeShortcutFeedback.value = "Vol Down: Ping Sent" to Color(0xFF2196F3)
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(2500)
                        if (_volumeShortcutFeedback.value?.first?.contains("Vol Down") == true) {
                            _volumeShortcutFeedback.value = null
                        }
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_USER_PRESENT) {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                try {
                    context?.startActivity(launchIntent)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light theme: keep dark status/navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Start (or re-attach to) the always-on core that owns the ESP32 connection.
        JackCore.start(this)

        val kioskManager = KioskManager(this)
        val isOwner = kioskManager.isDeviceOwner
        Log.d("DedicatedDevice", "Build type: ${BuildConfig.BUILD_TYPE}, Debug: ${BuildConfig.DEBUG}")
        Log.d("DedicatedDevice", "Device Owner: $isOwner")

        if (isOwner) {
            kioskManager.configureKiosk()
            if (!BuildConfig.DEBUG) {
                kioskManager.startLockTask(this)
                Log.d("DedicatedDevice", "Release build: Lock Task enforced automatically.")
            } else {
                Log.d("DedicatedDevice", "Debug build: Skipping automatic Lock Task enforcement for easy debugging and redeployment.")
            }
        }

        // Start 24/7 background foreground service automatically
        Esp32BackgroundService.startService(this)

        // Battery automation now lives in JackCore; only the screen receiver belongs to this screen.
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, screenFilter)

        setContent {
            JackBrandTheme {
                var showAddAlertDialog by remember { mutableStateOf(false) }
                var showConfigDialog by remember { mutableStateOf(false) }
                var showSettingsDialog by remember { mutableStateOf(false) }
                var showAlertsDialog by remember { mutableStateOf(false) }
                var showTerminalDialog by remember { mutableStateOf(false) }
                var showAttendanceDialog by _showAttendanceDialog
                var showAdminPinDialog by remember { mutableStateOf(false) }
                var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

                // Admin access locks again as soon as every admin screen is closed…
                val anyAdminScreenOpen = showSettingsDialog || showAlertsDialog || showTerminalDialog || showAddAlertDialog
                LaunchedEffect(anyAdminScreenOpen) {
                    if (!anyAdminScreenOpen) AdminSession.lock()
                }
                // …or after 2 minutes without a touch on an admin screen.
                LaunchedEffect(AdminSession.isUnlocked) {
                    while (AdminSession.isUnlocked) {
                        kotlinx.coroutines.delay(5_000)
                        if (AdminSession.isIdleExpired()) {
                            showSettingsDialog = false
                            showAlertsDialog = false
                            showTerminalDialog = false
                            showAddAlertDialog = false
                            AdminSession.lock()
                            addLog("🔒 Admin screens locked after 2 minutes without use.")
                        }
                    }
                }

                val requireAdmin: (() -> Unit) -> Unit = { action ->
                    if (AdminSession.isUnlocked) {
                        AdminSession.touch()
                        action()
                    } else {
                        pendingAction = action
                        showAdminPinDialog = true
                    }
                }

                // Result of Google's one-tap "Turn on location?" dialog.
                val locationSettingsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    if (result.resultCode == android.app.Activity.RESULT_OK) {
                        addLog("📍 Location turned on. Connecting...")
                        connectionManager?.connect()
                    } else {
                        addLog("⚠️ Location is still off — BLE can't find the ESP32 on Android ${Build.VERSION.RELEASE} without it.")
                    }
                }

                // Makes sure Location is on (Android 6–11 only), then starts the BLE connection.
                val connectBleWithLocation: () -> Unit = {
                    if (!LocationHelper.needsEnablingForBle(this@MainActivity)) {
                        connectionManager?.connect()
                    } else if (LocationHelper.enableAsDeviceOwner(this@MainActivity)) {
                        addLog("📍 Location turned on automatically (device owner). Connecting...")
                        connectionManager?.connect()
                    } else {
                        addLog("📍 Location is off. Asking to turn it on (required for BLE on Android ${Build.VERSION.RELEASE})...")
                        requestLocationOn(locationSettingsLauncher)
                    }
                }

                val blePermissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val allGranted = permissions.entries.all { it.value }
                    if (allGranted) {
                        addLog("✅ BLE permissions granted. Connecting...")
                        connectBleWithLocation()
                    } else {
                        addLog("❌ BLE permissions denied.")
                    }
                }

                val performConnect = {
                    if (_transportMode.value == TransportMode.BLE) {
                        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                        val adapter = bluetoothManager.adapter
                        if (adapter == null || !adapter.isEnabled) {
                            addLog("⚠️ Please turn on Bluetooth to connect via BLE.")
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            try {
                                startActivity(enableBtIntent)
                            } catch (_: SecurityException) {
                                addLog("❌ Bluetooth permission required to enable Bluetooth.")
                            } catch (_: Exception) {}
                        } else if (hasRequiredBlePermissions()) {
                            connectBleWithLocation()
                        } else {
                            requestBlePermissions(blePermissionsLauncher)
                        }
                    } else {
                        connectionManager?.connect()
                    }
                }

                val feedback by _volumeShortcutFeedback

                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        ESP32SerialScreen(
                            modifier = Modifier.padding(innerPadding),
                            connectionStatus = _connectionStatus.value,
                            alertRules = _alertRules,
                            onConnectClick = { performConnect() },
                            onOpenConfigDialog = { showConfigDialog = true },
                            onOpenSettings = { requireAdmin { showSettingsDialog = true } },
                            onOpenAlerts = { requireAdmin { showAlertsDialog = true } },
                            onOpenTerminal = { requireAdmin { showTerminalDialog = true } },
                            onMarkAttendance = { showAttendanceDialog = true },
                            deviceName = _deviceName.value,
                            transportLabel = if (_transportMode.value == TransportMode.BLE) "Bluetooth" else "USB"
                        )

                        if (feedback != null) {
                            Surface(
                                color = feedback!!.second,
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 40.dp)
                                    .padding(horizontal = 24.dp),
                                shadowElevation = 8.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = feedback!!.first,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    if (showConfigDialog) {
                        ConnectionConfigDialog(
                            connectionStatus = _connectionStatus.value,
                            deviceName = _deviceName.value,
                            transportMode = _transportMode.value,
                            hasUsbHost = _hasUsbHost,
                            onTransportChange = { newMode ->
                                if (newMode == TransportMode.USB && !_hasUsbHost) {
                                    addLog("⚠️ USB Host/OTG is not supported on this device.")
                                    return@ConnectionConfigDialog
                                }
                                JackCore.switchTransport(newMode)
                            },
                            onConnectClick = { performConnect() },
                            onDisconnectClick = { connectionManager?.disconnect() },
                            onDismiss = { showConfigDialog = false }
                        )
                    }

                    if (showAdminPinDialog) {
                        AdminPinDialog(
                            context = LocalContext.current,
                            onSuccess = {
                                showAdminPinDialog = false
                                pendingAction?.invoke()
                                pendingAction = null
                            },
                            onDismiss = {
                                showAdminPinDialog = false
                                pendingAction = null
                            }
                        )
                    }

                    if (showAttendanceDialog) {
                        AttendanceDialog(
                            onDismiss = { showAttendanceDialog = false },
                            onCheckedIn = { attendeeId ->
                                JackCore.sendCommand("ATTENDANCE:$attendeeId")
                                addLog("✅ Checked in: ID $attendeeId (selfie captured)")
                                showAttendanceDialog = false
                            },
                            onCheckedOut = { attendeeId ->
                                JackCore.sendCommand("CHECKOUT:$attendeeId")
                                addLog("👋 Checked out: ID $attendeeId (selfie captured)")
                                showAttendanceDialog = false
                            }
                        )
                    }

                    if (showAlertsDialog) {
                        AlertsConfigDialog(
                            alertRules = _alertRules,
                            onAddClick = {
                                showAlertsDialog = false
                                showAddAlertDialog = true
                            },
                            onDeleteRule = { rule ->
                                JackCore.removeAlertRule(rule)
                                addLog("🗑️ Removed alert rule: '${rule.name}'")
                            },
                            onDismiss = { showAlertsDialog = false }
                        )
                    }

                    if (showSettingsDialog) {
                        SettingsDialog(
                            context = LocalContext.current,
                            onDismiss = { showSettingsDialog = false },
                            // v3.1: Settings stays open underneath; these open on top of it.
                            onOpenConnection = { showConfigDialog = true },
                            onOpenAlerts = { showAlertsDialog = true }
                        )
                    }

                    if (showTerminalDialog) {
                        TerminalLogsDialog(
                            logLines = _logLines,
                            onClearLogs = { JackCore.clearLogs() },
                            onDismiss = { showTerminalDialog = false },
                            // v3.7: send test commands to the ESP32 from the terminal
                            connected = _connectionStatus.value == ConnectionState.CONNECTED,
                            onSendCommand = { cmd ->
                                JackCore.sendCommand(cmd)
                                addLog("📤 Sent: $cmd")
                            }
                        )
                    }

                    if (showAddAlertDialog) {
                        AddAlertDialog(
                            existingRules = _alertRules,
                            onDismiss = {
                                showAddAlertDialog = false
                                showAlertsDialog = true
                            },
                            onSave = { name, trigger, uri ->
                                JackCore.addAlertRule(AlertRule(name = name, triggerText = trigger, toneUri = uri))
                                showAddAlertDialog = false
                                showAlertsDialog = true
                                addLog("➕ Added custom alert rule: '$name' (Trigger: '$trigger')")
                            }
                        )
                    }
                }
            }
        }
    }

    /** Shows Google's one-tap "Turn on location?" dialog; falls back to the Location settings page. */
    private fun requestLocationOn(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L).build())
            .setAlwaysShow(true)
            .build()
        try {
            LocationServices.getSettingsClient(this)
                .checkLocationSettings(request)
                .addOnSuccessListener { connectionManager?.connect() }
                .addOnFailureListener { e ->
                    if (e is ResolvableApiException) {
                        try {
                            launcher.launch(IntentSenderRequest.Builder(e.resolution).build())
                        } catch (_: Exception) {
                            openLocationSettings()
                        }
                    } else {
                        openLocationSettings()  // e.g. no Google Play Services on this device
                    }
                }
        } catch (_: Exception) {
            openLocationSettings()
        }
    }

    private fun openLocationSettings() {
        addLog("📍 Opening Location settings — switch Location on and Jack will connect automatically.")
        connectionManager?.connect()  // keeps retrying in the background, so it connects once Location is on
        try {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (_: Exception) {}
    }

    private fun hasRequiredBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBlePermissions(launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            launcher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ))
        } else {
            launcher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    override fun onResume() {
        super.onResume()
        val kioskManager = KioskManager(this)
        if (kioskManager.isDeviceOwner) {
            kioskManager.startLockTask(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        // Deliberately NOT disconnecting: the ESP32 connection belongs to JackCore and stays up 24/7.
    }

}
