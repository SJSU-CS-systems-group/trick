package net.discdd.trick.signal.stores

import net.discdd.trick.TrickDatabase
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore

/**
 * IdentityKeyStore backed by SQLDelight.
 * Implements TOFU (Trust On First Use) for peer identity verification.
 *
 * Trust levels:
 * - 0: UNTRUSTED (identity changed, requires user confirmation)
 * - 1: TOFU (first-use trusted)
 * - 2: VERIFIED (manually verified by user)
 */
class SQLDelightIdentityKeyStore(
    private val database: TrickDatabase,
    private val localIdentityKeyPair: IdentityKeyPair,
    private val localRegistrationId: Int
) : IdentityKeyStore {

    companion object {
        const val TRUST_LEVEL_UNTRUSTED = 0L
        const val TRUST_LEVEL_TOFU = 1L
        const val TRUST_LEVEL_VERIFIED = 2L
    }

    override fun getIdentityKeyPair(): IdentityKeyPair = localIdentityKeyPair

    override fun getLocalRegistrationId(): Int = localRegistrationId

    /**
     * Save peer identity. Returns true if the identity changed (key was replaced).
     */
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val existing = database.signalIdentityKeyQueries
            .selectIdentityKey(address.name, address.deviceId.toLong())
            .executeAsOneOrNull()

        val now = System.currentTimeMillis()
        val changed = existing != null && !existing.identity_key.contentEquals(identityKey.serialize())

        database.signalIdentityKeyQueries.insertOrReplaceIdentityKey(
            address_name = address.name,
            device_id = address.deviceId.toLong(),
            identity_key = identityKey.serialize(),
            trust_level = if (changed) TRUST_LEVEL_UNTRUSTED else TRUST_LEVEL_TOFU,
            first_seen_at = existing?.first_seen_at ?: now,
            last_seen_at = now
        )

        return changed
    }

    /**
     * Check if identity is trusted using TOFU policy.
     * Returns true if:
     * - No existing key (first contact, will be trusted on save)
     * - Existing key matches provided key
     */
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        val existing = database.signalIdentityKeyQueries
            .selectIdentityKey(address.name, address.deviceId.toLong())
            .executeAsOneOrNull()

        // TOFU: Trust if no existing key, or if key matches
        return existing == null || existing.identity_key.contentEquals(identityKey.serialize())
    }

    /**
     * Get stored identity key for peer.
     */
    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val existing = database.signalIdentityKeyQueries
            .selectIdentityKey(address.name, address.deviceId.toLong())
            .executeAsOneOrNull()

        return existing?.let { IdentityKey(it.identity_key) }
    }

    /**
     * Update trust level for a peer identity.
     */
    fun updateTrustLevel(address: SignalProtocolAddress, trustLevel: Long) {
        database.signalIdentityKeyQueries.updateIdentityKeyTrust(
            trust_level = trustLevel,
            last_seen_at = System.currentTimeMillis(),
            address_name = address.name,
            device_id = address.deviceId.toLong()
        )
    }

    /**
     * Delete peer identity (for untrusting).
     */
    fun deleteIdentity(address: SignalProtocolAddress) {
        database.signalIdentityKeyQueries.deleteIdentityKey(
            address_name = address.name,
            device_id = address.deviceId.toLong()
        )
    }
}
