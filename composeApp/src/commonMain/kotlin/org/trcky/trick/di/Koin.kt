package org.trcky.trick.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.trcky.trick.TrickDatabase
import org.trcky.trick.contacts.NativeContactsManager
import org.trcky.trick.data.ImageStorage
import org.trcky.trick.data.MessageMetadataRepository
import org.trcky.trick.data.MessageMetadataRepositoryImpl
import org.trcky.trick.data.MessagePersistenceManager
import org.trcky.trick.data.MessageRepository
import org.trcky.trick.data.MessageRepositoryImpl
import org.trcky.trick.screens.chat.ChatViewModel
import org.trcky.trick.screens.contacts.ContactsListViewModel
import org.trcky.trick.signal.SecureKeyStorage
import org.trcky.trick.signal.SignalSessionManager
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
