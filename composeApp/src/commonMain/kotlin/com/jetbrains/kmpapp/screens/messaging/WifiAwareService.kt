package com.jetbrains.kmpapp.screens.messaging

interface WifiAwareService {
    fun startDiscovery(onMessageReceived: (String) -> Unit)
    fun sendMessage(message: String)
    fun isPeerConnected(): Boolean
    fun getConnectionStatus(): String
}

