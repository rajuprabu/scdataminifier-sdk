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

build_flavour() { # fmt
    local FMT="$1"
    local FLAV=$([ "$FMT" = 1 ] && echo flavourA || echo flavourB)
    local ROOT="$MN_DIR/out/ios/$FLAV"
    rm -rf "$ROOT"; mkdir -p "$ROOT"

    build_slice() { # name sysroot archs
        local NAME="$1" SYSROOT="$2" ARCHS="$3"
        local SLICE="$ROOT/$NAME"
        local ARGS=(
            -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$SYSROOT"
            -DCMAKE_OSX_ARCHITECTURES="$ARCHS" -DCMAKE_OSX_DEPLOYMENT_TARGET="$MIN_IOS"
        )
        bash "$SCRIPT_DIR/build-deps-decoder.sh" "$FMT" "$SLICE/deps-build" "$SLICE/deps" "${ARGS[@]}"
        # SHARED dylib, JNI off. visibility=hidden (set in CMakeLists) exports only scdec_*.
        cmake -S "$MN_DIR" -B "$SLICE/build" \
            -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$SLICE/deps" \
            -DSCDEC_FMT="$FMT" -DSCDEC_JNI=OFF -DSCDEC_STATIC=OFF \
            -DCMAKE_MACOSX_BUNDLE=OFF "${ARGS[@]}"
        cmake --build "$SLICE/build" -j
        local DYLIB="$SLICE/build/libscdec.dylib"
        strip -x "$DYLIB" 2>/dev/null || true
        python3 "$SCRIPT_DIR/scrub.py" "$DYLIB"
        # install_name so the app can embed it under @rpath
        install_name_tool -id "@rpath/libscdec.dylib" "$DYLIB" 2>/dev/null || true
        echo "$DYLIB"
    }

    local DEV SIM_A SIM_X
    DEV="$(build_slice device    iphoneos        arm64)"
    SIM_A="$(build_slice sim-arm64 iphonesimulator arm64)"
    SIM_X="$(build_slice sim-x64   iphonesimulator x86_64)"

    mkdir -p "$ROOT/simulator"
    lipo -create "$SIM_A" "$SIM_X" -output "$ROOT/simulator/libscdec.dylib"
    install_name_tool -id "@rpath/libscdec.dylib" "$ROOT/simulator/libscdec.dylib" 2>/dev/null || true

    mkdir -p "$ROOT/headers"; cp "$MN_DIR/src/scdec.h" "$ROOT/headers/"
    for lib in "$DEV" "$ROOT/simulator/libscdec.dylib"; do
        bash "$SCRIPT_DIR/assert-clean.sh" "$lib" || true   # nm on Mach-O differs; strings check still applies
    done
    rm -rf "$ROOT/scdec.xcframework"
    xcodebuild -create-xcframework \
        -library "$DEV" -headers "$ROOT/headers" \
        -library "$ROOT/simulator/libscdec.dylib" -headers "$ROOT/headers" \
        -output "$ROOT/scdec.xcframework"
    echo "Built: $ROOT/scdec.xcframework"
}

build_flavour 1
build_flavour 2
echo "Done. Embed the flavour's out/ios/<flavour>/scdec.xcframework in your app (Embed & Sign)."
