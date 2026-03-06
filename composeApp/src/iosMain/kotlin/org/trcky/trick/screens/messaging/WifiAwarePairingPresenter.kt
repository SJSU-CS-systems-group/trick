package org.trcky.trick.screens.messaging

/**
 * Protocol for presenting the native WiFi Aware pairing UI on iOS.
 *
 * Implemented in Swift and exported to Kotlin via ObjC interop.
 */
interface WifiAwarePairingPresenter {
    fun presentPairingUI()
}
