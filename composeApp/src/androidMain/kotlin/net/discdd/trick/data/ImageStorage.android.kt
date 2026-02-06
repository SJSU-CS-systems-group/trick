package net.discdd.trick.data

import android.content.Context
import java.io.File

actual class ImageStorage(private val androidContext: Context) {
    private val imageDir: File
        get() = File(androidContext.filesDir, "trick_images").also { it.mkdirs() }

    actual fun saveImage(data: ByteArray, filename: String): String {
        val file = File(imageDir, filename)
        file.writeBytes(data)
        return file.absolutePath
    }

    actual fun loadImage(path: String): ByteArray? {
        val file = File(path)
        return if (file.exists()) file.readBytes() else null
    }
    
    actual companion object {
        actual fun create(context: Any): ImageStorage {
            return ImageStorage(context as Context)
        }
    }
}
