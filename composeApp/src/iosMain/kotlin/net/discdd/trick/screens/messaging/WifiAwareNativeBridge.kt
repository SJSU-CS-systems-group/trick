@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package net.discdd.trick.screens.messaging

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * Protocol for the native Swift Wi-Fi Aware bridge.
 *
 * Defined in Kotlin so it's exported as an ObjC protocol in the ComposeApp framework.
 * The Swift `WifiAwareBridge` class conforms to this protocol.
 *
 * All byte data crosses the boundary as NSData to avoid KotlinByteArray friction in Swift.
 */
interface WifiAwareNativeBridge {
    fun configure(localDeviceId: String)
    fun startNativeDiscovery()
    fun stopNativeDiscovery()
    fun sendNativeData(data: NSData, toPeerId: String)
    fun sendNativeDataToAll(data: NSData)
    fun setNativeDesiredPeerId(peerId: String?)
    fun isNativePeerConnected(peerId: String): Boolean
    fun getNativeConnectedPeerIds(): List<String>
    fun getNativeConnectionStatus(): String
    fun isNativeSupported(): Boolean
    var nativeCallback: WifiAwareNativeCallback?
}

/**
 * Callback protocol for events from the native Wi-Fi Aware bridge back to Kotlin.
 * Implemented by [WifiAwareServiceImpl].
 */
interface WifiAwareNativeCallback {
    fun onNativeDataReceived(data: NSData, fromPeerId: String)
    fun onNativePeerConnected(peerId: String)
    fun onNativePeerDisconnected(peerId: String)
    fun onNativeStatusUpdated(status: String)
    fun onNativeError(error: String)
}

// MARK: - NSData <-> ByteArray conversion utilities

fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(
        bytes = allocArrayOf(this@toNSData),
        length = this@toNSData.size.toULong()
    )
}

fun NSData.toByteArray(): ByteArray {
    val size = this.length.toInt()
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return bytes
}
