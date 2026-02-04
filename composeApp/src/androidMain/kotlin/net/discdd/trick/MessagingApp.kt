package net.discdd.trick

import android.app.Application
import net.discdd.trick.contacts.NativeContactsManager
import net.discdd.trick.data.DatabaseProvider
import net.discdd.trick.di.initKoin
import org.koin.dsl.module

class MessagingApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize database
        DatabaseProvider.initialize(this)

        // Create platform-specific module with NativeContactsManager
        val platformModule = module {
            single { NativeContactsManager(this@MessagingApp) }
        }

        // Initialize Koin with database and platform module
        val database = DatabaseProvider.getDatabase()
        initKoin(database, platformModule)
    }
}
