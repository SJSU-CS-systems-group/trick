//
// LibSignalBridge.h
// C header declarations for Swift LibSignalClient bridge functions
// These match the @_cdecl functions in LibSignalBridge.swift
//

#ifndef LIBSIGNAL_BRIDGE_H
#define LIBSIGNAL_BRIDGE_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Test function to verify LibSignalClient availability
bool testSwiftLibSignalAvailability(void);

// Get version string from LibSignalClient
char* getSwiftLibSignalVersion(void);

// Free Swift-allocated string  
void freeSwiftString(const char* string);

// Generate private key using real LibSignalClient
int32_t generatePrivateKeyData(uint8_t* buffer, int32_t bufferSize);

// Derive public key from private key
int32_t getPublicKeyFromPrivate(
    const uint8_t* privateKeyData, int32_t privateKeySize,
    uint8_t* publicKeyBuffer, int32_t bufferSize
);

// Sign data with private key
int32_t signData(
    const uint8_t* privateKeyData, int32_t privateKeySize,
    const uint8_t* data, int32_t dataSize,
    uint8_t* signatureBuffer, int32_t bufferSize
);

// Verify signature with public key
int32_t verifySignature(
    const uint8_t* publicKeyData, int32_t publicKeySize,
    const uint8_t* data, int32_t dataSize,
    const uint8_t* signature, int32_t signatureSize
);

// HPKE seal with a public key (uses a fixed info string)
int32_t hpkeSeal(
    const uint8_t* publicKeyData, int32_t publicKeySize,
    const uint8_t* message, int32_t messageSize,
    uint8_t* outBuffer, int32_t outBufferSize
);

// HPKE open with a private key (uses the same fixed info string)
int32_t hpkeOpen(
    const uint8_t* privateKeyData, int32_t privateKeySize,
    const uint8_t* ciphertext, int32_t ciphertextSize,
    uint8_t* outBuffer, int32_t outBufferSize
);

// Generate identity key pair
int32_t generateIdentityKeyPair(
    uint8_t* privateKeyBuffer, int32_t privateKeyBufferSize,
    uint8_t* publicKeyBuffer, int32_t publicKeyBufferSize
);

#ifdef __cplusplus
}
#endif

#endif // LIBSIGNAL_BRIDGE_H
