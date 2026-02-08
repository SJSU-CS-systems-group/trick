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

### 1.1. iOS/Xcode Compilation Requirements

**CRITICAL**: All code in `composeApp/src/iosMain/` MUST compile with Xcode's toolchain. This means:

1. **NO Java APIs**: Java standard library (`java.*`, `javax.*`) is NOT available on iOS
   - WRONG: `import java.net.URLEncoder` in `iosMain`
   - CORRECT: `import platform.Foundation.*` and use `NSString.stringByAddingPercentEncodingWithAllowedCharacters`
   
2. **NO Android APIs**: Android-specific APIs are NOT available on iOS
   - WRONG: `import android.content.Context` in `iosMain`
   - CORRECT: Use iOS-specific APIs via `platform.Foundation.*` or `platform.Darwin.*`

3. **Use platform APIs**: Always use platform-specific imports:
   - `platform.Foundation.*` for iOS Foundation framework (NSString, NSData, NSDate, etc.)
   - `platform.Darwin.*` for Darwin-specific APIs
   - `platform.CoreCrypto.*` for cryptographic operations
   - `kotlinx.cinterop.*` for C interop operations

4. **C Interop**: Use `kotlinx.cinterop.*` for C FFI operations:
   - Always add `@OptIn(ExperimentalForeignApi::class)` annotation
   - Use `usePinned` for memory pinning
   - Use `addressOf` for pointer operations

5. **Test compilation**: Generated iOS code should be verified to compile in Xcode

**When in doubt, check existing iOS implementations in `composeApp/src/iosMain/` for reference patterns.**

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
- **Java APIs are available**: Can use `java.net.*`, `java.io.*`, `java.security.*`, `java.util.*`, etc.
- Common Android imports:
  - `java.net.URLEncoder` / `java.net.URLDecoder` for URL encoding
  - `java.util.UUID` for UUID generation
  - `java.io.File` for file operations
  - `java.security.MessageDigest` for hashing
  - `java.security.KeyStore` for secure storage

#### iOS
- **CRITICAL: iOS code MUST compile with Xcode's toolchain**
- **NEVER use Java APIs in iOS code** - Java APIs (`java.*`, `javax.*`) are NOT available on iOS
- **NEVER use Android-specific imports** in `iosMain` source files
- Use iOS-specific APIs only in `iosMain`
- Request permissions using platform-specific APIs
- **Always use `platform.Foundation.*` for iOS native APIs**:
  - `platform.Foundation.NSString` for string operations
  - `platform.Foundation.NSData` for byte arrays
  - `platform.Foundation.NSDate` for dates
  - `platform.Foundation.NSUUID` for UUIDs
  - `platform.Foundation.NSUserDefaults` for preferences
  - `platform.Foundation.NSFileManager` for file operations
  - `platform.Foundation.NSCharacterSet` for character set operations
- **Always use `kotlinx.cinterop.*` for C interop**:
  - `kotlinx.cinterop.ExperimentalForeignApi` annotation required
  - `kotlinx.cinterop.usePinned` for memory pinning
  - `kotlinx.cinterop.addressOf` for pointer operations
  - `kotlinx.cinterop.reinterpret` for type conversions
- **Use `platform.CoreCrypto.*` for cryptographic operations** (not `java.security.*`):
  - `platform.CoreCrypto.CC_SHA256` for SHA-256 hashing
  - `platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH` for digest length
- **Use `platform.Darwin.*` for Darwin-specific APIs** when needed
- **String operations**: Use `NSString` methods, not Java `String` methods
  - Example: `(s as NSString).stringByAddingPercentEncodingWithAllowedCharacters(...)`
- **File I/O**: Use `NSFileManager` and `NSData`, not `java.io.*`
  - Example: `NSFileManager.defaultManager` for file operations
- **Networking**: Use `ktor-client-darwin`, not `java.net.*`
- **Collections**: Use Kotlin standard library collections, not Java collections
- **Memory management**: Always use `usePinned` for memory pinning in C interop operations

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

2. **CRITICAL: Don't use Java APIs in iOS code**
   - WRONG: `import java.net.URLEncoder` in `iosMain`
   - CORRECT: `import platform.Foundation.*` and use `NSString.stringByAddingPercentEncodingWithAllowedCharacters`
   - WRONG: `import java.util.UUID` in `iosMain`
   - CORRECT: `import platform.Foundation.NSUUID` and use `NSUUID().UUIDString()`
   - WRONG: `import java.io.File` in `iosMain`
   - CORRECT: `import platform.Foundation.NSFileManager` and use `NSFileManager.defaultManager`
   - WRONG: `import java.security.MessageDigest` in `iosMain`
   - CORRECT: `import platform.CoreCrypto.CC_SHA256` and use `kotlinx.cinterop` for hashing
   - WRONG: `import java.security.KeyStore` in `iosMain`
   - CORRECT: Use `platform.Security.*` (Keychain Services) or `platform.Foundation.NSUserDefaults`
   - **Always check imports in `iosMain` files - if you see `java.*` or `javax.*`, it's wrong!**

3. **Don't implement custom encryption**
   - Always use Signal Protocol via `SignalNativeBridge` (Rust FFI)
   - Never bypass the Rust FFI layer for cryptographic operations

4. **Don't forget to build the Rust library**
   - Android: Run `./gradlew buildRustAndroid` before building the app
   - iOS: Run `rust/trick-signal-ffi/build-ios.sh` before building
   - The native libraries are not auto-built during Gradle build

5. **Don't use platform-specific DI frameworks**
   - Use Koin for multiplatform DI

6. **Don't put platform-specific code in commonMain**
   - Use expect/actual pattern
   - `SignalNativeBridge` is expect/actual, but `LibSignalManager` is common

7. **Don't use blocking I/O**
   - Use coroutines and suspend functions

8. **Don't expose platform-specific types in common interfaces**
   - Use multiplatform types or create common abstractions

9. **Don't forget Kyber prekeys**
   - libsignal 0.86.7+ requires Kyber post-quantum prekeys
   - Always include `kyberPreKeyId`, `kyberPreKeyPublic`, and `kyberPreKeySignature` in PreKey bundles

10. **Don't modify Rust FFI signatures without updating both platforms**
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

### iOS Code Generation Checklist

When generating iOS code (`iosMain`), verify:

- [ ] **No Java imports**: Check that no `java.*` or `javax.*` imports exist
- [ ] **No Android imports**: Check that no Android-specific imports exist
- [ ] **Foundation APIs**: Use `platform.Foundation.*` for iOS native functionality
- [ ] **C Interop**: Use `kotlinx.cinterop.*` for C FFI operations
- [ ] **Xcode compatibility**: Code must compile with Xcode's Swift/Objective-C toolchain
- [ ] **String handling**: Use `NSString` methods, not Java `String` methods
- [ ] **File operations**: Use `NSFileManager` and `NSData`, not `java.io.*`
- [ ] **Crypto operations**: Use `platform.CoreCrypto.*`, not `java.security.*`
- [ ] **Memory management**: Use `usePinned` for memory pinning in C interop
- [ ] **Annotations**: Add `@OptIn(ExperimentalForeignApi::class)` when using `kotlinx.cinterop`
- [ ] **Reference existing code**: Check `composeApp/src/iosMain/` for similar implementations

### iOS vs Android API Mapping

When implementing platform-specific code, use this mapping as a reference:

| Android (java.*) | iOS (platform.Foundation.*) | Notes |
|------------------|----------------------------|-------|
| `java.net.URLEncoder.encode()` | `NSString.stringByAddingPercentEncodingWithAllowedCharacters(NSCharacterSet.URLQueryAllowedCharacterSet)` | URL encoding |
| `java.net.URLDecoder.decode()` | `NSString.stringByRemovingPercentEncoding` | URL decoding |
| `java.util.UUID.randomUUID().toString()` | `NSUUID().UUIDString()` | UUID generation |
| `java.io.File` | `NSFileManager.defaultManager` | File operations |
| `java.security.MessageDigest.getInstance("SHA-256")` | `platform.CoreCrypto.CC_SHA256` (via `kotlinx.cinterop`) | SHA-256 hashing |
| `java.security.KeyStore` | `platform.Security.*` (Keychain Services) or `NSUserDefaults` | Secure storage |
| `java.util.Date` / `System.currentTimeMillis()` | `NSDate().timeIntervalSince1970 * 1000.0` | Timestamps |
| `java.io.FileInputStream` / `FileOutputStream` | `NSData.dataWithContentsOfFile()` / `writeToFile()` | File I/O |
| `java.nio.ByteBuffer` | `NSData` or `ByteArray` with `kotlinx.cinterop.usePinned` | Byte operations |

**Key Principles:**
- Android can use Java standard library (`java.*`)
- iOS must use Foundation/Darwin APIs (`platform.Foundation.*`, `platform.Darwin.*`)
- For C interop on iOS, always use `kotlinx.cinterop.*` with proper memory management
- When in doubt, check existing iOS implementations in `composeApp/src/iosMain/`

## Questions?

When in doubt:
1. Check existing code patterns in the codebase
2. Verify multiplatform compatibility of libraries
3. Test on both Android and iOS
4. Follow the expect/actual pattern for platform differences

