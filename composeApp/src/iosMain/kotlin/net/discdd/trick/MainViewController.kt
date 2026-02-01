package net.discdd.trick

import androidx.compose.ui.window.ComposeUIViewController
import net.discdd.trick.data.DatabaseProvider
import net.discdd.trick.di.initKoin
import net.discdd.trick.screens.messaging.WifiAwareServiceImpl

private var isInitialized = false

fun MainViewController() = ComposeUIViewController {
    if (!isInitialized) {
        DatabaseProvider.initialize(Unit)
        val database = DatabaseProvider.getDatabase()
        initKoin(database)
        isInitialized = true
    }

    val wifiAwareService = WifiAwareServiceImpl()
    App(wifiAwareService = wifiAwareService, permissionsGranted = true)
}
