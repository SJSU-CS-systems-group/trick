#ifndef LIBSIGNAL_BRIDGE_H
#define LIBSIGNAL_BRIDGE_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

bool testSwiftLibSignalAvailability(void);
char* getSwiftLibSignalVersion(void);
void freeSwiftString(const char* string);

int32_t generatePrivateKeyData(uint8_t* buffer, int32_t bufferSize);
int32_t getPublicKeyFromPrivate(const uint8_t* privateKeyData, int32_t privateKeySize,
                                uint8_t* publicKeyBuffer, int32_t bufferSize);
int32_t signData(const uint8_t* privateKeyData, int32_t privateKeySize,
                 const uint8_t* data, int32_t dataSize,
                 uint8_t* signatureBuffer, int32_t bufferSize);
int32_t verifySignature(const uint8_t* publicKeyData, int32_t publicKeySize,
                        const uint8_t* data, int32_t dataSize,
                        const uint8_t* signature, int32_t signatureSize);

// HPKE seal with a public key (uses a fixed info string)
int32_t hpkeSeal(const uint8_t* publicKeyData, int32_t publicKeySize,
                 const uint8_t* message, int32_t messageSize,
                 uint8_t* outBuffer, int32_t outBufferSize);

// HPKE open with a private key (uses the same fixed info string)
int32_t hpkeOpen(const uint8_t* privateKeyData, int32_t privateKeySize,
                 const uint8_t* ciphertext, int32_t ciphertextSize,
                 uint8_t* outBuffer, int32_t outBufferSize);

#ifdef __cplusplus
}
#endif

#endif // LIBSIGNAL_BRIDGE_H


