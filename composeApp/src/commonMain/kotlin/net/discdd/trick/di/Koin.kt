package net.discdd.trick.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.discdd.trick.TrickDatabase
import net.discdd.trick.contacts.NativeContactsManager
import net.discdd.trick.data.ImageStorage
import net.discdd.trick.data.MessageMetadataRepository
import net.discdd.trick.data.MessageMetadataRepositoryImpl
import net.discdd.trick.data.MessagePersistenceManager
import net.discdd.trick.data.MessageRepository
import net.discdd.trick.data.MessageRepositoryImpl
import net.discdd.trick.screens.chat.ChatViewModel
import net.discdd.trick.screens.contacts.ContactsListViewModel
import net.discdd.trick.signal.SecureKeyStorage
import net.discdd.trick.signal.SignalSessionManager
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
