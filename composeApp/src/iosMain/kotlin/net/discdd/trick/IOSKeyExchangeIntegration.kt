@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package net.discdd.trick

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.discdd.trick.contacts.IOSContactPickerScreen
import net.discdd.trick.contacts.NativeContactsManager
import net.discdd.trick.libsignal.createLibSignalManager
import net.discdd.trick.messaging.KeyExchangeBundle
import net.discdd.trick.screens.IOSMultiQRScannerScreen
import net.discdd.trick.screens.KeyExchangeScreen
import net.discdd.trick.security.KeyExchangePayload
import net.discdd.trick.security.KeyManager
import net.discdd.trick.security.QRKeyExchange
import net.discdd.trick.security.TRCKY_ORG_BASE_URL
import net.discdd.trick.signal.PreKeyBundleData
import net.discdd.trick.signal.SignalSessionManager
import net.discdd.trick.util.ShortIdGenerator
import okio.ByteString.Companion.toByteString
import org.koin.compose.koinInject
import kotlin.io.encoding.Base64
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
        Base64.Default.encode(chunkWithHeader)
    }
}

/**
 * Parse a single QR chunk and return (partNumber, totalParts, data).
 */
private fun parseQRChunk(data: String): Triple<Int, Int, ByteArray> {
    val bytes = Base64.Default.decode(data)
    require(bytes.size >= 2) { "QR chunk too small" }
    val partNumber: Int = bytes[0].toInt() and 0xFF
    val totalParts: Int = bytes[1].toInt() and 0xFF
    val chunkData: ByteArray = bytes.copyOfRange(2, bytes.size)
    return Triple(partNumber, totalParts, chunkData)
}

/**
 * Decode reassembled QR payload (raw Protobuf bytes) back to components.
 * Returns Pair of (KeyExchangePayload, PreKeyBundleData).
 */
private fun decodePayloadFromQR(protoBytes: ByteArray): Pair<KeyExchangePayload, PreKeyBundleData> {
    val bundle = KeyExchangeBundle.ADAPTER.decode(protoBytes)

    val payload = KeyExchangePayload(
        deviceId = bundle.device_id.toByteArray().decodeToString(),
        publicKeyHex = bundle.public_key.toByteArray().toHexString(),
        timestamp = bundle.timestamp,
        signatureHex = bundle.signature.toByteArray().toHexString(),
        shortId = bundle.short_id
    )

    require(bundle.kyber_prekey_id >= 0) {
        "Bundle missing required Kyber prekey ID."
    }
    require(bundle.kyber_prekey_public.size > 0) {
        "Bundle missing required Kyber prekey public key."
    }
    require(bundle.kyber_prekey_signature.size > 0) {
        "Bundle missing required Kyber prekey signature."
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
 * Pending key exchange data waiting for contact selection.
 */
private data class PendingKeyExchange(
    val deviceId: String,
    val publicKeyHex: String,
    val shortId: String
)

@Composable
fun IOSKeyExchangeScreen(
    deviceId: String,
    onNavigateBack: () -> Unit
) {
    val keyManager = remember { KeyManager() }
    val nativeContactsManager: NativeContactsManager = koinInject()
    val libSignalManager = remember { createLibSignalManager() }
    val signalSessionManager: SignalSessionManager = koinInject()
    val scope = rememberCoroutineScope()

    var qrPayloads by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var trustedPeers by remember { mutableStateOf(emptyList<String>()) }
    var shortId by remember { mutableStateOf("") }

    // Scanner state
    var showScanner by remember { mutableStateOf(false) }
    var scannedChunks by remember { mutableStateOf<Map<Int, ByteArray>>(emptyMap()) }
    var expectedTotalParts by remember { mutableStateOf(0) }

    // Contact picker state (pending key exchange waiting for contact selection)
    var pendingKeyExchange by remember { mutableStateOf<PendingKeyExchange?>(null) }
    var showContactPicker by remember { mutableStateOf(false) }

    // Success dialog state
    var showSuccessDialog by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf("") }

    // Initialize identity keys on a background thread
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.Default) {
                if (keyManager.getIdentityKeyPair() == null) {
                    keyManager.generateIdentityKeyPair()
                }
            }
            trustedPeers = keyManager.getTrustedPeerIds()
        } catch (e: Throwable) {
            println("Error initializing identity keys: ${e.message}")
            e.printStackTrace()
            errorMessage = "Failed to initialize identity keys: ${e.message}"
        }
    }

    // Generate QR payloads on background thread
    LaunchedEffect(deviceId) {
        try {
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
        } catch (e: Throwable) {
            println("Error generating key exchange payload: ${e.message}")
            e.printStackTrace()
            errorMessage = "Failed to generate key exchange: ${e.message}"
            isLoading = false
        }
    }

    // Success dialog
    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showSuccessDialog = false },
            title = { Text("Key Exchange Complete") },
            text = { Text(successMessage) },
            confirmButton = {
                TextButton(onClick = { showSuccessDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showScanner) {
        // Reset chunks when scanner opens
        LaunchedEffect(showScanner) {
            if (showScanner) {
                scannedChunks = emptyMap()
                expectedTotalParts = 0
            }
        }

        IOSMultiQRScannerScreen(
            scannedParts = scannedChunks.size,
            totalParts = expectedTotalParts,
            alreadyScannedChunkIds = scannedChunks.keys.toSet(),
            onQRCodeScanned = { qrCode: String ->
                scope.launch {
                    // Parse the chunk
                    val chunkResult: Triple<Int, Int, ByteArray> = try {
                        parseQRChunk(qrCode)
                    } catch (e: Exception) {
                        println("Failed to parse QR chunk: ${e.message}")
                        return@launch
                    }
                    val (partNumber, totalParts, chunkData) = chunkResult

                    println("Scanned part $partNumber of $totalParts")

                    // Update state and check for completion
                    expectedTotalParts = totalParts
                    scannedChunks = scannedChunks + (partNumber to chunkData)

                    // Check if all parts received
                    if (scannedChunks.size < totalParts) {
                        return@launch
                    }

                    // Reassemble the payload
                    val reassembled = (1..totalParts).mapNotNull { scannedChunks[it] }
                    if (reassembled.size != totalParts) {
                        println("Missing chunks after reassembly")
                        return@launch
                    }

                    val protoBytes = reassembled.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
                    println("Reassembled payload: ${protoBytes.size} bytes")

                    // Decode the reassembled payload
                    val (decodedPayload, bundleData) = try {
                        decodePayloadFromQR(protoBytes)
                    } catch (e: Exception) {
                        println("Failed to decode QR payload: ${e.message}")
                        showScanner = false
                        return@launch
                    }

                    // Verify signature and store peer public key
                    val payloadJson = Json.encodeToString(decodedPayload)
                    val (success, message) = QRKeyExchange.verifyAndStoreQRPayload(
                        payload = payloadJson,
                        keyManager = keyManager,
                        libSignalManager = libSignalManager
                    )

                    if (!success) {
                        println("QR verification failed: $message")
                        showScanner = false
                        return@launch
                    }

                    // Build Signal session
                    try {
                        val peerShortId = decodedPayload.shortId
                            ?: ShortIdGenerator.generateShortIdFromHex(decodedPayload.publicKeyHex)
                        val peerId = decodedPayload.deviceId

                        withContext(Dispatchers.Default) {
                            signalSessionManager.initialize()
                            signalSessionManager.buildSessionFromPreKeyBundle(
                                peerId = peerId,
                                deviceId = bundleData.deviceId,
                                bundle = bundleData
                            )
                            signalSessionManager.replenishPreKeysIfNeeded()
                        }

                        println("Signal session built successfully with peer $peerId")

                        trustedPeers = keyManager.getTrustedPeerIds()
                        showScanner = false

                        // Store pending exchange and launch contact picker
                        pendingKeyExchange = PendingKeyExchange(
                            deviceId = peerId,
                            publicKeyHex = decodedPayload.publicKeyHex,
                            shortId = peerShortId
                        )
                        showContactPicker = true
                    } catch (e: Exception) {
                        println("Failed to build Signal session: ${e.message}")
                        e.printStackTrace()
                        trustedPeers = keyManager.getTrustedPeerIds()
                        showScanner = false
                    }
                }
            },
            onNavigateBack = {
                showScanner = false
            }
        )
    } else if (showContactPicker) {
        IOSContactPickerScreen(
            onContactPicked = { result ->
                val pending = pendingKeyExchange
                if (pending != null) {
                    // Register the iOS contact mapping before linking
                    nativeContactsManager.registerContactMapping(
                        nativeContactId = result.nativeContactId,
                        contactIdentifier = result.contactIdentifier,
                        displayName = result.displayName
                    )

                    val success = nativeContactsManager.linkTrickDataToContact(
                        nativeContactId = result.nativeContactId,
                        shortId = pending.shortId,
                        publicKeyHex = pending.publicKeyHex,
                        deviceId = pending.deviceId
                    )

                    if (success) {
                        successMessage = "Key linked to ${result.displayName}"
                    } else {
                        successMessage = "Secure session established (contact linking failed)"
                    }
                    trustedPeers = keyManager.getTrustedPeerIds()
                } else {
                    successMessage = "Secure session established"
                }

                pendingKeyExchange = null
                showContactPicker = false
                showSuccessDialog = true
            },
            onDismiss = {
                // User cancelled contact selection — session is still valid
                pendingKeyExchange = null
                showContactPicker = false
                successMessage = "Secure session established"
                showSuccessDialog = true
            }
        )
    } else if (errorMessage != null) {
        @OptIn(ExperimentalMaterial3Api::class)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Key Exchange") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Error",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage ?: "Unknown error",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    } else {
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
                showScanner = true
            },
            onUntrust = { peerId ->
                // Get shortId for the peer and unlink from contacts BEFORE removing from KeyManager
                val peerPublicKey = keyManager.getPeerPublicKey(peerId)
                if (peerPublicKey != null) {
                    val peerShortId = ShortIdGenerator.generateShortId(peerPublicKey)
                    nativeContactsManager.unlinkTrickData(peerShortId)
                }

                keyManager.removePeerPublicKey(peerId)
                trustedPeers = keyManager.getTrustedPeerIds()
            }
        )
    }
}
