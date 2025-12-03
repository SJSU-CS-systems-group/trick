package net.discdd.trick

import androidx.compose.ui.window.ComposeUIViewController
import net.discdd.trick.screens.messaging.WifiAwareServiceImpl

fun MainViewController() = ComposeUIViewController { 
    val wifiAwareService = WifiAwareServiceImpl()
    // iOS doesn't need runtime permissions for this, so pass true
    App(wifiAwareService = wifiAwareService, permissionsGranted = true)
}
