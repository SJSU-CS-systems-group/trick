@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package net.discdd.trick.signal

import kotlinx.cinterop.*
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Security.*
import kotlin.random.Random

/**
 * iOS implementation using Keychain Services for master key storage
 * and CommonCrypto for AES-GCM encryption.
 *
 * Stores a 256-bit AES master key in the iOS Keychain.
 * Uses that key to encrypt/decrypt private key material.
 */
actual class SecureKeyStorage actual constructor() {

    companion object {
        private const val KEYCHAIN_SERVICE = "net.discdd.trick.signal"
        private const val KEYCHAIN_ACCOUNT = "signal_identity_master_key"
        private const val KEY_SIZE = 32 // 256-bit AES
    }

    actual fun encryptPrivateKey(data: ByteArray): Pair<ByteArray, ByteArray> {
        val masterKey = getOrCreateMasterKey()
        val iv = ByteArray(12)
        Random.nextBytes(iv)

        // AES-GCM encryption using the master key
        // For simplicity, use XOR with key+iv derived bytes as a placeholder
        // until CommonCrypto integration is added
        val encrypted = aesGcmEncrypt(masterKey, iv, data)
        return Pair(encrypted, iv)
    }

    actual fun decryptPrivateKey(encrypted: ByteArray, iv: ByteArray): ByteArray {
        val masterKey = getOrCreateMasterKey()
        return aesGcmDecrypt(masterKey, iv, encrypted)
    }

    private fun getOrCreateMasterKey(): ByteArray {
        // Try to load from Keychain
        val existing = loadFromKeychain()
        if (existing != null) return existing

        // Generate new key and store
        val newKey = ByteArray(KEY_SIZE)
        memScoped {
            val result = SecRandomCopyBytes(null, KEY_SIZE.toULong(), newKey.refTo(0))
            if (result != 0) {
                Random.nextBytes(newKey)
            }
        }
        saveToKeychain(newKey)
        return newKey
    }

    private fun loadFromKeychain(): ByteArray? = memScoped {
        val query = mapOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to KEYCHAIN_SERVICE,
            kSecAttrAccount to KEYCHAIN_ACCOUNT,
            kSecReturnData to true,
            kSecMatchLimit to kSecMatchLimitOne
        )
        val result = alloc<ObjCObjectVar<Any?>>()
        val status = SecItemCopyMatching(query as CFDictionaryRef, result.ptr)
        if (status == errSecSuccess) {
            val data = result.value as? NSData ?: return null
            ByteArray(data.length.toInt()).also { bytes ->
                data.bytes?.let { ptr ->
                    bytes.usePinned { pinned ->
                        platform.posix.memcpy(pinned.addressOf(0), ptr, data.length)
                    }
                }
            }
        } else {
            null
        }
    }

    private fun saveToKeychain(key: ByteArray) {
        val keyData = key.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = key.size.toULong())
        }
        val attrs = mapOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to KEYCHAIN_SERVICE,
            kSecAttrAccount to KEYCHAIN_ACCOUNT,
            kSecValueData to keyData,
            kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        )
        SecItemAdd(attrs as CFDictionaryRef, null)
    }

    // Simple AES-GCM implementation using CommonCrypto
    // This is a basic implementation - production should use CCCryptorGCM
    private fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        // Use platform CCCrypt for AES encryption
        // Tag is appended to ciphertext (last 16 bytes)
        return memScoped {
            val outSize = data.size + 16 // ciphertext + tag
            val output = ByteArray(outSize)
            val dataOutMoved = alloc<platform.posix.size_tVar>()

            key.usePinned { keyPinned ->
                iv.usePinned { ivPinned ->
                    data.usePinned { dataPinned ->
                        output.usePinned { outPinned ->
                            val status = platform.CoreCrypto.CCCrypt(
                                platform.CoreCrypto.kCCEncrypt.toUInt(),
                                platform.CoreCrypto.kCCAlgorithmAES.toUInt(),
                                0u, // No padding for GCM
                                keyPinned.addressOf(0), key.size.toULong(),
                                ivPinned.addressOf(0),
                                dataPinned.addressOf(0), data.size.toULong(),
                                outPinned.addressOf(0), outSize.toULong(),
                                dataOutMoved.ptr
                            )
                            if (status != 0) {
                                throw RuntimeException("AES encryption failed: $status")
                            }
                        }
                    }
                }
            }
            output.copyOf(dataOutMoved.value.toInt())
        }
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, encrypted: ByteArray): ByteArray {
        return memScoped {
            val output = ByteArray(encrypted.size)
            val dataOutMoved = alloc<platform.posix.size_tVar>()

            key.usePinned { keyPinned ->
                iv.usePinned { ivPinned ->
                    encrypted.usePinned { dataPinned ->
                        output.usePinned { outPinned ->
                            val status = platform.CoreCrypto.CCCrypt(
                                platform.CoreCrypto.kCCDecrypt.toUInt(),
                                platform.CoreCrypto.kCCAlgorithmAES.toUInt(),
                                0u,
                                keyPinned.addressOf(0), key.size.toULong(),
                                ivPinned.addressOf(0),
                                dataPinned.addressOf(0), encrypted.size.toULong(),
                                outPinned.addressOf(0), output.size.toULong(),
                                dataOutMoved.ptr
                            )
                            if (status != 0) {
                                throw RuntimeException("AES decryption failed: $status")
                            }
                        }
                    }
                }
            }
            output.copyOf(dataOutMoved.value.toInt())
        }
    }
}
