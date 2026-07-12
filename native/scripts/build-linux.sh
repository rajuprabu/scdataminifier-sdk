#!/usr/bin/env bash
# Linux (build on RHEL 9+ or compatible; the .so links glibc of the build
# host, so build on the OLDEST distro you deploy to).
# Produces out/linux-<arch>/libscimage.so with JNI.
#
# RHEL 9 prerequisites:
#   sudo dnf install -y gcc gcc-c++ cmake nasm java-17-openjdk-devel
# (nasm is required by aom's x86_64 assembly.)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(dirname "$SCRIPT_DIR")"
ARCH="$(uname -m)"
OUT="$NATIVE_DIR/out/linux-$ARCH"

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    export JAVA_HOME
fi

"$SCRIPT_DIR/fetch-sources.sh"
"$SCRIPT_DIR/build-deps.sh" "$OUT/deps-build" "$OUT/deps"

cmake -S "$NATIVE_DIR" -B "$OUT/build" \
    -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$OUT/deps" -DSCIMG_JNI=ON
cmake --build "$OUT/build" -j
cp "$OUT/build/libscimage.so" "$OUT/"
echo "Built: $OUT/libscimage.so"
