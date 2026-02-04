package net.discdd.trick.navigation

import java.net.URLEncoder

/**
 * Sealed class defining app screen routes for NavHost.
 */
sealed class Screen(val route: String) {
    data object ContactsList : Screen("contacts")
    data object Chat : Screen("chat/{shortId}/{peerId}") {
        /**
         * Create navigation route with shortId (for display) and peerId (for WiFi Aware).
         *
         * @param shortId The 12-char hex identifier for contact display
         * @param peerId The peer ID for WiFi Aware operations (deviceId when available, otherwise shortId)
         */
        fun createRoute(shortId: String, peerId: String): String {
            val encodedPeerId = URLEncoder.encode(peerId, "UTF-8")
            return "chat/$shortId/$encodedPeerId"
        }
    }
    data object KeyExchange : Screen("key_exchange")
}
