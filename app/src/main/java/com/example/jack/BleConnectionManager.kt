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
    private val scope: CoroutineScope
) : ConnectionManager {

    companion object {
        private const val TAG = "BleConnection"
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Write to ESP32
        val NUS_TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Read/Notify from ESP32
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

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
    private var reconnectJob: Job? = null
    private var hasBeenConnectedOnce = false

    private fun scheduleReconnect() {
        if (!hasBeenConnectedOnce) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(6000)
            if (_connectionState.value != ConnectionState.CONNECTED &&
                _connectionState.value != ConnectionState.CONNECTING &&
                _connectionState.value != ConnectionState.SCANNING
            ) {
                Log.d(TAG, "Auto-reconnecting BLE after connection drop...")
                connect()
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown"
            val address = device.address
            val rssi = result.rssi
            val serviceUuids = result.scanRecord?.serviceUuids

            val hasNusUuid = serviceUuids?.any { it.uuid == NUS_SERVICE_UUID } == true
            val hasEsp32Name = name.contains("ESP32", ignoreCase = true) ||
                    name.contains("UART", ignoreCase = true) ||
                    name.contains("Nordic", ignoreCase = true) ||
                    name.contains("Jack", ignoreCase = true)

            Log.d(TAG, "Discovered device: name='$name', address='$address', rssi=$rssi, hasNus=$hasNusUuid, hasEsp32Name=$hasEsp32Name")

            Log.d(TAG, "Connecting to discovered BLE device: $name ($address)...")
            stopScanning()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with errorCode: $errorCode")
            _connectionState.value = ConnectionState.DISCONNECTED
            isScanning = false
            scheduleReconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState (2=Connected, 0=Disconnected)")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _connectionState.value = ConnectionState.CONNECTING
                    Log.d(TAG, "Connected to GATT server. Discovering services...")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "GATT disconnected.")
                    cleanupGatt()
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _deviceName.value = "No BLE Device Connected"
                    scheduleReconnect()
                }
            } else {
                Log.e(TAG, "GATT connection failed with status: $status")
                cleanupGatt()
                _connectionState.value = ConnectionState.DISCONNECTED
                _deviceName.value = "No BLE Device Connected"
                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                hasBeenConnectedOnce = true
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

                val service = gatt.getService(NUS_SERVICE_UUID)
                if (service != null) {
                    Log.d(TAG, "NUS Service found!")
                    rxCharacteristic = service.getCharacteristic(NUS_RX_UUID)
                    txCharacteristic = service.getCharacteristic(NUS_TX_UUID)

                    if (txCharacteristic != null) {
                        Log.d(TAG, "TX Characteristic found. Enabling notifications...")
                        gatt.setCharacteristicNotification(txCharacteristic, true)
                        val descriptor = txCharacteristic?.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    } else {
                        Log.w(TAG, "TX Characteristic NOT found in NUS service!")
                    }
                } else {
                    Log.w(TAG, "NUS Service 6E400001-... NOT found on device! Available services:")
                    gatt.services.forEach { s ->
                        Log.w(TAG, " - Service UUID: ${s.uuid}")
                    }
                    // Fallback to first available service with characteristics
                    val fallbackService = gatt.services.firstOrNull { it.characteristics.isNotEmpty() }
                    if (fallbackService != null) {
                        Log.d(TAG, "Using fallback service: ${fallbackService.uuid}")
                        rxCharacteristic = fallbackService.characteristics.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 }
                            ?: fallbackService.characteristics.firstOrNull()
                        txCharacteristic = fallbackService.characteristics.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }
                            ?: fallbackService.characteristics.lastOrNull()

                        if (txCharacteristic != null) {
                            gatt.setCharacteristicNotification(txCharacteristic, true)
                            val descriptor = txCharacteristic?.getDescriptor(CCCD_UUID)
                            if (descriptor != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                } else {
                                    @Suppress("DEPRECATION")
                                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    @Suppress("DEPRECATION")
                                    gatt.writeDescriptor(descriptor)
                                }
                            }
                        }
                    }
                }
                _connectionState.value = ConnectionState.CONNECTED
                _deviceName.value = gatt.device.name ?: gatt.device.address
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                hasBeenConnectedOnce = true
                _connectionState.value = ConnectionState.CONNECTED
                _deviceName.value = gatt.device.name ?: gatt.device.address
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Used for older Android versions", ReplaceWith("onCharacteristicChanged(gatt, characteristic, value)"))
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == NUS_TX_UUID || true) {
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    scope.launch {
                        _dataReceivedFlow.emit(data)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == NUS_TX_UUID || true) {
                if (value.isNotEmpty()) {
                    scope.launch {
                        _dataReceivedFlow.emit(value)
                    }
                }
            }
        }
    }

    override fun connect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled or adapter is null.")
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
            return
        }
        startScanning()
    }

    private fun startScanning() {
        if (isScanning) return
        Log.d(TAG, "Starting BLE scan...")
        _connectionState.value = ConnectionState.SCANNING
        isScanning = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bluetoothLeScanner?.startScan(null, settings, scanCallback)
            scanJob = scope.launch {
                delay(10000)
                if (isScanning) {
                    Log.w(TAG, "BLE scan timeout (10s). Stopping scan...")
                    stopScanning()
                    if (_connectionState.value == ConnectionState.SCANNING) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                        scheduleReconnect()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan: ${e.localizedMessage}")
            _connectionState.value = ConnectionState.DISCONNECTED
            isScanning = false
            scheduleReconnect()
        }
    }

    private fun stopScanning() {
        if (!isScanning) return
        scanJob?.cancel()
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        isScanning = false
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to device: ${device.name ?: "Unknown"} (${device.address})")
        _connectionState.value = ConnectionState.CONNECTING
        _deviceName.value = device.name ?: device.address
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun cleanupGatt() {
        stopScanning()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
    }

    override fun disconnect() {
        reconnectJob?.cancel()
        hasBeenConnectedOnce = false
        cleanupGatt()
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceName.value = "No BLE Device Connected"
    }

    override fun sendData(data: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val rxChar = rxCharacteristic ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rxChar, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            rxChar.value = data
            @Suppress("DEPRECATION")
            rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(rxChar)
        }
    }
}
