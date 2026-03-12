package org.trick.screens.messaging

import org.trick.messaging.ChatMessage

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

    /**
     * Set the peer to connect to when discovered. Only this peer will get connection establishment.
     * - peerId != null: only establish connection to that peer (existing connections remain).
     * - peerId == null: do not establish any new connections (e.g. when on contacts list).
     */
    fun setDesiredPeerId(peerId: String?)
}

