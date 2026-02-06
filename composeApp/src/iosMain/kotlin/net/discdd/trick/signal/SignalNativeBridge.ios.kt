@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package net.discdd.trick.signal

import kotlinx.cinterop.*
import net.discdd.trick.libsignal.bridge.*

/**
 * iOS implementation of SignalNativeBridge using cinterop with the
 * Rust trick_signal_ffi C FFI functions.
 */
actual object SignalNativeBridge {

    /**
     * Safely gets a pointer from a ByteArray, handling empty arrays by returning null.
     * Empty arrays cannot use addressOf(0) as it will crash.
     */
    private inline fun <T> ByteArray.usePinnedSafe(block: (CPointer<ByteVar>?, Int) -> T): T {
        return if (this.isEmpty()) {
            block(null, 0)
        } else {
            this.usePinned { pinned ->
                block(pinned.addressOf(0).reinterpret(), this.size)
            }
        }
    }

    /**
     * Safely gets a pointer from a nullable ByteArray, handling both null and empty arrays.
     * Returns null pointer with length 0 for null or empty arrays.
     */
    private inline fun <T> ByteArray?.usePinnedSafeNullable(block: (CPointer<ByteVar>?, Int) -> T): T {
        return when {
            this == null -> block(null, 0)
            this.isEmpty() -> block(null, 0)
            else -> this.usePinned { pinned ->
                block(pinned.addressOf(0).reinterpret(), this.size)
            }
        }
    }

    /**
     * Helper function to conditionally pin 3 nullable ByteArrays and execute a lambda with all pointers.
     * This ensures all memory remains pinned simultaneously for the duration of the native call.
     * Handles both null and empty arrays safely.
     */
    private inline fun <T> withNullablePinned3(
        existingPeerIdentity: ByteArray?,
        existingSession: ByteArray?,
        preKeyPublic: ByteArray?,
        block: (
            existPeerPtr: CPointer<ByteVar>?,
            existPeerLen: Int,
            existSessPtr: CPointer<ByteVar>?,
            existSessLen: Int,
            bPkPtr: CPointer<ByteVar>?,
            bPkLen: Int
        ) -> T
    ): T {
        // Helper to check if array is non-null and non-empty
        fun ByteArray?.isNotEmpty(): Boolean = this != null && !this.isEmpty()
        
        return when {
            existingPeerIdentity.isNotEmpty() && existingSession.isNotEmpty() && preKeyPublic.isNotEmpty() -> {
                existingPeerIdentity!!.usePinned { existPeer ->
                    existingSession!!.usePinned { existSess ->
                        preKeyPublic!!.usePinned { bPk ->
                            block(
                                existPeer.addressOf(0).reinterpret(), existingPeerIdentity.size,
                                existSess.addressOf(0).reinterpret(), existingSession.size,
                                bPk.addressOf(0).reinterpret(), preKeyPublic.size
                            )
                        }
                    }
                }
            }
            existingPeerIdentity.isNotEmpty() && existingSession.isNotEmpty() -> {
                existingPeerIdentity!!.usePinned { existPeer ->
                    existingSession!!.usePinned { existSess ->
                        block(
                            existPeer.addressOf(0).reinterpret(), existingPeerIdentity.size,
                            existSess.addressOf(0).reinterpret(), existingSession.size,
                            null, 0
                        )
                    }
                }
            }
            existingPeerIdentity.isNotEmpty() && preKeyPublic.isNotEmpty() -> {
                existingPeerIdentity!!.usePinned { existPeer ->
                    preKeyPublic!!.usePinned { bPk ->
                        block(
                            existPeer.addressOf(0).reinterpret(), existingPeerIdentity.size,
                            null, 0,
                            bPk.addressOf(0).reinterpret(), preKeyPublic.size
                        )
                    }
                }
            }
            existingSession.isNotEmpty() && preKeyPublic.isNotEmpty() -> {
                existingSession!!.usePinned { existSess ->
                    preKeyPublic!!.usePinned { bPk ->
                        block(
                            null, 0,
                            existSess.addressOf(0).reinterpret(), existingSession.size,
                            bPk.addressOf(0).reinterpret(), preKeyPublic.size
                        )
                    }
                }
            }
            existingPeerIdentity.isNotEmpty() -> {
                existingPeerIdentity!!.usePinned { existPeer ->
                    block(
                        existPeer.addressOf(0).reinterpret(), existingPeerIdentity.size,
                        null, 0,
                        null, 0
                    )
                }
            }
            existingSession.isNotEmpty() -> {
                existingSession!!.usePinned { existSess ->
                    block(
                        null, 0,
                        existSess.addressOf(0).reinterpret(), existingSession.size,
                        null, 0
                    )
                }
            }
            preKeyPublic.isNotEmpty() -> {
                preKeyPublic!!.usePinned { bPk ->
                    block(
                        null, 0,
                        null, 0,
                        bPk.addressOf(0).reinterpret(), preKeyPublic.size
                    )
                }
            }
            else -> {
                block(null, 0, null, 0, null, 0)
            }
        }
    }

    /**
     * Helper function to conditionally pin multiple nullable ByteArrays and execute a lambda with all pointers.
     * This ensures all memory remains pinned simultaneously for the duration of the native call.
     * Handles both null and empty arrays safely.
     */
    private inline fun <T> withNullablePinned(
        peerIdentity: ByteArray?,
        preKeyRecord: ByteArray?,
        signedPreKeyRecord: ByteArray?,
        kyberPreKeyRecord: ByteArray?,
        block: (
            peerPtr: CPointer<ByteVar>?,
            peerLen: Int,
            pkPtr: CPointer<ByteVar>?,
            pkLen: Int,
            spkPtr: CPointer<ByteVar>?,
            spkLen: Int,
            kpkPtr: CPointer<ByteVar>?,
            kpkLen: Int
        ) -> T
    ): T {
        // Helper to check if array is non-null and non-empty
        fun ByteArray?.isNotEmpty(): Boolean = this != null && !this.isEmpty()
        
        return when {
            peerIdentity.isNotEmpty() && preKeyRecord.isNotEmpty() && signedPreKeyRecord.isNotEmpty() && kyberPreKeyRecord.isNotEmpty() -> {
                peerIdentity.usePinned { peer ->
                    preKeyRecord.usePinned { pk ->
                        signedPreKeyRecord.usePinned { spk ->
                            kyberPreKeyRecord.usePinned { kpk ->
                                block(
                                    peer.addressOf(0).reinterpret(), peerIdentity.size,
                                    pk.addressOf(0).reinterpret(), preKeyRecord.size,
                                    spk.addressOf(0).reinterpret(), signedPreKeyRecord.size,
                                    kpk.addressOf(0).reinterpret(), kyberPreKeyRecord.size
                                )
                            }
                        }
                    }
                }
            }
            peerIdentity.isNotEmpty() && preKeyRecord.isNotEmpty() && signedPreKeyRecord.isNotEmpty() -> {
                peerIdentity!!.usePinned { peer ->
                    preKeyRecord!!.usePinned { pk ->
                        signedPreKeyRecord!!.usePinned { spk ->
                            block(
                                peer.addressOf(0).reinterpret(), peerIdentity.size,
                                pk.addressOf(0).reinterpret(), preKeyRecord.size,
                                spk.addressOf(0).reinterpret(), signedPreKeyRecord.size,
                                null, 0
                            )
                        }
                    }
                }
            }
            peerIdentity.isNotEmpty() && preKeyRecord.isNotEmpty() && kyberPreKeyRecord.isNotEmpty() -> {
                peerIdentity!!.usePinned { peer ->
                    preKeyRecord!!.usePinned { pk ->
                        kyberPreKeyRecord!!.usePinned { kpk ->
                            block(
                                peer.addressOf(0).reinterpret(), peerIdentity.size,
                                pk.addressOf(0).reinterpret(), preKeyRecord.size,
                                null, 0,
                                kpk.addressOf(0).reinterpret(), kyberPreKeyRecord.size
                            )
                        }
                    }
                }
            }
            peerIdentity.isNotEmpty() && signedPreKeyRecord.isNotEmpty() && kyberPreKeyRecord.isNotEmpty() -> {
                peerIdentity!!.usePinned { peer ->
                    signedPreKeyRecord!!.usePinned { spk ->
                        kyberPreKeyRecord!!.usePinned { kpk ->
                            block(
                                peer.addressOf(0).reinterpret(), peerIdentity.size,
                                null, 0,
                                spk.addressOf(0).reinterpret(), signedPreKeyRecord.size,
                                kpk.addressOf(0).reinterpret(), kyberPreKeyRecord.size
                            )
                        }
                    }
                }
            }
            preKeyRecord.isNotEmpty() && signedPreKeyRecord.isNotEmpty() && kyberPreKeyRecord.isNotEmpty() -> {
                preKeyRecord!!.usePinned { pk ->
                    signedPreKeyRecord!!.usePinned { spk ->
                        kyberPreKeyRecord!!.usePinned { kpk ->
                            block(
                                null, 0,
                                pk.addressOf(0).reinterpret(), preKeyRecord.size,
                                spk.addressOf(0).reinterpret(), signedPreKeyRecord.size,
                                kpk.addressOf(0).reinterpret(), kyberPreKeyRecord.size
                            )
                        }
                    }
                }
            }
            peerIdentity.isNotEmpty() && preKeyRecord.isNotEmpty() -> {
                peerIdentity!!.usePinned { peer ->
                    preKeyRecord!!.usePinned { pk ->
                        block(
                            peer.addressOf(0).reinterpret(), peerIdentity.size,
                            pk.addressOf(0).reinterpret(), preKeyRecord.size,
                            null, 0,
                            null, 0
                        )
                    }
                }
            }
            peerIdentity.isNotEmpty() && signedPreKeyRecord.isNotEmpty() -> {
                peerIdentity!!.usePinned { peer ->
                    signedPreKeyRecord!!.usePinned { spk ->
                        block(
                            peer.addressOf(0).reinterpret(), peerIdentity.size,
                            null, 0,
                            spk.addressOf(0).reinterpret(), signedPreKeyRecord.size,
                            null, 0
                        )
                    }
                }
            }
            peerIdentity.isNotEmpty() && kyberPreKeyRecord.isNotEmpty() -> {
                peerIdentity!!.usePinned { peer ->
                    kyberPreKeyRecord!!.usePinned { kpk ->
                        block(
                            peer.addressOf(0).reinterpret(), peerIdentity.size,
                            null, 0,
                            null, 0,
                            kpk.addressOf(0).reinterpret(), kyberPreKeyRecord.size
                        )
                    }
                }
            }
            preKeyRecord.isNotEmpty() && signedPreKeyRecord.isNotEmpty() -> {
                preKeyRecord!!.usePinned { pk ->
                    signedPreKeyRecord!!.usePinned { spk ->
                        block(
                            null, 0,
                            pk.addressOf(0).reinterpret(), preKeyRecord.size,
                            spk.addressOf(0).reinterpret(), signedPreKeyRecord.size,
                            null, 0
                        )
                    }
                }
            }
            preKeyRecord.isNotEmpty() && kyberPreKeyRecord.isNotEmpty() -> {
                preKeyRecord!!.usePinned { pk ->
                    kyberPreKeyRecord!!.usePinned { kpk ->
                        block(
                            null, 0,
                            pk.addressOf(0).reinterpret(), preKeyRecord.size,
                            null, 0,
                            kpk.addressOf(0).reinterpret(), kyberPreKeyRecord.size
                        )
                    }
                }
            }
            signedPreKeyRecord.isNotEmpty() && kyberPreKeyRecord.isNotEmpty() -> {
                signedPreKeyRecord!!.usePinned { spk ->
                    kyberPreKeyRecord!!.usePinned { kpk ->
                        block(
                            null, 0,
                            null, 0,
                            spk.addressOf(0).reinterpret(), signedPreKeyRecord.size,
                            kpk.addressOf(0).reinterpret(), kyberPreKeyRecord.size
                        )
                    }
                }
            }
            peerIdentity.isNotEmpty() -> {
                peerIdentity!!.usePinned { peer ->
                    block(
                        peer.addressOf(0).reinterpret(), peerIdentity.size,
                        null, 0,
                        null, 0,
                        null, 0
                    )
                }
            }
            preKeyRecord.isNotEmpty() -> {
                preKeyRecord!!.usePinned { pk ->
                    block(
                        null, 0,
                        pk.addressOf(0).reinterpret(), preKeyRecord.size,
                        null, 0,
                        null, 0
                    )
                }
            }
            signedPreKeyRecord.isNotEmpty() -> {
                signedPreKeyRecord!!.usePinned { spk ->
                    block(
                        null, 0,
                        null, 0,
                        spk.addressOf(0).reinterpret(), signedPreKeyRecord.size,
                        null, 0
                    )
                }
            }
            kyberPreKeyRecord.isNotEmpty() -> {
                kyberPreKeyRecord!!.usePinned { kpk ->
                    block(
                        null, 0,
                        null, 0,
                        null, 0,
                        kpk.addressOf(0).reinterpret(), kyberPreKeyRecord.size
                    )
                }
            }
            else -> {
                block(null, 0, null, 0, null, 0, null, 0)
            }
        }
    }

    // =========================================================================
    // Key Generation
    // =========================================================================

    actual fun generateIdentityKeyPair(): Pair<ByteArray, ByteArray> {
        val publicKey = ByteArray(33)
        val privateKey = ByteArray(32)
        val result = publicKey.usePinned { pubPinned ->
            privateKey.usePinned { privPinned ->
                trick_generate_identity_key_pair(
                    pubPinned.addressOf(0).reinterpret(), 33,
                    privPinned.addressOf(0).reinterpret(), 32
                )
            }
        }
        if (result < 0) throw SignalNativeException("generateIdentityKeyPair failed: $result", result)
        return Pair(publicKey, privateKey)
    }

    actual fun generateRegistrationId(): Int {
        val result = trick_generate_registration_id()
        if (result < 0) throw SignalNativeException("generateRegistrationId failed: $result", result)
        return result
    }

    actual fun generatePreKeyRecord(id: Int): ByteArray {
        val buffer = ByteArray(8192)
        val written = buffer.usePinned { pinned ->
            trick_generate_pre_key_record(id, pinned.addressOf(0).reinterpret(), 8192)
        }
        if (written < 0) throw SignalNativeException("generatePreKeyRecord failed: $written", written)
        return buffer.copyOf(written)
    }

    actual fun generateSignedPreKeyRecord(id: Int, timestamp: Long, identityPrivateKey: ByteArray): ByteArray {
        val buffer = ByteArray(8192)
        val written = identityPrivateKey.usePinned { keyPinned ->
            buffer.usePinned { outPinned ->
                trick_generate_signed_pre_key_record(
                    id, timestamp,
                    keyPinned.addressOf(0).reinterpret(), identityPrivateKey.size,
                    outPinned.addressOf(0).reinterpret(), 8192
                )
            }
        }
        if (written < 0) throw SignalNativeException("generateSignedPreKeyRecord failed: $written", written)
        return buffer.copyOf(written)
    }

    actual fun generateKyberPreKeyRecord(id: Int, timestamp: Long, identityPrivateKey: ByteArray): ByteArray {
        val buffer = ByteArray(8192)
        val written = identityPrivateKey.usePinned { keyPinned ->
            buffer.usePinned { outPinned ->
                trick_generate_kyber_pre_key_record(
                    id, timestamp,
                    keyPinned.addressOf(0).reinterpret(), identityPrivateKey.size,
                    outPinned.addressOf(0).reinterpret(), 8192
                )
            }
        }
        if (written < 0) throw SignalNativeException("generateKyberPreKeyRecord failed: $written", written)
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
    ): SessionBuildResult = memScoped {
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
        val outIdentityChanged = alloc<IntVar>()
        val outSessionLen = alloc<IntVar>()

        val result = identityPublic.usePinned { idPub ->
            identityPrivate.usePinned { idPriv ->
                outSession.usePinned { sessPinned ->
                    bundle.signedPreKeyPublic.usePinned { spkPub ->
                        bundle.signedPreKeySignature.usePinned { spkSig ->
                            bundle.identityKey.usePinned { bIk ->
                                bundle.kyberPreKeyPublic!!.usePinned { kpkPub ->
                                    bundle.kyberPreKeySignature!!.usePinned { kpkSig ->
                                        // Pin nullable arrays for the duration of the native call
                                        withNullablePinned3(
                                            existingPeerIdentity,
                                            existingSession,
                                            bundle.preKeyPublic
                                        ) { existPeerPtr, existPeerLen, existSessPtr, existSessLen, bPkPtr, bPkLen ->
                                            trick_process_pre_key_bundle(
                                                idPub.addressOf(0).reinterpret(), identityPublic.size,
                                                idPriv.addressOf(0).reinterpret(), identityPrivate.size,
                                                registrationId,
                                                addressName.cstr.ptr,
                                                deviceId,
                                                existPeerPtr, existPeerLen,
                                                existSessPtr, existSessLen,
                                                bundle.registrationId, bundle.deviceId,
                                                bundle.preKeyId ?: -1,
                                                bPkPtr, bPkLen,
                                                bundle.signedPreKeyId,
                                                spkPub.addressOf(0).reinterpret(), bundle.signedPreKeyPublic.size,
                                                spkSig.addressOf(0).reinterpret(), bundle.signedPreKeySignature.size,
                                                bIk.addressOf(0).reinterpret(), bundle.identityKey.size,
                                                bundle.kyberPreKeyId!!,
                                                kpkPub.addressOf(0).reinterpret(), bundle.kyberPreKeyPublic!!.size,
                                                kpkSig.addressOf(0).reinterpret(), bundle.kyberPreKeySignature!!.size,
                                                sessPinned.addressOf(0).reinterpret(), 8192, outSessionLen.ptr,
                                                outIdentityChanged.ptr
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (result < 0) {
            val errorMsg = when (result) {
                -1 -> "Invalid argument: bundle format error"
                -3 -> "Serialization error: invalid bundle data"
                -4 -> "Invalid key: key deserialization failed"
                -5 -> "Untrusted identity: identity key changed"
                -99 -> "Internal error: check bundle format and Kyber prekey data"
                else -> "Unknown error"
            }
            throw SignalNativeException("processPreKeyBundle failed: $errorMsg (code $result)", result)
        }
        SessionBuildResult(outSession.copyOf(outSessionLen.value), outIdentityChanged.value)
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
    ): NativeEncryptResult = memScoped {
        val outCiphertext = ByteArray(plaintext.size + 4096)
        val outUpdatedSession = ByteArray(8192)
        val outCtLen = alloc<IntVar>()
        val outMsgType = alloc<IntVar>()
        val outSessLen = alloc<IntVar>()

        val result = identityPublic.usePinned { idPub ->
            identityPrivate.usePinned { idPriv ->
                // Handle empty sessionRecord safely
                sessionRecord.usePinnedSafe { sessPtr, sessLen ->
                    peerIdentity.usePinned { peer ->
                        plaintext.usePinned { pt ->
                            outCiphertext.usePinned { ct ->
                                outUpdatedSession.usePinned { updSess ->
                                    trick_encrypt_message(
                                        idPub.addressOf(0).reinterpret(), identityPublic.size,
                                        idPriv.addressOf(0).reinterpret(), identityPrivate.size,
                                        registrationId,
                                        addressName.cstr.ptr,
                                        deviceId,
                                        sessPtr, sessLen,
                                        peer.addressOf(0).reinterpret(), peerIdentity.size,
                                        pt.addressOf(0).reinterpret(), plaintext.size,
                                        ct.addressOf(0).reinterpret(), outCiphertext.size, outCtLen.ptr,
                                        outMsgType.ptr,
                                        updSess.addressOf(0).reinterpret(), 8192, outSessLen.ptr
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (result < 0) throw SignalNativeException("encryptMessage failed: $result", result)
        NativeEncryptResult(
            ciphertext = outCiphertext.copyOf(outCtLen.value),
            messageType = outMsgType.value,
            updatedSessionRecord = outUpdatedSession.copyOf(outSessLen.value)
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
    ): NativeDecryptResult = memScoped {
        val outPlaintext = ByteArray(ciphertext.size + 256)
        val outUpdatedSession = ByteArray(8192)
        val outSenderIdentity = ByteArray(64)
        val outPtLen = alloc<IntVar>()
        val outSessLen = alloc<IntVar>()
        val outConsumedPkId = alloc<IntVar>()
        val outConsumedKpkId = alloc<IntVar>()
        val outIdLen = alloc<IntVar>()

        val result = identityPublic.usePinned { idPub ->
            identityPrivate.usePinned { idPriv ->
                // Handle empty sessionRecord safely (can be empty for first-contact PreKey messages)
                sessionRecord.usePinnedSafe { sessPtr, sessLen ->
                    ciphertext.usePinned { ct ->
                        outPlaintext.usePinned { pt ->
                            outUpdatedSession.usePinned { updSess ->
                                outSenderIdentity.usePinned { senderId ->
                                    // Pin nullable arrays for the duration of the native call
                                    withNullablePinned(
                                        peerIdentity,
                                        preKeyRecord,
                                        signedPreKeyRecord,
                                        kyberPreKeyRecord
                                    ) { peerPtr, peerLen, pkPtr, pkLen, spkPtr, spkLen, kpkPtr, kpkLen ->
                                    trick_decrypt_message(
                                        idPub.addressOf(0).reinterpret(), identityPublic.size,
                                        idPriv.addressOf(0).reinterpret(), identityPrivate.size,
                                        registrationId,
                                        addressName.cstr.ptr,
                                        deviceId,
                                        sessPtr, sessLen,
                                        peerPtr, peerLen,
                                        pkPtr, pkLen,
                                        spkPtr, spkLen,
                                        kpkPtr, kpkLen,
                                        ct.addressOf(0).reinterpret(), ciphertext.size,
                                        messageType,
                                        pt.addressOf(0).reinterpret(), outPlaintext.size, outPtLen.ptr,
                                        updSess.addressOf(0).reinterpret(), 8192, outSessLen.ptr,
                                        outConsumedPkId.ptr,
                                        outConsumedKpkId.ptr,
                                        senderId.addressOf(0).reinterpret(), 64, outIdLen.ptr
                                    )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (result < 0) throw SignalNativeException("decryptMessage failed: $result", result)
        NativeDecryptResult(
            plaintext = outPlaintext.copyOf(outPtLen.value),
            updatedSessionRecord = outUpdatedSession.copyOf(outSessLen.value),
            consumedPreKeyId = outConsumedPkId.value,
            consumedKyberPreKeyId = outConsumedKpkId.value,
            senderIdentityKey = outSenderIdentity.copyOf(outIdLen.value)
        )
    }

    // =========================================================================
    // Message Parsing
    // =========================================================================

    actual fun getCiphertextMessageType(ciphertext: ByteArray): Int {
        val result = ciphertext.usePinned { pinned ->
            trick_get_ciphertext_message_type(pinned.addressOf(0).reinterpret(), ciphertext.size)
        }
        if (result < 0) throw SignalNativeException("getCiphertextMessageType failed: $result", result)
        return result
    }

    actual fun preKeyMessageGetIds(ciphertext: ByteArray): Pair<Int, Int> = memScoped {
        val outPkId = alloc<IntVar>()
        val outSpkId = alloc<IntVar>()
        val result = ciphertext.usePinned { pinned ->
            trick_prekey_message_get_ids(
                pinned.addressOf(0).reinterpret(), ciphertext.size,
                outPkId.ptr, outSpkId.ptr
            )
        }
        if (result < 0) throw SignalNativeException("preKeyMessageGetIds failed: $result", result)
        Pair(outPkId.value, outSpkId.value)
    }

    // =========================================================================
    // Record Utilities
    // =========================================================================

    actual fun preKeyRecordGetPublicKey(record: ByteArray): ByteArray {
        val buffer = ByteArray(64)
        val written = record.usePinned { recPinned ->
            buffer.usePinned { outPinned ->
                trick_prekey_record_get_public_key(
                    recPinned.addressOf(0).reinterpret(), record.size,
                    outPinned.addressOf(0).reinterpret(), 64
                )
            }
        }
        if (written < 0) throw SignalNativeException("preKeyRecordGetPublicKey failed: $written", written)
        return buffer.copyOf(written)
    }

    actual fun signedPreKeyRecordGetPublicKey(record: ByteArray): Pair<ByteArray, ByteArray> = memScoped {
        val pubKey = ByteArray(64)
        val signature = ByteArray(128)
        val outPubLen = alloc<IntVar>()
        val outSigLen = alloc<IntVar>()
        val result = record.usePinned { recPinned ->
            pubKey.usePinned { pkPinned ->
                signature.usePinned { sigPinned ->
                    trick_signed_prekey_record_get_public_key(
                        recPinned.addressOf(0).reinterpret(), record.size,
                        pkPinned.addressOf(0).reinterpret(), 64, outPubLen.ptr,
                        sigPinned.addressOf(0).reinterpret(), 128, outSigLen.ptr
                    )
                }
            }
        }
        if (result < 0) throw SignalNativeException("signedPreKeyRecordGetPublicKey failed: $result", result)
        Pair(pubKey.copyOf(outPubLen.value), signature.copyOf(outSigLen.value))
    }

    actual fun kyberPreKeyRecordGetPublicKey(record: ByteArray): Pair<ByteArray, ByteArray> = memScoped {
        val pubKey = ByteArray(2048)
        val signature = ByteArray(128)
        val outPubLen = alloc<IntVar>()
        val outSigLen = alloc<IntVar>()
        val result = record.usePinned { recPinned ->
            pubKey.usePinned { pkPinned ->
                signature.usePinned { sigPinned ->
                    trick_kyber_prekey_record_get_public_key(
                        recPinned.addressOf(0).reinterpret(), record.size,
                        pkPinned.addressOf(0).reinterpret(), 2048, outPubLen.ptr,
                        sigPinned.addressOf(0).reinterpret(), 128, outSigLen.ptr
                    )
                }
            }
        }
        if (result < 0) throw SignalNativeException("kyberPreKeyRecordGetPublicKey failed: $result", result)
        Pair(pubKey.copyOf(outPubLen.value), signature.copyOf(outSigLen.value))
    }

    // =========================================================================
    // EC Operations
    // =========================================================================

    actual fun privateKeySign(privateKey: ByteArray, data: ByteArray): ByteArray {
        val buffer = ByteArray(128)
        val written = privateKey.usePinned { keyPinned ->
            data.usePinned { dataPinned ->
                buffer.usePinned { outPinned ->
                    trick_private_key_sign(
                        keyPinned.addressOf(0).reinterpret(), privateKey.size,
                        dataPinned.addressOf(0).reinterpret(), data.size,
                        outPinned.addressOf(0).reinterpret(), 128
                    )
                }
            }
        }
        if (written < 0) throw SignalNativeException("privateKeySign failed: $written", written)
        return buffer.copyOf(written)
    }

    actual fun publicKeyVerify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        val result = publicKey.usePinned { pkPinned ->
            data.usePinned { dataPinned ->
                signature.usePinned { sigPinned ->
                    trick_public_key_verify(
                        pkPinned.addressOf(0).reinterpret(), publicKey.size,
                        dataPinned.addressOf(0).reinterpret(), data.size,
                        sigPinned.addressOf(0).reinterpret(), signature.size
                    )
                }
            }
        }
        if (result < 0) throw SignalNativeException("publicKeyVerify failed: $result", result)
        return result == 1
    }
}
