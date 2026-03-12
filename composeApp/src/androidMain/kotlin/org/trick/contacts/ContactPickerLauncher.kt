package org.trick.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Result from the contact picker.
 */
data class ContactPickerResult(
    val rawContactId: Long,
    val displayName: String,
    val photoUri: String?
)

/**
 * Composable that provides a launcher for the Android native contact picker.
 *
 * @param onContactPicked Callback when a contact is picked (null if cancelled)
 * @return A function to launch the contact picker
 */
@Composable
fun rememberContactPickerLauncher(
    onContactPicked: (ContactPickerResult?) -> Unit
): () -> Unit {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        if (uri != null) {
            val result = resolveContactFromUri(context, uri)
            onContactPicked(result)
        } else {
            onContactPicked(null)
        }
    }

    return remember(launcher) {
        { launcher.launch(null) }
    }
}

/**
 * Resolve contact details from a contact URI.
 */
private fun resolveContactFromUri(context: Context, contactUri: Uri): ContactPickerResult? {
    // First get the contact ID from the URI
    val contactId = contactUri.lastPathSegment?.toLongOrNull() ?: run {
        // Query to get the contact ID
        context.contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts._ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                cursor.getLong(idIndex)
            } else null
        }
    } ?: return null

    // Get display name and photo URI
    var displayName = "Unknown"
    var photoUri: String? = null

    context.contentResolver.query(
        ContactsContract.Contacts.CONTENT_URI,
        arrayOf(
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.PHOTO_URI
        ),
        "${ContactsContract.Contacts._ID} = ?",
        arrayOf(contactId.toString()),
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val photoIndex = cursor.getColumnIndex(ContactsContract.Contacts.PHOTO_URI)

            displayName = cursor.getString(nameIndex) ?: "Unknown"
            photoUri = cursor.getString(photoIndex)
        }
    }

    // Get raw_contact_id from contact_id
    val rawContactId = getRawContactId(context, contactId) ?: return null

    return ContactPickerResult(
        rawContactId = rawContactId,
        displayName = displayName,
        photoUri = photoUri
    )
}

/**
 * Get the raw_contact_id from a contact_id.
 * We prefer raw_contact_id because that's what we use to link Trick data.
 */
private fun getRawContactId(context: Context, contactId: Long): Long? {
    context.contentResolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(ContactsContract.RawContacts._ID),
        "${ContactsContract.RawContacts.CONTACT_ID} = ?",
        arrayOf(contactId.toString()),
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idIndex = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
            return cursor.getLong(idIndex)
        }
    }
    return null
}
