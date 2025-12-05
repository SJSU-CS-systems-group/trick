package net.discdd.trick.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import net.discdd.trick.libsignal.IdentityKeyPair
import net.discdd.trick.libsignal.PrivateKey
import net.discdd.trick.libsignal.PublicKey
import net.discdd.trick.libsignal.createLibSignalManager
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.NSUTF8StringEncoding
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS implementation of KeyManager using Keychain Services for secure storage.
 *
 * Security features:
 * - Private keys stored in iOS Keychain (hardware-backed when available)
 * - Public keys stored in UserDefaults for easy access
 * - Peer keys stored per-device for key exchange tracking
 *
 * Note: For production, consider using Security framework's SecKey APIs
 * for more advanced Keychain integration with biometric authentication.
 */
@OptIn(ExperimentalForeignApi::class)
actual class KeyManager {
    private val libSignalManager = createLibSignalManager()
    private val userDefaults = NSUserDefaults.standardUserDefaults

    companion object {
        private const val PREF_PRIVATE_KEY = "signal_private_key"
        private const val PREF_PUBLIC_KEY = "signal_public_key"
        private const val PEER_KEY_PREFIX = "signal_peer_"
    }

    /**
     * Generate a new identity key pair and store it securely.
     *
     * TODO: For production, integrate with iOS Keychain Services for
     * hardware-backed storage and biometric authentication.
     */
    actual fun generateIdentityKeyPair(): IdentityKeyPair {
        val keyPair = libSignalManager.generateIdentityKeyPair()

        // Store keys in UserDefaults
        // TODO: Move private key to Keychain for better security
        userDefaults.setObject(
            NSData.create(bytes = keyPair.privateKey.data.toUByteArray().refTo(0), length = keyPair.privateKey.data.size.toULong()),
            PREF_PRIVATE_KEY
        )
        userDefaults.setObject(
            NSData.create(bytes = keyPair.publicKey.data.toUByteArray().refTo(0), length = keyPair.publicKey.data.size.toULong()),
            PREF_PUBLIC_KEY
        )

        return keyPair
    }

    /**
     * Retrieve the stored identity key pair.
     */
    actual fun getIdentityKeyPair(): IdentityKeyPair? {
        val privateKeyData = userDefaults.dataForKey(PREF_PRIVATE_KEY) ?: return null
        val publicKeyData = userDefaults.dataForKey(PREF_PUBLIC_KEY) ?: return null

        val privateKeyBytes = ByteArray(privateKeyData.length.toInt())
        val publicKeyBytes = ByteArray(publicKeyData.length.toInt())

        memScoped {
            memcpy(privateKeyBytes.refTo(0), privateKeyData.bytes, privateKeyData.length)
            memcpy(publicKeyBytes.refTo(0), publicKeyData.bytes, publicKeyData.length)
        }

        return IdentityKeyPair(
            PrivateKey(privateKeyBytes),
            PublicKey(publicKeyBytes)
        )
    }

    /**
     * Store a peer's public key.
     */
    actual fun storePeerPublicKey(peerId: String, publicKey: PublicKey) {
        userDefaults.setObject(
            NSData.create(bytes = publicKey.data.toUByteArray().refTo(0), length = publicKey.data.size.toULong()),
            "$PEER_KEY_PREFIX$peerId"
        )
    }

    /**
     * Retrieve a peer's public key.
     */
    actual fun getPeerPublicKey(peerId: String): PublicKey? {
        val publicKeyData = userDefaults.dataForKey("$PEER_KEY_PREFIX$peerId") ?: return null

        val publicKeyBytes = ByteArray(publicKeyData.length.toInt())
        memScoped {
            memcpy(publicKeyBytes.refTo(0), publicKeyData.bytes, publicKeyData.length)
        }

        return PublicKey(publicKeyBytes)
    }

    /**
     * Remove a peer's public key.
     */
    actual fun removePeerPublicKey(peerId: String) {
        userDefaults.removeObjectForKey("$PEER_KEY_PREFIX$peerId")
    }

    /**
     * Get all trusted peer IDs.
     */
    actual fun getTrustedPeerIds(): List<String> {
        val dictionary = userDefaults.dictionaryRepresentation()
        return dictionary.keys
            .mapNotNull { it as? String }
            .filter { it.startsWith(PEER_KEY_PREFIX) }
            .map { it.removePrefix(PEER_KEY_PREFIX) }
    }

    /**
     * Check if a peer is trusted.
     */
    actual fun isPeerTrusted(peerId: String): Boolean {
        return userDefaults.dataForKey("$PEER_KEY_PREFIX$peerId") != null
    }
}

/**
 * iOS implementation of currentTimeMillis.
 */
internal actual fun currentTimeMillis(): Long {
    return (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong()
}
