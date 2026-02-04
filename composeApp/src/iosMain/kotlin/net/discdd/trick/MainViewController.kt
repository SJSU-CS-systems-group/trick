package net.discdd.trick

import androidx.compose.ui.window.ComposeUIViewController
import net.discdd.trick.contacts.NativeContactsManager
import net.discdd.trick.data.DatabaseProvider
import net.discdd.trick.di.initKoin
import net.discdd.trick.screens.messaging.WifiAwareServiceImpl
import org.koin.dsl.module

private var isInitialized = false

fun MainViewController() = ComposeUIViewController {
    if (!isInitialized) {
        DatabaseProvider.initialize(Unit)
        val database = DatabaseProvider.getDatabase()

        // Create platform-specific module with NativeContactsManager stub
        val platformModule = module {
            single { NativeContactsManager() }
        }

        initKoin(database, platformModule)
        isInitialized = true
    }

    val wifiAwareService = WifiAwareServiceImpl()
    App(wifiAwareService = wifiAwareService, permissionsGranted = true)
}
