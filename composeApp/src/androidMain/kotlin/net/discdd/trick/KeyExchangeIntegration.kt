package net.discdd.trick

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import net.discdd.trick.screens.KeyExchangeScreen
import net.discdd.trick.screens.QRScannerScreen
import net.discdd.trick.security.KeyExchangePayload
import net.discdd.trick.security.KeyManager
import net.discdd.trick.security.QRKeyExchange
import net.discdd.trick.security.TRCKY_ORG_BASE_URL
import net.discdd.trick.security.TrckyOrgPayloadResolver
import net.discdd.trick.security.parseTrckyShortId
import net.discdd.trick.util.ShortIdGenerator
import org.koin.core.context.GlobalContext
import kotlinx.serialization.json.Json

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
    val payloadResolver = remember { TrckyOrgPayloadResolver() }
    val localContext = LocalContext.current
    val scope = rememberCoroutineScope()

    var showScanner by remember { mutableStateOf(false) }
    var trustedPeers by rememberSaveable { mutableStateOf(emptyList<String>()) }

    // State for pending key exchange (waiting for contact selection)
    var pendingKeyExchange by remember { mutableStateOf<PendingKeyExchange?>(null) }

    // Ensure identity key pair exists
    LaunchedEffect(Unit) {
        if (keyManager.getIdentityKeyPair() == null) {
            keyManager.generateIdentityKeyPair()
        }
        trustedPeers = keyManager.getTrustedPeerIds()
    }

    // Generate QR payload and display URL
    val qrResult = remember(deviceId) {
        QRKeyExchange.generateQRPayload(
            keyManager = keyManager,
            libSignalManager = libSignalManager,
            deviceId = deviceId
        )
    }
    val qrCodePayload = qrResult.payloadJson
    val displayUrl = "$TRCKY_ORG_BASE_URL/${qrResult.shortId}"

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
        QRScannerScreen(
            onQRCodeScanned = { qrCode ->
                scope.launch {
                    val shortId = parseTrckyShortId(qrCode)
                    val payloadToVerify = if (shortId != null) {
                        payloadResolver.fetchPayloadByShortId(shortId)
                    } else {
                        null
                    }
                    when {
                        shortId != null && payloadToVerify == null -> {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(localContext, "Key not found", Toast.LENGTH_LONG).show()
                                showScanner = false
                            }
                        }
                        else -> {
                            val payload = payloadToVerify ?: qrCode
                            val (success, message) = QRKeyExchange.verifyAndStoreQRPayload(
                                payload = payload,
                                keyManager = keyManager,
                                libSignalManager = libSignalManager
                            )
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    // Parse payload to get key data for contact linking
                                    try {
                                        val data = Json.decodeFromString<KeyExchangePayload>(payload)
                                        val peerShortId = data.shortId ?: ShortIdGenerator.generateShortIdFromHex(data.publicKeyHex)

                                        // Store pending exchange and launch contact picker
                                        pendingKeyExchange = PendingKeyExchange(
                                            deviceId = data.deviceId,
                                            publicKeyHex = data.publicKeyHex,
                                            shortId = peerShortId
                                        )

                                        Toast.makeText(
                                            localContext,
                                            "Key verified! Select a contact to link.",
                                            Toast.LENGTH_SHORT
                                        ).show()

                                        showScanner = false
                                        launchContactPicker()
                                    } catch (e: Exception) {
                                        Toast.makeText(localContext, message, Toast.LENGTH_LONG).show()
                                        trustedPeers = keyManager.getTrustedPeerIds()
                                        showScanner = false
                                    }
                                } else {
                                    Toast.makeText(localContext, message, Toast.LENGTH_LONG).show()
                                    showScanner = false
                                }
                            }
                        }
                    }
                }
            },
            onNavigateBack = {
                showScanner = false
            }
        )
    } else {
        KeyExchangeScreen(
            deviceId = deviceId,
            qrCodePayload = qrCodePayload,
            displayUrl = displayUrl,
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
