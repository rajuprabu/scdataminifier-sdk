#!/usr/bin/env bash
# iOS: produces out/ios/scimage.xcframework - a STATIC library with the plain
# C API (scimage_codec.h) and its dependencies merged; no JNI on iOS.
# Call from Swift/ObjC:
#   var size = 0; let data = scimg_encode_avif(rgb, w, h, 60, 6, &size)
#
# Prerequisites: Xcode + command line tools, cmake (brew install cmake).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(dirname "$SCRIPT_DIR")"
OUT="$NATIVE_DIR/out/ios"
MIN_IOS=15.0

"$SCRIPT_DIR/fetch-sources.sh"

build_slice() { # name sysroot archs
    local NAME="$1" SYSROOT="$2" ARCHS="$3"
    local SLICE="$OUT/$NAME"
    local ARGS=(
        -DCMAKE_SYSTEM_NAME=iOS
        -DCMAKE_OSX_SYSROOT="$SYSROOT"
        -DCMAKE_OSX_ARCHITECTURES="$ARCHS"
        -DCMAKE_OSX_DEPLOYMENT_TARGET="$MIN_IOS"
    )
    "$SCRIPT_DIR/build-deps.sh" "$SLICE/deps-build" "$SLICE/deps" "${ARGS[@]}"
    cmake -S "$NATIVE_DIR" -B "$SLICE/build" \
        -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$SLICE/deps" \
        -DSCIMG_JNI=OFF -DSCIMG_STATIC=ON "${ARGS[@]}"
    cmake --build "$SLICE/build" -j
    # merge scimage + deps into one archive per slice
    libtool -static -o "$SLICE/libscimage-full.a" \
        "$SLICE/build/libscimage.a" \
        "$SLICE"/deps/lib/libavif.a "$SLICE"/deps/lib/libaom.a \
        "$SLICE"/deps/lib/libwebp.a "$SLICE"/deps/lib/libsharpyuv.a
}

build_slice device    iphoneos        arm64
build_slice simulator iphonesimulator "arm64;x86_64"

mkdir -p "$OUT/headers"
cp "$NATIVE_DIR/src/scimage_codec.h" "$OUT/headers/"
rm -rf "$OUT/scimage.xcframework"
xcodebuild -create-xcframework \
    -library "$OUT/device/libscimage-full.a"    -headers "$OUT/headers" \
    -library "$OUT/simulator/libscimage-full.a" -headers "$OUT/headers" \
    -output "$OUT/scimage.xcframework"
echo "Built: $OUT/scimage.xcframework"
