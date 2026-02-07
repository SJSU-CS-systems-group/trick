package net.discdd.trick

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.discdd.trick.libsignal.createLibSignalManager
import net.discdd.trick.messaging.KeyExchangeBundle
import net.discdd.trick.screens.KeyExchangeScreen
import net.discdd.trick.security.KeyExchangePayload
import net.discdd.trick.security.KeyManager
import net.discdd.trick.security.QRKeyExchange
import net.discdd.trick.security.TRCKY_ORG_BASE_URL
import net.discdd.trick.signal.PreKeyBundleData
import net.discdd.trick.signal.SignalSessionManager
import okio.ByteString.Companion.toByteString
import org.koin.compose.koinInject
import platform.UIKit.UIPasteboard

private fun String.hexToBytes(): ByteArray {
    check(length % 2 == 0)
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun ByteArray.toHexString(): String {
    return joinToString("") { byte ->
        val i = byte.toInt() and 0xFF
        val hex = i.toString(16)
        if (hex.length == 1) "0$hex" else hex
    }
}

/** ISO-8859-1: each byte maps 1:1 to the same Unicode code point. */
private fun ByteArray.toIso8859String(): String = buildString {
    this@toIso8859String.forEach { append((it.toInt() and 0xFF).toChar()) }
}

/** ISO-8859-1: each char's code point maps 1:1 to a byte. */
private fun String.toIso8859Bytes(): ByteArray =
    ByteArray(length) { i -> this[i].code.toByte() }

private fun createKeyExchangeBundle(
    deviceId: String,
    publicKeyHex: String,
    timestamp: Long,
    signatureHex: String,
    shortId: String,
    bundle: PreKeyBundleData
): KeyExchangeBundle {
    return KeyExchangeBundle(
        device_id = deviceId.encodeToByteArray().toByteString(),
        public_key = publicKeyHex.hexToBytes().toByteString(),
        timestamp = timestamp,
        signature = signatureHex.hexToBytes().toByteString(),
        short_id = shortId,
        registration_id = bundle.registrationId,
        signal_device_id = bundle.deviceId,
        prekey_id = bundle.preKeyId,
        prekey_public = bundle.preKeyPublic?.toByteString(),
        signed_prekey_id = bundle.signedPreKeyId,
        signed_prekey_public = bundle.signedPreKeyPublic.toByteString(),
        signed_prekey_signature = bundle.signedPreKeySignature.toByteString(),
        identity_key = bundle.identityKey.toByteString(),
        kyber_prekey_id = bundle.kyberPreKeyId ?: 1,
        kyber_prekey_public = bundle.kyberPreKeyPublic?.toByteString()
            ?: throw IllegalArgumentException("Kyber prekey is required"),
        kyber_prekey_signature = bundle.kyberPreKeySignature?.toByteString()
            ?: throw IllegalArgumentException("Kyber signature is required")
    )
}

private const val TARGET_SINGLE_QR_BYTES = 1900
private const val MAX_QR_PARTS = 2

private fun encodePayloadForQR(
    deviceId: String,
    publicKeyHex: String,
    timestamp: Long,
    signatureHex: String,
    shortId: String,
    bundle: PreKeyBundleData
): List<String> {
    val protoBundle = createKeyExchangeBundle(
        deviceId = deviceId,
        publicKeyHex = publicKeyHex,
        timestamp = timestamp,
        signatureHex = signatureHex,
        shortId = shortId,
        bundle = bundle
    )

    val protoBytes: ByteArray = protoBundle.encode()
    val totalSize = protoBytes.size

    val (chunks, totalParts) = if (totalSize <= TARGET_SINGLE_QR_BYTES) {
        listOf(protoBytes.toList()) to 1
    } else {
        val partCount = MAX_QR_PARTS
        val chunkSize = (totalSize + partCount - 1) / partCount
        val splitChunks = protoBytes.toList().chunked(chunkSize)

        val normalizedChunks = if (splitChunks.size <= MAX_QR_PARTS) {
            splitChunks
        } else {
            val head = splitChunks.take(MAX_QR_PARTS - 1)
            val tailMerged = splitChunks.drop(MAX_QR_PARTS - 1).flatten()
            head + listOf(tailMerged)
        }

        normalizedChunks to normalizedChunks.size
    }

    return chunks.mapIndexed { index, chunk ->
        val partNumber = index + 1
        val chunkWithHeader = byteArrayOf(partNumber.toByte(), totalParts.toByte()) + chunk.toByteArray()
        chunkWithHeader.toIso8859String()
    }
}

private fun parseQRChunk(data: String): Triple<Int, Int, ByteArray> {
    val bytes = data.toIso8859Bytes()
    require(bytes.size >= 2) { "QR chunk too small" }
    val partNumber = bytes[0].toInt() and 0xFF
    val totalParts = bytes[1].toInt() and 0xFF
    val chunkData = bytes.copyOfRange(2, bytes.size)
    return Triple(partNumber, totalParts, chunkData)
}

@Composable
fun IOSKeyExchangeScreen(
    deviceId: String,
    onNavigateBack: () -> Unit
) {
    val keyManager = remember { KeyManager() }
    val libSignalManager = remember { createLibSignalManager() }
    val signalSessionManager: SignalSessionManager = koinInject()

    var qrPayloads by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var trustedPeers by remember { mutableStateOf(emptyList<String>()) }
    var shortId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (keyManager.getIdentityKeyPair() == null) {
            keyManager.generateIdentityKeyPair()
        }
        trustedPeers = keyManager.getTrustedPeerIds()
    }

    LaunchedEffect(deviceId) {
        withContext(Dispatchers.Default) {
            signalSessionManager.initialize()
            signalSessionManager.replenishPreKeysIfNeeded()

            val baseResult = QRKeyExchange.generateQRPayload(
                keyManager = keyManager,
                libSignalManager = libSignalManager,
                deviceId = deviceId
            )

            val signalBundle = signalSessionManager.generatePreKeyBundle()
            val identityPayload = Json.decodeFromString<KeyExchangePayload>(baseResult.payloadJson)

            val payloads = encodePayloadForQR(
                deviceId = identityPayload.deviceId,
                publicKeyHex = identityPayload.publicKeyHex,
                timestamp = identityPayload.timestamp,
                signatureHex = identityPayload.signatureHex,
                shortId = baseResult.shortId,
                bundle = signalBundle
            )

            val orderedPayloads = payloads.sortedBy { payload ->
                val (partNumber, _, _) = parseQRChunk(payload)
                partNumber
            }

            withContext(Dispatchers.Main) {
                qrPayloads = orderedPayloads
                shortId = baseResult.shortId
                isLoading = false
            }
        }
    }

    KeyExchangeScreen(
        deviceId = deviceId,
        qrCodePayloads = qrPayloads,
        displayUrl = if (shortId.isNotBlank()) "$TRCKY_ORG_BASE_URL/$shortId" else "",
        isLoading = isLoading,
        onCopyUrl = { url ->
            UIPasteboard.generalPasteboard.string = url
        },
        onShareUrl = { url ->
            UIPasteboard.generalPasteboard.string = url
        },
        trustedPeers = trustedPeers,
        onNavigateBack = onNavigateBack,
        onScanQR = {
            // TODO: Implement iOS QR scanner
        },
        onUntrust = { peerId ->
            keyManager.removePeerPublicKey(peerId)
            trustedPeers = keyManager.getTrustedPeerIds()
        }
    )
}
