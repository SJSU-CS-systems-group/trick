package net.discdd.trick.signal

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
    val kyberPreKeyId: Int? = null,
    val kyberPreKeyPublic: ByteArray? = null,
    val kyberPreKeySignature: ByteArray? = null
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
 * Main Signal protocol manager.
 * ALL encryption/decryption for network messages goes through this class.
 * This is the SINGLE SOURCE OF TRUTH for our Signal identity.
 */
expect class SignalSessionManager {
    /**
     * Initialize Signal identity.
     * - Loads existing identity from SignalIdentity table, OR
     * - Generates new identity, registration ID, and shortId if none exists
     * - Generates initial prekeys if needed
     * Must be called on app startup before any other operations.
     */
    suspend fun initialize()

    /**
     * Get our device's shortId (opaque identifier for sharing).
     * Independent of identity key - does not change on key rotation.
     */
    fun getShortId(): String

    /**
     * Check if Signal session exists with peer
     */
    fun hasSession(peerId: String, deviceId: Int = 1): Boolean

    /**
     * Build session from PreKeyBundle using X3DH.
     * - Constructs libsignal PreKeyBundle and calls SessionBuilder.process()
     * - libsignal internally validates signed prekey signature
     * - Applies TOFU for peer identity
     * @throws SignalError.SessionBuildFailed if libsignal rejects the bundle
     * @throws SignalError.UntrustedIdentity if peer identity changed (requires confirmIdentityChange)
     */
    suspend fun buildSessionFromPreKeyBundle(peerId: String, deviceId: Int = 1, bundle: PreKeyBundleData)

    /**
     * Encrypt message using Signal protocol.
     * @throws SignalError.NoSession if no session exists
     */
    suspend fun encryptMessage(peerId: String, deviceId: Int = 1, plaintext: ByteArray): SignalEncryptResult

    /**
     * Decrypt Signal protocol message.
     * - libsignal detects PreKeySignalMessage vs SignalMessage internally
     * - libsignal calls PreKeyStore.removePreKey() automatically on consumption
     * @throws SignalError.InvalidMessage if decryption fails
     * @throws SignalError.UntrustedIdentity if identity changed during decryption
     */
    suspend fun decryptMessage(senderId: String, deviceId: Int = 1, ciphertext: ByteArray): SignalDecryptResult

    /**
     * Generate PreKeyBundle for sharing with peers (e.g. via QR).
     * Uses current signed prekey and an available one-time prekey.
     */
    suspend fun generatePreKeyBundle(): PreKeyBundleData

    /**
     * Get local registration ID
     */
    fun getLocalRegistrationId(): Int

    /**
     * Get our public identity key (serialized)
     */
    fun getIdentityPublicKey(): ByteArray

    /**
     * Delete session (for re-keying or untrust)
     */
    suspend fun deleteSession(peerId: String, deviceId: Int = 1)

    /**
     * Get count of available one-time prekeys
     */
    fun getAvailablePreKeyCount(): Int

    /**
     * Replenish prekeys if count < threshold.
     * Called on app startup and after session creation.
     */
    suspend fun replenishPreKeysIfNeeded(threshold: Int = 20, generateCount: Int = 100)
    /**
     * Confirm identity change after UntrustedIdentity error.
     * Deletes old session, updates stored identity, allows retry.
     */
    suspend fun confirmIdentityChange(peerId: String, deviceId: Int = 1, newIdentityKey: ByteArray)
}
