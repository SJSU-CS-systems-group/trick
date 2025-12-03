package net.discdd.trick.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import net.discdd.trick.libsignal.IdentityKeyPair
import net.discdd.trick.libsignal.PrivateKey
import net.discdd.trick.libsignal.PublicKey
import net.discdd.trick.libsignal.createLibSignalManager
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android implementation of KeyManager using AndroidKeyStore for secure storage.
 *
 * Security features:
 * - Private keys encrypted with hardware-backed AES-256-GCM master key
 * - Master key never leaves Android KeyStore secure hardware
 * - Public keys stored in SharedPreferences for easy access
 * - Peer keys stored per-device for key exchange tracking
 */
actual class KeyManager(private val context: Context) {
    private val libSignalManager = createLibSignalManager()
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "KeyManager"
        private const val PREFS_NAME = "signal_keys"
        private const val MASTER_KEY_ALIAS = "signal_master_key"
        private const val PREF_PRIVATE_KEY_ENCRYPTED = "private_key_encrypted"
        private const val PREF_PRIVATE_KEY_IV = "private_key_iv"
        private const val PREF_PUBLIC_KEY = "public_key"
        private const val PEER_KEY_PREFIX = "peer_"
    }

    /**
     * Get or create the master key from Android KeyStore.
     * This key is hardware-backed and never leaves the secure enclave.
     */
    private fun getMasterKey(): SecretKey {
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            Log.d(TAG, "Generating new master key in KeyStore")
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
            )
            keyGenerator.generateKey()
        }
        return keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
    }

    /**
     * Generate a new identity key pair and store it securely.
     * The private key is encrypted with the master key before storage.
     */
    actual fun generateIdentityKeyPair(): IdentityKeyPair {
        Log.d(TAG, "Generating new identity key pair")
        val keyPair = libSignalManager.generateIdentityKeyPair()

        // Encrypt private key with master key
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())
        val encryptedPrivateKey = cipher.doFinal(keyPair.privateKey.data)
        val iv = cipher.iv

        // Store encrypted private key and IV
        prefs.edit()
            .putString(PREF_PRIVATE_KEY_ENCRYPTED, Base64.encodeToString(encryptedPrivateKey, Base64.NO_WRAP))
            .putString(PREF_PRIVATE_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(PREF_PUBLIC_KEY, Base64.encodeToString(keyPair.publicKey.data, Base64.NO_WRAP))
            .apply()

        Log.d(TAG, "Identity key pair stored securely")
        return keyPair
    }

    /**
     * Retrieve the stored identity key pair.
     * The private key is decrypted using the master key.
     */
    actual fun getIdentityKeyPair(): IdentityKeyPair? {
        val encryptedPrivateKeyStr = prefs.getString(PREF_PRIVATE_KEY_ENCRYPTED, null) ?: run {
            Log.d(TAG, "No identity key pair found")
            return null
        }
        val ivStr = prefs.getString(PREF_PRIVATE_KEY_IV, null) ?: return null
        val publicKeyStr = prefs.getString(PREF_PUBLIC_KEY, null) ?: return null

        return try {
            // Decrypt private key with master key
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getMasterKey(),
                GCMParameterSpec(128, Base64.decode(ivStr, Base64.NO_WRAP))
            )
            val privateKeyData = cipher.doFinal(Base64.decode(encryptedPrivateKeyStr, Base64.NO_WRAP))
            val publicKeyData = Base64.decode(publicKeyStr, Base64.NO_WRAP)

            val keyPair = IdentityKeyPair(
                PrivateKey(privateKeyData),
                PublicKey(publicKeyData)
            )
            Log.d(TAG, "Identity key pair retrieved successfully")
            keyPair
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve identity key pair: ${e.message}", e)
            null
        }
    }

    /**
     * Store a peer's public key.
     */
    actual fun storePeerPublicKey(peerId: String, publicKey: PublicKey) {
        prefs.edit()
            .putString("$PEER_KEY_PREFIX$peerId", Base64.encodeToString(publicKey.data, Base64.NO_WRAP))
            .apply()
        Log.d(TAG, "Stored public key for peer: $peerId")
    }

    /**
     * Retrieve a peer's public key.
     */
    actual fun getPeerPublicKey(peerId: String): PublicKey? {
        val keyStr = prefs.getString("$PEER_KEY_PREFIX$peerId", null) ?: return null
        return PublicKey(Base64.decode(keyStr, Base64.NO_WRAP))
    }

    /**
     * Remove a peer's public key.
     */
    actual fun removePeerPublicKey(peerId: String) {
        prefs.edit()
            .remove("$PEER_KEY_PREFIX$peerId")
            .apply()
        Log.d(TAG, "Removed public key for peer: $peerId")
    }

    /**
     * Get all trusted peer IDs.
     */
    actual fun getTrustedPeerIds(): List<String> {
        return prefs.all.keys
            .filter { it.startsWith(PEER_KEY_PREFIX) }
            .map { it.removePrefix(PEER_KEY_PREFIX) }
    }

    /**
     * Check if a peer is trusted.
     */
    actual fun isPeerTrusted(peerId: String): Boolean {
        return prefs.contains("$PEER_KEY_PREFIX$peerId")
    }
}

/**
 * Android implementation of currentTimeMillis.
 */
internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()
