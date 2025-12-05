package net.discdd.trick.screens

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
            qrBitmap = generateQRCode(payload, 512, 512)
        }
    }

    qrBitmap?.let { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR Code for key exchange",
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
    val hints = hashMapOf<EncodeHintType, Any>().apply {
        put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
        put(EncodeHintType.CHARACTER_SET, "UTF-8")
        put(EncodeHintType.MARGIN, 1)
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
