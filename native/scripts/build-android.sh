#!/usr/bin/env bash
# Android: produces out/android/<abi>/libscimage.so (JNI included) for each ABI.
# The same com.scdataminifier.image.NativeImageCodec Java class works on
# Android - package the .so files under app/src/main/jniLibs/<abi>/.
#
# Prerequisites: Android NDK r25+ (set ANDROID_NDK_HOME), cmake.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(dirname "$SCRIPT_DIR")"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your NDK path}"
API=24
ABIS=(arm64-v8a armeabi-v7a x86_64)

"$SCRIPT_DIR/fetch-sources.sh"

for ABI in "${ABIS[@]}"; do
    OUT="$NATIVE_DIR/out/android/$ABI"
    TOOLCHAIN_ARGS=(
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
        -DANDROID_ABI="$ABI"
        -DANDROID_PLATFORM="android-$API"
    )
    echo "########## $ABI ##########"
    "$SCRIPT_DIR/build-deps.sh" "$OUT/deps-build" "$OUT/deps" "${TOOLCHAIN_ARGS[@]}"
    cmake -S "$NATIVE_DIR" -B "$OUT/build" \
        -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$OUT/deps" -DSCIMG_JNI=ON \
        "${TOOLCHAIN_ARGS[@]}"
    cmake --build "$OUT/build" -j
    cp "$OUT/build/libscimage.so" "$OUT/"
    # Strip debug symbols — the unstripped .so is ~50 MB (static aom/webp/avif); stripping
    # cuts it to a few MB, which matters a lot when it is bundled/split into the app.
    STRIP="$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-strip)"
    if [ -x "$STRIP" ]; then
        "$STRIP" --strip-unneeded "$OUT/libscimage.so"
    fi
    echo "Built: $OUT/libscimage.so ($(du -h "$OUT/libscimage.so" | cut -f1))"
done
echo "Copy out/android/<abi>/libscimage.so into your app's jniLibs/<abi>/"
