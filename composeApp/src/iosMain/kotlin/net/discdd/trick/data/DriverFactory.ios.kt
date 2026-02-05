package net.discdd.trick.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import net.discdd.trick.TrickDatabase

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        return NativeSqliteDriver(
            schema = TrickDatabase.Schema,
            name = "trick.db"
        )
    }
}


