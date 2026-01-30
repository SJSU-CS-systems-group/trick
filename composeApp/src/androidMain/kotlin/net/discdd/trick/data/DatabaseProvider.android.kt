package net.discdd.trick.data

import android.content.Context
import net.discdd.trick.TrickDatabase

actual object DatabaseProvider {
    private var database: TrickDatabase? = null
    
    actual fun initialize(context: Any) {
        require(context is Context) { "Context must be an Android Context" }
        if (database == null) {
            val driverFactory = DriverFactory(context.applicationContext)
            database = TrickDatabase(driverFactory.createDriver())
        }
    }
    
    actual fun getDatabase(): TrickDatabase {
        return database ?: throw IllegalStateException(
            "DatabaseProvider not initialized. Call initialize(context) first."
        )
    }
}

