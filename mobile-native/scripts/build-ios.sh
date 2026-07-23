#!/usr/bin/env bash
# iOS: builds BOTH decode-only flavours as DYNAMIC libraries (one per flavour), scrubs each,
# and packages an xcframework per flavour.
#
# Why dynamic (not the static xcframework the full codec ships): a static library's strings are
# copied into the final app binary at link time, where this project can no longer scrub them —
# the codec name would reappear in the shipped app. A dynamic library is scrubbed here and the
# app embeds it unchanged, so the "names no codec" guarantee survives into the app. Only the
# scdec_* symbols are exported (visibility=hidden), so nothing else leaks either.
#
# Output: out/ios/<flavour>/scdec.xcframework   (flavour = flavourA | flavourB)
# Prerequisites: Xcode + command line tools, cmake, nasm (brew install cmake nasm), python3.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MN_DIR="$(dirname "$SCRIPT_DIR")"
NATIVE_DIR="$(dirname "$MN_DIR")/native"
MIN_IOS=15.0

bash "$NATIVE_DIR/scripts/fetch-sources.sh"

# Build one arch slice into $SLICE/libscdec.dylib. Writes only to fixed paths (no stdout return,
# so noisy build output can never pollute a captured variable).
build_slice() { # fmt slice-dir sysroot archs
    local FMT="$1" SLICE="$2" SYSROOT="$3" ARCHS="$4"
    local ARGS=(
        -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$SYSROOT"
        -DCMAKE_OSX_ARCHITECTURES="$ARCHS" -DCMAKE_OSX_DEPLOYMENT_TARGET="$MIN_IOS"
    )
    bash "$SCRIPT_DIR/build-deps-decoder.sh" "$FMT" "$SLICE/deps-build" "$SLICE/deps" "${ARGS[@]}"
    # SHARED dylib, JNI off. visibility=hidden (CMakeLists) exports only scdec_*.
    cmake -S "$MN_DIR" -B "$SLICE/build" \
        -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$SLICE/deps" \
        -DSCDEC_FMT="$FMT" -DSCDEC_JNI=OFF -DSCDEC_STATIC=OFF "${ARGS[@]}"
    cmake --build "$SLICE/build" -j
    local DYLIB="$SLICE/build/libscdec.dylib"
    # Order matches the Linux reference: set id, strip, then scrub LAST so no cctool rewrites
    # the Mach-O after scrubbing (lipo/xcframework only concatenate/copy).
    install_name_tool -id "@rpath/libscdec.dylib" "$DYLIB"
    strip -x "$DYLIB"
    python3 "$SCRIPT_DIR/scrub.py" "$DYLIB"
    cp "$DYLIB" "$SLICE/libscdec.dylib"
}

build_flavour() { # fmt
    local FMT="$1"
    local FLAV; FLAV=$([ "$FMT" = 1 ] && echo flavourA || echo flavourB)
    local ROOT="$MN_DIR/out/ios/$FLAV"
    rm -rf "$ROOT"; mkdir -p "$ROOT/simulator" "$ROOT/headers"

    build_slice "$FMT" "$ROOT/device"    iphoneos        arm64
    build_slice "$FMT" "$ROOT/sim-arm64" iphonesimulator arm64
    build_slice "$FMT" "$ROOT/sim-x64"   iphonesimulator x86_64

    # One simulator dylib covering both simulator arches.
    lipo -create "$ROOT/sim-arm64/libscdec.dylib" "$ROOT/sim-x64/libscdec.dylib" \
        -output "$ROOT/simulator/libscdec.dylib"

    cp "$MN_DIR/src/scdec.h" "$ROOT/headers/"
    bash "$SCRIPT_DIR/assert-clean.sh" "$ROOT/device/libscdec.dylib"
    bash "$SCRIPT_DIR/assert-clean.sh" "$ROOT/simulator/libscdec.dylib"

    rm -rf "$ROOT/scdec.xcframework"
    xcodebuild -create-xcframework \
        -library "$ROOT/device/libscdec.dylib"    -headers "$ROOT/headers" \
        -library "$ROOT/simulator/libscdec.dylib" -headers "$ROOT/headers" \
        -output "$ROOT/scdec.xcframework"
    echo "Built: $ROOT/scdec.xcframework"
}

build_flavour 1
build_flavour 2
echo "Done. Embed the flavour's out/ios/<flavour>/scdec.xcframework (Embed & Sign)."
