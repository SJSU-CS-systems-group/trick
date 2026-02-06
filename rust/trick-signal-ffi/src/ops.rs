//! Core Signal protocol operations.
//!
//! These functions operate on raw bytes (serialized records) and return raw bytes.
//! They are called by both the C FFI layer (iOS) and JNI layer (Android).

use std::time::SystemTime;

use libsignal_core::ProtocolAddress;
use libsignal_protocol::*;

use crate::stores::*;

/// Result types returned to Kotlin.
pub struct KeyPairResult {
    pub public_key: Vec<u8>,
    pub private_key: Vec<u8>,
}

pub struct SessionBuildResult {
    pub session_record: Vec<u8>,
    /// 0 = new identity (first contact), 1 = unchanged, 2 = changed
    pub identity_change_type: i32,
}

pub struct EncryptResult {
    pub ciphertext: Vec<u8>,
    pub message_type: i32,
    pub updated_session_record: Vec<u8>,
}

pub struct DecryptResult {
    pub plaintext: Vec<u8>,
    pub updated_session_record: Vec<u8>,
    /// -1 if no prekey was consumed
    pub consumed_pre_key_id: i32,
    /// -1 if no kyber prekey was consumed
    pub consumed_kyber_pre_key_id: i32,
    pub sender_identity_key: Vec<u8>,
}

pub struct PublicKeyAndSignature {
    pub public_key: Vec<u8>,
    pub signature: Vec<u8>,
}

// =============================================================================
// Key Generation
// =============================================================================

/// Generate a new identity key pair.
pub fn generate_identity_key_pair() -> Result<KeyPairResult, SignalProtocolError> {
    let mut rng = rand::rng();
    let key_pair = IdentityKeyPair::generate(&mut rng);
    Ok(KeyPairResult {
        public_key: key_pair.identity_key().serialize().to_vec(),
        private_key: key_pair.private_key().serialize().to_vec(),
    })
}

/// Generate a random registration ID.
pub fn generate_registration_id() -> u32 {
    let mut rng = rand::rng();
    rand::Rng::random_range(&mut rng, 1..16380)
}

/// Generate a pre-key record and return its serialized form.
pub fn generate_pre_key_record(pre_key_id: u32) -> Result<Vec<u8>, SignalProtocolError> {
    let mut rng = rand::rng();
    let key_pair = KeyPair::generate(&mut rng);
    let record = PreKeyRecord::new(PreKeyId::from(pre_key_id), &key_pair);
    Ok(record.serialize()?)
}

/// Generate a signed pre-key record and return its serialized form.
pub fn generate_signed_pre_key_record(
    signed_pre_key_id: u32,
    timestamp: u64,
    identity_private_key: &[u8],
) -> Result<Vec<u8>, SignalProtocolError> {
    let mut rng = rand::rng();
    let identity_key = PrivateKey::deserialize(identity_private_key)?;
    let key_pair = KeyPair::generate(&mut rng);
    let signature = identity_key.calculate_signature(&key_pair.public_key.serialize(), &mut rng)?;
    let ts = Timestamp::from_epoch_millis(timestamp);
    let record = SignedPreKeyRecord::new(
        SignedPreKeyId::from(signed_pre_key_id),
        ts,
        &key_pair,
        &signature,
    );
    Ok(record.serialize()?)
}

/// Generate a Kyber pre-key record and return its serialized form.
pub fn generate_kyber_pre_key_record(
    kyber_pre_key_id: u32,
    timestamp: u64,
    identity_private_key: &[u8],
) -> Result<Vec<u8>, SignalProtocolError> {
    let mut rng = rand::rng();
    let identity_key = PrivateKey::deserialize(identity_private_key)?;
    let key_pair = kem::KeyPair::generate(kem::KeyType::Kyber1024, &mut rng);
    let signature =
        identity_key.calculate_signature(&key_pair.public_key.serialize(), &mut rng)?;
    let ts = Timestamp::from_epoch_millis(timestamp);
    let record = KyberPreKeyRecord::new(
        KyberPreKeyId::from(kyber_pre_key_id),
        ts,
        &key_pair,
        &signature,
    );
    Ok(record.serialize()?)
}

// =============================================================================
// Session Building
// =============================================================================

fn make_device_id(id: u32) -> Result<DeviceId, SignalProtocolError> {
    DeviceId::try_from(id).map_err(|_| {
        SignalProtocolError::InvalidArgument(format!("invalid device_id: {id}"))
    })
}

/// Process a pre-key bundle to establish a session (X3DH key agreement).
pub fn process_pre_key_bundle(
    identity_public: &[u8],
    identity_private: &[u8],
    registration_id: u32,
    address_name: &str,
    device_id: u32,
    existing_peer_identity: Option<&[u8]>,
    existing_session: Option<&[u8]>,
    // Bundle components
    bundle_registration_id: u32,
    bundle_device_id: u32,
    bundle_pre_key_id: Option<u32>,
    bundle_pre_key_public: Option<&[u8]>,
    bundle_signed_pre_key_id: u32,
    bundle_signed_pre_key_public: &[u8],
    bundle_signed_pre_key_sig: &[u8],
    bundle_identity_key: &[u8],
    bundle_kyber_pre_key_id: u32,
    bundle_kyber_pre_key_public: &[u8],
    bundle_kyber_pre_key_sig: &[u8],
) -> Result<SessionBuildResult, SignalProtocolError> {
    // Reconstruct our identity
    let identity_public_key = IdentityKey::decode(identity_public)?;
    let identity_private_key = PrivateKey::deserialize(identity_private)?;
    let identity_key_pair = IdentityKeyPair::new(identity_public_key, identity_private_key);

    // Build stores
    let mut identity_store = InMemoryIdentityKeyStore::new(identity_key_pair, registration_id);
    let mut session_store = InMemorySessionStore::new();

    let address = ProtocolAddress::new(address_name.to_string(), make_device_id(device_id)?);

    // Pre-populate existing peer identity
    let had_existing_identity = if let Some(peer_id_bytes) = existing_peer_identity {
        let peer_identity = IdentityKey::decode(peer_id_bytes)?;
        identity_store.insert_identity(address.clone(), peer_identity);
        true
    } else {
        false
    };

    // Pre-populate existing session
    if let Some(session_bytes) = existing_session {
        let session = SessionRecord::deserialize(session_bytes)?;
        session_store.insert_session(address.clone(), session);
    }

    // Build the PreKeyBundle
    let pre_key = match (bundle_pre_key_id, bundle_pre_key_public) {
        (Some(id), Some(pub_bytes)) => Some((PreKeyId::from(id), PublicKey::deserialize(pub_bytes)?)),
        _ => None,
    };

    let signed_pre_key_public = PublicKey::deserialize(bundle_signed_pre_key_public)?;
    let bundle_identity = IdentityKey::decode(bundle_identity_key)?;

    let kyber_pub = kem::PublicKey::deserialize(bundle_kyber_pre_key_public)?;

    let bundle = PreKeyBundle::new(
        bundle_registration_id,
        make_device_id(bundle_device_id)?,
        pre_key,
        SignedPreKeyId::from(bundle_signed_pre_key_id),
        signed_pre_key_public,
        bundle_signed_pre_key_sig.to_vec(),
        KyberPreKeyId::from(bundle_kyber_pre_key_id),
        kyber_pub,
        bundle_kyber_pre_key_sig.to_vec(),
        bundle_identity,
    )?;

    // Process the bundle (X3DH)
    let now = SystemTime::now();
    let mut rng = rand::rng();

    futures::executor::block_on(libsignal_protocol::process_prekey_bundle(
        &address,
        &mut session_store,
        &mut identity_store,
        &bundle,
        now,
        &mut rng,
    ))?;

    // Extract the new session record
    let session = session_store
        .get_session(&address)
        .ok_or_else(|| {
            SignalProtocolError::InvalidState("process_prekey_bundle", "no session created".into())
        })?;

    let session_bytes = session.serialize()?;

    // Determine identity change type
    let identity_change_type = if !had_existing_identity {
        0 // new identity (first contact)
    } else {
        let saved_identity = identity_store.get_saved_identity(&address);
        if let (Some(saved), Some(peer_id_bytes)) = (saved_identity, existing_peer_identity) {
            let old = IdentityKey::decode(peer_id_bytes)?;
            if saved != &old { 2 } else { 1 } // changed vs unchanged
        } else {
            1 // unchanged
        }
    };

    Ok(SessionBuildResult {
        session_record: session_bytes,
        identity_change_type,
    })
}

// =============================================================================
// Encryption
// =============================================================================

/// Encrypt a message using the Signal protocol (Double Ratchet).
pub fn encrypt_message(
    identity_public: &[u8],
    identity_private: &[u8],
    registration_id: u32,
    address_name: &str,
    device_id: u32,
    session_record: &[u8],
    peer_identity: &[u8],
    plaintext: &[u8],
) -> Result<EncryptResult, SignalProtocolError> {
    let identity_public_key = IdentityKey::decode(identity_public)?;
    let identity_private_key = PrivateKey::deserialize(identity_private)?;
    let identity_key_pair = IdentityKeyPair::new(identity_public_key, identity_private_key);

    let mut identity_store = InMemoryIdentityKeyStore::new(identity_key_pair, registration_id);
    let mut session_store = InMemorySessionStore::new();

    let address = ProtocolAddress::new(address_name.to_string(), make_device_id(device_id)?);

    // Populate stores
    let peer_ik = IdentityKey::decode(peer_identity)?;
    identity_store.insert_identity(address.clone(), peer_ik);

    let session = SessionRecord::deserialize(session_record)?;
    session_store.insert_session(address.clone(), session);

    // Encrypt
    let now = SystemTime::now();
    let mut rng = rand::rng();
    let ciphertext = futures::executor::block_on(message_encrypt(
        plaintext,
        &address,
        &mut session_store,
        &mut identity_store,
        now,
        &mut rng,
    ))?;

    // Get updated session
    let updated_session = session_store
        .get_session(&address)
        .ok_or_else(|| {
            SignalProtocolError::InvalidState("encrypt", "session disappeared".into())
        })?;

    Ok(EncryptResult {
        ciphertext: ciphertext.serialize().to_vec(),
        message_type: ciphertext.message_type() as i32,
        updated_session_record: updated_session.serialize()?,
    })
}

// =============================================================================
// Decryption
// =============================================================================

/// Decrypt a Signal protocol message.
pub fn decrypt_message(
    identity_public: &[u8],
    identity_private: &[u8],
    registration_id: u32,
    address_name: &str,
    device_id: u32,
    session_record: &[u8],
    peer_identity: Option<&[u8]>,
    pre_key_record: Option<&[u8]>,
    signed_pre_key_record: Option<&[u8]>,
    kyber_pre_key_record: Option<&[u8]>,
    ciphertext: &[u8],
    message_type: i32,
) -> Result<DecryptResult, SignalProtocolError> {
    let identity_public_key = IdentityKey::decode(identity_public)?;
    let identity_private_key = PrivateKey::deserialize(identity_private)?;
    let identity_key_pair = IdentityKeyPair::new(identity_public_key, identity_private_key);

    let mut identity_store = InMemoryIdentityKeyStore::new(identity_key_pair, registration_id);
    let mut session_store = InMemorySessionStore::new();
    let mut pre_key_store = InMemoryPreKeyStore::new();
    let mut signed_pre_key_store = InMemorySignedPreKeyStore::new();
    let mut kyber_pre_key_store = InMemoryKyberPreKeyStore::new();

    let address = ProtocolAddress::new(address_name.to_string(), make_device_id(device_id)?);

    // Populate stores
    if let Some(peer_id_bytes) = peer_identity {
        let peer_ik = IdentityKey::decode(peer_id_bytes)?;
        identity_store.insert_identity(address.clone(), peer_ik);
    }

    // Only populate session if non-empty
    if !session_record.is_empty() {
        let session = SessionRecord::deserialize(session_record)?;
        session_store.insert_session(address.clone(), session);
    }

    if let Some(pk_bytes) = pre_key_record {
        let record = PreKeyRecord::deserialize(pk_bytes)?;
        let id = record.id()?;
        pre_key_store.insert(id, record);
    }

    if let Some(spk_bytes) = signed_pre_key_record {
        let record = SignedPreKeyRecord::deserialize(spk_bytes)?;
        let id = record.id()?;
        signed_pre_key_store.insert(id, record);
    }

    if let Some(kpk_bytes) = kyber_pre_key_record {
        let record = KyberPreKeyRecord::deserialize(kpk_bytes)?;
        let id = record.id()?;
        kyber_pre_key_store.insert(id, record);
    }

    // Decrypt based on message type
    let mut rng = rand::rng();

    let plaintext = if message_type == 3 {
        // PreKeySignalMessage
        let msg = PreKeySignalMessage::try_from(ciphertext)?;
        futures::executor::block_on(message_decrypt_prekey(
            &msg,
            &address,
            &mut session_store,
            &mut identity_store,
            &mut pre_key_store,
            &signed_pre_key_store,
            &mut kyber_pre_key_store,
            &mut rng,
        ))?
    } else {
        // Regular SignalMessage (type 2)
        let msg = SignalMessage::try_from(ciphertext)?;
        futures::executor::block_on(message_decrypt_signal(
            &msg,
            &address,
            &mut session_store,
            &mut identity_store,
            &mut rng,
        ))?
    };

    // Get updated session
    let updated_session = session_store
        .get_session(&address)
        .ok_or_else(|| {
            SignalProtocolError::InvalidState("decrypt", "no session after decrypt".into())
        })?;

    // Get consumed prekey IDs
    let consumed_pre_key_id = pre_key_store
        .removed_ids()
        .first()
        .map(|id| u32::from(*id) as i32)
        .unwrap_or(-1);

    let consumed_kyber_pre_key_id = kyber_pre_key_store
        .used_ids()
        .first()
        .map(|id| u32::from(*id) as i32)
        .unwrap_or(-1);

    // Get sender identity
    let sender_identity = identity_store
        .get_saved_identity(&address)
        .map(|k| k.serialize().to_vec())
        .unwrap_or_default();

    Ok(DecryptResult {
        plaintext,
        updated_session_record: updated_session.serialize()?,
        consumed_pre_key_id,
        consumed_kyber_pre_key_id,
        sender_identity_key: sender_identity,
    })
}

// =============================================================================
// Utility
// =============================================================================

/// Get message type from ciphertext (first byte encodes type).
pub fn get_ciphertext_message_type(ciphertext: &[u8]) -> Result<i32, SignalProtocolError> {
    if ciphertext.is_empty() {
        return Err(SignalProtocolError::InvalidArgument("empty ciphertext".into()));
    }
    // Try PreKeySignalMessage first, then SignalMessage
    if PreKeySignalMessage::try_from(ciphertext).is_ok() {
        Ok(3) // PreKeySignalMessage
    } else if SignalMessage::try_from(ciphertext).is_ok() {
        Ok(2) // SignalMessage
    } else {
        Err(SignalProtocolError::InvalidArgument("unrecognized message type".into()))
    }
}

/// Extract prekey IDs from a PreKeySignalMessage.
pub fn prekey_message_get_ids(ciphertext: &[u8]) -> Result<(i32, i32), SignalProtocolError> {
    let msg = PreKeySignalMessage::try_from(ciphertext)?;
    let pre_key_id = msg
        .pre_key_id()
        .map(|id| u32::from(id) as i32)
        .unwrap_or(-1);
    let signed_pre_key_id = u32::from(msg.signed_pre_key_id()) as i32;
    Ok((pre_key_id, signed_pre_key_id))
}

/// Extract public key from a serialized PreKeyRecord.
pub fn prekey_record_get_public_key(record: &[u8]) -> Result<Vec<u8>, SignalProtocolError> {
    let rec = PreKeyRecord::deserialize(record)?;
    Ok(rec.public_key()?.serialize().to_vec())
}

/// Extract public key and signature from a serialized SignedPreKeyRecord.
pub fn signed_prekey_record_get_public_key(
    record: &[u8],
) -> Result<PublicKeyAndSignature, SignalProtocolError> {
    let rec = SignedPreKeyRecord::deserialize(record)?;
    Ok(PublicKeyAndSignature {
        public_key: rec.public_key()?.serialize().to_vec(),
        signature: rec.signature()?.to_vec(),
    })
}

/// Extract public key and signature from a serialized KyberPreKeyRecord.
pub fn kyber_prekey_record_get_public_key(
    record: &[u8],
) -> Result<PublicKeyAndSignature, SignalProtocolError> {
    let rec = KyberPreKeyRecord::deserialize(record)?;
    Ok(PublicKeyAndSignature {
        public_key: rec.public_key()?.serialize().to_vec(),
        signature: rec.signature()?.to_vec(),
    })
}

/// Check if a session record has a sender chain (is usable for sending).
pub fn session_record_has_sender_chain(record: &[u8]) -> Result<bool, SignalProtocolError> {
    let rec = SessionRecord::deserialize(record)?;
    Ok(rec.has_usable_sender_chain(SystemTime::now(), SessionUsabilityRequirements::empty())?)
}

/// Sign data with an EC private key.
pub fn private_key_sign(
    private_key: &[u8],
    data: &[u8],
) -> Result<Vec<u8>, SignalProtocolError> {
    let mut rng = rand::rng();
    let key = PrivateKey::deserialize(private_key)?;
    let signature = key.calculate_signature(data, &mut rng)?;
    Ok(signature.to_vec())
}

/// Verify signature with an EC public key.
pub fn public_key_verify(
    public_key: &[u8],
    data: &[u8],
    signature: &[u8],
) -> Result<bool, SignalProtocolError> {
    let key = PublicKey::deserialize(public_key)?;
    Ok(key.verify_signature(data, signature))
}
