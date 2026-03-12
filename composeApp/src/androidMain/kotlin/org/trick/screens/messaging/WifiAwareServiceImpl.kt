package org.trick.screens.messaging

import android.content.Context
import android.util.Log
import org.trick.metrics.StressTestReceiver
import org.trick.signal.SignalSessionManager

class WifiAwareServiceImpl(
    private val context: Context,
    private val signalSessionManager: SignalSessionManager
) : WifiAwareService {
    // Connection-based WiFi Aware manager
    private val manager = AndroidWifiAwareManager(context, signalSessionManager)

    init {
        // Register manager for stress-test access via ADB broadcast
        Log.d("WifiAwareServiceImpl", "Registering AndroidWifiAwareManager with StressTestReceiver...")
        StressTestReceiver.registerManager(manager)
        Log.d("WifiAwareServiceImpl", "Manager registered successfully for stress tests")
    }

    override fun startDiscovery(onMessageReceived: (org.trick.messaging.ChatMessage, String?) -> Unit) {
        Log.d("WifiAwareServiceImpl", "Starting discovery with connection-based networking")

        manager.startDiscovery(
            onMessageReceived = { chatMessage, peerId ->
                val messagePreview = chatMessage.text_content?.text
                    ?: chatMessage.photo_content?.filename
                    ?: "[Image]"
                Log.d("WifiAwareServiceImpl", "Message received from ${peerId?.take(8) ?: "unknown"}: $messagePreview")
                onMessageReceived(chatMessage, peerId)
            },
            onConnectionStatusChanged = { peerId, state ->
                Log.d("WifiAwareServiceImpl", "Connection status: ${peerId.take(8)} -> $state")
            }
        )
    }

    override fun sendMessage(message: String) {
        Log.d("WifiAwareServiceImpl", "Broadcasting message: $message")
        Log.d("WifiAwareServiceImpl", "Connection status: ${manager.getConnectionStatus()}")

        // Broadcast to all connected peers
        manager.broadcastMessage(message)
    }

    override fun sendPicture(imageData: ByteArray, filename: String?, mimeType: String?) {
        Log.d("WifiAwareServiceImpl", "Broadcasting picture: $filename (${imageData.size} bytes)")
        Log.d("WifiAwareServiceImpl", "Connection status: ${manager.getConnectionStatus()}")

        // Broadcast picture to all connected peers
        manager.broadcastPicture(imageData, filename, mimeType)
    }

    override fun isPeerConnected(): Boolean {
        return manager.getConnectedPeers().isNotEmpty()
    }

    override fun getConnectionStatus(): String {
        return manager.getConnectionStatus()
    }

    override fun stopDiscovery() {
        Log.d("WifiAwareServiceImpl", "Stopping discovery")
        manager.stopDiscovery()
    }

    override fun getDeviceId(): String {
        return manager.getDeviceId()
    }

    override fun getConnectedPeers(): List<String> {
        return manager.getConnectedPeers()
    }

    override fun sendMessageToPeer(message: String, peerId: String) {
        Log.d("WifiAwareServiceImpl", "Sending message to specific peer: ${peerId.take(8)}")
        manager.sendMessageToPeer(message, peerId)
    }

    override fun sendPictureToPeer(imageData: ByteArray, filename: String?, mimeType: String?, peerId: String) {
        Log.d("WifiAwareServiceImpl", "Sending picture to specific peer: ${peerId.take(8)}")
        manager.sendPictureToPeer(imageData, filename, mimeType, peerId)
    }

    override fun setDesiredPeerId(peerId: String?) {
        Log.d("WifiAwareServiceImpl", "Set desired peer: ${peerId?.take(8) ?: "null"}")
        manager.setDesiredPeerId(peerId)
    }
}
