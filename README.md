This is a Kotlin Multiplatform project targeting Android, iOS.

* [/composeApp](./composeApp/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./composeApp/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./composeApp/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./composeApp/src/jvmMain/kotlin)
    folder is the appropriate location.

* [/iosApp](./iosApp/iosApp) contains iOS applications. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.


****## LibSignalClient integration

This project integrates the real Signal Foundation libraries on both platforms:
- iOS: LibSignalClient via CocoaPods, bridged with a small Swift module (`SignalBridge`) and Kotlin/Native cinterop.
- Android: `org.signal:libsignal-android` and `org.signal:libsignal-client` from Maven Central.

Large compiled artifacts (Rust targets, static libraries, Xcode frameworks) are intentionally ignored by Git. You must build them locally by following the instructions below.

### iOS build (Simulator)
1) Install CocoaPods dependencies:
```
cd iosApp
pod install
```
2) Build the iOS app (from the workspace):
```
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 16 Pro'
```
3) From Gradle (to regenerate cinterop if needed):
```
./gradlew composeApp:cinteropLibsignalIosX64 composeApp:compileKotlinIosX64
```

Notes:
- The Swift `SignalBridge` pod exposes C symbols (e.g., `hpkeSeal`, `hpkeOpen`) that call LibSignalClient’s `PublicKey.seal` and `PrivateKey.open` (HPKE, RFC 9180).
- Kotlin/Native imports those via cinterop package `net.discdd.trick.libsignal.bridge`.

### Getting LibSignalClient (Swift) locally
Follow Signal’s Swift setup docs: https://github.com/signalapp/libsignal/tree/main/swift
- We use CocoaPods. Ensure you have Ruby/CocoaPods installed (`gem install cocoapods`).
- In `iosApp/Podfile`, LibSignalClient is declared; run `pod install` to fetch the binary/framework.
- The `iosApp/Pods` folder is ignored in Git; each developer should run `pod install` locally.

### Android build
1) Build debug APK:
```
./gradlew :composeApp:assembleDebug
```
2) Install and run on an emulator:
```
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb shell am start -n net.discdd.trick/.MainActivity
```

### Do not commit binaries / third-party Pods
- `iosApp/Pods/` and `LibSignalClient/` are excluded from Git to avoid large files.
- Run `pod install` locally to obtain LibSignalClient.
- GitHub rejects files >100MB; large artifacts must be built locally or distributed via CI artifacts.

### Cleaning local large files from Git (if necessary)
If large artifacts were accidentally staged/committed locally, you can undo before pushing:
```
git restore --staged iosApp/Pods || true
git rm -r --cached iosApp/Pods || true

git restore --staged LibSignalClient || true
git rm -r --cached LibSignalClient || true

git commit -m "chore: drop Pods/LibSignalClient from index"
```

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…