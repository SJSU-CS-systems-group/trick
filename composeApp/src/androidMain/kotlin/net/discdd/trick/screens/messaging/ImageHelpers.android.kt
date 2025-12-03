package net.discdd.trick.screens.messaging

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

@Composable
actual fun rememberImageBitmap(imageData: ByteArray): ImageBitmap? {
    return remember(imageData) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            bitmap?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}

