package net.discdd.trick.di

import net.discdd.trick.TrickDatabase
import net.discdd.trick.contacts.NativeContactsManager
import net.discdd.trick.data.MessageMetadataRepository
import net.discdd.trick.data.MessageMetadataRepositoryImpl
import net.discdd.trick.screens.chat.ChatViewModel
import net.discdd.trick.screens.contacts.ContactsListViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Initialize Koin with database and platform-specific modules.
 *
 * @param database The TrickDatabase instance
 * @param platformModule Platform-specific module (provides NativeContactsManager)
 */
fun initKoin(database: TrickDatabase? = null, platformModule: Module? = null) {
    startKoin {
        val modules = mutableListOf<Module>()

        // Add platform-specific module if provided
        if (platformModule != null) {
            modules.add(platformModule)
        }

        // Add common module
        modules.add(
            module {
                // Provide database if available
                if (database != null) {
                    single<TrickDatabase> { database }

                    // Provide MessageMetadataRepository
                    single<MessageMetadataRepository> { MessageMetadataRepositoryImpl(get()) }

                    // Provide ContactsListViewModel (requires NativeContactsManager from platform module)
                    viewModel { ContactsListViewModel(get(), get()) }

                    // Provide ChatViewModel with shortId from navigation (requires NativeContactsManager)
                    viewModel { (shortId: String) -> ChatViewModel(shortId, get<NativeContactsManager>()) }
                }
            }
        )

        modules(modules)
    }
}
