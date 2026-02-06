package net.discdd.trick.signal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.discdd.trick.TrickDatabase
import net.discdd.trick.data.currentTimeMillis

/**
 * Encryption result from Signal protocol
 */
data class SignalEncryptResult(
    val ciphertext: ByteArray,
    val messageType: Int,
    val registrationId: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalEncryptResult) return false
        return ciphertext.contentEquals(other.ciphertext) &&
               messageType == other.messageType &&
               registrationId == other.registrationId
    }
    override fun hashCode(): Int = ciphertext.contentHashCode()
}

/**
 * Decryption result from Signal protocol
 */
data class SignalDecryptResult(
    val plaintext: ByteArray,
    val senderIdentityKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalDecryptResult) return false
        return plaintext.contentEquals(other.plaintext)
    }
    override fun hashCode(): Int = plaintext.contentHashCode()
}

/**
 * PreKey bundle data for X3DH key agreement.
 *
 * Includes both classical EC prekeys and optional Kyber post-quantum prekeys,
 * matching the libsignal PreKeyBundle constructor parameters.
 */
data class PreKeyBundleData(
    val registrationId: Int,
    val deviceId: Int,
    val preKeyId: Int?,
    val preKeyPublic: ByteArray?,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: ByteArray,
    val signedPreKeySignature: ByteArray,
    val identityKey: ByteArray,
    val kyberPreKeyId: Int?,
    val kyberPreKeyPublic: ByteArray?,
    val kyberPreKeySignature: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreKeyBundleData) return false
        return registrationId == other.registrationId &&
               deviceId == other.deviceId &&
               preKeyId == other.preKeyId &&
               (preKeyPublic == null && other.preKeyPublic == null ||
                preKeyPublic != null && other.preKeyPublic != null && preKeyPublic.contentEquals(other.preKeyPublic)) &&
               signedPreKeyId == other.signedPreKeyId &&
               signedPreKeyPublic.contentEquals(other.signedPreKeyPublic) &&
               signedPreKeySignature.contentEquals(other.signedPreKeySignature) &&
               identityKey.contentEquals(other.identityKey) &&
               kyberPreKeyId == other.kyberPreKeyId &&
               (kyberPreKeyPublic == null && other.kyberPreKeyPublic == null ||
                kyberPreKeyPublic != null && other.kyberPreKeyPublic != null && kyberPreKeyPublic.contentEquals(other.kyberPreKeyPublic)) &&
               (kyberPreKeySignature == null && other.kyberPreKeySignature == null ||
                kyberPreKeySignature != null && other.kyberPreKeySignature != null && kyberPreKeySignature.contentEquals(other.kyberPreKeySignature))
    }

    override fun hashCode(): Int {
        var result = registrationId
        result = 31 * result + deviceId
        result = 31 * result + (preKeyId ?: 0)
        result = 31 * result + (preKeyPublic?.contentHashCode() ?: 0)
        result = 31 * result + signedPreKeyId
        result = 31 * result + signedPreKeyPublic.contentHashCode()
        result = 31 * result + signedPreKeySignature.contentHashCode()
        result = 31 * result + identityKey.contentHashCode()
        result = 31 * result + (kyberPreKeyId ?: 0)
        result = 31 * result + (kyberPreKeyPublic?.contentHashCode() ?: 0)
        result = 31 * result + (kyberPreKeySignature?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Exception for native Signal bridge errors.
 */
class SignalNativeException(message: String, val errorCode: Int = -99) : RuntimeException(message)

/**
 * Signal operation errors
 */
sealed class SignalError : Exception() {
    data class UntrustedIdentity(val peerId: String, val newKey: ByteArray) : SignalError() {
        override val message: String get() = "Identity key changed for $peerId - requires user confirmation"
    }
    data class InvalidMessage(val reason: String) : SignalError() {
        override val message: String get() = "Invalid message: $reason"
    }
    data class NoSession(val peerId: String) : SignalError() {
        override val message: String get() = "No Signal session exists for $peerId"
    }
    data class SessionBuildFailed(val peerId: String, val details: String) : SignalError() {
        override val message: String get() = "Failed to build session with $peerId: $details"
    }
    data class DowngradeAttempt(val peerId: String) : SignalError() {
        override val message: String get() = "Rejected encryption downgrade from $peerId"
    }
    data class PlaintextRejected(val peerId: String) : SignalError() {
        override val message: String get() = "Rejected plaintext message from $peerId - encryption required"
    }
}

/**
 * Main Signal protocol manager — concrete class in commonMain.
 *
 * Delegates all cryptographic operations to [SignalNativeBridge] (Rust FFI).
 * All Signal protocol records (sessions, prekeys, identity keys) are stored
 * as serialized protobuf blobs in SQLDelight.
 *
 * This is the SINGLE SOURCE OF TRUTH for our Signal identity.
 */
class SignalSessionManager(
    private val database: TrickDatabase,
    private val secureKeyStorage: SecureKeyStorage
) {
    private val mutex = Mutex()

    // Our identity (loaded from SignalIdentity table)
    private lateinit var identityPublicKey: ByteArray
    private lateinit var identityPrivateKey: ByteArray
    private var registrationId: Int = 0
    private lateinit var shortId: String

    private var isInitialized = false

    companion object {
        private const val INITIAL_PREKEY_COUNT = 100
        private const val INITIAL_SIGNED_PREKEY_ID = 1
        private const val INITIAL_KYBER_PREKEY_ID = 1
        private const val TRUST_LEVEL_UNTRUSTED = 0L
        private const val TRUST_LEVEL_TOFU = 1L
    }

    /**
     * Initialize Signal identity.
     * - Loads existing identity from SignalIdentity table, OR
     * - Generates new identity, registration ID, and shortId if none exists
     * - Generates initial prekeys if needed
     */
    suspend fun initialize(): Unit = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (isInitialized) return@withContext

            val existing = database.trickDatabaseQueries.selectIdentity().executeAsOneOrNull()

            if (existing != null) {
                registrationId = existing.registration_id.toInt()
                shortId = existing.short_id
                identityPublicKey = existing.identity_key_public
                identityPrivateKey = secureKeyStorage.decryptPrivateKey(
                    existing.identity_key_private_encrypted,
                    existing.identity_key_private_iv
                )
            } else {
                // Generate new identity via Rust
                val keyPair = SignalNativeBridge.generateIdentityKeyPair()
                identityPublicKey = keyPair.first
                identityPrivateKey = keyPair.second
                registrationId = SignalNativeBridge.generateRegistrationId()
                shortId = generateRandomShortId()

                val (encryptedPrivate, iv) = secureKeyStorage.encryptPrivateKey(identityPrivateKey)
                database.trickDatabaseQueries.insertIdentity(
                    registration_id = registrationId.toLong(),
                    identity_key_public = identityPublicKey,
                    identity_key_private_encrypted = encryptedPrivate,
                    identity_key_private_iv = iv,
                    short_id = shortId,
                    created_at = currentTimeMillis()
                )

                generateInitialPreKeys()
            }

            isInitialized = true
        }
    }

    fun getShortId(): String {
        checkInitialized()
        return shortId
    }

    fun hasSession(peerId: String, deviceId: Int = 1): Boolean {
        checkInitialized()
        return database.trickDatabaseQueries
            .containsSession(peerId, deviceId.toLong())
            .executeAsOne() > 0
    }

    /**
     * Build session from PreKeyBundle using X3DH.
     */
    suspend fun buildSessionFromPreKeyBundle(
        peerId: String,
        deviceId: Int = 1,
        bundle: PreKeyBundleData
    ): Unit = withContext(Dispatchers.Default) {
        mutex.withLock {
            checkInitialized()

            // Validate required Kyber fields (libsignal 0.86.7+ requires Kyber post-quantum prekeys)
            if (bundle.kyberPreKeyId == null || bundle.kyberPreKeyId!! < 0) {
                throw SignalError.SessionBuildFailed(
                    peerId,
                    "Bundle missing required Kyber prekey ID. Kyber post-quantum cryptography is required (libsignal 0.86.7+)."
                )
            }
            if (bundle.kyberPreKeyPublic == null || bundle.kyberPreKeyPublic!!.isEmpty()) {
                throw SignalError.SessionBuildFailed(
                    peerId,
                    "Bundle missing required Kyber prekey public key. Kyber post-quantum cryptography is required (libsignal 0.86.7+)."
                )
            }
            if (bundle.kyberPreKeySignature == null || bundle.kyberPreKeySignature!!.isEmpty()) {
                throw SignalError.SessionBuildFailed(
                    peerId,
                    "Bundle missing required Kyber prekey signature. Kyber post-quantum cryptography is required (libsignal 0.86.7+)."
                )
            }

            // Check for identity change (TOFU)
            val existingIdentity = database.trickDatabaseQueries
                .selectIdentityKey(peerId, deviceId.toLong())
                .executeAsOneOrNull()

            if (existingIdentity != null && !existingIdentity.identity_key.contentEquals(bundle.identityKey)) {
                throw SignalError.UntrustedIdentity(peerId, bundle.identityKey)
            }

            // Load existing session (if any)
            val existingSession = database.trickDatabaseQueries
                .selectSession(peerId, deviceId.toLong())
                .executeAsOneOrNull()

            try {
                val result = SignalNativeBridge.processPreKeyBundle(
                    identityPublic = identityPublicKey,
                    identityPrivate = identityPrivateKey,
                    registrationId = registrationId,
                    addressName = peerId,
                    deviceId = deviceId,
                    existingPeerIdentity = existingIdentity?.identity_key,
                    existingSession = existingSession,
                    bundle = bundle
                )

                // Save session record
                val now = currentTimeMillis()
                database.trickDatabaseQueries.insertOrReplaceSession(
                    address_name = peerId,
                    device_id = deviceId.toLong(),
                    session_record = result.sessionRecord,
                    created_at = now,
                    updated_at = now
                )

                // Store peer identity (TOFU)
                if (existingIdentity == null) {
                    database.trickDatabaseQueries.insertOrReplaceIdentityKey(
                        address_name = peerId,
                        device_id = deviceId.toLong(),
                        identity_key = bundle.identityKey,
                        trust_level = TRUST_LEVEL_TOFU,
                        first_seen_at = now,
                        last_seen_at = now
                    )
                }
            } catch (e: SignalNativeException) {
                when (e.errorCode) {
                    -5 -> throw SignalError.UntrustedIdentity(peerId, bundle.identityKey)
                    -1 -> throw SignalError.SessionBuildFailed(
                        peerId,
                        "Invalid bundle format: ${e.message ?: "Missing or invalid prekey data"}"
                    )
                    -3 -> throw SignalError.SessionBuildFailed(
                        peerId,
                        "Bundle serialization error: ${e.message ?: "Invalid bundle data format"}"
                    )
                    -4 -> throw SignalError.SessionBuildFailed(
                        peerId,
                        "Invalid key in bundle: ${e.message ?: "Key deserialization failed"}"
                    )
                    -99 -> throw SignalError.SessionBuildFailed(
                        peerId,
                        "Internal error processing bundle: ${e.message ?: "Rust FFI internal error. Check bundle format and Kyber prekey data."}"
                    )
                    else -> throw SignalError.SessionBuildFailed(
                        peerId,
                        "Rust FFI error (code ${e.errorCode}): ${e.message ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    /**
     * Encrypt message using Signal protocol.
     */
    suspend fun encryptMessage(
        peerId: String,
        deviceId: Int = 1,
        plaintext: ByteArray
    ): SignalEncryptResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            checkInitialized()

            if (!hasSession(peerId, deviceId)) {
                throw SignalError.NoSession(peerId)
            }

            // Load session and peer identity
            val sessionRecord = database.trickDatabaseQueries
                .selectSession(peerId, deviceId.toLong())
                .executeAsOne()

            val peerIdentity = database.trickDatabaseQueries
                .selectIdentityKey(peerId, deviceId.toLong())
                .executeAsOneOrNull()
                ?: throw SignalError.NoSession(peerId)

            val result = SignalNativeBridge.encryptMessage(
                identityPublic = identityPublicKey,
                identityPrivate = identityPrivateKey,
                registrationId = registrationId,
                addressName = peerId,
                deviceId = deviceId,
                sessionRecord = sessionRecord,
                peerIdentity = peerIdentity.identity_key,
                plaintext = plaintext
            )

            // Save updated session
            val now = currentTimeMillis()
            database.trickDatabaseQueries.insertOrReplaceSession(
                address_name = peerId,
                device_id = deviceId.toLong(),
                session_record = result.updatedSessionRecord,
                created_at = now,
                updated_at = now
            )

            SignalEncryptResult(
                ciphertext = result.ciphertext,
                messageType = result.messageType,
                registrationId = registrationId
            )
        }
    }

    /**
     * Decrypt Signal protocol message.
     */
    suspend fun decryptMessage(
        senderId: String,
        deviceId: Int = 1,
        ciphertext: ByteArray
    ): SignalDecryptResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            checkInitialized()

            // Determine message type
            val messageType = try {
                SignalNativeBridge.getCiphertextMessageType(ciphertext)
            } catch (_: Exception) {
                throw SignalError.InvalidMessage("Cannot determine message type")
            }

            // Load session record
            val sessionRecord = database.trickDatabaseQueries
                .selectSession(senderId, deviceId.toLong())
                .executeAsOneOrNull() ?: ByteArray(0)

            // Load peer identity (may be null for first contact via PreKey)
            val peerIdentity = database.trickDatabaseQueries
                .selectIdentityKey(senderId, deviceId.toLong())
                .executeAsOneOrNull()

            // For PreKeySignalMessage, load the needed prekeys
            var preKeyRecord: ByteArray? = null
            var signedPreKeyRecord: ByteArray? = null
            var kyberPreKeyRecord: ByteArray? = null

            if (messageType == 3) {
                // Extract prekey IDs from the message
                val (preKeyId, signedPreKeyId) = SignalNativeBridge.preKeyMessageGetIds(ciphertext)

                if (preKeyId >= 0) {
                    preKeyRecord = database.trickDatabaseQueries
                        .selectPreKey(preKeyId.toLong())
                        .executeAsOneOrNull()
                }
                signedPreKeyRecord = database.trickDatabaseQueries
                    .selectSignedPreKey(signedPreKeyId.toLong())
                    .executeAsOneOrNull()

                // Load the latest Kyber prekey (the message includes the kyber prekey ID internally)
                val kyberMaxId = database.trickDatabaseQueries
                    .selectMaxKyberPreKeyId()
                    .executeAsOneOrNull()?.max_kyber_prekey_id
                if (kyberMaxId != null) {
                    kyberPreKeyRecord = database.trickDatabaseQueries
                        .selectKyberPreKey(kyberMaxId)
                        .executeAsOneOrNull()
                }
            }

            try {
                val result = SignalNativeBridge.decryptMessage(
                    identityPublic = identityPublicKey,
                    identityPrivate = identityPrivateKey,
                    registrationId = registrationId,
                    addressName = senderId,
                    deviceId = deviceId,
                    sessionRecord = sessionRecord,
                    peerIdentity = peerIdentity?.identity_key,
                    preKeyRecord = preKeyRecord,
                    signedPreKeyRecord = signedPreKeyRecord,
                    kyberPreKeyRecord = kyberPreKeyRecord,
                    ciphertext = ciphertext,
                    messageType = messageType
                )

                // Save updated session
                val now = currentTimeMillis()
                database.trickDatabaseQueries.insertOrReplaceSession(
                    address_name = senderId,
                    device_id = deviceId.toLong(),
                    session_record = result.updatedSessionRecord,
                    created_at = now,
                    updated_at = now
                )

                // Remove consumed prekey
                if (result.consumedPreKeyId >= 0) {
                    database.trickDatabaseQueries.deletePreKey(result.consumedPreKeyId.toLong())
                }

                // Mark consumed Kyber prekey as used
                if (result.consumedKyberPreKeyId >= 0) {
                    database.trickDatabaseQueries.markKyberPreKeyUsed(
                        used_at = now,
                        kyber_prekey_id = result.consumedKyberPreKeyId.toLong()
                    )
                }

                // Save peer identity (TOFU for first contact via PreKey message)
                if (peerIdentity == null && result.senderIdentityKey.isNotEmpty()) {
                    database.trickDatabaseQueries.insertOrReplaceIdentityKey(
                        address_name = senderId,
                        device_id = deviceId.toLong(),
                        identity_key = result.senderIdentityKey,
                        trust_level = TRUST_LEVEL_TOFU,
                        first_seen_at = now,
                        last_seen_at = now
                    )
                }

                SignalDecryptResult(
                    plaintext = result.plaintext,
                    senderIdentityKey = result.senderIdentityKey
                )
            } catch (e: SignalNativeException) {
                when (e.errorCode) {
                    -5 -> {
                        val newKey = peerIdentity?.identity_key ?: ByteArray(0)
                        throw SignalError.UntrustedIdentity(senderId, newKey)
                    }
                    -7 -> throw SignalError.InvalidMessage(e.message ?: "Decryption failed")
                    -8 -> throw SignalError.InvalidMessage("Duplicate message")
                    -6 -> throw SignalError.NoSession(senderId)
                    else -> throw SignalError.InvalidMessage(e.message ?: "Decryption error")
                }
            }
        }
    }

    /**
     * Generate PreKeyBundle for sharing with peers.
     */
    suspend fun generatePreKeyBundle(): PreKeyBundleData = withContext(Dispatchers.Default) {
        mutex.withLock {
            checkInitialized()

            // Get latest signed prekey
            val signedPreKeyId = database.trickDatabaseQueries
                .selectLatestSignedPreKeyId()
                .executeAsOneOrNull()?.toInt()
                ?: throw IllegalStateException("No signed prekey available")

            val signedPreKeyRecord = database.trickDatabaseQueries
                .selectSignedPreKey(signedPreKeyId.toLong())
                .executeAsOne()
            val (spkPublic, spkSignature) = SignalNativeBridge.signedPreKeyRecordGetPublicKey(signedPreKeyRecord)

            // Get an available one-time prekey
            var preKeyId: Int? = null
            var preKeyPublic: ByteArray? = null
            val preKeyCount = database.trickDatabaseQueries.countPreKeys().executeAsOne()
            if (preKeyCount > 0) {
                val maxId = database.trickDatabaseQueries.selectMaxPreKeyId().executeAsOneOrNull()?.max_prekey_id
                if (maxId != null) {
                    for (id in 1..maxId.toInt()) {
                        val exists = database.trickDatabaseQueries.containsPreKey(id.toLong()).executeAsOne() > 0
                        if (exists) {
                            val record = database.trickDatabaseQueries.selectPreKey(id.toLong()).executeAsOne()
                            preKeyId = id
                            preKeyPublic = SignalNativeBridge.preKeyRecordGetPublicKey(record)
                            break
                        }
                    }
                }
            }

            // Get Kyber prekey
            val kyberMaxId = database.trickDatabaseQueries
                .selectMaxKyberPreKeyId()
                .executeAsOneOrNull()?.max_kyber_prekey_id?.toInt()
                ?: throw IllegalStateException("No Kyber prekey available")

            val kyberPreKeyRecord = database.trickDatabaseQueries
                .selectKyberPreKey(kyberMaxId.toLong())
                .executeAsOne()
            val (kyberPublic, kyberSignature) = SignalNativeBridge.kyberPreKeyRecordGetPublicKey(kyberPreKeyRecord)

            PreKeyBundleData(
                registrationId = registrationId,
                deviceId = 1,
                preKeyId = preKeyId,
                preKeyPublic = preKeyPublic,
                signedPreKeyId = signedPreKeyId,
                signedPreKeyPublic = spkPublic,
                signedPreKeySignature = spkSignature,
                identityKey = identityPublicKey,
                kyberPreKeyId = kyberMaxId,
                kyberPreKeyPublic = kyberPublic,
                kyberPreKeySignature = kyberSignature
            )
        }
    }

    fun getLocalRegistrationId(): Int {
        checkInitialized()
        return registrationId
    }

    fun getIdentityPublicKey(): ByteArray {
        checkInitialized()
        return identityPublicKey
    }

    suspend fun deleteSession(peerId: String, deviceId: Int = 1): Unit = withContext(Dispatchers.Default) {
        mutex.withLock {
            checkInitialized()
            database.trickDatabaseQueries.deleteSession(peerId, deviceId.toLong())
        }
    }

    fun getAvailablePreKeyCount(): Int {
        checkInitialized()
        return database.trickDatabaseQueries.countPreKeys().executeAsOne().toInt()
    }

    /**
     * Replenish prekeys if count < threshold.
     */
    suspend fun replenishPreKeysIfNeeded(
        threshold: Int = 20,
        generateCount: Int = 100
    ): Unit = withContext(Dispatchers.Default) {
        mutex.withLock {
            checkInitialized()
            val count = database.trickDatabaseQueries.countPreKeys().executeAsOne().toInt()
            if (count < threshold) {
                val maxId = database.trickDatabaseQueries.selectMaxPreKeyId()
                    .executeAsOneOrNull()?.max_prekey_id ?: 0L
                val startId = (maxId + 1).toInt()
                for (i in 0 until generateCount) {
                    val id = startId + i
                    val record = SignalNativeBridge.generatePreKeyRecord(id)
                    database.trickDatabaseQueries.insertPreKey(
                        prekey_id = id.toLong(),
                        prekey_record = record
                    )
                }
            }
        }
    }

    /**
     * Confirm identity change after UntrustedIdentity error.
     */
    suspend fun confirmIdentityChange(
        peerId: String,
        deviceId: Int = 1,
        newIdentityKey: ByteArray
    ): Unit = withContext(Dispatchers.Default) {
        mutex.withLock {
            checkInitialized()

            // Delete old session
            database.trickDatabaseQueries.deleteSession(peerId, deviceId.toLong())

            // Update identity
            val existingRecord = database.trickDatabaseQueries
                .selectIdentityKey(peerId, deviceId.toLong())
                .executeAsOneOrNull()

            val now = currentTimeMillis()
            database.trickDatabaseQueries.insertOrReplaceIdentityKey(
                address_name = peerId,
                device_id = deviceId.toLong(),
                identity_key = newIdentityKey,
                trust_level = TRUST_LEVEL_TOFU,
                first_seen_at = existingRecord?.first_seen_at ?: now,
                last_seen_at = now
            )
        }
    }

    // =====================
    // Private helpers
    // =====================

    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("SignalSessionManager not initialized. Call initialize() first.")
        }
    }

    private fun generateRandomShortId(): String {
        val bytes = ByteArray(6)
        // Use Rust for secure random via identity key pair generation side effect,
        // or just generate random bytes
        kotlin.random.Random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generate initial prekeys on first setup using Rust.
     */
    private fun generateInitialPreKeys() {
        // One-time prekeys
        for (id in 1..INITIAL_PREKEY_COUNT) {
            val record = SignalNativeBridge.generatePreKeyRecord(id)
            database.trickDatabaseQueries.insertPreKey(
                prekey_id = id.toLong(),
                prekey_record = record
            )
        }

        // Signed prekey
        val timestamp = currentTimeMillis()
        val signedPreKeyRecord = SignalNativeBridge.generateSignedPreKeyRecord(
            INITIAL_SIGNED_PREKEY_ID, timestamp, identityPrivateKey
        )
        database.trickDatabaseQueries.insertSignedPreKey(
            signed_prekey_id = INITIAL_SIGNED_PREKEY_ID.toLong(),
            signed_prekey_record = signedPreKeyRecord,
            created_at = timestamp
        )

        // Kyber prekey
        val kyberPreKeyRecord = SignalNativeBridge.generateKyberPreKeyRecord(
            INITIAL_KYBER_PREKEY_ID, timestamp, identityPrivateKey
        )
        database.trickDatabaseQueries.insertKyberPreKey(
            kyber_prekey_id = INITIAL_KYBER_PREKEY_ID.toLong(),
            kyber_prekey_record = kyberPreKeyRecord,
            created_at = timestamp,
            used_at = null
        )
    }
}
