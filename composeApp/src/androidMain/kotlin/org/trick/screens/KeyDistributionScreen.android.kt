package org.trick.screens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android implementation of QR code view using ZXing library.
 *
 * Generates a QR code bitmap from the payload string and displays it.
 */
@Composable
actual fun QRCodeView(payload: String) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(payload) {
        scope.launch {
            // ZXing throws IllegalArgumentException on empty contents, so skip generation
            qrBitmap = if (payload.isBlank()) {
                null
            } else {
                // Use larger size for dense QR codes (Signal bundles with Kyber keys)
                generateQRCode(payload, 1024, 1024)
            }
        }
    }

    qrBitmap?.let { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR Code for key distribution",
            modifier = Modifier.fillMaxSize()
        )
    } ?: run {
        Box(modifier = Modifier.fillMaxSize())
    }
}

/**
 * Generate a QR code bitmap from a string.
 *
 * @param content The string to encode in the QR code
 * @param width Width of the QR code in pixels
 * @param height Height of the QR code in pixels
 * @return Bitmap containing the QR code
 */
private suspend fun generateQRCode(content: String, width: Int, height: Int): Bitmap = withContext(Dispatchers.Default) {
    // Extra safety: never pass empty/blank content to ZXing
    if (content.isBlank()) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmap.eraseColor(Color.WHITE)
        return@withContext bitmap
    }

    val hints = hashMapOf<EncodeHintType, Any>().apply {
        // Use L (Low) error correction to allow denser payloads (Kyber + Signal bundle)
        // while still remaining robust at 1024x1024 resolution.
        put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L)
        put(EncodeHintType.MARGIN, 1)
        put(EncodeHintType.CHARACTER_SET, "UTF-8")
    }

    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }

    bitmap
}
