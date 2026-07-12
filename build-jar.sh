#!/usr/bin/env bash
# Builds the SCDataMinifier SDK jar with the OS-native binaries embedded.
#
#   dist/scdataminifier.jar          <- compiled classes + native/<os>-<arch>/<lib>
#   (then run obfuscate.sh to produce dist/scdataminifier-obf.jar via ZKM)
#
# Native binaries are taken from resources/native/. Only the platforms whose real
# binary is present get bundled; placeholders (README.txt) are skipped. Build the
# Linux/Windows binaries on their target OS or via CI (see native/PACKAGING.md) and
# drop them into resources/native/<os>-<arch>/ before packaging for release.
set -euo pipefail
cd "$(dirname "$0")"

OUT=build/jar-classes
DIST=dist
rm -rf "$OUT" "$DIST"
mkdir -p "$OUT" "$DIST"

echo "== compiling (Java 17) =="
find src -name '*.java' > build/sources.txt
javac --release 17 -encoding UTF-8 -d "$OUT" @build/sources.txt
echo "   $(find "$OUT" -name '*.class' | wc -l | tr -d ' ') classes"

echo "== embedding native binaries (split + scrambled; raw stays in resources/) =="
# The jar carries ONLY the two opaque parts per OS (app-resources.dat + app-cache.bin),
# never the raw .so/.dll/.dylib. LibraryObfuscator.pack does the split; the SDK reconstructs
# and loads them in memory at runtime via ScImageCodec.loadBundledObfuscatedNative().
for d in resources/native/*/; do
    name=$(basename "$d")
    lib=$(find "$d" -type f ! -name 'README.txt' | head -1)
    if [ -n "$lib" ]; then
        mkdir -p "$OUT/native/$name"
        java -cp "$OUT" com.scdataminifier.image.LibraryObfuscator \
            "$lib" "$OUT/native/$name/app-resources.dat" "$OUT/native/$name/app-cache.bin" >/dev/null
        echo "   + native/$name/{app-resources.dat,app-cache.bin}  (from $(basename "$lib"))"
    else
        echo "   - native/$name (no binary yet — skipped)"
    fi
done

echo "== packaging jar =="
jar --create --file "$DIST/scdataminifier.jar" -C "$OUT" .
echo "Built: $DIST/scdataminifier.jar ($(du -h "$DIST/scdataminifier.jar" | cut -f1))"
