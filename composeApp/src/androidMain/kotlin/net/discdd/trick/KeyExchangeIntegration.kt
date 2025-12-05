package net.discdd.trick

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import net.discdd.trick.libsignal.createLibSignalManager
import net.discdd.trick.screens.KeyExchangeScreen
import net.discdd.trick.screens.QRScannerScreen
import net.discdd.trick.security.KeyManager
import net.discdd.trick.security.QRKeyExchange

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
    val localContext = LocalContext.current

    var showScanner by remember { mutableStateOf(false) }
    var trustedPeers by rememberSaveable { mutableStateOf(emptyList<String>()) }

    // Ensure identity key pair exists
    LaunchedEffect(Unit) {
        if (keyManager.getIdentityKeyPair() == null) {
            keyManager.generateIdentityKeyPair()
        }
        trustedPeers = keyManager.getTrustedPeerIds()
    }

    // Generate QR payload
    val qrCodePayload = remember(deviceId) {
        QRKeyExchange.generateQRPayload(
            keyManager = keyManager,
            libSignalManager = libSignalManager,
            deviceId = deviceId
        )
    }

    if (showScanner) {
        QRScannerScreen(
            onQRCodeScanned = { qrCode ->
                // Verify and store the scanned QR code
                val (success, message) = QRKeyExchange.verifyAndStoreQRPayload(
                    payload = qrCode,
                    keyManager = keyManager,
                    libSignalManager = libSignalManager
                )

                Toast.makeText(localContext, message, Toast.LENGTH_LONG).show()

                if (success) {
                    // Refresh trusted peers list
                    trustedPeers = keyManager.getTrustedPeerIds()
                }

                // Go back to key exchange screen
                showScanner = false
            },
            onNavigateBack = {
                showScanner = false
            }
        )
    } else {
        KeyExchangeScreen(
            deviceId = deviceId,
            qrCodePayload = qrCodePayload,
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
