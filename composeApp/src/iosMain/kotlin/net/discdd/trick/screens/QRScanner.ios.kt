@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package net.discdd.trick.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.*
import platform.CoreGraphics.CGRectMake
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import kotlin.io.encoding.Base64

/**
 * iOS Multi-QR Scanner screen using AVFoundation.
 * Supports scanning multiple QR codes in sequence with progress indicator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IOSMultiQRScannerScreen(
    scannedParts: Int,
    totalParts: Int,
    alreadyScannedChunkIds: Set<Int>,
    onQRCodeScanned: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var cameraPermissionGranted by remember { mutableStateOf(false) }
    var cameraPermissionDenied by remember { mutableStateOf(false) }

    // Check camera permission on launch
    LaunchedEffect(Unit) {
        val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
        when (status) {
            AVAuthorizationStatusAuthorized -> {
                cameraPermissionGranted = true
            }
            AVAuthorizationStatusNotDetermined -> {
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    dispatch_async(dispatch_get_main_queue()) {
                        cameraPermissionGranted = granted
                        cameraPermissionDenied = !granted
                    }
                }
            }
            else -> {
                cameraPermissionDenied = true
            }
        }
    }

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
                cameraPermissionGranted -> {
                    IOSCameraPreview(
                        alreadyScannedChunkIds = alreadyScannedChunkIds,
                        onQRCodeScanned = onQRCodeScanned
                    )

                    // Progress overlay at bottom
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Card(
                            modifier = Modifier.padding(16.dp),
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
                cameraPermissionDenied -> {
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
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Please enable camera access in Settings",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

/**
 * UIKitView wrapping AVCaptureSession for camera preview + QR detection.
 */
@OptIn(BetaInteropApi::class)
@Composable
private fun IOSCameraPreview(
    alreadyScannedChunkIds: Set<Int>,
    onQRCodeScanned: (String) -> Unit
) {
    // Track scanned chunk IDs locally to avoid duplicate callbacks
    var localScannedIds by remember { mutableStateOf(alreadyScannedChunkIds) }

    // Keep in sync with parent
    LaunchedEffect(alreadyScannedChunkIds) {
        localScannedIds = alreadyScannedChunkIds
    }

    val captureSession = remember { AVCaptureSession() }

    // Hold strong reference to delegate so it doesn't get garbage collected
    val delegate = remember {
        QRCodeDelegate { qrString ->
            try {
                val bytes = Base64.Default.decode(qrString)
                if (bytes.size >= 2) {
                    val chunkId = bytes[0].toInt() and 0xFF
                    if (chunkId !in localScannedIds) {
                        localScannedIds = localScannedIds + chunkId
                        onQRCodeScanned(qrString)
                    }
                }
            } catch (_: Exception) {
                // Not a valid base64 QR code, ignore
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (captureSession.isRunning()) {
                captureSession.stopRunning()
            }
        }
    }

    UIKitView(
        factory = {
            val containerView = UIView(frame = CGRectMake(0.0, 0.0, 400.0, 600.0))

            // Set up capture device
            val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            if (device == null) {
                println("QRScanner: No camera device available")
                return@UIKitView containerView
            }

            val input = AVCaptureDeviceInput.deviceInputWithDevice(device, error = null)
                as? AVCaptureDeviceInput

            if (input == null) {
                println("QRScanner: Camera input is null")
                return@UIKitView containerView
            }

            if (captureSession.canAddInput(input)) {
                captureSession.addInput(input)
            }

            // Set up metadata output for QR detection
            val metadataOutput = AVCaptureMetadataOutput()

            if (captureSession.canAddOutput(metadataOutput)) {
                captureSession.addOutput(metadataOutput)
                metadataOutput.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
                metadataOutput.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
            }

            // Set up preview layer
            val previewLayer = AVCaptureVideoPreviewLayer(session = captureSession)
            previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
            previewLayer.frame = containerView.bounds
            containerView.layer.addSublayer(previewLayer)

            // Start session on background queue (not main thread)
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                captureSession.startRunning()
            }

            containerView
        },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            // Update preview layer frame when view size changes
            val sublayers = view.layer.sublayers
            if (sublayers != null) {
                for (layer in sublayers) {
                    if (layer is AVCaptureVideoPreviewLayer) {
                        CATransaction.begin()
                        CATransaction.setValue(true, kCATransactionDisableActions)
                        layer.frame = view.bounds
                        CATransaction.commit()
                    }
                }
            }
        }
    )
}

/**
 * AVCaptureMetadataOutput delegate that detects QR codes.
 * Extracts string value from QR codes containing ISO-8859-1 encoded binary data.
 */
private class QRCodeDelegate(
    private val onDetected: (String) -> Unit
) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection
    ) {
        for (metadataObject in didOutputMetadataObjects) {
            val readableObject = metadataObject as? AVMetadataMachineReadableCodeObject ?: continue
            if (readableObject.type != AVMetadataObjectTypeQRCode) continue

            val stringValue = readableObject.stringValue ?: continue
            if (stringValue.isNotEmpty()) {
                onDetected(stringValue)
            }
        }
    }
}
