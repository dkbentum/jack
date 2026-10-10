package com.example.jack

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

interface ConnectionManager {
    val connectionState: StateFlow<ConnectionState>
    val dataReceivedFlow: SharedFlow<ByteArray>
    val deviceName: StateFlow<String>

    fun connect()
    fun disconnect()
    fun sendData(data: ByteArray)
}
