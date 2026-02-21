package net.discdd.trick.signal

/**
 * Bridge to the Rust trick-signal-ffi crate.
 *
 * Platform implementations call into the shared Rust library:
 * - Android: via JNI (System.loadLibrary)
 * - iOS: via cinterop (C FFI)
 *
 * All functions operate on serialized byte arrays (protobuf blobs from SQLDelight).
 * Rust creates in-memory stores per-call, operates, and returns updated records.
 */
expect object SignalNativeBridge {

    // =========================================================================
    // Key Generation
    // =========================================================================

    /** Generate a new identity key pair. Returns (publicKey, privateKey). */
    fun generateIdentityKeyPair(): Pair<ByteArray, ByteArray>

    /** Generate a random registration ID. */
    fun generateRegistrationId(): Int

    /** Generate a pre-key record. Returns serialized PreKeyRecord. */
    fun generatePreKeyRecord(id: Int): ByteArray

    /** Generate a signed pre-key record. Returns serialized SignedPreKeyRecord. */
    fun generateSignedPreKeyRecord(id: Int, timestamp: Long, identityPrivateKey: ByteArray): ByteArray

    /** Generate a Kyber pre-key record. Returns serialized KyberPreKeyRecord. */
    fun generateKyberPreKeyRecord(id: Int, timestamp: Long, identityPrivateKey: ByteArray): ByteArray

    // =========================================================================
    // Session
    // =========================================================================

    /**
     * Process a pre-key bundle to establish a session (X3DH).
     * Returns SessionBuildResult with the new session record.
     */
    fun processPreKeyBundle(
        identityPublic: ByteArray,
        identityPrivate: ByteArray,
        registrationId: Int,
        addressName: String,
        deviceId: Int,
        existingPeerIdentity: ByteArray?,
        existingSession: ByteArray?,
        bundle: PreKeyBundleData
    ): SessionBuildResult

    // =========================================================================
    // Encrypt / Decrypt
    // =========================================================================

    /**
     * Encrypt a message using the Signal protocol.
     */
    fun encryptMessage(
        identityPublic: ByteArray,
        identityPrivate: ByteArray,
        registrationId: Int,
        addressName: String,
        deviceId: Int,
        sessionRecord: ByteArray,
        peerIdentity: ByteArray,
        plaintext: ByteArray
    ): NativeEncryptResult

    /**
     * Decrypt a Signal protocol message.
     *
     * For PreKeySignalMessage (type 3): pass the specific prekey records.
     * For SignalMessage (type 2): prekey records can be null.
     */
    fun decryptMessage(
        identityPublic: ByteArray,
        identityPrivate: ByteArray,
        registrationId: Int,
        addressName: String,
        deviceId: Int,
        sessionRecord: ByteArray,
        peerIdentity: ByteArray?,
        preKeyRecord: ByteArray?,
        signedPreKeyRecord: ByteArray?,
        kyberPreKeyRecord: ByteArray?,
        ciphertext: ByteArray,
        messageType: Int
    ): NativeDecryptResult

    // =========================================================================
    // Message Parsing
    // =========================================================================

    /** Get message type from ciphertext (2=SignalMessage, 3=PreKeySignalMessage). */
    fun getCiphertextMessageType(ciphertext: ByteArray): Int

    /**
     * Extract pre-key IDs from a PreKeySignalMessage.
     * Returns (preKeyId, signedPreKeyId). preKeyId is -1 if not present.
     */
    fun preKeyMessageGetIds(ciphertext: ByteArray): Pair<Int, Int>

    // =========================================================================
    // Record Utilities
    // =========================================================================

    /** Extract public key from a serialized PreKeyRecord. */
    fun preKeyRecordGetPublicKey(record: ByteArray): ByteArray

    /** Extract public key and signature from a SignedPreKeyRecord. */
    fun signedPreKeyRecordGetPublicKey(record: ByteArray): Pair<ByteArray, ByteArray>

    /** Extract public key and signature from a KyberPreKeyRecord. */
    fun kyberPreKeyRecordGetPublicKey(record: ByteArray): Pair<ByteArray, ByteArray>

    // =========================================================================
    // EC Operations (for QR key distribution, etc.)
    // =========================================================================

    /** Sign data with an EC private key. Returns signature bytes. */
    fun privateKeySign(privateKey: ByteArray, data: ByteArray): ByteArray

    /** Verify signature with an EC public key. */
    fun publicKeyVerify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
}

/**
 * Result of processing a pre-key bundle.
 */
data class SessionBuildResult(
    val sessionRecord: ByteArray,
    /** 0=new identity (first contact), 1=unchanged, 2=changed */
    val identityChangeType: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionBuildResult) return false
        return sessionRecord.contentEquals(other.sessionRecord) && identityChangeType == other.identityChangeType
    }
    override fun hashCode(): Int = sessionRecord.contentHashCode()
}

/**
 * Result of encrypting a message via Rust.
 */
data class NativeEncryptResult(
    val ciphertext: ByteArray,
    val messageType: Int,
    val updatedSessionRecord: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NativeEncryptResult) return false
        return ciphertext.contentEquals(other.ciphertext) && messageType == other.messageType
    }
    override fun hashCode(): Int = ciphertext.contentHashCode()
}

/**
 * Result of decrypting a message via Rust.
 */
data class NativeDecryptResult(
    val plaintext: ByteArray,
    val updatedSessionRecord: ByteArray,
    /** -1 if no prekey was consumed */
    val consumedPreKeyId: Int,
    /** -1 if no kyber prekey was consumed */
    val consumedKyberPreKeyId: Int,
    val senderIdentityKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NativeDecryptResult) return false
        return plaintext.contentEquals(other.plaintext)
    }
    override fun hashCode(): Int = plaintext.contentHashCode()
}
