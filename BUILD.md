# Building, obfuscating, and packaging the SCData SDK

This is the end-to-end build guide for every artifact the SDK ships:

| # | Artifact | What it is | Built by |
|---|---|---|---|
| 1 | `dist/obf/scdataminifier.jar` | Full Java SDK jar (all classes + per-OS native codec), **ZKM-obfuscated** | `build-jar.sh` → `obfuscate.sh` |
| 2 | `dist/server-obf/scdataminifier-lean.jar` | Lean server jar (classes only, no natives), obfuscated | `build-server-jar.sh` |
| 3 | `resources/native/<os>-<arch>/…` | Full **encode+decode** native codec (`libscimage`) per platform | `native/scripts/build-*.sh` |
| 4 | Android `.so` / iOS `xcframework` (full codec) | Mobile build of the full codec | `native/scripts/build-android.sh` / `build-ios.sh` |
| 5 | `mobile-native/out/**/libscdec.*` | **Decode-only**, license-gated, format-neutral mobile SDK in two flavours | `mobile-native/scripts/build-*.sh` |

Everything can be produced on **GitHub Actions** (no local toolchain), which is the recommended
path — see [§7 CI](#7-ci--the-recommended-path). The sections below also give the exact local
commands for each platform.

- Repo root for all commands below: this folder (`SCDataMinifier/`).
- Pinned upstream versions (libwebp / libavif / aom): `native/VERSIONS.env`.
- Related docs: [`LICENSING.md`](LICENSING.md), [`native/PACKAGING.md`](native/PACKAGING.md),
  [`native/WINDOWS-BUILD.md`](native/WINDOWS-BUILD.md), [`mobile-native/README.md`](mobile-native/README.md).

---

## Codec identity is hidden — READ THIS BEFORE REBUILDING

Neither shipped artifact names its image formats. The distributed jar and the decode-only
mobile libraries were deliberately scrubbed of the words *webp* / *avif* / *aom* and of the
pinned version numbers. **This constrains how you rebuild** — get it wrong and either the codec
name reappears in the jar, or the jar fails to bind to the native at runtime.

**The neutral scheme (for maintainers — this mapping is intentionally NOT in the shipped jar):**

| Neutral name (public) | Really is | Where |
|---|---|---|
| `ImageType.CODEC_A` | the RIFF/VP8 still-image format (WebP) | `enums/ImageType.java` |
| `ImageType.CODEC_B` | the ISO-BMFF/AV1 still-image format (AVIF) | same |
| `nEncodeA` / `nDecodeA` / `nEncodeATarget` | CODEC_A native entry points | `NativeImageCodec.java` ↔ `native/src/scimage_jni.c` |
| `nEncodeB` / `nDecodeB` | CODEC_B native entry points | same |
| `codecVersionA` / `codecVersionB` / `codecVersions` | version accessors (runtime strings, not in `.class`) | same |
| `nVersionsOk` → `scimg_versions_ok()` | native self-check of the pinned versions | `NativeImageCodec.java` ↔ `native/src/scimage_codec.c` |

**The golden rule — the Java native-method name and the JNI export symbol MUST match.**
Java binds `nEncodeA` to the native symbol `Java_com_scdataminifier_image_NativeImageCodec_nEncodeA`.
So **any** rename of a codec identifier is a coordinated change across:

1. `src/com/scdataminifier/image/NativeImageCodec.java` — native method declarations,
2. `native/src/scimage_jni.c` — the matching `Java_..._<name>` export names,
3. **every platform native** (Linux + macOS + Windows + Android + iOS) — rebuilt so its exported
   JNI symbols match, because the jar bundles all platforms' natives and a stale one throws
   `UnsatisfiedLinkError` on that platform at runtime,
4. `ImageType` constants + **all callers** (in this tree: QRTest `GenerateSecureQr`,
   TrustraCrypto `SecureCodeSdkAdapter`),
5. rebuild + re-obfuscate the jar (§1) with all refreshed natives.

**Pinned versions live in the native**, not the jar: `scimg_versions_ok()` compares the runtime
codecs to the compile-time pins (webp as a packed int, avif via the linked headers' version
macros). To bump a dependency you therefore edit **both** `native/VERSIONS.env` **and** the pin
inside `scimg_versions_ok()`, then rebuild every native (see [§8](#8-version-pins--upgrades)).

**Verify a build stayed clean** (§9 has the full commands): the jar's classes must grep to
**zero** for `webp|avif|1\.6\.0|1\.4\.2`, and each mobile `libscdec` must pass `assert-clean.sh`.

---

## 0. Prerequisites

| Target | Tools |
|---|---|
| Java jars | JDK 17 (`javac`, `jar`) |
| Obfuscation | Zelix KlassMaster — `ZKM.jar` (licensed). Default location `/Users/praburaju/P_DRIVE/ZKM`, override with `ZKM_HOME`. |
| Native (Linux) | `gcc gcc-c++ cmake nasm` (RHEL/Rocky 9+: glibc ≤ 2.34 target). `nasm` is required by aom's x86_64 assembly. |
| Native (macOS) | Xcode + command-line tools, `cmake`, `nasm` (`brew install cmake nasm`) |
| Native (Windows) | VS 2019+ C++ tools, `cmake`, `nasm`, JDK 17 (see `native/WINDOWS-BUILD.md`) |
| Android | Android NDK r25+ (`ANDROID_NDK_HOME`), `cmake`, `nasm`, `python3` |
| iOS | Xcode, `cmake`, `nasm`, `python3` |

The native dependency sources (libwebp, libavif, aom) are **downloaded on demand** by
`native/scripts/fetch-sources.sh` (every build script calls it) into `native/third_party/`;
they are not committed.

---

## 1. Full Java SDK jar (+ obfuscation)

The shipped jar carries the compiled classes **and** a native codec for each supported OS/arch,
split + scrambled so the raw `.so`/`.dll`/`.dylib` never appears in the jar.

```bash
# 1) put a real native binary in resources/native/<os>-<arch>/ for each platform you ship
#    (see §3 / §4; platforms without a binary are simply skipped and error clearly at runtime)
./build-jar.sh        # -> dist/scdataminifier.jar   (classes + split/scrambled natives)
./obfuscate.sh        # -> dist/obf/scdataminifier.jar   (ZKM: flow + string + exception obf)
```

What each step does:

- **`build-jar.sh`** compiles `src/**` with `javac --release 17`, then for every
  `resources/native/<os>-<arch>/` that holds a real binary runs
  `com.scdataminifier.image.LibraryObfuscator` to split it into two opaque parts
  (`app-resources.dat` + `app-cache.bin`) and bundles **only those** under `native/<os>-<arch>/`
  in the jar. Placeholder-only folders (just `README.txt`) are skipped.
- **`obfuscate.sh`** runs ZKM with `build/zkm/scdataminifier.zkm` →
  `dist/obf/scdataminifier.jar`. See [§5 Obfuscation](#5-obfuscation-zkm).

At runtime the app loads the right native with
`ScImageCodec.loadBundledObfuscatedNative()` (reconstructs it in memory from the two parts),
then `applyLicense(...)`.

---

## 2. Lean server jar

For the middleware (ProjectTrustra): classes only, **no** native binaries — the server signs
with `ScDataWriter` + the HSM and never loads the codec.

```bash
./build-server-jar.sh
#   dist/server/scdataminifier-lean.jar        (plain)
#   dist/server-obf/scdataminifier-lean.jar    (ZKM-obfuscated — ship this)
```

---

## 3. Native full codec — desktop/server (`libscimage`)

Produces the license-gated **encode + decode** codec (statically-linked, pinned
libwebp 1.6.0 + libavif 1.4.2 + aom 3.14.1). Build on the **oldest** distro you deploy to (the
`.so` links the build host's glibc).

```bash
# Linux (RHEL/Rocky 9+):
native/scripts/build-linux.sh          # -> native/out/linux-<arch>/libscimage.so
native/scripts/install-to-resources.sh # -> resources/native/linux-<arch>/libscimage.so

# macOS (this host's arch):
native/scripts/build-macos.sh
native/scripts/install-to-resources.sh # -> resources/native/macos-<arch>/libscimage.dylib

# Windows (x64 Native Tools prompt): see native/WINDOWS-BUILD.md
native/scripts/build-windows.ps1       # -> native/out/windows-x64/scimage.dll
#   then copy to resources/native/windows-x86_64/scimage.dll
```

Each script: `fetch-sources.sh` → `build-deps.sh` (builds the three static deps into a prefix) →
CMake configure/build of `native/CMakeLists.txt` with `-DSCIMG_JNI=ON`.
`install-to-resources.sh` copies the result into the jar-resource layout for §1.

The compiled `libscimage` exports the **neutral** JNI symbols
(`Java_..._nEncodeA`/`nDecodeA`/`nEncodeATarget`/`nEncodeB`/`nDecodeB`/`codecVersionA`/
`codecVersionB`/`codecVersions`/`nVersionsOk`) that the jar's `NativeImageCodec` binds to — the
raw `.so`/`.dylib`/`.dll` itself still contains the upstream libraries' own symbols/strings, but
in the jar those natives are split + scrambled (opaque; verified 0 webp/avif in the jar parts).
If you rename any of these, see the [golden rule](#codec-identity-is-hidden--read-this-before-rebuilding).

> **Why not cross-compile:** the codec verifies byte-identical container rebuilds, so each OS
> binary must be built on that OS (or its container). See `native/PACKAGING.md`.

---

## 4. Native full codec — Android / iOS

Same full encode+decode codec, for mobile embedding.

```bash
# Android (per ABI): arm64-v8a, armeabi-v7a, x86_64
export ANDROID_NDK_HOME=/path/to/ndk
native/scripts/build-android.sh        # -> native/out/android/<abi>/libscimage.so
#   package under app/src/main/jniLibs/<abi>/libscimage.so

# iOS (static xcframework, no JNI):
native/scripts/build-ios.sh            # -> native/out/ios/scimage.xcframework
```

Use these when the app needs the **full** codec (e.g. AVIF decode on older OSes, or on-device
encode). If the app only needs to **display** stored images, prefer the smaller decode-only SDK
in §5.

---

## 5. Decode-only mobile SDK (`scdec`) — two format-neutral flavours

A license-gated, **decode-only** core that turns a stored IMAGE value into RGB, shipped in two
flavours that name no image format anywhere in the artifact. Full design + guarantees:
[`mobile-native/README.md`](mobile-native/README.md).

| | flavour A | flavour B |
|---|---|---|
| decodes | one image family | the other image family |
| links | that family's **decoder** archive only | still-image + AV1 **decoder** libs only |

**Build (both flavours, all targets):**

```bash
# Android — per ABI, both flavours, each scrubbed + asserted:
export ANDROID_NDK_HOME=/path/to/ndk
mobile-native/scripts/build-android.sh
#   -> mobile-native/out/android/<flavour>/<abi>/libscdec.so
#   package the chosen flavour's ABIs under app/src/main/jniLibs/<abi>/libscdec.so

# iOS — dynamic xcframework per flavour, scrubbed + asserted:
mobile-native/scripts/build-ios.sh
#   -> mobile-native/out/ios/<flavour>/scdec.xcframework   (Embed & Sign)

# Linux — verification build only (NOT shipped): proves each flavour compiles,
# decodes, and passes the scrub assertion on a real toolchain:
mobile-native/scripts/build-linux-test.sh
```

**What the build guarantees (enforced by `assert-clean.sh`, run after every scrub — failure
stops the build):**

1. **Neutral exports only.** `-fvisibility=hidden` + (Linux/Android) `-Wl,--exclude-libs,ALL` /
   (iOS) `-Wl,-exported_symbols_list ios-exported-symbols.txt`. `nm` shows only `scdec_*` and
   the JNI methods — none of the decoder's symbols.
2. **Masked container constants.** The RIFF/box fourccs and AVIF template `scdec.c` must emit to
   rebuild a decodable file are stored **XOR-masked** and unmasked only at decode time.
3. **Scrubbed strings.** `scrub.py` overwrites every `webp`/`avif`/`aom`/`aomedia` occurrence
   with a same-length filler; the build then re-runs a decode self-test, so a scrubbed binary is
   provably still bit-identical (over-scrubbing fails the build). `assert-clean.sh` finally
   asserts **zero** codec tokens and only-neutral exports.

Generic container tokens (`RIFF`/`ftyp`/`av01`) remain **by necessity** — they are the magic the
decoder compares against to parse the rebuilt file (scrubbing them is tested and breaks decode).
None contains the word "webp" or "avif".

iOS is **dynamic** (not the static xcframework the full codec uses) on purpose: a static
library's strings are copied into the app binary at link time, where they could no longer be
scrubbed; a dynamic framework is scrubbed here and embedded unchanged.

**Shared helper scripts** (`mobile-native/scripts/`): `build-deps-decoder.sh` (per-flavour
minimal decode deps — flavour A builds libwebp but links only `libwebpdecoder`; flavour B builds
aom with the **encoder disabled** + libavif decode), `scrub.py`, `assert-clean.sh`.

**Wrappers:** `mobile-native/android/ScDecoder.kt`, `mobile-native/ios/ScDecoder.swift`.

---

## 6. Licensing — embed your key before a production build

The native libraries (both `libscimage` and `scdec`) are license-gated with a P-256 public key
**compiled in** from `native/src/license_pubkey.h`. Full flow: [`LICENSING.md`](LICENSING.md).

```bash
# 1) generate the signing key pair + a customer .lic (Java console app)
cd ../SCLicenseGenerator
javac -d bin $(find src -name '*.java') && java -cp bin com.scdataminifier.license.LicenseGenerator
#    first run writes keys/ (incl. license_pubkey.h); then it prompts for each .lic field
# 2) embed the PUBLIC key and rebuild every native binary
cp ../SCLicenseGenerator/keys/license_pubkey.h native/src/license_pubkey.h
#    then re-run the §3/§4/§5 builds (or CI). Any key change requires rebuilding ALL binaries.
```

At runtime the app base64-decodes the `.lic` body and calls the license method **before any
codec call**: `ScImageCodec.applyLicense(lic, pkg)` (full codec) or
`ScDecoder.license(lic, pkg)` (decode-only). Return `0` = licensed; negatives:
`-1` malformed, `-2` signature, `-3` package, `-4` not-yet-valid, `-5` expired.

> Keep `keys/license_private.pem` secret. The committed `native/src/license_pubkey.h` may be a
> development key — replace it with your production public key before shipping.

---

## 7. CI — the recommended path

No local toolchain needed; each OS builds on its own runner. Trigger from the **Actions** tab
(Run workflow) or by pushing the matching tag.

| Workflow | Produces | Trigger |
|---|---|---|
| `build-native-jar.yml` | full codec natives (macOS/Linux/Windows) + assembled jar | manual / tag `sdk-v*` |
| `build-mobile-native.yml` | full codec: Android per-ABI `.so`, iOS `xcframework` | manual / tag `sdk-mobile-v*` |
| `build-decoder-mobile.yml` | decode-only `scdec`: Android per-ABI `.so`, iOS dynamic `xcframework`, both flavours (scrubbed + asserted) | manual / tag `sdk-decoder-v*` |

```bash
# e.g. rebuild the decode-only mobile SDK on CI (bump the number each time):
git tag -a sdk-decoder-v4 -m "decode-only mobile build" && git push origin sdk-decoder-v4
```

Tag prefixes and the latest tags used: `sdk-v*` (full-codec natives + jar, latest `sdk-v3`),
`sdk-mobile-v*` (full-codec mobile, latest `sdk-mobile-v3`), `sdk-decoder-v*` (decode-only
mobile, latest `sdk-decoder-v3`). Bump the trailing number for a new run.

Download the run's artifacts, drop the native binaries into `resources/native/` (jar) or
`jniLibs/` / your Xcode project (mobile). **ZKM obfuscation (§1/§2) is not run in CI** (ZKM is
licensed/local) — do that step on your machine after collecting the natives.

CI still embeds `native/src/license_pubkey.h`, so replace it with your production key (§6) and
re-tag before a production build.

---

## 8. Version pins & upgrades

`native/VERSIONS.env` pins libwebp / libavif / aom. To move up:

1. bump the versions there,
2. **also** bump the matching pin inside `scimg_versions_ok()` in `native/src/scimage_codec.c`
   (webp packed int, e.g. `(1<<16)|(6<<8)|0` for 1.6.0; avif is checked via the linked headers'
   `AVIF_VERSION_*` macros, so it follows automatically) — the pinned numbers live in the native
   on purpose so they are not string constants in the jar,
3. re-run `build-deps.sh` + the platform build scripts (or CI) for **every** platform,
4. if libavif's container layout changed, re-capture the AVIF template in
   `util/ImageContainers.java` **and** the mirrored hex in the mobile ports
   (`mobile-native/src/scdec.c`, iOS/Android `ScImage` ports),
5. re-run the golden-image / round-trip tests, then rebuild + re-obfuscate the jar.

---

## 9. Quick verification

```bash
# Full codec jar loads its native and round-trips (desktop):
javac -cp dist/obf/scdataminifier.jar samples/SdkSmokeTest.java
java  -cp dist/obf/scdataminifier.jar:samples SdkSmokeTest       # expects "SUCCESS"

# Jar hides the codec: classes must grep to ZERO for codec names AND version numbers:
mkdir -p /tmp/jx && (cd /tmp/jx && unzip -oq "$OLDPWD"/dist/obf/scdataminifier.jar)
grep -rilE 'webp|avif|1\.6\.0|1\.4\.2' /tmp/jx/com | wc -l          # -> 0
javap -cp /tmp/jx com.scdataminifier.enums.ImageType | grep -E 'CODEC_A|CODEC_B'  # neutral only

# Decode-only mobile artifact is clean (run on any built libscdec.so/.dylib):
nm -D  libscdec.so | grep ' T '          # only scdec_* / JNI
strings libscdec.so | grep -ic -e webp -e avif -e aom   # -> 0
bash mobile-native/scripts/assert-clean.sh libscdec.so  # -> OK
```

---

## 10. Recompile checklist — what to rebuild when something changes

| You changed… | Rebuild | Then |
|---|---|---|
| **Only Java logic** (no native, no identifier rename) | jar (§1) | `./build-jar.sh` + `./obfuscate.sh` — natives unchanged |
| **A codec identifier** (ImageType constant, `nEncode*`/`nDecode*`, `codecVersion*`) | **every** platform native (§3+§4) + jar | update Java + `scimage_jni.c` to matching names, rebuild all natives via CI (`sdk-v*` + `sdk-mobile-v*`), collect into `resources/native/`, then jar. See the [golden rule](#codec-identity-is-hidden--read-this-before-rebuilding). |
| **`scimage_codec.c` / `scimage_jni.c`** (native codec logic) | every platform native (§3+§4) + jar | CI `sdk-v*` (+ `sdk-mobile-v*` if the app embeds the full codec), then jar |
| **A pinned dependency version** (`VERSIONS.env`) | dep + every native + jar (§8) | also bump `scimg_versions_ok()`; re-run golden-image tests |
| **The license public key** (`license_pubkey.h`) | every native (full + decode-only) | CI all three workflows; jar; ship the new `.lic` |
| **`mobile-native/` decode-only code** (`scdec.c`, CMake, scripts) | only `libscdec` (§5) | CI `sdk-decoder-v*`; nothing else — it is independent of the full codec |

**Which platforms must I rebuild?** The jar bundles **all** platforms' natives, so a codec
identifier or native-logic change means **all** of Linux + macOS + Windows must be rebuilt before
the jar works everywhere (a stale native → `UnsatisfiedLinkError` on that OS). If you only deploy
on Linux, rebuilding just the Linux native + jar is enough for that deployment; the other slots
stay stale until you run CI. Mobile app natives (full-codec §4 or decode-only §5) are separate
downloads embedded in the app, rebuilt via their own CI workflows.
