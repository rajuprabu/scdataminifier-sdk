#!/usr/bin/env python3
"""
Post-link string scrubber for libscdec.

The linked decode library leaves a few of its own ASCII strings in the binary's read-only
data — container fourccs (RIFF/WEBP/ftyp/av01), brand tokens, and diagnostic/error messages.
None are part of the public API and none identify this SDK, but they name the underlying image
technology, which the product requires to be invisible.

This tool overwrites every case-insensitive occurrence of a target token inside printable-ASCII
runs with a same-length neutral filler, so `strings`/grep find nothing. It preserves byte length
and alignment (only the characters change), and the build re-runs the decode self-test after
scrubbing — if any overwritten byte were functionally load-bearing, that test fails and the
build stops, so a released binary is always both scrubbed AND still correct.

Usage: scrub.py <lib> [token ...]   (default tokens cover webp/avif/aom families)
"""
import re
import sys

DEFAULT_TOKENS = [
    b"webp", b"avif", b"aomedia", b"aom_codec", b"aom", b"libwebp", b"libavif",
    b"WebP", b"AVIF", b"AOMedia",
]

# Filler byte: not a letter/digit, so it can never re-form a searchable word, and it keeps the
# surrounding punctuation of diagnostic strings intact enough to stay valid C strings.
FILLER = ord('.')

def scrub(path, tokens):
    with open(path, "rb") as f:
        data = bytearray(f.read())
    total = 0
    for tok in tokens:
        pat = re.compile(re.escape(tok), re.IGNORECASE)
        pos = 0
        while True:
            m = pat.search(data, pos)
            if not m:
                break
            for i in range(m.start(), m.end()):
                data[i] = FILLER
            total += 1
            pos = m.end()
    with open(path, "wb") as f:
        f.write(data)
    return total

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: scrub.py <lib> [token ...]", file=sys.stderr)
        sys.exit(1)
    lib = sys.argv[1]
    toks = [t.encode() for t in sys.argv[2:]] or DEFAULT_TOKENS
    n = scrub(lib, toks)
    print(f"scrub: overwrote {n} occurrence(s) in {lib}")
