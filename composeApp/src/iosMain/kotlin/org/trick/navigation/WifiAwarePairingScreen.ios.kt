package org.trick.navigation

import androidx.compose.runtime.Composable
import org.trick.screens.messaging.WifiAwareService

/**
 * iOS implementation - presents native WiFi Aware pairing UI.
 * The actual pairing is handled by WifiAwarePairingCoordinator in Swift.
 * This is a placeholder that can be used in navigation if needed.
 */
@Composable
actual fun WifiAwarePairingScreenPlaceholder(
    onPairDevice: () -> Unit,
    onNavigateBack: () -> Unit,
    wifiAwareService: WifiAwareService
) {
    // On iOS, pairing is handled natively via WifiAwarePairingCoordinator
    // This placeholder can navigate back or trigger the native pairing UI
    onNavigateBack()
}
