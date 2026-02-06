//! In-memory Signal protocol store implementations.
//!
//! These stores are populated per-FFI-call from serialized data that Kotlin
//! loads from SQLDelight. After the operation, mutated records are serialized
//! back and returned. No callbacks needed.

use std::collections::HashMap;

use async_trait::async_trait;
use libsignal_core::ProtocolAddress;
use libsignal_protocol::*;

// =============================================================================
// Identity Key Store
// =============================================================================

pub struct InMemoryIdentityKeyStore {
    key_pair: IdentityKeyPair,
    registration_id: u32,
    identities: HashMap<ProtocolAddress, IdentityKey>,
}

impl InMemoryIdentityKeyStore {
    pub fn new(key_pair: IdentityKeyPair, registration_id: u32) -> Self {
        Self {
            key_pair,
            registration_id,
            identities: HashMap::new(),
        }
    }

    /// Pre-populate with a known peer identity (from DB).
    pub fn insert_identity(&mut self, address: ProtocolAddress, key: IdentityKey) {
        self.identities.insert(address, key);
    }

    /// Get identity that was saved during the operation (for returning to Kotlin).
    pub fn get_saved_identity(&self, address: &ProtocolAddress) -> Option<&IdentityKey> {
        self.identities.get(address)
    }
}

#[async_trait(?Send)]
impl IdentityKeyStore for InMemoryIdentityKeyStore {
    async fn get_identity_key_pair(&self) -> Result<IdentityKeyPair, SignalProtocolError> {
        Ok(self.key_pair)
    }

    async fn get_local_registration_id(&self) -> Result<u32, SignalProtocolError> {
        Ok(self.registration_id)
    }

    async fn save_identity(
        &mut self,
        address: &ProtocolAddress,
        identity: &IdentityKey,
    ) -> Result<IdentityChange, SignalProtocolError> {
        match self.identities.get(address) {
            None => {
                self.identities.insert(address.clone(), *identity);
                Ok(IdentityChange::NewOrUnchanged)
            }
            Some(k) if k == identity => Ok(IdentityChange::NewOrUnchanged),
            Some(_) => {
                self.identities.insert(address.clone(), *identity);
                Ok(IdentityChange::ReplacedExisting)
            }
        }
    }

    async fn is_trusted_identity(
        &self,
        address: &ProtocolAddress,
        identity: &IdentityKey,
        _direction: Direction,
    ) -> Result<bool, SignalProtocolError> {
        // TOFU: trust if no existing key, or if key matches
        match self.identities.get(address) {
            None => Ok(true),
            Some(existing) => Ok(existing == identity),
        }
    }

    async fn get_identity(
        &self,
        address: &ProtocolAddress,
    ) -> Result<Option<IdentityKey>, SignalProtocolError> {
        Ok(self.identities.get(address).copied())
    }
}

// =============================================================================
// Session Store
// =============================================================================

pub struct InMemorySessionStore {
    sessions: HashMap<ProtocolAddress, SessionRecord>,
}

impl InMemorySessionStore {
    pub fn new() -> Self {
        Self {
            sessions: HashMap::new(),
        }
    }

    /// Pre-populate with an existing session from DB.
    pub fn insert_session(&mut self, address: ProtocolAddress, record: SessionRecord) {
        self.sessions.insert(address, record);
    }

    /// Get the (potentially updated) session record after an operation.
    pub fn get_session(&self, address: &ProtocolAddress) -> Option<&SessionRecord> {
        self.sessions.get(address)
    }
}

#[async_trait(?Send)]
impl SessionStore for InMemorySessionStore {
    async fn load_session(
        &self,
        address: &ProtocolAddress,
    ) -> Result<Option<SessionRecord>, SignalProtocolError> {
        Ok(self.sessions.get(address).cloned())
    }

    async fn store_session(
        &mut self,
        address: &ProtocolAddress,
        record: &SessionRecord,
    ) -> Result<(), SignalProtocolError> {
        self.sessions.insert(address.clone(), record.clone());
        Ok(())
    }
}

// =============================================================================
// Pre Key Store
// =============================================================================

pub struct InMemoryPreKeyStore {
    pre_keys: HashMap<PreKeyId, PreKeyRecord>,
    removed: Vec<PreKeyId>,
}

impl InMemoryPreKeyStore {
    pub fn new() -> Self {
        Self {
            pre_keys: HashMap::new(),
            removed: Vec::new(),
        }
    }

    /// Pre-populate with a specific prekey from DB.
    pub fn insert(&mut self, id: PreKeyId, record: PreKeyRecord) {
        self.pre_keys.insert(id, record);
    }

    /// Get list of prekey IDs that were consumed (removed) during decrypt.
    pub fn removed_ids(&self) -> &[PreKeyId] {
        &self.removed
    }
}

#[async_trait(?Send)]
impl PreKeyStore for InMemoryPreKeyStore {
    async fn get_pre_key(&self, id: PreKeyId) -> Result<PreKeyRecord, SignalProtocolError> {
        self.pre_keys
            .get(&id)
            .cloned()
            .ok_or(SignalProtocolError::InvalidPreKeyId)
    }

    async fn save_pre_key(
        &mut self,
        id: PreKeyId,
        record: &PreKeyRecord,
    ) -> Result<(), SignalProtocolError> {
        self.pre_keys.insert(id, record.clone());
        Ok(())
    }

    async fn remove_pre_key(&mut self, id: PreKeyId) -> Result<(), SignalProtocolError> {
        self.pre_keys.remove(&id);
        self.removed.push(id);
        Ok(())
    }
}

// =============================================================================
// Signed Pre Key Store
// =============================================================================

pub struct InMemorySignedPreKeyStore {
    signed_pre_keys: HashMap<SignedPreKeyId, SignedPreKeyRecord>,
}

impl InMemorySignedPreKeyStore {
    pub fn new() -> Self {
        Self {
            signed_pre_keys: HashMap::new(),
        }
    }

    pub fn insert(&mut self, id: SignedPreKeyId, record: SignedPreKeyRecord) {
        self.signed_pre_keys.insert(id, record);
    }
}

#[async_trait(?Send)]
impl SignedPreKeyStore for InMemorySignedPreKeyStore {
    async fn get_signed_pre_key(
        &self,
        id: SignedPreKeyId,
    ) -> Result<SignedPreKeyRecord, SignalProtocolError> {
        self.signed_pre_keys
            .get(&id)
            .cloned()
            .ok_or(SignalProtocolError::InvalidSignedPreKeyId)
    }

    async fn save_signed_pre_key(
        &mut self,
        id: SignedPreKeyId,
        record: &SignedPreKeyRecord,
    ) -> Result<(), SignalProtocolError> {
        self.signed_pre_keys.insert(id, record.clone());
        Ok(())
    }
}

// =============================================================================
// Kyber Pre Key Store
// =============================================================================

pub struct InMemoryKyberPreKeyStore {
    kyber_pre_keys: HashMap<KyberPreKeyId, KyberPreKeyRecord>,
    used: Vec<KyberPreKeyId>,
}

impl InMemoryKyberPreKeyStore {
    pub fn new() -> Self {
        Self {
            kyber_pre_keys: HashMap::new(),
            used: Vec::new(),
        }
    }

    pub fn insert(&mut self, id: KyberPreKeyId, record: KyberPreKeyRecord) {
        self.kyber_pre_keys.insert(id, record);
    }

    /// Get list of kyber prekey IDs that were marked as used during decrypt.
    pub fn used_ids(&self) -> &[KyberPreKeyId] {
        &self.used
    }
}

#[async_trait(?Send)]
impl KyberPreKeyStore for InMemoryKyberPreKeyStore {
    async fn get_kyber_pre_key(
        &self,
        id: KyberPreKeyId,
    ) -> Result<KyberPreKeyRecord, SignalProtocolError> {
        self.kyber_pre_keys
            .get(&id)
            .cloned()
            .ok_or(SignalProtocolError::InvalidKyberPreKeyId)
    }

    async fn save_kyber_pre_key(
        &mut self,
        id: KyberPreKeyId,
        record: &KyberPreKeyRecord,
    ) -> Result<(), SignalProtocolError> {
        self.kyber_pre_keys.insert(id, record.clone());
        Ok(())
    }

    async fn mark_kyber_pre_key_used(
        &mut self,
        id: KyberPreKeyId,
        _ec_prekey_id: SignedPreKeyId,
        _base_key: &PublicKey,
    ) -> Result<(), SignalProtocolError> {
        self.used.push(id);
        Ok(())
    }
}
