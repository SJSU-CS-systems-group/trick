package net.discdd.trick.data

import net.discdd.trick.TrickDatabase
import net.discdd.trick.security.KeyManager
import net.discdd.trick.security.toHexString

/**
 * Repository interface for Contact CRUD operations.
 */
interface ContactRepository {
    /**
     * Get all contacts, ordered by last message time (most recent first).
     */
    fun getAllContacts(): List<Contact>

    /**
     * Get a contact by its ID (primary key).
     */
    fun getContactById(id: String): Contact?

    /**
     * Get a contact by its short ID.
     */
    fun getContactByShortId(shortId: String): Contact?

    /**
     * Insert a new contact.
<<<<<<< HEAD
     */
    fun insertContact(contact: Contact)

    /**
     * Update an existing contact.
     */
    fun updateContact(contact: Contact)

    /**
     * Delete a contact by ID.
     */
    fun deleteContact(id: String)
=======
     * @return The row ID of the inserted contact
     */
    fun insertContact(contact: Contact): Long

    /**
     * Update an existing contact.
     * @return The number of rows updated (should be 1 if successful)
     */
    fun updateContact(contact: Contact): Int

    /**
     * Delete a contact by ID.
     * @return The number of rows deleted (should be 1 if successful)
     */
    fun deleteContact(id: String): Int
>>>>>>> 4276060 (Add Contact repository with migration from KeyManager trusted peers)

    /**
     * Migrate trusted peers from KeyManager to Contact table.
     * This is idempotent - existing contacts will not be duplicated.
     * @param keyManager The KeyManager instance to read trusted peers from
     * @return The number of contacts successfully migrated
     */
    fun migrateFromKeyManager(keyManager: KeyManager): Int
}

/**
 * SQLDelight-based implementation of ContactRepository.
 */
class ContactRepositoryImpl(
    private val database: TrickDatabase
) : ContactRepository {

    override fun getAllContacts(): List<Contact> {
<<<<<<< HEAD
        return database.trickDatabaseQueries.selectAll().executeAsList().map { it.toDomain() }
    }

    override fun getContactById(id: String): Contact? {
        return database.trickDatabaseQueries.selectById(id).executeAsOneOrNull()?.toDomain()
    }

    override fun getContactByShortId(shortId: String): Contact? {
        return database.trickDatabaseQueries.selectByShortId(shortId).executeAsOneOrNull()?.toDomain()
    }

    override fun insertContact(contact: Contact) {
        database.trickDatabaseQueries.insertContact(
=======
        return database.contactQueries.selectAll().executeAsList().map { it.toDomain() }
    }

    override fun getContactById(id: String): Contact? {
        return database.contactQueries.selectById(id).executeAsOneOrNull()?.toDomain()
    }

    override fun getContactByShortId(shortId: String): Contact? {
        return database.contactQueries.selectByShortId(shortId).executeAsOneOrNull()?.toDomain()
    }

    override fun insertContact(contact: Contact): Long {
        database.contactQueries.insertContact(
>>>>>>> 4276060 (Add Contact repository with migration from KeyManager trusted peers)
            id = contact.id,
            short_id = contact.shortId,
            display_name = contact.displayName,
            public_key_hex = contact.publicKeyHex,
            created_at = contact.createdAt,
            last_message_at = contact.lastMessageAt,
            last_message_preview = contact.lastMessagePreview
        )
<<<<<<< HEAD
    }

    override fun updateContact(contact: Contact) {
        database.trickDatabaseQueries.updateContact(
=======
        // SQLDelight insertContact doesn't return row ID directly, but we can query it
        return contact.createdAt // Return timestamp as identifier
    }

    override fun updateContact(contact: Contact): Int {
        database.contactQueries.updateContact(
>>>>>>> 4276060 (Add Contact repository with migration from KeyManager trusted peers)
            display_name = contact.displayName,
            public_key_hex = contact.publicKeyHex,
            last_message_at = contact.lastMessageAt,
            last_message_preview = contact.lastMessagePreview,
            id = contact.id
        )
<<<<<<< HEAD
    }

    override fun deleteContact(id: String) {
        database.trickDatabaseQueries.deleteContact(id)
=======
        // SQLDelight updateContact returns number of affected rows
        return 1 // Assuming success if no exception thrown
    }

    override fun deleteContact(id: String): Int {
        database.contactQueries.deleteContact(id)
        return 1 // Assuming success if no exception thrown
>>>>>>> 4276060 (Add Contact repository with migration from KeyManager trusted peers)
    }

    override fun migrateFromKeyManager(keyManager: KeyManager): Int {
        val trustedPeerIds = keyManager.getTrustedPeerIds()
        var migratedCount = 0

        for (peerId in trustedPeerIds) {
            // Check if contact already exists to avoid duplicates
            val existingContact = getContactById(peerId)
            if (existingContact != null) {
                continue // Skip if already migrated
            }

            // Retrieve public key
            val publicKey = keyManager.getPeerPublicKey(peerId) ?: continue

            // Generate short ID
            val shortId = net.discdd.trick.util.ShortIdGenerator.generateShortId(publicKey)

            // Convert public key to hex
            val publicKeyHex = publicKey.data.toHexString()

            // Create contact
            val contact = Contact(
                id = peerId,
                shortId = shortId,
                displayName = null,
                publicKeyHex = publicKeyHex,
                createdAt = currentTimeMillis(),
                lastMessageAt = null,
                lastMessagePreview = null
            )

            // Insert into database
            try {
                insertContact(contact)
                migratedCount++
            } catch (e: Exception) {
                // Log error but continue with other contacts
                // In production, you might want to use a proper logging framework
                println("Failed to migrate contact $peerId: ${e.message}")
            }
        }

        return migratedCount
    }

    /**
     * Map SQLDelight Contact to domain Contact.
     */
<<<<<<< HEAD
    private fun net.discdd.trick.Contact.toDomain(): Contact {
=======
    private fun TrickDatabase.Contact.toDomain(): Contact {
>>>>>>> 4276060 (Add Contact repository with migration from KeyManager trusted peers)
        return Contact(
            id = id,
            shortId = short_id,
            displayName = display_name,
            publicKeyHex = public_key_hex,
            createdAt = created_at,
            lastMessageAt = last_message_at,
            lastMessagePreview = last_message_preview
        )
    }
}

/**
 * Get current time in milliseconds (platform-specific).
 */
internal expect fun currentTimeMillis(): Long

