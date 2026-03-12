package org.trick.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.trick.libsignal.LibSignalManager
import org.trick.libsignal.PublicKey
import org.trick.util.ShortIdGenerator
import org.trick.data.currentTimeMillis
import org.trick.signal.SignalNativeBridge
import org.trick.signal.SignalSessionManager
import org.trick.util.sha256

/**
 * QR code hash commitment payload.
 *
 * Contains a SHA-256 commitment over the full Signal prekey bundle, plus an
 * Ed25519 signature for authenticity. The full bundle is exchanged over TCP
 * after WiFi Aware discovery; the QR code merely authenticates it.
 *
 * Estimated JSON size: ~420 chars — fits in a single QR code at error-correction L.
 */
@Serializable
data class QRHashPayload(
    val deviceId: String,
    val shortId: String,
    val identityKeyHex: String,   // Ed25519 public key hex (64 chars = 32 bytes)
    val combinedHashHex: String,  // SHA-256(identityKey || signedPreKeyPublic || kyberPreKeyPublic) hex (64 chars)
    val signatureHex: String,     // Ed25519 signature hex (128 chars = 64 bytes)
    val timestamp: Long
)

/**
 * Result of generating a QR hash payload.
 */
data class QRHashResult(
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
 * QRKeyDistribution handles generation and verification of single-QR hash commitment payloads.
 *
 * Protocol:
 * 1. Device A generates QRHashPayload (SHA-256 commitment + Ed25519 signature) → single QR code.
 * 2. Device B scans, verifies signature, stores (deviceId → hash) commitment in memory.
 * 3. After WiFi Aware TCP connection, both sides exchange full prekey bundles.
 * 4. Each side verifies: SHA-256(received bundle fields) == stored commitment.
 * 5. On match, buildSessionFromPreKeyBundle() is called to establish the Signal session.
 */
object QRKeyDistribution {

    // In-memory commitment store: deviceId → 32-byte SHA-256 hash
    private val pendingHashCommitments = mutableMapOf<String, ByteArray>()

    fun storeCommitment(deviceId: String, hash: ByteArray) {
        pendingHashCommitments[deviceId] = hash
    }

    fun getCommitment(deviceId: String): ByteArray? = pendingHashCommitments[deviceId]

    fun clearCommitment(deviceId: String) {
        pendingHashCommitments.remove(deviceId)
    }

    /**
     * Generate a QR hash commitment payload for this device.
     *
     * Computes SHA-256(identityKey || signedPreKeyPublic || kyberPreKeyPublic),
     * signs "$deviceId:$hashHex:$timestamp" with the identity private key,
     * and returns the JSON-encoded QRHashPayload.
     */
    suspend fun generateQRHashPayload(
        signalSessionManager: SignalSessionManager,
        deviceId: String
    ): QRHashResult {
        val bundle = signalSessionManager.generatePreKeyBundle()
        val kyberPublic = bundle.kyberPreKeyPublic
            ?: throw IllegalStateException("Kyber prekey is required")

        // SHA-256(identityKey || signedPreKeyPublic || kyberPreKeyPublic)
        val combinedBytes = bundle.identityKey + bundle.signedPreKeyPublic + kyberPublic
        val combinedHash = sha256(combinedBytes)
        val combinedHashHex = combinedHash.toHexString()
        val identityKeyHex = bundle.identityKey.toHexString()
        val shortId = signalSessionManager.getShortId()
        val timestamp = currentTimeMillis()

        // Sign "$deviceId:$combinedHashHex:$timestamp"
        val dataToSign = "$deviceId:$combinedHashHex:$timestamp".encodeToByteArray()
        val signature = signalSessionManager.signWithIdentityKey(dataToSign)
        val signatureHex = signature.toHexString()

        val payload = QRHashPayload(
            deviceId = deviceId,
            shortId = shortId,
            identityKeyHex = identityKeyHex,
            combinedHashHex = combinedHashHex,
            signatureHex = signatureHex,
            timestamp = timestamp
        )
        return QRHashResult(payloadJson = Json.encodeToString(payload), shortId = shortId)
    }

    /**
     * Verify a scanned QR hash payload and store the hash commitment on success.
     *
     * Verification steps:
     * 1. Decode JSON.
     * 2. Verify Ed25519 signature with the included identity public key.
     * 3. Store (deviceId → combinedHash) in memory for later TCP bundle verification.
     *
     * @return Pair(success, deviceId or error message).
     */
    fun verifyAndStoreQRHashPayload(payload: String): Pair<Boolean, String> {
        return try {
            val data = Json.decodeFromString<QRHashPayload>(payload)

            val identityKeyBytes = data.identityKeyHex.hexToByteArray()
            val combinedHashBytes = data.combinedHashHex.hexToByteArray()
            val signatureBytes = data.signatureHex.hexToByteArray()

            // Verify signature: "$deviceId:$combinedHashHex:$timestamp"
            val dataToVerify = "${data.deviceId}:${data.combinedHashHex}:${data.timestamp}".encodeToByteArray()
            if (!SignalNativeBridge.publicKeyVerify(identityKeyBytes, dataToVerify, signatureBytes)) {
                return Pair(false, "Invalid signature — QR code may be tampered")
            }

            storeCommitment(data.deviceId, combinedHashBytes)
            Pair(true, data.deviceId)
        } catch (e: Exception) {
            Pair(false, "Failed to parse QR code: ${e.message}")
        }
    }
}

// ============================================================
// LEGACY API — used by iOS key distribution (IOSKeyDistributionIntegration.kt)
// Android has switched to the single-QR hash commitment protocol above.
// ============================================================

@Serializable
data class KeyDistributionPayload(
    val deviceId: String,
    val publicKeyHex: String,
    val timestamp: Long,
    val signatureHex: String,
    val shortId: String? = null
)

data class KeyDistributionQRResult(
    val payloadJson: String,
    val shortId: String
)

fun QRKeyDistribution.generateQRPayload(
    keyManager: KeyManager,
    libSignalManager: LibSignalManager,
    deviceId: String
): KeyDistributionQRResult {
    val keyPair = keyManager.getIdentityKeyPair() ?: keyManager.generateIdentityKeyPair()
    val timestamp = 0L
    val publicKeyHex = keyPair.publicKey.data.toHexString()
    val shortId = ShortIdGenerator.generateShortId(keyPair.publicKey)
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
    return KeyDistributionQRResult(payloadJson = Json.encodeToString(payload), shortId = shortId)
}

fun QRKeyDistribution.verifyAndStoreQRPayload(
    payload: String,
    keyManager: KeyManager,
    libSignalManager: LibSignalManager
): Pair<Boolean, String> {
    return try {
        val data = Json.decodeFromString<KeyDistributionPayload>(payload)
        val publicKeyBytes = data.publicKeyHex.hexToByteArray()
        val signatureBytes = data.signatureHex.hexToByteArray()
        val dataToVerify = if (data.shortId != null) {
            "${data.deviceId}:${data.publicKeyHex}:${data.timestamp}:${data.shortId}".encodeToByteArray()
        } else {
            "${data.deviceId}:${data.publicKeyHex}:${data.timestamp}".encodeToByteArray()
        }
        val publicKey = PublicKey(publicKeyBytes)
        if (!libSignalManager.verify(publicKey, dataToVerify, signatureBytes)) {
            return Pair(false, "Invalid signature - QR code may be tampered")
        }
        keyManager.storePeerPublicKey(data.deviceId, publicKey)
        Pair(true, "Successfully distributed keys with ${data.deviceId}")
    } catch (e: Exception) {
        Pair(false, "Failed to parse QR code: ${e.message}")
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
