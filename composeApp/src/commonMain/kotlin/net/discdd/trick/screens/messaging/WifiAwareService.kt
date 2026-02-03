package net.discdd.trick.screens.messaging

import net.discdd.trick.messaging.ChatMessage

interface WifiAwareService {
    fun startDiscovery(onMessageReceived: (ChatMessage, peerId: String?) -> Unit)
    fun stopDiscovery()
    fun sendMessage(message: String)
    fun sendPicture(imageData: ByteArray, filename: String?, mimeType: String?)
    fun sendMessageToPeer(message: String, peerId: String)
    fun sendPictureToPeer(imageData: ByteArray, filename: String?, mimeType: String?, peerId: String)
    fun isPeerConnected(): Boolean
    fun getConnectionStatus(): String
    fun getDeviceId(): String
    fun getConnectedPeers(): List<String>
}

