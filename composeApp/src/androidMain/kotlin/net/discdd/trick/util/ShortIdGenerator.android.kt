package net.discdd.trick.util

import java.security.MessageDigest

internal actual fun sha256(data: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(data)
}
