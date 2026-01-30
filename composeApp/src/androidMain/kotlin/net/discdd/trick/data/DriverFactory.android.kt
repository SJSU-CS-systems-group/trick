package net.discdd.trick.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import net.discdd.trick.TrickDatabase

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = TrickDatabase.Schema,
            context = context,
            name = "trick.db"
        )
    }
}

