package org.trick

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import org.trick.contacts.ContactsPermissionGate
import org.trick.navigation.TrickNavHost
import org.trick.screens.UnsupportedDeviceScreen
import org.trick.screens.messaging.WifiAwareService
import org.trick.screens.messaging.rememberImagePickerLauncher
import org.trick.theme.AppThemeState
import org.trick.theme.LocalAppTheme
import org.trick.theme.TrickTheme

@Composable
fun AndroidApp(
    wifiAwareService: WifiAwareService,
    permissionsGranted: Boolean = false,
    wifiAwareSupported: Boolean = true
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

            // Gate the app behind contacts permissions
            ContactsPermissionGate {
                val navController = rememberNavController()
                val context = LocalContext.current

                var imagePickedCallback by remember { mutableStateOf<((ByteArray, String, String) -> Unit)?>(null) }
                val imagePickerLauncher = rememberImagePickerLauncher { imageResult ->
                    imagePickedCallback?.invoke(
                        imageResult.data,
                        imageResult.filename,
                        imageResult.mimeType
                    )
                }

                TrickNavHost(
                    navController = navController,
                    wifiAwareService = wifiAwareService,
                    permissionsGranted = permissionsGranted,
                    onPickImage = { callback ->
                        imagePickedCallback = callback
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    keyDistributionContent = { deviceId, onNavigateBack ->
                        AndroidKeyDistributionScreen(
                            context = context,
                            deviceId = deviceId,
                            onNavigateBack = onNavigateBack
                        )
                    }
                )
            }
        }
        }
    }
}
