# Building Native Library in IntelliJ IDEA

## Quick Start

### Option 1: Use IntelliJ's Terminal (Easiest)

1. **Open Terminal in IntelliJ:**
   - View → Tool Windows → Terminal (or `Alt+F12` / `⌥F12` on macOS)

2. **Install Rust** (if not already installed):
   ```bash
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   source $HOME/.cargo/env
   ```

3. **Install cargo-ndk:**
   ```bash
   cargo install cargo-ndk
   ```

4. **Add Android targets:**
   ```bash
   rustup target add aarch64-linux-android x86_64-linux-android
   ```

5. **Set Android NDK path:**
   - Find your NDK: File → Settings → Appearance & Behavior → System Settings → Android SDK → SDK Tools → NDK
   - Or check: `$HOME/Library/Android/sdk/ndk/` (macOS)
   ```bash
   export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/<version>
   ```

6. **Build using Gradle task:**
   ```bash
   ./gradlew buildRustAndroid
   ```

### Option 2: Run Gradle Task from IntelliJ

1. **Open Gradle Tool Window:**
   - View → Tool Windows → Gradle (or `Ctrl+Shift+A` → "Gradle")

2. **Navigate to the task:**
   - `composeApp` → `Tasks` → `other` → `buildRustAndroid`

3. **Double-click to run** (or right-click → Run)

**Note:** Make sure Rust and cargo-ndk are installed first (see Option 1, steps 2-4).

### Option 3: Configure Environment Variables in IntelliJ

If you need to set `ANDROID_NDK_HOME` permanently for IntelliJ:

1. **For Gradle tasks:**
   - File → Settings → Build, Execution, Deployment → Build Tools → Gradle
   - Under "Gradle JVM", you can set environment variables
   - Or create/edit `gradle.properties` in project root:
     ```properties
     ANDROID_NDK_HOME=/Users/ronaldli/Library/Android/sdk/ndk/<version>
     ```

2. **For Run Configurations:**
   - Run → Edit Configurations
   - Select your configuration
   - Under "Environment variables", add:
     ```
     ANDROID_NDK_HOME=/Users/ronaldli/Library/Android/sdk/ndk/<version>
     ```

3. **For Terminal:**
   - Add to your shell profile (`~/.zshrc` or `~/.bashrc`):
     ```bash
     export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/<version>
     ```
   - Restart IntelliJ terminal or run `source ~/.zshrc`

## Finding Your Android NDK Version

**In IntelliJ/Android Studio:**
1. File → Settings → Appearance & Behavior → System Settings → Android SDK
2. Click "SDK Tools" tab
3. Check "Show Package Details"
4. Find "NDK (Side by side)" and note the version number

**Or via command line:**
```bash
ls $HOME/Library/Android/sdk/ndk/  # macOS
```

## Verifying the Build

After building, check that the libraries were created:

**In IntelliJ:**
1. Open Project view
2. Navigate to: `composeApp/src/androidMain/jniLibs/`
3. You should see:
   - `arm64-v8a/libtrick_signal_ffi.so`
   - `x86_64/libtrick_signal_ffi.so`

**Or in Terminal:**
```bash
ls -la composeApp/src/androidMain/jniLibs/arm64-v8a/libtrick_signal_ffi.so
ls -la composeApp/src/androidMain/jniLibs/x86_64/libtrick_signal_ffi.so
```

## Troubleshooting

### "cargo: command not found" in IntelliJ Terminal

The terminal might not have Rust in PATH. Try:

1. **Restart IntelliJ** after installing Rust
2. **Or manually add to PATH in terminal:**
   ```bash
   export PATH="$HOME/.cargo/bin:$PATH"
   ```

### Gradle Task Fails

1. Make sure Rust is installed and in PATH
2. Check that `ANDROID_NDK_HOME` is set (see Option 3 above)
3. Verify cargo-ndk is installed: `cargo ndk --version`
4. Check Gradle console for detailed error messages

### Library Still Not Found After Build

1. **Sync Gradle:** File → Sync Project with Gradle Files
2. **Clean and rebuild:** Build → Clean Project, then Build → Rebuild Project
3. **Invalidate caches:** File → Invalidate Caches → Invalidate and Restart

## Next Steps

Once the library is built, you can:
- Run the Android app normally
- The library will be automatically included in the APK
- No need to rebuild unless you change the Rust code

