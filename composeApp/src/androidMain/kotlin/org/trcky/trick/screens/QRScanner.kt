@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package org.trcky.trick.screens

import android.Manifest
import android.util.Log
import android.util.Size
import org.trcky.trick.BuildConfig
import android.view.MotionEvent
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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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

    // Hold references to camera and preview for focus control
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Periodically re-trigger center autofocus to help cameras that struggle at close range
    // (e.g. Pixel 6's large sensor has trouble locking focus on near objects).
    // This nudges the AF system every 1.5s so it keeps trying to find the QR code.
    LaunchedEffect(cameraRef, previewViewRef) {
        val cam = cameraRef ?: return@LaunchedEffect
        val pv = previewViewRef ?: return@LaunchedEffect
        while (isActive) {
            delay(1500)
            try {
                val factory = pv.meteringPointFactory
                val center = factory.createPoint(0.5f, 0.5f)
                val action = FocusMeteringAction.Builder(center, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(1, TimeUnit.SECONDS)
                    .build()
                cam.cameraControl.startFocusAndMetering(action)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.d("QRScanner", "Periodic AF retrigger failed: ${e.message}")
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            previewViewRef = previewView
            val executor = ContextCompat.getMainExecutor(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                // Preview
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Image analysis for QR scanning
                // Use 1280x720 target resolution — plenty for QR codes,
                // avoids wasting cycles on high-res frames from large sensors (e.g. Pixel 6 50MP)
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(1280, 720))
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
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )
                    cameraRef = camera

                    // Enable tap-to-focus: user can tap the preview to force AF at that point.
                    // Critical for Pixel 6 and other large-sensor devices that struggle with
                    // close-range autofocus — gives the user manual control to lock focus.
                    setupTapToFocus(previewView, camera)
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (hasScanned) "✓ Scanned!" else "Position QR code here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!hasScanned) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap to focus if blurry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}


/**
 * Sets up tap-to-focus on the PreviewView.
 *
 * When the user taps anywhere on the camera preview, this triggers autofocus (AF) and
 * auto-exposure (AE) metering at the tapped point. This is especially important for
 * devices with large camera sensors (like the Pixel 6) that struggle to autofocus on
 * close-range objects like QR codes — tapping gives the user direct control to force
 * the camera to re-evaluate focus at the correct distance.
 */
@Suppress("ClickableViewAccessibility")
private fun setupTapToFocus(previewView: PreviewView, camera: Camera) {
    previewView.setOnTouchListener { _, event ->
        if (event.action == MotionEvent.ACTION_DOWN) {
            try {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                camera.cameraControl.startFocusAndMetering(action)
                if (BuildConfig.DEBUG) Log.d("QRScanner", "Tap-to-focus triggered at (${event.x}, ${event.y})")
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.d("QRScanner", "Tap-to-focus failed: ${e.message}")
            }
            true
        } else {
            false
        }
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
                    val qrCode = barcode.rawValue
                    if (qrCode != null) {
                        if (BuildConfig.DEBUG) Log.d("QRScanner", "QR Code detected: ${qrCode.length} chars")
                        onQRCodeDetected(qrCode)
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
