package net.discdd.trick.di

import org.koin.core.context.startKoin
import org.koin.dsl.module

fun initKoin() {
    startKoin {
        modules(
            // No modules needed for Wi-Fi Aware messaging app
        )
    }
}
