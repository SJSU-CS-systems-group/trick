package org.trcky.trick.signal

/**
 * Platform-specific secure storage for private keys.
 * - Android: Android KeyStore with AES-GCM encryption
 * - iOS: Keychain Services
 */
expect class SecureKeyStorage() {
    /** Encrypt private key data. Returns (encryptedData, iv). */
    fun encryptPrivateKey(data: ByteArray): Pair<ByteArray, ByteArray>

    /** Decrypt private key data using the stored IV. */
    fun decryptPrivateKey(encrypted: ByteArray, iv: ByteArray): ByteArray
}
