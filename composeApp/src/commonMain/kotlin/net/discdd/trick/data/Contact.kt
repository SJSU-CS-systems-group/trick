package net.discdd.trick.data

/**
 * Domain model for a Contact.
 * Maps to the SQLDelight Contact table.
 */
data class Contact(
    val id: String,
    val shortId: String,
    val displayName: String?,
    val publicKeyHex: String,
    val createdAt: Long,
    val lastMessageAt: Long?,
    val lastMessagePreview: String?
) {
    /**
     * Generate the URL for this contact using the short ID.
     * Format: trcky.org/<shortId>
     */
    fun getUrl(): String {
        return "trcky.org/$shortId"
    }
}

