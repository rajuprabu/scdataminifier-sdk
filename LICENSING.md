# SCData native library — licensing

The native library (`.so`/`.dylib`/`.a`) is **license-gated**: its value methods
(encode/decode) refuse to run until the app presents a **digitally-signed license** bound to
that app's package. The verification key is compiled into the library, so validation is fully
offline and can't be swapped out without rebuilding the binary.

```
  SCLicenseGenerator (Java, offline)                 App + native library (Android/iOS)
  ┌───────────────────────────────┐                 ┌─────────────────────────────────┐
  │ 1st run: make P-256 key pair   │  public key     │ ScImageCodec.applyLicense(       │
  │  → keys/license_private.pem    │ ───(embedded)──▶│   licBytes, packageName)         │
  │  → keys/license_pubkey.h  ─────┼── into .so ────▶│     └─ sc_license_init() verifies:│
  │ per customer: sign a .lic      │                 │        • signature (embedded key) │
  │  → licenses/<Licensed To>.lic  │ ──(ship .lic)──▶│        • package == caller        │
  └───────────────────────────────┘                 │        • dates (unless no-check)  │
                                                     │   only then encode/decode work    │
                                                     └─────────────────────────────────┘
```

## License fields (signed)

package name · licensed-to · city · state · country · valid-from · valid-till ·
no-date-check flag · **ECDSA P-256 signature** over all of the above.
Canonical byte layout: `SCLicenseGenerator/src/.../LicenseFormat.java` (mirrored by
`native/src/license.c`).

## 1. Generate the signing key + a license (Java console app)

```bash
cd SCLicenseGenerator
./run.sh          # or: javac -d bin src/**/*.java && java -cp bin com.scdataminifier.license.LicenseGenerator
```
- **First run** creates the key pair in `keys/` (private + public PEM, raw pubkey, and
  `license_pubkey.h`). **Keep `license_private.pem` secret.**
- Then it prompts for each field (showing a `[sample]` you can accept with Enter) and writes
  `licenses/<Licensed To sanitised>.lic` — special characters stripped, `.lic` extension.

## 2. Embed the public key + rebuild the native library

```bash
cp SCLicenseGenerator/keys/license_pubkey.h SCDataMinifier/native/src/license_pubkey.h
# then rebuild the .so/.dylib/.a — e.g. via CI (build-native-jar.yml) or:
#   native/scripts/build-linux.sh | build-macos.sh | build-windows.ps1 | build-android.sh | build-ios.sh
```
CMake already compiles `license.c`, `sha256.c` and `micro-ecc`, and forces micro-ecc's
portable C path. **Any change to the key requires rebuilding every platform binary.**

## 3. Use it from the app

The app reads the `.lic`, base64-decodes the body, and calls the license method **before any
codec call**:

```java
// Android / desktop (through the SDK jar)
byte[] lic = base64DecodeBodyOf(".lic file");
int r = ScImageCodec.applyLicense(lic, context.getPackageName());   // 0 == licensed
if (r != 0) { /* refuse: -2 signature, -3 package, -4 not-yet, -5 expired, -1 malformed */ }
```
```swift
// iOS (direct C API, static-linked)
let r = sc_license_init(licBytes, licBytes.count, Bundle.main.bundleIdentifier)
```
Until `applyLicense`/`sc_license_init` returns 0, every `scimg_encode_*`/`scimg_decode_*`
returns an error — so **only a validly-licensed app can use the library**.

## Verified

`native/test/license_test.c` compiled on macOS and validated the exact `.lic` the Java app
signed:
- valid license + correct package → `OK (licensed)`
- wrong package → `PACKAGE MISMATCH`
- tampered bytes → `BAD SIGNATURE`
(Java signs with the JDK; the C side verifies with micro-ecc + a self-contained SHA-256.)

## Threat notes (honest)

- The app passes its own package name; a determined attacker on a rooted device could pass a
  different name, but they still need a **validly-signed license for that package** — which
  only the private-key holder can issue, per customer. The signature binds the license to one
  package + validity window.
- The `.so` can be extracted and the gate is software — obfuscation raises cost, not a wall.
  There are no secrets in the library (only the public key). See `OBFUSCATION` notes for the
  Android split-load / iOS static-link + app-level obfuscation layers.
- For hard package enforcement, the native could additionally read the real package from the
  OS (JNI on Android, `Bundle.main.bundleIdentifier` on iOS) instead of trusting the caller —
  marked as a hardening TODO.
