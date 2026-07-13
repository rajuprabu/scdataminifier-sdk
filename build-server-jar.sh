#!/usr/bin/env bash
# Builds a LEAN, Java-only SDK jar for server use (ProjectTrustra middleware) — the compiled
# classes only, NO bundled native binaries (the server signs with ScDataWriter + the HSM and
# never loads the native codec). Then ZKM-obfuscates it.
#
#   dist/server/scdataminifier-lean.jar        <- plain, classes only
#   dist/server-obf/scdataminifier-lean.jar    <- ZKM-obfuscated (ship this to the middleware)
set -euo pipefail
cd "$(dirname "$0")"

OUT=build/server-classes
rm -rf "$OUT" dist/server dist/server-obf
mkdir -p "$OUT" dist/server

echo "== compiling (Java 17) =="
find src -name '*.java' > build/server-sources.txt
javac --release 17 -encoding UTF-8 -d "$OUT" @build/server-sources.txt
echo "   $(find "$OUT" -name '*.class' | wc -l | tr -d ' ') classes (no native binaries)"

echo "== packaging jar =="
jar --create --file dist/server/scdataminifier-lean.jar -C "$OUT" .
echo "   dist/server/scdataminifier-lean.jar ($(du -h dist/server/scdataminifier-lean.jar | cut -f1))"

echo "== ZKM obfuscate =="
ZKM_HOME="${ZKM_HOME:-/Users/praburaju/P_DRIVE/ZKM}"
java -jar "$ZKM_HOME/ZKM.jar" -de "$ZKM_HOME/defaultExclude.txt" build/zkm/scdataminifier-server.zkm
echo "Obfuscated: dist/server-obf/scdataminifier-lean.jar ($(du -h dist/server-obf/scdataminifier-lean.jar | cut -f1))"
