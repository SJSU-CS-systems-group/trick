package net.discdd.trick.data

/**
 * Status of a message in the system
 */
enum class MessageStatus {
    PENDING,    // Message is queued but not yet sent
    SENT,       // Message has been sent
    DELIVERED,  // Message has been delivered to recipient
    FAILED      // Message failed to send
}

