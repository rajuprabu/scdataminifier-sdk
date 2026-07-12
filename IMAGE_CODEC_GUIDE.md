# SCDataMinifier Image Codec — Build, Obfuscate, Encode & Decode

End-to-end process for the native WEBP/AVIF image codec used by SCDataMinifier:

1. **Compile** the pinned native library (`libscimage`) for each platform.
2. **Split/obfuscate** it into two innocuous files (no recognizable `.so`/`.dll`).
3. **Encode** face photos (JPG/PNG → WEBP/AVIF) on the issuing server.
4. **Decode** them in the viewer apps.

Pinned versions (one source of truth: `native/VERSIONS.env`):
**libwebp 1.6.0**, **libavif 1.4.2**, **aom 3.14.1**.

---

## 0. Architecture at a glance

```
 ISSUING SIDE (RHEL 9 server)                     VIEWER SIDE (Android / iOS)
 ─────────────────────────────                    ───────────────────────────
 JPG/PNG face photo                               2D barcode scan → payload bytes
      │ ImageIO read + EXIF rotate + cover-crop        │ ScDataParser.parse(...)
      │ NativeImageCodec.encode (libscimage)           │ tlv.asImage() → ScImage
      ▼                                                 │ ScImage.toImageBytes()
 ScImage (image-header byte + headerless data)          ▼  (container rebuilt)
      │ ScImageCodec.compress(...) → target size   complete .webp / .avif bytes
      ▼                                                 │
 ScDataWriter … addImage(scImage) … build()             ▼ decode:
      ▼                                            OS-native (Android 12+/iOS 16+)
 signed/encrypted payload → 2D barcode              or bundled libscimage
```

The native library is the **same C core** everywhere
(`native/src/scimage_codec.c`); only the build toolchain differs. It is
loaded through JNI on desktop/server/Android and called as plain C on iOS.

---

## 1. Compile the native library

### 1.1 What gets built

`scripts/build-<platform>` produces one self-contained artifact that
statically links libwebp + libavif + aom (no external runtime deps):

| Platform | Script | Output |
|---|---|---|
| Linux / RHEL 9+ | `build-linux.sh` | `out/linux-x86_64/libscimage.so` |
| Windows x64 | `build-windows.ps1` | `out/windows-x64/scimage.dll` |
| Android (3 ABIs) | `build-android.sh` | `out/android/<abi>/libscimage.so` |
| iOS (device+sim) | `build-ios.sh` | `out/ios/scimage.xcframework` |
| macOS (dev) | `build-macos.sh` | `out/macos/libscimage.dylib` |

All scripts live in `native/scripts/`. They call `fetch-sources.sh`
(downloads the pinned tarballs into `native/third_party/`) and
`build-deps.sh` (builds the three static dependencies), then build
`libscimage` on top.

### 1.2 Linux / RHEL 9 (the production encoder host)

Build on the **oldest** distro you deploy to — the `.so` links the build
host's glibc, and older glibc runs on newer systems but not vice-versa. RHEL 9
is a good baseline.

```bash
# prerequisites (RHEL 9 / Rocky / Alma)
sudo dnf install -y gcc gcc-c++ cmake nasm java-17-openjdk-devel
#   nasm is required by aom's x86_64 assembly.

cd SCDataMinifier/native
./scripts/build-linux.sh
# → out/linux-x86_64/libscimage.so
```

Do NOT use distro packages of libwebp/libavif — RHEL 9 ships libwebp 1.2.0 and
libavif only via EPEL, neither matching the pin. The scripts compile the exact
pinned versions from source.

### 1.3 Windows x64

```powershell
# prerequisites: Visual Studio 2019+ (Desktop C++), cmake, nasm, JDK 17 (JAVA_HOME set)
# run from the "x64 Native Tools Command Prompt for VS"
cd SCDataMinifier\native
powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1
# → out\windows-x64\scimage.dll   (static CRT, no VC++ redistributable needed)
```

### 1.4 Android

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r25c   # or newer
cd SCDataMinifier/native
./scripts/build-android.sh
# → out/android/{arm64-v8a,armeabi-v7a,x86_64}/libscimage.so
```

Copy each ABI's `libscimage.so` into your app at
`app/src/main/jniLibs/<abi>/libscimage.so` (or the obfuscated files, §2).

### 1.5 iOS

```bash
# prerequisites: Xcode + command line tools, cmake
cd SCDataMinifier/native
./scripts/build-ios.sh
# → out/ios/scimage.xcframework   (device arm64 + simulator arm64/x86_64)
```

The xcframework exposes the **plain C API** in `scimage_codec.h` (no JNI on
iOS). Add it to your Xcode project and import the header in your bridging
header.

### 1.6 Verify the build

Confirm the pinned versions are what got linked. A three-line main:

```java
NativeImageCodec.load("out/linux-x86_64/libscimage.so");
System.out.println(NativeImageCodec.webpVersion());    // must be 1.6.0
System.out.println(NativeImageCodec.avifVersion());    // must be 1.4.2
System.out.println(NativeImageCodec.codecVersions());  // aom [enc/dec]:v3.14.1
```

`NativeImageCodec.load(...)` itself throws if the linked versions don't match
`PINNED_WEBP_VERSION` / `PINNED_AVIF_VERSION`, so a wrong artifact fails at
startup, not in the field. A stronger check is a **golden-image test**: encode
a fixed reference PNG and byte-compare against a stored expected `ScImage` —
that catches any codec-output drift the version string alone can't.

---

## 2. Split / obfuscate the library

Goal: no file named `libscimage.*` on disk, no recognizable strings, and no
single file that is a loadable library. **Keyless** — a reversible scramble,
no encryption, nothing to manage. The outer JAR/APK obfuscator
(ProGuard/R8/DexGuard) is what hides the scramble logic itself.

### 2.1 Pack (build time)

```bash
# run once per platform artifact
java -cp <sdk-bin> com.scdataminifier.image.LibraryObfuscator \
     out/linux-x86_64/libscimage.so  app-resources.dat  app-cache.bin
```

Produces two opaque files (`file` reports "data", `strings | grep -i
'avif\|webp\|scimage'` → 0 hits). Ship these instead of the `.so`/`.dll`.
Name them to blend in with your other resources.

### 2.2 What it does

`LibraryObfuscator` runs the library bytes through a fixed-seed splitmix64
keystream XOR (self-inverse) and splits the result in half. Neither half is a
valid library; both are pseudo-random. Reconstruction is purely algorithmic —
`reconstruct(blob, part)` returns the exact original bytes (verified
byte-identical in testing).

> If you fork the SDK, change `LibraryObfuscator.SEED` so your scramble
> differs from the stock one. Rely on your JAR/APK obfuscator to hide this
> class. This is obfuscation, not security — real trust stays with the
> payload signing/encryption keys.

### 2.3 Deploy the two files

- **Server (RHEL):** place `app-resources.dat` / `app-cache.bin` in your app's
  resource dir, owned by the service account, `0400`.
- **Android:** bundle both as `assets/` (or raw resources); copy to app
  storage at first run, or read directly and pass the bytes to the loader.

---

## 3. Encode (issuing server)

The encoder turns a JPG/PNG face photo into an `ScImage` sized to fit the
barcode budget, using the native codec.

### 3.1 One-time setup at startup

```java
import com.scdataminifier.image.ScImageCodec;

// Option A: plain (unobfuscated) native library
ScImageCodec.useNativeCodec("/opt/yourapp/lib/libscimage.so");

// Option B: obfuscated (the two split files) — no key
ScImageCodec.useObfuscatedNativeCodec(
        Paths.get("/opt/yourapp/res/app-resources.dat"),
        Paths.get("/opt/yourapp/res/app-cache.bin"));
```

Either call loads the codec into memory, verifies the pinned versions, and
routes all encoding through it. (With Option B the library is reconstructed in
memory, staged on a RAM-backed tmpfs under a random name, loaded, then
unlinked — no `libscimage.*` ever appears on disk.)

### 3.2 Compress a face photo

```java
import com.scdataminifier.enums.ImageType;
import com.scdataminifier.model.ScImage;

byte[] jpgOrPng = Files.readAllBytes(Paths.get("face.jpg"));

ScImage face = ScImageCodec.compress(
        jpgOrPng,            // JPG or PNG source (any ImageIO format)
        ImageType.AVIF,      // or ImageType.WEBP
        false,               // includeHeader=false → strip container (image version 1)
        96, 96,              // output width, height in pixels
        800);                // target: max bytes of the encoded IMAGE value
```

What `compress` does internally:
1. Reads the source; **auto-rotates** per JPEG EXIF orientation.
2. **Aspect-preserving center-crop** ("cover") to 96×96 — no face distortion.
3. Encodes via the native codec (WEBP: picture preset, no alpha; AVIF: Main
   profile 8-bit 4:2:0, sRGB) and **binary-searches quality** for the largest
   image that fits `targetSize`.
4. Strips the container to image-header **version 1** (the ~275-byte AVIF shell
   / 20-byte WebP wrapper is dropped and rebuilt at decode time).

Sizing guide for faces in a QR: 96×96 in ~600–900 bytes is clearly
recognizable; 128×128 wants ~1200–1800 bytes. The QR ceiling is 2953 bytes
total, so 96×96 leaves room for the rest of the payload.

### 3.3 Put it in a payload

```java
import com.scdataminifier.ScDataWriter;

ScDataWriter w = new ScDataWriter(appId, uniqueId, minClientVersion);
w.startTlvContent()
        .addString("John Doe", 1)
        .addImage(face, 2)          // tag ID 2
        .endContent();
// … withSigner(...) / withEncryption(...) as needed …
byte[] payload = w.build();          // → render as a 2D barcode
```

---

## 4. Decode (viewer apps)

### 4.1 Parse the payload → ScImage → image file

```java
import com.scdataminifier.ScData;
import com.scdataminifier.ScDataParser;
import com.scdataminifier.model.ScImage;

ScData data = ScDataParser.parse(scannedBytes, decryptor, verifier);  // callbacks as configured
ScImage face = data.getContents().get(0).getTlvs().get(1).asImage();  // tag position

byte[] fileBytes = face.toImageBytes();   // rebuilds the WEBP/AVIF container → standard file
```

`toImageBytes()` reconstructs the full `.webp`/`.avif` from the version-1
headerless form. From here you have two decode paths:

### 4.2 Decode path A — OS-native (recommended on modern OS)

No native library needed in the viewer at all:

- **Android 12+ (API 31):** `ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(fileBytes)))`
  — decodes both WEBP and AVIF natively.
- **iOS 16+:** `UIImage(data: Data(fileBytes))` — native WEBP and AVIF.

This is the smallest, safest viewer: it ships **nothing** to obfuscate.
Use it wherever the OS version allows.

### 4.3 Decode path B — bundled libscimage (older OS / uniform pipeline)

**Android** (Java SDK runs as-is; load the obfuscated files):

```java
NativeLibraryLoader.loadFromFiles(
        Paths.get(blobPath), Paths.get(partPath));    // or NativeImageCodec.loadDefault()
BufferedImage img = NativeImageCodec.decodeToImage(face);   // desktop
// on Android use the byte API with a Bitmap:
int[] dims = new int[2];
byte[] rgb = NativeImageCodec.decodeToRgb(fileBytes, ImageType.AVIF, dims);
// build a Bitmap from rgb (dims[0]×dims[1], 3 bytes/pixel)
```

**iOS** (no JVM — call the C API from the xcframework):

```swift
var w: Int32 = 0, h: Int32 = 0
if let rgb = scimg_decode_avif(fileBytes, fileBytes.count, &w, &h) {
    // build a UIImage/CGImage from the RGB buffer (w×h, 3 bytes/pixel)
    scimg_free(rgb)
}
```

> **Mobile reconstruction is ported.** `mobile/android/ScImage.kt` and
> `mobile/ios/ScImage.swift` reproduce `ScImage.toImageBytes()` byte-identically
> (verified against Java vectors). Android can alternatively run the Java SDK
> directly. On iOS you still supply the IMAGE value bytes from your payload
> reader; the port turns them into a decodable `.webp`/`.avif`. See
> `mobile/README.md`.

---

## 5. Version pinning & upgrading later

The container-strip scheme depends on the encoder's exact output layout, so
versions are pinned and self-checked. To move to newer libwebp/libavif/aom:

1. Edit `native/VERSIONS.env`.
2. `rm -rf native/third_party native/out && ./scripts/fetch-sources.sh && ./scripts/build-<platform>.sh`.
3. Update `NativeImageCodec.PINNED_WEBP_VERSION` / `PINNED_AVIF_VERSION`.
4. **Re-capture the AVIF container template** if libavif changed its box
   layout: encode a sample and run the strip; a mismatch throws with details.
   Update the constants in `util/ImageContainers.java` from a hex dump of the
   new `avifenc`/native output.
5. Re-run the golden-image tests before rollout.
6. Ship updated viewers only if you bumped **major bitstream** features — VP8
   and AV1 bitstreams are frozen, so ordinary version bumps stay
   backward-decodable; existing payloads remain readable.

---

## 6. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `nasm not found` during aom build | `dnf install nasm` (Linux) / `winget install nasm` (Windows). aom's x86 asm needs it. |
| `libavif` can't find aom | `build-deps.sh` builds aom first and passes `-DAVIF_CODEC_AOM=SYSTEM -DCMAKE_PREFIX_PATH`. Ensure pkg-config is installed. |
| `UnsatisfiedLinkError: absolute path` | `NativeImageCodec.load` now resolves to absolute; ensure the path exists and is readable. |
| Version mismatch at startup | The linked `.so`/`.dll` isn't the pinned build. Rebuild from `VERSIONS.env`; don't use distro packages. |
| AVIF v1 strip throws "reconstruction mismatch" | Encoder output layout changed (different libavif) — re-capture the `ImageContainers` template (§5.4). |
| Cannot fit image in target bytes | Reduce dimensions or raise `targetSize`; error reports the achievable minimum. |
| `strings` shows codec names in the split files | You shipped the raw `.so`, not the packed files — re-run `LibraryObfuscator` (§2.1). |
| Decoded face looks stretched | Old build — current `compress` uses aspect-preserving cover-crop; rebuild the SDK. |

---

## 7. Quick reference

**Build:** `native/scripts/build-<platform>` → self-contained `libscimage.*`.
**Split:** `java … LibraryObfuscator <lib> app-resources.dat app-cache.bin`.
**Load:** `ScImageCodec.useObfuscatedNativeCodec(blob, part)` (or `useNativeCodec(path)`).
**Encode:** `ScImageCodec.compress(src, type, false, w, h, targetBytes)` → `ScImage`.
**Payload:** `writer.addImage(scImage, tagId)`.
**Decode:** `scImage.toImageBytes()` → OS-native decode, or `NativeImageCodec.decodeToRgb(...)`.

Related docs: `SPEC.md` (payload format), `native/README.md` (native build
details), `native/VERSIONS.env` (pinned versions).
