package com.jetbrains.kmpapp.screens.messaging

interface WifiAwareService {
    fun startDiscovery(onMessageReceived: (String) -> Unit)
    fun stopDiscovery()
    fun sendMessage(message: String)
    fun sendPicture(imageData: ByteArray, filename: String?, mimeType: String?)
    fun isPeerConnected(): Boolean
    fun getConnectionStatus(): String
    fun getDeviceId(): String
    fun getConnectedPeers(): List<String>
}

