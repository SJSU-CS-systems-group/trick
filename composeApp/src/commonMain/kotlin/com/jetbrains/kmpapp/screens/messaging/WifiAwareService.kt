package com.jetbrains.kmpapp.screens.messaging

interface WifiAwareService {
    fun startDiscovery(onMessageReceived: (String) -> Unit)
    fun stopDiscovery()
    fun sendMessage(message: String)
    fun isPeerConnected(): Boolean
    fun getConnectionStatus(): String
}

