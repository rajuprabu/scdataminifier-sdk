#!/usr/bin/env bash
# Obfuscates dist/scdataminifier.jar with Zelix KlassMaster.
#   in:  dist/scdataminifier.jar        (produced by build-jar.sh)
#   out: dist/obf/scdataminifier.jar    (ship this)
# ZKM is licensed/local; override its location with ZKM_HOME if it moves.
set -euo pipefail
cd "$(dirname "$0")"

ZKM_HOME="${ZKM_HOME:-/Users/praburaju/P_DRIVE/ZKM}"
ZKM_JAR="$ZKM_HOME/ZKM.jar"

[ -f dist/scdataminifier.jar ] || { echo "run ./build-jar.sh first"; exit 1; }
[ -f "$ZKM_JAR" ] || { echo "ZKM.jar not found at $ZKM_JAR (set ZKM_HOME)"; exit 1; }

# ZKM resolves the default-exclusion file relative to its own dir; run from there via -de.
java -jar "$ZKM_JAR" -de "$ZKM_HOME/defaultExclude.txt" build/zkm/scdataminifier.zkm

echo "Obfuscated: dist/obf/scdataminifier.jar ($(du -h dist/obf/scdataminifier.jar | cut -f1))"
echo "Name-mapping log: build/zkm/changeLog.txt"
