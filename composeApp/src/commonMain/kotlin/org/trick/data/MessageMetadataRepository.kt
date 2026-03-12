package org.trick.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.trick.TrickDatabase

/**
 * Domain model for message metadata.
 * Links to native contacts via shortId.
 */
data class MessageMetadata(
    val shortId: String,
    val lastMessageAt: Long?,
    val lastMessagePreview: String?
)

/**
 * Repository interface for message metadata operations.
 */
interface MessageMetadataRepository {
    /**
     * Get all message metadata, ordered by last message time (most recent first).
     */
    fun getAllMetadata(): List<MessageMetadata>

    /**
     * Get all message metadata as a Flow for reactive updates.
     */
    fun getAllMetadataFlow(): Flow<List<MessageMetadata>>

    /**
     * Get metadata for a specific shortId.
     */
    fun getMetadata(shortId: String): MessageMetadata?

    /**
     * Get metadata as a Flow for reactive updates.
     */
    fun getMetadataFlow(shortId: String): Flow<MessageMetadata?>

    /**
     * Insert or update message metadata.
     */
    fun upsertMetadata(metadata: MessageMetadata)

    /**
     * Update message metadata (last message time and preview).
     */
    fun updateMetadata(shortId: String, lastMessageAt: Long?, lastMessagePreview: String?)

    /**
     * Delete metadata for a shortId.
     */
    fun deleteMetadata(shortId: String)
}

/**
 * SQLDelight-based implementation of MessageMetadataRepository.
 */
class MessageMetadataRepositoryImpl(
    private val database: TrickDatabase
) : MessageMetadataRepository {

    override fun getAllMetadata(): List<MessageMetadata> {
        return database.trickDatabaseQueries.selectAllMetadata()
            .executeAsList()
            .map { it.toDomain() }
    }

    override fun getAllMetadataFlow(): Flow<List<MessageMetadata>> {
        return database.trickDatabaseQueries.selectAllMetadata()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun getMetadata(shortId: String): MessageMetadata? {
        return database.trickDatabaseQueries.selectMetadataByShortId(shortId)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override fun getMetadataFlow(shortId: String): Flow<MessageMetadata?> {
        return database.trickDatabaseQueries.selectMetadataByShortId(shortId)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.toDomain() }
    }

    override fun upsertMetadata(metadata: MessageMetadata) {
        database.trickDatabaseQueries.insertMetadata(
            short_id = metadata.shortId,
            last_message_at = metadata.lastMessageAt,
            last_message_preview = metadata.lastMessagePreview
        )
    }

    override fun updateMetadata(shortId: String, lastMessageAt: Long?, lastMessagePreview: String?) {
        database.trickDatabaseQueries.updateMetadata(
            last_message_at = lastMessageAt,
            last_message_preview = lastMessagePreview,
            short_id = shortId
        )
    }

    override fun deleteMetadata(shortId: String) {
        database.trickDatabaseQueries.deleteMetadata(shortId)
    }

    /**
     * Map SQLDelight MessageMetadata to domain MessageMetadata.
     */
    private fun org.trick.MessageMetadata.toDomain(): MessageMetadata {
        return MessageMetadata(
            shortId = short_id,
            lastMessageAt = last_message_at,
            lastMessagePreview = last_message_preview
        )
    }
}
