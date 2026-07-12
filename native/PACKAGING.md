# Packaging the SDK jar with per-OS natives + ZKM obfuscation

The SDK ships **one jar** that carries a native `scimage` codec for each supported OS/arch
and loads the right one at runtime (`ScImageCodec.loadBundledNative()` →
`BundledNativeLoader`, which reads `native/<os>-<arch>/…` from the jar).

## Jar-resource layout

```
resources/native/
  macos-aarch64/libscimage.dylib     <- built (this machine)
  macos-x86_64/libscimage.dylib      <- build on an Intel Mac (or CI)
  linux-x86_64/libscimage.so         <- build on RHEL9/Rocky9 or via Dockerfile.linux
  linux-aarch64/libscimage.so        <- build on an arm64 Linux box (or CI)
  windows-x86_64/scimage.dll         <- build on Windows (MSVC) or CI
```
`build-jar.sh` embeds every real binary present and skips the `README.txt` placeholders,
so a jar built today (macOS only) works on macOS and reports a clear error elsewhere until
the other binaries are dropped in.

## Why these are NOT cross-compiled

The native statically links pinned **libwebp 1.6.0 + libavif 1.4.2 + aom 3.14.1** (aom needs
nasm x86 assembly), and the codec **verifies byte-identical rebuilds**. A cross-compiled
binary that differs even slightly breaks that invariant silently. So each OS binary is built
on that OS (or its container), exactly as the platform scripts state. This cannot be done
correctly from a single macOS host.

## Producing each native

| Target            | How | Output |
|-------------------|-----|--------|
| macOS (this host) | `native/scripts/build-macos.sh` then `native/scripts/install-to-resources.sh` | `resources/native/macos-<arch>/libscimage.dylib` |
| Linux x86_64      | On RHEL9/Rocky9: `build-linux.sh` + `install-to-resources.sh` — **or** `docker build -f native/Dockerfile.linux -t scimage-linux .` (context = SCDataMinifier dir) then copy the `.so` out | `resources/native/linux-x86_64/libscimage.so` |
| Linux aarch64     | Same on an arm64 Linux host/runner | `resources/native/linux-aarch64/libscimage.so` |
| Windows x86_64    | From an "x64 Native Tools" prompt: `native/scripts/build-windows.ps1`, then copy `native/out/windows-x64/scimage.dll` → `resources/native/windows-x86_64/` | `resources/native/windows-x86_64/scimage.dll` |

Prereqs per platform are documented at the top of each script (RHEL: `gcc gcc-c++ cmake nasm
java-17-openjdk-devel`; Windows: VS2019+ C++ tools, cmake, nasm, JDK 17).

### One-shot, all OSes: CI
`.github/workflows/build-native-jar.yml` builds the native on macOS + Linux + Windows
runners, collects them into `resources/native/`, runs `build-jar.sh`, and (on a self-hosted
runner labeled `zkm`, commit message contains `[obfuscate]`) runs `obfuscate.sh`. This is the
recommended way to produce a fully-populated, obfuscated release jar.

## Build + obfuscate (once the natives are in place)

```bash
cd SCDataMinifier
./build-jar.sh      # -> dist/scdataminifier.jar   (classes + native/<os>-<arch>/…)
./obfuscate.sh      # -> dist/obf/scdataminifier.jar (ZKM: flow+string+exception obf)
```

`obfuscate.sh` uses `ZKM_HOME` (default `/Users/praburaju/P_DRIVE/ZKM`) and the script
`build/zkm/scdataminifier.zkm`. That script keeps public API names + the JNI class
`NativeImageCodec` (native methods bind by name) and obfuscates everything else. The embedded
`.dylib`/`.so`/`.dll` are non-class resources and pass through ZKM unchanged.

## Using the jar

```java
import com.scdataminifier.image.ScImageCodec;

ScImageCodec.loadBundledNative();   // picks native/<os>-<arch>/… for this machine
// ... now ScImageCodec.compress(...) / decode(...) use the native codec
```

Alternatives still available: `useNativeCodec(path)` (explicit file),
`useObfuscatedNativeCodec(blob, part)` (split/scrambled parts), `NativeImageCodec.loadDefault()`
(`java.library.path` / Android `jniLibs`).
