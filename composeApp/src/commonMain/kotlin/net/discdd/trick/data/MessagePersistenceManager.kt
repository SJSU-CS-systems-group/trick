package net.discdd.trick.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.discdd.trick.TrickDatabase
import net.discdd.trick.contacts.NativeContactsManager
import net.discdd.trick.screens.messaging.MessageType
import net.discdd.trick.util.generateUuid

/**
 * Central class that handles persistence for both sent and received messages.
 * Messages arrive globally via WiFi Aware regardless of which screen is active,
 * so this manager operates independently of any ViewModel.
 */
class MessagePersistenceManager(
    private val database: TrickDatabase,
    private val messageRepository: MessageRepository,
    private val messageMetadataRepository: MessageMetadataRepository,
    private val imageStorage: ImageStorage,
    private val nativeContactsManager: NativeContactsManager,
    private val scope: CoroutineScope
) {
    // Cache peerId -> shortId mapping to avoid repeated lookups
    private val peerIdToShortIdCache = mutableMapOf<String, String>()

    /**
     * Resolve a WiFi Aware peerId to a contact's shortId.
     * 1. Check cache
     * 2. Check if peerId is already a 12-char shortId (legacy routing)
     * 3. Look up contacts matching deviceId
     */
    fun resolveShortId(peerId: String): String? {
        peerIdToShortIdCache[peerId]?.let { return it }

        // If peerId is already a 12-char hex shortId, use it directly
        if (peerId.length == 12 && peerId.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            peerIdToShortIdCache[peerId] = peerId
            return peerId
        }

        // Look up contacts whose deviceId matches peerId
        val contacts = nativeContactsManager.getTrickContacts()
        val match = contacts.firstOrNull { it.deviceId == peerId }
        if (match != null) {
            peerIdToShortIdCache[peerId] = match.shortId
            return match.shortId
        }

        return null
    }

    /**
     * Persist a received text message.
     * @param messageId Unique ID from protobuf (for deduplication)
     */
    fun persistReceivedTextMessage(
        peerId: String,
        content: String,
        isEncrypted: Boolean,
        messageId: String? = null
    ) {
        val shortId = resolveShortId(peerId) ?: return
        val id = messageId ?: generateUuid()
        val timestamp = currentTimeMillis()

        scope.launch {
            database.transaction {
                // Upsert metadata first (FK constraint)
                messageMetadataRepository.upsertMetadata(
                    MessageMetadata(
                        shortId = shortId,
                        lastMessageAt = timestamp,
                        lastMessagePreview = content.take(100)
                    )
                )

                messageRepository.insertMessage(
                    PersistedMessage(
                        id = id,
                        shortId = shortId,
                        content = content,
                        type = MessageType.TEXT,
                        isSent = false,
                        isEncrypted = isEncrypted,
                        timestamp = timestamp,
                        imagePath = null,
                        status = MessageStatus.DELIVERED
                    )
                )
            }
        }
    }

    /**
     * Persist a received image message.
     */
    fun persistReceivedImageMessage(
        peerId: String,
        imageData: ByteArray,
        filename: String,
        isEncrypted: Boolean,
        messageId: String? = null
    ) {
        val shortId = resolveShortId(peerId) ?: return
        val id = messageId ?: generateUuid()
        val timestamp = currentTimeMillis()
        val savedFilename = "${id}_$filename"
        val imagePath = imageStorage.saveImage(imageData, savedFilename)

        scope.launch {
            database.transaction {
                messageMetadataRepository.upsertMetadata(
                    MessageMetadata(
                        shortId = shortId,
                        lastMessageAt = timestamp,
                        lastMessagePreview = "Image"
                    )
                )

                messageRepository.insertMessage(
                    PersistedMessage(
                        id = id,
                        shortId = shortId,
                        content = "[Image]",
                        type = MessageType.IMAGE,
                        isSent = false,
                        isEncrypted = isEncrypted,
                        timestamp = timestamp,
                        imagePath = imagePath,
                        status = MessageStatus.DELIVERED
                    )
                )
            }
        }
    }

    /**
     * Persist a sent text message.
     */
    fun persistSentTextMessage(
        shortId: String,
        content: String,
        isEncrypted: Boolean
    ) {
        val id = generateUuid()
        val timestamp = currentTimeMillis()

        scope.launch {
            database.transaction {
                messageMetadataRepository.upsertMetadata(
                    MessageMetadata(
                        shortId = shortId,
                        lastMessageAt = timestamp,
                        lastMessagePreview = content.take(100)
                    )
                )

                messageRepository.insertMessage(
                    PersistedMessage(
                        id = id,
                        shortId = shortId,
                        content = content,
                        type = MessageType.TEXT,
                        isSent = true,
                        isEncrypted = isEncrypted,
                        timestamp = timestamp,
                        imagePath = null,
                        status = MessageStatus.SENT
                    )
                )
            }
        }
    }

    /**
     * Persist a sent image message.
     */
    fun persistSentImageMessage(
        shortId: String,
        imageData: ByteArray,
        filename: String?,
        isEncrypted: Boolean
    ) {
        val id = generateUuid()
        val timestamp = currentTimeMillis()
        val actualFilename = filename ?: "image_$id"
        val savedFilename = "${id}_$actualFilename"
        val imagePath = imageStorage.saveImage(imageData, savedFilename)

        scope.launch {
            database.transaction {
                messageMetadataRepository.upsertMetadata(
                    MessageMetadata(
                        shortId = shortId,
                        lastMessageAt = timestamp,
                        lastMessagePreview = "Image"
                    )
                )

                messageRepository.insertMessage(
                    PersistedMessage(
                        id = id,
                        shortId = shortId,
                        content = "[Image]",
                        type = MessageType.IMAGE,
                        isSent = true,
                        isEncrypted = isEncrypted,
                        timestamp = timestamp,
                        imagePath = imagePath,
                        status = MessageStatus.SENT
                    )
                )
            }
        }
    }
}
