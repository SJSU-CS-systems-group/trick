package net.discdd.trick.screens.messaging

import net.discdd.trick.messaging.ChatMessage

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

