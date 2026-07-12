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

## Verification

The encoder was validated by decoding generated PNGs with Apple's CIDetector
(the CoreImage QR engine used by iOS): numeric mode round-trips arbitrary
bytes (via `numericToBytes`), byte mode round-trips text, a full
SCDataMinifier payload survives numeric-QR → parse, and high versions
(v25, with version-info bits and multiple alignment patterns) decode cleanly.
