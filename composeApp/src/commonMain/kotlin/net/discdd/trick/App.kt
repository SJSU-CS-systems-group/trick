package net.discdd.trick

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.rememberNavController
import net.discdd.trick.navigation.KeyExchangeContent
import net.discdd.trick.navigation.OnPickImageRequest
import net.discdd.trick.navigation.TrickNavHost
import net.discdd.trick.screens.UnsupportedDeviceScreen
import net.discdd.trick.screens.messaging.WifiAwareService
import net.discdd.trick.theme.AppThemeState
import net.discdd.trick.theme.LocalAppTheme
import net.discdd.trick.theme.TrickTheme

@Composable
fun App(
    wifiAwareService: WifiAwareService,
    permissionsGranted: Boolean = false,
    wifiAwareSupported: Boolean = true,
    onPickImage: OnPickImageRequest? = null,
    keyExchangeContent: KeyExchangeContent? = null
) {
    var isDarkTheme by remember { mutableStateOf(true) }
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeState(
            isDark = isDarkTheme,
            onToggleTheme = { isDarkTheme = !isDarkTheme }
        )
    ) {
        TrickTheme(isDark = isDarkTheme) {
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
                onPickImage = onPickImage,
                keyExchangeContent = keyExchangeContent
            )
        }
        }
    }
}
