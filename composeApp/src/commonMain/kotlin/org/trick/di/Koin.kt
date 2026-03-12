package org.trick.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.trick.TrickDatabase
import org.trick.contacts.NativeContactsManager
import org.trick.data.ImageStorage
import org.trick.data.MessageMetadataRepository
import org.trick.data.MessageMetadataRepositoryImpl
import org.trick.data.MessagePersistenceManager
import org.trick.data.MessageRepository
import org.trick.data.MessageRepositoryImpl
import org.trick.screens.chat.ChatViewModel
import org.trick.screens.contacts.ContactsListViewModel
import org.trick.signal.SecureKeyStorage
import org.trick.signal.SignalSessionManager
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Initialize Koin with database and platform-specific modules.
 *
 * @param database The TrickDatabase instance
 * @param platformModule Platform-specific module (provides NativeContactsManager, ImageStorage)
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

                    // Provide repositories
                    single<MessageMetadataRepository> { MessageMetadataRepositoryImpl(get()) }
                    single<MessageRepository> { MessageRepositoryImpl(get()) }

                    // Provide Signal components
                    single { SecureKeyStorage() }
                    single { SignalSessionManager(get(), get()) }

                    // Provide MessagePersistenceManager
                    single {
                        MessagePersistenceManager(
                            database = get(),
                            messageRepository = get(),
                            messageMetadataRepository = get(),
                            imageStorage = get(),
                            nativeContactsManager = get(),
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                        )
                    }

                    // Provide ContactsListViewModel (requires NativeContactsManager from platform module)
                    viewModel { ContactsListViewModel(get(), get()) }

                    // Provide ChatViewModel with shortId from navigation
                    viewModel { (shortId: String) ->
                        ChatViewModel(
                            shortId,
                            get<NativeContactsManager>(),
                            get<MessageRepository>(),
                            get<ImageStorage>()
                        )
                    }
                }
            }
        )

        modules(modules)
    }
}
