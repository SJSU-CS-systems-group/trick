package net.discdd.trick.signal

/**
 * Legacy JNI entry point shim.
 * The compiled .so exports symbols under the old package name
 * (Java_org_trcky_trick_signal_SignalNativeBridge_*).
 * This object matches those symbols so the library works without a rebuild.
 *
 * Once the Rust library is rebuilt against org.trick, delete this file
 * and remove the delegation calls in SignalNativeBridge.android.kt.
 */
internal object SignalNativeBridge {
    @JvmStatic external fun nativeGenerateIdentityKeyPair(outPublic: ByteArray, outPrivate: ByteArray): Int
    @JvmStatic external fun nativeGenerateRegistrationId(): Int
    @JvmStatic external fun nativeGeneratePreKeyRecord(id: Int, outRecord: ByteArray): Int
    @JvmStatic external fun nativeGenerateSignedPreKeyRecord(id: Int, timestamp: Long, identityPrivateKey: ByteArray, outRecord: ByteArray): Int
    @JvmStatic external fun nativeGenerateKyberPreKeyRecord(id: Int, timestamp: Long, identityPrivateKey: ByteArray, outRecord: ByteArray): Int

    @JvmStatic external fun nativeProcessPreKeyBundle(
        identityPublic: ByteArray, identityPrivate: ByteArray, registrationId: Int,
        addressName: String, deviceId: Int,
        existingPeerIdentity: ByteArray?, existingSession: ByteArray?,
        bundleRegistrationId: Int, bundleDeviceId: Int,
        bundlePreKeyId: Int, bundlePreKeyPublic: ByteArray?,
        bundleSignedPreKeyId: Int, bundleSignedPreKeyPublic: ByteArray,
        bundleSignedPreKeySig: ByteArray, bundleIdentityKey: ByteArray,
        bundleKyberPreKeyId: Int, bundleKyberPreKeyPublic: ByteArray,
        bundleKyberPreKeySig: ByteArray,
        outSession: ByteArray, outIdentityChanged: IntArray
    ): Int

    @JvmStatic external fun nativeEncryptMessage(
        identityPublic: ByteArray, identityPrivate: ByteArray, registrationId: Int,
        addressName: String, deviceId: Int,
        sessionRecord: ByteArray, peerIdentity: ByteArray, plaintext: ByteArray,
        outCiphertext: ByteArray, outUpdatedSession: ByteArray, outMeta: IntArray
    ): Int

    @JvmStatic external fun nativeDecryptMessage(
        identityPublic: ByteArray, identityPrivate: ByteArray, registrationId: Int,
        addressName: String, deviceId: Int,
        sessionRecord: ByteArray, peerIdentity: ByteArray?,
        preKeyRecord: ByteArray?, signedPreKeyRecord: ByteArray?, kyberPreKeyRecord: ByteArray?,
        ciphertext: ByteArray, messageType: Int,
        outPlaintext: ByteArray, outUpdatedSession: ByteArray, outSenderIdentity: ByteArray, outMeta: IntArray
    ): Int

    @JvmStatic external fun nativeGetCiphertextMessageType(ciphertext: ByteArray): Int
    @JvmStatic external fun nativePreKeyMessageGetIds(ciphertext: ByteArray, outIds: IntArray): Int

    @JvmStatic external fun nativePreKeyRecordGetPublicKey(record: ByteArray, outPublicKey: ByteArray): Int
    @JvmStatic external fun nativeSignedPreKeyRecordGetPublicKey(record: ByteArray, outPublicKey: ByteArray, outSignature: ByteArray): Int
    @JvmStatic external fun nativeKyberPreKeyRecordGetPublicKey(record: ByteArray, outPublicKey: ByteArray, outSignature: ByteArray, outMeta: IntArray): Int

    @JvmStatic external fun nativePrivateKeySign(privateKey: ByteArray, data: ByteArray, outSignature: ByteArray): Int
    @JvmStatic external fun nativePublicKeyVerify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Int
}