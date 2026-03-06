package org.trcky.trick

import android.app.Application
import android.content.Context
import org.trcky.trick.contacts.NativeContactsManager
import org.trcky.trick.data.DatabaseProvider
import org.trcky.trick.data.ImageStorage
import org.trcky.trick.di.androidModule
import org.trcky.trick.di.initKoin
import org.koin.dsl.module

class MessagingApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize database
        DatabaseProvider.initialize(this)

        // Create platform-specific module with NativeContactsManager and Signal components
        val database = DatabaseProvider.getDatabase()
        val platformModule = module {
            single<Context> { this@MessagingApp }
            single { NativeContactsManager(this@MessagingApp) }
            single { ImageStorage() }
            includes(androidModule())
        }

        // Initialize Koin with database and platform module
        initKoin(database, platformModule)
    }
}
