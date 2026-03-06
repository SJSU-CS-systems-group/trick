# Development Guidelines for Trick

Development rules and best practices for the Trick Kotlin Multiplatform messaging application.

## Core Principles

### 1. Kotlin Multiplatform First
- **Use multiplatform-friendly APIs** - Prefer libraries that work across Android and iOS
- **Use `expect/actual` declarations** for platform-specific implementations
  - Define `expect` in `commonMain`, provide `actual` in `androidMain` and `iosMain`
  - Keep platform-specific code isolated to platform source sets
- **Avoid platform-specific dependencies in common code** - Only use multiplatform libraries in `commonMain`
- **Use Kotlin Coroutines and Flow** for asynchronous operations

### 2. Platform-Specific Requirements

#### iOS - CRITICAL Rules
**All code in `composeApp/src/iosMain/` MUST compile with Xcode's toolchain.**

- **NO Java APIs**: `java.*` and `javax.*` are NOT available on iOS
- **NO Android APIs**: Android-specific imports are NOT available
- **Use iOS platform APIs**:
  - `platform.Foundation.*` for NSString, NSData, NSDate, NSUUID, NSFileManager, etc.
  - `platform.CoreCrypto.*` for cryptographic operations (not `java.security.*`)
  - `platform.Darwin.*` for Darwin-specific APIs
  - `kotlinx.cinterop.*` for C FFI (requires `@OptIn(ExperimentalForeignApi::class)`)
- **Common mappings**:
  - URL encoding: `NSString.stringByAddingPercentEncodingWithAllowedCharacters(...)`
  - UUID: `NSUUID().UUIDString()`
  - File I/O: `NSFileManager.defaultManager` and `NSData`
  - Hashing: `platform.CoreCrypto.CC_SHA256` with `kotlinx.cinterop`
  - Secure storage: `platform.Security.*` (Keychain) or `NSUserDefaults`
- **Always check imports** - if you see `java.*` or `javax.*` in `iosMain`, it's wrong!
- **Reference existing code**: Check `composeApp/src/iosMain/` for patterns

#### Android
- Java standard library available: `java.net.*`, `java.io.*`, `java.security.*`, `java.util.*`
- Use Android-specific APIs only in `androidMain`
- Request permissions using `rememberLauncherForActivityResult`

### 3. Signal Protocol (Double Ratchet)

#### Architecture
- **Rust FFI Layer**: All Signal Protocol operations through `rust/trick-signal-ffi`
- **Official Library**: libsignal v0.86.7 from `https://github.com/signalapp/libsignal`
- **Platform Bridges**:
  - Android: JNI bridge (`SignalNativeBridge.android.kt`) → `libtrick_signal_ffi.so`
  - iOS: C interop bridge (`SignalNativeBridge.ios.kt`) → `libtrick_signal_ffi.a`
- **Common Interface**: `SignalNativeBridge` (expect object) in `commonMain`
- **Wrapper**: `LibSignalManager` delegates to `SignalNativeBridge` for both platforms

#### Requirements
- **Kyber Post-Quantum Cryptography**: Required (libsignal 0.86.7+ mandates Kyber prekeys)
- **All cryptographic operations** must go through `SignalNativeBridge` - never implement custom crypto
- **Test encryption/decryption on both platforms** after any changes

#### Building Rust Library
- **Android**: `./gradlew buildRustAndroid` or `rust/trick-signal-ffi/build-android.sh`
  - Outputs: `libtrick_signal_ffi.so` to `composeApp/src/androidMain/jniLibs/`
- **iOS**: `rust/trick-signal-ffi/build-ios.sh`
  - Outputs: XCFramework and static libraries
- **Note**: Rust library must be built before running the app (not auto-built by Gradle)

### 4. Architecture Patterns

#### Dependency Injection
- **Use Koin** for multiplatform DI
- Common module in `commonMain/kotlin/org/trcky/trick/di/Koin.kt`
- Platform-specific modules passed to `initKoin()` from platform entry points

#### Database
- **Use SQLDelight** - multiplatform and type-safe
- Define schemas in `commonMain/sqldelight/`
- Use coroutines extensions for reactive queries
- **Always use migrations** for schema changes - never modify existing schemas directly

#### UI
- **Use Compose Multiplatform** for all UI code
- Keep UI code in `commonMain` when possible
- Follow Material Design 3 guidelines

#### Common Patterns
- **Networking**: Ktor Client (Android: `ktor-client-okhttp`, iOS: `ktor-client-darwin`)
- **Serialization**: Kotlinx Serialization for JSON
- **Reactive**: Kotlin Coroutines and Flow (prefer `StateFlow` for UI state)
- **Error Handling**: Use Kotlin `Result` types, never expose platform-specific exceptions in common interfaces

### 5. Code Organization

#### File Structure
```
trick/
├── composeApp/src/
│   ├── commonMain/          # Shared business logic, schemas, resources
│   ├── androidMain/         # Android implementations, jniLibs/
│   ├── iosMain/             # iOS implementations
│   └── nativeInterop/       # C interop definitions
└── rust/trick-signal-ffi/   # Rust FFI crate (jni_bridge.rs, ffi.rs, ops.rs)
```

#### Naming Conventions
- **Platform-specific files**: Use `.android.kt` and `.ios.kt` suffixes
- **Package structure**: `org.trcky.trick.<module>`
- **Expect/Actual Pattern**: Define `expect` in `commonMain`, implement `actual` in platform source sets

### 6. Security & Best Practices

- **Always use Signal Protocol** for message encryption - never implement custom crypto
- **Store sensitive data securely**: Android (EncryptedSharedPreferences/Keystore), iOS (Keychain)
- **Validate all inputs** before processing
- **Never log sensitive data** (keys, messages, user data)
- **Use HTTPS** for network communication (when not peer-to-peer)
- **Use coroutines for async** - avoid blocking threads
- **Profile on both platforms** - performance characteristics may differ

### 7. Dependencies

- **Prefer multiplatform libraries** over platform-specific ones
- Keep versions in `gradle/libs.versions.toml`
- **Rust Dependencies**: libsignal v0.86.7, JNI v0.21 (Android), cbindgen v0.27, Rust 1.85+

### 8. Testing & Quality

- Write unit tests in `commonTest` for shared business logic
- Test platform-specific implementations separately
- **Test encryption/decryption flows** on both platforms
- Write self-documenting code with clear names
- Add KDoc comments for public APIs
- Keep functions small and focused

## Common Pitfalls

1. **Java APIs in iOS code** - `java.*` and `javax.*` imports will NOT compile on iOS
2. **Custom encryption** - Always use Signal Protocol via `SignalNativeBridge` (Rust FFI)
3. **Forgetting to build Rust library** - Must build before running app (not auto-built)
4. **Platform-specific code in commonMain** - Use expect/actual pattern
5. **Missing Kyber prekeys** - libsignal 0.86.7+ requires Kyber prekeys in PreKey bundles
6. **Rust FFI signature changes** - Must update both Android (JNI) and iOS (C FFI) bridges

## Quick Reference

### Adding Platform-Specific Feature
1. Define `expect` in `commonMain`
2. Implement `actual` in `androidMain` and `iosMain`
3. Add to DI module if needed
4. Test on both platforms

### Building Rust FFI
```bash
# Android
./gradlew buildRustAndroid

# iOS
cd rust/trick-signal-ffi && ./build-ios.sh
```

### Database Migration
1. Update `.sq` file in `commonMain/sqldelight/`
2. Create migration
3. Test on both platforms

### iOS Code Checklist
- [ ] No `java.*` or `javax.*` imports
- [ ] No Android-specific imports
- [ ] Use `platform.Foundation.*` for iOS APIs
- [ ] Use `kotlinx.cinterop.*` for C FFI (with `@OptIn(ExperimentalForeignApi::class)`)
- [ ] Use `platform.CoreCrypto.*` for crypto (not `java.security.*`)
- [ ] Check existing `iosMain` implementations for patterns

## Questions?

When in doubt:
1. Check existing code patterns in the codebase
2. Verify multiplatform compatibility of libraries
3. Test on both Android and iOS
4. Follow the expect/actual pattern for platform differences
