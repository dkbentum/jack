package com.example.jack

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

class UsbConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope
) : ConnectionManager {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.jack.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbSerialPort: UsbSerialPort? = null
    private var readJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _deviceName = MutableStateFlow("No USB Device Connected")
    override val deviceName: StateFlow<String> = _deviceName

    private val _dataReceivedFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val dataReceivedFlow: SharedFlow<ByteArray> = _dataReceivedFlow

    private var permissionReceiverRegistered = false
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openDevice(it) }
                    } else {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }
        permissionReceiverRegistered = true
    }

    override fun connect() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            _connectionState.value = ConnectionState.DISCONNECTED
            _deviceName.value = "No USB Device Found"
            return
        }

        val driver = drivers[0]
        val device = driver.device
        _deviceName.value = "${device.productName ?: "ESP32 USB"} (VID:${device.vendorId})"

        if (usbManager.hasPermission(device)) {
            openDevice(device)
        } else {
            _connectionState.value = ConnectionState.CONNECTING
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun openDevice(device: UsbDevice) {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.find { it.device == device } ?: return

        try {
            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                _connectionState.value = ConnectionState.DISCONNECTED
                return
            }

            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            usbSerialPort = port
            _connectionState.value = ConnectionState.CONNECTED
            startReading(port)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            disconnect()
        }
    }

    override fun disconnect() {
        readJob?.cancel()
        readJob = null
        try {
            usbSerialPort?.close()
        } catch (_: Exception) {}
        usbSerialPort = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceName.value = "No USB Device Connected"
    }

    override fun sendData(data: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                usbSerialPort?.write(data, 1000)
            } catch (_: Exception) {}
        }
    }

    private fun startReading(port: UsbSerialPort) {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            try {
                while (isActive) {
                    val len = port.read(buffer, 1000)
                    if (len > 0) {
                        val dataCopy = buffer.copyOfRange(0, len)
                        _dataReceivedFlow.emit(dataCopy)
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    disconnect()
                }
            }
        }
    }

    fun release() {
        disconnect()
        if (permissionReceiverRegistered) {
            try {
                context.unregisterReceiver(usbPermissionReceiver)
            } catch (_: Exception) {}
            permissionReceiverRegistered = false
        }
    }
}
