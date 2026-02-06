package net.discdd.trick.data

import android.content.Context
import java.io.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

actual class ImageStorage() : KoinComponent {
    private val context: Context by inject()
    private val imageDir: File
        get() = File(context.filesDir, "trick_images").also { it.mkdirs() }

    actual fun saveImage(data: ByteArray, filename: String): String {
        val file = File(imageDir, filename)
        file.writeBytes(data)
        return file.absolutePath
    }

    actual fun loadImage(path: String): ByteArray? {
        val file = File(path)
        return if (file.exists()) file.readBytes() else null
    }
}
