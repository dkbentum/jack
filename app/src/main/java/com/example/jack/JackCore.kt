package com.example.jack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.UUID

/**
 * Jack's always-on core: the ESP32 connection, incoming-data processing, alert rules and
 * battery automation.
 *
 * It lives as long as the app *process* (kept alive 24/7 by [Esp32BackgroundService]),
 * not as long as a screen. MainActivity only displays this state and sends commands through it,
 * so closing, rotating or recreating the screen no longer drops the ESP32 connection.
 *
 * All methods must be called on the main thread.
 */
object JackCore {
    private const val TAG = "JackCore"
    const val PREF_NAME = "esp32_prefs"
    private const val PREF_KEY_RULES = "alert_rules_json"
    private const val PREF_KEY_TRANSPORT = "pref_transport_mode"
    private const val MAX_LOG_LINES = 500
    // Text with no line ending is treated as a complete message once the ESP32 has been quiet this long.
    private const val PARTIAL_LINE_TIMEOUT_MS = 300L
    // When several alerts fire, their sounds play one after another with this gap between them.
    private const val GAP_BETWEEN_TONES_MS = 600L
    // Don't wait longer than this for a (long or looping) sound to finish before playing the next.
    private const val MAX_TONE_WAIT_MS = 5_000L

    private lateinit var app: Context
    private var started = false

    /** Process-wide scope: survives activities being destroyed. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var connectionManager: ConnectionManager? = null
        private set

    // Jobs observing the current connection manager — cancelled whenever it is replaced.
    private val managerJobs = mutableListOf<Job>()

    // ---- State shown by the UI (Compose state, read directly by MainActivity) ----
    val connectionStatus = mutableStateOf(ConnectionState.DISCONNECTED)
    val deviceName = mutableStateOf("No Device Connected")
    val logLines: SnapshotStateList<String> = mutableStateListOf()
    val transportMode = mutableStateOf(TransportMode.USB)
    var hasUsbHost = false
        private set
    lateinit var alertRules: SnapshotStateList<AlertRule>
        private set

    /** One-line status for the background service's notification. */
    private val _statusText = MutableStateFlow("Starting…")
    val statusText: StateFlow<String> = _statusText

    // ---------------------------------------------------------------------------------------------
    // Startup
    // ---------------------------------------------------------------------------------------------

    /** Safe to call many times (service, activity, boot) — only the first call does anything. */
    @MainThread
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext

        hasUsbHost = app.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        transportMode.value = loadTransportMode()
        alertRules = loadAlertRules()
        startTonePlayer()

        addLog("🚀 Jack core started (v${BuildConfig.VERSION_NAME}) — connection runs in the background service.")

        // Android 6–11 can't find BLE devices with Location off. As device owner (kiosk) we can switch it on silently.
        if (transportMode.value == TransportMode.BLE && LocationHelper.needsEnablingForBle(app)) {
            if (LocationHelper.enableAsDeviceOwner(app)) {
                addLog("📍 Location turned on automatically (required for BLE on Android ${Build.VERSION.RELEASE}).")
            } else {
                addLog("⚠️ Location is off — BLE needs it on Android ${Build.VERSION.RELEASE}. Tap connect to turn it on.")
            }
        }

        ContextCompat.registerReceiver(
            app, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setupConnectionManager(transportMode.value)

        // Daily CSV export + Google Sheets sync run in the background alongside the connection.
        AttendanceSync.start(app, scope)
    }

    // ---------------------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------------------

    /** Switches between USB and BLE, remembers the choice and reconnects. */
    @MainThread
    fun switchTransport(mode: TransportMode) {
        transportMode.value = mode
        app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY_TRANSPORT, mode.name).apply()
        setupConnectionManager(mode)
        addLog("🔄 Switched transport mode to: $mode")
    }

    fun sendCommand(command: String) {
        connectionManager?.sendData("$command\n".toByteArray())
    }

    private fun setupConnectionManager(mode: TransportMode) {
        // Stop observing the old manager before replacing it (otherwise its old collectors keep running).
        managerJobs.forEach { it.cancel() }
        managerJobs.clear()
        partialLineJob?.cancel()
        lineAssembler.flush()  // drop half-received text from the old connection
        (connectionManager as? UsbConnectionManager)?.release()
        connectionManager?.disconnect()

        val manager: ConnectionManager = if (mode == TransportMode.USB) {
            UsbConnectionManager(app, scope)
        } else {
            // BLE steps are mirrored into the terminal log so connection problems are visible on the device.
            BleConnectionManager(app, scope) { msg -> addLog("🔵 BLE: $msg") }
        }
        connectionManager = manager

        managerJobs += scope.launch {
            manager.connectionState.collect { state ->
                connectionStatus.value = state
                updateStatusText()
            }
        }
        managerJobs += scope.launch {
            manager.deviceName.collect { name ->
                deviceName.value = name
                updateStatusText()
            }
        }
        managerJobs += scope.launch {
            manager.dataReceivedFlow.collect { bytes ->
                processIncomingData(String(bytes, Charset.forName("UTF-8")))
            }
        }

        manager.connect()
    }

    private fun updateStatusText() {
        _statusText.value = when (connectionStatus.value) {
            ConnectionState.CONNECTED -> "Connected to ${deviceName.value}"
            ConnectionState.CONNECTING -> "Connecting to ${deviceName.value}…"
            ConnectionState.SCANNING -> "Searching for ESP32…"
            ConnectionState.DISCONNECTED -> "Disconnected — retrying automatically"
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Incoming data — every line is logged and checked against the alert rules exactly once
    // ---------------------------------------------------------------------------------------------

    private val lineAssembler = LineAssembler()
    private var partialLineJob: Job? = null

    private fun processIncomingData(chunk: String) {
        lineAssembler.append(chunk).forEach(::handleLine)

        // If the ESP32 sent text without a line ending, deliver it once it goes quiet.
        partialLineJob?.cancel()
        if (lineAssembler.hasPending) {
            partialLineJob = scope.launch {
                delay(PARTIAL_LINE_TIMEOUT_MS)
                lineAssembler.flush()?.let(::handleLine)
            }
        }
    }

    /** Logs the line and fires every user alert whose trigger it contains. */
    private fun handleLine(line: String) {
        val matches = AlertMatcher.matching(line, alertRules)
        if (matches.isEmpty()) {
            addLog(line)
            return
        }
        addLog("🔔 $line")
        for (rule in matches) {
            addLog("🔔 ↳ Alert '${rule.name}' triggered (trigger: \"${rule.triggerText}\")")
            if (!toneQueue.trySend(rule.toneUri).isSuccess) {
                addLog("⚠️ Sound for '${rule.name}' skipped — too many alerts queued at once.")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Alert rules
    // ---------------------------------------------------------------------------------------------

    fun addAlertRule(rule: AlertRule) {
        alertRules.add(rule)
        saveAlertRules()
    }

    fun removeAlertRule(rule: AlertRule) {
        alertRules.remove(rule)
        saveAlertRules()
    }

    // Sounds are queued and played one at a time, so several alerts on one message don't talk over each other.
    private val toneQueue = Channel<String>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_LATEST)

    private fun startTonePlayer() {
        scope.launch {
            for (toneUri in toneQueue) {
                val ringtone = playTone(toneUri)
                var waited = 0L
                while (ringtone?.isPlaying == true && waited < MAX_TONE_WAIT_MS) {
                    delay(100)
                    waited += 100
                }
                delay(GAP_BETWEEN_TONES_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeUpScreen() {
        try {
            val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Jack::AlertScreenWakeLock"
            )
            wakeLock.acquire(4000L)
        } catch (_: Exception) {}
    }

    /** Plays a tone (falling back to the default notification sound) and returns it so we can wait for it. */
    private fun playTone(uriStr: String): Ringtone? {
        wakeUpScreen()
        val ringtone = try {
            RingtoneManager.getRingtone(app, uriStr.toUri())
        } catch (_: Exception) {
            null
        } ?: try {
            RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        } catch (_: Exception) {
            null
        }
        try {
            ringtone?.play()
        } catch (_: Exception) {}
        return ringtone
    }

    private fun loadAlertRules(): SnapshotStateList<AlertRule> {
        val prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
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

        return list
    }

    private fun saveAlertRules() {
        try {
            val jsonArray = JSONArray()
            for (rule in alertRules) {
                jsonArray.put(JSONObject().apply {
                    put("id", rule.id)
                    put("name", rule.name)
                    put("triggerText", rule.triggerText)
                    put("toneUri", rule.toneUri)
                })
            }
            app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY_RULES, jsonArray.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadTransportMode(): TransportMode {
        val saved = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY_TRANSPORT, null)
            ?.let { s -> TransportMode.entries.firstOrNull { it.name == s } }
        return when {
            saved == TransportMode.USB && !hasUsbHost -> TransportMode.BLE
            saved != null -> saved
            hasUsbHost -> TransportMode.USB
            else -> TransportMode.BLE
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Battery automation (CHARGE / DISCHARGE) — now runs even when the screen is closed
    // ---------------------------------------------------------------------------------------------

    private var lastBatteryCommandSent: String? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return

            val pct = (level * 100 / scale.toFloat()).toInt()
            val prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val minThreshold = prefs.getInt("pref_min_battery", 20)
            val maxThreshold = prefs.getInt("pref_max_battery", 80)

            if (pct <= minThreshold && lastBatteryCommandSent != "CHARGE") {
                lastBatteryCommandSent = "CHARGE"
                sendCommand("CHARGE")
                addLog("⚡ Battery low ($pct% <= min $minThreshold%). Sent 'CHARGE' command.")
            } else if (pct >= maxThreshold && lastBatteryCommandSent != "DISCHARGE") {
                lastBatteryCommandSent = "DISCHARGE"
                sendCommand("DISCHARGE")
                addLog("🔋 Battery full ($pct% >= max $maxThreshold%). Sent 'DISCHARGE' command.")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Terminal log
    // ---------------------------------------------------------------------------------------------

    fun addLog(message: String) {
        Log.d(TAG, message)
        val timestamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        logLines.add("[$timestamp] $message")
        if (logLines.size > MAX_LOG_LINES) {
            logLines.removeAt(0)
        }
    }

    fun clearLogs() = logLines.clear()
}
