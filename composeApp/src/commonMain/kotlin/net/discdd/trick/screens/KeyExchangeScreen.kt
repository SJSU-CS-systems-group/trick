package net.discdd.trick.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * KeyExchangeScreen provides UI for QR code key exchange.
 *
 * Features:
 * - Display QR codes containing device's public key (may be split across multiple codes)
 * - Scan QR code from peer device (platform-specific)
 * - List of trusted peers with option to untrust
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyExchangeScreen(
    deviceId: String,
    qrCodePayloads: List<String>,
    displayUrl: String,
    isLoading: Boolean = false,
    onCopyUrl: (String) -> Unit,
    onShareUrl: (String) -> Unit,
    trustedPeers: List<String>,
    onNavigateBack: () -> Unit,
    onScanQR: () -> Unit,
    onUntrust: (String) -> Unit
) {
    var currentQrIndex by remember { mutableStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Key Exchange") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Section 1: Your QR Code
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Your Device",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = deviceId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // QR Code display with pagination for multiple codes
                    if (isLoading || qrCodePayloads.isEmpty()) {
                        // Loading indicator while QR codes are being generated
                        Box(
                            modifier = Modifier
                                .size(300.dp)
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Generating QR code...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        // QR code indicator (e.g., "1 of 2")
                        if (qrCodePayloads.size > 1) {
                            Text(
                                text = "QR Code ${currentQrIndex + 1} of ${qrCodePayloads.size}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        // QR code with navigation
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // QR Code
                                Box(
                                    modifier = Modifier
                                        .size(300.dp)
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    QRCodeView(payload = qrCodePayloads[currentQrIndex])
                                }

                                // Next button on the right side
                                if (qrCodePayloads.size > 1) {
                                    IconButton(
                                        onClick = {
                                            currentQrIndex = (currentQrIndex + 1) % qrCodePayloads.size
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.ArrowForward,
                                            contentDescription = "Next QR",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = if (qrCodePayloads.size > 1)
                                "Peer must scan ALL ${qrCodePayloads.size} QR codes"
                                else "Show this QR code to your peer",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (qrCodePayloads.size > 1)
                                MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    // trcky.org URL with Copy and Share (second option; hide when displayUrl is blank)
                    if (displayUrl.isNotBlank()) {
                        Text(
                            text = "Or share this link to add peer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        Text(
                            text = displayUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth(),
                            maxLines = 1
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            FilledTonalButton(
                                onClick = { onCopyUrl(displayUrl) },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text("Copy")
                            }
                            FilledTonalButton(onClick = { onShareUrl(displayUrl) }) {
                                Text("Share")
                            }
                        }
                    }
                }
            }

            // Section 2: Scan QR Code Button
            Button(
                onClick = onScanQR,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Scan Peer's QR Code")
            }

            // Section 3: Trusted Peers List
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Trusted Peers (${trustedPeers.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    if (trustedPeers.isEmpty()) {
                        Text(
                            text = "No trusted peers yet. Scan a QR code to add one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn {
                            items(trustedPeers) { peerId ->
                                TrustedPeerItem(
                                    peerId = peerId,
                                    onUntrust = { onUntrust(peerId) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrustedPeerItem(
    peerId: String,
    onUntrust: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peerId,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Keys exchanged",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onUntrust) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove trust",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
    HorizontalDivider()
}

/**
 * Platform-specific QR code view.
 * Expect declaration for platform-specific implementations.
 */
@Composable
expect fun QRCodeView(payload: String)
