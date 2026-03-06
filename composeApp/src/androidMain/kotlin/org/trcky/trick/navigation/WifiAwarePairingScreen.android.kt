package org.trcky.trick.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.trcky.trick.screens.messaging.WifiAwareService

/**
 * Android implementation - device pairing is not needed on Android.
 * Android uses PSK passphrase for WiFi Aware security.
 * This immediately navigates back since pairing is iOS-only.
 */
@Composable
actual fun WifiAwarePairingScreenPlaceholder(
    onPairDevice: () -> Unit,
    onNavigateBack: () -> Unit,
    wifiAwareService: WifiAwareService
) {
    // Immediately navigate back - pairing is not needed on Android
    LaunchedEffect(Unit) {
        onNavigateBack()
    }
}

