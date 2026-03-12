//! JNI wrapper functions for Android.
//!
//! Each function converts JNI types to Rust types, calls the internal ops
//! functions, and converts results back to JNI types.
//!
//! Naming convention: Java_org_trick_signal_SignalNativeBridge_<method>

#![cfg(target_os = "android")]

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::jint;
use jni::JNIEnv;

use crate::error::*;
use crate::ops;

/// Helper: read a JNI byte array into a Vec<u8>.
fn get_bytes(env: &JNIEnv, arr: &JByteArray) -> Result<Vec<u8>, jint> {
    env.convert_byte_array(arr).map_err(|_| TRICK_ERROR_INTERNAL)
}

/// Helper: read an optional JNI byte array (null → None).
fn get_optional_bytes(env: &JNIEnv, arr: &JByteArray) -> Result<Option<Vec<u8>>, jint> {
    if env.is_same_object(arr, jni::objects::JObject::null()).unwrap_or(true) {
        Ok(None)
    } else {
        Ok(Some(get_bytes(env, arr)?))
    }
}

/// Helper: write bytes into a JNI output byte array.
fn set_bytes(env: &JNIEnv, arr: &JByteArray, data: &[u8]) -> Result<i32, jint> {
    let jbytes: Vec<i8> = data.iter().map(|&b| b as i8).collect();
    env.set_byte_array_region(arr, 0, &jbytes)
        .map_err(|_| TRICK_ERROR_INTERNAL)?;
    Ok(data.len() as i32)
}

/// Helper: get a Rust string from a JNI string.
fn get_string(env: &mut JNIEnv, s: &JString) -> Result<String, jint> {
    env.get_string(s)
        .map(|s| s.into())
        .map_err(|_| TRICK_ERROR_INVALID_ARGUMENT)
}

// =============================================================================
// Key Generation
// =============================================================================

#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativeGenerateIdentityKeyPair(
    _env: JNIEnv,
    _class: JClass,
    out_public: JByteArray,
    out_private: JByteArray,
) -> jint {
    match ops::generate_identity_key_pair() {
        Ok(result) => {
            if set_bytes(&_env, &out_public, &result.public_key).is_err() {
                return TRICK_ERROR_INTERNAL;
            }
            if set_bytes(&_env, &out_private, &result.private_key).is_err() {
                return TRICK_ERROR_INTERNAL;
            }
            TRICK_SUCCESS
        }
        Err(e) => signal_error_to_code(&e),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativeGenerateRegistrationId(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    ops::generate_registration_id() as jint
}

#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativeGeneratePreKeyRecord(
    _env: JNIEnv,
    _class: JClass,
    pre_key_id: jint,
    out_record: JByteArray,
) -> jint {
    match ops::generate_pre_key_record(pre_key_id as u32) {
        Ok(data) => match set_bytes(&_env, &out_record, &data) {
            Ok(len) => len,
            Err(e) => e,
        },
        Err(e) => signal_error_to_code(&e),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativeGenerateSignedPreKeyRecord(
    _env: JNIEnv,
    _class: JClass,
    signed_pre_key_id: jint,
    timestamp: jni::sys::jlong,
    identity_private_key: JByteArray,
    out_record: JByteArray,
) -> jint {
    let priv_key = match get_bytes(&_env, &identity_private_key) {
        Ok(b) => b, Err(e) => return e,
    };
    match ops::generate_signed_pre_key_record(
        signed_pre_key_id as u32,
        timestamp as u64,
        &priv_key,
    ) {
        Ok(data) => match set_bytes(&_env, &out_record, &data) {
            Ok(len) => len,
            Err(e) => e,
        },
        Err(e) => signal_error_to_code(&e),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativeGenerateKyberPreKeyRecord(
    _env: JNIEnv,
    _class: JClass,
    kyber_pre_key_id: jint,
    timestamp: jni::sys::jlong,
    identity_private_key: JByteArray,
    out_record: JByteArray,
) -> jint {
    let priv_key = match get_bytes(&_env, &identity_private_key) {
        Ok(b) => b, Err(e) => return e,
    };
    match ops::generate_kyber_pre_key_record(
        kyber_pre_key_id as u32,
        timestamp as u64,
        &priv_key,
    ) {
        Ok(data) => match set_bytes(&_env, &out_record, &data) {
            Ok(len) => len,
            Err(e) => e,
        },
        Err(e) => signal_error_to_code(&e),
    }
}

// =============================================================================
// Session Building
// =============================================================================

/// Process a pre-key bundle. Returns serialized session in out_session,
/// identity change type in out_identity_changed (int array of length 1).
/// Return value: bytes written to out_session, or negative on error.
#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativeProcessPreKeyBundle(
    mut _env: JNIEnv,
    _class: JClass,
    identity_public: JByteArray,
    identity_private: JByteArray,
    registration_id: jint,
    address_name: JString,
    device_id: jint,
    existing_peer_identity: JByteArray,
    existing_session: JByteArray,
    bundle_registration_id: jint,
    bundle_device_id: jint,
    bundle_pre_key_id: jint,
    bundle_pre_key_public: JByteArray,
    bundle_signed_pre_key_id: jint,
    bundle_signed_pre_key_public: JByteArray,
    bundle_signed_pre_key_sig: JByteArray,
    bundle_identity_key: JByteArray,
    bundle_kyber_pre_key_id: jint,
    bundle_kyber_pre_key_public: JByteArray,
    bundle_kyber_pre_key_sig: JByteArray,
    out_session: JByteArray,
    out_identity_changed: jni::objects::JIntArray,
) -> jint {
    let id_pub = match get_bytes(&_env, &identity_public) {
        Ok(b) => b, Err(e) => return e,
    };
    let id_priv = match get_bytes(&_env, &identity_private) {
        Ok(b) => b, Err(e) => return e,
    };
    let addr = match get_string(&mut _env, &address_name) {
        Ok(s) => s, Err(e) => return e,
    };
    let existing_peer = match get_optional_bytes(&_env, &existing_peer_identity) {
        Ok(b) => b, Err(e) => return e,
    };
    let existing_sess = match get_optional_bytes(&_env, &existing_session) {
        Ok(b) => b, Err(e) => return e,
    };
    let spk_pub = match get_bytes(&_env, &bundle_signed_pre_key_public) {
        Ok(b) => b, Err(e) => return e,
    };
    let spk_sig = match get_bytes(&_env, &bundle_signed_pre_key_sig) {
        Ok(b) => b, Err(e) => return e,
    };
    let b_ik = match get_bytes(&_env, &bundle_identity_key) {
        Ok(b) => b, Err(e) => return e,
    };
    let b_pk_id = if bundle_pre_key_id >= 0 { Some(bundle_pre_key_id as u32) } else { None };
    let b_pk_pub = match get_optional_bytes(&_env, &bundle_pre_key_public) {
        Ok(b) => b, Err(e) => return e,
    };
    // Kyber is required by the current Signal protocol
    if bundle_kyber_pre_key_id < 0 {
        return TRICK_ERROR_INVALID_ARGUMENT;
    }
    let b_kpk_pub = match get_bytes(&_env, &bundle_kyber_pre_key_public) {
        Ok(b) => b, Err(e) => return e,
    };
    let b_kpk_sig = match get_bytes(&_env, &bundle_kyber_pre_key_sig) {
        Ok(b) => b, Err(e) => return e,
    };

    match ops::process_pre_key_bundle(
        &id_pub, &id_priv, registration_id as u32,
        &addr, device_id as u32,
        existing_peer.as_deref(), existing_sess.as_deref(),
        bundle_registration_id as u32, bundle_device_id as u32,
        b_pk_id, b_pk_pub.as_deref(),
        bundle_signed_pre_key_id as u32, &spk_pub, &spk_sig,
        &b_ik,
        bundle_kyber_pre_key_id as u32, &b_kpk_pub, &b_kpk_sig,
    ) {
        Ok(result) => {
            let written = match set_bytes(&_env, &out_session, &result.session_record) {
                Ok(len) => len, Err(e) => return e,
            };
            let change_arr = [result.identity_change_type];
            let _ = _env.set_int_array_region(&out_identity_changed, 0, &change_arr);
            written
        }
        Err(e) => signal_error_to_code(&e),
    }
}

// =============================================================================
// Encryption
// =============================================================================

/// Encrypt a message. Returns serialized ciphertext in out_ciphertext,
/// message type and updated session in out_message_type_and_session_len (int[2]).
/// Return value: bytes written to out_ciphertext, or negative on error.
#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativeEncryptMessage(
    mut _env: JNIEnv,
    _class: JClass,
    identity_public: JByteArray,
    identity_private: JByteArray,
    registration_id: jint,
    address_name: JString,
    device_id: jint,
    session_record: JByteArray,
    peer_identity: JByteArray,
    plaintext: JByteArray,
    out_ciphertext: JByteArray,
    out_updated_session: JByteArray,
    out_meta: jni::objects::JIntArray, // [message_type, session_len]
) -> jint {
    let id_pub = match get_bytes(&_env, &identity_public) { Ok(b) => b, Err(e) => return e };
    let id_priv = match get_bytes(&_env, &identity_private) { Ok(b) => b, Err(e) => return e };
    let addr = match get_string(&mut _env, &address_name) { Ok(s) => s, Err(e) => return e };
    let sess = match get_bytes(&_env, &session_record) { Ok(b) => b, Err(e) => return e };
    let peer_id = match get_bytes(&_env, &peer_identity) { Ok(b) => b, Err(e) => return e };
    let pt = match get_bytes(&_env, &plaintext) { Ok(b) => b, Err(e) => return e };

    match ops::encrypt_message(
        &id_pub, &id_priv, registration_id as u32,
        &addr, device_id as u32,
        &sess, &peer_id, &pt,
    ) {
        Ok(result) => {
            let ct_written = match set_bytes(&_env, &out_ciphertext, &result.ciphertext) {
                Ok(len) => len, Err(e) => return e,
            };
            let sess_written = match set_bytes(&_env, &out_updated_session, &result.updated_session_record) {
                Ok(len) => len, Err(e) => return e,
            };
            let meta = [result.message_type, sess_written];
            let _ = _env.set_int_array_region(&out_meta, 0, &meta);
            ct_written
        }
        Err(e) => signal_error_to_code(&e),
    }
}

// =============================================================================
// Decryption
// =============================================================================

#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativeGetCiphertextMessageType(
    _env: JNIEnv,
    _class: JClass,
    ciphertext: JByteArray,
) -> jint {
    let ct = match get_bytes(&_env, &ciphertext) { Ok(b) => b, Err(e) => return e };
    match ops::get_ciphertext_message_type(&ct) {
        Ok(t) => t,
        Err(e) => signal_error_to_code(&e),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativePreKeyMessageGetIds(
    _env: JNIEnv,
    _class: JClass,
    ciphertext: JByteArray,
    out_ids: jni::objects::JIntArray, // [pre_key_id, signed_pre_key_id]
) -> jint {
    let ct = match get_bytes(&_env, &ciphertext) { Ok(b) => b, Err(e) => return e };
    match ops::prekey_message_get_ids(&ct) {
        Ok((pk_id, spk_id)) => {
            let ids = [pk_id, spk_id];
            let _ = _env.set_int_array_region(&out_ids, 0, &ids);
            TRICK_SUCCESS
        }
        Err(e) => signal_error_to_code(&e),
    }
}

/// Decrypt a message. Returns plaintext in out_plaintext, updated session in
/// out_updated_session, sender identity in out_sender_identity.
/// out_meta: [consumed_pre_key_id, consumed_kyber_pre_key_id, session_len, sender_identity_len]
/// Return value: bytes of plaintext written, or negative on error.
#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativeDecryptMessage(
    mut _env: JNIEnv,
    _class: JClass,
    identity_public: JByteArray,
    identity_private: JByteArray,
    registration_id: jint,
    address_name: JString,
    device_id: jint,
    session_record: JByteArray,
    peer_identity: JByteArray,
    pre_key_record: JByteArray,
    signed_pre_key_record: JByteArray,
    kyber_pre_key_record: JByteArray,
    ciphertext: JByteArray,
    message_type: jint,
    out_plaintext: JByteArray,
    out_updated_session: JByteArray,
    out_sender_identity: JByteArray,
    out_meta: jni::objects::JIntArray, // [consumed_pk_id, consumed_kpk_id, sess_len, id_len]
) -> jint {
    let id_pub = match get_bytes(&_env, &identity_public) { Ok(b) => b, Err(e) => return e };
    let id_priv = match get_bytes(&_env, &identity_private) { Ok(b) => b, Err(e) => return e };
    let addr = match get_string(&mut _env, &address_name) { Ok(s) => s, Err(e) => return e };
    let sess = match get_bytes(&_env, &session_record) { Ok(b) => b, Err(e) => return e };
    let peer_id = match get_optional_bytes(&_env, &peer_identity) { Ok(b) => b, Err(e) => return e };
    let pk = match get_optional_bytes(&_env, &pre_key_record) { Ok(b) => b, Err(e) => return e };
    let spk = match get_optional_bytes(&_env, &signed_pre_key_record) { Ok(b) => b, Err(e) => return e };
    let kpk = match get_optional_bytes(&_env, &kyber_pre_key_record) { Ok(b) => b, Err(e) => return e };
    let ct = match get_bytes(&_env, &ciphertext) { Ok(b) => b, Err(e) => return e };

    match ops::decrypt_message(
        &id_pub, &id_priv, registration_id as u32,
        &addr, device_id as u32,
        &sess, peer_id.as_deref(), pk.as_deref(), spk.as_deref(), kpk.as_deref(),
        &ct, message_type,
    ) {
        Ok(result) => {
            let pt_written = match set_bytes(&_env, &out_plaintext, &result.plaintext) {
                Ok(len) => len, Err(e) => return e,
            };
            let sess_written = match set_bytes(&_env, &out_updated_session, &result.updated_session_record) {
                Ok(len) => len, Err(e) => return e,
            };
            let id_written = match set_bytes(&_env, &out_sender_identity, &result.sender_identity_key) {
                Ok(len) => len, Err(e) => return e,
            };
            let meta = [
                result.consumed_pre_key_id,
                result.consumed_kyber_pre_key_id,
                sess_written,
                id_written,
            ];
            let _ = _env.set_int_array_region(&out_meta, 0, &meta);
            pt_written
        }
        Err(e) => signal_error_to_code(&e),
    }
}

// =============================================================================
// Utility
// =============================================================================

#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativePreKeyRecordGetPublicKey(
    _env: JNIEnv,
    _class: JClass,
    record: JByteArray,
    out_public_key: JByteArray,
) -> jint {
    let rec = match get_bytes(&_env, &record) { Ok(b) => b, Err(e) => return e };
    match ops::prekey_record_get_public_key(&rec) {
        Ok(pk) => match set_bytes(&_env, &out_public_key, &pk) {
            Ok(len) => len, Err(e) => e,
        },
        Err(e) => signal_error_to_code(&e),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativeSignedPreKeyRecordGetPublicKey(
    _env: JNIEnv,
    _class: JClass,
    record: JByteArray,
    out_public_key: JByteArray,
    out_signature: JByteArray,
) -> jint {
    let rec = match get_bytes(&_env, &record) { Ok(b) => b, Err(e) => return e };
    match ops::signed_prekey_record_get_public_key(&rec) {
        Ok(result) => {
            if set_bytes(&_env, &out_public_key, &result.public_key).is_err() { return TRICK_ERROR_INTERNAL; }
            if set_bytes(&_env, &out_signature, &result.signature).is_err() { return TRICK_ERROR_INTERNAL; }
            TRICK_SUCCESS
        }
        Err(e) => signal_error_to_code(&e),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativeKyberPreKeyRecordGetPublicKey(
    _env: JNIEnv,
    _class: JClass,
    record: JByteArray,
    out_public_key: JByteArray,
    out_signature: JByteArray,
    out_meta: jni::objects::JIntArray, // [pub_key_len, sig_len]
) -> jint {
    let rec = match get_bytes(&_env, &record) { Ok(b) => b, Err(e) => return e };
    match ops::kyber_prekey_record_get_public_key(&rec) {
        Ok(result) => {
            let pk_len = match set_bytes(&_env, &out_public_key, &result.public_key) {
                Ok(len) => len, Err(e) => return e,
            };
            let sig_len = match set_bytes(&_env, &out_signature, &result.signature) {
                Ok(len) => len, Err(e) => return e,
            };
            let meta = [pk_len, sig_len];
            let _ = _env.set_int_array_region(&out_meta, 0, &meta);
            TRICK_SUCCESS
        }
        Err(e) => signal_error_to_code(&e),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativePrivateKeySign(
    _env: JNIEnv,
    _class: JClass,
    private_key: JByteArray,
    data: JByteArray,
    out_signature: JByteArray,
) -> jint {
    let pk = match get_bytes(&_env, &private_key) { Ok(b) => b, Err(e) => return e };
    let d = match get_bytes(&_env, &data) { Ok(b) => b, Err(e) => return e };
    match ops::private_key_sign(&pk, &d) {
        Ok(sig) => match set_bytes(&_env, &out_signature, &sig) {
            Ok(len) => len, Err(e) => e,
        },
        Err(e) => signal_error_to_code(&e),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_trick_signal_SignalNativeBridge_nativePublicKeyVerify(
    _env: JNIEnv,
    _class: JClass,
    public_key: JByteArray,
    data: JByteArray,
    signature: JByteArray,
) -> jint {
    let pk = match get_bytes(&_env, &public_key) { Ok(b) => b, Err(e) => return e };
    let d = match get_bytes(&_env, &data) { Ok(b) => b, Err(e) => return e };
    let sig = match get_bytes(&_env, &signature) { Ok(b) => b, Err(e) => return e };
    match ops::public_key_verify(&pk, &d, &sig) {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(e) => signal_error_to_code(&e),
    }
}
