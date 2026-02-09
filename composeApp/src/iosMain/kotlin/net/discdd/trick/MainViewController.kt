package net.discdd.trick

import androidx.compose.ui.window.ComposeUIViewController
import net.discdd.trick.contacts.NativeContactsManager
import net.discdd.trick.data.DatabaseProvider
import net.discdd.trick.data.ImageStorage
import net.discdd.trick.di.initKoin
import net.discdd.trick.screens.messaging.WifiAwareNativeBridge
import net.discdd.trick.screens.messaging.WifiAwareServiceImpl
import net.discdd.trick.signal.SignalSessionManager
import org.koin.mp.KoinPlatform
import org.koin.dsl.module

private var isInitialized = false

/**
 * Create the main Compose UI view controller.
 *
 * @param bridge Optional Wi-Fi Aware native bridge from Swift (null on iOS < 26)
 */
fun MainViewController(bridge: WifiAwareNativeBridge? = null) = ComposeUIViewController {
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
    val wifiAwareService = WifiAwareServiceImpl(signalSessionManager, bridge)
    App(
        wifiAwareService = wifiAwareService,
        permissionsGranted = true,
        keyExchangeContent = { deviceId, onNavigateBack ->
            IOSKeyExchangeScreen(
                deviceId = deviceId,
                onNavigateBack = onNavigateBack
            )
        }
    )
}
