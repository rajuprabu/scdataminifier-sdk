# scimage — pinned native WEBP/AVIF codec

Statically links **libwebp 1.6.0** and **libavif 1.4.2 (aom 3.14.1)** into a
single native library used by SCDataMinifier for all image encoding and
decoding. One C core serves every platform:

| Platform | Script | Output | API |
|---|---|---|---|
| macOS (dev) | `scripts/build-macos.sh` | `out/macos/libscimage.dylib` | JNI |
| Linux (RHEL 9+) | `scripts/build-linux.sh` | `out/linux-x86_64/libscimage.so` | JNI |
| Windows | `scripts/build-windows.ps1` | `out/windows-x64/scimage.dll` | JNI |
| Android | `scripts/build-android.sh` | `out/android/<abi>/libscimage.so` | JNI (same Java class) |
| iOS | `scripts/build-ios.sh` | `out/ios/scimage.xcframework` | plain C (no JNI) |

## Build

```bash
scripts/fetch-sources.sh     # downloads pinned tarballs to third_party/
scripts/build-<platform>...  # see prerequisites in each script's header
```

All dependencies are compiled from the pinned sources as static archives, so
the produced library has **no external dependencies** beyond the platform's
C runtime. Build the Linux .so on the oldest glibc you deploy to (RHEL 9).

## Use

Server/desktop (Java):

```java
NativeImageCodec.load("/opt/yourapp/lib/libscimage.so"); // verifies pinned versions
ScImageCodec.setEncoder(new NativeImageCodec());          // encode via native
BufferedImage img = NativeImageCodec.decodeToImage(scImage); // decode via native
```

Android: put `libscimage.so` per ABI under `app/src/main/jniLibs/<abi>/`,
then `NativeImageCodec.loadDefault()` — the same Java class works unchanged
(replace the BufferedImage helpers with Bitmap equivalents in your app).

iOS (Swift): add `scimage.xcframework`, include `scimage_codec.h` in the
bridging header, call `scimg_decode_avif`/`scimg_decode_webp` with the bytes
from `ScImage.toImageBytes()` equivalents.

## Hiding the library (obfuscated loading)

To avoid shipping a recognizably-named `.so`/`.dll`, pack it into two opaque
files and reconstruct in memory at runtime. **Keyless** - no encryption, no
key to manage:

```bash
# build time
java -cp bin com.scdataminifier.image.LibraryObfuscator \
     out/linux-x86_64/libscimage.so app-resources.dat app-cache.bin
```

```java
// runtime - no file named libscimage.* ever exists on disk
ScImageCodec.useObfuscatedNativeCodec(
    Paths.get("app-resources.dat"), Paths.get("app-cache.bin"));
```

The library bytes are run through a fixed-seed keystream XOR (a reversible
scramble, not encryption) and split across the two files; both become
pseudo-random, so neither reveals strings (`avif`/`webp`/`Mach`/`dylib` = 0
hits) nor is loadable alone. At load the bytes are reconstructed in memory,
written to a RAM-backed tmpfs (`/dev/shm` on Linux/Android - never persistent
disk) under a random `.cache` name, `System.load`ed, then immediately
unlinked (the mapping survives on POSIX; Windows marks delete-on-exit).

**This is obfuscation, not security.** The scramble algorithm and seed are in
the code, so anyone reading the (unobfuscated) code can reverse it. Pair it
with an outer JAR/APK obfuscator (ProGuard/R8/DexGuard) that hides this logic
- that is what makes it hard to find. On its own it only stops casual
inspection (file name, strings, ldd). Real trust stays with the payload
signing/encryption keys.

If you fork the SDK, change `LibraryObfuscator.SEED` so your scramble differs
from the stock one.

## Upgrading versions later

1. Edit `VERSIONS.env`.
2. Update the pin inside `scimg_versions_ok()` in `src/scimage_codec.c` (webp packed int;
   avif follows the linked `AVIF_VERSION_*` macros). The pinned numbers live here — **not** as
   Java constants — so they never appear as strings in the jar.
3. `rm -rf third_party out && scripts/fetch-sources.sh && scripts/build-<platform>.sh` for
   **every** platform (the jar bundles all of them).
4. Re-capture the AVIF container template if libavif's box layout changed
   (encode a sample, run the strip — a template mismatch throws with details;
   update `ImageContainers` constants from a hex dump of the new output, and mirror the same
   hex in `mobile-native/src/scdec.c` + the `ScImage` mobile ports).
5. Re-run the golden-image tests before rolling out; ship updated decoders
   to Android/iOS only if you bumped major bitstream features (not needed
   for ordinary version bumps — VP8/AV1 bitstreams are frozen).

> **Renaming the JNI entry points** (the neutral `nEncodeA`/`nDecodeB`/`codecVersionA`/
> `nVersionsOk` names that hide the codec) is a coordinated change — Java native declarations,
> `src/scimage_jni.c` export names, **and every platform native** must move together, or the jar
> throws `UnsatisfiedLinkError` on the stale platform. See `BUILD.md` → "Codec identity is hidden".
