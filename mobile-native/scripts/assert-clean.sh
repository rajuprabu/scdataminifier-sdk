#!/usr/bin/env bash
# Fails the build if a scrubbed artifact still exposes anything it must not:
#   - an exported symbol other than the neutral scdec_* / JNI entry points, or
#   - any literal "webp" / "avif" / "aom" token in its strings.
# Runs on every mobile/desktop build right after scrub.py.
set -euo pipefail
LIB="$1"

# 1) exported symbols — allow only our own.
# GNU nm (ELF): `nm -D`, defined text symbols show type T, no name decoration.
# BSD nm (Mach-O): `nm -gU`, exported symbols carry a leading underscore (_scdec_open).
if command -v nm >/dev/null 2>&1; then
    SYMS="$(nm -D "$LIB" 2>/dev/null || nm -gU "$LIB" 2>/dev/null || true)"
    BAD="$(printf '%s\n' "$SYMS" | awk '$2=="T"||$2=="t"{print $3}' \
        | sed 's/^_//' | grep -vE '^(scdec_|Java_com_scdataminifier_decoder_ScDecoder_)' || true)"
    if [ -n "$BAD" ]; then
        echo "assert-clean: FAIL — unexpected exported symbol(s) in $LIB:" >&2
        echo "$BAD" >&2
        exit 1
    fi
fi

# 2) codec-naming strings — must be zero
for tok in webp avif aom aomedia; do
    n="$(strings "$LIB" | grep -ic "$tok" || true)"
    if [ "$n" -ne 0 ]; then
        echo "assert-clean: FAIL — '$tok' appears $n time(s) in $LIB" >&2
        strings "$LIB" | grep -i "$tok" | head >&2
        exit 1
    fi
done

echo "assert-clean: OK — $(basename "$LIB") exports only scdec_*/JNI and names no codec"
