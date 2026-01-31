package net.discdd.trick.di

import net.discdd.trick.TrickDatabase
import net.discdd.trick.data.ContactRepository
import net.discdd.trick.data.ContactRepositoryImpl
import net.discdd.trick.screens.contacts.ContactsListViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

fun initKoin(database: TrickDatabase? = null) {
    startKoin {
        modules(
            module {
                // Provide database if available
                if (database != null) {
                    single<TrickDatabase> { database }
                    
                    // Provide ContactRepository
                    single<ContactRepository> { ContactRepositoryImpl(get()) }
                    
                    // Provide ContactsListViewModel
                    viewModel { ContactsListViewModel(get()) }
                }
            }
        )
    }
}
