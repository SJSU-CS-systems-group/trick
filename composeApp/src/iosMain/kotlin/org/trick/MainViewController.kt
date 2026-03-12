package org.trick

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import org.trick.contacts.NativeContactsManager
import org.trick.data.DatabaseProvider
import org.trick.data.ImageStorage
import org.trick.di.initKoin
import org.trick.screens.messaging.ImagePickerBridge
import org.trick.screens.messaging.ImagePickerCallback
import org.trick.screens.messaging.WifiAwarePairingPresenter
import org.trick.screens.messaging.WifiAwareNativeBridge
import org.trick.screens.messaging.WifiAwareServiceImpl
import org.trick.screens.messaging.toByteArray
import org.trick.signal.SignalSessionManager
import org.koin.mp.KoinPlatform
import org.koin.dsl.module
import platform.Foundation.NSData

private var isInitialized = false

/**
 * Create the main Compose UI view controller.
 *
 * @param bridge Optional Wi-Fi Aware native bridge from Swift (null on iOS < 26)
 * @param imagePicker Optional image picker bridge from Swift for photo selection
 * @param pairingPresenter Optional native pairing presenter from Swift
 */
fun MainViewController(
    bridge: WifiAwareNativeBridge? = null,
    imagePicker: ImagePickerBridge? = null,
    pairingPresenter: WifiAwarePairingPresenter? = null
) = ComposeUIViewController {
    if (!isInitialized) {
        DatabaseProvider.initialize(Unit)
        val database = DatabaseProvider.getDatabase()

        // Create platform-specific module with NativeContactsManager stub
        val platformModule = module {
            single { NativeContactsManager() }
            single { ImageStorage() }
        }

        initKoin(database, platformModule)
        isInitialized = true
    }

    val signalSessionManager = KoinPlatform.getKoin().get<SignalSessionManager>()

    // Initialize Signal protocol on startup (matches Android's LaunchedEffect in MainActivity)
    var signalReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        signalSessionManager.initialize()
        signalSessionManager.replenishPreKeysIfNeeded()
        signalReady = true
    }

    val wifiAwareService = WifiAwareServiceImpl(signalSessionManager, bridge)
    
    // Create onPickImage callback if imagePicker is available
    val onPickImage: ((callback: (ByteArray, String, String) -> Unit) -> Unit)? = 
        if (imagePicker != null && imagePicker.isAvailable()) {
            { kotlinCallback ->
                // Create an ImagePickerCallback that bridges to the Kotlin lambda
                val bridgeCallback = object : ImagePickerCallback {
                    override fun onImagePicked(data: NSData, filename: String, mimeType: String) {
                        val byteArray = data.toByteArray()
                        kotlinCallback(byteArray, filename, mimeType)
                    }
                }
                imagePicker.pickImage(bridgeCallback)
            }
        } else {
            null
        }
    
    App(
        wifiAwareService = wifiAwareService,
        permissionsGranted = signalReady,
        onPickImage = onPickImage,
        keyDistributionContent = { deviceId, onNavigateBack ->
            IOSKeyDistributionScreen(
                deviceId = deviceId,
                onNavigateBack = onNavigateBack,
                pairingPresenter = pairingPresenter
            )
        }
    )
}
