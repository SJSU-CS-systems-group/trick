package net.discdd.trick

import android.app.Application
import net.discdd.trick.data.DatabaseProvider
import net.discdd.trick.di.initKoin

class MessagingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize database
        DatabaseProvider.initialize(this)
        
        // Initialize Koin with database
        val database = DatabaseProvider.getDatabase()
        initKoin(database)
    }
}

