package org.trick.signal

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android implementation using Android KeyStore + AES-GCM.
 * Extracted from the old SignalSessionManager.android.kt.
 */
actual class SecureKeyStorage actual constructor() {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val MASTER_KEY_ALIAS = "signal_identity_master_key"
    }

    actual fun encryptPrivateKey(data: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())
        val encrypted = cipher.doFinal(data)
        return Pair(encrypted, cipher.iv)
    }

    actual fun decryptPrivateKey(encrypted: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getMasterKey(): SecretKey {
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
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
}
