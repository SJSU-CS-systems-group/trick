package net.discdd.trick.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.filterWithName
import platform.CoreImage.kCIFormatRGBA8
import platform.Foundation.NSISOLatin1StringEncoding
import platform.Foundation.NSString
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setValue

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun QRCodeView(payload: String) {
    val imageBitmap = remember(payload) {
        if (payload.isBlank()) return@remember null
        generateQRCodeBitmap(payload)
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = "QR Code for key exchange",
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun generateQRCodeBitmap(payload: String): ImageBitmap? {
    // Convert payload to NSData using ISO-8859-1 to match Android's byte encoding
    @Suppress("CAST_NEVER_SUCCEEDS")
    val nsString = payload as NSString
    val data = nsString.dataUsingEncoding(NSISOLatin1StringEncoding) ?: return null

    // Create QR code using CoreImage
    val filter = CIFilter.filterWithName("CIQRCodeGenerator") ?: return null
    filter.setValue(data, forKey = "inputMessage")
    filter.setValue("L", forKey = "inputCorrectionLevel")

    // Get output image - CIFilter has an outputImage property
    val outputImage = filter.outputImage as? platform.CoreImage.CIImage ?: return null

    // The raw CIImage is tiny (e.g. 23x23 pixels). Render at small size then scale up in Kotlin.
    val ciContext = CIContext()
    val extent = outputImage.extent
    val smallWidth = extent.useContents { size.width }.toInt()
    val smallHeight = extent.useContents { size.height }.toInt()
    if (smallWidth <= 0 || smallHeight <= 0) return null

    // Render CIImage to an RGBA byte buffer
    val bytesPerRow = smallWidth * 4
    val smallRgba = ByteArray(bytesPerRow * smallHeight)
    val colorSpace = CGColorSpaceCreateDeviceRGB()

    smallRgba.usePinned { pinned ->
        ciContext.render(
            outputImage,
            toBitmap = pinned.addressOf(0),
            rowBytes = bytesPerRow.toLong(),
            bounds = extent,
            format = kCIFormatRGBA8,
            colorSpace = colorSpace
        )
    }

    // Nearest-neighbor scale to 1024x1024 for sharp QR pixels
    val targetSize = 1024
    val rgbaBytes = ByteArray(targetSize * targetSize * 4)
    for (y in 0 until targetSize) {
        val srcY = y * smallHeight / targetSize
        for (x in 0 until targetSize) {
            val srcX = x * smallWidth / targetSize
            val srcIdx = (srcY * smallWidth + srcX) * 4
            val dstIdx = (y * targetSize + x) * 4
            rgbaBytes[dstIdx] = smallRgba[srcIdx]
            rgbaBytes[dstIdx + 1] = smallRgba[srcIdx + 1]
            rgbaBytes[dstIdx + 2] = smallRgba[srcIdx + 2]
            rgbaBytes[dstIdx + 3] = smallRgba[srcIdx + 3]
        }
    }
    val width = targetSize
    val height = targetSize

    // Create Skia Image from RGBA bytes
    val imageInfo = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
    val skiaImage = org.jetbrains.skia.Image.makeRaster(imageInfo, rgbaBytes, width * 4)
    return skiaImage.toComposeImageBitmap()
}
