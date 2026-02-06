#!/bin/bash
# Build trick-signal-ffi for Android targets using cargo-ndk.
#
# Prerequisites:
#   cargo install cargo-ndk
#   rustup target add aarch64-linux-android x86_64-linux-android
#   ANDROID_NDK_HOME must be set (or ANDROID_HOME/ndk/<version>)
#
# Output: .so files in composeApp/src/androidMain/jniLibs/

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
JNI_LIBS_DIR="$PROJECT_ROOT/composeApp/src/androidMain/jniLibs"

echo "Building trick-signal-ffi for Android..."
echo "Output: $JNI_LIBS_DIR"

cd "$SCRIPT_DIR"

cargo ndk \
    -t aarch64-linux-android \
    -t x86_64-linux-android \
    -o "$JNI_LIBS_DIR" \
    build --release

echo ""
echo "Android build complete:"
ls -la "$JNI_LIBS_DIR"/arm64-v8a/libtrick_signal_ffi.so 2>/dev/null || true
ls -la "$JNI_LIBS_DIR"/x86_64/libtrick_signal_ffi.so 2>/dev/null || true
