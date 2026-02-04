package net.discdd.trick.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Custom MIME type for storing Trick key data in Android Contacts.
 * DATA1 = shortId (12-char hex)
 * DATA2 = publicKeyHex
 * DATA3 = deviceId (64-char hex, optional - for WiFi Aware peer matching)
 */
private const val TRICK_MIME_TYPE = "vnd.android.cursor.item/net.discdd.trick.key"

/**
 * Android implementation of NativeContactsManager.
 * Uses ContentProvider to store custom MIME type data in the Contacts app.
 */
actual class NativeContactsManager(
    private val context: Context
) {
    private val contentResolver: ContentResolver = context.contentResolver

    actual fun getTrickContacts(): List<TrickContact> {
        if (!hasContactsPermission()) {
            return emptyList()
        }

        val contacts = mutableListOf<TrickContact>()

        // Query for all data rows with our custom MIME type
        val projection = arrayOf(
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.DATA1, // shortId
            ContactsContract.Data.DATA2, // publicKeyHex
            ContactsContract.Data.DATA3  // deviceId (optional)
        )

        val selection = "${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(TRICK_MIME_TYPE)

        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val rawContactIdIndex = cursor.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
            val shortIdIndex = cursor.getColumnIndex(ContactsContract.Data.DATA1)
            val publicKeyIndex = cursor.getColumnIndex(ContactsContract.Data.DATA2)
            val deviceIdIndex = cursor.getColumnIndex(ContactsContract.Data.DATA3)

            while (cursor.moveToNext()) {
                val rawContactId = cursor.getLong(rawContactIdIndex)
                val shortId = cursor.getString(shortIdIndex) ?: continue
                val publicKeyHex = cursor.getString(publicKeyIndex) ?: continue
                val deviceId = cursor.getString(deviceIdIndex) // nullable

                // Get contact details (name and photo)
                val contactDetails = getContactDetails(rawContactId)
                if (contactDetails != null) {
                    contacts.add(
                        TrickContact(
                            nativeContactId = rawContactId,
                            displayName = contactDetails.first,
                            photoUri = contactDetails.second,
                            shortId = shortId,
                            publicKeyHex = publicKeyHex,
                            deviceId = deviceId
                        )
                    )
                }
            }
        }

        return contacts
    }

    actual fun observeTrickContacts(): Flow<List<TrickContact>> {
        // Return empty flow if permissions not granted (avoid SecurityException)
        if (!hasContactsPermission()) {
            return flowOf(emptyList())
        }

        return callbackFlow {
            // Emit initial value
            trySend(getTrickContacts())

            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(getTrickContacts())
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    trySend(getTrickContacts())
                }
            }

            // Register observer for contacts changes
            contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                observer
            )

            awaitClose {
                contentResolver.unregisterContentObserver(observer)
            }
        }
    }

    actual fun linkTrickDataToContact(
        nativeContactId: Long,
        shortId: String,
        publicKeyHex: String,
        deviceId: String?
    ): Boolean {
        if (!hasContactsPermission()) {
            return false
        }

        return try {
            // First check if this contact already has Trick data
            val existingDataId = findTrickDataId(nativeContactId)

            if (existingDataId != null) {
                // Update existing data row
                val values = android.content.ContentValues().apply {
                    put(ContactsContract.Data.DATA1, shortId)
                    put(ContactsContract.Data.DATA2, publicKeyHex)
                    put(ContactsContract.Data.DATA3, deviceId)
                }

                val updatedRows = contentResolver.update(
                    ContactsContract.Data.CONTENT_URI,
                    values,
                    "${ContactsContract.Data._ID} = ?",
                    arrayOf(existingDataId.toString())
                )

                updatedRows > 0
            } else {
                // Insert new data row
                val operations = ArrayList<ContentProviderOperation>()

                val insertOp = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, nativeContactId)
                    .withValue(ContactsContract.Data.MIMETYPE, TRICK_MIME_TYPE)
                    .withValue(ContactsContract.Data.DATA1, shortId)
                    .withValue(ContactsContract.Data.DATA2, publicKeyHex)

                // Only add deviceId if provided
                if (deviceId != null) {
                    insertOp.withValue(ContactsContract.Data.DATA3, deviceId)
                }

                operations.add(insertOp.build())

                val results = contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
                results.isNotEmpty() && results[0].uri != null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    actual fun getContactByShortId(shortId: String): TrickContact? {
        if (!hasContactsPermission()) {
            return null
        }

        val projection = arrayOf(
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.DATA1, // shortId
            ContactsContract.Data.DATA2, // publicKeyHex
            ContactsContract.Data.DATA3  // deviceId (optional)
        )

        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DATA1} = ?"
        val selectionArgs = arrayOf(TRICK_MIME_TYPE, shortId)

        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val rawContactIdIndex = cursor.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
                val publicKeyIndex = cursor.getColumnIndex(ContactsContract.Data.DATA2)
                val deviceIdIndex = cursor.getColumnIndex(ContactsContract.Data.DATA3)

                val rawContactId = cursor.getLong(rawContactIdIndex)
                val publicKeyHex = cursor.getString(publicKeyIndex) ?: return null
                val deviceId = cursor.getString(deviceIdIndex) // nullable

                val contactDetails = getContactDetails(rawContactId)
                if (contactDetails != null) {
                    return TrickContact(
                        nativeContactId = rawContactId,
                        displayName = contactDetails.first,
                        photoUri = contactDetails.second,
                        shortId = shortId,
                        publicKeyHex = publicKeyHex,
                        deviceId = deviceId
                    )
                }
            }
        }

        return null
    }

    actual fun unlinkTrickData(shortId: String): Boolean {
        if (!hasContactsPermission()) {
            return false
        }

        return try {
            val deletedRows = contentResolver.delete(
                ContactsContract.Data.CONTENT_URI,
                "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DATA1} = ?",
                arrayOf(TRICK_MIME_TYPE, shortId)
            )
            deletedRows > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    actual fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.WRITE_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get display name and photo URI for a raw contact.
     *
     * @return Pair of (displayName, photoUri) or null if not found
     */
    private fun getContactDetails(rawContactId: Long): Pair<String, String?>? {
        // First get the contact_id from the raw_contact_id
        val contactId = getContactIdFromRawContact(rawContactId) ?: return null

        val projection = arrayOf(
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.PHOTO_URI
        )

        contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val photoIndex = cursor.getColumnIndex(ContactsContract.Contacts.PHOTO_URI)

                val displayName = cursor.getString(nameIndex) ?: "Unknown"
                val photoUri = cursor.getString(photoIndex)

                return Pair(displayName, photoUri)
            }
        }

        return null
    }

    /**
     * Get the contact_id from a raw_contact_id.
     */
    private fun getContactIdFromRawContact(rawContactId: Long): Long? {
        val projection = arrayOf(ContactsContract.RawContacts.CONTACT_ID)

        contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            projection,
            "${ContactsContract.RawContacts._ID} = ?",
            arrayOf(rawContactId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val contactIdIndex = cursor.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID)
                return cursor.getLong(contactIdIndex)
            }
        }

        return null
    }

    /**
     * Find the data row ID for existing Trick data on a contact.
     */
    private fun findTrickDataId(rawContactId: Long): Long? {
        val projection = arrayOf(ContactsContract.Data._ID)

        val selection = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(rawContactId.toString(), TRICK_MIME_TYPE)

        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(ContactsContract.Data._ID)
                return cursor.getLong(idIndex)
            }
        }

        return null
    }
}
