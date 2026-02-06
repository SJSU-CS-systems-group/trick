package net.discdd.trick.signal.stores

import net.discdd.trick.TrickDatabase
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore

/**
 * PreKeyStore backed by SQLDelight.
 * libsignal calls removePreKey() automatically after consuming a one-time prekey.
 */
class SQLDelightPreKeyStore(
    private val database: TrickDatabase
) : PreKeyStore {

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val record = database.trickDatabaseQueries
            .selectPreKey(preKeyId.toLong())
            .executeAsOneOrNull()
            ?: throw InvalidKeyIdException("PreKey $preKeyId not found")

        return PreKeyRecord(record)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        database.trickDatabaseQueries.insertPreKey(
            prekey_id = preKeyId.toLong(),
            prekey_record = record.serialize()
        )
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return database.trickDatabaseQueries
            .containsPreKey(preKeyId.toLong())
            .executeAsOne() > 0
    }

    /**
     * Called by libsignal after successfully decrypting a PreKeySignalMessage.
     * This is the proper way to handle prekey consumption - let libsignal manage it.
     */
    override fun removePreKey(preKeyId: Int) {
        database.trickDatabaseQueries.deletePreKey(preKeyId.toLong())
    }

    /**
     * Get count of available prekeys for replenishment check.
     */
    fun getCount(): Int {
        return database.trickDatabaseQueries.countPreKeys().executeAsOne().toInt()
    }

    /**
     * Get next prekey ID for generation.
     */
    fun getNextId(): Int {
        val maxId = database.trickDatabaseQueries.selectMaxPreKeyId().executeAsOneOrNull()
        return ((maxId?.max_prekey_id ?: 0L) + 1).toInt()
    }
}
