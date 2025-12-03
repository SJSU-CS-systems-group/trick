package net.discdd.trick.screens.messaging

import net.discdd.trick.messaging.ChatMessage
import net.discdd.trick.messaging.TextContent

/**
 * iOS stub implementation of WifiAwareService
 * WiFi Aware is Android-specific, so this is a no-op implementation
 */
class WifiAwareServiceImpl : WifiAwareService {
    override fun startDiscovery(onMessageReceived: (ChatMessage) -> Unit) {
        // WiFi Aware is not available on iOS - send a system message
        val systemMessage = ChatMessage(
            message_id = "system-ios",
            timestamp = System.currentTimeMillis(),
            sender_id = "system",
            text_content = TextContent("[System] WiFi Aware is only available on Android")
        )
        onMessageReceived(systemMessage)
    }

    override fun stopDiscovery() {
        // No-op
    }

    override fun sendMessage(message: String) {
        // No-op
    }

    override fun sendPicture(imageData: ByteArray, filename: String?, mimeType: String?) {
        // No-op
    }

    override fun isPeerConnected(): Boolean {
        return false
    }

    override fun getConnectionStatus(): String {
        return "iOS platform (WiFi Aware not supported)"
    }

    override fun getDeviceId(): String {
        return "ios-device-not-supported"
    }

    override fun getConnectedPeers(): List<String> {
        return emptyList()
    }
}

