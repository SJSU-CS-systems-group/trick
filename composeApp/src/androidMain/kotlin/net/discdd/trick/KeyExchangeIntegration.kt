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
import net.discdd.trick.libsignal.createLibSignalManager
import net.discdd.trick.screens.KeyExchangeScreen
import net.discdd.trick.screens.QRScannerScreen
import net.discdd.trick.security.KeyManager
import net.discdd.trick.security.QRKeyExchange
import net.discdd.trick.security.TRCKY_ORG_BASE_URL
import net.discdd.trick.security.TrckyOrgPayloadResolver
import net.discdd.trick.security.parseTrckyShortId

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
    val libSignalManager = remember { createLibSignalManager() }
    val payloadResolver = remember { TrckyOrgPayloadResolver() }
    val localContext = LocalContext.current
    val scope = rememberCoroutineScope()

    var showScanner by remember { mutableStateOf(false) }
    var trustedPeers by rememberSaveable { mutableStateOf(emptyList<String>()) }

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
                                Toast.makeText(localContext, message, Toast.LENGTH_LONG).show()
                                if (success) {
                                    trustedPeers = keyManager.getTrustedPeerIds()
                                }
                                showScanner = false
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
                keyManager.removePeerPublicKey(peerId)
                trustedPeers = keyManager.getTrustedPeerIds()
                Toast.makeText(localContext, "Removed trust for $peerId", Toast.LENGTH_SHORT).show()
            }
        )
    }
}
