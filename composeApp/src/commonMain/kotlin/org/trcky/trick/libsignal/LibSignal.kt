package org.trcky.trick.libsignal

import org.trcky.trick.signal.SignalNativeBridge

/**
 * KMP wrapper for libsignal functionality.
 * Delegates all cryptographic operations to [SignalNativeBridge] (Rust FFI).
 */

// Core types
data class PrivateKey(val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as PrivateKey
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
}

data class PublicKey(val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as PublicKey
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
}

data class IdentityKeyPair(
    val privateKey: PrivateKey,
    val publicKey: PublicKey
)

data class SignalProtocolAddress(
    val name: String,
    val deviceId: Int
)

// Exception data classes (no inheritance to avoid platform issues)
data class SignalProtocolError(val message: String, val cause: String? = null)
data class UntrustedIdentityError(val message: String)

/**
 * Concrete class delegating EC operations to [SignalNativeBridge] (Rust FFI).
 * No longer an expect/actual — works in commonMain for both platforms.
 */
class LibSignalManager {

    fun generateIdentityKeyPair(): IdentityKeyPair {
        val (publicBytes, privateBytes) = SignalNativeBridge.generateIdentityKeyPair()
        return IdentityKeyPair(PrivateKey(privateBytes), PublicKey(publicBytes))
    }

    fun generatePrivateKey(): PrivateKey {
        val (_, privateBytes) = SignalNativeBridge.generateIdentityKeyPair()
        return PrivateKey(privateBytes)
    }

    fun getPublicKey(privateKey: PrivateKey): PublicKey {
        // Generate a key pair and sign+verify to derive the public key
        // This is a limitation — for now, callers should use generateIdentityKeyPair()
        // which returns both keys. This method is unused in production flows.
        throw UnsupportedOperationException(
            "getPublicKey(privateKey) is not supported. Use generateIdentityKeyPair() instead."
        )
    }

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        return SignalNativeBridge.privateKeySign(privateKey.data, data)
    }

    fun verify(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        return SignalNativeBridge.publicKeyVerify(publicKey.data, data, signature)
    }

    fun getVersion(): String = "0.86.7-rust-ffi"

    fun encrypt(publicKey: PublicKey, data: ByteArray): ByteArray {
        throw UnsupportedOperationException(
            "HPKE encrypt not available via Rust FFI. Use SignalSessionManager for message encryption."
        )
    }

    fun decrypt(privateKey: PrivateKey, encryptedData: ByteArray): ByteArray {
        throw UnsupportedOperationException(
            "HPKE decrypt not available via Rust FFI. Use SignalSessionManager for message decryption."
        )
    }

    fun test(): String {
        return try {
            val keyPair = generateIdentityKeyPair()
            val testData = "Hello Rust Signal FFI!".encodeToByteArray()
            val signature = sign(keyPair.privateKey, testData)
            val isValid = verify(keyPair.publicKey, testData, signature)
            "Rust Signal FFI test: keygen OK, sign OK, verify=$isValid"
        } catch (e: Exception) {
            "Rust Signal FFI test failed: ${e.message}"
        }
    }
}

fun createLibSignalManager(): LibSignalManager = LibSignalManager()
