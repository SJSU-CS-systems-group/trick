# Trick

Trick is a cross-platform offline messaging application that provides end-to-end encrypted communication without server infrastructure. It uses Wi-Fi Aware (Neighbor Awareness Networking) for direct peer-to-peer discovery and communication, and the Signal Protocol with Kyber-1024 post-quantum cryptography for message confidentiality. The application targets scenarios where internet access is unavailable, such as disaster response and remote deployments.

## Motivation

When cellular towers fail during natural disasters, or when networks become congested at crowded events, people still need to communicate with those nearby. Building such a system requires not only a peer-to-peer transport layer, but an encryption protocol capable of establishing and maintaining secure sessions without any central infrastructure. Most established encryption protocols assume server infrastructure for key distribution, message relay, and session initialization. Trick explores how to achieve the security guarantees of the Signal Protocol in a fully serverless, peer-to-peer environment.

## Overview

Two devices running Trick discover each other via Wi-Fi Aware, negotiate roles, and exchange encrypted messages over a direct TCP connection — no access point or internet connection required. All cryptographic operations are performed in a Rust library built on [libsignal](https://github.com/signalapp/libsignal) v0.86.7, shared across Android and iOS via platform-specific FFI bridges.

Users are identified by their public key rather than a phone number or account, eliminating the need for a registration server or certificate authority. Every session combines classical X3DH with Kyber-1024 (PQXDH), so an attacker must break both the classical and post-quantum components to recover plaintext. A single Rust crate handles all cryptographic logic for both Android and iOS, ensuring consistent behavior across platforms.


## Features

- Peer-to-peer messaging over Wi-Fi Aware with no access point required
- End-to-end encryption using the Signal Protocol (Double Ratchet, X3DH, Kyber-1024 post-quantum prekeys)
- QR code-based key exchange for in-person session establishment
- Text and image messaging with Protocol Buffer serialization
- Persistent encrypted message history via SQLDelight
- Automatic reconnection with session state recovery after transport interruptions

## Architecture
<img width="4612" height="2488" alt="trick_system_architecture" src="https://github.com/user-attachments/assets/e86188dd-a2d2-4e71-93f0-708a9795a72e" />


The application is built with Kotlin Multiplatform, sharing a single codebase across Android and iOS with platform-specific networking and secure storage. Below the UI and ViewModel layers, a Signal Session Manager coordinates all encryption and decryption by delegating to a shared Rust FFI layer that wraps libsignal. Cryptographic keys and session records are persisted in a SQLDelight database, with private keys encrypted at rest using hardware-backed storage (Android Keystore or iOS Keychain). At the bottom of the stack, Wi-Fi Aware handles peer discovery and data transport.

When a message is sent, plaintext flows down through the session manager into the Rust layer, where it is encrypted using the Double Ratchet. The resulting ciphertext is transmitted over Wi-Fi Aware. On the receiving device, the ciphertext travels back up through the same stack and is decrypted before delivery. Key distribution occurs out-of-band via QR codes before any messages are sent.



### Signal Protocol Integration

All cryptographic operations are performed in a Rust crate (`rust/trick-signal-ffi`) that wraps libsignal v0.86.7. Rather than maintaining persistent state inside the native layer, the FFI uses a stateless design: each operation receives serialized session state from Kotlin, executes against in-memory store implementations in Rust, and returns updated records for persistence. This makes every FFI call effectively a pure function of its inputs, eliminating state synchronization bugs across the boundary and allowing both platform bridges to be tested against the same Rust core.

The same Rust core runs on both platforms. Android reaches it through a JNI entry point and iOS through a C interop entry point, but the underlying cryptographic logic is identical. A common `expect`/`actual` declaration in shared Kotlin provides a single interface for all higher-level code to call into, regardless of platform.

### Key Distribution via QR Codes

Without a server to host pre-key bundles, Trick distributes them in person via QR codes. When Trick is first launched, a set of long-term identity keys is generated once and stored in the device's secure enclave. To establish a session, each user shares a pre-key bundle containing their identity public key, signed pre-key, Kyber-1024 public key, registration ID, and device identifier.

Because a Kyber-1024 public key is 1,568 bytes — far larger than a 32-byte Curve25519 key — the full bundle exceeds what a single QR code can reliably display. Trick splits the bundle across two sequential QR codes, trading one extra scan for reliable exchange. The physical co-presence required for scanning also serves as an authentication mechanism. Bundles without Kyber keys are rejected outright, making post-quantum protection mandatory.

### Transport Reliability

Wi-Fi Aware connections can drop due to range changes, interference, or device sleep, and the Double Ratchet is sensitive to message loss or duplication. Trick mitigates this with deterministic role negotiation based on hashed device identifiers to prevent duplicate connections, length-prefixed Protocol Buffers over TCP for in-order delivery, and a heartbeat mechanism for disconnection detection. Because the FFI layer is stateless, reconnection simply reloads the last persisted session record without re-initializing any cryptographic state.

### Platform Support

Android-to-Android and iOS-to-iOS communication work on supported devices. Cross-platform iOS-to-Android communication is currently limited by an ecosystem-level compatibility gap: Apple's iOS 26 implementation requires Wi-Fi Aware Specification Version 4.0 with an OS-level device pairing flow that Android devices do not yet fully support. This reflects a platform maturity gap rather than a protocol limitation, and the Signal Protocol implementation requires no changes once the transport gap is resolved.

## Performance

Trick was evaluated on Google Pixel 6, Pixel 6a, and Samsung Galaxy S21 running Android 16, communicating over Wi-Fi Aware. Over 276,000 metrics were collected across five test scenarios.

Text message encryption averages 0.49 ms and decryption 0.42 ms, confirming negligible cryptographic overhead for real-time messaging. End-to-end send latency for text including encryption, serialization, and transmission is 7.4 ms on average with a P95 of 21 ms. Photo messages show higher latency reflecting the cost of encrypting larger payloads, but remain under 50 ms on average for the send path.

Encryption cost scales linearly with payload size, from around 2 ms at 100 bytes to 42 ms at 500 KB, with the bottleneck shifting to the transport layer for mid-size payloads. Steady-state ciphertext overhead is a fixed 93 bytes per message regardless of payload size. The first message in a session carries an additional ~1,750 bytes of X3DH key material; once the ratchet advances, this overhead drops away entirely.

A throughput ramp test sustaining rates from 1 to 50 messages per second achieved 95–100% efficiency at every level and a peak of 47.4 msg/s, with zero delivery failures across 6,133 messages. Under concurrency stress testing up to 500 simultaneous in-flight messages, all messages were delivered with zero loss. In a three-device parallel burst test, all devices achieved consistent throughput of approximately 21.7 msg/s, confirming stable performance across different Android hardware.

## Requirements

Android 8.0 (API level 26) or higher is required for Wi-Fi Aware support. iOS requires iOS 26 or higher for Wi-Fi Aware.

For development, you will need Kotlin Multiplatform, Gradle 8.x, Rust 1.85+ with Cargo, Android NDK r27+, cbindgen 0.27, and Xcode 16+ for iOS builds.

## Building

The Rust FFI library must be compiled before running the app on either platform. It is not built automatically by Gradle.

**Android**

```bash
./gradlew buildRustAndroid
```

This compiles the Rust crate for all Android ABI targets and copies the resulting `.so` files into `composeApp/src/androidMain/jniLibs/`. Then build the app with:

```bash
./gradlew assembleDebug
```

**iOS**

```bash
cd rust/trick-signal-ffi && ./build-ios.sh
```

This produces an XCFramework and static libraries consumed by the iOS Kotlin cinterop configuration. Then open `iosApp/iosApp.xcodeproj` in Xcode and build normally.

## Project Structure

```
trick/
├── composeApp/
│   └── src/
│       ├── commonMain/          # Shared business logic, UI, database schemas
│       ├── androidMain/         # Android implementations and JNI bridge
│       │   └── jniLibs/         # Compiled Rust .so libraries (generated)
│       └── iosMain/             # iOS implementations and C interop bridge
├── iosApp/                      # iOS application entry point (Xcode project)
└── rust/
    └── trick-signal-ffi/        # Rust FFI crate wrapping libsignal
        ├── src/
        │   ├── ops.rs           # Signal Protocol operations
        │   ├── stores.rs        # In-memory Signal stores
        │   ├── ffi.rs           # C FFI interface (iOS)
        │   └── jni_bridge.rs    # JNI interface (Android)
        ├── build-android.sh
        └── build-ios.sh
```

---

## Dependencies

Trick uses [libsignal](https://github.com/signalapp/libsignal) v0.86.7 for all Signal Protocol operations, [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) for the shared UI, [SQLDelight](https://cashapp.github.io/sqldelight/) for the database, [Ktor](https://ktor.io/) for networking, [Wire](https://square.github.io/wire/) for Protocol Buffer serialization, and [Koin](https://insert-koin.io/) for dependency injection. Full version declarations are in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
