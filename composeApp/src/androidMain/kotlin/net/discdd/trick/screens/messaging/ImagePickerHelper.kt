package net.discdd.trick.screens.messaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Helper class for picking and processing images
 */
object ImagePickerHelper {
    private const val TAG = "ImagePickerHelper"
    private const val MAX_IMAGE_DIMENSION = 1024
    private const val JPEG_QUALITY = 85

    /**
     * Process an image URI into compressed ByteArray
     */
    fun processImageUri(context: Context, uri: Uri): ImageResult? {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            
            // Decode the image
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            if (originalBitmap == null) {
                Log.e(TAG, "Failed to decode image")
                return null
            }

            Log.d(TAG, "Original image size: ${originalBitmap.width}x${originalBitmap.height}")

            // Resize if needed
            val resizedBitmap = resizeImage(originalBitmap)
            
            // Compress to JPEG
            val outputStream = ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            val imageBytes = outputStream.toByteArray()
            
            // Clean up
            if (resizedBitmap != originalBitmap) {
                resizedBitmap.recycle()
            }
            originalBitmap.recycle()

            // Get filename from URI
            val filename = getFilenameFromUri(context, uri) ?: "image.jpg"

            Log.d(TAG, "Processed image: $filename, ${imageBytes.size} bytes")

            return ImageResult(
                data = imageBytes,
                filename = filename,
                mimeType = "image/jpeg"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image: ${e.message}", e)
            return null
        }
    }

    /**
     * Resize image to fit within MAX_IMAGE_DIMENSION while maintaining aspect ratio
     */
    private fun resizeImage(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION) {
            return bitmap
        }

        val scale = MAX_IMAGE_DIMENSION.toFloat() / max(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        Log.d(TAG, "Resizing image from ${width}x${height} to ${newWidth}x${newHeight}")

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Extract filename from URI
     */
    private fun getFilenameFromUri(context: Context, uri: Uri): String? {
        var filename: String? = null
        
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                filename = cursor.getString(nameIndex)
            }
        }
        
        return filename ?: uri.lastPathSegment
    }
}

/**
 * Result of image processing
 */
data class ImageResult(
    val data: ByteArray,
    val filename: String,
    val mimeType: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImageResult

        if (!data.contentEquals(other.data)) return false
        if (filename != other.filename) return false
        if (mimeType != other.mimeType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

/**
 * Composable function to create an image picker launcher
 */
@Composable
fun rememberImagePickerLauncher(
    onImagePicked: (ImageResult) -> Unit
): ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?> {
    val context = LocalContext.current
    
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            val result = ImagePickerHelper.processImageUri(context, it)
            result?.let { imageResult ->
                onImagePicked(imageResult)
            }
        }
    }
}

