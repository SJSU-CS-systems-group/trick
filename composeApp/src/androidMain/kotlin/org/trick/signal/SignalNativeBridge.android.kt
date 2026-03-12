package org.trick.signal

/**
 * Android implementation of SignalNativeBridge using JNI.
 * Calls into libtrick_signal_ffi.so (built from Rust).
 */
actual object SignalNativeBridge {

    private var libraryLoaded = false

    private fun ensureLibraryLoaded() {
        if (!libraryLoaded) {
            try {
                System.loadLibrary("trick_signal_ffi")
                libraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                throw UnsatisfiedLinkError(
                    "Failed to load libtrick_signal_ffi.so. " +
                    "The native library must be built first. " +
                    "See rust/trick-signal-ffi/build-android.sh for build instructions. " +
                    "Original error: ${e.message}"
                ).apply {
                    initCause(e)
                }
            }
        }
    }

    // =========================================================================
    // Key Generation
    // =========================================================================

    actual fun generateIdentityKeyPair(): Pair<ByteArray, ByteArray> {
        ensureLibraryLoaded()
        val publicKey = ByteArray(33)
        val privateKey = ByteArray(32)
        val result = Jni.nativeGenerateIdentityKeyPair(publicKey, privateKey)
        if (result < 0) throw SignalNativeException("generateIdentityKeyPair failed: $result")
        return Pair(publicKey, privateKey)
    }

    actual fun generateRegistrationId(): Int {
        ensureLibraryLoaded()
        val result = Jni.nativeGenerateRegistrationId()
        if (result < 0) throw SignalNativeException("generateRegistrationId failed: $result")
        return result
    }

    actual fun generatePreKeyRecord(id: Int): ByteArray {
        ensureLibraryLoaded()
        val buffer = ByteArray(8192)
        val written = Jni.nativeGeneratePreKeyRecord(id, buffer)
        if (written < 0) throw SignalNativeException("generatePreKeyRecord failed: $written")
        return buffer.copyOf(written)
    }

    actual fun generateSignedPreKeyRecord(id: Int, timestamp: Long, identityPrivateKey: ByteArray): ByteArray {
        ensureLibraryLoaded()
        val buffer = ByteArray(8192)
        val written = Jni.nativeGenerateSignedPreKeyRecord(id, timestamp, identityPrivateKey, buffer)
        if (written < 0) throw SignalNativeException("generateSignedPreKeyRecord failed: $written")
        return buffer.copyOf(written)
    }

    actual fun generateKyberPreKeyRecord(id: Int, timestamp: Long, identityPrivateKey: ByteArray): ByteArray {
        ensureLibraryLoaded()
        val buffer = ByteArray(8192)
        val written = Jni.nativeGenerateKyberPreKeyRecord(id, timestamp, identityPrivateKey, buffer)
        if (written < 0) throw SignalNativeException("generateKyberPreKeyRecord failed: $written")
        return buffer.copyOf(written)
    }

    // =========================================================================
    // Session
    // =========================================================================

    actual fun processPreKeyBundle(
        identityPublic: ByteArray,
        identityPrivate: ByteArray,
        registrationId: Int,
        addressName: String,
        deviceId: Int,
        existingPeerIdentity: ByteArray?,
        existingSession: ByteArray?,
        bundle: PreKeyBundleData
    ): SessionBuildResult {
        ensureLibraryLoaded()
        
        // Validate required Kyber fields before calling native code
        // (libsignal 0.86.7+ requires Kyber prekeys)
        require(bundle.kyberPreKeyId != null && bundle.kyberPreKeyId!! >= 0) {
            "Kyber prekey ID is required but was null or negative"
        }
        require(bundle.kyberPreKeyPublic != null && bundle.kyberPreKeyPublic!!.isNotEmpty()) {
            "Kyber prekey public key is required but was null or empty"
        }
        require(bundle.kyberPreKeySignature != null && bundle.kyberPreKeySignature!!.isNotEmpty()) {
            "Kyber prekey signature is required but was null or empty"
        }
        
        val outSession = ByteArray(8192)
        val outIdentityChanged = IntArray(1)

        val written = Jni.nativeProcessPreKeyBundle(
            identityPublic, identityPrivate, registrationId,
            addressName, deviceId,
            existingPeerIdentity, existingSession,
            bundle.registrationId, bundle.deviceId,
            bundle.preKeyId ?: -1, bundle.preKeyPublic,
            bundle.signedPreKeyId, bundle.signedPreKeyPublic,
            bundle.signedPreKeySignature, bundle.identityKey,
            bundle.kyberPreKeyId!!, bundle.kyberPreKeyPublic!!,
            bundle.kyberPreKeySignature!!,
            outSession, outIdentityChanged
        )
        if (written < 0) {
            val errorMsg = when (written) {
                -1 -> "Invalid argument: bundle format error"
                -3 -> "Serialization error: invalid bundle data"
                -4 -> "Invalid key: key deserialization failed"
                -5 -> "Untrusted identity: identity key changed"
                -99 -> "Internal error: check bundle format and Kyber prekey data"
                else -> "Unknown error"
            }
            throw SignalNativeException("processPreKeyBundle failed: $errorMsg (code $written)", written)
        }
        return SessionBuildResult(outSession.copyOf(written), outIdentityChanged[0])
    }

    // =========================================================================
    // Encrypt / Decrypt
    // =========================================================================

    actual fun encryptMessage(
        identityPublic: ByteArray,
        identityPrivate: ByteArray,
        registrationId: Int,
        addressName: String,
        deviceId: Int,
        sessionRecord: ByteArray,
        peerIdentity: ByteArray,
        plaintext: ByteArray
    ): NativeEncryptResult {
        ensureLibraryLoaded()
        val outCiphertext = ByteArray(plaintext.size + 4096) // plaintext + Signal/Kyber overhead
        // Session record can grow significantly with many unacknowledged messages (skipped ratchet keys)
        val outUpdatedSession = ByteArray(maxOf(262_144, sessionRecord.size * 2))
        val outMeta = IntArray(2) // [messageType, sessionLen]

        val ctWritten = Jni.nativeEncryptMessage(
            identityPublic, identityPrivate, registrationId,
            addressName, deviceId,
            sessionRecord, peerIdentity, plaintext,
            outCiphertext, outUpdatedSession, outMeta
        )
        if (ctWritten < 0) throw SignalNativeException("encryptMessage failed: $ctWritten", ctWritten)

        return NativeEncryptResult(
            ciphertext = outCiphertext.copyOf(ctWritten),
            messageType = outMeta[0],
            updatedSessionRecord = outUpdatedSession.copyOf(outMeta[1])
        )
    }

    actual fun decryptMessage(
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
    ): NativeDecryptResult {
        ensureLibraryLoaded()
        val outPlaintext = ByteArray(ciphertext.size + 256)
        // Session record can grow significantly with many unacknowledged messages (skipped ratchet keys)
        val outUpdatedSession = ByteArray(maxOf(262_144, sessionRecord.size * 2))
        val outSenderIdentity = ByteArray(64)
        val outMeta = IntArray(4) // [consumedPkId, consumedKpkId, sessLen, idLen]

        val ptWritten = Jni.nativeDecryptMessage(
            identityPublic, identityPrivate, registrationId,
            addressName, deviceId,
            sessionRecord, peerIdentity,
            preKeyRecord, signedPreKeyRecord, kyberPreKeyRecord,
            ciphertext, messageType,
            outPlaintext, outUpdatedSession, outSenderIdentity, outMeta
        )
        if (ptWritten < 0) throw SignalNativeException("decryptMessage failed: $ptWritten", ptWritten)

        return NativeDecryptResult(
            plaintext = outPlaintext.copyOf(ptWritten),
            updatedSessionRecord = outUpdatedSession.copyOf(outMeta[2]),
            consumedPreKeyId = outMeta[0],
            consumedKyberPreKeyId = outMeta[1],
            senderIdentityKey = outSenderIdentity.copyOf(outMeta[3])
        )
    }

    // =========================================================================
    // Message Parsing
    // =========================================================================

    actual fun getCiphertextMessageType(ciphertext: ByteArray): Int {
        ensureLibraryLoaded()
        val result = Jni.nativeGetCiphertextMessageType(ciphertext)
        if (result < 0) throw SignalNativeException("getCiphertextMessageType failed: $result", result)
        return result
    }

    actual fun preKeyMessageGetIds(ciphertext: ByteArray): Pair<Int, Int> {
        ensureLibraryLoaded()
        val outIds = IntArray(2)
        val result = Jni.nativePreKeyMessageGetIds(ciphertext, outIds)
        if (result < 0) throw SignalNativeException("preKeyMessageGetIds failed: $result", result)
        return Pair(outIds[0], outIds[1])
    }

    // =========================================================================
    // Record Utilities
    // =========================================================================

    actual fun preKeyRecordGetPublicKey(record: ByteArray): ByteArray {
        ensureLibraryLoaded()
        val buffer = ByteArray(64)
        val written = Jni.nativePreKeyRecordGetPublicKey(record, buffer)
        if (written < 0) throw SignalNativeException("preKeyRecordGetPublicKey failed: $written", written)
        return buffer.copyOf(written)
    }

    actual fun signedPreKeyRecordGetPublicKey(record: ByteArray): Pair<ByteArray, ByteArray> {
        ensureLibraryLoaded()
        val pubKey = ByteArray(64)
        val signature = ByteArray(128)
        val result = Jni.nativeSignedPreKeyRecordGetPublicKey(record, pubKey, signature)
        if (result < 0) throw SignalNativeException("signedPreKeyRecordGetPublicKey failed: $result", result)
        // We don't get exact lengths back from this JNI call, but EC public keys are 33 bytes
        // and signatures are 64 bytes. Trim based on known sizes.
        return Pair(pubKey.copyOf(33), signature.copyOf(64))
    }

    actual fun kyberPreKeyRecordGetPublicKey(record: ByteArray): Pair<ByteArray, ByteArray> {
        ensureLibraryLoaded()
        val pubKey = ByteArray(2048) // Kyber public keys are ~1569 bytes (1 type-byte + 1568 raw)
        val signature = ByteArray(128)
        val outMeta = IntArray(2) // [pub_key_len, sig_len]
        val result = Jni.nativeKyberPreKeyRecordGetPublicKey(record, pubKey, signature, outMeta)
        if (result < 0) throw SignalNativeException("kyberPreKeyRecordGetPublicKey failed: $result", result)
        return Pair(pubKey.copyOf(outMeta[0]), signature.copyOf(outMeta[1]))
    }

    // =========================================================================
    // EC Operations
    // =========================================================================

    actual fun privateKeySign(privateKey: ByteArray, data: ByteArray): ByteArray {
        ensureLibraryLoaded()
        val buffer = ByteArray(128)
        val written = Jni.nativePrivateKeySign(privateKey, data, buffer)
        if (written < 0) throw SignalNativeException("privateKeySign failed: $written", written)
        return buffer.copyOf(written)
    }

    actual fun publicKeyVerify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        ensureLibraryLoaded()
        val result = Jni.nativePublicKeyVerify(publicKey, data, signature)
        if (result < 0) throw SignalNativeException("publicKeyVerify failed: $result", result)
        return result == 1
    }

    // Delegates to the legacy shim which matches the .so's exported JNI symbols.
    // Remove Jni alias and restore external declarations here once the Rust library
    // is rebuilt against org.trick.
    private val Jni get() = net.discdd.trick.signal.SignalNativeBridge
}
