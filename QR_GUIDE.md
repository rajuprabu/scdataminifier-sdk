# SCDataMinifier QR Code generation

Dependency-free QR encoder (`com.scdataminifier.qr`) that renders a payload to
a PNG. Supports **numeric** and **binary (byte)** modes, all versions 1-40 and
all four ECC levels. Encoding only — decoding is the scanner's job.

## API

```java
import com.scdataminifier.qr.SCQrGenerator;
import com.scdataminifier.qr.SCQrGenerator.*;
import com.scdataminifier.qr.Ecc;

QrResult qr = SCQrGenerator.generate(
        payload,               // byte[] to encode
        DataFormat.BINARY,     // or DataFormat.NUMERIC
        version,               // QR version 1-40 (fixed; throws if data won't fit)
        pixelsPerDot,          // pixels per module in the PNG
        Ecc.QUARTILE);         // error-correction level (optional; defaults to MEDIUM)

Files.write(Paths.get("code.png"), qr.pngBytes);
// qr.pixelDimension  = PNG width == height in pixels
// qr.moduleCount     = modules per side (before quiet zone)
// qr.version         = version used
```

Error-correction level is `Ecc.LOW | MEDIUM | QUARTILE | HIGH`. Higher levels
survive more print damage but hold less data (so the same payload may need a
higher version). For labels read by standard 2D hardware scanners, `QUARTILE`
or `HIGH` improves first-read reliability. Omit the argument to default to
`MEDIUM`.

Full overload with ECC level and border width in **modules**:

```java
QrResult qr = SCQrGenerator.generate(payload, DataFormat.NUMERIC, version,
        pixelsPerDot, Ecc.MEDIUM, /*quietZoneModules*/ 4);
// pixelDimension = (moduleCount + 2*quietZoneModules) * pixelsPerDot
```

Border width in **pixels** (for exact output sizes or hardware-scanner
integrations that mandate a specific margin):

```java
QrResult qr = SCQrGenerator.generateWithPixelBorder(payload, DataFormat.BINARY,
        version, pixelsPerDot, Ecc.MEDIUM, /*borderPixels*/ 40);
// pixelDimension = moduleCount * pixelsPerDot + 2 * borderPixels
```

`qr.borderPixels` reports the actual border in pixels; `qr.quietZoneModules`
is the border in modules, or -1 when a pixel border isn't a whole number of
modules. Default ECC is MEDIUM; default border is a 4-module quiet zone.

**Standard 2D hardware scanners** are stricter about the quiet zone than phone
cameras and need at least a 4-module margin. Use
`SCQrGenerator.minBorderPixels(pixelsPerDot)` (= 4 * pixelsPerDot) as the floor
when setting a pixel border; smaller values are allowed but may not scan.

### Choosing the version automatically

```java
int v = SCQrGenerator.minimumVersion(payload, DataFormat.BINARY, Ecc.MEDIUM);
QrResult qr = SCQrGenerator.generate(payload, DataFormat.BINARY, v, 8);
```

## NUMERIC vs BINARY

| | NUMERIC | BINARY (byte mode) |
|---|---|---|
| Encoding | bytes → optimal base-10 digits (~2.408 digits/byte) → QR numeric mode | raw bytes, 8 bits each |
| Density | ~8.03 QR bits/byte | 8.0 QR bits/byte (most compact) |
| Robustness | digits only — survives any scanner / text channel | needs a reader that returns raw bytes (or Latin-1) |
| Use when | third-party or unknown scanners must read it | your own app reads it and can get raw bytes |

For NUMERIC the SDK converts the payload with `bytesToNumeric` (whole-array
BigInteger with a 0x01 sentinel so leading zero bytes survive) and the scanner
side reverses it with `numericToBytes`:

```java
String digits = SCQrGenerator.bytesToNumeric(payload);   // for a numeric QR
byte[] payload = SCQrGenerator.numericToBytes(scannedDigits);
```

Byte mode carries all 256 byte values at the QR spec level (default
interpretation is ISO-8859-1, a 1:1 map). The only caveat is decoder software
that assumes UTF-8 and corrupts bytes ≥ 0x80 — read raw bytes on the viewer
to avoid it.

## Capacity (bytes) by version, ECC level M

Approximate payload bytes that fit (byte mode / numeric mode):

| Version | Modules | Byte mode | Numeric mode |
|---|---|---|---|
| 4  | 33  | 62    | ~102 |
| 10 | 57  | 213   | ~350 |
| 20 | 97  | 666   | ~1090 |
| 40 | 177 | 2331  | ~3800 |

(`generate` throws with the bit counts if the payload doesn't fit the chosen
version — raise the version or lower the ECC.)

## Multi-QR (splitting a large payload)

When a payload is too big for one QR, split it across several that can be
scanned in **any order** and reassembled. The framing is a thin transport
envelope (`com.scdataminifier.qr.MultiQr`); the fragments are raw byte slices
of the final (signed/encrypted) payload, so no security is lost.

The first byte is the single/multi tag: `0x03` = a standalone complete
payload (parse directly), `0x05` = a fragment (collect and reassemble).

**Write:**

```java
List<QrResult> codes = MultiQrWriter.split(payload, DataFormat.NUMERIC, version, pixelsPerDot);
// or the full form with ECC + pixel border:
List<QrResult> codes = MultiQrWriter.split(payload, DataFormat.NUMERIC, version,
        pixelsPerDot, Ecc.MEDIUM, borderPixels);
for (int i = 0; i < codes.size(); i++)
    Files.write(Paths.get("code-" + (i+1) + ".png"), codes.get(i).pngBytes);
```

If the payload fits one QR, `split` returns a single standalone code (no
envelope). `MultiQrWriter.fragmentCount(payload, format, version, ecc)` tells
you how many codes it will produce; `SCQrGenerator.maxPayloadBytes(format,
version, ecc)` gives the per-QR capacity.

**Read (any order):**

```java
MultiQrAssembler asm = new MultiQrAssembler();
for (byte[] scanned : scans) {                 // for numeric scans use asm.addNumeric(digits)
    switch (asm.add(scanned)) {
        case ACCEPTED:  showProgress(asm.getReceivedCount(), asm.getTotal(),
                                     asm.getMissingIndices()); break;
        case DUPLICATE: break;                  // already had this one
        case WRONG_SET: break;                  // belongs to a different document
        case STANDALONE:
        case COMPLETED: byte[] payload = asm.assemble();   // fingerprint-verified
                        ScData data = ScDataParser.parse(payload);
    }
}
```

**Per-fragment envelope (10 bytes):** magic `0x05`, envelope version, 4-byte
payload fingerprint (SHA-256 of the whole payload — set identity + mix
detection + integrity), total fragments N, fragment index `1..N`, and a
2-byte fragment length. All fragments carry the same fingerprint and N, so
the reader learns the total from the first scan, rejects fragments from a
different document, ignores duplicate scans, and re-verifies the fingerprint
after reassembly before parsing.

## Verification

The encoder was validated by decoding generated PNGs with Apple's CIDetector
(the CoreImage QR engine used by iOS): numeric mode round-trips arbitrary
bytes (via `numericToBytes`), byte mode round-trips text, a full
SCDataMinifier payload survives numeric-QR → parse, and high versions
(v25, with version-info bits and multiple alignment patterns) decode cleanly.
