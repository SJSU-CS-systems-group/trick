package net.discdd.trick.signal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.discdd.trick.TrickDatabase
import net.discdd.trick.signal.stores.SQLDelightIdentityKeyStore
import net.discdd.trick.signal.stores.SQLDelightPreKeyStore
import net.discdd.trick.signal.stores.SQLDelightSessionStore
import net.discdd.trick.signal.stores.SQLDelightSignedPreKeyStore
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android implementation of SignalSessionManager using libsignal-client.
 *
 * This is the SINGLE SOURCE OF TRUTH for our Signal identity.
 * All encryption/decryption for network messages goes through this class.
 */
actual class SignalSessionManager(
    private val context: Context,
    private val database: TrickDatabase,
    private val preKeyBundleResolver: PreKeyBundleResolver?
) {
    private val mutex = Mutex()
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    // libsignal stores backed by SQLDelight
    private lateinit var identityKeyStore: SQLDelightIdentityKeyStore
    private lateinit var sessionStore: SQLDelightSessionStore
    private lateinit var preKeyStore: SQLDelightPreKeyStore
    private lateinit var signedPreKeyStore: SQLDelightSignedPreKeyStore

    // Our identity (loaded from SignalIdentity table)
    private lateinit var identityKeyPair: IdentityKeyPair
    private var registrationId: Int = 0
    private lateinit var shortId: String

    private var isInitialized = false

    companion object {
        private const val TAG = "SignalSessionManager"
        private const val MASTER_KEY_ALIAS = "signal_identity_master_key"
        private const val INITIAL_PREKEY_COUNT = 100
        private const val INITIAL_SIGNED_PREKEY_ID = 1
    }

    /**
     * Initialize Signal identity.
     * - Loads existing identity from SignalIdentity table, OR
     * - Generates new identity, registration ID, and shortId if none exists
     * - Generates initial prekeys if needed
     */
    actual suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isInitialized) {
                Log.d(TAG, "Already initialized")
                return@withContext
            }

            val existing = database.signalIdentityQueries.selectIdentity().executeAsOneOrNull()

            if (existing != null) {
                // Load existing identity
                Log.d(TAG, "Loading existing Signal identity")
                registrationId = existing.registration_id.toInt()
                shortId = existing.short_id
                identityKeyPair = decryptIdentityKeyPair(
                    existing.identity_key_public,
                    existing.identity_key_private_encrypted,
                    existing.identity_key_private_iv
                )
            } else {
                // Generate new identity
                Log.d(TAG, "Generating new Signal identity")
                registrationId = KeyHelper.generateRegistrationId(false)
                identityKeyPair = IdentityKeyPair.generate()
                shortId = generateRandomShortId()

                val (encryptedPrivate, iv) = encryptPrivateKey(identityKeyPair.privateKey.serialize())
                database.signalIdentityQueries.insertIdentity(
                    registration_id = registrationId.toLong(),
                    identity_key_public = identityKeyPair.publicKey.serialize(),
                    identity_key_private_encrypted = encryptedPrivate,
                    identity_key_private_iv = iv,
                    short_id = shortId,
                    created_at = System.currentTimeMillis()
                )

                // Generate initial prekeys
                generateInitialPreKeys()
            }

            // Initialize stores
            identityKeyStore = SQLDelightIdentityKeyStore(database, identityKeyPair, registrationId)
            sessionStore = SQLDelightSessionStore(database)
            preKeyStore = SQLDelightPreKeyStore(database)
            signedPreKeyStore = SQLDelightSignedPreKeyStore(database)

            isInitialized = true
            Log.d(TAG, "Signal identity initialized - shortId: $shortId, registrationId: $registrationId")
        }
    }

    /**
     * Get our device's shortId (opaque identifier for sharing).
     */
    actual fun getShortId(): String {
        checkInitialized()
        return shortId
    }

    /**
     * Check if Signal session exists with peer.
     */
    actual fun hasSession(peerId: String, deviceId: Int): Boolean {
        checkInitialized()
        val address = SignalProtocolAddress(peerId, deviceId)
        return sessionStore.containsSession(address)
    }

    /**
     * Build session from PreKeyBundle using X3DH.
     * libsignal internally validates signed prekey signature.
     */
    actual suspend fun buildSessionFromPreKeyBundle(
        peerId: String,
        deviceId: Int,
        bundle: PreKeyBundleData
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkInitialized()
            val address = SignalProtocolAddress(peerId, deviceId)

            // Check for identity change (TOFU)
            val existingIdentity = identityKeyStore.getIdentity(address)
            val newIdentityKey = IdentityKey(bundle.identityKey)

            if (existingIdentity != null && existingIdentity != newIdentityKey) {
                Log.w(TAG, "Identity changed for $peerId")
                throw SignalError.UntrustedIdentity(peerId, bundle.identityKey)
            }

            try {
                // Construct libsignal PreKeyBundle - let libsignal validate signature
                val preKeyBundle = if (bundle.preKeyId != null && bundle.preKeyPublic != null) {
                    PreKeyBundle(
                        bundle.registrationId,
                        bundle.deviceId,
                        bundle.preKeyId,
                        Curve.decodePoint(bundle.preKeyPublic, 0),
                        bundle.signedPreKeyId,
                        Curve.decodePoint(bundle.signedPreKeyPublic, 0),
                        bundle.signedPreKeySignature,
                        newIdentityKey
                    )
                } else {
                    // No one-time prekey available
                    PreKeyBundle(
                        bundle.registrationId,
                        bundle.deviceId,
                        -1,
                        null,
                        bundle.signedPreKeyId,
                        Curve.decodePoint(bundle.signedPreKeyPublic, 0),
                        bundle.signedPreKeySignature,
                        newIdentityKey
                    )
                }

                // SessionBuilder.process() validates signed prekey signature internally
                val sessionBuilder = SessionBuilder(sessionStore, preKeyStore, signedPreKeyStore, identityKeyStore, address)
                sessionBuilder.process(preKeyBundle)

                // Store peer identity (TOFU - first time)
                if (existingIdentity == null) {
                    identityKeyStore.saveIdentity(address, newIdentityKey)
                }

                Log.d(TAG, "Session built successfully with $peerId")

            } catch (e: InvalidKeyException) {
                Log.e(TAG, "Invalid key in bundle for $peerId: ${e.message}")
                throw SignalError.SessionBuildFailed(peerId, "Invalid key: ${e.message}")
            } catch (e: UntrustedIdentityException) {
                Log.e(TAG, "Untrusted identity for $peerId")
                throw SignalError.UntrustedIdentity(peerId, bundle.identityKey)
            }
        }
    }

    /**
     * Encrypt message using Signal protocol.
     */
    actual suspend fun encryptMessage(
        peerId: String,
        deviceId: Int,
        plaintext: ByteArray
    ): SignalEncryptResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkInitialized()
            val address = SignalProtocolAddress(peerId, deviceId)

            if (!sessionStore.containsSession(address)) {
                throw SignalError.NoSession(peerId)
            }

            val sessionCipher = SessionCipher(sessionStore, preKeyStore, signedPreKeyStore, identityKeyStore, address)
            val ciphertext = sessionCipher.encrypt(plaintext)

            SignalEncryptResult(
                ciphertext = ciphertext.serialize(),
                messageType = ciphertext.type,
                registrationId = registrationId
            )
        }
    }

    /**
     * Decrypt Signal protocol message.
     * libsignal detects message type and calls removePreKey() automatically for PreKeySignalMessage.
     */
    actual suspend fun decryptMessage(
        senderId: String,
        deviceId: Int,
        ciphertext: ByteArray
    ): SignalDecryptResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkInitialized()
            val address = SignalProtocolAddress(senderId, deviceId)
            val sessionCipher = SessionCipher(sessionStore, preKeyStore, signedPreKeyStore, identityKeyStore, address)

            try {
                // Try to determine message type and decrypt
                val plaintext = try {
                    // First try as regular SignalMessage
                    val signalMessage = SignalMessage(ciphertext)
                    sessionCipher.decrypt(signalMessage)
                } catch (e: Exception) {
                    // Try as PreKeySignalMessage
                    try {
                        val preKeyMessage = PreKeySignalMessage(ciphertext)
                        sessionCipher.decrypt(preKeyMessage)
                    } catch (e2: InvalidMessageException) {
                        throw SignalError.InvalidMessage(e2.message ?: "Failed to parse message")
                    }
                }

                val senderIdentity = identityKeyStore.getIdentity(address)
                    ?: throw SignalError.InvalidMessage("No identity for sender")

                SignalDecryptResult(
                    plaintext = plaintext,
                    senderIdentityKey = senderIdentity.serialize()
                )
            } catch (e: SignalError) {
                throw e
            } catch (e: DuplicateMessageException) {
                throw SignalError.InvalidMessage("Duplicate message")
            } catch (e: InvalidMessageException) {
                throw SignalError.InvalidMessage(e.message ?: "Invalid message")
            } catch (e: NoSessionException) {
                throw SignalError.NoSession(senderId)
            } catch (e: UntrustedIdentityException) {
                val newKey = identityKeyStore.getIdentity(address)?.serialize() ?: ByteArray(0)
                throw SignalError.UntrustedIdentity(senderId, newKey)
            }
        }
    }

    /**
     * Generate PreKeyBundle for upload to trcky.org.
     */
    actual suspend fun generatePreKeyBundle(): PreKeyBundleData = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkInitialized()

            // Get latest signed prekey
            val signedPreKeyId = signedPreKeyStore.getLatestSignedPreKeyId()
                ?: throw IllegalStateException("No signed prekey available")
            val signedPreKey = signedPreKeyStore.loadSignedPreKey(signedPreKeyId)

            // Get an available one-time prekey (if any)
            val preKeyId: Int?
            val preKeyPublic: ByteArray?

            if (preKeyStore.getCount() > 0) {
                // Get the first available prekey
                val nextId = database.signalPreKeyQueries.selectMaxPreKeyId().executeAsOneOrNull()?.MAX
                if (nextId != null) {
                    // Find an actual available prekey by iterating
                    var foundId: Int? = null
                    for (id in 1..nextId.toInt()) {
                        if (preKeyStore.containsPreKey(id)) {
                            foundId = id
                            break
                        }
                    }
                    if (foundId != null) {
                        preKeyId = foundId
                        preKeyPublic = preKeyStore.loadPreKey(foundId).keyPair.publicKey.serialize()
                    } else {
                        preKeyId = null
                        preKeyPublic = null
                    }
                } else {
                    preKeyId = null
                    preKeyPublic = null
                }
            } else {
                preKeyId = null
                preKeyPublic = null
            }

            PreKeyBundleData(
                registrationId = registrationId,
                deviceId = 1,
                preKeyId = preKeyId,
                preKeyPublic = preKeyPublic,
                signedPreKeyId = signedPreKeyId,
                signedPreKeyPublic = signedPreKey.keyPair.publicKey.serialize(),
                signedPreKeySignature = signedPreKey.signature,
                identityKey = identityKeyPair.publicKey.serialize()
            )
        }
    }

    /**
     * Get local registration ID.
     */
    actual fun getLocalRegistrationId(): Int {
        checkInitialized()
        return registrationId
    }

    /**
     * Get our public identity key (serialized).
     */
    actual fun getIdentityPublicKey(): ByteArray {
        checkInitialized()
        return identityKeyPair.publicKey.serialize()
    }

    /**
     * Delete session (for re-keying or untrust).
     */
    actual suspend fun deleteSession(peerId: String, deviceId: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkInitialized()
            val address = SignalProtocolAddress(peerId, deviceId)
            sessionStore.deleteSession(address)
            Log.d(TAG, "Deleted session with $peerId:$deviceId")
        }
    }

    /**
     * Get count of available one-time prekeys.
     */
    actual fun getAvailablePreKeyCount(): Int {
        checkInitialized()
        return preKeyStore.getCount()
    }

    /**
     * Replenish prekeys if count < threshold.
     */
    actual suspend fun replenishPreKeysIfNeeded(threshold: Int, generateCount: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkInitialized()
            val availableCount = preKeyStore.getCount()

            if (availableCount < threshold) {
                Log.d(TAG, "Prekey count ($availableCount) < $threshold, replenishing...")

                // Generate new prekeys
                val startId = preKeyStore.getNextId()
                val newPreKeys = KeyHelper.generatePreKeys(startId, generateCount)
                newPreKeys.forEach { preKey ->
                    preKeyStore.storePreKey(preKey.id, preKey)
                }

                // Upload new bundle to trcky.org if resolver available
                preKeyBundleResolver?.let { resolver ->
                    try {
                        val bundle = generatePreKeyBundleInternal()
                        resolver.uploadBundle(shortId, bundle)
                        Log.d(TAG, "Uploaded new bundle to trcky.org")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to upload bundle: ${e.message}")
                    }
                }

                Log.d(TAG, "Replenished ${newPreKeys.size} prekeys")
            }
        }
    }

    /**
     * Confirm identity change after UntrustedIdentity error.
     */
    actual suspend fun confirmIdentityChange(
        peerId: String,
        deviceId: Int,
        newIdentityKey: ByteArray
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkInitialized()
            val address = SignalProtocolAddress(peerId, deviceId)

            // Delete old session
            sessionStore.deleteSession(address)

            // Update identity with new key, mark as re-trusted
            val existingRecord = database.signalIdentityKeyQueries
                .selectIdentityKey(peerId, deviceId.toLong())
                .executeAsOneOrNull()

            database.signalIdentityKeyQueries.insertOrReplaceIdentityKey(
                address_name = peerId,
                device_id = deviceId.toLong(),
                identity_key = newIdentityKey,
                trust_level = SQLDelightIdentityKeyStore.TRUST_LEVEL_TOFU,
                first_seen_at = existingRecord?.first_seen_at ?: System.currentTimeMillis(),
                last_seen_at = System.currentTimeMillis()
            )

            Log.d(TAG, "Confirmed identity change for $peerId")
        }
    }

    // =====================
    // Private helper methods
    // =====================

    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("SignalSessionManager not initialized. Call initialize() first.")
        }
    }

    /**
     * Generate opaque random shortId (NOT derived from identity).
     */
    private fun generateRandomShortId(): String {
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Get or create the master key from Android KeyStore.
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
     * Encrypt private key with master key.
     */
    private fun encryptPrivateKey(privateKeyData: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())
        val encrypted = cipher.doFinal(privateKeyData)
        return Pair(encrypted, cipher.iv)
    }

    /**
     * Decrypt identity key pair from stored data.
     */
    private fun decryptIdentityKeyPair(
        publicKeyData: ByteArray,
        encryptedPrivateKey: ByteArray,
        iv: ByteArray
    ): IdentityKeyPair {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), GCMParameterSpec(128, iv))
        val privateKeyData = cipher.doFinal(encryptedPrivateKey)

        val publicKey = IdentityKey(publicKeyData)
        val privateKey = Curve.decodePrivatePoint(privateKeyData)
        return IdentityKeyPair(publicKey, privateKey)
    }

    /**
     * Generate initial prekeys on first setup.
     */
    private fun generateInitialPreKeys() {
        Log.d(TAG, "Generating initial prekeys")

        // Generate one-time prekeys
        val preKeys = KeyHelper.generatePreKeys(1, INITIAL_PREKEY_COUNT)
        preKeys.forEach { preKey ->
            database.signalPreKeyQueries.insertPreKey(
                prekey_id = preKey.id.toLong(),
                prekey_record = preKey.serialize()
            )
        }

        // Generate signed prekey
        val signedPreKey = KeyHelper.generateSignedPreKey(identityKeyPair, INITIAL_SIGNED_PREKEY_ID)
        database.signalSignedPreKeyQueries.insertSignedPreKey(
            signed_prekey_id = signedPreKey.id.toLong(),
            signed_prekey_record = signedPreKey.serialize(),
            created_at = System.currentTimeMillis()
        )

        Log.d(TAG, "Generated ${preKeys.size} prekeys and 1 signed prekey")
    }

    /**
     * Internal bundle generation without locking (for use within locked context).
     */
    private fun generatePreKeyBundleInternal(): PreKeyBundleData {
        val signedPreKeyId = signedPreKeyStore.getLatestSignedPreKeyId()
            ?: throw IllegalStateException("No signed prekey available")
        val signedPreKey = signedPreKeyStore.loadSignedPreKey(signedPreKeyId)

        val preKeyId: Int?
        val preKeyPublic: ByteArray?

        if (preKeyStore.getCount() > 0) {
            val maxId = database.signalPreKeyQueries.selectMaxPreKeyId().executeAsOneOrNull()?.MAX
            if (maxId != null) {
                var foundId: Int? = null
                for (id in 1..maxId.toInt()) {
                    if (preKeyStore.containsPreKey(id)) {
                        foundId = id
                        break
                    }
                }
                if (foundId != null) {
                    preKeyId = foundId
                    preKeyPublic = preKeyStore.loadPreKey(foundId).keyPair.publicKey.serialize()
                } else {
                    preKeyId = null
                    preKeyPublic = null
                }
            } else {
                preKeyId = null
                preKeyPublic = null
            }
        } else {
            preKeyId = null
            preKeyPublic = null
        }

        return PreKeyBundleData(
            registrationId = registrationId,
            deviceId = 1,
            preKeyId = preKeyId,
            preKeyPublic = preKeyPublic,
            signedPreKeyId = signedPreKeyId,
            signedPreKeyPublic = signedPreKey.keyPair.publicKey.serialize(),
            signedPreKeySignature = signedPreKey.signature,
            identityKey = identityKeyPair.publicKey.serialize()
        )
    }
}
