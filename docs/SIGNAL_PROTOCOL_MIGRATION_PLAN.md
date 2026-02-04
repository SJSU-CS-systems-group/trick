# Signal Protocol Migration Plan (Signal-Only)

## Overview
Migrate from one-shot ECC seal/open encryption to full Signal protocol (X3DH + Double Ratchet) using libsignal-rust. **All new messaging uses Signal exclusively** - no runtime HPKE fallback.

## Design Principles

1. **Signal-only for new messages**: All outgoing messages use `encryption_version="signal-v1"`
2. **No HPKE fallback**: If bundle fetch fails on scan, show error - do NOT create contact
3. **Legacy HPKE read-only**: Keep hpke-v1 decryption ONLY for historical messages in local chat history
4. **Strict downgrade prevention**: Reject any incoming hpke-v1 if Signal session exists
5. **TOFU + identity change blocking**: Accept first identity, block on change until user confirms
6. **Reject plaintext over network**: Plaintext messages are errors, not warnings
7. **Let libsignal handle crypto**: No manual signature verification or ciphertext parsing

## Key Design Decisions

### Decision 1: Identity Storage - Single Source of Truth
**Choice: Option B - SignalIdentity table as sole source**

- Store identity key pair fully in `SignalIdentity` SQLDelight table
- Remove identity key storage from `KeyManager` (KeyManager keeps only peer public keys for legacy history decryption)
- Private key encrypted with Android KeyStore master key before storing in DB
- `SignalSessionManager.initialize()` is the single entry point for identity

### Decision 2: shortId Independence
- shortId is an **opaque random identifier**, NOT derived from identity key
- Generated once per device, stored persistently
- Backend maps `shortId → current bundle`
- Identity key rotation does NOT change shortId
- User can regenerate shortId explicitly if desired (new share link)

### Decision 3: Let libsignal Handle Validation & Consumption
- **Signed prekey validation**: Construct `PreKeyBundle` object, let `SessionBuilder.process()` validate internally
- **Prekey consumption**: Implement `PreKeyStore.removePreKey()` in SQLDelight store; libsignal calls it automatically

---

## Current State
- **Crypto**: `ECPublicKey.seal()` / `ECPrivateKey.open()` for one-shot encryption
- **Key exchange**: QR/URL with `{deviceId, publicKeyHex, timestamp, signature, shortId}` - identity only
- **Wire format**: `encryption_version="hpke-v1"`, `encrypted_content` bytes
- **Storage**: KeyManager (SharedPreferences), TrickDatabase.sq (MessageMetadata, Message)

---

## Phase 1: SQLDelight Schema

### New Tables in `TrickDatabase.sq`

```sql
-- ============================================================
-- SIGNAL PROTOCOL TABLES
-- ============================================================

-- Our device's Signal identity (singleton)
-- This is the SINGLE SOURCE OF TRUTH for our identity
CREATE TABLE SignalIdentity (
    id INTEGER NOT NULL PRIMARY KEY DEFAULT 1,
    registration_id INTEGER NOT NULL,
    identity_key_public BLOB NOT NULL,
    identity_key_private_encrypted BLOB NOT NULL,  -- Encrypted with Android KeyStore
    identity_key_private_iv BLOB NOT NULL,
    short_id TEXT NOT NULL,  -- Opaque random identifier, independent of keys
    created_at INTEGER NOT NULL,
    CHECK (id = 1)
);

-- Peer identity keys (TOFU + change detection)
-- Used by IdentityKeyStore implementation
CREATE TABLE SignalIdentityKey (
    address_name TEXT NOT NULL,
    device_id INTEGER NOT NULL,
    identity_key BLOB NOT NULL,
    trust_level INTEGER NOT NULL DEFAULT 1,  -- 0=UNTRUSTED, 1=TOFU, 2=VERIFIED
    first_seen_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    PRIMARY KEY (address_name, device_id)
);

CREATE INDEX SignalIdentityKey_address ON SignalIdentityKey(address_name);

-- Double Ratchet session records
-- Used by SessionStore implementation
CREATE TABLE SignalSession (
    address_name TEXT NOT NULL,
    device_id INTEGER NOT NULL,
    session_record BLOB NOT NULL,  -- libsignal SessionRecord.serialize()
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (address_name, device_id)
);

CREATE INDEX SignalSession_address ON SignalSession(address_name);

-- One-time prekeys
-- Used by PreKeyStore implementation
-- libsignal calls removePreKey() after consumption
CREATE TABLE SignalPreKey (
    prekey_id INTEGER NOT NULL PRIMARY KEY,
    prekey_record BLOB NOT NULL  -- libsignal PreKeyRecord.serialize()
);

-- Signed prekeys (longer-lived)
-- Used by SignedPreKeyStore implementation
CREATE TABLE SignalSignedPreKey (
    signed_prekey_id INTEGER NOT NULL PRIMARY KEY,
    signed_prekey_record BLOB NOT NULL,  -- libsignal SignedPreKeyRecord.serialize()
    created_at INTEGER NOT NULL
);
```

### Queries

```sql
-- SignalIdentity queries (singleton)
selectIdentity:
SELECT * FROM SignalIdentity WHERE id = 1;

insertIdentity:
INSERT INTO SignalIdentity(id, registration_id, identity_key_public, identity_key_private_encrypted, identity_key_private_iv, short_id, created_at)
VALUES (1, ?, ?, ?, ?, ?, ?);

updateIdentityKeys:
UPDATE SignalIdentity
SET identity_key_public = ?, identity_key_private_encrypted = ?, identity_key_private_iv = ?
WHERE id = 1;

-- SignalIdentityKey queries (peer identities)
selectIdentityKey:
SELECT * FROM SignalIdentityKey WHERE address_name = ? AND device_id = ?;

insertOrReplaceIdentityKey:
INSERT OR REPLACE INTO SignalIdentityKey(address_name, device_id, identity_key, trust_level, first_seen_at, last_seen_at)
VALUES (?, ?, ?, ?, ?, ?);

updateIdentityKeyTrust:
UPDATE SignalIdentityKey SET trust_level = ?, last_seen_at = ? WHERE address_name = ? AND device_id = ?;

deleteIdentityKey:
DELETE FROM SignalIdentityKey WHERE address_name = ? AND device_id = ?;

-- SignalSession queries
selectSession:
SELECT session_record FROM SignalSession WHERE address_name = ? AND device_id = ?;

selectAllSessionsForAddress:
SELECT device_id FROM SignalSession WHERE address_name = ?;

containsSession:
SELECT COUNT(*) FROM SignalSession WHERE address_name = ? AND device_id = ?;

insertOrReplaceSession:
INSERT OR REPLACE INTO SignalSession(address_name, device_id, session_record, created_at, updated_at)
VALUES (?, ?, ?, ?, ?);

deleteSession:
DELETE FROM SignalSession WHERE address_name = ? AND device_id = ?;

deleteAllSessionsForAddress:
DELETE FROM SignalSession WHERE address_name = ?;

-- SignalPreKey queries
selectPreKey:
SELECT prekey_record FROM SignalPreKey WHERE prekey_id = ?;

containsPreKey:
SELECT COUNT(*) FROM SignalPreKey WHERE prekey_id = ?;

insertPreKey:
INSERT INTO SignalPreKey(prekey_id, prekey_record) VALUES (?, ?);

deletePreKey:
DELETE FROM SignalPreKey WHERE prekey_id = ?;

countPreKeys:
SELECT COUNT(*) FROM SignalPreKey;

selectMaxPreKeyId:
SELECT MAX(prekey_id) FROM SignalPreKey;

-- SignalSignedPreKey queries
selectSignedPreKey:
SELECT signed_prekey_record FROM SignalSignedPreKey WHERE signed_prekey_id = ?;

containsSignedPreKey:
SELECT COUNT(*) FROM SignalSignedPreKey WHERE signed_prekey_id = ?;

insertSignedPreKey:
INSERT INTO SignalSignedPreKey(signed_prekey_id, signed_prekey_record, created_at) VALUES (?, ?, ?);

deleteSignedPreKey:
DELETE FROM SignalSignedPreKey WHERE signed_prekey_id = ?;

selectAllSignedPreKeys:
SELECT * FROM SignalSignedPreKey ORDER BY created_at DESC;

selectLatestSignedPreKeyId:
SELECT signed_prekey_id FROM SignalSignedPreKey ORDER BY created_at DESC LIMIT 1;
```

**File**: `/composeApp/src/commonMain/sqldelight/net/discdd/trick/TrickDatabase.sq`

---

## Phase 2: SignalSessionManager API

### Common Interface (expect/actual)

**File**: `/composeApp/src/commonMain/kotlin/net/discdd/trick/signal/SignalSessionManager.kt`

```kotlin
package net.discdd.trick.signal

/**
 * Encryption result from Signal protocol
 */
data class SignalEncryptResult(
    val ciphertext: ByteArray,
    val messageType: Int,  // CiphertextMessage.WHISPER_TYPE or PREKEY_TYPE
    val registrationId: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalEncryptResult) return false
        return ciphertext.contentEquals(other.ciphertext) &&
               messageType == other.messageType &&
               registrationId == other.registrationId
    }
    override fun hashCode(): Int = ciphertext.contentHashCode()
}

/**
 * Decryption result from Signal protocol
 */
data class SignalDecryptResult(
    val plaintext: ByteArray,
    val senderIdentityKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalDecryptResult) return false
        return plaintext.contentEquals(other.plaintext)
    }
    override fun hashCode(): Int = plaintext.contentHashCode()
}

/**
 * PreKey bundle data for X3DH key agreement.
 * Matches libsignal PreKeyBundle constructor parameters.
 */
data class PreKeyBundleData(
    val registrationId: Int,
    val deviceId: Int,
    val preKeyId: Int?,           // null if no one-time prekey
    val preKeyPublic: ByteArray?, // null if no one-time prekey
    val signedPreKeyId: Int,
    val signedPreKeyPublic: ByteArray,
    val signedPreKeySignature: ByteArray,
    val identityKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreKeyBundleData) return false
        return registrationId == other.registrationId &&
               deviceId == other.deviceId &&
               preKeyId == other.preKeyId
    }
    override fun hashCode(): Int = registrationId.hashCode()
}

/**
 * Signal operation errors
 */
sealed class SignalError : Exception() {
    data class UntrustedIdentity(val peerId: String, val newKey: ByteArray) : SignalError() {
        override val message = "Identity key changed for $peerId - requires user confirmation"
    }
    data class InvalidMessage(val reason: String) : SignalError() {
        override val message = "Invalid message: $reason"
    }
    data class NoSession(val peerId: String) : SignalError() {
        override val message = "No Signal session exists for $peerId"
    }
    data class SessionBuildFailed(val peerId: String, val cause: String) : SignalError() {
        override val message = "Failed to build session with $peerId: $cause"
    }
    data class BundleFetchFailed(val shortId: String, val cause: String) : SignalError() {
        override val message = "Failed to fetch prekey bundle for $shortId: $cause"
    }
    data class DowngradeAttempt(val peerId: String) : SignalError() {
        override val message = "Rejected encryption downgrade from $peerId"
    }
    data class PlaintextRejected(val peerId: String) : SignalError() {
        override val message = "Rejected plaintext message from $peerId - encryption required"
    }
}

/**
 * Main Signal protocol manager.
 * ALL encryption/decryption for network messages goes through this class.
 * This is the SINGLE SOURCE OF TRUTH for our Signal identity.
 */
expect class SignalSessionManager {
    /**
     * Initialize Signal identity.
     * - Loads existing identity from SignalIdentity table, OR
     * - Generates new identity, registration ID, and shortId if none exists
     * - Generates initial prekeys if needed
     * Must be called on app startup before any other operations.
     */
    suspend fun initialize()

    /**
     * Get our device's shortId (opaque identifier for sharing).
     * Independent of identity key - does not change on key rotation.
     */
    fun getShortId(): String

    /** Check if Signal session exists with peer */
    fun hasSession(peerId: String, deviceId: Int = 1): Boolean

    /**
     * Build session from PreKeyBundle using X3DH.
     * - Constructs libsignal PreKeyBundle and calls SessionBuilder.process()
     * - libsignal internally validates signed prekey signature
     * - Applies TOFU for peer identity
     * @throws SessionBuildFailed if libsignal rejects the bundle
     * @throws UntrustedIdentity if peer identity changed (requires confirmIdentityChange)
     */
    suspend fun buildSessionFromPreKeyBundle(peerId: String, deviceId: Int = 1, bundle: PreKeyBundleData)

    /**
     * Encrypt message using Signal protocol.
     * @throws NoSession if no session exists
     */
    suspend fun encryptMessage(peerId: String, deviceId: Int = 1, plaintext: ByteArray): SignalEncryptResult

    /**
     * Decrypt Signal protocol message.
     * - libsignal detects PreKeySignalMessage vs SignalMessage internally
     * - libsignal calls PreKeyStore.removePreKey() automatically on consumption
     * @throws InvalidMessage if decryption fails
     * @throws UntrustedIdentity if identity changed during decryption
     */
    suspend fun decryptMessage(senderId: String, deviceId: Int = 1, ciphertext: ByteArray): SignalDecryptResult

    /**
     * Generate PreKeyBundle for upload to trcky.org.
     * Uses current signed prekey and an available one-time prekey.
     */
    suspend fun generatePreKeyBundle(): PreKeyBundleData

    /** Get local registration ID */
    fun getLocalRegistrationId(): Int

    /** Get our public identity key (serialized) */
    fun getIdentityPublicKey(): ByteArray

    /** Delete session (for re-keying or untrust) */
    suspend fun deleteSession(peerId: String, deviceId: Int = 1)

    /** Get count of available one-time prekeys */
    fun getAvailablePreKeyCount(): Int

    /**
     * Replenish prekeys if count < threshold.
     * Also uploads new bundle to trcky.org.
     * Called on app startup and after session creation.
     */
    suspend fun replenishPreKeysIfNeeded(threshold: Int = 20, generateCount: Int = 100)

    /**
     * Confirm identity change after UntrustedIdentity error.
     * Deletes old session, updates stored identity, allows retry.
     */
    suspend fun confirmIdentityChange(peerId: String, deviceId: Int = 1, newIdentityKey: ByteArray)
}
```

### Android Implementation

**File**: `/composeApp/src/androidMain/kotlin/net/discdd/trick/signal/SignalSessionManager.android.kt`

Key implementation details:

```kotlin
actual class SignalSessionManager(
    private val context: Context,
    private val database: TrickDatabase,
    private val preKeyBundleResolver: PreKeyBundleResolver
) {
    private val mutex = Mutex()

    // libsignal stores backed by SQLDelight
    private lateinit var identityKeyStore: SQLDelightIdentityKeyStore
    private lateinit var sessionStore: SQLDelightSessionStore
    private lateinit var preKeyStore: SQLDelightPreKeyStore
    private lateinit var signedPreKeyStore: SQLDelightSignedPreKeyStore

    // Our identity (loaded from SignalIdentity table)
    private lateinit var identityKeyPair: IdentityKeyPair
    private var registrationId: Int = 0
    private lateinit var shortId: String

    actual suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = database.signalIdentityQueries.selectIdentity().executeAsOneOrNull()

            if (existing != null) {
                // Load existing identity
                registrationId = existing.registration_id.toInt()
                shortId = existing.short_id
                identityKeyPair = decryptIdentityKeyPair(
                    existing.identity_key_public,
                    existing.identity_key_private_encrypted,
                    existing.identity_key_private_iv
                )
            } else {
                // Generate new identity
                registrationId = KeyHelper.generateRegistrationId(false)
                identityKeyPair = IdentityKeyPair.generate()
                shortId = generateRandomShortId()  // Opaque random, NOT derived from key

                val (encryptedPrivate, iv) = encryptPrivateKey(identityKeyPair.privateKey)
                database.signalIdentityQueries.insertIdentity(
                    registration_id = registrationId.toLong(),
                    identity_key_public = identityKeyPair.publicKey.serialize(),
                    identity_key_private_encrypted = encryptedPrivate,
                    identity_key_private_iv = iv,
                    short_id = shortId,
                    created_at = System.currentTimeMillis()
                )

                // Generate initial prekeys
                generateInitialPreKeys()
            }

            // Initialize stores
            identityKeyStore = SQLDelightIdentityKeyStore(database, identityKeyPair, registrationId)
            sessionStore = SQLDelightSessionStore(database)
            preKeyStore = SQLDelightPreKeyStore(database)
            signedPreKeyStore = SQLDelightSignedPreKeyStore(database)
        }
    }

    actual fun getShortId(): String = shortId

    actual suspend fun buildSessionFromPreKeyBundle(
        peerId: String,
        deviceId: Int,
        bundle: PreKeyBundleData
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val address = SignalProtocolAddress(peerId, deviceId)

            // Check for identity change (TOFU)
            val existingIdentity = identityKeyStore.getIdentity(address)
            val newIdentityKey = IdentityKey(bundle.identityKey)

            if (existingIdentity != null && existingIdentity != newIdentityKey) {
                throw SignalError.UntrustedIdentity(peerId, bundle.identityKey)
            }

            try {
                // Construct libsignal PreKeyBundle - let libsignal validate signature
                val preKeyBundle = if (bundle.preKeyId != null && bundle.preKeyPublic != null) {
                    PreKeyBundle(
                        bundle.registrationId,
                        bundle.deviceId,
                        bundle.preKeyId,
                        ECPublicKey(bundle.preKeyPublic),
                        bundle.signedPreKeyId,
                        ECPublicKey(bundle.signedPreKeyPublic),
                        bundle.signedPreKeySignature,
                        newIdentityKey
                    )
                } else {
                    // No one-time prekey available
                    PreKeyBundle(
                        bundle.registrationId,
                        bundle.deviceId,
                        -1,  // No prekey
                        null,
                        bundle.signedPreKeyId,
                        ECPublicKey(bundle.signedPreKeyPublic),
                        bundle.signedPreKeySignature,
                        newIdentityKey
                    )
                }

                // SessionBuilder.process() validates signed prekey signature internally
                val sessionBuilder = SessionBuilder(sessionStore, preKeyStore, signedPreKeyStore, identityKeyStore, address)
                sessionBuilder.process(preKeyBundle)

                // Store peer identity (TOFU - first time)
                if (existingIdentity == null) {
                    identityKeyStore.saveIdentity(address, newIdentityKey)
                }

            } catch (e: InvalidKeyException) {
                throw SignalError.SessionBuildFailed(peerId, "Invalid key: ${e.message}")
            } catch (e: UntrustedIdentityException) {
                throw SignalError.UntrustedIdentity(peerId, bundle.identityKey)
            }
        }
    }

    actual suspend fun decryptMessage(
        senderId: String,
        deviceId: Int,
        ciphertext: ByteArray
    ): SignalDecryptResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val address = SignalProtocolAddress(senderId, deviceId)
            val sessionCipher = SessionCipher(sessionStore, preKeyStore, signedPreKeyStore, identityKeyStore, address)

            try {
                // libsignal detects message type from ciphertext format
                // libsignal calls preKeyStore.removePreKey() automatically for PreKeySignalMessage
                val plaintext = sessionCipher.decrypt(SignalMessage(ciphertext))

                val senderIdentity = identityKeyStore.getIdentity(address)
                    ?: throw SignalError.InvalidMessage("No identity for sender")

                SignalDecryptResult(
                    plaintext = plaintext,
                    senderIdentityKey = senderIdentity.serialize()
                )
            } catch (e: InvalidMessageException) {
                // Try as PreKeySignalMessage
                try {
                    val plaintext = sessionCipher.decrypt(PreKeySignalMessage(ciphertext))
                    val senderIdentity = identityKeyStore.getIdentity(address)
                        ?: throw SignalError.InvalidMessage("No identity for sender")

                    SignalDecryptResult(
                        plaintext = plaintext,
                        senderIdentityKey = senderIdentity.serialize()
                    )
                } catch (e2: Exception) {
                    throw SignalError.InvalidMessage(e2.message ?: "Decryption failed")
                }
            } catch (e: DuplicateMessageException) {
                throw SignalError.InvalidMessage("Duplicate message")
            } catch (e: UntrustedIdentityException) {
                val newKey = identityKeyStore.getIdentity(address)?.serialize() ?: ByteArray(0)
                throw SignalError.UntrustedIdentity(senderId, newKey)
            }
        }
    }

    // Helper: Generate opaque random shortId (NOT derived from identity)
    private fun generateRandomShortId(): String {
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        return bytes.toHexString().lowercase()  // 12 hex chars
    }
}
```

### iOS Stub

**File**: `/composeApp/src/iosMain/kotlin/net/discdd/trick/signal/SignalSessionManager.ios.kt`

All methods throw `NotImplementedError("iOS Signal protocol not yet implemented")`

---

## Phase 3: Store Implementations (Android)

### SQLDelightPreKeyStore (with proper removePreKey)

**File**: `/composeApp/src/androidMain/kotlin/net/discdd/trick/signal/stores/SQLDelightPreKeyStore.kt`

```kotlin
package net.discdd.trick.signal.stores

import net.discdd.trick.TrickDatabase
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
        val record = database.signalPreKeyQueries
            .selectPreKey(preKeyId.toLong())
            .executeAsOneOrNull()
            ?: throw InvalidKeyIdException("PreKey $preKeyId not found")

        return PreKeyRecord(record)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        database.signalPreKeyQueries.insertPreKey(
            prekey_id = preKeyId.toLong(),
            prekey_record = record.serialize()
        )
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return database.signalPreKeyQueries
            .containsPreKey(preKeyId.toLong())
            .executeAsOne() > 0
    }

    /**
     * Called by libsignal after successfully decrypting a PreKeySignalMessage.
     * This is the proper way to handle prekey consumption - let libsignal manage it.
     */
    override fun removePreKey(preKeyId: Int) {
        database.signalPreKeyQueries.deletePreKey(preKeyId.toLong())
    }

    // Helper for replenishment check
    fun getCount(): Int {
        return database.signalPreKeyQueries.countPreKeys().executeAsOne().toInt()
    }

    fun getNextId(): Int {
        val maxId = database.signalPreKeyQueries.selectMaxPreKeyId().executeAsOneOrNull()
        return (maxId?.toInt() ?: 0) + 1
    }
}
```

### SQLDelightSessionStore

**File**: `/composeApp/src/androidMain/kotlin/net/discdd/trick/signal/stores/SQLDelightSessionStore.kt`

```kotlin
class SQLDelightSessionStore(private val database: TrickDatabase) : SessionStore {

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
```

### SQLDelightIdentityKeyStore

**File**: `/composeApp/src/androidMain/kotlin/net/discdd/trick/signal/stores/SQLDelightIdentityKeyStore.kt`

```kotlin
class SQLDelightIdentityKeyStore(
    private val database: TrickDatabase,
    private val localIdentityKeyPair: IdentityKeyPair,
    private val localRegistrationId: Int
) : IdentityKeyStore {

    override fun getIdentityKeyPair(): IdentityKeyPair = localIdentityKeyPair

    override fun getLocalRegistrationId(): Int = localRegistrationId

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
            trust_level = if (changed) 0L else 1L,  // UNTRUSTED if changed, TOFU if new
            first_seen_at = existing?.first_seen_at ?: now,
            last_seen_at = now
        )

        return changed
    }

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

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val existing = database.signalIdentityKeyQueries
            .selectIdentityKey(address.name, address.deviceId.toLong())
            .executeAsOneOrNull()

        return existing?.let { IdentityKey(it.identity_key) }
    }
}
```

### SQLDelightSignedPreKeyStore

**File**: `/composeApp/src/androidMain/kotlin/net/discdd/trick/signal/stores/SQLDelightSignedPreKeyStore.kt`

```kotlin
class SQLDelightSignedPreKeyStore(
    private val database: TrickDatabase
) : SignedPreKeyStore {

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val record = database.signalSignedPreKeyQueries
            .selectSignedPreKey(signedPreKeyId.toLong())
            .executeAsOneOrNull()
            ?: throw InvalidKeyIdException("SignedPreKey $signedPreKeyId not found")

        return SignedPreKeyRecord(record)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        return database.signalSignedPreKeyQueries
            .selectAllSignedPreKeys()
            .executeAsList()
            .map { SignedPreKeyRecord(it.signed_prekey_record) }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        database.signalSignedPreKeyQueries.insertSignedPreKey(
            signed_prekey_id = signedPreKeyId.toLong(),
            signed_prekey_record = record.serialize(),
            created_at = System.currentTimeMillis()
        )
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return database.signalSignedPreKeyQueries
            .containsSignedPreKey(signedPreKeyId.toLong())
            .executeAsOne() > 0
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        database.signalSignedPreKeyQueries.deleteSignedPreKey(signedPreKeyId.toLong())
    }
}
```

---

## Phase 4: PreKey Bundle Serialization

**File**: `/composeApp/src/commonMain/kotlin/net/discdd/trick/signal/PreKeyBundleSerialization.kt`

```kotlin
@Serializable
data class PreKeyBundleJson(
    val version: Int = 1,
    val registrationId: Int,
    val deviceId: Int,
    val preKeyId: Int?,              // null if exhausted
    val preKeyPublic: String?,       // Base64, null if exhausted
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,  // Base64
    val signedPreKeySignature: String, // Base64
    val identityKey: String,         // Base64
    val timestamp: Long
)

object PreKeyBundleSerialization {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun serialize(bundle: PreKeyBundleData, timestamp: Long = currentTimeMillis()): String {
        val jsonBundle = PreKeyBundleJson(
            version = 1,
            registrationId = bundle.registrationId,
            deviceId = bundle.deviceId,
            preKeyId = bundle.preKeyId,
            preKeyPublic = bundle.preKeyPublic?.let { Base64.encode(it) },
            signedPreKeyId = bundle.signedPreKeyId,
            signedPreKeyPublic = Base64.encode(bundle.signedPreKeyPublic),
            signedPreKeySignature = Base64.encode(bundle.signedPreKeySignature),
            identityKey = Base64.encode(bundle.identityKey),
            timestamp = timestamp
        )
        return json.encodeToString(jsonBundle)
    }

    fun deserialize(jsonString: String): PreKeyBundleData {
        val parsed = json.decodeFromString<PreKeyBundleJson>(jsonString)
        require(parsed.version == 1) { "Unsupported bundle version: ${parsed.version}" }

        return PreKeyBundleData(
            registrationId = parsed.registrationId,
            deviceId = parsed.deviceId,
            preKeyId = parsed.preKeyId,
            preKeyPublic = parsed.preKeyPublic?.let { Base64.decode(it) },
            signedPreKeyId = parsed.signedPreKeyId,
            signedPreKeyPublic = Base64.decode(parsed.signedPreKeyPublic),
            signedPreKeySignature = Base64.decode(parsed.signedPreKeySignature),
            identityKey = Base64.decode(parsed.identityKey)
        )
    }
}
```

---

## Phase 5: trcky.org API Integration

### Endpoints (backend work needed)

```
POST /api/bundles/{shortId}   - Upload/update prekey bundle
GET  /api/bundles/{shortId}   - Fetch bundle (404 if none)
```

**Note**: shortId is opaque and independent of identity key. Backend stores latest bundle per shortId.

### Resolver Interface

**File**: `/composeApp/src/commonMain/kotlin/net/discdd/trick/signal/PreKeyBundleResolver.kt`

```kotlin
interface PreKeyBundleResolver {
    /**
     * Fetch bundle by shortId.
     * @throws SignalError.BundleFetchFailed on 404 or network error
     */
    suspend fun fetchBundleByShortId(shortId: String): PreKeyBundleData

    /**
     * Upload bundle to trcky.org.
     * @return true if success
     */
    suspend fun uploadBundle(shortId: String, bundle: PreKeyBundleData): Boolean
}
```

**Android Implementation**: `/composeApp/src/androidMain/kotlin/net/discdd/trick/signal/TrckyOrgPreKeyBundleResolver.kt`

```kotlin
class TrckyOrgPreKeyBundleResolver(
    private val httpClient: HttpClient = HttpClient(OkHttp)
) : PreKeyBundleResolver {

    override suspend fun fetchBundleByShortId(shortId: String): PreKeyBundleData {
        return try {
            val response = httpClient.get("$TRCKY_ORG_BASE_URL/api/bundles/$shortId")
            when (response.status) {
                HttpStatusCode.OK -> {
                    val json = response.bodyAsText()
                    PreKeyBundleSerialization.deserialize(json)
                }
                HttpStatusCode.NotFound -> {
                    throw SignalError.BundleFetchFailed(shortId, "Bundle not found - contact may need to refresh QR")
                }
                else -> {
                    throw SignalError.BundleFetchFailed(shortId, "HTTP ${response.status}")
                }
            }
        } catch (e: SignalError) {
            throw e
        } catch (e: Exception) {
            throw SignalError.BundleFetchFailed(shortId, e.message ?: "Network error")
        }
    }

    override suspend fun uploadBundle(shortId: String, bundle: PreKeyBundleData): Boolean {
        return try {
            val json = PreKeyBundleSerialization.serialize(bundle)
            val response = httpClient.post("$TRCKY_ORG_BASE_URL/api/bundles/$shortId") {
                contentType(ContentType.Application.Json)
                setBody(json)
            }
            response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created
        } catch (e: Exception) {
            Log.e(TAG, "Bundle upload failed: ${e.message}")
            false
        }
    }
}
```

---

## Phase 6: Wire Format Updates

### Proto Changes

**File**: `/composeApp/src/commonMain/proto/message.proto`

```protobuf
message ChatMessage {
  string message_id = 1;
  int64 timestamp = 2;
  string sender_id = 3;

  oneof content {
    TextContent text_content = 4;
    PhotoContent photo_content = 5;
  }

  optional bytes encrypted_content = 6;
  optional string encryption_version = 7;   // "signal-v1" for all new messages
  optional bytes sender_public_key = 8;     // Deprecated, kept for legacy

  // Signal protocol metadata (informational)
  optional int32 message_type = 9;          // 2=Whisper, 3=PreKey
  optional int32 registration_id = 10;
  optional int32 sender_device_id = 11;     // Default 1
}
```

### Encryption Versions
- `"signal-v1"` - **All new outgoing messages** (required)
- `"hpke-v1"` - **Legacy read-only** (only for local chat history decryption)

---

## Phase 7: Key Exchange Flow Updates

### QR Generation

**File**: `/composeApp/src/commonMain/kotlin/net/discdd/trick/security/QRKeyExchange.kt`

```kotlin
/**
 * Generate QR payload with Signal prekey bundle upload.
 * shortId is opaque and stored in SignalIdentity - NOT derived from keys.
 */
suspend fun generateQRPayloadWithBundle(
    signalSessionManager: SignalSessionManager,
    preKeyBundleResolver: PreKeyBundleResolver,
    deviceId: String
): KeyExchangeQRResult {
    // 1. Ensure initialized (loads/creates identity and shortId)
    signalSessionManager.initialize()

    // 2. Replenish prekeys if needed
    signalSessionManager.replenishPreKeysIfNeeded()

    // 3. Get our shortId (opaque, independent of identity)
    val shortId = signalSessionManager.getShortId()

    // 4. Generate and upload prekey bundle
    val bundle = signalSessionManager.generatePreKeyBundle()
    val uploadSuccess = preKeyBundleResolver.uploadBundle(shortId, bundle)
    if (!uploadSuccess) {
        throw Exception("Failed to upload prekey bundle")
    }

    // 5. Generate QR payload (identity for verification, shortId for bundle lookup)
    val identityKeyHex = signalSessionManager.getIdentityPublicKey().toHexString()
    val timestamp = currentTimeMillis()
    val dataToSign = "$deviceId:$identityKeyHex:$timestamp:$shortId".encodeToByteArray()
    val signature = signWithIdentityKey(signalSessionManager, dataToSign)

    val payload = KeyExchangePayload(
        deviceId = deviceId,
        publicKeyHex = identityKeyHex,
        timestamp = timestamp,
        signatureHex = signature.toHexString(),
        shortId = shortId
    )

    return KeyExchangeQRResult(Json.encodeToString(payload), shortId)
}
```

### QR Scanning (Signal-Only, No Fallback)

```kotlin
/**
 * Process scanned QR/shortId.
 * Fetches bundle and builds session - NO fallback to legacy.
 */
suspend fun processScannedQR(
    shortId: String,
    signalSessionManager: SignalSessionManager,
    preKeyBundleResolver: PreKeyBundleResolver
): Result<Contact> {
    // 1. Fetch bundle (throws BundleFetchFailed if not found)
    val bundle: PreKeyBundleData
    try {
        bundle = preKeyBundleResolver.fetchBundleByShortId(shortId)
    } catch (e: SignalError.BundleFetchFailed) {
        // NO FALLBACK - show error to user
        return Result.failure(e)
    }

    // 2. Build Signal session (libsignal validates bundle internally)
    try {
        signalSessionManager.buildSessionFromPreKeyBundle(
            peerId = shortId,
            deviceId = bundle.deviceId,
            bundle = bundle
        )
    } catch (e: SignalError.UntrustedIdentity) {
        // Identity changed - requires user confirmation
        return Result.failure(e)
    } catch (e: SignalError.SessionBuildFailed) {
        // Invalid bundle (libsignal rejected it)
        return Result.failure(e)
    }

    // 3. Replenish our prekeys (we may have been scanned too)
    signalSessionManager.replenishPreKeysIfNeeded()

    // 4. Create contact only after successful session
    return Result.success(createContact(shortId, bundle.identityKey))
}
```

**Error handling**: Show user: "Could not establish secure connection. Ask your contact to refresh their QR code."

---

## Phase 8: AndroidWifiAwareManager Updates (Signal-Only)

**File**: `/composeApp/src/androidMain/kotlin/net/discdd/trick/screens/messaging/AndroidWifiAwareManager.kt`

### Remove All HPKE Encryption Calls

Remove all direct calls to:
- `libSignalManager.encrypt(publicKey, data)`
- `libSignalManager.decrypt(privateKey, ciphertext)`

### Sending Messages (Signal-Only)

```kotlin
fun sendMessageToPeer(message: String, peerId: String) {
    scope.launch(Dispatchers.IO) {
        val connection = connectionPool.getConnection(peerId) ?: run {
            notifyError("Not connected to peer")
            return@launch
        }

        try {
            // SIGNAL-ONLY: Require session
            if (!signalSessionManager.hasSession(peerId)) {
                throw SignalError.NoSession(peerId)
            }

            // Encrypt with Signal
            val textContent = TextContent(text = message)
            val contentBytes = byteArrayOf(CONTENT_TYPE_TEXT.toByte()) + textContent.encode()
            val result = signalSessionManager.encryptMessage(peerId, 1, contentBytes)

            val chatMessage = ChatMessage(
                message_id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                sender_id = localDeviceId,
                encrypted_content = result.ciphertext.toByteString(),
                encryption_version = "signal-v1",
                message_type = result.messageType,
                registration_id = signalSessionManager.getLocalRegistrationId(),
                sender_device_id = 1
            )

            // Send
            val outputStream = connection.outputStream!!
            val messageBytes = chatMessage.encode()
            outputStream.writeInt(messageBytes.size)
            outputStream.write(messageBytes)
            outputStream.flush()

        } catch (e: SignalError.NoSession) {
            Log.e(TAG, "No Signal session for $peerId")
            withContext(Dispatchers.Main) {
                notifyError("Secure session not established. Exchange QR codes first.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send: ${e.message}")
            handleConnectionLost(peerId)
        }
    }
}
```

### Receiving Messages (Signal-Only, Reject Plaintext)

```kotlin
private fun handleReceivedMessage(chatMessage: ChatMessage, peerId: String) {
    val decryptedMessage = when {
        // REJECT PLAINTEXT - encryption is required
        chatMessage.encrypted_content == null -> {
            Log.e(TAG, "REJECTED: Plaintext message from $peerId - encryption required")
            chatMessage.copy(text_content = TextContent("[Rejected: unencrypted message]"))
        }

        // SIGNAL-V1: Normal path
        chatMessage.encryption_version == "signal-v1" -> {
            try {
                val result = signalSessionManager.decryptMessage(
                    senderId = peerId,
                    deviceId = chatMessage.sender_device_id ?: 1,
                    ciphertext = chatMessage.encrypted_content.toByteArray()
                )
                decodeContent(result.plaintext, chatMessage)
            } catch (e: SignalError.UntrustedIdentity) {
                Log.e(TAG, "Identity changed for $peerId")
                showIdentityChangedWarning(peerId, e.newKey)
                chatMessage.copy(text_content = TextContent("[Security: Identity changed - verify contact]"))
            } catch (e: SignalError.InvalidMessage) {
                Log.e(TAG, "Decryption failed: ${e.reason}")
                chatMessage.copy(text_content = TextContent("[Decryption failed]"))
            }
        }

        // HPKE-V1: REJECT as downgrade attempt
        chatMessage.encryption_version == "hpke-v1" -> {
            Log.e(TAG, "DOWNGRADE REJECTED: hpke-v1 from $peerId over network")
            chatMessage.copy(text_content = TextContent("[Rejected: encryption downgrade]"))
        }

        else -> {
            Log.e(TAG, "Unknown encryption version: ${chatMessage.encryption_version}")
            chatMessage.copy(text_content = TextContent("[Unknown encryption]"))
        }
    }

    withContext(Dispatchers.Main) { messageCallback?.invoke(decryptedMessage, peerId) }
}
```

### Legacy HPKE Decryption (Local History Only)

**File**: `/composeApp/src/androidMain/kotlin/net/discdd/trick/data/LegacyMessageDecryptor.kt`

```kotlin
/**
 * Legacy decryptor for reading old hpke-v1 messages from local database ONLY.
 * NOT used for network messages.
 */
class LegacyMessageDecryptor(
    private val keyManager: KeyManager,  // Kept for legacy private key access
    private val libSignalManager: LibSignalManager
) {
    fun decryptLegacyMessage(encryptedContent: ByteArray): ByteArray? {
        val myKeyPair = keyManager.getIdentityKeyPair() ?: return null
        return try {
            libSignalManager.decrypt(myKeyPair.privateKey, encryptedContent)
        } catch (e: Exception) {
            Log.e(TAG, "Legacy decryption failed: ${e.message}")
            null
        }
    }
}
```

---

## Phase 9: Prekey Hygiene

### Replenishment (no manual parsing)

```kotlin
suspend fun replenishPreKeysIfNeeded(threshold: Int = 20, generateCount: Int = 100) {
    val availableCount = preKeyStore.getCount()

    if (availableCount < threshold) {
        Log.d(TAG, "Prekey count ($availableCount) < $threshold, replenishing...")

        // Generate new prekeys
        val startId = preKeyStore.getNextId()
        val newPreKeys = KeyHelper.generatePreKeys(startId, generateCount)
        newPreKeys.forEach { preKey ->
            preKeyStore.storePreKey(preKey.id, preKey)
        }

        // Upload new bundle to trcky.org
        val bundle = generatePreKeyBundle()
        preKeyBundleResolver.uploadBundle(shortId, bundle)

        Log.d(TAG, "Replenished ${newPreKeys.size} prekeys")
    }
}
```

**libsignal handles consumption** - `PreKeyStore.removePreKey()` called automatically after PreKeySignalMessage decryption.

---

## Phase 10: Identity Change Handling (TOFU)

### Confirm Identity Change

```kotlin
suspend fun confirmIdentityChange(peerId: String, deviceId: Int, newIdentityKey: ByteArray) {
    mutex.withLock {
        val address = SignalProtocolAddress(peerId, deviceId)

        // Delete old session
        sessionStore.deleteSession(address)

        // Update identity with new key, mark as re-trusted
        val existingRecord = database.signalIdentityKeyQueries
            .selectIdentityKey(peerId, deviceId.toLong())
            .executeAsOneOrNull()

        database.signalIdentityKeyQueries.insertOrReplaceIdentityKey(
            address_name = peerId,
            device_id = deviceId.toLong(),
            identity_key = newIdentityKey,
            trust_level = 1,  // Re-trusted after confirmation
            first_seen_at = existingRecord?.first_seen_at ?: System.currentTimeMillis(),
            last_seen_at = System.currentTimeMillis()
        )
    }
    // Caller should now retry buildSessionFromPreKeyBundle
}
```

---

## Implementation Order

1. **SQLDelight schema** - Add tables, queries, migration
2. **Store implementations** - 4 SQLDelight wrappers (proper removePreKey)
3. **SignalSessionManager** - Android implementation (let libsignal validate)
4. **PreKey serialization** - JSON format
5. **trcky.org resolver** - Fetch/upload implementation
6. **Proto update** - Add new fields
7. **QR flow** - shortId from DB, bundle upload/fetch
8. **AndroidWifiAwareManager** - Signal-only, reject plaintext
9. **Legacy decryptor** - For local history only
10. **Identity change UI** - Warning dialog, confirmation flow
11. **iOS stub** - Placeholder implementation

---

## Files Summary

### Modified
| File | Changes |
|------|---------|
| `TrickDatabase.sq` | Add 5 Signal tables + queries |
| `message.proto` | Add message_type, registration_id, sender_device_id |
| `QRKeyExchange.kt` | Use shortId from SignalSessionManager, bundle upload |
| `AndroidWifiAwareManager.kt` | Signal-only, reject plaintext & hpke-v1 |
| `Koin.kt` | Register SignalSessionManager, PreKeyBundleResolver |

### New
| File | Purpose |
|------|---------|
| `commonMain/.../signal/SignalSessionManager.kt` | expect class |
| `commonMain/.../signal/PreKeyBundleSerialization.kt` | JSON format |
| `commonMain/.../signal/PreKeyBundleResolver.kt` | Interface |
| `androidMain/.../signal/SignalSessionManager.android.kt` | actual impl |
| `androidMain/.../signal/stores/SQLDelightIdentityKeyStore.kt` | IdentityKeyStore |
| `androidMain/.../signal/stores/SQLDelightSessionStore.kt` | SessionStore |
| `androidMain/.../signal/stores/SQLDelightPreKeyStore.kt` | PreKeyStore with removePreKey |
| `androidMain/.../signal/stores/SQLDelightSignedPreKeyStore.kt` | SignedPreKeyStore |
| `androidMain/.../signal/TrckyOrgPreKeyBundleResolver.kt` | API client |
| `androidMain/.../data/LegacyMessageDecryptor.kt` | History-only HPKE |
| `iosMain/.../signal/SignalSessionManager.ios.kt` | iOS stub |

---

## Key Corrections Applied

1. **Signed PreKey Validation**: Removed manual `ECPublicKey.verifySignature()`. Construct `PreKeyBundle` and let `SessionBuilder.process()` validate internally.

2. **PreKey Consumption**: Removed manual ciphertext parsing. Implemented proper `PreKeyStore.removePreKey()` - libsignal calls it automatically.

3. **shortId Independence**: shortId is opaque random string stored in `SignalIdentity.short_id`, NOT derived from identity key. Key rotation doesn't change shortId.

4. **Identity Storage Single Source**: `SignalIdentity` table is the sole source for our identity. KeyManager only retained for legacy HPKE decryption of chat history.

5. **Plaintext Enforcement**: Plaintext messages over network are rejected with `[Rejected: unencrypted message]`, not just logged as warnings.

---

## Backend Requirements (trcky.org)

```
POST /api/bundles/{shortId}
  Body: PreKeyBundleJson
  Response: 201 Created

GET /api/bundles/{shortId}
  Response: 200 OK + PreKeyBundleJson
  Response: 404 Not Found (contact needs to refresh QR)
```

Recommended: TTL of 30 days on bundles, with client re-upload on app launch.

---

## Test Checklist

### Positive Tests
- [ ] QR scan: Fetches bundle, creates session, can send/receive
- [ ] Subsequent messages: Ratchet advances correctly
- [ ] App restart: Session persists, decrypt works
- [ ] Prekey replenishment: Auto-generates when count < 20
- [ ] Legacy history: Can read old hpke-v1 from local DB

### Negative Tests
- [ ] Bundle fetch 404: Shows error, does NOT create contact
- [ ] Invalid bundle (libsignal rejects): SessionBuildFailed error
- [ ] Downgrade attempt: hpke-v1 rejected
- [ ] Plaintext over network: Rejected
- [ ] Identity change: UntrustedIdentity, blocks until confirmed
- [ ] No session: Cannot send, shows error

### Integration Tests
- [ ] Full flow: QR generate → upload → scan → fetch → session → messages
- [ ] Bidirectional messaging works
- [ ] Prekey consumed after PreKeySignalMessage (count decreases)
- [ ] shortId unchanged after hypothetical identity rotation
