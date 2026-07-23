# Decode-only mobile SDK (`scdec`)

A license-gated, **decode-only** image core for the mobile apps, shipped in two format-neutral
flavours. Given a stored IMAGE value (the 1-byte descriptor + version-1 headerless data carried
in an SCDataMinifier TLV), it returns raw RGB pixels. It cannot encode, and nothing it ships
names the underlying image technology.

| | flavour A | flavour B |
|---|---|---|
| decodes | one image family | the other image family |
| links | that family's **decoder** archive only | still-image + AV1 **decoder** libs only |
| size (Android arm64, stripped) | ~0.3–0.4 MB | ~2.5–2.8 MB |

An app links **exactly one** flavour — both use the same library name (`libscdec`/`scdec.xcframework`),
the same class (`com.scdataminifier.decoder.ScDecoder` / Swift `ScDecoder`), and the same symbols,
so the app picks a flavour purely by which binary it bundles.

## What "names no codec" means here — and how it is enforced

Three independent measures, all asserted by the build (`assert-clean.sh`, run after every
scrub — a failure stops the build):

1. **Public API & symbols.** The C API (`scdec.h`), the exported symbols, and the JNI/Swift
   entry points are format-neutral (`scdec_open`, `scdec_info`, `scdec_license`, …). The build
   compiles with `-fvisibility=hidden` and links with `--exclude-libs ALL`, so **only** the
   `scdec_*`/JNI symbols are exported — none of the decode library's symbols appear in `nm -D`.
2. **Our container constants.** To hand the decoder a parseable file, `scdec.c` rebuilds the
   container shell it stripped at encode time. Those constants (the RIFF/box fourccs and the
   AVIF box template) would otherwise sit verbatim in read-only data, so they are stored
   **XOR-masked** and unmasked into a scratch buffer only at decode time.
3. **Library-internal strings.** The linked decoder still carries a few of its own ASCII
   strings (brand tokens, diagnostics). A post-link pass (`scrub.py`) overwrites every
   case-insensitive `webp` / `avif` / `aom` / `aomedia` occurrence with a neutral filler of the
   same length. The build then **re-runs a decode self-test**: if any overwritten byte were
   functionally load-bearing, decode would change and the build would fail — so a released
   binary is always both scrubbed **and** bit-identical in output.

**Verified result** (reference Linux build, both flavours, against the SDK's own decoder):

```
nm -D  → only scdec_* and Java_com_scdataminifier_decoder_ScDecoder_* exported
strings → webp = 0   avif = 0   aom = 0   aomedia = 0
decode  → 75x100 RGB, MSE 0.00 (bit-identical to ScImageCodec.decode)
```

Residual generic tokens `RIFF` / `ftyp` / `av01` remain **by necessity** — they are the
container/codec magic the decoder compares against to parse the rebuilt file (scrubbing them was
tested and breaks decode). None of them contains the word "webp" or "avif"; they are the same
generic tokens found in any RIFF (WAV/AVI) or ISO-BMFF (MP4/HEIC) file.

## Licensing

Same gate as the full native codec: the public verification key is embedded from
`native/src/license_pubkey.h` (replace with your own from SCLicenseGenerator before a
production build). A caller must apply a signed license bound to its own package before
`scdec_info`/`scdec_open` return anything.

```kotlin
ScDecoder.license(licBytes, BuildConfig.APPLICATION_ID)   // 0 == OK
val bitmap = ScDecoder.decode(imageValueFromTlv)          // null if unlicensed/malformed
```

## Building (GitHub Actions — no local toolchain needed)

**Actions → build-decoder-mobile → Run workflow** (or push a tag `sdk-decoder-v*`). Download:

- `scdec-android-flavourA` / `scdec-android-flavourB` — `libscdec.so` per ABI
  (`arm64-v8a`, `armeabi-v7a`, `x86_64`). Drop each into your app's `src/main/jniLibs/<abi>/`.
- `scdec-ios-flavourA` / `scdec-ios-flavourB` — `scdec.xcframework` (dynamic; Embed & Sign).

iOS ships **dynamic** (not the static xcframework the full codec uses) on purpose: a static
library's strings would be copied into the app binary at link time, where they can no longer be
scrubbed. A dynamic framework is scrubbed here and embedded unchanged, so the guarantee survives
into the app.

### Local build scripts (used by CI; run directly if you have the toolchains)

| Script | Produces |
|---|---|
| `scripts/build-android.sh` | `out/android/<flavour>/<abi>/libscdec.so` (NDK) |
| `scripts/build-ios.sh` | `out/ios/<flavour>/scdec.xcframework` (Xcode) |
| `scripts/build-linux-test.sh` | `out/linux-<arch>/<flavour>/libscdec.so` — desktop **verification** build (not shipped): confirms each flavour compiles, decodes, and passes the scrub assertion on a real toolchain |

All share `build-deps-decoder.sh` (per-flavour minimal decode deps), `scrub.py`, and
`assert-clean.sh`.

## Wrappers

- `android/ScDecoder.kt` — loads `libscdec`, exposes `license/size/decode → Bitmap`.
- `ios/ScDecoder.swift` — wraps the C API, `license/size/decode → CGImage`.

## Recompiling / when to rebuild

This module is **independent of the full codec** (`native/` / the jar) — it has its own
`scdec.c` and JNI, so a change here rebuilds only `libscdec` and touches nothing else. Conversely,
the full-codec identifier renames (`ImageType.CODEC_A`, `nEncodeA`, …) do **not** apply here: the
decode-only API is already neutral (`scdec_*` / `ScDecoder`).

- **After changing `scdec.c` / `CMakeLists.txt` / a script:** re-run CI `sdk-decoder-v*` (bump
  the number), or locally `build-android.sh` / `build-ios.sh`. Always verify on the Linux
  reference first: `build-linux-test.sh` builds both flavours, scrubs, and asserts clean on a
  real toolchain (it does not ship — it is the fast correctness check).
- **The scrub is safety-gated:** every build runs `scrub.py` then `assert-clean.sh`; if a change
  ever caused a codec token to survive or a non-`scdec_*` symbol to export, the build fails.
- **A/B mapping (maintainers only; NOT in the shipped binary):** flavour A = the RIFF/VP8 family
  (WebP), flavour B = the ISO-BMFF/AV1 family (AVIF). Keep this out of any shipped artifact.
- **License key change:** rebuild both flavours (the key is embedded from
  `native/src/license_pubkey.h`, shared with the full codec).

## Value format (input)

The IMAGE value is exactly what the SDK stores in a TLV: `[descriptor byte][data]`.
`descriptor & 0x10` set means the data is already a complete file (decoded directly); unset
(the normal, size-optimized case) means the version-1 headerless form, and `scdec` rebuilds the
container before decoding. Flavour A's data is the raw still-image frame bitstream; flavour B's
data is `[w u16][h u16][cfgLen u8][codec-config][compressed payload]`.
