package org.trick.screens.messaging

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

@Composable
actual fun rememberImageBitmap(imageData: ByteArray): ImageBitmap? {
    return remember(imageData) {
        try {
            Image.makeFromEncoded(imageData).toComposeImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}
