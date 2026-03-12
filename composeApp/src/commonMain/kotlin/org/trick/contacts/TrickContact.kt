package org.trick.contacts

/**
 * Data class representing a contact that has distributed keys with the user.
 * Combines native Android contact information with Trick-specific data.
 */
data class TrickContact(
    val nativeContactId: Long,       // Android's raw_contact_id
    val displayName: String,
    val photoUri: String?,
    val shortId: String,             // 12-char hex from SHA-256 of public key
    val publicKeyHex: String,
    val deviceId: String? = null,    // 64-char hex from device identity (for WiFi Aware matching)
    val lastMessageAt: Long? = null,
    val lastMessagePreview: String? = null
) {
    /**
     * Generate the URL for this contact using the short ID.
     * Format: trcky.org/<shortId>
     */
    fun getUrl(): String {
        return "trcky.org/$shortId"
    }
}
