@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package net.discdd.trick.screens.messaging

import kotlinx.coroutines.*
import net.discdd.trick.data.currentTimeMillis
import net.discdd.trick.messaging.ChatMessage
import net.discdd.trick.messaging.PhotoContent
import net.discdd.trick.messaging.TextContent
import net.discdd.trick.signal.SignalError
import net.discdd.trick.signal.SignalSessionManager
import net.discdd.trick.util.generateUuid
import net.discdd.trick.util.sha256
import okio.ByteString.Companion.toByteString
import platform.Foundation.NSData
import platform.UIKit.UIDevice

/**
 * iOS Wi-Fi Aware implementation of [WifiAwareService].
 *
 * Delegates transport to a [WifiAwareNativeBridge] (implemented in Swift) and
 * handles Signal encryption/decryption + protobuf encoding/decoding in Kotlin.
 *
 * Content encoding matches Android: `[1-byte type][protobuf payload]` before encryption.
 */
class WifiAwareServiceImpl(
    private val signalSessionManager: SignalSessionManager,
    private val bridge: WifiAwareNativeBridge?
) : WifiAwareService, WifiAwareNativeCallback {

    companion object {
        private const val CONTENT_TYPE_TEXT: Byte = 0
        private const val CONTENT_TYPE_PHOTO: Byte = 1
        private var cachedDeviceId: String? = null
    }

    private var messageCallback: ((ChatMessage, String?) -> Unit)? = null

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            println("WifiAwareServiceImpl coroutine error: ${throwable.message}")
        }
    )

    init {
        bridge?.nativeCallback = this
        bridge?.configure(localDeviceId = getDeviceId())
    }

    // MARK: - WifiAwareService implementation

    override fun startDiscovery(onMessageReceived: (ChatMessage, String?) -> Unit) {
        messageCallback = onMessageReceived

        if (bridge == null || !bridge.isNativeSupported()) {
            notifySystemMessage("[System] Wi-Fi Aware requires iOS 26 or later")
            return
        }

        println("[WifiAwareServiceImpl] Starting discovery")
        bridge.startNativeDiscovery()
    }

    override fun stopDiscovery() {
        bridge?.stopNativeDiscovery()
        messageCallback = null
    }

    override fun sendMessage(message: String) {
        val peers = bridge?.getNativeConnectedPeerIds() ?: return
        peers.forEach { peerId -> sendMessageToPeer(message, peerId) }
    }

    override fun sendPicture(imageData: ByteArray, filename: String?, mimeType: String?) {
        val peers = bridge?.getNativeConnectedPeerIds() ?: return
        peers.forEach { peerId -> sendPictureToPeer(imageData, filename, mimeType, peerId) }
    }

    override fun sendMessageToPeer(message: String, peerId: String) {
        val textContent = TextContent(text = message)
        val contentBytes = byteArrayOf(CONTENT_TYPE_TEXT) + textContent.encode()
        sendEncryptedContent(contentBytes, peerId)
    }

    override fun sendPictureToPeer(imageData: ByteArray, filename: String?, mimeType: String?, peerId: String) {
        val photoContent = PhotoContent(
            data_ = imageData.toByteString(),
            filename = filename,
            mime_type = mimeType
        )
        val contentBytes = byteArrayOf(CONTENT_TYPE_PHOTO) + photoContent.encode()
        sendEncryptedContent(contentBytes, peerId)
    }

    override fun isPeerConnected(): Boolean {
        return (bridge?.getNativeConnectedPeerIds()?.isNotEmpty()) == true
    }

    override fun getConnectionStatus(): String {
        return bridge?.getNativeConnectionStatus() ?: "Wi-Fi Aware unavailable"
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
        return bridge?.getNativeConnectedPeerIds() ?: emptyList()
    }

    override fun setDesiredPeerId(peerId: String?) {
        bridge?.setNativeDesiredPeerId(peerId)
    }

    // MARK: - WifiAwareNativeCallback (called from Swift bridge)

    override fun onNativeDataReceived(data: NSData, fromPeerId: String) {
        scope.launch {
            val bytes = data.toByteArray()
            val chatMessage = try {
                ChatMessage.ADAPTER.decode(bytes)
            } catch (e: Exception) {
                println("Failed to decode protobuf message: ${e.message}")
                return@launch
            }

            val decryptedMessage = handleReceivedMessage(chatMessage, fromPeerId)

            withContext(Dispatchers.Main) {
                messageCallback?.invoke(decryptedMessage, fromPeerId)
            }
        }
    }

    override fun onNativePeerConnected(peerId: String) {
        val shortId = peerId.take(8)
        notifySystemMessage("You're now connected to $shortId!", peerId)
    }

    override fun onNativePeerDisconnected(peerId: String) {
        val shortId = peerId.take(8)
        notifySystemMessage("Connection lost to $shortId", peerId)
    }

    override fun onNativeStatusUpdated(status: String) {
        // Send status updates as system messages so we can detect pairing success
        // Status like "Publishing" or "Browsing" indicates discovery started successfully
        notifySystemMessage("[Status] $status")
    }

    override fun onNativeError(error: String) {
        notifySystemMessage("[Error] $error")
    }

    // MARK: - Encryption

    /**
     * Encrypt content bytes with Signal and send over the bridge.
     * Mirrors Android's sendEncryptedContent().
     */
    private fun sendEncryptedContent(contentBytes: ByteArray, peerId: String) {
        scope.launch {
            if (bridge == null) return@launch

            if (!signalSessionManager.hasSession(peerId)) {
                withContext(Dispatchers.Main) {
                    notifySystemMessage(
                        "[Error] Secure session not established. Exchange QR codes first.",
                        peerId
                    )
                }
                return@launch
            }

            try {
                val result = signalSessionManager.encryptMessage(peerId, 1, contentBytes)

                val chatMessage = ChatMessage(
                    message_id = generateUuid(),
                    timestamp = currentTimeMillis(),
                    sender_id = getDeviceId(),
                    encrypted_content = result.ciphertext.toByteString(),
                    encryption_version = "signal-v1"
                )

                val messageBytes = chatMessage.encode()
                bridge.sendNativeData(messageBytes.toNSData(), peerId)
            } catch (e: SignalError.NoSession) {
                withContext(Dispatchers.Main) {
                    notifySystemMessage(
                        "[Error] Secure session not established. Exchange QR codes first.",
                        peerId
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    notifySystemMessage("[Error] Failed to send message: ${e.message}", peerId)
                }
            }
        }
    }

    /**
     * Handle received message with Signal decryption.
     * Mirrors Android's handleReceivedMessage().
     */
    private suspend fun handleReceivedMessage(chatMessage: ChatMessage, peerId: String): ChatMessage {
        return when {
            chatMessage.encrypted_content == null -> {
                chatMessage.copy(text_content = TextContent(text = "[Rejected: unencrypted message]"))
            }

            chatMessage.encryption_version == "signal-v1" -> {
                try {
                    val encryptedContent = chatMessage.encrypted_content
                        ?: return chatMessage.copy(
                            text_content = TextContent(text = "[Decryption failed: No encrypted content]")
                        )

                    val result = signalSessionManager.decryptMessage(
                        senderId = peerId,
                        deviceId = 1,
                        ciphertext = encryptedContent.toByteArray()
                    )

                    val decryptedBytes = result.plaintext
                    when {
                        decryptedBytes.isEmpty() -> {
                            chatMessage.copy(
                                text_content = TextContent(text = "[Decryption failed: Invalid content]")
                            )
                        }
                        decryptedBytes[0] == CONTENT_TYPE_TEXT -> {
                            val payload = decryptedBytes.copyOfRange(1, decryptedBytes.size)
                            val textContent = TextContent.ADAPTER.decode(payload.toByteString())
                            chatMessage.copy(text_content = textContent, encrypted_content = null)
                        }
                        decryptedBytes[0] == CONTENT_TYPE_PHOTO -> {
                            val payload = decryptedBytes.copyOfRange(1, decryptedBytes.size)
                            val photoContent = PhotoContent.ADAPTER.decode(payload.toByteString())
                            chatMessage.copy(photo_content = photoContent, encrypted_content = null)
                        }
                        else -> {
                            chatMessage.copy(
                                text_content = TextContent(text = "[Decryption failed: Unknown content type]")
                            )
                        }
                    }
                } catch (e: SignalError.UntrustedIdentity) {
                    chatMessage.copy(
                        text_content = TextContent(text = "[Security: Identity changed - verify contact]")
                    )
                } catch (e: SignalError.InvalidMessage) {
                    chatMessage.copy(text_content = TextContent(text = "[Decryption failed]"))
                } catch (e: SignalError.NoSession) {
                    chatMessage.copy(text_content = TextContent(text = "[No secure session]"))
                } catch (e: Exception) {
                    chatMessage.copy(
                        text_content = TextContent(text = "[Decryption failed: ${e.message}]")
                    )
                }
            }

            chatMessage.encryption_version == "hpke-v1" -> {
                chatMessage.copy(
                    text_content = TextContent(text = "[Rejected: encryption downgrade]")
                )
            }

            else -> {
                chatMessage.copy(text_content = TextContent(text = "[Unknown encryption]"))
            }
        }
    }

    // MARK: - Helpers

    private fun notifySystemMessage(message: String, peerId: String? = null) {
        scope.launch(Dispatchers.Main) {
            val chatMessage = ChatMessage(
                message_id = generateUuid(),
                timestamp = currentTimeMillis(),
                sender_id = "system",
                text_content = TextContent(text = message)
            )
            messageCallback?.invoke(chatMessage, peerId)
        }
    }
}
