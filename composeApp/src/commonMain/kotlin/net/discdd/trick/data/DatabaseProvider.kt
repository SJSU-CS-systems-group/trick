package net.discdd.trick.data

import net.discdd.trick.TrickDatabase

/**
 * Platform-specific database provider
 * Initialize with platform-specific context before use
 */
expect object DatabaseProvider {
    /**
     * Initialize the database with platform-specific context
     * Must be called before getDatabase()
     */
    fun initialize(context: Any)
    
    /**
     * Get the database instance
     * @throws IllegalStateException if not initialized
     */
    fun getDatabase(): TrickDatabase
}

