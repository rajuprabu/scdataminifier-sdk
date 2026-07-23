#!/usr/bin/env bash
# Builds ONLY the decode dependency for one flavour into a per-target prefix.
#
#   Usage: build-deps-decoder.sh <fmt:1|2> <build-dir> <install-prefix> [extra cmake args...]
#
# Flavour 1: libwebp — but only the decoder archive (libwebpdecoder.a) is later linked; the
#            encoder, sharpyuv, aom and avif are never built, so their strings can't leak.
# Flavour 2: aom with the ENCODER DISABLED (decoder only — drops the encoder banners) + libavif.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MN_DIR="$(dirname "$SCRIPT_DIR")"
NATIVE_DIR="$(dirname "$MN_DIR")/native"
source "$NATIVE_DIR/VERSIONS.env"

FMT="$1"; BUILD_DIR="$2"; PREFIX="$3"; shift 3
EXTRA_ARGS=()
if [ "$#" -gt 0 ]; then EXTRA_ARGS=("$@"); fi
TP="$NATIVE_DIR/third_party"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

[ -d "$TP/libwebp-$LIBWEBP_VERSION" ] || { echo "Run native/scripts/fetch-sources.sh first"; exit 1; }
mkdir -p "$BUILD_DIR" "$PREFIX"

COMMON=(-DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX"
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH
        -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH
        -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH
        -DCMAKE_PREFIX_PATH="$PREFIX"
        ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"})

if [ "$FMT" = "1" ]; then
    echo "=== libwebp $LIBWEBP_VERSION (decoder archive only) ==="
    cmake -S "$TP/libwebp-$LIBWEBP_VERSION" -B "$BUILD_DIR/webp" "${COMMON[@]}" \
        -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF \
        -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_VWEBP=OFF \
        -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_EXTRAS=OFF
    # Build the full set (so `cmake --install` finds every archive it expects, incl. sharpyuv),
    # but only libwebpdecoder.a is LINKED by CMakeLists — the encoder + sharpyuv archives are
    # produced here yet never linked, so their symbols/strings can't enter libscdec.
    cmake --build "$BUILD_DIR/webp" -j "$JOBS"
    cmake --install "$BUILD_DIR/webp"
elif [ "$FMT" = "2" ]; then
    echo "=== aom $AOM_VERSION (DECODER ONLY — encoder disabled) ==="
    cmake -S "$TP/libaom-$AOM_VERSION" -B "$BUILD_DIR/aom" "${COMMON[@]}" \
        -DENABLE_TESTS=0 -DENABLE_EXAMPLES=0 -DENABLE_DOCS=0 -DENABLE_TOOLS=0 \
        -DCONFIG_AV1_ENCODER=0 -DCONFIG_AV1_DECODER=1
    cmake --build "$BUILD_DIR/aom" -j "$JOBS"
    cmake --install "$BUILD_DIR/aom"

    echo "=== libavif $LIBAVIF_VERSION (decode) ==="
    cmake -S "$TP/libavif-$LIBAVIF_VERSION" -B "$BUILD_DIR/avif" "${COMMON[@]}" \
        -DAVIF_CODEC_AOM=SYSTEM -DAVIF_CODEC_AOM_ENCODE=OFF -DAVIF_CODEC_AOM_DECODE=ON \
        -DAVIF_LIBYUV=OFF -DAVIF_BUILD_APPS=OFF -DAVIF_BUILD_EXAMPLES=OFF -DAVIF_BUILD_TESTS=OFF \
        -DCMAKE_PREFIX_PATH="$PREFIX"
    cmake --build "$BUILD_DIR/avif" -j "$JOBS"
    cmake --install "$BUILD_DIR/avif"
else
    echo "fmt must be 1 or 2"; exit 1
fi
echo "Decode dependency (flavour $FMT) installed to $PREFIX"
