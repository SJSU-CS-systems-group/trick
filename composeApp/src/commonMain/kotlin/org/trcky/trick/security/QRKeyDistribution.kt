package org.trcky.trick.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.trcky.trick.libsignal.LibSignalManager
import org.trcky.trick.libsignal.PublicKey
import org.trcky.trick.util.ShortIdGenerator

/**
 * Payload structure for QR code key distribution.
 * Contains the device ID, public key, timestamp (fixed at 0 for permanent QR codes),
 * signature, and optional shortId for trcky.org URL.
 */
@Serializable
data class KeyDistributionPayload(
    val deviceId: String,
    val publicKeyHex: String,
    val timestamp: Long,
    val signatureHex: String,
    val shortId: String? = null
)

/**
 * Result of generating a QR payload, including the JSON and shortId for building the display URL.
 */
data class KeyDistributionQRResult(
    val payloadJson: String,
    val shortId: String
)

/** Base URL for trcky.org key distribution links (align with Contact.getUrl()). */
const val TRCKY_ORG_BASE_URL = "https://trcky.org"

/**
 * Parse a trcky.org URL and return the shortId path segment, or null if not a trcky.org URL.
 * Accepts: trcky.org/xyz, https://trcky.org/xyz, http://trcky.org/xyz (with optional trailing path/query).
 */
fun parseTrckyShortId(input: String): String? {
    val trimmed = input.trim()
    val withoutScheme = when {
        trimmed.startsWith("https://trcky.org/", ignoreCase = true) -> trimmed.removePrefix("https://trcky.org/")
        trimmed.startsWith("http://trcky.org/", ignoreCase = true) -> trimmed.removePrefix("http://trcky.org/")
        trimmed.startsWith("trcky.org/", ignoreCase = true) -> trimmed.removePrefix("trcky.org/")
        else -> return null
    }
    val shortId = withoutScheme.substringBefore('/').substringBefore('?').trim()
    return shortId.ifEmpty { null }
}

/**
 * QRKeyDistribution handles the generation and verification of QR codes for key distribution.
 *
 * Security features:
 * - QR codes are signed to prevent impersonation
 * - Signature verification ensures authenticity
 * - QR codes are permanent and deterministic (same key material → same QR code)
 */
object QRKeyDistribution {

    /**
     * Generate a QR code payload containing the device's public key.
     *
     * The payload includes:
     * - Device ID
     * - Public key (hex encoded)
     * - Timestamp (fixed at 0 for permanent, deterministic QR codes)
     * - Signature (to prevent tampering/impersonation)
     * - ShortId (for trcky.org URL)
     *
     * @param keyManager KeyManager to retrieve identity key pair
     * @param libSignalManager LibSignalManager for signing
     * @param deviceId The device's unique identifier
     * @return KeyDistributionQRResult with payloadJson and shortId for display URL
     */
    fun generateQRPayload(
        keyManager: KeyManager,
        libSignalManager: LibSignalManager,
        deviceId: String
    ): KeyDistributionQRResult {
        val keyPair = keyManager.getIdentityKeyPair()
            ?: keyManager.generateIdentityKeyPair()

        val timestamp = 0L
        val publicKeyHex = keyPair.publicKey.data.toHexString()
        val shortId = ShortIdGenerator.generateShortId(keyPair.publicKey)

        // Sign the payload to prevent tampering (include shortId for new payloads)
        val dataToSign = "$deviceId:$publicKeyHex:$timestamp:$shortId".encodeToByteArray()
        val signature = libSignalManager.sign(keyPair.privateKey, dataToSign)
        val signatureHex = signature.toHexString()

        val payload = KeyDistributionPayload(
            deviceId = deviceId,
            publicKeyHex = publicKeyHex,
            timestamp = timestamp,
            signatureHex = signatureHex,
            shortId = shortId
        )

        val payloadJson = Json.encodeToString(payload)
        return KeyDistributionQRResult(payloadJson = payloadJson, shortId = shortId)
    }

    /**
     * Verify and store a QR code payload from a peer.
     *
     * Verification steps:
     * 1. Parse JSON payload
     * 2. Verify signature using the peer's public key
     * 3. Store the peer's public key if verification passes
     *
     * @param payload JSON string from QR code
     * @param keyManager KeyManager to store peer's public key
     * @param libSignalManager LibSignalManager for signature verification
     * @return True if verification and storage succeeded, false otherwise
     */
    fun verifyAndStoreQRPayload(
        payload: String,
        keyManager: KeyManager,
        libSignalManager: LibSignalManager
    ): Pair<Boolean, String> {
        return try {
            val data = Json.decodeFromString<KeyDistributionPayload>(payload)

            // Decode hex strings
            val publicKeyBytes = data.publicKeyHex.hexToByteArray()
            val signatureBytes = data.signatureHex.hexToByteArray()

            // Verify signature (match format used when signing: with or without shortId)
            val dataToVerify = if (data.shortId != null) {
                "${data.deviceId}:${data.publicKeyHex}:${data.timestamp}:${data.shortId}".encodeToByteArray()
            } else {
                "${data.deviceId}:${data.publicKeyHex}:${data.timestamp}".encodeToByteArray()
            }
            val publicKey = PublicKey(publicKeyBytes)

            if (!libSignalManager.verify(publicKey, dataToVerify, signatureBytes)) {
                return Pair(false, "Invalid signature - QR code may be tampered")
            }

            // Store peer's public key
            keyManager.storePeerPublicKey(data.deviceId, publicKey)

            Pair(true, "Successfully distributed keys with ${data.deviceId}")
        } catch (e: Exception) {
            Pair(false, "Failed to parse QR code: ${e.message}")
        }
    }

}

/**
 * Convert ByteArray to hex string.
 */
fun ByteArray.toHexString(): String {
    return joinToString("") { byte ->
        val unsignedByte = (byte.toInt() and 0xFF)
        val hex = unsignedByte.toString(16)
        if (hex.length == 1) "0$hex" else hex
    }
}

/**
 * Convert hex string to ByteArray.
 */
fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

// ============================================================
// SIGNAL PROTOCOL KEY DISTRIBUTION (New)
// ============================================================

/**
 * Result of Signal-based QR generation.
 */
data class SignalKeyDistributionQRResult(
    val payloadJson: String,
    val shortId: String,
    val bundleUploaded: Boolean
)

/**
 * Result of processing a scanned QR code for Signal protocol.
 */
sealed class SignalScanResult {
    data class Success(
        val shortId: String,
        val identityKey: ByteArray
    ) : SignalScanResult()

    data class BundleFetchFailed(
        val shortId: String,
        val message: String
    ) : SignalScanResult()

    data class SessionBuildFailed(
        val shortId: String,
        val message: String
    ) : SignalScanResult()

    data class IdentityChanged(
        val shortId: String,
        val newIdentityKey: ByteArray
    ) : SignalScanResult()
}
