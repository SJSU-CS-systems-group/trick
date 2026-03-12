package org.trick.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

actual class ImageStorage {
    @OptIn(ExperimentalForeignApi::class)
    private val imageDir: String
        get() {
            val paths = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory, NSUserDomainMask, true
            )
            val documentsDir = paths.first() as String
            val dir = "$documentsDir/trick_images"
            NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
            return dir
        }

    @OptIn(ExperimentalForeignApi::class)
    actual fun saveImage(data: ByteArray, filename: String): String {
        val path = "$imageDir/$filename"
        val nsData = data.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = data.size.toULong())
        }
        nsData.writeToFile(path, atomically = true)
        return path
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun loadImage(path: String): ByteArray? {
        val nsData = NSData.dataWithContentsOfFile(path) ?: return null
        val size = nsData.length.toInt()
        if (size == 0) return null
        val bytes = ByteArray(size)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), nsData.bytes, nsData.length)
        }
        return bytes
    }
}
