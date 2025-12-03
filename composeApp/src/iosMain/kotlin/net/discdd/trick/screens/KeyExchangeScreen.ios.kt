package net.discdd.trick.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * iOS implementation of QR code view.
 * TODO: Implement using CoreImage QR code generator.
 */
@Composable
actual fun QRCodeView(payload: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("QR Code (iOS implementation pending)")
    }
}
