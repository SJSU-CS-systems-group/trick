package org.trcky.trick

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.rememberNavController
import org.trcky.trick.navigation.KeyDistributionContent
import org.trcky.trick.navigation.OnPickImageRequest
import org.trcky.trick.navigation.TrickNavHost
import org.trcky.trick.screens.UnsupportedDeviceScreen
import org.trcky.trick.screens.messaging.WifiAwareService
import org.trcky.trick.theme.AppThemeState
import org.trcky.trick.theme.LocalAppTheme
import org.trcky.trick.theme.TrickTheme

@Composable
fun App(
    wifiAwareService: WifiAwareService,
    permissionsGranted: Boolean = false,
    wifiAwareSupported: Boolean = true,
    onPickImage: OnPickImageRequest? = null,
    keyDistributionContent: KeyDistributionContent? = null
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
                keyDistributionContent = keyDistributionContent
            )
        }
        }
    }
}
