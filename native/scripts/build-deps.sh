#!/usr/bin/env bash
# Builds static libwebp + aom + libavif into a per-target prefix.
#
# Usage: build-deps.sh <build-dir> <install-prefix> [extra cmake args...]
# The extra cmake args are passed to ALL three dependency builds - this is
# how platform scripts inject toolchain files (Android NDK, iOS, MinGW).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(dirname "$SCRIPT_DIR")"
source "$NATIVE_DIR/VERSIONS.env"

BUILD_DIR="$1"; PREFIX="$2"; shift 2
EXTRA_ARGS=()
if [ "$#" -gt 0 ]; then EXTRA_ARGS=("$@"); fi
TP="$NATIVE_DIR/third_party"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

[ -d "$TP/libwebp-$LIBWEBP_VERSION" ] || { echo "Run fetch-sources.sh first"; exit 1; }
mkdir -p "$BUILD_DIR" "$PREFIX"

COMMON=(-DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX"
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"})

echo "=== libwebp $LIBWEBP_VERSION ==="
cmake -S "$TP/libwebp-$LIBWEBP_VERSION" -B "$BUILD_DIR/webp" "${COMMON[@]}" \
    -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF \
    -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_VWEBP=OFF \
    -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_EXTRAS=OFF
cmake --build "$BUILD_DIR/webp" -j "$JOBS"
cmake --install "$BUILD_DIR/webp"

echo "=== aom $AOM_VERSION ==="
cmake -S "$TP/libaom-$AOM_VERSION" -B "$BUILD_DIR/aom" "${COMMON[@]}" \
    -DENABLE_TESTS=0 -DENABLE_EXAMPLES=0 -DENABLE_DOCS=0 -DENABLE_TOOLS=0 \
    -DCONFIG_AV1_ENCODER=1 -DCONFIG_AV1_DECODER=1
cmake --build "$BUILD_DIR/aom" -j "$JOBS"
cmake --install "$BUILD_DIR/aom"

echo "=== libavif $LIBAVIF_VERSION ==="
cmake -S "$TP/libavif-$LIBAVIF_VERSION" -B "$BUILD_DIR/avif" "${COMMON[@]}" \
    -DAVIF_CODEC_AOM=SYSTEM -DAVIF_LIBYUV=OFF -DAVIF_BUILD_APPS=OFF \
    -DAVIF_BUILD_EXAMPLES=OFF -DAVIF_BUILD_TESTS=OFF \
    -DCMAKE_PREFIX_PATH="$PREFIX"
cmake --build "$BUILD_DIR/avif" -j "$JOBS"
cmake --install "$BUILD_DIR/avif"

echo "Dependencies installed to $PREFIX"
