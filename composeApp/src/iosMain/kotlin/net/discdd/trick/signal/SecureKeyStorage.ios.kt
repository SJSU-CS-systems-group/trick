@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package net.discdd.trick.signal

import kotlinx.cinterop.*
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.*
import kotlin.random.Random

/**
 * iOS implementation using Keychain Services for master key storage
 * and CommonCrypto for AES-CBC encryption.
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
        // AES-CBC requires 16-byte IV (block size)
        val iv = ByteArray(16)
        Random.nextBytes(iv)
        val encrypted = aesEncrypt(masterKey, iv, data)
        return Pair(encrypted, iv)
    }

    actual fun decryptPrivateKey(encrypted: ByteArray, iv: ByteArray): ByteArray {
        val masterKey = getOrCreateMasterKey()
        return aesDecrypt(masterKey, iv, encrypted)
    }

    private fun getOrCreateMasterKey(): ByteArray {
        val existing = loadFromKeychain()
        if (existing != null) return existing

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

    /**
     * Load the master key from the iOS Keychain.
     * Uses CFDictionary directly to avoid Kotlin/CF type bridging issues.
     */
    private fun loadFromKeychain(): ByteArray? = memScoped {
        val query = CFDictionaryCreateMutable(
            null, 5,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr
        ) ?: return null

        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        val serviceRef = CFBridgingRetain(KEYCHAIN_SERVICE)
        val accountRef = CFBridgingRetain(KEYCHAIN_ACCOUNT)
        CFDictionaryAddValue(query, kSecAttrService, serviceRef)
        CFDictionaryAddValue(query, kSecAttrAccount, accountRef)
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)

        val resultPtr = alloc<COpaquePointerVar>()
        val status = SecItemCopyMatching(query, resultPtr.ptr)

        CFRelease(serviceRef)
        CFRelease(accountRef)
        CFRelease(query)

        if (status == errSecSuccess) {
            val data = CFBridgingRelease(resultPtr.value) as? NSData ?: return null
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

    /**
     * Save the master key to the iOS Keychain.
     * Uses CFDictionary directly to avoid Kotlin/CF type bridging issues.
     */
    private fun saveToKeychain(key: ByteArray) {
        val keyData: NSData = key.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = key.size.toULong())
        }

        val attrs = CFDictionaryCreateMutable(
            null, 5,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr
        ) ?: return

        val serviceRef = CFBridgingRetain(KEYCHAIN_SERVICE)
        val accountRef = CFBridgingRetain(KEYCHAIN_ACCOUNT)
        val dataRef = CFBridgingRetain(keyData)
        CFDictionaryAddValue(attrs, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(attrs, kSecAttrService, serviceRef)
        CFDictionaryAddValue(attrs, kSecAttrAccount, accountRef)
        CFDictionaryAddValue(attrs, kSecValueData, dataRef)
        CFDictionaryAddValue(attrs, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)

        SecItemAdd(attrs, null)

        CFRelease(serviceRef)
        CFRelease(accountRef)
        CFRelease(dataRef)
        CFRelease(attrs)
    }

    private fun aesEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        return memScoped {
            val outSize = data.size + 16
            val output = ByteArray(outSize)
            val dataOutMoved = alloc<platform.posix.size_tVar>()

            key.usePinned { keyPinned ->
                iv.usePinned { ivPinned ->
                    data.usePinned { dataPinned ->
                        output.usePinned { outPinned ->
                            val status = platform.CoreCrypto.CCCrypt(
                                platform.CoreCrypto.kCCEncrypt.toUInt(),
                                platform.CoreCrypto.kCCAlgorithmAES.toUInt(),
                                0u,
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

    private fun aesDecrypt(key: ByteArray, iv: ByteArray, encrypted: ByteArray): ByteArray {
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
