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
import androidx.activity.result.contract.ActivityResultContracts
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

enum class TransportMode {
    USB,
    BLE
}

data class AlertRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val triggerText: String,
    val toneUri: String
)

data class ToneOption(
    val title: String,
    val uri: String
)

class MainActivity : ComponentActivity() {

    private companion object {
        const val PREF_NAME = "esp32_prefs"
        const val PREF_KEY_RULES = "alert_rules_json"
    }

    private var connectionManager: ConnectionManager? = null
    private var dataCollectionJob: Job? = null

    // Compose state exposed to UI
    private var _connectionStatus = mutableStateOf(ConnectionState.DISCONNECTED)
    private var _deviceName = mutableStateOf("No Device Connected")
    private var _motionState = mutableStateOf(false)
    private var _pixelCount = mutableStateOf("0")
    private var _logLines = mutableStateListOf<String>()
    private var _transportMode = mutableStateOf(TransportMode.USB)
    private var _hasUsbHost = false

    // Custom Alert Rules (persisted)
    private lateinit var _alertRules: SnapshotStateList<AlertRule>

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

    private var lastBatteryCommandSent: String? = null
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val pct = (level * 100 / scale.toFloat()).toInt()
                    val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    val minThreshold = prefs.getInt("pref_min_battery", 20)
                    val maxThreshold = prefs.getInt("pref_max_battery", 80)

                    if (pct <= minThreshold && lastBatteryCommandSent != "CHARGE") {
                        lastBatteryCommandSent = "CHARGE"
                        val cmd = "CHARGE\n"
                        connectionManager?.sendData(cmd.toByteArray())
                        addLog("⚡ Battery low ($pct% <= min $minThreshold%). Sent 'CHARGE' command.")
                    } else if (pct >= maxThreshold && lastBatteryCommandSent != "DISCHARGE") {
                        lastBatteryCommandSent = "DISCHARGE"
                        val cmd = "DISCHARGE\n"
                        connectionManager?.sendData(cmd.toByteArray())
                        addLog("🔋 Battery full ($pct% >= max $maxThreshold%). Sent 'DISCHARGE' command.")
                    }
                }
            }
        }
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
        enableEdgeToEdge()

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

        _hasUsbHost = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        _transportMode.value = if (_hasUsbHost) TransportMode.USB else TransportMode.BLE

        _alertRules = loadAlertRules()
        setupConnectionManager(_transportMode.value)

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

        // Register battery and screen status receivers
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, screenFilter)

        setContent {
            JackTheme {
                var showAddAlertDialog by remember { mutableStateOf(false) }
                var showConfigDialog by remember { mutableStateOf(false) }
                var showSettingsDialog by remember { mutableStateOf(false) }
                var showAlertsDialog by remember { mutableStateOf(false) }
                var showTerminalDialog by remember { mutableStateOf(false) }
                var showAttendanceDialog by _showAttendanceDialog
                var showAdminPinDialog by remember { mutableStateOf(false) }
                var isAdminAuthenticated by remember { mutableStateOf(false) }
                var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

                val requireAdmin: (() -> Unit) -> Unit = { action ->
                    if (isAdminAuthenticated) {
                        action()
                    } else {
                        pendingAction = action
                        showAdminPinDialog = true
                    }
                }

                val blePermissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val allGranted = permissions.entries.all { it.value }
                    if (allGranted) {
                        connectionManager?.connect()
                        addLog("✅ BLE permissions granted. Connecting...")
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
                            connectionManager?.connect()
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
                            onMarkAttendance = { showAttendanceDialog = true }
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
                                _transportMode.value = newMode
                                setupConnectionManager(newMode)
                                addLog("🔄 Switched transport mode to: $newMode")
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
                                isAdminAuthenticated = true
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
                            onSubmit = { attendeeId ->
                                val cmd = "ATTENDANCE:$attendeeId\n"
                                connectionManager?.sendData(cmd.toByteArray())
                                addLog("✅ Attendance marked for ID: $attendeeId (Selfie captured)")
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
                                _alertRules.remove(rule)
                                saveAlertRules()
                                addLog("🗑️ Removed alert rule: '${rule.name}'")
                            },
                            onDismiss = { showAlertsDialog = false }
                        )
                    }

                    if (showSettingsDialog) {
                        SettingsDialog(
                            context = LocalContext.current,
                            onDismiss = { showSettingsDialog = false }
                        )
                    }

                    if (showTerminalDialog) {
                        TerminalLogsDialog(
                            logLines = _logLines,
                            onClearLogs = { _logLines.clear() },
                            onDismiss = { showTerminalDialog = false }
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
                                _alertRules.add(AlertRule(name = name, triggerText = trigger, toneUri = uri))
                                saveAlertRules()
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

    private fun setupConnectionManager(mode: TransportMode) {
        dataCollectionJob?.cancel()
        (connectionManager as? UsbConnectionManager)?.release()
        (connectionManager as? BleConnectionManager)?.disconnect()

        connectionManager = if (mode == TransportMode.USB) {
            UsbConnectionManager(this, lifecycleScope)
        } else {
            BleConnectionManager(this, lifecycleScope)
        }
        connectionManager?.connect()

        // Observe connection state
        lifecycleScope.launch {
            connectionManager?.connectionState?.collectLatest { state ->
                _connectionStatus.value = state
            }
        }

        // Observe device name
        lifecycleScope.launch {
            connectionManager?.deviceName?.collectLatest { name ->
                _deviceName.value = name
            }
        }

        // Observe incoming data stream
        dataCollectionJob = lifecycleScope.launch {
            connectionManager?.dataReceivedFlow?.collectLatest { bytes ->
                val receivedStr = String(bytes, Charset.forName("UTF-8"))
                processIncomingData(receivedStr)
            }
        }
    }

    private val stringBuilder = StringBuilder()
    private fun processIncomingData(chunk: String) {
        stringBuilder.append(chunk)
        val currentText = stringBuilder.toString()

        var lineEnd = stringBuilder.indexOfAny(charArrayOf('\n', '\r'))
        while (lineEnd >= 0) {
            var line = stringBuilder.substring(0, lineEnd)
            var removeCount = 1
            if (lineEnd + 1 < stringBuilder.length) {
                val nextChar = stringBuilder[lineEnd + 1]
                if ((stringBuilder[lineEnd] == '\r' && nextChar == '\n') || (stringBuilder[lineEnd] == '\n' && nextChar == '\r')) {
                    removeCount = 2
                }
            }
            stringBuilder.delete(0, lineEnd + removeCount)
            line = line.trim { it <= ' ' }
            if (line.isNotEmpty()) {
                addLog(line)
                evaluateAlertRules(line)
            }
            lineEnd = stringBuilder.indexOfAny(charArrayOf('\n', '\r'))
        }

        if (stringBuilder.length > 256 || currentText.contains("MOTION", ignoreCase = true) || currentText.contains("motion", ignoreCase = true) || currentText.contains("Pixels", ignoreCase = true)) {
            val trimmed = currentText.trim { it <= ' ' }
            if (trimmed.isNotEmpty()) {
                addLog(trimmed)
                evaluateAlertRules(trimmed)
                stringBuilder.clear()
            }
        }
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

    private fun loadAlertRules(): SnapshotStateList<AlertRule> {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(PREF_KEY_RULES, null)
        val list = mutableStateListOf<AlertRule>()

        if (!jsonStr.isNullOrEmpty()) {
            try {
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optString("id", UUID.randomUUID().toString())
                    val name = obj.getString("name")
                    val trigger = obj.getString("triggerText")
                    val toneUri = obj.getString("toneUri")
                    list.add(AlertRule(id = id, name = name, triggerText = trigger, toneUri = toneUri))
                }
            } catch (_: Exception) {}
        }

        if (list.isEmpty()) {
            list.add(
                AlertRule(
                    name = "Motion Detected",
                    triggerText = "MOTION DETECTED!",
                    toneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()
                )
            )
        }
        return list
    }

    private fun saveAlertRules() {
        try {
            val jsonArray = JSONArray()
            for (rule in _alertRules) {
                val obj = JSONObject().apply {
                    put("id", rule.id)
                    put("name", rule.name)
                    put("triggerText", rule.triggerText)
                    put("toneUri", rule.toneUri)
                }
                jsonArray.put(obj)
            }
            val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_KEY_RULES, jsonArray.toString()).apply()
        } catch (_: Exception) {}
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
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        (connectionManager as? UsbConnectionManager)?.release()
        connectionManager?.disconnect()
    }

    private fun evaluateAlertRules(line: String) {
        for (rule in _alertRules) {
            if (line.contains(rule.triggerText, ignoreCase = true)) {
                playTone(rule.toneUri)
                if (rule.triggerText.contains("MOTION", ignoreCase = true) || rule.name.contains("Motion", ignoreCase = true)) {
                    _motionState.value = true
                    extractPixelValue(line)
                } else if (line.contains("No motion", ignoreCase = true)) {
                    _motionState.value = false
                    extractPixelValue(line)
                }
                break
            }
        }
        if (line.contains("No motion", ignoreCase = true)) {
            _motionState.value = false
            extractPixelValue(line)
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Jack::AlertScreenWakeLock"
            )
            wakeLock.acquire(4000L)
        } catch (_: Exception) {}
    }

    private fun playTone(uriStr: String) {
        wakeUpScreen()
        try {
            val uri = uriStr.toUri()
            val ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
        } catch (_: Exception) {
            try {
                val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(applicationContext, defaultUri)?.play()
            } catch (_: Exception) {}
        }
    }

    private fun extractPixelValue(line: String) {
        try {
            val idx = line.indexOf("Pixels:")
            if (idx != -1) {
                val valueStr = line.substring(idx + 7).trim().takeWhile { it.isDigit() || it == '.' }
                if (valueStr.isNotEmpty()) {
                    _pixelCount.value = valueStr
                }
            }
        } catch (_: Exception) {}
    }

    private fun addLog(message: String) {
        val timestamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        _logLines.add("[$timestamp] $message")
        if (_logLines.size > 500) {
            _logLines.removeAt(0)
        }
    }
}

@Composable
fun AdminPinDialog(
    context: Context,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    val prefs = remember { context.getSharedPreferences("esp32_prefs", Context.MODE_PRIVATE) }
    val adminPin = prefs.getString("pref_admin_pin", "1234") ?: "1234"
    var enteredPin by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

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
                    text = "Please enter Admin PIN to access management features (Default: 1234).",
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
                            text = if (enteredPin.isEmpty()) "Enter 4-digit PIN..." else "*".repeat(enteredPin.length),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (enteredPin.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (showError) {
                    Text("Incorrect PIN. Try again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                CustomKeypad(
                    isNumericOnly = true,
                    onCharClick = { digit ->
                        if (enteredPin.length < 6) {
                            enteredPin += digit
                            showError = false
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
                    if (enteredPin == adminPin) {
                        onSuccess()
                    } else {
                        showError = true
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

fun saveBitmapToFile(context: Context, bitmap: android.graphics.Bitmap): File? {
    return try {
        val file = File(context.filesDir, "attendance_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        }
        file
    } catch (_: Exception) {
        null
    }
}

@Composable
fun CustomKeypad(
    onCharClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit
) {
    val buttons = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("C", "0", "⌫")
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        buttons.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { label ->
                    Button(
                        onClick = {
                            when (label) {
                                "C" -> onClear()
                                "⌫" -> onBackspace()
                                else -> onCharClick(label)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when (label) {
                                "C", "⌫" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = when (label) {
                                "C", "⌫" -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AttendanceDialog(
    onDismiss: () -> Unit,
    onSubmit: (attendeeId: String) -> Unit
) {
    val context = LocalContext.current
    val db = remember { AttendanceDatabase(context) }
    var attendeeId by remember { mutableStateOf("") }

    val matchedUser = remember(attendeeId) {
        if (attendeeId.isBlank()) null else db.getUserByAttendeeId(attendeeId.trim())
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val bitmap = result.data?.extras?.get("data") as? android.graphics.Bitmap
            if (bitmap != null) {
                if (matchedUser != null) {
                    val file = saveBitmapToFile(context, bitmap)
                    db.insertRecord(matchedUser.attendeeId, file?.absolutePath)
                    onSubmit(matchedUser.attendeeId)
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra("android.intent.extras.CAMERA_FACING", 1)
                putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                putExtra("camerafacing", "front")
            }
            cameraLauncher.launch(intent)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Mark Attendance", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Enter your Attendee ID using the keypad below and take a selfie.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Read-only ID & Name Preview Box
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, if (matchedUser != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (attendeeId.isEmpty()) "Tap ID digits..." else attendeeId,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (attendeeId.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (attendeeId.isNotBlank()) {
                            if (matchedUser != null) {
                                Text(
                                    text = "✅ ${matchedUser.name} [${matchedUser.tag}]",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50)
                                )
                            } else {
                                Text(
                                    text = "⚠️ ID not registered in database",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                // Custom On-Screen Keypad
                CustomKeypad(
                    onCharClick = { digit -> attendeeId += digit },
                    onBackspace = { if (attendeeId.isNotEmpty()) attendeeId = attendeeId.dropLast(1) },
                    onClear = { attendeeId = "" }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (matchedUser != null) {
                        val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                            val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                                putExtra("android.intent.extras.CAMERA_FACING", 1)
                                putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                                putExtra("camerafacing", "front")
                            }
                            cameraLauncher.launch(intent)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                },
                enabled = matchedUser != null,
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Take Selfie & Submit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun UserManagementDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val db = remember { AttendanceDatabase(context) }
    var users by remember { mutableStateOf(db.getAllUsers()) }

    var showAddUserDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Workers & Subscribers (${users.size})", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { showAddUserDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add New User / Worker")
                }

                if (users.isEmpty()) {
                    Text(
                        text = "No users or subscribers registered yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(users) { user ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = user.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = when (user.tag.lowercase()) {
                                                    "worker" -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                                                    "subscriber" -> Color(0xFF2196F3).copy(alpha = 0.15f)
                                                    else -> MaterialTheme.colorScheme.primaryContainer
                                                }
                                            ) {
                                                Text(
                                                    text = user.tag,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when (user.tag.lowercase()) {
                                                        "worker" -> Color(0xFF4CAF50)
                                                        "subscriber" -> Color(0xFF2196F3)
                                                        else -> MaterialTheme.colorScheme.primary
                                                    },
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Attendee ID: ${user.attendeeId}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            db.deleteUser(user.id)
                                            users = db.getAllUsers()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("Close")
            }
        }
    )

    if (showAddUserDialog) {
        AddUserDialog(
            context = context,
            onDismiss = { showAddUserDialog = false },
            onUserAdded = {
                users = db.getAllUsers()
                showAddUserDialog = false
            }
        )
    }
}

@Composable
fun AddUserDialog(
    context: Context,
    onDismiss: () -> Unit,
    onUserAdded: () -> Unit
) {
    val db = remember { AttendanceDatabase(context) }
    var name by remember { mutableStateOf("") }
    var attendeeId by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("Worker") }
    var activeField by remember { mutableStateOf("name") }

    val tagsList = listOf("Worker", "Subscriber", "Admin", "Guest", "Staff")
    var tagExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Add User / Worker / Subscriber", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Name Box
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { activeField = "name" },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(2.dp, if (activeField == "name") MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Full Name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = if (name.isEmpty()) "Tap to type name..." else name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (name.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Attendee ID Box
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { activeField = "id" },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(2.dp, if (activeField == "id") MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Attendee ID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = if (attendeeId.isEmpty()) "Tap to type ID..." else attendeeId,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (attendeeId.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Tag Dropdown
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { tagExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Tag: $tag", fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(
                        expanded = tagExpanded,
                        onDismissRequest = { tagExpanded = false }
                    ) {
                        tagsList.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t) },
                                onClick = {
                                    tag = t
                                    tagExpanded = false
                                }
                            )
                        }
                    }
                }

                // Global Custom Touch Keypad
                CustomKeypad(
                    isNumericOnly = false,
                    onCharClick = { ch ->
                        if (activeField == "name") name += ch else attendeeId += ch
                    },
                    onBackspace = {
                        if (activeField == "name" && name.isNotEmpty()) name = name.dropLast(1)
                        else if (activeField == "id" && attendeeId.isNotEmpty()) attendeeId = attendeeId.dropLast(1)
                    },
                    onClear = {
                        if (activeField == "name") name = "" else attendeeId = ""
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && attendeeId.isNotBlank()) {
                        db.addUser(name.trim(), attendeeId.trim(), tag)
                        onUserAdded()
                    }
                },
                enabled = name.isNotBlank() && attendeeId.isNotBlank(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save User")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun CustomKeypad(
    onCharClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    isNumericOnly: Boolean = false
) {
    var isAlphaMode by remember { mutableStateOf(!isNumericOnly) }

    val alphaRows = listOf(
        listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
        listOf("Z", "X", "C", "V", "B", "N", "M", "⌫"),
        listOf(if (isAlphaMode) "123" else "ABC", " ", "C")
    )

    val numericRows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("C", "0", "⌫")
    )

    val rows = if (isNumericOnly || !isAlphaMode) numericRows else alphaRows

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { label ->
                    val weight = if (label == " ") 2f else 1f
                    Button(
                        onClick = {
                            when (label) {
                                "C" -> onClear()
                                "⌫" -> onBackspace()
                                "123" -> isAlphaMode = false
                                "ABC" -> isAlphaMode = true
                                else -> onCharClick(label)
                            }
                        },
                        modifier = Modifier
                            .weight(weight)
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when (label) {
                                "C", "⌫" -> MaterialTheme.colorScheme.errorContainer
                                "123", "ABC" -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = when (label) {
                                "C", "⌫" -> MaterialTheme.colorScheme.onErrorContainer
                                "123", "ABC" -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = if (label == " ") "Space" else label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalLogsDialog(
    logLines: List<String>,
    onClearLogs: () -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Serial Terminal Logs", fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onClearLogs, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear Logs",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        text = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E1E1E),
                tonalElevation = 4.dp
            ) {
                if (logLines.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Waiting for data stream...",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logLines) { line ->
                            val isAlert = line.contains("MOTION", ignoreCase = true) || line.contains("WARNING", ignoreCase = true)
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = if (isAlert) Color(0xFFFF5252) else Color(0xFF00E676)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("Close")
            }
        }
    )
}

@Composable
fun AlertsConfigDialog(
    alertRules: List<AlertRule>,
    onAddClick: () -> Unit,
    onDeleteRule: (AlertRule) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Manage Alerts (${alertRules.size})", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (alertRules.isEmpty()) {
                    Text(
                        text = "No custom alerts configured.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(alertRules) { rule ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = rule.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Trigger: \"${rule.triggerText}\"",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteRule(rule) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Rule",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = onAddClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add New Alert")
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("Close")
            }
        }
    )
}

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
fun AttendanceLogsDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val db = remember { AttendanceDatabase(context) }
    var records by remember { mutableStateOf(db.getAllRecords()) }

    LaunchedEffect(Unit) {
        records = db.getAllRecords()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Attendance Logs (${records.size})", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (records.isEmpty()) {
                    Text(
                        text = "No attendance records stored locally yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(records) { record ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        val bitmap = remember(record.selfiePath) {
                                            if (!record.selfiePath.isNullOrEmpty()) {
                                                try {
                                                    android.graphics.BitmapFactory.decodeFile(record.selfiePath)
                                                } catch (_: Exception) { null }
                                            } else null
                                        }

                                        if (bitmap != null) {
                                            androidx.compose.foundation.Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = "Selfie",
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(CircleShape),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                            )
                                        }

                                        Column {
                                            Text(
                                                text = "ID: ${record.attendeeId}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = record.timestamp,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (record.synced) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color(0xFFFFC107).copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = if (record.synced) "Synced" else "Offline",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (record.synced) Color(0xFF4CAF50) else Color(0xFFFFC107),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("Close")
            }
        }
    )
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
    var bgServiceEnabled by remember { mutableStateOf(true) }

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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Jack Settings", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
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
                    // Section 5: Offline Attendance & Cloud Sync
                    val db = remember { AttendanceDatabase(context) }
                    var unsyncCount by remember { mutableStateOf(db.getUnsyncedCount()) }
                    var showLogsDialog by remember { mutableStateOf(false) }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("5. Offline Attendance Logs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Unsynced records stored locally: $unsyncCount", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showLogsDialog = true },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("View Logs")
                                }
                                Button(
                                    onClick = {
                                        db.markAllSynced()
                                        unsyncCount = db.getUnsyncedCount()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Sync to Cloud")
                                }
                            }
                        }
                    }

                    if (showLogsDialog) {
                        AttendanceLogsDialog(
                            context = context,
                            onDismiss = { showLogsDialog = false }
                        )
                    }
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
                                        Text("Ping ESP32", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
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
                            Text("Android SDK: ${Build.VERSION.SDK_INT}", style = MaterialTheme.typography.bodySmall)
                            Text("Foreground Service: RUNNING", style = MaterialTheme.typography.bodySmall)
                            Text("Boot Receiver: REGISTERED", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                item {
                    // Section 8: Users, Workers & Subscribers Management
                    val db = remember { AttendanceDatabase(context) }
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
                    // Section 9: Test & Reset Utilities
                    val db = remember { AttendanceDatabase(context) }
                    var clearStatus by remember { mutableStateOf<String?>(null) }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("9. Test & Reset Utilities", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val dbWritable = db.writableDatabase
                                        dbWritable.execSQL("DELETE FROM attendance_records")
                                        clearStatus = "Attendance records cleared!"
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Clear Attendance", color = MaterialTheme.colorScheme.error)
                                }
                                Button(
                                    onClick = {
                                        val dbWritable = db.writableDatabase
                                        dbWritable.execSQL("DELETE FROM attendance_records")
                                        dbWritable.execSQL("DELETE FROM users")
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectionConfigDialog(
    connectionStatus: ConnectionState,
    deviceName: String,
    transportMode: TransportMode,
    hasUsbHost: Boolean,
    onTransportChange: (TransportMode) -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val isConnected = connectionStatus == ConnectionState.CONNECTED

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Power,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Connection Settings", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text("Status: ${connectionStatus.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Device: $deviceName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                HorizontalDivider()

                Text("Select Transport Mode", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = transportMode == TransportMode.USB,
                        onClick = { onTransportChange(TransportMode.USB) },
                        enabled = hasUsbHost,
                        label = { Text("USB Serial") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = transportMode == TransportMode.BLE,
                        onClick = { onTransportChange(TransportMode.BLE) },
                        label = { Text("Bluetooth BLE") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isConnected) {
                    Button(
                        onClick = {
                            onDisconnectClick()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    Button(
                        onClick = {
                            onConnectClick()
                            onDismiss()
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (transportMode == TransportMode.BLE) "Scan & Connect" else "Connect USB")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAlertDialog(
    existingRules: List<AlertRule>,
    onDismiss: () -> Unit,
    onSave: (name: String, trigger: String, toneUri: String) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var triggerText by remember { mutableStateOf("") }
    var activeField by remember { mutableStateOf("name") }
    var expanded by remember { mutableStateOf(false) }

    var tonesList by remember { mutableStateOf<List<ToneOption>>(emptyList()) }
    var isLoadingTones by remember { mutableStateOf(true) }
    var selectedTone by remember { mutableStateOf<ToneOption?>(null) }
    var currentRingtone by remember { mutableStateOf<android.media.Ringtone?>(null) }

    LaunchedEffect(Unit) {
        val loadedTones = withContext(Dispatchers.IO) {
            val list = mutableListOf<ToneOption>()
            try {
                val ringtoneManager = RingtoneManager(context)
                ringtoneManager.setType(RingtoneManager.TYPE_ALL)
                val cursor: Cursor = ringtoneManager.cursor
                while (cursor.moveToNext()) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    val uriStr = ringtoneManager.getRingtoneUri(cursor.position).toString()
                    list.add(ToneOption(title = title, uri = uriStr))
                }
            } catch (_: Exception) {}
            if (list.isEmpty()) {
                list.add(ToneOption("Default Notification", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()))
            }
            list
        }
        tonesList = loadedTones
        selectedTone = loadedTones.firstOrNull()
        isLoadingTones = false
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)

                var displayName = "Custom Audio File"
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            displayName = it.getString(nameIndex)
                        }
                    }
                }

                val customTone = ToneOption(title = "🎵 $displayName", uri = uri.toString())
                tonesList = listOf(customTone) + tonesList
                selectedTone = customTone

                currentRingtone?.stop()
                val ringtone = RingtoneManager.getRingtone(context, uri)
                currentRingtone = ringtone
                ringtone?.play()
            } catch (_: Exception) {}
        }
    }

    val trimmedTrigger = triggerText.trim()
    val isDuplicate = existingRules.any { it.triggerText.equals(trimmedTrigger, ignoreCase = true) }
    val canSave = name.isNotBlank() && trimmedTrigger.isNotBlank() && !isDuplicate

    DisposableEffect(Unit) {
        onDispose {
            try {
                currentRingtone?.stop()
            } catch (_: Exception) {}
        }
    }

    AlertDialog(
        onDismissRequest = {
            try {
                currentRingtone?.stop()
            } catch (_: Exception) {}
            onDismiss()
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Add Custom Alert Rule", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Alert Name Box
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { activeField = "name" },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(2.dp, if (activeField == "name") MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Alert Name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = if (name.isEmpty()) "Tap to type name..." else name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (name.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Trigger Text Box
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { activeField = "triggerText" },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(2.dp, if (activeField == "triggerText") MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("ESP32 Trigger Text", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = if (triggerText.isEmpty()) "Tap to type trigger..." else triggerText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (triggerText.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (isDuplicate) {
                    Text("An alert with this trigger string already exists!", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                // Custom Touch Keypad
                CustomKeypad(
                    isNumericOnly = false,
                    onCharClick = { ch ->
                        if (activeField == "name") name += ch else triggerText += ch
                    },
                    onBackspace = {
                        if (activeField == "name" && name.isNotEmpty()) name = name.dropLast(1)
                        else if (activeField == "triggerText" && triggerText.isNotEmpty()) triggerText = triggerText.dropLast(1)
                    },
                    onClear = {
                        if (activeField == "name") name = "" else triggerText = ""
                    }
                )

                ExposedDropdownMenuBox(
                    expanded = expanded && !isLoadingTones,
                    onExpandedChange = { if (!isLoadingTones) expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = if (isLoadingTones) "Loading system tones..." else (selectedTone?.title ?: "Select Tone"),
                        onValueChange = {},
                        readOnly = true,
                        shape = RoundedCornerShape(12.dp),
                        leadingIcon = {
                            if (isLoadingTones) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = !isLoadingTones)
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("📁 Browse Audio File...", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                expanded = false
                                audioPickerLauncher.launch(arrayOf("audio/*"))
                            }
                        )
                        HorizontalDivider()

                        tonesList.forEach { tone ->
                            DropdownMenuItem(
                                text = { Text(tone.title) },
                                onClick = {
                                    selectedTone = tone
                                    expanded = false
                                    try {
                                        currentRingtone?.stop()
                                        val uri = tone.uri.toUri()
                                        val ringtone = RingtoneManager.getRingtone(context, uri)
                                        currentRingtone = ringtone
                                        ringtone?.play()
                                    } catch (_: Exception) {}
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canSave) {
                        try {
                            currentRingtone?.stop()
                        } catch (_: Exception) {}
                        onSave(name.trim(), trimmedTrigger, selectedTone?.uri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString())
                    }
                },
                enabled = canSave,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Add Alert")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    try {
                        currentRingtone?.stop()
                    } catch (_: Exception) {}
                    onDismiss()
                }
            ) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ESP32SerialScreen(
    modifier: Modifier = Modifier,
    connectionStatus: ConnectionState,
    alertRules: List<AlertRule>,
    onConnectClick: () -> Unit,
    onOpenConfigDialog: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenTerminal: () -> Unit,
    onMarkAttendance: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = "Jack",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Serial Terminal Logs Icon
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { onOpenTerminal() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Serial Terminal Logs",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Alerts Bell Icon with Green Dot if rules exist
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onOpenAlerts() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Manage Alerts",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    if (alertRules.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = (-4).dp, y = 4.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                        )
                    }
                }

                // Settings Gear Icon
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { onOpenSettings() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Fused Connection Pill with Plug Icon & Status
                val pillBgColor = when (connectionStatus) {
                    ConnectionState.CONNECTED -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                    ConnectionState.CONNECTING, ConnectionState.SCANNING -> Color(0xFFFFC107).copy(alpha = 0.15f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }

                val pillBorderColor = when (connectionStatus) {
                    ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                    ConnectionState.CONNECTING, ConnectionState.SCANNING -> Color(0xFFFFC107)
                    else -> Color.Transparent
                }

                val plugTint = when (connectionStatus) {
                    ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                    ConnectionState.CONNECTING, ConnectionState.SCANNING -> Color(0xFFFFC107)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = pillBgColor,
                    border = BorderStroke(1.dp, pillBorderColor),
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onConnectClick() },
                            onLongClick = { onOpenConfigDialog() }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Power,
                            contentDescription = "Connection Settings",
                            tint = plugTint,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = connectionStatus.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = plugTint,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Mark Attendance Action Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Workshop Attendance",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tap to record attendance on connected device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onMarkAttendance,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("MARK ATTENDANCE", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Who is in the Building Roster Card
        WhoIsInTheBuildingCard()
    }
}

@Composable
fun WhoIsInTheBuildingCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { AttendanceDatabase(context) }
    var records by remember { mutableStateOf(db.getAllRecords()) }

    LaunchedEffect(Unit) {
        records = db.getAllRecords()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.People,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Who is in the Building",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${records.size} Checked In",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (records.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No attendance records yet. Tap Mark Attendance above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(records) { record ->
                        val user = remember(record.attendeeId) {
                            db.getUserByAttendeeId(record.attendeeId)
                        }
                        val bitmap = remember(record.selfiePath) {
                            if (!record.selfiePath.isNullOrEmpty()) {
                                try {
                                    android.graphics.BitmapFactory.decodeFile(record.selfiePath)
                                } catch (_: Exception) { null }
                            } else null
                        }

                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.width(150.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (bitmap != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Selfie",
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(CircleShape),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                } else {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(64.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = user?.name ?: "ID: ${record.attendeeId}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (user != null) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = when (user.tag.lowercase()) {
                                            "worker" -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                                            "subscriber" -> Color(0xFF2196F3).copy(alpha = 0.15f)
                                            else -> MaterialTheme.colorScheme.primaryContainer
                                        },
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = user.tag,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = when (user.tag.lowercase()) {
                                                "worker" -> Color(0xFF4CAF50)
                                                "subscriber" -> Color(0xFF2196F3)
                                                else -> MaterialTheme.colorScheme.primary
                                            },
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = record.timestamp.takeLast(8),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
