# SCDataMinifier payload format — v1

Compact, optionally encrypted and digitally signed container for storing
structured data in 2D barcodes (QR, DataMatrix, ...).

## Global conventions

- **Bit numbering: Bit 1 = least significant bit (LSB)** of a byte.
- **All multi-byte integers are big-endian** (network byte order).
- All "reserved for future use" (RFU) bits are written as **0** and ignored
  by parsers of the same version (format changes are gated by the Version byte).
- Parsers **reject** payloads whose Version byte is newer than they support.
- The largest QR code (version 40, ECC L) holds **2,953 binary bytes**; the
  writer enforces this limit by default.

## Header

| Bytes | Field |
|-------|-------|
| 1     | Magic ID = `0x03` |
| 2     | Format version (currently 1) |
| 3–4   | Application ID (uint16) |
| 5–9   | Unique ID (uint40) |
| 10–11 | Minimum client application version required (uint16) |
| 12    | Encryption byte (below) |

**Byte 12 — encryption byte**

| Bits | Meaning |
|------|---------|
| 1    | Encryption flag — 0: not encrypted, 1: encrypted |
| 2–4  | Encryption type — 0: AES-256-GCM, 1: AES-128-GCM |
| 5    | Encrypted-data length field size — 0: 1 byte, 1: 2 bytes |
| 6–8  | RFU (0) |

Bits 2–5 are meaningful only when Bit 1 = 1.

### When encrypted (Bit 1 = 1)

| Bytes | Field |
|-------|-------|
| 13–14 | Encryption key version (uint16) |
| 15 or 15–16 | Encrypted data length (per Bit 5) |
| next *n* | Encrypted data = **12-byte random IV ‖ AES-GCM ciphertext + 16-byte tag** |

The decrypted plaintext has exactly the *plaintext region* layout below
(content count, contents, signature block). This is **sign-then-encrypt**:
the digital signature travels inside the encrypted payload.

### When not encrypted (Bit 1 = 0)

Byte 13 onward is the *plaintext region* directly.

## Plaintext region

| Bytes | Field |
|-------|-------|
| 1     | Total content count (0–255) |
| …     | Content block × count |
| …     | Signature block |

### Content block

| Bytes | Field |
|-------|-------|
| 1     | Bits 1–4: content type — 0: VARIABLES, 1: TLV, 2: COMPRESSED_TLV.<br>Bit 5: length field size (0: 1 byte, 1: 2 bytes). Bits 6–8: RFU |
| 2 or 2–3 | Content data length |
| next *n* | Content data |

## Tag types (shared enum)

Used identically by TLV tags, table cells and variables:

| Code | Type | Value encoding |
|------|------|----------------|
| 0 | CAPTION | UTF-8 text (label / column header) |
| 1 | STRING | Latin-1 text (1 byte per char) |
| 2 | INTEGER | 4-byte signed big-endian |
| 3 | FLOAT | 4-byte IEEE-754 |
| 4 | IMAGE | structured image value (image header byte + data, below) |
| 5 | BIOMETRIC | application-defined template bytes |
| 6 | TABLE | table structure (TLV only, no nesting) |
| 7 | UNICODE_STRING | UTF-8 text |

### IMAGE value structure

Every IMAGE value (TLV, table cell or variable) starts with one image header byte:

| Bits | Meaning |
|------|---------|
| 1–4  | Image type — 0: WEBP, 1: AVIF |
| 5    | 0: container header stripped, 1: header present |
| 6–8  | Version — only **1** is defined; 0 and 2–7 are reserved |

The remaining bytes are the image data, with or without its container header
per Bit 5.

**Version 1 — full container shell strip.** The entire container is rebuilt
in code from dynamic fields kept in the value:

- WebP: stored data is the raw VP8 bitstream (its frame header carries the
  dimensions); the RIFF + `VP8 ` chunk headers (20 bytes) are rebuilt.
  Only simple lossy (VP8, no alpha) files qualify.
- AVIF: stored data is `[width u16][height u16][av1cLen u8][av1C config]`
  followed by the raw AV1 payload; the ~275-byte ISO-BMFF shell
  (`ftyp`/`meta` with hdlr, pitm, iloc, iinf, ispe, pixi, av1C, colr, ipma /
  `mdat`) is rebuilt from a template matching libavif's single-image 8-bit
  4:2:0 sRGB output. Saves ~266 bytes per image.

Writers must verify at strip time that the rebuild is byte-identical to the
original encoder output and fail otherwise, so a stripped image always
reconstructs exactly. Because the strip scheme depends on the encoder's
container layout, deployments pin exact encoder versions (e.g. cwebp 1.6.0,
avifenc 1.4.2) on the issuing side and matching decoder versions in viewers;
the SDK's CliImageEncoder enforces the pin at encode time. Images the scheme
cannot strip (alpha/lossless WebP, non-canonical AVIF) are stored with the
header present (Bit 5 = 1).

## Content type TLV (1)

A sequence of TLV entries filling the content data exactly.

**Tag byte**

| Bits | Meaning |
|------|---------|
| 1–4  | Tag type (table above) |
| 5    | Length field size — 0: 1 byte, 1: 2 bytes |
| 6    | Tag ID present flag |
| 7    | Tag ID size — 0: 1 byte, 1: 2 bytes (meaningful only when Bit 6 = 1) |
| 8    | RFU (0) |

Order on the wire: **Tag byte, Length (1–2), Tag ID (0–2), Value**.

### TABLE value (tag type 6)

| Bytes | Field |
|-------|-------|
| 1 | Bits 5–8 (high nibble): rows − 1. Bits 1–4 (low nibble): columns − 1. Range 1–16 each. |
| 2 | Flags: Bit 1 — header row present (row 0 holds column captions). Bits 2–8: RFU |
| 3… | Cells in row-major order (row 0 col 0, row 0 col 1, …) |

**Cell byte**

| Bits | Meaning |
|------|---------|
| 1–4  | Tag type (TABLE not allowed — no nesting) |
| 5    | Length field size — 0: 1 byte, 1: 2 bytes |
| 6    | Data present — 0: empty cell, **no length/value bytes follow**; 1: length + value follow |
| 7–8  | RFU (0) |

## Content type COMPRESSED_TLV (2)

| Bytes | Field |
|-------|-------|
| 1 | Bits 1–4: compression type — 0: ZIP (raw DEFLATE, no zlib/gzip wrapper), 1: GZIP, 2: ZIP_DICT (raw DEFLATE with shared preset dictionary). Bits 5–8: RFU |
| 2 | **ZIP_DICT only:** dictionary version (0–255). Absent for other types. |
| next… | Compressed bytes of a TLV sequence (decompresses to the TLV layout above) |

The ZIP_DICT dictionary is distributed out-of-band (bundled with the
applications) and identified by its version; writer and parser must use
byte-identical dictionaries. Writers may operate in *auto* mode: compress,
and if the result is not smaller than the plain TLV encoding, emit the
content as type TLV instead — parsers need no special handling since the
emitted block is an ordinary TLV content.

## Content type VARIABLES (0)

A sequence of variables filling the content data exactly. Variables are
referenced from **outside** the payload (HTML Minifier templates or the
rendering application) by their numeric ID.

| Bytes | Field |
|-------|-------|
| 1 | Bits 1–4: variable type (tag type enum; TABLE not allowed). Bit 5: length field size. Bits 6–8: RFU |
| 2 | Bits 1–5: variable ID (0–31). Bits 6–8: RFU |
| 3 or 3–4 | Variable data length |
| next *n* | Variable data |

## Signature block

Always present at the end of the plaintext region.

**Byte 1**

| Bits | Meaning |
|------|---------|
| 1–3  | Signature algorithm — 0: ECDSA P-256/SHA-256, 1: RSA-2048/SHA-256, 2: ECDSA P-384/SHA-384, 7: NONE |
| 4–7  | Signature key version (0–15) |
| 8    | Signature length field size — 0: 1 byte, 1: 2 bytes |

- **Algorithm ≠ NONE:** Byte 2 (or 2–3) = signature length, followed by the
  signature bytes.
- **Algorithm = NONE:** exactly **2 bytes of CRC16/CCITT-FALSE** follow
  (no length field), protecting against corruption when unsigned.

**Signed data** (also what the CRC16 covers): **header bytes 1–11**
concatenated with the **content region** (content count byte + all content
blocks). The encryption byte and encryption fields are excluded, so the same
bytes are signed whether or not the payload is encrypted.

## Limits

| Item | Limit |
|------|-------|
| Payload size | 2,953 bytes (QR v40-L) — writer-enforced, can be disabled |
| Contents per payload | 255 |
| Content / TLV value / variable / signature size | 65,535 bytes |
| Table dimensions | 16 × 16, no nesting |
| Variable ID | 0–31 |
| Tag ID | 0–65,535 |
| Signature key version | 0–15 |
| Encryption key version | 0–65,535 |
