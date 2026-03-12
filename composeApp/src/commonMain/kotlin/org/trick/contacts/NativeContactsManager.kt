package org.trick.contacts

import kotlinx.coroutines.flow.Flow

/**
 * Platform-specific manager for interacting with native contacts.
 *
 * On Android, this uses ContentProvider to store custom MIME type data
 * in the Contacts app. On iOS, this is a stub implementation.
 */
expect class NativeContactsManager {
    /**
     * Get all contacts that have Trick key data stored.
     */
    fun getTrickContacts(): List<TrickContact>

    /**
     * Observe contacts with Trick key data as a reactive Flow.
     * Automatically updates when contacts change.
     */
    fun observeTrickContacts(): Flow<List<TrickContact>>

    /**
     * Link Trick key data to an existing Android contact.
     *
     * @param nativeContactId The raw_contact_id from Android Contacts
     * @param shortId The 12-char hex identifier derived from the public key
     * @param publicKeyHex The full public key in hexadecimal format
     * @param deviceId The 64-char hex device identifier for WiFi Aware matching (optional)
     * @return true if successful, false otherwise
     */
    fun linkTrickDataToContact(nativeContactId: Long, shortId: String, publicKeyHex: String, deviceId: String?): Boolean

    /**
     * Get a contact by its shortId.
     *
     * @param shortId The 12-char hex identifier
     * @return The TrickContact if found, null otherwise
     */
    fun getContactByShortId(shortId: String): TrickContact?

    /**
     * Remove Trick key data from a contact.
     *
     * @param shortId The 12-char hex identifier
     * @return true if successful, false otherwise
     */
    fun unlinkTrickData(shortId: String): Boolean

    /**
     * Check if the app has contacts permissions.
     *
     * @return true if READ_CONTACTS and WRITE_CONTACTS are granted
     */
    fun hasContactsPermission(): Boolean
}
