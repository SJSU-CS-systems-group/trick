# Claude AI Development Guidelines for Trick

This document outlines the development rules and best practices for working on the Trick Kotlin Multiplatform messaging application.

## Core Principles

### 1. Kotlin Multiplatform First
- **Always use Kotlin Multiplatform-friendly APIs** - Prefer libraries and APIs that work across all platforms (Android, iOS)
- **Use `expect/actual` declarations** for platform-specific implementations
  - Define `expect` declarations in `commonMain`
  - Provide `actual` implementations in `androidMain` and `iosMain`
  - Keep platform-specific code isolated to platform source sets
- **Avoid platform-specific dependencies in common code** - Only use multiplatform libraries in `commonMain`
- **Use Kotlin Coroutines and Flow** for asynchronous operations (works across all platforms)

### 2. Signal Protocol (Double Ratchet)

#### Architecture Overview
- **Rust FFI Layer**: All Signal Protocol operations go through a unified Rust FFI layer (`rust/trick-signal-ffi`)
- **Official Library**: Uses the official Signal Protocol library (libsignal) from `https://github.com/signalapp/libsignal` version **v0.86.7**
- **Cross-Platform Bridge**: Single Rust codebase with platform-specific FFI bindings:
  - **Android**: JNI bridge (`SignalNativeBridge.android.kt`) → `libtrick_signal_ffi.so`
  - **iOS**: C interop bridge (`SignalNativeBridge.ios.kt`) → `libtrick_signal_ffi.a`
- **Common Interface**: `SignalNativeBridge` (expect object) in `commonMain` provides unified API
- **Wrapper Layer**: `LibSignalManager` in `commonMain` delegates to `SignalNativeBridge` for both platforms

#### Key Components
- **Rust Crate**: `rust/trick-signal-ffi/` wraps `libsignal-protocol` and `libsignal-core` v0.86.7
- **Platform Bridges**:
  - Android: `composeApp/src/androidMain/kotlin/net/discdd/trick/signal/SignalNativeBridge.android.kt`
  - iOS: `composeApp/src/iosMain/kotlin/net/discdd/trick/signal/SignalNativeBridge.ios.kt`
- **Common API**: `composeApp/src/commonMain/kotlin/net/discdd/trick/signal/SignalNativeBridge.kt` (expect)
- **Session Management**: `SignalSessionManager` handles all Signal Protocol operations

#### Protocol Requirements
- **Kyber Post-Quantum Cryptography**: Required (libsignal 0.86.7+ mandates Kyber prekeys)
- **Double Ratchet**: Automatic forward secrecy via session ratcheting
- **X3DH Key Agreement**: Used for initial session establishment
- **All cryptographic operations** must go through `SignalNativeBridge` - never implement custom crypto

#### Building the Rust Library
- **Android**: Run `./gradlew buildRustAndroid` or `rust/trick-signal-ffi/build-android.sh`
  - Outputs: `libtrick_signal_ffi.so` to `composeApp/src/androidMain/jniLibs/`
- **iOS**: Run `rust/trick-signal-ffi/build-ios.sh`
  - Outputs: XCFramework and static libraries for iOS targets
- **JNI Functions**: Defined in `rust/trick-signal-ffi/src/jni_bridge.rs` (Android only)
- **C FFI Functions**: Defined in `rust/trick-signal-ffi/src/ffi.rs` (iOS/Android)

#### Testing
- **Test encryption/decryption on both platforms** after any Signal Protocol changes
- **Verify Kyber prekey generation** and inclusion in PreKey bundles
- **Test session establishment** via QR code key exchange

### 3. Architecture Patterns

#### Dependency Injection
- **Use Koin for dependency injection** across all platforms
- Structure DI modules:
  - Common module in `commonMain/kotlin/net/discdd/trick/di/Koin.kt`
  - Platform-specific modules passed to `initKoin()` from platform entry points
  - Platform-specific implementations (e.g., `NativeContactsManager`) provided via platform modules
- **Never use platform-specific DI frameworks** (e.g., Dagger/Hilt on Android only)

#### Database
- **Use SQLDelight for database operations** - It's multiplatform and type-safe
- Define schemas in `commonMain/sqldelight/`
- Use coroutines extensions (`sqldelight-coroutines-extensions`) for reactive queries
- **Never use platform-specific database APIs** (e.g., Room on Android, Core Data on iOS) in common code

#### UI
- **Use Compose Multiplatform** for all UI code
- Keep UI code in `commonMain` when possible
- Use platform-specific composables only when necessary (e.g., permission requests)
- **Follow Material Design 3** guidelines for consistent UI

### 4. Code Organization

#### File Structure
```
trick/
├── composeApp/src/
│   ├── commonMain/
│   │   ├── kotlin/           # Shared business logic
│   │   ├── sqldelight/       # Database schemas
│   │   └── composeResources/ # Shared resources
│   ├── androidMain/
│   │   ├── kotlin/           # Android-specific implementations
│   │   ├── jniLibs/          # Native libraries (libtrick_signal_ffi.so)
│   │   └── res/              # Android resources
│   ├── iosMain/
│   │   └── kotlin/           # iOS-specific implementations
│   └── nativeInterop/
│       └── cinterop/          # C interop definitions (libsignal.def)
└── rust/
    └── trick-signal-ffi/     # Rust FFI crate wrapping libsignal
        ├── src/
        │   ├── jni_bridge.rs  # JNI functions for Android
        │   ├── ffi.rs         # C FFI functions for iOS
        │   ├── ops.rs         # Core Signal Protocol operations
        │   └── stores.rs      # In-memory store implementations
        ├── build-android.sh   # Android build script
        └── build-ios.sh       # iOS build script
```

#### Naming Conventions
- **Platform-specific files**: Use `.android.kt` and `.ios.kt` suffixes
  - Example: `TimeUtils.kt` (expect) → `TimeUtils.android.kt` and `TimeUtils.ios.kt`
- **Package structure**: Follow `net.discdd.trick.<module>` pattern
- **Classes**: Use descriptive names, avoid abbreviations

#### Expect/Actual Pattern
```kotlin
// commonMain/kotlin/.../Feature.kt
expect class PlatformFeature {
    fun doSomething()
}

// androidMain/kotlin/.../Feature.android.kt
actual class PlatformFeature {
    actual fun doSomething() {
        // Android implementation
    }
}

// iosMain/kotlin/.../Feature.ios.kt
actual class PlatformFeature {
    actual fun doSomething() {
        // iOS implementation
    }
}
```

### 5. Networking

- **Use Ktor Client** for HTTP operations (multiplatform)
- Configure platform-specific engines:
  - Android: `ktor-client-okhttp`
  - iOS: `ktor-client-darwin`
- **Use Kotlinx Serialization** for JSON parsing (multiplatform)
- **Never use platform-specific networking libraries** (e.g., Retrofit, URLSession directly) in common code

### 6. Reactive Programming

- **Use Kotlin Coroutines and Flow** for reactive data streams
- Prefer `StateFlow` for UI state management
- Use `Flow` for database queries and network streams
- **Avoid platform-specific reactive libraries** (e.g., RxJava, Combine) in common code

### 7. Error Handling

- **Use Kotlin Result types** for operations that can fail
- Handle platform-specific errors gracefully with fallbacks
- Log errors appropriately (use multiplatform logging when available)
- **Never expose platform-specific exceptions** in common interfaces

### 8. Testing

- Write unit tests in `commonTest` for shared business logic
- Test platform-specific implementations separately
- **Test encryption/decryption flows** on both platforms
- Use multiplatform testing frameworks when possible

### 9. Security Best Practices

- **Always use Signal Protocol** for message encryption - never implement custom crypto
- **Store sensitive data securely** using platform-specific secure storage:
  - Android: EncryptedSharedPreferences or Android Keystore
  - iOS: Keychain Services
- **Validate all inputs** before processing
- **Never log sensitive data** (keys, messages, user data)
- **Use HTTPS** for any network communication (when not using peer-to-peer)

### 10. Performance

- **Use coroutines for async operations** - avoid blocking threads
- **Leverage Flow operators** for efficient data transformations
- **Cache expensive computations** when appropriate
- **Profile on both platforms** - performance characteristics may differ

### 11. Platform-Specific Features

#### Android
- Use Android-specific APIs only in `androidMain`
- Request permissions using `rememberLauncherForActivityResult`
- Use `Context` for platform services (passed via DI)

#### iOS
- Use iOS-specific APIs only in `iosMain`
- Request permissions using platform-specific APIs
- Use Foundation APIs via `platform.Foundation.*` imports

### 12. Database Migrations

- **Always use SQLDelight migrations** for schema changes
- Test migrations on both platforms
- **Never modify existing schemas directly** - always create migrations
- Keep migration logic in `commonMain/sqldelight/`

### 13. Dependencies

- **Prefer multiplatform libraries** over platform-specific ones
- Check library compatibility before adding dependencies
- Keep versions in `gradle/libs.versions.toml` for consistency
- **Avoid transitive platform-specific dependencies** in common code

#### Rust Dependencies
- **Signal Protocol**: `libsignal-protocol` and `libsignal-core` v0.86.7 from `https://github.com/signalapp/libsignal`
- **JNI**: `jni` crate v0.21 (Android only, for JNI bridge)
- **Build Tools**: `cbindgen` v0.27 (for C header generation)
- **Rust Version**: 1.85+ required
- **Cargo Dependencies**: Defined in `rust/trick-signal-ffi/Cargo.toml`

### 14. Code Quality

- **Write self-documenting code** with clear function and variable names
- **Add KDoc comments** for public APIs
- **Keep functions small and focused** (single responsibility)
- **Use data classes** for immutable data structures
- **Prefer sealed classes/interfaces** for type-safe state management

### 15. Git Workflow

- **Keep commits focused** - one logical change per commit
- **Write descriptive commit messages**
- **Test on both platforms** before committing
- **Update documentation** when adding new features

## Common Pitfalls to Avoid

1. **Don't use Java/Android-specific APIs in common code**
   - Use Kotlin standard library or multiplatform libraries

2. **Don't implement custom encryption**
   - Always use Signal Protocol via `SignalNativeBridge` (Rust FFI)
   - Never bypass the Rust FFI layer for cryptographic operations

3. **Don't forget to build the Rust library**
   - Android: Run `./gradlew buildRustAndroid` before building the app
   - iOS: Run `rust/trick-signal-ffi/build-ios.sh` before building
   - The native libraries are not auto-built during Gradle build

4. **Don't use platform-specific DI frameworks**
   - Use Koin for multiplatform DI

5. **Don't put platform-specific code in commonMain**
   - Use expect/actual pattern
   - `SignalNativeBridge` is expect/actual, but `LibSignalManager` is common

6. **Don't use blocking I/O**
   - Use coroutines and suspend functions

7. **Don't expose platform-specific types in common interfaces**
   - Use multiplatform types or create common abstractions

8. **Don't forget Kyber prekeys**
   - libsignal 0.86.7+ requires Kyber post-quantum prekeys
   - Always include `kyberPreKeyId`, `kyberPreKeyPublic`, and `kyberPreKeySignature` in PreKey bundles

9. **Don't modify Rust FFI signatures without updating both platforms**
   - Changes to `SignalNativeBridge` expect declaration require updates to both Android and iOS implementations
   - Changes to Rust FFI functions require updating both JNI bridge (Android) and C FFI (iOS)

## Quick Reference

### Adding a New Platform-Specific Feature

1. Define `expect` declaration in `commonMain`
2. Implement `actual` in `androidMain` and `iosMain`
3. Add to DI module if needed
4. Test on both platforms

### Adding a New Dependency

1. Check if multiplatform version exists
2. Add to `gradle/libs.versions.toml`
3. Add to appropriate source set in `build.gradle.kts`
4. Update this document if it's a significant architectural change

### Building the Rust FFI Library

#### Android
```bash
# Option 1: Use Gradle task
./gradlew buildRustAndroid

# Option 2: Use build script directly
cd rust/trick-signal-ffi
./build-android.sh
```

#### iOS
```bash
cd rust/trick-signal-ffi
./build-ios.sh
```

**Note**: The Rust library must be built before running the app. The native libraries are not automatically built during Gradle build (by design, to avoid requiring Rust toolchain for all developers).

### Modifying Database Schema

1. Update `.sq` file in `commonMain/sqldelight/`
2. Create migration if needed
3. Test on both platforms
4. Update repository implementations if needed

## Questions?

When in doubt:
1. Check existing code patterns in the codebase
2. Verify multiplatform compatibility of libraries
3. Test on both Android and iOS
4. Follow the expect/actual pattern for platform differences

