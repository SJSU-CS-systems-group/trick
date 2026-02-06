package net.discdd.trick.signal.stores

import net.discdd.trick.TrickDatabase
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore

/**
 * KyberPreKeyStore backed by SQLDelight.
 *
 * This mirrors the classical PreKeyStore implementation but stores the
 * Kyber-specific prekey records in the dedicated SignalKyberPreKey table.
 * We keep a simple "used_at" timestamp instead of deleting rows so we can
 * debug key usage if necessary.
 */
class SQLDelightKyberPreKeyStore(
    private val database: TrickDatabase
) : KyberPreKeyStore {

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val record = database.trickDatabaseQueries
            .selectKyberPreKey(kyberPreKeyId.toLong())
            .executeAsOneOrNull()
            ?: throw InvalidKeyIdException("KyberPreKey $kyberPreKeyId not found")

        return KyberPreKeyRecord(record)
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        val now = System.currentTimeMillis()
        database.trickDatabaseQueries.insertKyberPreKey(
            kyber_prekey_id = kyberPreKeyId.toLong(),
            kyber_prekey_record = record.serialize(),
            created_at = now,
            used_at = null
        )
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
        return database.trickDatabaseQueries
            .containsKyberPreKey(kyberPreKeyId.toLong())
            .executeAsOne() > 0
    }

    /**
     * Helpers for diagnostics / potential future replenishment logic.
     */
    fun getCount(): Int {
        return database.trickDatabaseQueries.countKyberPreKeys().executeAsOne().toInt()
    }

    fun getNextId(): Int {
        val maxId = database.trickDatabaseQueries.selectMaxKyberPreKeyId().executeAsOneOrNull()
        return ((maxId?.max_kyber_prekey_id ?: 0L) + 1).toInt()
    }

    /**
     * Get the ID of the latest (most recently created) Kyber prekey.
     */
    fun getLatestKyberPreKeyId(): Int? {
        val maxId = database.trickDatabaseQueries.selectMaxKyberPreKeyId().executeAsOneOrNull()
        return maxId?.max_kyber_prekey_id?.toInt()
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
        return database.trickDatabaseQueries.selectAllKyberPreKeys()
            .executeAsList()
            .map { KyberPreKeyRecord(it) }
    }

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
        val now = System.currentTimeMillis()
        database.trickDatabaseQueries.markKyberPreKeyUsed(
            used_at = now,
            kyber_prekey_id = kyberPreKeyId.toLong()
        )
    }
}



