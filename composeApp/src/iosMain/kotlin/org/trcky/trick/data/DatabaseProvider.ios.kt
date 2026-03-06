package org.trcky.trick.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import org.trcky.trick.TrickDatabase

actual object DatabaseProvider {
    private var database: TrickDatabase? = null

    actual fun initialize(context: Any) {
        if (database == null) {
            val driver = NativeSqliteDriver(
                schema = TrickDatabase.Schema,
                name = "trick.db"
            )
            database = TrickDatabase(driver)
        }
    }

    actual fun getDatabase(): TrickDatabase {
        return database ?: throw IllegalStateException(
            "DatabaseProvider not initialized. Call initialize(context) first."
        )
    }
}
