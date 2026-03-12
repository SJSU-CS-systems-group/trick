@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package org.trick

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.trick.contacts.ContactPickerResult
import org.trick.contacts.NativeContactsManager
import org.trick.contacts.rememberContactPickerLauncher
import org.trick.screens.KeyDistributionScreen
import org.trick.screens.QRScannerScreen
import org.trick.security.QRHashPayload
import org.trick.security.QRHashResult
import org.trick.security.QRKeyDistribution
import org.trick.security.TRCKY_ORG_BASE_URL
import org.trick.signal.SignalSessionManager
import org.koin.core.context.GlobalContext

/**
 * Pending key distribution data waiting for contact selection.
 */
private data class PendingKeyDistribution(
    val deviceId: String,
    val shortId: String,
    val identityKeyHex: String
)

/**
 * Android wrapper for KeyDistributionScreen — single QR code hash commitment flow.
 *
 * The QR code contains only a SHA-256 commitment over the peer's Signal prekey bundle
 * (~420 chars JSON, well within single-QR capacity). The full bundle is exchanged over
 * TCP after WiFi Aware discovery and verified against this commitment before building
 * the Signal session.
 */
@Composable
fun AndroidKeyDistributionScreen(
    context: Context,
    deviceId: String,
    onNavigateBack: () -> Unit
) {
    val nativeContactsManager = remember { GlobalContext.get().get<NativeContactsManager>() }
    val signalSessionManager = remember { GlobalContext.get().get<SignalSessionManager>() }
    val localContext = LocalContext.current
    val scope = rememberCoroutineScope()

    var showScanner by remember { mutableStateOf(false) }
    var trustedPeers by remember { mutableStateOf(emptyList<String>()) }
    var pendingKeyDistribution by remember { mutableStateOf<PendingKeyDistribution?>(null) }

    LaunchedEffect(Unit) {
        trustedPeers = nativeContactsManager.getTrickContacts().mapNotNull { it.deviceId }
    }

    var qrResult by remember { mutableStateOf<QRHashResult?>(null) }

    // Generate single-QR hash commitment payload
    LaunchedEffect(deviceId) {
        withContext(Dispatchers.IO) {
            signalSessionManager.initialize()
            signalSessionManager.replenishPreKeysIfNeeded()

            val result = QRKeyDistribution.generateQRHashPayload(
                signalSessionManager = signalSessionManager,
                deviceId = deviceId
            )

            withContext(Dispatchers.Main) {
                qrResult = result
            }
        }
    }

    // Contact picker launcher
    val launchContactPicker = rememberContactPickerLauncher { result: ContactPickerResult? ->
        val pending = pendingKeyDistribution
        if (result != null && pending != null) {
            val success = nativeContactsManager.linkTrickDataToContact(
                nativeContactId = result.rawContactId,
                shortId = pending.shortId,
                publicKeyHex = pending.identityKeyHex,
                deviceId = pending.deviceId
            )
            if (success) {
                Toast.makeText(localContext, "Key linked to ${result.displayName}", Toast.LENGTH_LONG).show()
                trustedPeers = nativeContactsManager.getTrickContacts().mapNotNull { it.deviceId }
            } else {
                Toast.makeText(localContext, "Failed to link key to contact", Toast.LENGTH_LONG).show()
            }
        } else if (result == null && pending != null) {
            Toast.makeText(localContext, "Contact selection cancelled", Toast.LENGTH_SHORT).show()
        }
        pendingKeyDistribution = null
    }

    if (showScanner) {
        QRScannerScreen(
            onQRCodeScanned = { qrCode ->
                scope.launch {
                    val (success, result) = withContext(Dispatchers.Default) {
                        QRKeyDistribution.verifyAndStoreQRHashPayload(qrCode)
                    }

                    if (!success) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(localContext, result, Toast.LENGTH_LONG).show()
                            showScanner = false
                        }
                        return@launch
                    }

                    // result is deviceId; re-parse payload to get shortId and identityKeyHex
                    val qrData = try {
                        Json.decodeFromString<QRHashPayload>(qrCode)
                    } catch (e: Exception) {
                        Log.e("KeyDistribution", "Failed to re-parse QR payload: ${e.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(localContext, "Failed to parse QR code", Toast.LENGTH_LONG).show()
                            showScanner = false
                        }
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        pendingKeyDistribution = PendingKeyDistribution(
                            deviceId = qrData.deviceId,
                            shortId = qrData.shortId,
                            identityKeyHex = qrData.identityKeyHex
                        )
                        Toast.makeText(
                            localContext,
                            "QR code verified! Select a contact to link.",
                            Toast.LENGTH_SHORT
                        ).show()
                        showScanner = false
                        launchContactPicker()
                    }
                }
            },
            onNavigateBack = { showScanner = false }
        )
    } else {
        val readyQr = qrResult
        if (readyQr == null) {
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
                qrCodePayloads = listOf(readyQr.payloadJson),
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
                onScanQR = { showScanner = true },
                onUntrust = { peerId ->
                    val contact = nativeContactsManager.getTrickContacts().find { it.deviceId == peerId }
                    if (contact != null) {
                        nativeContactsManager.unlinkTrickData(contact.shortId)
                    }
                    QRKeyDistribution.clearCommitment(peerId)
                    trustedPeers = nativeContactsManager.getTrickContacts().mapNotNull { it.deviceId }
                    Toast.makeText(localContext, "Removed trust for $peerId", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
