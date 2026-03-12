package org.trick.data

import org.trick.screens.messaging.MessageType

/**
 * Domain model mapping to the DB Message table.
 */
data class PersistedMessage(
    val id: String,
    val shortId: String,
    val content: String,
    val type: MessageType,
    val isSent: Boolean,
    val isEncrypted: Boolean,
    val timestamp: Long,
    val imagePath: String?,
    val status: MessageStatus
)
