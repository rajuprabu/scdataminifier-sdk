#!/usr/bin/env bash
# macOS (dev machine): produces out/macos/libscimage.dylib with JNI.
# Requires: Xcode command line tools, cmake, JDK (JAVA_HOME or auto).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(dirname "$SCRIPT_DIR")"
OUT="$NATIVE_DIR/out/macos"

"$SCRIPT_DIR/fetch-sources.sh"
"$SCRIPT_DIR/build-deps.sh" "$OUT/deps-build" "$OUT/deps"

cmake -S "$NATIVE_DIR" -B "$OUT/build" \
    -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$OUT/deps" -DSCIMG_JNI=ON
cmake --build "$OUT/build" -j
cp "$OUT/build/libscimage.dylib" "$OUT/"
echo "Built: $OUT/libscimage.dylib"
