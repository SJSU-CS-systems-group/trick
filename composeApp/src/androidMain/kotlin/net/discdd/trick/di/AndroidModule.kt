package net.discdd.trick.di

import android.content.Context
import net.discdd.trick.TrickDatabase
import net.discdd.trick.signal.SignalSessionManager
import org.koin.dsl.module

/**
 * Android-specific Koin module for Signal Protocol components.
 */
fun androidModule(context: Context) = module {
    single { SignalSessionManager(context, get<TrickDatabase>()) }
}
