package net.discdd.trick

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.navigation.compose.rememberNavController
import net.discdd.trick.navigation.TrickNavHost
import net.discdd.trick.screens.UnsupportedDeviceScreen
import net.discdd.trick.screens.messaging.WifiAwareService

@Composable
fun App(
    wifiAwareService: WifiAwareService,
    permissionsGranted: Boolean = false,
    wifiAwareSupported: Boolean = true
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    ) {
        Surface {
            if (!wifiAwareSupported) {
                UnsupportedDeviceScreen()
                return@Surface
            }
            val navController = rememberNavController()
            TrickNavHost(
                navController = navController,
                wifiAwareService = wifiAwareService,
                permissionsGranted = permissionsGranted,
                onPickImage = null,
                keyExchangeContent = null
            )
        }
    }
}
