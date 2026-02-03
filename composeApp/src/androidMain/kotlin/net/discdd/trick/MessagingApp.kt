package net.discdd.trick

import android.app.Application
import net.discdd.trick.data.ContactRepository
import net.discdd.trick.data.DatabaseProvider
import net.discdd.trick.di.initKoin
import net.discdd.trick.security.KeyManager
import org.koin.core.context.GlobalContext

class MessagingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize database
        DatabaseProvider.initialize(this)
        
        // Initialize Koin with database
        val database = DatabaseProvider.getDatabase()
        initKoin(database)
        
        // Populate contacts from trusted peers (key exchange)
        val contactRepository = GlobalContext.get().get<ContactRepository>()
        val keyManager = KeyManager(this)
        contactRepository.migrateFromKeyManager(keyManager)
    }
}

