package net.discdd.trick

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.discdd.trick.contacts.ContactPickerResult
import net.discdd.trick.contacts.NativeContactsManager
import net.discdd.trick.contacts.rememberContactPickerLauncher
import net.discdd.trick.libsignal.createLibSignalManager
import net.discdd.trick.messaging.KeyExchangeBundle
import net.discdd.trick.screens.KeyExchangeScreen
import net.discdd.trick.screens.MultiQRScannerScreen
import net.discdd.trick.screens.QRScannerScreen
import net.discdd.trick.security.KeyExchangePayload
import net.discdd.trick.security.KeyExchangeQRResult
import net.discdd.trick.security.KeyManager
import net.discdd.trick.security.QRKeyExchange
import net.discdd.trick.security.TRCKY_ORG_BASE_URL
import net.discdd.trick.signal.PreKeyBundleData
import net.discdd.trick.signal.SignalSessionManager
import net.discdd.trick.util.ShortIdGenerator
import okio.ByteString.Companion.toByteString
import org.koin.core.context.GlobalContext
import kotlinx.serialization.json.Json

private fun String.hexToBytes(): ByteArray {
    check(length % 2 == 0)
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}

/**
 * Create a KeyExchangeBundle protobuf from identity payload and Signal prekey bundle.
 */
private fun createKeyExchangeBundle(
    deviceId: String,
    publicKeyHex: String,
    timestamp: Long,
    signatureHex: String,
    shortId: String,
    bundle: PreKeyBundleData
): KeyExchangeBundle {
    return KeyExchangeBundle(
        device_id = deviceId.toByteArray(Charsets.UTF_8).toByteString(),
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

/**
 * Encode a full key exchange payload (with Signal bundle) for QR codes.
 * Splits data into multiple QR codes for easier scanning.
 *
 * Each chunk format: [part_number (1 byte), total_parts (1 byte), ...data...]
 * Target: ~1000 bytes per QR -> Version 17-18 with M error correction (~85 modules)
 *
 * Returns list of ISO-8859-1 encoded strings (1:1 byte mapping) for ZXing byte mode.
 */
private const val QR_CHUNK_SIZE = 1000 // bytes per QR code (excluding 2-byte header)

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

    val protoBytes = protoBundle.encode()
    Log.d("KeyExchange", "Protobuf size: ${protoBytes.size} bytes, splitting into QR chunks")

    // Split into chunks
    val chunks = protoBytes.toList().chunked(QR_CHUNK_SIZE)
    val totalParts = chunks.size

    return chunks.mapIndexed { index, chunk ->
        val partNumber = index + 1
        // Header: [part_number, total_parts] + data
        val chunkWithHeader = byteArrayOf(partNumber.toByte(), totalParts.toByte()) + chunk.toByteArray()
        Log.d("KeyExchange", "QR part $partNumber/$totalParts: ${chunkWithHeader.size} bytes")
        String(chunkWithHeader, Charsets.ISO_8859_1)
    }
}

/**
 * Parse a single QR chunk and return (partNumber, totalParts, data).
 */
private fun parseQRChunk(data: String): Triple<Int, Int, ByteArray> {
    val bytes = data.toByteArray(Charsets.ISO_8859_1)
    require(bytes.size >= 2) { "QR chunk too small" }
    val partNumber = bytes[0].toInt() and 0xFF
    val totalParts = bytes[1].toInt() and 0xFF
    val chunkData = bytes.copyOfRange(2, bytes.size)
    return Triple(partNumber, totalParts, chunkData)
}

/**
 * Decode reassembled QR payload (raw Protobuf bytes) back to components.
 * Returns Pair of (KeyExchangePayload, PreKeyBundleData).
 */
private fun decodePayloadFromQR(protoBytes: ByteArray): Pair<KeyExchangePayload, PreKeyBundleData> {
    val bundle = KeyExchangeBundle.ADAPTER.decode(protoBytes)

    // Extract identity payload
    val payload = KeyExchangePayload(
        deviceId = String(bundle.device_id.toByteArray(), Charsets.UTF_8),
        publicKeyHex = bundle.public_key.toByteArray().toHexString(),
        timestamp = bundle.timestamp,
        signatureHex = bundle.signature.toByteArray().toHexString(),
        shortId = bundle.short_id,
        signalPreKeyBundleJson = null // Not used in protobuf format
    )

    // Extract Signal prekey bundle data
    // Validate required Kyber fields (libsignal 0.86.7+ requires Kyber post-quantum prekeys)
    require(bundle.kyber_prekey_id >= 0) {
        "Bundle missing required Kyber prekey ID. Kyber post-quantum cryptography is required (libsignal 0.86.7+)."
    }
    require(bundle.kyber_prekey_public.size > 0) {
        "Bundle missing required Kyber prekey public key. Kyber post-quantum cryptography is required (libsignal 0.86.7+)."
    }
    require(bundle.kyber_prekey_signature.size > 0) {
        "Bundle missing required Kyber prekey signature. Kyber post-quantum cryptography is required (libsignal 0.86.7+)."
    }
    
    val preKeyBundleData = PreKeyBundleData(
        registrationId = bundle.registration_id,
        deviceId = bundle.signal_device_id,
        preKeyId = bundle.prekey_id,
        preKeyPublic = bundle.prekey_public?.toByteArray(),
        signedPreKeyId = bundle.signed_prekey_id,
        signedPreKeyPublic = bundle.signed_prekey_public.toByteArray(),
        signedPreKeySignature = bundle.signed_prekey_signature.toByteArray(),
        identityKey = bundle.identity_key.toByteArray(),
        kyberPreKeyId = bundle.kyber_prekey_id,
        kyberPreKeyPublic = bundle.kyber_prekey_public.toByteArray(),
        kyberPreKeySignature = bundle.kyber_prekey_signature.toByteArray()
    )

    return Pair(payload, preKeyBundleData)
}

/**
 * Result of multi-QR code generation.
 */
private data class MultiQRResult(
    val payloads: List<String>,
    val shortId: String
)

/**
 * Pending key exchange data waiting for contact selection.
 */
private data class PendingKeyExchange(
    val deviceId: String,
    val publicKeyHex: String,
    val shortId: String
)

/**
 * Android wrapper for KeyExchangeScreen with KeyManager integration
 */
@Composable
fun AndroidKeyExchangeScreen(
    context: Context,
    deviceId: String,
    onNavigateBack: () -> Unit
) {
    val keyManager = remember { KeyManager(context) }
    val nativeContactsManager = remember { GlobalContext.get().get<NativeContactsManager>() }
    val libSignalManager = remember { createLibSignalManager() }
    val signalSessionManager = remember { GlobalContext.get().get<SignalSessionManager>() }
    val localContext = LocalContext.current
    val scope = rememberCoroutineScope()

    var showScanner by remember { mutableStateOf(false) }
    var trustedPeers by rememberSaveable { mutableStateOf(emptyList<String>()) }

    // State for pending key exchange (waiting for contact selection)
    var pendingKeyExchange by remember { mutableStateOf<PendingKeyExchange?>(null) }

    // State for accumulating scanned QR chunks
    var scannedChunks by remember { mutableStateOf<MutableMap<Int, ByteArray>>(mutableMapOf()) }
    var expectedTotalParts by remember { mutableStateOf(0) }

    // Ensure identity key pair exists
    LaunchedEffect(Unit) {
        if (keyManager.getIdentityKeyPair() == null) {
            keyManager.generateIdentityKeyPair()
        }
        trustedPeers = keyManager.getTrustedPeerIds()
    }

    // Multi-QR result that includes an embedded Signal prekey bundle with Kyber keys
    var qrResult by remember { mutableStateOf<MultiQRResult?>(null) }

    // Generate QR payloads with FULL Signal bundle including Kyber keys
    // Splits into multiple QR codes for easier scanning
    LaunchedEffect(deviceId) {
        withContext(Dispatchers.IO) {
            // Ensure Signal identity & prekeys are ready
            signalSessionManager.initialize()
            signalSessionManager.replenishPreKeysIfNeeded()

            // Get identity info for signature
            val baseResult = QRKeyExchange.generateQRPayload(
                keyManager = keyManager,
                libSignalManager = libSignalManager,
                deviceId = deviceId
            )

            // Get the full Signal prekey bundle (including Kyber)
            val signalBundle = signalSessionManager.generatePreKeyBundle()

            // Parse the base result to get identity fields
            val identityPayload = Json.decodeFromString<KeyExchangePayload>(baseResult.payloadJson)

            // Create QR payloads (split into multiple QR codes)
            val qrPayloads = encodePayloadForQR(
                deviceId = identityPayload.deviceId,
                publicKeyHex = identityPayload.publicKeyHex,
                timestamp = identityPayload.timestamp,
                signatureHex = identityPayload.signatureHex,
                shortId = baseResult.shortId,
                bundle = signalBundle
            )

            Log.d("KeyExchange", "Generated ${qrPayloads.size} QR codes")

            withContext(Dispatchers.Main) {
                qrResult = MultiQRResult(
                    payloads = qrPayloads,
                    shortId = baseResult.shortId
                )
            }
        }
    }

    val currentQrResult = qrResult

    // Contact picker launcher
    val launchContactPicker = rememberContactPickerLauncher { result: ContactPickerResult? ->
        val pending = pendingKeyExchange
        if (result != null && pending != null) {
            // Link the key data to the selected contact (including deviceId for WiFi Aware matching)
            val success = nativeContactsManager.linkTrickDataToContact(
                nativeContactId = result.rawContactId,
                shortId = pending.shortId,
                publicKeyHex = pending.publicKeyHex,
                deviceId = pending.deviceId
            )

            if (success) {
                Toast.makeText(
                    localContext,
                    "Key linked to ${result.displayName}",
                    Toast.LENGTH_LONG
                ).show()
                trustedPeers = keyManager.getTrustedPeerIds()
            } else {
                Toast.makeText(
                    localContext,
                    "Failed to link key to contact",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else if (result == null && pending != null) {
            Toast.makeText(
                localContext,
                "Contact selection cancelled",
                Toast.LENGTH_SHORT
            ).show()
        }
        pendingKeyExchange = null
    }

    if (showScanner) {
        // Reset chunks when scanner opens
        LaunchedEffect(showScanner) {
            if (showScanner) {
                scannedChunks = mutableMapOf()
                expectedTotalParts = 0
            }
        }

        MultiQRScannerScreen(
            scannedParts = scannedChunks.size,
            totalParts = expectedTotalParts,
            alreadyScannedChunkIds = scannedChunks.keys.toSet(),
            onQRCodeScanned = { qrCode: String ->
                scope.launch {
                    // Parse the chunk
                    val chunkResult: Triple<Int, Int, ByteArray> = try {
                        parseQRChunk(qrCode)
                    } catch (e: Exception) {
                        Log.e("KeyExchange", "Failed to parse QR chunk: ${e.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(localContext, "Invalid QR code format", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    val (partNumber, totalParts, chunkData) = chunkResult

                    Log.d("KeyExchange", "Scanned part $partNumber of $totalParts")

                    // Update state, check completion, and capture chunks for reassembly
                    val (allPartsReceived, chunksForReassembly) = withContext(Dispatchers.Main) {
                        expectedTotalParts = totalParts
                        scannedChunks = scannedChunks.toMutableMap().apply {
                            put(partNumber, chunkData)
                        }

                        // Show progress
                        Toast.makeText(
                            localContext,
                            "Scanned $partNumber of $totalParts",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Check if all parts received and capture chunks (inside Main context for consistent read)
                        val allReceived = scannedChunks.size >= totalParts
                        val chunks = if (allReceived) {
                            (1..totalParts).mapNotNull { scannedChunks[it] }
                        } else {
                            null
                        }
                        Pair(allReceived, chunks)
                    }

                    // Wait for more parts if not all received
                    if (!allPartsReceived || chunksForReassembly == null) {
                        return@launch
                    }

                    // Reassemble the payload
                    val reassembled = chunksForReassembly
                    if (reassembled.size != totalParts) {
                        Log.e("KeyExchange", "Missing chunks after reassembly")
                        return@launch
                    }

                    val protoBytes = reassembled.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
                    Log.d("KeyExchange", "Reassembled payload: ${protoBytes.size} bytes")

                    // Decode the reassembled payload
                    val (decodedPayload, bundleData) = try {
                        decodePayloadFromQR(protoBytes)
                    } catch (e: Exception) {
                        Log.e("KeyExchange", "Failed to decode QR payload: ${e.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(localContext, "Invalid QR code format", Toast.LENGTH_LONG).show()
                            showScanner = false
                        }
                        return@launch
                    }

                    // Convert to JSON for QRKeyExchange verification
                    val payload = Json.encodeToString(decodedPayload)

                    // Verify signature and store peer public key
                    val (success, message) = QRKeyExchange.verifyAndStoreQRPayload(
                        payload = payload,
                        keyManager = keyManager,
                        libSignalManager = libSignalManager
                    )

                    if (!success) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(localContext, message, Toast.LENGTH_LONG).show()
                            showScanner = false
                        }
                        return@launch
                    }

                    try {
                        val peerShortId = decodedPayload.shortId
                            ?: ShortIdGenerator.generateShortIdFromHex(decodedPayload.publicKeyHex)
                        val peerId = decodedPayload.deviceId

                        withContext(Dispatchers.IO) {
                            try {
                                signalSessionManager.initialize()
                                signalSessionManager.buildSessionFromPreKeyBundle(
                                    peerId = peerId,
                                    deviceId = bundleData.deviceId,
                                    bundle = bundleData
                                )
                                signalSessionManager.replenishPreKeysIfNeeded()

                                withContext(Dispatchers.Main) {
                                    Log.d("KeyExchange", "Signal session built successfully with Kyber PQ ratchet")

                                    // Store pending exchange and launch contact picker
                                    pendingKeyExchange = PendingKeyExchange(
                                        deviceId = peerId,
                                        publicKeyHex = decodedPayload.publicKeyHex,
                                        shortId = peerShortId
                                    )

                                    Toast.makeText(
                                        localContext,
                                        "Secure session established! Select a contact to link.",
                                        Toast.LENGTH_SHORT
                                    ).show()

                                    showScanner = false
                                    launchContactPicker()
                                }
                            } catch (e: Exception) {
                                Log.e("KeyExchange", "Failed to build Signal session: ${e.message}", e)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        localContext,
                                        "Failed to establish secure session: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    showScanner = false
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(localContext, message, Toast.LENGTH_LONG).show()
                            trustedPeers = keyManager.getTrustedPeerIds()
                            showScanner = false
                        }
                    }
                }
            },
            onNavigateBack = {
                showScanner = false
            }
        )
    } else {
        val readyQr = currentQrResult
        if (readyQr == null) {
            // Show loading state while QR is being prepared
            KeyExchangeScreen(
                deviceId = deviceId,
                qrCodePayloads = emptyList(),
                displayUrl = "",
                isLoading = true,
                onCopyUrl = { _ -> },
                onShareUrl = { _ -> },
                trustedPeers = trustedPeers,
                onNavigateBack = onNavigateBack,
                onScanQR = { },
                onUntrust = { }
            )
        } else {
            KeyExchangeScreen(
                deviceId = deviceId,
                qrCodePayloads = readyQr.payloads,
                displayUrl = "$TRCKY_ORG_BASE_URL/${readyQr.shortId}",
                isLoading = false,
                onCopyUrl = { url ->
                    val clipboard = localContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
                    Toast.makeText(localContext, "URL copied", Toast.LENGTH_SHORT).show()
                },
                onShareUrl = { url ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    localContext.startActivity(Intent.createChooser(intent, "Share URL"))
                },
                trustedPeers = trustedPeers,
                onNavigateBack = onNavigateBack,
                onScanQR = {
                    showScanner = true
                },
                onUntrust = { peerId ->
                    // Get shortId for the peer and unlink from native contacts BEFORE removing from KeyManager
                    val peerPublicKey = keyManager.getPeerPublicKey(peerId)
                    if (peerPublicKey != null) {
                        val peerShortId = ShortIdGenerator.generateShortId(peerPublicKey)
                        nativeContactsManager.unlinkTrickData(peerShortId)
                    }

                    // Remove from KeyManager after we've retrieved the data we need
                    keyManager.removePeerPublicKey(peerId)

                    trustedPeers = keyManager.getTrustedPeerIds()
                    Toast.makeText(localContext, "Removed trust for $peerId", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
