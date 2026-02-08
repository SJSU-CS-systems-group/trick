package net.discdd.trick.screens.messaging

import net.discdd.trick.data.currentTimeMillis
import net.discdd.trick.messaging.ChatMessage
import net.discdd.trick.messaging.TextContent
import net.discdd.trick.util.sha256
import platform.UIKit.UIDevice

/**
 * iOS stub implementation of WifiAwareService
 * WiFi Aware is Android-specific, so this is a no-op implementation.
 * Device ID generation is still functional for key exchange.
 */
class WifiAwareServiceImpl : WifiAwareService {

    companion object {
        private var cachedDeviceId: String? = null
    }

    override fun startDiscovery(onMessageReceived: (ChatMessage, String?) -> Unit) {
        val systemMessage = ChatMessage(
            message_id = "system-ios",
            timestamp = currentTimeMillis(),
            sender_id = "system",
            text_content = TextContent("[System] WiFi Aware is only available on Android")
        )
        onMessageReceived(systemMessage, null)
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

    override fun sendMessageToPeer(message: String, peerId: String) {
        // No-op on iOS
    }

    override fun sendPictureToPeer(imageData: ByteArray, filename: String?, mimeType: String?, peerId: String) {
        // No-op on iOS
    }

    override fun isPeerConnected(): Boolean {
        return false
    }

    override fun getConnectionStatus(): String {
        return "iOS device (peer-to-peer via key exchange)"
    }

    override fun getDeviceId(): String {
        cachedDeviceId?.let { return it }

        val device = UIDevice.currentDevice
        val vendorId = device.identifierForVendor?.UUIDString() ?: "unknown-ios"
        val model = device.model
        val systemName = device.systemName
        val combined = "$vendorId:$model:$systemName"

        val hashBytes = sha256(combined.encodeToByteArray())
        val hexString = hashBytes.joinToString("") { byte ->
            val i = byte.toInt() and 0xFF
            val hex = i.toString(16)
            if (hex.length == 1) "0$hex" else hex
        }

        cachedDeviceId = hexString
        return hexString
    }

    override fun getConnectedPeers(): List<String> {
        return emptyList()
    }

    override fun setDesiredPeerId(peerId: String?) {
        // No-op on iOS (WiFi Aware not supported)
    }
}

