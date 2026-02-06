//! trick-signal-ffi: Shared Rust FFI crate for Signal Protocol operations.
//!
//! Used by both Android (via JNI) and iOS (via cinterop) to provide
//! wire-compatible Signal encryption across platforms.

pub mod error;
pub mod stores;
pub mod ops;
pub mod ffi;

#[cfg(target_os = "android")]
pub mod jni_bridge;
