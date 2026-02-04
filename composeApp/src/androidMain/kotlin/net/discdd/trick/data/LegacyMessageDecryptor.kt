package net.discdd.trick.data

import android.util.Log
import net.discdd.trick.libsignal.LibSignalManager
import net.discdd.trick.messaging.PhotoContent
import net.discdd.trick.messaging.TextContent
import net.discdd.trick.security.KeyManager
import okio.ByteString.Companion.toByteString

/**
 * Legacy decryptor for reading old hpke-v1 messages from local database ONLY.
 * NOT used for network messages - those must use Signal protocol.
 *
 * This class exists to maintain backward compatibility with messages that were
 * encrypted using the old HPKE-based encryption before the Signal protocol migration.
 */
class LegacyMessageDecryptor(
    private val keyManager: KeyManager,
    private val libSignalManager: LibSignalManager
) {
    companion object {
        private const val TAG = "LegacyMessageDecryptor"
        private const val CONTENT_TYPE_TEXT = 0
        private const val CONTENT_TYPE_PHOTO = 1
    }

    /**
     * Decrypt a legacy hpke-v1 encrypted message.
     *
     * @param encryptedContent The encrypted content bytes
     * @return Decrypted content bytes, or null if decryption fails
     */
    fun decryptLegacyMessage(encryptedContent: ByteArray): ByteArray? {
        val myKeyPair = keyManager.getIdentityKeyPair() ?: run {
            Log.e(TAG, "Cannot decrypt: no identity key pair")
            return null
        }

        return try {
            libSignalManager.decrypt(myKeyPair.privateKey, encryptedContent)
        } catch (e: Exception) {
            Log.e(TAG, "Legacy decryption failed: ${e.message}")
            null
        }
    }

    /**
     * Decrypt and parse a legacy text message.
     *
     * @param encryptedContent The encrypted content bytes
     * @return TextContent if decryption succeeds, null otherwise
     */
    fun decryptLegacyTextMessage(encryptedContent: ByteArray): TextContent? {
        val decryptedBytes = decryptLegacyMessage(encryptedContent) ?: return null

        return try {
            when {
                decryptedBytes.isEmpty() -> {
                    Log.e(TAG, "Decrypted content is empty")
                    null
                }
                decryptedBytes[0] == CONTENT_TYPE_TEXT.toByte() -> {
                    // New format with type discriminator
                    val payload = decryptedBytes.copyOfRange(1, decryptedBytes.size)
                    TextContent.ADAPTER.decode(payload.toByteString())
                }
                else -> {
                    // Legacy format without type discriminator - try direct decode
                    TextContent.ADAPTER.decode(decryptedBytes.toByteString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse decrypted text content: ${e.message}")
            null
        }
    }

    /**
     * Decrypt and parse a legacy photo message.
     *
     * @param encryptedContent The encrypted content bytes
     * @return PhotoContent if decryption succeeds, null otherwise
     */
    fun decryptLegacyPhotoMessage(encryptedContent: ByteArray): PhotoContent? {
        val decryptedBytes = decryptLegacyMessage(encryptedContent) ?: return null

        return try {
            when {
                decryptedBytes.isEmpty() -> {
                    Log.e(TAG, "Decrypted content is empty")
                    null
                }
                decryptedBytes[0] == CONTENT_TYPE_PHOTO.toByte() -> {
                    // New format with type discriminator
                    val payload = decryptedBytes.copyOfRange(1, decryptedBytes.size)
                    PhotoContent.ADAPTER.decode(payload.toByteString())
                }
                else -> {
                    // Legacy format without type discriminator - try direct decode
                    PhotoContent.ADAPTER.decode(decryptedBytes.toByteString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse decrypted photo content: ${e.message}")
            null
        }
    }

    /**
     * Try to decrypt legacy content and determine the content type.
     *
     * @param encryptedContent The encrypted content bytes
     * @return Pair of (contentType, decryptedBytes), or null if decryption fails
     *         contentType: 0 for text, 1 for photo
     */
    fun decryptAndDetectType(encryptedContent: ByteArray): Pair<Int, ByteArray>? {
        val decryptedBytes = decryptLegacyMessage(encryptedContent) ?: return null

        if (decryptedBytes.isEmpty()) return null

        return when {
            decryptedBytes[0] == CONTENT_TYPE_TEXT.toByte() -> {
                val payload = decryptedBytes.copyOfRange(1, decryptedBytes.size)
                Pair(CONTENT_TYPE_TEXT, payload)
            }
            decryptedBytes[0] == CONTENT_TYPE_PHOTO.toByte() -> {
                val payload = decryptedBytes.copyOfRange(1, decryptedBytes.size)
                Pair(CONTENT_TYPE_PHOTO, payload)
            }
            else -> {
                // Legacy format - try to detect by parsing
                try {
                    PhotoContent.ADAPTER.decode(decryptedBytes.toByteString())
                    Pair(CONTENT_TYPE_PHOTO, decryptedBytes)
                } catch (e: Exception) {
                    try {
                        TextContent.ADAPTER.decode(decryptedBytes.toByteString())
                        Pair(CONTENT_TYPE_TEXT, decryptedBytes)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Cannot determine content type: ${e2.message}")
                        null
                    }
                }
            }
        }
    }
}
