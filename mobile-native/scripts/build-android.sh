#!/usr/bin/env bash
# Android: builds BOTH decode-only flavours for every ABI, scrubs and asserts each.
#
# Output: out/android/<flavour>/<abi>/libscdec.so   (flavour = flavourA | flavourB)
# Package each flavour's ABIs under your app's src/main/jniLibs/<abi>/libscdec.so — an app
# links exactly ONE flavour (both use the same library/symbol names).
#
# Prerequisites: Android NDK r25+ (ANDROID_NDK_HOME), cmake, nasm, python3.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MN_DIR="$(dirname "$SCRIPT_DIR")"
NATIVE_DIR="$(dirname "$MN_DIR")/native"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your NDK path}"
API=24
ABIS=(arm64-v8a armeabi-v7a x86_64)

bash "$NATIVE_DIR/scripts/fetch-sources.sh"
STRIP="$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-strip)"

for FMT in 1 2; do
    FLAV=$([ "$FMT" = 1 ] && echo flavourA || echo flavourB)
    for ABI in "${ABIS[@]}"; do
        OUT="$MN_DIR/out/android/$FLAV/$ABI"
        mkdir -p "$OUT"
        TC=(
            -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
            -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API"
        )
        echo "########## flavour $FMT / $ABI ##########"
        bash "$SCRIPT_DIR/build-deps-decoder.sh" "$FMT" "$OUT/deps-build" "$OUT/deps" "${TC[@]}"
        cmake -S "$MN_DIR" -B "$OUT/build" \
            -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$OUT/deps" \
            -DSCDEC_FMT="$FMT" -DSCDEC_JNI=ON "${TC[@]}"
        cmake --build "$OUT/build" -j
        cp "$OUT/build/libscdec.so" "$OUT/"
        [ -x "$STRIP" ] && "$STRIP" --strip-unneeded "$OUT/libscdec.so"
        python3 "$SCRIPT_DIR/scrub.py" "$OUT/libscdec.so"
        bash "$SCRIPT_DIR/assert-clean.sh" "$OUT/libscdec.so"
        echo "Built: $OUT/libscdec.so ($(du -h "$OUT/libscdec.so" | cut -f1))"
    done
done
echo "Done. Package out/android/<flavour>/<abi>/libscdec.so into jniLibs/<abi>/."
