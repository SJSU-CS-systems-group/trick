package net.discdd.trick.signal.stores

import net.discdd.trick.TrickDatabase
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore

/**
 * SignedPreKeyStore backed by SQLDelight.
 */
class SQLDelightSignedPreKeyStore(
    private val database: TrickDatabase
) : SignedPreKeyStore {

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val record = database.trickDatabaseQueries
            .selectSignedPreKey(signedPreKeyId.toLong())
            .executeAsOneOrNull()
            ?: throw InvalidKeyIdException("SignedPreKey $signedPreKeyId not found")

        return SignedPreKeyRecord(record)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        return database.trickDatabaseQueries
            .selectAllSignedPreKeys()
            .executeAsList()
            .map { SignedPreKeyRecord(it.signed_prekey_record) }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        database.trickDatabaseQueries.insertSignedPreKey(
            signed_prekey_id = signedPreKeyId.toLong(),
            signed_prekey_record = record.serialize(),
            created_at = System.currentTimeMillis()
        )
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return database.trickDatabaseQueries
            .containsSignedPreKey(signedPreKeyId.toLong())
            .executeAsOne() > 0
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        database.trickDatabaseQueries.deleteSignedPreKey(signedPreKeyId.toLong())
    }

    /**
     * Get the latest signed prekey ID for bundle generation.
     */
    fun getLatestSignedPreKeyId(): Int? {
        return database.trickDatabaseQueries
            .selectLatestSignedPreKeyId()
            .executeAsOneOrNull()
            ?.toInt()
    }
}
