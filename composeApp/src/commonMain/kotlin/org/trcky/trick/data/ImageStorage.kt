package org.trcky.trick.data

/**
 * Platform-specific image storage.
 * Bridges between ByteArray (used by UI) and file paths (stored in DB).
 */
expect class ImageStorage {
    fun saveImage(data: ByteArray, filename: String): String
    fun loadImage(path: String): ByteArray?
}
