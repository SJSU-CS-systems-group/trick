package net.discdd.trick.libsignal

/**
 * KMP wrapper for libsignal functionality
 * Provides a clean, modern API over the native Rust libsignal library
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

// Main API interface
expect class LibSignalManager {
    /**
     * Generate a new identity key pair
     */
    fun generateIdentityKeyPair(): IdentityKeyPair
    
    /**
     * Generate a new private key
     */
    fun generatePrivateKey(): PrivateKey
    
    /**
     * Get public key from private key
     */
    fun getPublicKey(privateKey: PrivateKey): PublicKey
    
    /**
     * Sign data with private key
     */
    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray
    
    /**
     * Verify signature with public key
     */
    fun verify(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean
    
    /**
     * Get version info
     */
    fun getVersion(): String
    
    /**
     * Encrypt data using a public key
     */
    fun encrypt(publicKey: PublicKey, data: ByteArray): ByteArray
    
    /**
     * Decrypt data using a private key
     */
    fun decrypt(privateKey: PrivateKey, encryptedData: ByteArray): ByteArray
    
    /**
     * Test if libsignal is working
     */
    fun test(): String
}

// Factory function
expect fun createLibSignalManager(): LibSignalManager
