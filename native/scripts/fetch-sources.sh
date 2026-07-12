#!/usr/bin/env bash
# Downloads the pinned upstream source tarballs into native/third_party/.
set -euo pipefail
cd "$(dirname "$0")/.."
source VERSIONS.env

mkdir -p third_party
cd third_party

fetch() { # name url dir
    local name="$1" url="$2" dir="$3"
    if [ -d "$dir" ]; then echo "$name: already present ($dir)"; return; fi
    echo "$name: downloading $url"
    curl -fL --retry 3 -o "$name.tar.gz" "$url"
    shasum -a 256 "$name.tar.gz" | tee "$name.sha256"
    tar xzf "$name.tar.gz"
    rm "$name.tar.gz"
    [ -d "$dir" ] || { echo "ERROR: expected directory $dir after extraction"; exit 1; }
}

fetch "libwebp" "$LIBWEBP_URL" "libwebp-$LIBWEBP_VERSION"
fetch "libavif" "$LIBAVIF_URL" "libavif-$LIBAVIF_VERSION"
fetch "aom"     "$AOM_URL"     "libaom-$AOM_VERSION"

echo "All sources present under native/third_party/"
