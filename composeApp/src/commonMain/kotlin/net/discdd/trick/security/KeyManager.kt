package net.discdd.trick.security

import net.discdd.trick.libsignal.IdentityKeyPair
import net.discdd.trick.libsignal.PublicKey

/**
 * KeyManager provides secure storage and retrieval of encryption keys
 * across different platforms (Android KeyStore, iOS Keychain).
 */
expect class KeyManager {
    /**
     * Generate and store a new identity key pair.
     * The private key is encrypted with a platform-specific master key
     * before storage.
     *
     * @return The generated identity key pair
     */
    fun generateIdentityKeyPair(): IdentityKeyPair

    /**
     * Retrieve the stored identity key pair.
     *
     * @return The identity key pair, or null if not yet generated
     */
    fun getIdentityKeyPair(): IdentityKeyPair?

    /**
     * Store a peer's public key for encryption.
     *
     * @param peerId The unique identifier for the peer
     * @param publicKey The peer's public key
     */
    fun storePeerPublicKey(peerId: String, publicKey: PublicKey)

    /**
     * Retrieve a peer's public key.
     *
     * @param peerId The unique identifier for the peer
     * @return The peer's public key, or null if not found
     */
    fun getPeerPublicKey(peerId: String): PublicKey?

    /**
     * Remove a peer's public key (untrust the peer).
     *
     * @param peerId The unique identifier for the peer
     */
    fun removePeerPublicKey(peerId: String)

    /**
     * Get a list of all trusted peer IDs.
     *
     * @return List of peer IDs that have exchanged keys
     */
    fun getTrustedPeerIds(): List<String>

    /**
     * Check if a peer is trusted (key exchange completed).
     *
     * @param peerId The unique identifier for the peer
     * @return True if the peer has a stored public key
     */
    fun isPeerTrusted(peerId: String): Boolean
}
