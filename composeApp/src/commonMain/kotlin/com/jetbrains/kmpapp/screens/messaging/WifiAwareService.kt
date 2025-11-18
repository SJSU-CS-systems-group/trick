package com.jetbrains.kmpapp.screens.messaging

import com.jetbrains.kmpapp.messaging.ChatMessage

interface WifiAwareService {
    fun startDiscovery(onMessageReceived: (ChatMessage) -> Unit)
    fun stopDiscovery()
    fun sendMessage(message: String)
    fun sendPicture(imageData: ByteArray, filename: String?, mimeType: String?)
    fun isPeerConnected(): Boolean
    fun getConnectionStatus(): String
    fun getDeviceId(): String
    fun getConnectedPeers(): List<String>
}

