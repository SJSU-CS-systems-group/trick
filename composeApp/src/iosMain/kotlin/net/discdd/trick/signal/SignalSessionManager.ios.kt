package net.discdd.trick.signal

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSLog

/**
 * iOS stub implementation of SignalSessionManager.
 * iOS WiFi Aware support is not available, so all operations are no-ops.
 */
actual class SignalSessionManager {
    private var isInitialized = false

    /**
     * Initialize Signal identity (stub).
     */
    actual suspend fun initialize(): Unit = withContext(Dispatchers.Default) {
        isInitialized = true
    }

    /**
     * Get our device's shortId (stub).
     */
    actual fun getShortId(): String {
        checkInitialized()
        return "ios-stub"
    }

    /**
     * Check if Signal session exists with peer (stub).
     */
    actual fun hasSession(peerId: String, deviceId: Int): Boolean {
        checkInitialized()
        return false
    }

    /**
     * Build session from PreKeyBundle (stub).
     */
    actual suspend fun buildSessionFromPreKeyBundle(
        peerId: String,
        deviceId: Int,
        bundle: PreKeyBundleData
    ): Unit = withContext(Dispatchers.Default) {
        checkInitialized()
        throw UnsupportedOperationException("Signal on iOS not implemented")
    }

    /**
     * Encrypt message (stub).
     */
    actual suspend fun encryptMessage(
        peerId: String,
        deviceId: Int,
        plaintext: ByteArray
    ): SignalEncryptResult = withContext(Dispatchers.Default) {
        checkInitialized()
        throw UnsupportedOperationException("Signal on iOS not implemented")
    }

    /**
     * Decrypt message (stub).
     */
    actual suspend fun decryptMessage(
        senderId: String,
        deviceId: Int,
        ciphertext: ByteArray
    ): SignalDecryptResult = withContext(Dispatchers.Default) {
        checkInitialized()
        throw UnsupportedOperationException("Signal on iOS not implemented")
    }

    /**
     * Generate PreKeyBundle (stub).
     */
    actual suspend fun generatePreKeyBundle(): PreKeyBundleData = withContext(Dispatchers.Default) {
        checkInitialized()
        throw UnsupportedOperationException("Signal on iOS not implemented")
    }

    /**
     * Get local registration ID (stub).
     */
    actual fun getLocalRegistrationId(): Int {
        checkInitialized()
        return 0
    }

    /**
     * Get our public identity key (stub).
     */
    actual fun getIdentityPublicKey(): ByteArray {
        checkInitialized()
        return ByteArray(0)
    }

    /**
     * Delete session (stub).
     */
    actual suspend fun deleteSession(peerId: String, deviceId: Int): Unit = withContext(Dispatchers.Default) {
        checkInitialized()
    }

    /**
     * Get count of available one-time prekeys (stub).
     */
    actual fun getAvailablePreKeyCount(): Int {
        checkInitialized()
        return 0
    }

    /**
     * Replenish prekeys (stub).
     */
    actual suspend fun replenishPreKeysIfNeeded(threshold: Int, generateCount: Int): Unit = withContext(Dispatchers.Default) {
        checkInitialized()
    }

    /**
     * Confirm identity change (stub).
     */
    actual suspend fun confirmIdentityChange(
        peerId: String,
        deviceId: Int,
        newIdentityKey: ByteArray
    ): Unit = withContext(Dispatchers.Default) {
        checkInitialized()
    }

    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("SignalSessionManager not initialized. Call initialize() first.")
        }
    }
}
