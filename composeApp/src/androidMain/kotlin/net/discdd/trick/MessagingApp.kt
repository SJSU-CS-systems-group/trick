package net.discdd.trick

import android.app.Application
import net.discdd.trick.contacts.NativeContactsManager
import net.discdd.trick.data.DatabaseProvider
import net.discdd.trick.data.ImageStorage
import net.discdd.trick.di.androidModule
import net.discdd.trick.di.initKoin
import org.koin.dsl.module

class MessagingApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize database
        DatabaseProvider.initialize(this)

        // Create platform-specific module with NativeContactsManager and Signal components
        val database = DatabaseProvider.getDatabase()
        val platformModule = module {
            single { NativeContactsManager(this@MessagingApp) }
            single { ImageStorage(this@MessagingApp) }
            // Include Signal protocol components
            includes(androidModule(this@MessagingApp))
        }

        // Initialize Koin with database and platform module
        initKoin(database, platformModule)
    }
}
