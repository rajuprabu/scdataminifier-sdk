# Mobile viewer ports — ScImage reconstruction

Viewer-side ports of the SCDataMinifier image reconstruction, so Android and
iOS apps can turn a parsed IMAGE value back into a complete WEBP/AVIF file and
hand it to the OS decoder. Both are verified **byte-identical** to the Java
`model.ScImage` + `util.ImageContainers` build path (WEBP 808 B, AVIF 1036 B
test vectors matched exactly).

| File | Platform | Purpose |
|---|---|---|
| `android/ScImage.kt` | Android | image reconstruction (Kotlin, no deps) |
| `ios/ScImage.swift`  | iOS      | image reconstruction (Swift, no deps) |
| `ios/ScDataParser.swift` | iOS  | full payload reader (Swift, no deps) |

## Scope

- **Image reconstruction** (`ScImage`): takes an IMAGE value (image-header
  byte + version-1 headerless data) and returns the full WEBP/AVIF file.
  Rebuild only — the encoder-side strip logic is not needed on viewers.
- **Payload reader** (`ScDataParser.swift`, iOS): full port of the Java
  `ScDataParser` — header, all content types (TLV / COMPRESSED_TLV /
  VARIABLES), all tag types, tables, variables, CRC16 check, with encryption
  and signature verification injected as closures. Verified field-for-field
  identical to the Java parser on a mixed payload (string/int/float/unicode/
  image/table/variables/ZIP-compressed).

On **Android** you can run the Java SCDataMinifier SDK directly (it is a JAR),
so only `ScImage.kt` is needed there; a Kotlin parser port is unnecessary. On
**iOS** there is no JVM, so `ScDataParser.swift` gives you the whole reader.

## Android (Kotlin)

```kotlin
val img = ScImage.parse(imageValueBytes)     // from the parsed TLV
val fileBytes = img.toImageBytes()           // complete .webp / .avif

// Decode - WEBP works on all Android; AVIF is native on Android 12+ (API 31):
val bitmap = if (Build.VERSION.SDK_INT >= 28)
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(fileBytes)))
else
    BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size)
```

For AVIF on Android < 12, either bundle the native `libscimage.so`
(`NativeImageCodec.decodeToRgb`) or an AVIF decoder library (Coil/Glide AVIF).

## iOS (Swift)

Full pipeline — parse the scanned payload, pull the face image, decode:

```swift
let data = try ScDataParser.parse(scannedBytes,
    decrypt: { header, ivAndCiphertext in myDecrypt(header, ivAndCiphertext) }, // if encrypted
    verify:  { header, alg, kv, signed, sig in myVerify(alg, kv, signed, sig) }) // if signed

// find the IMAGE tlv (e.g. tag ID 5) in the first content
if case .tlv(let tlvs) = data.contents[0],
   let imageTlv = tlvs.first(where: { $0.type == .image }) {
    let fileBytes = try imageTlv.asImage().toImageBytes()   // complete .avif / .webp
    let uiImage = UIImage(data: Data(fileBytes))            // iOS 16+ decodes both natively
}
```

`ScDataParser.parse` handles unencrypted+unsigned payloads with no closures
(the CRC16 is checked automatically). Supply `decrypt`/`verify` only for
payloads that use them. For AVIF on iOS < 16, bundle `scimage.xcframework`
(call `scimg_decode_avif` on `fileBytes`) or an AVIF pod.

**Compression note:** `ScDataParser.swift` decodes all three compression
types: ZIP (raw DEFLATE) and GZIP via Apple's Compression framework, and
ZIP_DICT (preset dictionary) via zlib (`inflateSetDictionary`). The file does
`import zlib` and must be linked against **libz** (add `-lz`, or `libz.tbd`
in Xcode → Build Phases → Link Binary With Libraries). Supply the dictionary
through the `dictionary:` closure on `parse(...)`:

```swift
let data = try ScDataParser.parse(scannedBytes,
    dictionary: { header, version in myDictionaries[version] })  // ZIP_DICT only
```

All three compression types are verified against Java-produced payloads.

## Keeping the ports in sync

The AVIF rebuild embeds a template that mirrors libavif's container layout
for the pinned version (see `native/VERSIONS.env`). If you ever bump libavif
and its box layout changes, re-capture the template in the Java
`util/ImageContainers.java` **and** update the same hex constants
(`FTYP`/`HDLR`/`PITM`/`IINF`/`PIXI`/`COLR`/`IPMA` and the two `iloc`/`meta`
size calcs) in both ports. WEBP's rebuild is arithmetic and never changes.

Re-run the vector test after any change: generate `<name>.value` /
`<name>.expected` from the Java side and confirm both ports reproduce
`.expected` byte-for-byte.
