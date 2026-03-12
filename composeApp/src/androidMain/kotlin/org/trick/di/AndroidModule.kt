package org.trick.di

import org.koin.dsl.module

/**
 * Android-specific Koin module.
 * Signal components are now provided in the common module.
 */
fun androidModule() = module {
    // Platform-specific services can be added here
}
