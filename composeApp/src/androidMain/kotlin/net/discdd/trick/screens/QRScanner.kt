package net.discdd.trick.screens

import android.Manifest
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * QR Code Scanner screen using CameraX and ML Kit
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun QRScannerScreen(
    onQRCodeScanned: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan QR Code") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                cameraPermissionState.status.isGranted -> {
                    CameraPreviewWithScanner(
                        onQRCodeScanned = onQRCodeScanned
                    )
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Camera permission is required to scan QR codes",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                            Text("Grant Camera Permission")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewWithScanner(
    onQRCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var hasScanned by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = ContextCompat.getMainExecutor(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                // Preview
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Image analysis for QR scanning
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                            if (!hasScanned) {
                                scanBarcodes(imageProxy) { qrCode ->
                                    hasScanned = true
                                    onQRCodeScanned(qrCode)
                                }
                            }
                            imageProxy.close()
                        }
                    }

                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )
                } catch (e: Exception) {
                    Log.e("QRScanner", "Camera binding failed", e)
                }
            }, executor)

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )

    // Scanning overlay
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.size(250.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (hasScanned) "✓ Scanned!" else "Position QR code here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun scanBarcodes(
    imageProxy: ImageProxy,
    onQRCodeDetected: (String) -> Unit
) {
    val mediaImage = imageProxy.image ?: return
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    val scanner = BarcodeScanning.getClient()
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            for (barcode in barcodes) {
                if (barcode.format == Barcode.FORMAT_QR_CODE) {
                    barcode.rawValue?.let { qrCode ->
                        Log.d("QRScanner", "QR Code detected: ${qrCode.take(50)}...")
                        onQRCodeDetected(qrCode)
                    }
                }
            }
        }
        .addOnFailureListener { e ->
            Log.e("QRScanner", "Barcode scanning failed", e)
        }
}
