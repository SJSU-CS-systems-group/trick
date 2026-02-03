package net.discdd.trick

import androidx.compose.ui.window.ComposeUIViewController
import net.discdd.trick.data.ContactRepository
import net.discdd.trick.data.DatabaseProvider
import net.discdd.trick.di.initKoin
import net.discdd.trick.screens.messaging.WifiAwareServiceImpl
import net.discdd.trick.security.KeyManager
import org.koin.core.context.GlobalContext

private var isInitialized = false

fun MainViewController() = ComposeUIViewController {
    if (!isInitialized) {
        DatabaseProvider.initialize(Unit)
        val database = DatabaseProvider.getDatabase()
        initKoin(database)
        val contactRepository = GlobalContext.get().get<ContactRepository>()
        val keyManager = KeyManager()
        contactRepository.migrateFromKeyManager(keyManager)
        isInitialized = true
    }

    val wifiAwareService = WifiAwareServiceImpl()
    App(wifiAwareService = wifiAwareService, permissionsGranted = true)
}
