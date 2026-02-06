//! C FFI functions for iOS (cinterop) and general use.
//!
//! All functions return i32: 0 or positive on success, negative on error.
//! For functions returning variable-size data, the return value is the number
//! of bytes written. For fixed-size outputs, return value is 0 on success.

use std::ffi::CStr;
use std::os::raw::c_char;
use std::slice;

use crate::error::*;
use crate::ops;

/// Helper: write bytes into a caller-provided output buffer.
/// Returns bytes written, TRICK_ERROR_INVALID_ARGUMENT if out is null,
/// or TRICK_ERROR_BUFFER_TOO_SMALL if capacity is insufficient.
unsafe fn write_output(data: &[u8], out: *mut u8, cap: i32) -> i32 {
    if out.is_null() {
        return TRICK_ERROR_INVALID_ARGUMENT;
    }
    if cap < data.len() as i32 {
        return TRICK_ERROR_BUFFER_TOO_SMALL;
    }
    std::ptr::copy_nonoverlapping(data.as_ptr(), out, data.len());
    data.len() as i32
}

/// Helper: read a byte slice from a pointer + length. Returns None if null.
unsafe fn read_bytes<'a>(ptr: *const u8, len: i32) -> Option<&'a [u8]> {
    if ptr.is_null() || len <= 0 {
        None
    } else {
        Some(slice::from_raw_parts(ptr, len as usize))
    }
}

/// Helper: read a required byte slice. Returns Err if null.
unsafe fn require_bytes<'a>(ptr: *const u8, len: i32) -> Result<&'a [u8], i32> {
    read_bytes(ptr, len).ok_or(TRICK_ERROR_INVALID_ARGUMENT)
}

/// Helper: read a C string.
unsafe fn read_cstr<'a>(ptr: *const c_char) -> Result<&'a str, i32> {
    if ptr.is_null() {
        return Err(TRICK_ERROR_INVALID_ARGUMENT);
    }
    CStr::from_ptr(ptr).to_str().map_err(|_| TRICK_ERROR_INVALID_ARGUMENT)
}

// =============================================================================
// Key Generation
// =============================================================================

/// Generate a new identity key pair.
/// out_public must be >= 33 bytes, out_private must be >= 32 bytes.
/// Returns 0 on success, negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_generate_identity_key_pair(
    out_public: *mut u8,
    out_public_cap: i32,
    out_private: *mut u8,
    out_private_cap: i32,
) -> i32 {
    match ops::generate_identity_key_pair() {
        Ok(result) => {
            let r1 = write_output(&result.public_key, out_public, out_public_cap);
            if r1 < 0 { return r1; }
            let r2 = write_output(&result.private_key, out_private, out_private_cap);
            if r2 < 0 { return r2; }
            TRICK_SUCCESS
        }
        Err(e) => signal_error_to_code(&e),
    }
}

/// Generate a random registration ID.
/// Returns the registration ID (always positive).
#[no_mangle]
pub extern "C" fn trick_generate_registration_id() -> i32 {
    ops::generate_registration_id() as i32
}

/// Generate a pre-key record.
/// Returns bytes written to out_record, or negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_generate_pre_key_record(
    pre_key_id: i32,
    out_record: *mut u8,
    out_record_cap: i32,
) -> i32 {
    match ops::generate_pre_key_record(pre_key_id as u32) {
        Ok(data) => write_output(&data, out_record, out_record_cap),
        Err(e) => signal_error_to_code(&e),
    }
}

/// Generate a signed pre-key record.
/// Returns bytes written to out_record, or negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_generate_signed_pre_key_record(
    signed_pre_key_id: i32,
    timestamp: i64,
    identity_private_key: *const u8,
    identity_private_key_len: i32,
    out_record: *mut u8,
    out_record_cap: i32,
) -> i32 {
    let priv_key = match require_bytes(identity_private_key, identity_private_key_len) {
        Ok(b) => b,
        Err(e) => return e,
    };
    match ops::generate_signed_pre_key_record(
        signed_pre_key_id as u32,
        timestamp as u64,
        priv_key,
    ) {
        Ok(data) => write_output(&data, out_record, out_record_cap),
        Err(e) => signal_error_to_code(&e),
    }
}

/// Generate a Kyber pre-key record.
/// Returns bytes written to out_record, or negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_generate_kyber_pre_key_record(
    kyber_pre_key_id: i32,
    timestamp: i64,
    identity_private_key: *const u8,
    identity_private_key_len: i32,
    out_record: *mut u8,
    out_record_cap: i32,
) -> i32 {
    let priv_key = match require_bytes(identity_private_key, identity_private_key_len) {
        Ok(b) => b,
        Err(e) => return e,
    };
    match ops::generate_kyber_pre_key_record(
        kyber_pre_key_id as u32,
        timestamp as u64,
        priv_key,
    ) {
        Ok(data) => write_output(&data, out_record, out_record_cap),
        Err(e) => signal_error_to_code(&e),
    }
}

// =============================================================================
// Session Building
// =============================================================================

/// Process a pre-key bundle to establish a session.
/// Returns 0 on success, negative on error.
/// out_session_len is set to the number of bytes written to out_session.
/// out_identity_changed: 0=new, 1=unchanged, 2=changed.
#[no_mangle]
pub unsafe extern "C" fn trick_process_pre_key_bundle(
    // Our identity
    identity_public: *const u8, identity_public_len: i32,
    identity_private: *const u8, identity_private_len: i32,
    registration_id: i32,
    // Peer address
    address_name: *const c_char,
    device_id: i32,
    // Existing peer identity (null if first contact)
    existing_peer_identity: *const u8, existing_peer_identity_len: i32,
    // Existing session (null if none)
    existing_session: *const u8, existing_session_len: i32,
    // Bundle components
    bundle_registration_id: i32,
    bundle_device_id: i32,
    bundle_pre_key_id: i32, // -1 if none
    bundle_pre_key_public: *const u8, bundle_pre_key_public_len: i32,
    bundle_signed_pre_key_id: i32,
    bundle_signed_pre_key_public: *const u8, bundle_signed_pre_key_public_len: i32,
    bundle_signed_pre_key_sig: *const u8, bundle_signed_pre_key_sig_len: i32,
    bundle_identity_key: *const u8, bundle_identity_key_len: i32,
    bundle_kyber_pre_key_id: i32, // -1 if none
    bundle_kyber_pre_key_public: *const u8, bundle_kyber_pre_key_public_len: i32,
    bundle_kyber_pre_key_sig: *const u8, bundle_kyber_pre_key_sig_len: i32,
    // Outputs
    out_session: *mut u8, out_session_cap: i32, out_session_len: *mut i32,
    out_identity_changed: *mut i32,
) -> i32 {
    let id_pub = match require_bytes(identity_public, identity_public_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let id_priv = match require_bytes(identity_private, identity_private_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let addr = match read_cstr(address_name) {
        Ok(s) => s, Err(e) => return e,
    };
    let spk_pub = match require_bytes(bundle_signed_pre_key_public, bundle_signed_pre_key_public_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let spk_sig = match require_bytes(bundle_signed_pre_key_sig, bundle_signed_pre_key_sig_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let b_ik = match require_bytes(bundle_identity_key, bundle_identity_key_len) {
        Ok(b) => b, Err(e) => return e,
    };

    let existing_peer = read_bytes(existing_peer_identity, existing_peer_identity_len);
    let existing_sess = read_bytes(existing_session, existing_session_len);
    let b_pk_id = if bundle_pre_key_id >= 0 { Some(bundle_pre_key_id as u32) } else { None };
    let b_pk_pub = read_bytes(bundle_pre_key_public, bundle_pre_key_public_len);

    // Kyber is required by the current Signal protocol
    let b_kpk_pub = match require_bytes(bundle_kyber_pre_key_public, bundle_kyber_pre_key_public_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let b_kpk_sig = match require_bytes(bundle_kyber_pre_key_sig, bundle_kyber_pre_key_sig_len) {
        Ok(b) => b, Err(e) => return e,
    };
    if bundle_kyber_pre_key_id < 0 {
        return TRICK_ERROR_INVALID_ARGUMENT;
    }

    match ops::process_pre_key_bundle(
        id_pub, id_priv, registration_id as u32,
        addr, device_id as u32,
        existing_peer, existing_sess,
        bundle_registration_id as u32, bundle_device_id as u32,
        b_pk_id, b_pk_pub,
        bundle_signed_pre_key_id as u32, spk_pub, spk_sig,
        b_ik,
        bundle_kyber_pre_key_id as u32, b_kpk_pub, b_kpk_sig,
    ) {
        Ok(result) => {
            let written = write_output(&result.session_record, out_session, out_session_cap);
            if written < 0 { return written; }
            if !out_session_len.is_null() { *out_session_len = written; }
            if !out_identity_changed.is_null() { *out_identity_changed = result.identity_change_type; }
            TRICK_SUCCESS
        }
        Err(e) => signal_error_to_code(&e),
    }
}

// =============================================================================
// Encryption
// =============================================================================

/// Encrypt a message using the Signal protocol.
/// Returns 0 on success, negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_encrypt_message(
    identity_public: *const u8, identity_public_len: i32,
    identity_private: *const u8, identity_private_len: i32,
    registration_id: i32,
    address_name: *const c_char,
    device_id: i32,
    session_record: *const u8, session_record_len: i32,
    peer_identity: *const u8, peer_identity_len: i32,
    plaintext: *const u8, plaintext_len: i32,
    // Outputs
    out_ciphertext: *mut u8, out_ct_cap: i32, out_ct_len: *mut i32,
    out_message_type: *mut i32,
    out_updated_session: *mut u8, out_sess_cap: i32, out_sess_len: *mut i32,
) -> i32 {
    let id_pub = match require_bytes(identity_public, identity_public_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let id_priv = match require_bytes(identity_private, identity_private_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let addr = match read_cstr(address_name) {
        Ok(s) => s, Err(e) => return e,
    };
    let sess = match require_bytes(session_record, session_record_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let peer_id = match require_bytes(peer_identity, peer_identity_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let pt = match require_bytes(plaintext, plaintext_len) {
        Ok(b) => b, Err(e) => return e,
    };

    match ops::encrypt_message(
        id_pub, id_priv, registration_id as u32,
        addr, device_id as u32,
        sess, peer_id, pt,
    ) {
        Ok(result) => {
            let ct_written = write_output(&result.ciphertext, out_ciphertext, out_ct_cap);
            if ct_written < 0 { return ct_written; }
            if !out_ct_len.is_null() { *out_ct_len = ct_written; }
            if !out_message_type.is_null() { *out_message_type = result.message_type; }
            let sess_written = write_output(&result.updated_session_record, out_updated_session, out_sess_cap);
            if sess_written < 0 { return sess_written; }
            if !out_sess_len.is_null() { *out_sess_len = sess_written; }
            TRICK_SUCCESS
        }
        Err(e) => signal_error_to_code(&e),
    }
}

// =============================================================================
// Decryption
// =============================================================================

/// Get the message type from raw ciphertext.
/// Returns 2 (SignalMessage) or 3 (PreKeySignalMessage), or negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_get_ciphertext_message_type(
    ciphertext: *const u8,
    ciphertext_len: i32,
) -> i32 {
    let ct = match require_bytes(ciphertext, ciphertext_len) {
        Ok(b) => b, Err(e) => return e,
    };
    match ops::get_ciphertext_message_type(ct) {
        Ok(t) => t,
        Err(e) => signal_error_to_code(&e),
    }
}

/// Extract pre-key IDs from a PreKeySignalMessage.
/// Returns 0 on success, negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_prekey_message_get_ids(
    ciphertext: *const u8,
    ciphertext_len: i32,
    out_pre_key_id: *mut i32,
    out_signed_pre_key_id: *mut i32,
) -> i32 {
    let ct = match require_bytes(ciphertext, ciphertext_len) {
        Ok(b) => b, Err(e) => return e,
    };
    match ops::prekey_message_get_ids(ct) {
        Ok((pk_id, spk_id)) => {
            if !out_pre_key_id.is_null() { *out_pre_key_id = pk_id; }
            if !out_signed_pre_key_id.is_null() { *out_signed_pre_key_id = spk_id; }
            TRICK_SUCCESS
        }
        Err(e) => signal_error_to_code(&e),
    }
}

/// Decrypt a Signal protocol message.
/// Returns 0 on success, negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_decrypt_message(
    identity_public: *const u8, identity_public_len: i32,
    identity_private: *const u8, identity_private_len: i32,
    registration_id: i32,
    address_name: *const c_char,
    device_id: i32,
    session_record: *const u8, session_record_len: i32,
    peer_identity: *const u8, peer_identity_len: i32,
    pre_key_record: *const u8, pre_key_record_len: i32,
    signed_pre_key_record: *const u8, signed_pre_key_record_len: i32,
    kyber_pre_key_record: *const u8, kyber_pre_key_record_len: i32,
    ciphertext: *const u8, ciphertext_len: i32,
    message_type: i32,
    // Outputs
    out_plaintext: *mut u8, out_pt_cap: i32, out_pt_len: *mut i32,
    out_updated_session: *mut u8, out_sess_cap: i32, out_sess_len: *mut i32,
    out_consumed_pre_key_id: *mut i32,
    out_consumed_kyber_pre_key_id: *mut i32,
    out_sender_identity: *mut u8, out_id_cap: i32, out_id_len: *mut i32,
) -> i32 {
    let id_pub = match require_bytes(identity_public, identity_public_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let id_priv = match require_bytes(identity_private, identity_private_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let addr = match read_cstr(address_name) {
        Ok(s) => s, Err(e) => return e,
    };
    // session_record is optional: empty/null is valid for first-contact PreKeySignalMessage decrypts
    let sess = read_bytes(session_record, session_record_len).unwrap_or(&[]);
    let ct = match require_bytes(ciphertext, ciphertext_len) {
        Ok(b) => b, Err(e) => return e,
    };

    let peer_id = read_bytes(peer_identity, peer_identity_len);
    let pk = read_bytes(pre_key_record, pre_key_record_len);
    let spk = read_bytes(signed_pre_key_record, signed_pre_key_record_len);
    let kpk = read_bytes(kyber_pre_key_record, kyber_pre_key_record_len);

    match ops::decrypt_message(
        id_pub, id_priv, registration_id as u32,
        addr, device_id as u32,
        sess, peer_id, pk, spk, kpk,
        ct, message_type,
    ) {
        Ok(result) => {
            let pt_written = write_output(&result.plaintext, out_plaintext, out_pt_cap);
            if pt_written < 0 { return pt_written; }
            if !out_pt_len.is_null() { *out_pt_len = pt_written; }

            let sess_written = write_output(&result.updated_session_record, out_updated_session, out_sess_cap);
            if sess_written < 0 { return sess_written; }
            if !out_sess_len.is_null() { *out_sess_len = sess_written; }

            if !out_consumed_pre_key_id.is_null() { *out_consumed_pre_key_id = result.consumed_pre_key_id; }
            if !out_consumed_kyber_pre_key_id.is_null() { *out_consumed_kyber_pre_key_id = result.consumed_kyber_pre_key_id; }

            let id_written = write_output(&result.sender_identity_key, out_sender_identity, out_id_cap);
            if id_written < 0 { return id_written; }
            if !out_id_len.is_null() { *out_id_len = id_written; }

            TRICK_SUCCESS
        }
        Err(e) => signal_error_to_code(&e),
    }
}

// =============================================================================
// Utility
// =============================================================================

/// Extract public key from a serialized PreKeyRecord.
/// Returns bytes written, or negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_prekey_record_get_public_key(
    record: *const u8, record_len: i32,
    out_public_key: *mut u8, out_cap: i32,
) -> i32 {
    let rec = match require_bytes(record, record_len) {
        Ok(b) => b, Err(e) => return e,
    };
    match ops::prekey_record_get_public_key(rec) {
        Ok(pk) => write_output(&pk, out_public_key, out_cap),
        Err(e) => signal_error_to_code(&e),
    }
}

/// Extract public key and signature from a serialized SignedPreKeyRecord.
/// Returns 0 on success, negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_signed_prekey_record_get_public_key(
    record: *const u8, record_len: i32,
    out_public_key: *mut u8, out_pub_cap: i32, out_pub_len: *mut i32,
    out_signature: *mut u8, out_sig_cap: i32, out_sig_len: *mut i32,
) -> i32 {
    let rec = match require_bytes(record, record_len) {
        Ok(b) => b, Err(e) => return e,
    };
    match ops::signed_prekey_record_get_public_key(rec) {
        Ok(result) => {
            let pk_w = write_output(&result.public_key, out_public_key, out_pub_cap);
            if pk_w < 0 { return pk_w; }
            if !out_pub_len.is_null() { *out_pub_len = pk_w; }
            let sig_w = write_output(&result.signature, out_signature, out_sig_cap);
            if sig_w < 0 { return sig_w; }
            if !out_sig_len.is_null() { *out_sig_len = sig_w; }
            TRICK_SUCCESS
        }
        Err(e) => signal_error_to_code(&e),
    }
}

/// Extract public key and signature from a serialized KyberPreKeyRecord.
/// Returns 0 on success, negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_kyber_prekey_record_get_public_key(
    record: *const u8, record_len: i32,
    out_public_key: *mut u8, out_pub_cap: i32, out_pub_len: *mut i32,
    out_signature: *mut u8, out_sig_cap: i32, out_sig_len: *mut i32,
) -> i32 {
    let rec = match require_bytes(record, record_len) {
        Ok(b) => b, Err(e) => return e,
    };
    match ops::kyber_prekey_record_get_public_key(rec) {
        Ok(result) => {
            let pk_w = write_output(&result.public_key, out_public_key, out_pub_cap);
            if pk_w < 0 { return pk_w; }
            if !out_pub_len.is_null() { *out_pub_len = pk_w; }
            let sig_w = write_output(&result.signature, out_signature, out_sig_cap);
            if sig_w < 0 { return sig_w; }
            if !out_sig_len.is_null() { *out_sig_len = sig_w; }
            TRICK_SUCCESS
        }
        Err(e) => signal_error_to_code(&e),
    }
}

/// Check if a session record has a sender chain.
/// Returns 1 if true, 0 if false, negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_session_record_has_sender_chain(
    record: *const u8, record_len: i32,
) -> i32 {
    let rec = match require_bytes(record, record_len) {
        Ok(b) => b, Err(e) => return e,
    };
    match ops::session_record_has_sender_chain(rec) {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(e) => signal_error_to_code(&e),
    }
}

/// Sign data with an EC private key.
/// Returns bytes written to out_signature, or negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_private_key_sign(
    private_key: *const u8, private_key_len: i32,
    data: *const u8, data_len: i32,
    out_signature: *mut u8, out_cap: i32,
) -> i32 {
    let pk = match require_bytes(private_key, private_key_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let d = match require_bytes(data, data_len) {
        Ok(b) => b, Err(e) => return e,
    };
    match ops::private_key_sign(pk, d) {
        Ok(sig) => write_output(&sig, out_signature, out_cap),
        Err(e) => signal_error_to_code(&e),
    }
}

/// Verify signature with an EC public key.
/// Returns 1 if valid, 0 if invalid, negative on error.
#[no_mangle]
pub unsafe extern "C" fn trick_public_key_verify(
    public_key: *const u8, public_key_len: i32,
    data: *const u8, data_len: i32,
    signature: *const u8, signature_len: i32,
) -> i32 {
    let pk = match require_bytes(public_key, public_key_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let d = match require_bytes(data, data_len) {
        Ok(b) => b, Err(e) => return e,
    };
    let sig = match require_bytes(signature, signature_len) {
        Ok(b) => b, Err(e) => return e,
    };
    match ops::public_key_verify(pk, d, sig) {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(e) => signal_error_to_code(&e),
    }
}

/// Free a Rust-allocated buffer. Currently unused since we use caller-provided
/// buffers, but kept for potential future use.
#[no_mangle]
pub unsafe extern "C" fn trick_free_buffer(ptr: *mut u8, len: i32) {
    if !ptr.is_null() && len > 0 {
        let _ = Vec::from_raw_parts(ptr, len as usize, len as usize);
    }
}
