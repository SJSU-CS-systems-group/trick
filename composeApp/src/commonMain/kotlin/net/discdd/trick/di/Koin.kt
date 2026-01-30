package net.discdd.trick.di

import net.discdd.trick.TrickDatabase
import org.koin.core.context.startKoin
import org.koin.dsl.module

fun initKoin(database: TrickDatabase? = null) {
    startKoin {
        modules(
            module {
                // Provide database if available
                if (database != null) {
                    single<TrickDatabase> { database }
                }
            }
        )
    }
}
