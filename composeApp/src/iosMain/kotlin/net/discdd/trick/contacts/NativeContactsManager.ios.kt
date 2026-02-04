package net.discdd.trick.contacts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * iOS stub implementation of NativeContactsManager.
 * Native contacts integration is not yet implemented for iOS.
 */
actual class NativeContactsManager {
    actual fun getTrickContacts(): List<TrickContact> {
        // iOS implementation not yet available
        return emptyList()
    }

    actual fun observeTrickContacts(): Flow<List<TrickContact>> {
        // iOS implementation not yet available
        return flowOf(emptyList())
    }

    actual fun linkTrickDataToContact(
        nativeContactId: Long,
        shortId: String,
        publicKeyHex: String,
        deviceId: String?
    ): Boolean {
        // iOS implementation not yet available
        return false
    }

    actual fun getContactByShortId(shortId: String): TrickContact? {
        // iOS implementation not yet available
        return null
    }

    actual fun unlinkTrickData(shortId: String): Boolean {
        // iOS implementation not yet available
        return false
    }

    actual fun hasContactsPermission(): Boolean {
        // iOS implementation not yet available
        return false
    }
}
