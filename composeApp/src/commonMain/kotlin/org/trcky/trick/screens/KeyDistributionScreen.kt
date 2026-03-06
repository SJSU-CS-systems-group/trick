package org.trcky.trick.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * KeyDistributionScreen provides UI for QR code key distribution.
 *
 * Features:
 * - Display a single QR code containing the device's hash commitment
 * - Scan QR code from peer device (platform-specific)
 * - List of trusted peers with option to untrust
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyDistributionScreen(
    deviceId: String,
    qrCodePayloads: List<String>,
    displayUrl: String,
    isLoading: Boolean = false,
    onCopyUrl: (String) -> Unit,
    onShareUrl: (String) -> Unit,
    trustedPeers: List<String>,
    onNavigateBack: () -> Unit,
    onScanQR: () -> Unit,
    onUntrust: (String) -> Unit,
    onWifiAwarePairing: (() -> Unit)? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Key Distribution") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
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

                    // QR Code display
                    if (isLoading || qrCodePayloads.isEmpty()) {
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
                                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Generating QR code...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(300.dp)
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            QRCodeView(payload = qrCodePayloads[0])
                        }
                        Text(
                            text = "Show this QR code to your peer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            if (onWifiAwarePairing != null) {
                Button(
                    onClick = onWifiAwarePairing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Text("WiFi Aware Pairing")
                }
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
                        trustedPeers.forEach { peerId ->
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
                text = "Keys distributed",
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
