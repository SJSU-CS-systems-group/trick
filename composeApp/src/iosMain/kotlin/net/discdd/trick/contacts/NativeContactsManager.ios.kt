package net.discdd.trick.contacts

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Contacts.*
import platform.Foundation.NSUserDefaults

/**
 * Internal data class for persisting trick contact data in NSUserDefaults.
 */
@Serializable
private data class StoredTrickContactData(
    val contactIdentifier: String,
    val nativeContactId: Long,
    val displayName: String,
    val shortId: String,
    val publicKeyHex: String,
    val deviceId: String? = null
)

/**
 * iOS implementation of NativeContactsManager.
 * Uses iOS Contacts framework for reading contact details and NSUserDefaults
 * for storing trick key data (since iOS doesn't support custom data types
 * in the Contacts database like Android does).
 *
 * Workflow:
 * 1. After QR key distribution, caller picks a contact via IOSContactPickerScreen
 * 2. Caller registers the mapping via registerContactMapping()
 * 3. Caller calls linkTrickDataToContact() to persist the association
 */
@OptIn(ExperimentalForeignApi::class)
actual class NativeContactsManager {
    private val contactStore = CNContactStore()
    private val userDefaults = NSUserDefaults.standardUserDefaults
    private val json = Json { ignoreUnknownKeys = true }
    private val contactsFlow = MutableStateFlow<List<TrickContact>>(emptyList())

    // In-memory mapping: nativeContactId (Long) -> (contactIdentifier, displayName)
    // Populated by registerContactMapping() before linkTrickDataToContact() is called.
    private val pendingMappings = mutableMapOf<Long, Pair<String, String>>()

    companion object {
        private const val TRICK_SHORTIDS_KEY = "trick_contacts_shortids"
        private const val TRICK_DATA_PREFIX = "trick_data_"
    }

    /**
     * Register a mapping from nativeContactId (Long) to iOS contact identifier (String).
     * Must be called before linkTrickDataToContact on iOS, because the expect API
     * only passes a Long, but iOS needs the CNContact.identifier string.
     */
    fun registerContactMapping(nativeContactId: Long, contactIdentifier: String, displayName: String) {
        pendingMappings[nativeContactId] = Pair(contactIdentifier, displayName)
    }

    actual fun getTrickContacts(): List<TrickContact> {
        val shortIds = getStoredShortIds()
        return shortIds.mapNotNull { loadTrickContact(it) }
    }

    actual fun observeTrickContacts(): Flow<List<TrickContact>> {
        contactsFlow.value = getTrickContacts()
        return contactsFlow
    }

    actual fun linkTrickDataToContact(
        nativeContactId: Long,
        shortId: String,
        publicKeyHex: String,
        deviceId: String?
    ): Boolean {
        return try {
            // Remove existing link with this shortId (prevents duplicates)
            unlinkTrickData(shortId)

            // Retrieve the iOS contact details from the pending mapping
            val (contactIdentifier, displayName) = pendingMappings.remove(nativeContactId)
                ?: return false

            val stored = StoredTrickContactData(
                contactIdentifier = contactIdentifier,
                nativeContactId = nativeContactId,
                displayName = displayName,
                shortId = shortId,
                publicKeyHex = publicKeyHex,
                deviceId = deviceId
            )

            userDefaults.setObject(json.encodeToString(stored), "$TRICK_DATA_PREFIX$shortId")
            addShortId(shortId)

            // Update reactive flow
            contactsFlow.value = getTrickContacts()
            true
        } catch (e: Exception) {
            println("NativeContactsManager: Failed to link trick data: ${e.message}")
            false
        }
    }

    actual fun getContactByShortId(shortId: String): TrickContact? {
        return loadTrickContact(shortId)
    }

    actual fun unlinkTrickData(shortId: String): Boolean {
        return try {
            userDefaults.removeObjectForKey("$TRICK_DATA_PREFIX$shortId")
            removeShortId(shortId)

            // Update reactive flow
            contactsFlow.value = getTrickContacts()
            true
        } catch (e: Exception) {
            println("NativeContactsManager: Failed to unlink trick data: ${e.message}")
            false
        }
    }

    actual fun hasContactsPermission(): Boolean {
        val status = CNContactStore.authorizationStatusForEntityType(CNEntityType.CNEntityTypeContacts)
        return status == CNAuthorizationStatusAuthorized || status == CNAuthorizationStatusLimited
    }

    // ---- Internal helpers ----

    private fun getStoredShortIds(): List<String> {
        val jsonStr = userDefaults.stringForKey(TRICK_SHORTIDS_KEY) ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun addShortId(shortId: String) {
        val shortIds = getStoredShortIds().toMutableList()
        if (shortId !in shortIds) {
            shortIds.add(shortId)
            userDefaults.setObject(json.encodeToString(shortIds), TRICK_SHORTIDS_KEY)
        }
    }

    private fun removeShortId(shortId: String) {
        val shortIds = getStoredShortIds().toMutableList()
        shortIds.remove(shortId)
        userDefaults.setObject(json.encodeToString(shortIds), TRICK_SHORTIDS_KEY)
    }

    private fun loadTrickContact(shortId: String): TrickContact? {
        val dataJson = userDefaults.stringForKey("$TRICK_DATA_PREFIX$shortId") ?: return null
        return try {
            val stored = json.decodeFromString<StoredTrickContactData>(dataJson)
            // Try refreshing display name from CNContactStore (contact may have been renamed)
            val currentName = fetchContactName(stored.contactIdentifier)
            TrickContact(
                nativeContactId = stored.nativeContactId,
                displayName = currentName ?: stored.displayName,
                photoUri = null,
                shortId = stored.shortId,
                publicKeyHex = stored.publicKeyHex,
                deviceId = stored.deviceId
            )
        } catch (e: Exception) {
            println("NativeContactsManager: Failed to load trick contact $shortId: ${e.message}")
            null
        }
    }

    private fun fetchContactName(contactIdentifier: String): String? {
        if (!hasContactsPermission()) return null
        return try {
            val keysToFetch: List<Any> = listOf(CNContactGivenNameKey, CNContactFamilyNameKey)
            val contact = contactStore.unifiedContactWithIdentifier(
                contactIdentifier,
                keysToFetch = keysToFetch,
                error = null
            ) ?: return null
            val name = "${contact.givenName} ${contact.familyName}".trim()
            name.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}
