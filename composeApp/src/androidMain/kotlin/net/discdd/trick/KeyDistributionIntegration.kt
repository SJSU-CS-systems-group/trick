@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

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
import net.discdd.trick.messaging.KeyDistributionBundle
import net.discdd.trick.screens.KeyDistributionScreen
import net.discdd.trick.screens.MultiQRScannerScreen
import net.discdd.trick.screens.QRScannerScreen
import net.discdd.trick.security.KeyDistributionPayload
import net.discdd.trick.security.KeyDistributionQRResult
import net.discdd.trick.security.KeyManager
import net.discdd.trick.security.QRKeyDistribution
import net.discdd.trick.security.TRCKY_ORG_BASE_URL
import net.discdd.trick.signal.PreKeyBundleData
import net.discdd.trick.signal.SignalSessionManager
import net.discdd.trick.util.ShortIdGenerator
import okio.ByteString.Companion.toByteString
import org.koin.core.context.GlobalContext
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

private fun String.hexToBytes(): ByteArray {
    check(length % 2 == 0)
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}

/**
 * Create a KeyDistributionBundle protobuf from identity payload and Signal prekey bundle.
 */
private fun createKeyDistributionBundle(
    deviceId: String,
    publicKeyHex: String,
    timestamp: Long,
    signatureHex: String,
    shortId: String,
    bundle: PreKeyBundleData
): KeyDistributionBundle {
    return KeyDistributionBundle(
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
 * Encode a full key distribution payload (with Signal bundle) for QR codes.
 * Splits data into multiple QR codes for easier scanning.
 *
 * Each chunk format: [part_number (1 byte), total_parts (1 byte), ...data...]
 * Target: <= ~1900 bytes for a single QR (when possible) with L/M error correction.
 *
 * We always cap at **two** QR codes:
 * - If the full protobuf fits comfortably in a single QR, use 1 part.
 * - Otherwise, split into exactly 2 parts of (roughly) equal size.
 *
 * Returns list of Base64-encoded strings for cross-platform QR compatibility.
 */
// Conservative capacity for a single QR in byte mode with ErrorCorrectionLevel.L.
// Version 40-L can hold up to 2,953 bytes; we stay well under that for robustness.
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
    val protoBundle = createKeyDistributionBundle(
        deviceId = deviceId,
        publicKeyHex = publicKeyHex,
        timestamp = timestamp,
        signatureHex = signatureHex,
        shortId = shortId,
        bundle = bundle
    )

    val protoBytes = protoBundle.encode()
    val totalSize = protoBytes.size
    Log.d("KeyDistribution", "Protobuf size: $totalSize bytes (before QR chunking)")

    // Decide how many QR parts we need (1 or 2), and corresponding chunk size.
    val (chunks, totalParts) = if (totalSize <= TARGET_SINGLE_QR_BYTES) {
        // Single QR: no real chunking needed, but keep the same header format for compatibility.
        listOf(protoBytes.toList()) to 1
    } else {
        // Two-part QR: split as evenly as possible into at most MAX_QR_PARTS parts.
        val partCount = MAX_QR_PARTS
        val chunkSize = (totalSize + partCount - 1) / partCount // ceil(totalSize / partCount)
        val splitChunks = protoBytes.toList().chunked(chunkSize)

        // Safety: if for some reason we produced more than MAX_QR_PARTS chunks (shouldn't happen),
        // merge the extras into the last chunk to keep totalParts <= MAX_QR_PARTS.
        val normalizedChunks = if (splitChunks.size <= MAX_QR_PARTS) {
            splitChunks
        } else {
            val head = splitChunks.take(MAX_QR_PARTS - 1)
            val tailMerged = splitChunks.drop(MAX_QR_PARTS - 1).flatten()
            head + listOf(tailMerged)
        }

        normalizedChunks to normalizedChunks.size
    }

    Log.d("KeyDistribution", "Encoding into $totalParts QR part(s)")

    return chunks.mapIndexed { index, chunk ->
        val partNumber = index + 1
        val chunkWithHeader = byteArrayOf(partNumber.toByte(), totalParts.toByte()) + chunk.toByteArray()
        Log.d("KeyDistribution", "QR part $partNumber/$totalParts: ${chunkWithHeader.size} bytes")
        Base64.Default.encode(chunkWithHeader)
    }
}

/**
 * Parse a single QR chunk and return (partNumber, totalParts, data).
 */
private fun parseQRChunk(data: String): Triple<Int, Int, ByteArray> {
    val bytes = Base64.Default.decode(data)
    require(bytes.size >= 2) { "QR chunk too small" }
    val partNumber = bytes[0].toInt() and 0xFF
    val totalParts = bytes[1].toInt() and 0xFF
    val chunkData = bytes.copyOfRange(2, bytes.size)
    return Triple(partNumber, totalParts, chunkData)
}

/**
 * Decode reassembled QR payload (raw Protobuf bytes) back to components.
 * Returns Pair of (KeyDistributionPayload, PreKeyBundleData).
 */
private fun decodePayloadFromQR(protoBytes: ByteArray): Pair<KeyDistributionPayload, PreKeyBundleData> {
    val bundle = KeyDistributionBundle.ADAPTER.decode(protoBytes)

    // Extract identity payload
    val payload = KeyDistributionPayload(
        deviceId = String(bundle.device_id.toByteArray(), Charsets.UTF_8),
        publicKeyHex = bundle.public_key.toByteArray().toHexString(),
        timestamp = bundle.timestamp,
        signatureHex = bundle.signature.toByteArray().toHexString(),
        shortId = bundle.short_id
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
 * Pending key distribution data waiting for contact selection.
 */
private data class PendingKeyDistribution(
    val deviceId: String,
    val publicKeyHex: String,
    val shortId: String
)

/**
 * Android wrapper for KeyDistributionScreen with KeyManager integration
 */
@Composable
fun AndroidKeyDistributionScreen(
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

    // State for pending key distribution (waiting for contact selection)
    var pendingKeyDistribution by remember { mutableStateOf<PendingKeyDistribution?>(null) }

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
            val baseResult = QRKeyDistribution.generateQRPayload(
                keyManager = keyManager,
                libSignalManager = libSignalManager,
                deviceId = deviceId
            )

            // Get the full Signal prekey bundle (including Kyber)
            val signalBundle = signalSessionManager.generatePreKeyBundle()

            // Parse the base result to get identity fields
            val identityPayload = Json.decodeFromString<KeyDistributionPayload>(baseResult.payloadJson)

            // Create QR payloads (split into multiple QR codes)
            val qrPayloads = encodePayloadForQR(
                deviceId = identityPayload.deviceId,
                publicKeyHex = identityPayload.publicKeyHex,
                timestamp = identityPayload.timestamp,
                signatureHex = identityPayload.signatureHex,
                shortId = baseResult.shortId,
                bundle = signalBundle
            )

            Log.d("KeyDistribution", "Generated ${qrPayloads.size} QR codes")

            // Ensure QR codes are in deterministic order (1..N) based on embedded part numbers.
            // This guards against any accidental reordering so the UI always shows 1 -> 2 -> 3.
            val orderedPayloads =
                qrPayloads.sortedBy { payload ->
                    val (partNumber, _, _) = parseQRChunk(payload)
                    partNumber
                }

            withContext(Dispatchers.Main) {
                qrResult = MultiQRResult(
                    payloads = orderedPayloads,
                    shortId = baseResult.shortId
                )
            }
        }
    }

    val currentQrResult = qrResult

    // Contact picker launcher
    val launchContactPicker = rememberContactPickerLauncher { result: ContactPickerResult? ->
        val pending = pendingKeyDistribution
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
        pendingKeyDistribution = null
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
                        Log.e("KeyDistribution", "Failed to parse QR chunk: ${e.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(localContext, "Invalid QR code format", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    val (partNumber, totalParts, chunkData) = chunkResult

                    Log.d("KeyDistribution", "Scanned part $partNumber of $totalParts")

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
                        Log.e("KeyDistribution", "Missing chunks after reassembly")
                        return@launch
                    }

                    val protoBytes = reassembled.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
                    Log.d("KeyDistribution", "Reassembled payload: ${protoBytes.size} bytes")

                    // Decode the reassembled payload
                    val (decodedPayload, bundleData) = try {
                        decodePayloadFromQR(protoBytes)
                    } catch (e: Exception) {
                        Log.e("KeyDistribution", "Failed to decode QR payload: ${e.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(localContext, "Invalid QR code format", Toast.LENGTH_LONG).show()
                            showScanner = false
                        }
                        return@launch
                    }

                    // Convert to JSON for QRKeyDistribution verification
                    val payload = Json.encodeToString(decodedPayload)

                    // Verify signature and store peer public key
                    val (success, message) = QRKeyDistribution.verifyAndStoreQRPayload(
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
                                    Log.d("KeyDistribution", "Signal session built successfully with Kyber PQ ratchet")

                                    // Store pending distribution and launch contact picker
                                    pendingKeyDistribution = PendingKeyDistribution(
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
                                Log.e("KeyDistribution", "Failed to build Signal session: ${e.message}", e)
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
            KeyDistributionScreen(
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
            KeyDistributionScreen(
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
