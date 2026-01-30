package net.discdd.trick.util

import net.discdd.trick.libsignal.PublicKey
import net.discdd.trick.security.toHexString
import java.security.MessageDigest

/**
 * Utility for generating short IDs from public keys.
 * 
 * The short ID is a 12-character hexadecimal string derived from
 * the SHA-256 hash of the public key's hex representation.
 * This ensures deterministic generation: the same public key
 * will always produce the same short ID.
 */
object ShortIdGenerator {
    /**
     * Generate a 12-character hexadecimal short ID from a public key.
     * 
     * Process:
     * 1. Convert public key ByteArray to hex string
     * 2. Compute SHA-256 hash of the hex string
     * 3. Take first 12 characters of the hash (lowercase)
     * 
     * @param publicKey The public key to generate a short ID for
     * @return 12-character lowercase hexadecimal string
     */
    fun generateShortId(publicKey: PublicKey): String {
        // Convert public key to hex string
        val publicKeyHex = publicKey.data.toHexString()
        
        // Compute SHA-256 hash
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(publicKeyHex.encodeToByteArray())
        
        // Convert hash to hex and take first 12 characters
        val hashHex = hashBytes.toHexString()
        return hashHex.take(12).lowercase()
    }
}

