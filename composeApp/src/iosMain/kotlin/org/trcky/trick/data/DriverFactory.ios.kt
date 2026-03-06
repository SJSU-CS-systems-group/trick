package org.trcky.trick.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import org.trcky.trick.TrickDatabase

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        return NativeSqliteDriver(
            schema = TrickDatabase.Schema,
            name = "trick.db"
        )
    }
}


