# Testing the SDK jar on Rocky Linux 9.x (also RHEL 9 / AlmaLinux 9)

End-to-end check that the jar loads its bundled native (split + scrambled parts,
reconstructed in memory) and encodes/decodes on Rocky 9. Rocky 9 ships glibc 2.34 and the
Linux native needs ≤ 2.34, so it is compatible; RHEL/Rocky 10 (glibc 2.39) work too. RHEL/
Rocky **8** do NOT (glibc 2.28 is too old).

Everything below runs as a normal user (no root except the one `dnf install`).

## 0. Files you need on the box

- `scdataminifier.jar` — the SDK jar (with the linux-x86_64 parts inside). Use either:
  - the CI artifact `scdataminifier-jar`, or
  - `dist/scdataminifier.jar` / `dist/obf/scdataminifier.jar` from a local build.
- `SdkSmokeTest.java` — this folder's sample.

Copy them over, e.g. from your machine:
```bash
scp dist/obf/scdataminifier.jar samples/SdkSmokeTest.java user@rocky9-host:~/sdktest/
```

## 1. Install Java 17

```bash
sudo dnf install -y java-17-openjdk-devel
java -version        # expect: openjdk version "17...."
```

## 2. Preflight (optional but recommended)

```bash
# glibc must be >= 2.34 (Rocky 9 = 2.34)
ldd --version | head -1

# The in-memory loader writes the reconstructed .so to /dev/shm then dlopen()s it.
# Confirm /dev/shm is NOT mounted noexec (default Rocky 9 is fine; CIS-hardened may not be):
mount | grep '/dev/shm'      # must NOT contain the word "noexec"
```
If `/dev/shm` shows `noexec`, see "Hardened systems" below.

## 3. Compile and run

```bash
cd ~/sdktest
javac -cp scdataminifier.jar SdkSmokeTest.java
java  -cp scdataminifier.jar:. SdkSmokeTest
```

## 4. Expected output

```
OS      : Linux / amd64
Java    : 17.0.x
Native  : loaded=true
Codec   : aom [enc/dec]:v3.14.1
Encode  : WEBP=270B -> out.webp,  AVIF=406B -> out.avif
Decode  : WEBP -> 64x64 (12288 RGB bytes)
RESULT  : SUCCESS - native codec works end-to-end
```
`out.webp` and `out.avif` are written to the current directory — real, viewable images.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `No bundled native part for this platform ... linux-x86_64` | The jar was built without the linux binary. Use the CI `scdataminifier-jar` or a local build that included `resources/native/linux-x86_64/`. |
| `UnsatisfiedLinkError: ... GLIBC_2.xx not found` | Native built against a newer glibc than this host. Shouldn't happen on Rocky 9 (needs ≤2.34); if it does, rebuild the `.so` on Rocky 9 (`native/Dockerfile.linux`). |
| `Failed to load reconstructed native library` + `/dev/shm` is `noexec` | Hardened mount — see below. |
| `version mismatch: libwebp=... libavif=...` | Wrong/old native binary in the jar; rebuild it. |

### Hardened systems (noexec /dev/shm or /tmp, SELinux)

The loader stages the reconstructed native under `/dev/shm` → `/run/user/<uid>` →
`java.io.tmpdir`, then `dlopen`s it. If all candidate dirs are mounted `noexec`, point the JVM
at an exec-capable directory:
```bash
mkdir -p ~/sdk-exec
java -Djava.io.tmpdir=$HOME/sdk-exec -cp scdataminifier.jar:. SdkSmokeTest
```
(Ensure `~` is not itself on a `noexec` mount.) Under SELinux enforcing with a confined JVM
domain you may also need `execmem`/tmpfs allowances; a JVM started normally (unconfined) is not
affected.
