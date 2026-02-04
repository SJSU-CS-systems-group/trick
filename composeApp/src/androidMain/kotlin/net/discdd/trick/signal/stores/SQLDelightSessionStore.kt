package net.discdd.trick.signal.stores

import net.discdd.trick.TrickDatabase
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore

/**
 * SessionStore backed by SQLDelight.
 * Stores Double Ratchet session state for each peer.
 */
class SQLDelightSessionStore(
    private val database: TrickDatabase
) : SessionStore {

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val record = database.signalSessionQueries
            .selectSession(address.name, address.deviceId.toLong())
            .executeAsOneOrNull()

        return record?.let { SessionRecord(it) } ?: SessionRecord()
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        val now = System.currentTimeMillis()
        database.signalSessionQueries.insertOrReplaceSession(
            address_name = address.name,
            device_id = address.deviceId.toLong(),
            session_record = record.serialize(),
            created_at = now,
            updated_at = now
        )
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        return database.signalSessionQueries
            .containsSession(address.name, address.deviceId.toLong())
            .executeAsOne() > 0
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        database.signalSessionQueries.deleteSession(address.name, address.deviceId.toLong())
    }

    override fun deleteAllSessions(name: String) {
        database.signalSessionQueries.deleteAllSessionsForAddress(name)
    }

    override fun getSubDeviceSessions(name: String): List<Int> {
        return database.signalSessionQueries
            .selectAllSessionsForAddress(name)
            .executeAsList()
            .map { it.toInt() }
    }
}
