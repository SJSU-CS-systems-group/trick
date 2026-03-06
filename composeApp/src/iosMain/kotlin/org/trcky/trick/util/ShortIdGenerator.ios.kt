package org.trcky.trick.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(data: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    data.usePinned { dataPinned ->
        digest.usePinned { digestPinned ->
            CC_SHA256(
                dataPinned.addressOf(0),
                data.size.convert(),
                digestPinned.addressOf(0).reinterpret()
            )
        }
    }
    return digest
}
