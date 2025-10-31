package com.jetbrains.kmpapp.screens.messaging

/**
 * iOS stub implementation of WifiAwareService
 * WiFi Aware is Android-specific, so this is a no-op implementation
 */
class WifiAwareServiceImpl : WifiAwareService {
    override fun startDiscovery(onMessageReceived: (String) -> Unit) {
        // WiFi Aware is not available on iOS
        onMessageReceived("[System] WiFi Aware is only available on Android")
    }

    override fun stopDiscovery() {
        // No-op
    }

    override fun sendMessage(message: String) {
        // No-op
    }

    override fun isPeerConnected(): Boolean {
        return false
    }

    override fun getConnectionStatus(): String {
        return "iOS platform (WiFi Aware not supported)"
    }
}

