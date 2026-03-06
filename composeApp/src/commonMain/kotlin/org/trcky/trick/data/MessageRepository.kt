package org.trcky.trick.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.trcky.trick.TrickDatabase
import org.trcky.trick.screens.messaging.MessageType

/**
 * Repository interface for message persistence operations.
 */
interface MessageRepository {
    fun getMessagesByShortIdFlow(shortId: String): Flow<List<PersistedMessage>>
    fun insertMessage(message: PersistedMessage)
    fun updateMessageStatus(id: String, status: MessageStatus)
    fun deleteMessage(id: String)
    fun deleteMessagesByShortId(shortId: String)
}

/**
 * SQLDelight-based implementation of MessageRepository.
 */
class MessageRepositoryImpl(
    private val database: TrickDatabase
) : MessageRepository {

    override fun getMessagesByShortIdFlow(shortId: String): Flow<List<PersistedMessage>> {
        return database.trickDatabaseQueries.selectMessagesByShortId(shortId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun insertMessage(message: PersistedMessage) {
        database.trickDatabaseQueries.insertMessage(
            id = message.id,
            short_id = message.shortId,
            content = message.content,
            type = message.type.name,
            is_sent = if (message.isSent) 1L else 0L,
            is_encrypted = if (message.isEncrypted) 1L else 0L,
            timestamp = message.timestamp,
            image_path = message.imagePath,
            status = message.status.name
        )
    }

    override fun updateMessageStatus(id: String, status: MessageStatus) {
        database.trickDatabaseQueries.updateMessageStatus(
            status = status.name,
            id = id
        )
    }

    override fun deleteMessage(id: String) {
        database.trickDatabaseQueries.deleteMessage(id)
    }

    override fun deleteMessagesByShortId(shortId: String) {
        database.trickDatabaseQueries.deleteMessagesByShortId(shortId)
    }

    private fun org.trcky.trick.Message.toDomain(): PersistedMessage {
        return PersistedMessage(
            id = id,
            shortId = short_id,
            content = content,
            type = try { MessageType.valueOf(type) } catch (_: Exception) { MessageType.TEXT },
            isSent = is_sent != 0L,
            isEncrypted = is_encrypted != 0L,
            timestamp = timestamp,
            imagePath = image_path,
            status = try { MessageStatus.valueOf(status) } catch (_: Exception) { MessageStatus.SENT }
        )
    }
}
