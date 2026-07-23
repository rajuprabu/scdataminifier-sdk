#!/usr/bin/env bash
# Desktop/Linux verification build of both decode-only flavours (NOT shipped — the shipped
# artifacts are Android .so + iOS xcframework). Used to confirm on a real toolchain that each
# flavour compiles, decodes, exports only scdec_* symbols, and leaks no codec strings.
#
# Produces out/linux-<arch>/flavourA/libscdec.so and .../flavourB/libscdec.so
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MN_DIR="$(dirname "$SCRIPT_DIR")"
NATIVE_DIR="$(dirname "$MN_DIR")/native"
ARCH="$(uname -m)"

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    export JAVA_HOME
fi

bash "$NATIVE_DIR/scripts/fetch-sources.sh"

for FMT in 1 2; do
    NAME=$([ "$FMT" = 1 ] && echo flavourA || echo flavourB)
    OUT="$MN_DIR/out/linux-$ARCH/$NAME"
    mkdir -p "$OUT"
    bash "$SCRIPT_DIR/build-deps-decoder.sh" "$FMT" "$OUT/deps-build" "$OUT/deps"
    cmake -S "$MN_DIR" -B "$OUT/build" \
        -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$OUT/deps" \
        -DSCDEC_FMT="$FMT" -DSCDEC_JNI=ON
    cmake --build "$OUT/build" -j
    cp "$OUT/build/libscdec.so" "$OUT/"
    strip --strip-unneeded "$OUT/libscdec.so" 2>/dev/null || true
    python3 "$SCRIPT_DIR/scrub.py" "$OUT/libscdec.so"
    bash "$SCRIPT_DIR/assert-clean.sh" "$OUT/libscdec.so"
    echo "Built: $OUT/libscdec.so ($(du -h "$OUT/libscdec.so" | cut -f1))"
done
