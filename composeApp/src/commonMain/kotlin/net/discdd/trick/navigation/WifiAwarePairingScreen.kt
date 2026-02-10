package net.discdd.trick.navigation

import androidx.compose.runtime.Composable
import net.discdd.trick.screens.messaging.WifiAwareService

/**
 * Platform-specific WiFi Aware pairing screen.
 * 
 * On iOS, this presents the native device pairing UI.
 * On Android, pairing is not needed (uses PSK passphrase), so this immediately navigates back.
 */
@Composable
expect fun WifiAwarePairingScreenPlaceholder(
    onPairDevice: () -> Unit,
    onNavigateBack: () -> Unit,
    wifiAwareService: WifiAwareService
)

