@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package net.discdd.trick.signal

import kotlinx.cinterop.*
import net.discdd.trick.libsignal.bridge.*

/**
 * iOS implementation of SignalNativeBridge using cinterop with the
 * Rust trick_signal_ffi C FFI functions.
 */
actual object SignalNativeBridge {

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
                                        // Handle nullable arrays
                                        val existPeerPtr = existingPeerIdentity?.usePinned { it.addressOf(0).reinterpret() }
                                        val existPeerLen = existingPeerIdentity?.size ?: 0
                                        val existSessPtr = existingSession?.usePinned { it.addressOf(0).reinterpret() }
                                        val existSessLen = existingSession?.size ?: 0
                                        val bPkPtr = bundle.preKeyPublic?.usePinned { it.addressOf(0).reinterpret() }
                                        val bPkLen = bundle.preKeyPublic?.size ?: 0

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
<<<<<<< HEAD
                // Handle empty sessionRecord safely
                sessionRecord.usePinnedSafe { sessPtr, sessLen ->
=======
                sessionRecord.usePinned { sess ->
>>>>>>> e13ac59 (Full migration to Rust Libsignal via FFI for Android and iOS code unification)
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
                                        sess.addressOf(0).reinterpret(), sessionRecord.size,
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
                sessionRecord.usePinned { sess ->
                    ciphertext.usePinned { ct ->
                        outPlaintext.usePinned { pt ->
                            outUpdatedSession.usePinned { updSess ->
                                outSenderIdentity.usePinned { senderId ->
                                    val peerPtr = peerIdentity?.usePinned { it.addressOf(0).reinterpret() }
                                    val peerLen = peerIdentity?.size ?: 0
                                    val pkPtr = preKeyRecord?.usePinned { it.addressOf(0).reinterpret() }
                                    val pkLen = preKeyRecord?.size ?: 0
                                    val spkPtr = signedPreKeyRecord?.usePinned { it.addressOf(0).reinterpret() }
                                    val spkLen = signedPreKeyRecord?.size ?: 0
                                    val kpkPtr = kyberPreKeyRecord?.usePinned { it.addressOf(0).reinterpret() }
                                    val kpkLen = kyberPreKeyRecord?.size ?: 0

                                    trick_decrypt_message(
                                        idPub.addressOf(0).reinterpret(), identityPublic.size,
                                        idPriv.addressOf(0).reinterpret(), identityPrivate.size,
                                        registrationId,
                                        addressName.cstr.ptr,
                                        deviceId,
                                        sess.addressOf(0).reinterpret(), sessionRecord.size,
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
