#!/bin/bash
# Build trick-signal-ffi for iOS targets and create an XCFramework.
#
# Prerequisites:
#   rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
#
# Output:
#   - Static libraries in target/<triple>/release/
#   - Universal simulator lib via lipo
#   - XCFramework at out/TrickSignalFFI.xcframework
#   - Generated header at trick_signal_ffi.h

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$SCRIPT_DIR/out"

echo "Building trick-signal-ffi for iOS..."

cd "$SCRIPT_DIR"

# Build for all iOS targets
echo "Building for aarch64-apple-ios (device)..."
cargo build --release --target aarch64-apple-ios

echo "Building for aarch64-apple-ios-sim (simulator ARM64)..."
cargo build --release --target aarch64-apple-ios-sim

echo "Building for x86_64-apple-ios (simulator x86_64)..."
cargo build --release --target x86_64-apple-ios

# Create output directory
mkdir -p "$OUT_DIR"

# Create universal simulator library
echo "Creating universal simulator library..."
lipo -create \
    target/aarch64-apple-ios-sim/release/libtrick_signal_ffi.a \
    target/x86_64-apple-ios/release/libtrick_signal_ffi.a \
    -output "$OUT_DIR/libtrick_signal_ffi_sim.a"

# Copy device library
cp target/aarch64-apple-ios/release/libtrick_signal_ffi.a "$OUT_DIR/libtrick_signal_ffi_device.a"

# Generate the C header (built by build.rs, should already exist)
if [ -f "$SCRIPT_DIR/trick_signal_ffi.h" ]; then
    cp "$SCRIPT_DIR/trick_signal_ffi.h" "$OUT_DIR/"
    echo "Header copied to $OUT_DIR/trick_signal_ffi.h"
fi

# Create XCFramework
echo "Creating XCFramework..."

# Clean up existing XCFramework
rm -rf "$OUT_DIR/TrickSignalFFI.xcframework"

# Create module map for the header
mkdir -p "$OUT_DIR/include"
cp "$OUT_DIR/trick_signal_ffi.h" "$OUT_DIR/include/" 2>/dev/null || true
cat > "$OUT_DIR/include/module.modulemap" <<'EOF'
module TrickSignalFFI {
    header "trick_signal_ffi.h"
    export *
}
EOF

xcodebuild -create-xcframework \
    -library "$OUT_DIR/libtrick_signal_ffi_device.a" \
    -headers "$OUT_DIR/include" \
    -library "$OUT_DIR/libtrick_signal_ffi_sim.a" \
    -headers "$OUT_DIR/include" \
    -output "$OUT_DIR/TrickSignalFFI.xcframework"

echo ""
echo "iOS build complete:"
echo "  Device lib: $OUT_DIR/libtrick_signal_ffi_device.a"
echo "  Sim lib:    $OUT_DIR/libtrick_signal_ffi_sim.a"
echo "  XCFramework: $OUT_DIR/TrickSignalFFI.xcframework"
echo "  Header:     $OUT_DIR/trick_signal_ffi.h"
