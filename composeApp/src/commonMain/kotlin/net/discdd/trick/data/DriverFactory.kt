package net.discdd.trick.data

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific factory for creating SQLite drivers
 */
expect class DriverFactory {
    fun createDriver(): SqlDriver
}

