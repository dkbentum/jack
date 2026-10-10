package com.example.jack

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

@SuppressLint("MissingPermission")
class BleConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    // Receives human-readable connection steps (shown in Jack's terminal log).
    private val onDiagnostic: (String) -> Unit = {}
) : ConnectionManager {

    companion object {
        private const val TAG = "BleConnection"
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Write to ESP32
        val NUS_TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Read/Notify from ESP32
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Remembers the last ESP32 we successfully connected to (cleared by "Reset App").
        private const val PREF_NAME = "esp32_prefs"
        private const val PREF_KEY_SAVED_ADDRESS = "pref_ble_device_address"

        // Advertised names that identify our ESP32 (e.g. "ESP32_Device").
        private val NAME_KEYWORDS = listOf("ESP32", "UART", "Nordic", "Jack")

        // Candidate ranking: saved device > advertises NUS > name match.
        private const val SCORE_SAVED = 3
        private const val SCORE_NUS = 2
        private const val SCORE_NAME = 1

        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val SELECTION_WINDOW_MS = 2_500L        // keep scanning briefly to pick the best ESP32
        private const val RECONNECT_DELAY_MS = 6_000L         // wait before a fresh scan after failure/drop
        private const val GATT_RETRY_DELAY_MS = 1_000L        // wait before retrying the same device
        private const val MAX_DIRECT_RETRIES = 3              // ESP32 often fails the first attempt with status 133
        private const val SERVICE_DISCOVERY_DELAY_MS = 600L   // let the link settle before discovering services
        private const val MTU_FALLBACK_TIMEOUT_MS = 1_500L    // discover anyway if the MTU callback never arrives
        private const val DESIRED_MTU = 247                   // allows ESP32 lines up to 244 bytes per packet
    }

    private data class Candidate(
        val device: BluetoothDevice,
        val name: String,
        val score: Int,
        val rssi: Int
    )

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _deviceName = MutableStateFlow("No BLE Device Connected")
    override val deviceName: StateFlow<String> = _deviceName

    private val _dataReceivedFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val dataReceivedFlow: SharedFlow<ByteArray> = _dataReceivedFlow

    private var isScanning = false
    private var scanJob: Job? = null
    private var selectionJob: Job? = null
    private var reconnectJob: Job? = null
    private val candidates = mutableMapOf<String, Candidate>()

    // True from connect() until disconnect(): keep trying until we are connected.
    private var autoReconnect = false
    private var lastDevice: BluetoothDevice? = null
    private var directRetries = 0
    private var serviceDiscoveryStarted = false

    // Every BLE device seen during the current scan (address -> name), for "what did we see?" diagnostics.
    private val seenDevices = mutableMapOf<String, String>()
    private var scanMisses = 0

    private fun diag(message: String) {
        Log.d(TAG, message)
        onDiagnostic(message)
    }

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    override fun connect() {
        autoReconnect = true
        reconnectJob?.cancel()

        val state = _connectionState.value
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING || isScanning) {
            diag("connect() ignored: already $state")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            diag("⚠️ Bluetooth is disabled or adapter is null. Will retry.")
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
            return
        }
        startScanning()
    }

    override fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        directRetries = 0
        scanMisses = 0
        stopScanning()
        closeGatt()
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceName.value = "No BLE Device Connected"
    }

    override fun sendData(data: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val rxChar = rxCharacteristic ?: return
        if (_connectionState.value != ConnectionState.CONNECTED) return

        // Use write-with-response if the characteristic doesn't support write-without-response.
        val writeType = if (rxChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(rxChar, data, writeType)
            } else {
                @Suppress("DEPRECATION")
                rxChar.value = data
                @Suppress("DEPRECATION")
                rxChar.writeType = writeType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(rxChar)
            }
        } catch (e: Exception) {
            diag("❌ sendData failed: ${e.localizedMessage}")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Results can still arrive after stopScan(); ignore them so we never open a second GATT connection.
            if (!isScanning) return

            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
            val address = device.address
            val rssi = result.rssi
            val serviceUuids = result.scanRecord?.serviceUuids
            seenDevices[address] = name

            val savedAddress = prefs.getString(PREF_KEY_SAVED_ADDRESS, null)
            val isSavedDevice = savedAddress != null && address.equals(savedAddress, ignoreCase = true)
            val hasNusUuid = serviceUuids?.any { it.uuid == NUS_SERVICE_UUID } == true
            val hasEsp32Name = NAME_KEYWORDS.any { name.contains(it, ignoreCase = true) }

            val score = when {
                isSavedDevice -> SCORE_SAVED
                hasNusUuid -> SCORE_NUS
                hasEsp32Name -> SCORE_NAME
                else -> 0
            }

            if (score == 0) {
                // Phones, headphones, watches, etc. — not our ESP32.
                Log.v(TAG, "Ignoring non-ESP32 device: name='$name', address='$address', rssi=$rssi")
                return
            }

            diag("ESP32 candidate: name='$name', address='$address', rssi=$rssi, saved=$isSavedDevice, hasNus=$hasNusUuid, nameMatch=$hasEsp32Name")

            if (isSavedDevice) {
                diag("Saved ESP32 found. Connecting to $name ($address)...")
                stopScanning()
                directRetries = 0
                connectToDevice(device, name)
                return
            }

            val existing = candidates[address]
            if (existing == null || rssi > existing.rssi) {
                candidates[address] = Candidate(device, name, score, rssi)
            }

            // Give other ESP32s a moment to show up, then pick the best one.
            if (selectionJob == null) {
                selectionJob = scope.launch {
                    delay(SELECTION_WINDOW_MS)
                    connectToBestCandidate()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            diag("❌ Scan failed with errorCode: $errorCode")
            isScanning = false
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    private fun startScanning() {
        if (isScanning) return

        // Fetch the scanner every time: it is null while Bluetooth is off, so a cached one goes stale.
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            diag("⚠️ BLE scanner unavailable (Bluetooth off?). Will retry.")
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
            return
        }

        diag("Starting BLE scan (BLE manager v${BuildConfig.VERSION_NAME})...")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            diag("⚠️ Location is OFF. Android ${Build.VERSION.RELEASE} needs Location turned on to find BLE devices.")
        }
        candidates.clear()
        seenDevices.clear()
        selectionJob?.cancel()
        selectionJob = null
        _connectionState.value = ConnectionState.SCANNING
        _deviceName.value = "Searching for ESP32..."
        isScanning = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
            scanJob = scope.launch {
                delay(SCAN_TIMEOUT_MS)
                if (isScanning && candidates.isNotEmpty()) {
                    // An ESP32 showed up late in the scan — use it rather than giving up.
                    connectToBestCandidate()
                } else if (isScanning) {
                    diag("⚠️ BLE scan timeout (${SCAN_TIMEOUT_MS / 1000}s): no ESP32 found.")
                    stopScanning()
                    reportScanMiss()
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _deviceName.value = "ESP32 not found"
                    scheduleReconnect()
                }
            }
        } catch (e: Exception) {
            diag("❌ Failed to start BLE scan: ${e.localizedMessage}")
            isScanning = false
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    private fun stopScanning() {
        if (!isScanning) return
        scanJob?.cancel()
        selectionJob?.cancel()
        selectionJob = null
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        isScanning = false
    }

    /** Explains a scan that found no ESP32, so the operator can tell an app problem from a device problem. */
    private fun reportScanMiss() {
        scanMisses++
        if (scanMisses != 1 && scanMisses % 10 != 0) return  // don't flood the log while auto-retrying
        if (seenDevices.isEmpty()) {
            diag("❌ Scan saw NO BLE devices at all. Check Bluetooth permissions (App Settings) and, on Android 11 or older, Location.")
        } else {
            val names = seenDevices.values.distinct().take(10).joinToString(", ")
            diag("Scan saw ${seenDevices.size} BLE device(s), none look like an ESP32: $names")
            diag("If 'ESP32_Device' is not in that list, the ESP32 is not advertising over BLE (off, already connected to another phone, or running Classic Bluetooth firmware).")
        }

        // A paired ESP32 that is Classic Bluetooth (BluetoothSerial/SPP) will never appear in a BLE scan.
        try {
            bluetoothAdapter?.bondedDevices
                ?.filter { d -> val n = d.name ?: ""; NAME_KEYWORDS.any { n.contains(it, ignoreCase = true) } }
                ?.forEach { d ->
                    val type = when (d.type) {
                        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic Bluetooth (SPP) — NOT BLE, Jack cannot use it"
                        BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                        BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual mode (Classic + BLE)"
                        else -> "unknown type"
                    }
                    diag("Paired in Android settings: '${d.name}' (${d.address}) — $type")
                }
        } catch (_: Exception) {}
    }

    private fun isLocationEnabled(): Boolean = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
    } catch (_: Exception) {
        true
    }

    private fun connectToBestCandidate() {
        selectionJob = null
        if (!isScanning) return

        val best = candidates.values.maxWithOrNull(
            compareBy<Candidate>({ it.score }, { it.rssi })
        ) ?: return

        diag("Selected ESP32 '${best.name}' (${best.device.address}) from ${candidates.size} candidate(s), rssi=${best.rssi}")
        stopScanning()
        directRetries = 0
        connectToDevice(best.device, best.name)
    }

    // ---------------------------------------------------------------------------------------------
    // Connection lifecycle
    // ---------------------------------------------------------------------------------------------

    private fun connectToDevice(device: BluetoothDevice, name: String? = null) {
        diag("Connecting to ${name ?: device.address} (${device.address}), attempt ${directRetries + 1}")
        lastDevice = device
        _connectionState.value = ConnectionState.CONNECTING
        _deviceName.value = name ?: device.address

        // Close any stale connection first so we never hold two GATT clients at once.
        closeGatt()
        serviceDiscoveryStarted = false
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            diag("❌ connectGatt failed: ${e.localizedMessage}")
            _connectionState.value = ConnectionState.DISCONNECTED
            handleConnectionLost(status = -1, wasConnected = false)
        }
    }

    /** Decides what to do after a failed attempt or a dropped link. */
    private fun handleConnectionLost(status: Int, wasConnected: Boolean) {
        if (!autoReconnect) return
        val device = lastDevice

        if (!wasConnected && device != null && directRetries < MAX_DIRECT_RETRIES) {
            // A failed first attempt (typically status 133) usually succeeds on a quick retry.
            directRetries++
            diag("⚠️ Connection attempt failed (status=$status). Retrying same device ($directRetries/$MAX_DIRECT_RETRIES)...")
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(GATT_RETRY_DELAY_MS)
                if (autoReconnect && _connectionState.value == ConnectionState.DISCONNECTED) {
                    connectToDevice(device)
                }
            }
        } else {
            // Link dropped, or retries exhausted: start over with a fresh scan.
            directRetries = 0
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (autoReconnect && _connectionState.value == ConnectionState.DISCONNECTED) {
                diag("Auto-reconnecting BLE...")
                connect()
            }
        }
    }

    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        if (serviceDiscoveryStarted || gatt != bluetoothGatt) return
        serviceDiscoveryStarted = true
        scope.launch {
            delay(SERVICE_DISCOVERY_DELAY_MS)
            if (gatt != bluetoothGatt) return@launch
            diag("Discovering services...")
            if (!gatt.discoverServices()) {
                diag("❌ discoverServices() returned false. Dropping link to retry.")
                gatt.disconnect()
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return false

        // Some firmwares expose TX as "indicate" only.
        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun markConnected(gatt: BluetoothGatt) {
        directRetries = 0
        scanMisses = 0
        prefs.edit().putString(PREF_KEY_SAVED_ADDRESS, gatt.device.address).apply()
        diag("✅ ESP32 ready. Saved address for auto-reconnect: ${gatt.device.address}")

        _connectionState.value = ConnectionState.CONNECTED
        _deviceName.value = gatt.device.name ?: gatt.device.address

        // Only now, with setup finished, ask for a faster link (doing it earlier can drop the descriptor write).
        try {
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        } catch (_: Exception) {}
    }

    private fun closeGatt() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
    }

    // ---------------------------------------------------------------------------------------------
    // GATT callbacks (arrive on a binder thread; hop to the main scope before touching state)
    // ---------------------------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                if (gatt != bluetoothGatt) {
                    // Callback from a connection we've already replaced.
                    try { gatt.close() } catch (_: Exception) {}
                    return@launch
                }
                diag("onConnectionStateChange: status=$status, newState=$newState (2=Connected, 0=Disconnected)")

                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    diag("Link established. Requesting MTU $DESIRED_MTU...")
                    _connectionState.value = ConnectionState.CONNECTING
                    val mtuRequested = try { gatt.requestMtu(DESIRED_MTU) } catch (_: Exception) { false }
                    if (mtuRequested) {
                        // Safety net in case onMtuChanged never arrives.
                        launch {
                            delay(MTU_FALLBACK_TIMEOUT_MS)
                            startServiceDiscovery(gatt)
                        }
                    } else {
                        startServiceDiscovery(gatt)
                    }
                } else {
                    val wasConnected = _connectionState.value == ConnectionState.CONNECTED
                    diag("⚠️ GATT disconnected/failed: status=$status, wasConnected=$wasConnected")
                    closeGatt()
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _deviceName.value = "No BLE Device Connected"
                    handleConnectionLost(status, wasConnected)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch {
                diag("onMtuChanged: mtu=$mtu, status=$status")
                startServiceDiscovery(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            scope.launch {
                if (gatt != bluetoothGatt) return@launch
                diag("onServicesDiscovered: status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    diag("❌ Service discovery failed. Dropping link to retry.")
                    gatt.disconnect()
                    return@launch
                }

                val service = gatt.getService(NUS_SERVICE_UUID)
                if (service != null) {
                    diag("NUS service found.")
                    rxCharacteristic = service.getCharacteristic(NUS_RX_UUID)
                    txCharacteristic = service.getCharacteristic(NUS_TX_UUID)
                } else {
                    diag("⚠️ NUS service NOT found. Available services:")
                    gatt.services.forEach { s -> diag("⚠️  - ${s.uuid}") }
                    // Fallback for firmware using a custom UART-style service.
                    val fallbackService = gatt.services.firstOrNull { s ->
                        s.characteristics.any { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
                    }
                    if (fallbackService != null) {
                        diag("Using fallback service: ${fallbackService.uuid}")
                        rxCharacteristic = fallbackService.characteristics.firstOrNull {
                            it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        }
                        txCharacteristic = fallbackService.characteristics.firstOrNull {
                            it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                        }
                    }
                }

                val tx = txCharacteristic
                if (tx == null) {
                    diag("❌ No notify characteristic found — this is not a UART device. Disconnecting.")
                    gatt.disconnect()
                    return@launch
                }
                if (rxCharacteristic == null) {
                    diag("⚠️ No writable characteristic found: Jack can receive but not send commands.")
                }

                diag("Enabling notifications on ${tx.uuid}...")
                if (tx.getDescriptor(CCCD_UUID) == null) {
                    gatt.setCharacteristicNotification(tx, true)
                    markConnected(gatt)
                } else if (!enableNotifications(gatt, tx)) {
                    // The stack was busy; give it a moment and try once more.
                    delay(300)
                    if (gatt == bluetoothGatt && !enableNotifications(gatt, tx)) {
                        diag("❌ Could not enable notifications. Dropping link to retry.")
                        gatt.disconnect()
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            scope.launch {
                if (gatt != bluetoothGatt) return@launch
                diag("onDescriptorWrite: status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    markConnected(gatt)
                } else {
                    diag("❌ Enabling notifications failed (status=$status). Dropping link to retry.")
                    gatt.disconnect()
                }
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Used for older Android versions", ReplaceWith("onCharacteristicChanged(gatt, characteristic, value)"))
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Copy now: the characteristic's buffer is reused for the next notification.
            val data = characteristic.value?.copyOf() ?: return
            if (data.isNotEmpty()) {
                scope.launch { _dataReceivedFlow.emit(data) }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (value.isNotEmpty()) {
                val data = value.copyOf()
                scope.launch { _dataReceivedFlow.emit(data) }
            }
        }
    }
}
