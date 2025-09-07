package com.jetbrains.kmpapp.di

import org.koin.core.context.startKoin
import org.koin.dsl.module

fun initKoin() {
    startKoin {
        modules(
            // No modules needed for WiFi Aware messaging app
        )
    }
}
