package net.discdd.trick

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import net.discdd.trick.navigation.TrickNavHost
import net.discdd.trick.screens.UnsupportedDeviceScreen
import net.discdd.trick.screens.messaging.WifiAwareService
import net.discdd.trick.screens.messaging.rememberImagePickerLauncher

@Composable
fun AndroidApp(
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
                keyExchangeContent = { deviceId, onNavigateBack ->
                    AndroidKeyExchangeScreen(
                        context = context,
                        deviceId = deviceId,
                        onNavigateBack = onNavigateBack
                    )
                }
            )
        }
    }
}
