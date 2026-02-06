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
                                // scanBarcodes is responsible for closing imageProxy
                                scanBarcodes(imageProxy) { qrCode ->
                                    hasScanned = true
                                    onQRCodeScanned(qrCode)
                                }
                            } else {
                                // Nothing to do with this frame anymore
                                imageProxy.close()
                            }
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

/**
 * Multi-QR Scanner screen that supports scanning multiple QR codes in sequence.
 * Shows progress and continues scanning until all parts are received.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun MultiQRScannerScreen(
    scannedParts: Int,
    totalParts: Int,
    alreadyScannedChunkIds: Set<Int> = emptySet(),
    onQRCodeScanned: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan QR Codes") },
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
                    MultiCameraPreviewWithScanner(
                        scannedParts = scannedParts,
                        totalParts = totalParts,
                        alreadyScannedChunkIds = alreadyScannedChunkIds,
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
private fun MultiCameraPreviewWithScanner(
    scannedParts: Int,
    totalParts: Int,
    alreadyScannedChunkIds: Set<Int> = emptySet(),
    onQRCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // Track which chunks we've already scanned to avoid duplicates
    // Initialize from parent state and keep synchronized
    // Parent state is the source of truth - if parent resets, we reset too
    var scannedChunkIds by remember { mutableStateOf(alreadyScannedChunkIds) }
    
    // Synchronize with parent state when it changes
    // If parent state is smaller (reset case), use parent as source of truth
    // Otherwise merge to preserve local updates that haven't been confirmed yet
    LaunchedEffect(alreadyScannedChunkIds) {
        scannedChunkIds = if (alreadyScannedChunkIds.size < scannedChunkIds.size) {
            // Parent reset - use parent as source of truth
            alreadyScannedChunkIds
        } else {
            // Parent added chunks - merge to preserve any local updates
            scannedChunkIds union alreadyScannedChunkIds
        }
    }

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
                            // Continue scanning until we have all parts
                            scanBarcodesMulti(imageProxy, scannedChunkIds) { qrCode, chunkId ->
                                scannedChunkIds = scannedChunkIds + chunkId
                                onQRCodeScanned(qrCode)
                            }
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

    // Scanning overlay with progress
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.size(280.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Position QR code here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Progress indicator
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (totalParts > 0)
                            "Scanned $scannedParts of $totalParts"
                        else
                            "Scan first QR code...",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (totalParts > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { scannedParts.toFloat() / totalParts.toFloat() },
                            modifier = Modifier.width(200.dp),
                        )
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun scanBarcodesMulti(
    imageProxy: ImageProxy,
    alreadyScanned: Set<Int>,
    onQRCodeDetected: (String, Int) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    val scanner = BarcodeScanning.getClient()
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            for (barcode in barcodes) {
                if (barcode.format == Barcode.FORMAT_QR_CODE) {
                    val bytes = barcode.rawBytes
                    if (bytes != null && bytes.size >= 2) {
                        // Extract chunk ID from header to check if already scanned
                        val chunkId = bytes[0].toInt() and 0xFF
                        if (chunkId !in alreadyScanned) {
                            val qrCode = String(bytes, Charsets.ISO_8859_1)
                            Log.d("QRScanner", "QR Code chunk $chunkId detected: ${bytes.size} bytes")
                            onQRCodeDetected(qrCode, chunkId)
                        }
                    }
                }
            }
        }
        .addOnFailureListener { e ->
            Log.e("QRScanner", "Barcode scanning failed", e)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun scanBarcodes(
    imageProxy: ImageProxy,
    onQRCodeDetected: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    val scanner = BarcodeScanning.getClient()
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            for (barcode in barcodes) {
                if (barcode.format == Barcode.FORMAT_QR_CODE) {
                    // Use rawBytes for binary data (protobuf), convert to ISO-8859-1 string
                    val bytes = barcode.rawBytes
                    if (bytes != null) {
                        val qrCode = String(bytes, Charsets.ISO_8859_1)
                        Log.d("QRScanner", "QR Code detected: ${bytes.size} bytes")
                        onQRCodeDetected(qrCode)
                    }
                }
            }
        }
        .addOnFailureListener { e ->
            Log.e("QRScanner", "Barcode scanning failed", e)
        }
        .addOnCompleteListener {
            // Always close the image once ML Kit is done with it
            imageProxy.close()
        }
}
