/// Error codes returned by FFI functions.
/// Success = 0, errors are negative.
pub const TRICK_SUCCESS: i32 = 0;
pub const TRICK_ERROR_INVALID_ARGUMENT: i32 = -1;
pub const TRICK_ERROR_BUFFER_TOO_SMALL: i32 = -2;
pub const TRICK_ERROR_SERIALIZATION: i32 = -3;
pub const TRICK_ERROR_INVALID_KEY: i32 = -4;
pub const TRICK_ERROR_UNTRUSTED_IDENTITY: i32 = -5;
pub const TRICK_ERROR_NO_SESSION: i32 = -6;
pub const TRICK_ERROR_INVALID_MESSAGE: i32 = -7;
pub const TRICK_ERROR_DUPLICATE_MESSAGE: i32 = -8;
pub const TRICK_ERROR_INTERNAL: i32 = -99;

/// Map a libsignal-protocol error to an FFI error code.
pub fn signal_error_to_code(e: &libsignal_protocol::error::SignalProtocolError) -> i32 {
    use libsignal_protocol::error::SignalProtocolError::*;
    match e {
        InvalidArgument(_) => TRICK_ERROR_INVALID_ARGUMENT,
        InvalidState(_, _) => TRICK_ERROR_INTERNAL,
        UntrustedIdentity(_) => TRICK_ERROR_UNTRUSTED_IDENTITY,
        SessionNotFound(_) => TRICK_ERROR_NO_SESSION,
        InvalidMessage(_, _) => TRICK_ERROR_INVALID_MESSAGE,
        DuplicatedMessage(_, _) => TRICK_ERROR_DUPLICATE_MESSAGE,
        InvalidSealedSenderMessage(_) => TRICK_ERROR_INVALID_MESSAGE,
        _ => TRICK_ERROR_INTERNAL,
    }
}
