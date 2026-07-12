#!/usr/bin/env bash
# Copies a freshly-built native (from native/out/<platform>/) into the jar-resource layout
# resources/native/<os>-<arch>/ that BundledNativeLoader reads. Run on the build host right
# after its platform build script (build-macos.sh / build-linux.sh). For Windows, the CI
# workflow (or a manual copy) places out\windows-x64\scimage.dll into
# resources/native/windows-x86_64/.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(dirname "$SCRIPT_DIR")"
SDK_DIR="$(dirname "$NATIVE_DIR")"

uname_s="$(uname -s)"
uname_m="$(uname -m)"
case "$uname_m" in
    x86_64|amd64) arch=x86_64 ;;
    arm64|aarch64) arch=aarch64 ;;
    *) arch="$uname_m" ;;
esac

case "$uname_s" in
    Darwin) os=macos;  src="$NATIVE_DIR/out/macos/libscimage.dylib";       lib=libscimage.dylib ;;
    Linux)  os=linux;  src="$NATIVE_DIR/out/linux-$uname_m/libscimage.so"; lib=libscimage.so ;;
    *) echo "Unsupported host $uname_s; on Windows copy out\\windows-x64\\scimage.dll manually"; exit 1 ;;
esac

[ -f "$src" ] || { echo "Native not built: $src (run the platform build script first)"; exit 1; }
dest="$SDK_DIR/resources/native/$os-$arch"
mkdir -p "$dest"
cp "$src" "$dest/$lib"
echo "Installed: resources/native/$os-$arch/$lib"
