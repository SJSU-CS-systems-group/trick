package org.trcky.trick.di

import android.content.Context
import org.koin.dsl.module
import org.trcky.trick.screens.messaging.TrickDDDManager
import org.trcky.trick.screens.messaging.WifiAwareService
import org.trcky.trick.screens.messaging.WifiAwareServiceImpl
import org.trcky.trick.signal.SignalSessionManager

/**
 * Android-specific Koin module.
 * Signal components are now provided in the common module.
 */
fun androidModule() = module {
    single { TrickDDDManager(get<Context>()) }
    single<WifiAwareService> {
        WifiAwareServiceImpl(get<Context>(), get<SignalSessionManager>(), get<TrickDDDManager>())
    }
}
