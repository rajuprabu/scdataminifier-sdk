# Building scimage.dll on Windows

Produces `scimage.dll` (statically-linked libwebp 1.6.0 + libavif 1.4.2 + aom 3.14.1 + JNI)
and installs it to `resources/native/windows-x86_64/scimage.dll` for the SDK jar.

The dependency sources are already vendored under `native/third_party/` (libwebp-1.6.0,
libavif-1.4.2, libaom-3.14.1), so the build runs **offline**.

## 1. Prerequisites (install once)

- **Visual Studio 2019 or 2022** with the **"Desktop development with C++"** workload
  (or the standalone *Build Tools for Visual Studio*).
- **CMake** — `winget install Kitware.CMake`
- **NASM** (required by aom's x86-64 assembly) — `winget install NASM.NASM`
  (or `choco install nasm`). Make sure it's on `PATH`.
- **JDK 17** with `JAVA_HOME` set (CMake's `find_package(JNI)` needs it).

Verify NASM is visible: open a new terminal and run `nasm -v`.

## 2. Build

Open **"x64 Native Tools Command Prompt for VS 2022"** (Start menu — this is what puts the
MSVC compiler on `PATH`; a plain PowerShell/cmd will fail with "cl not found"). Then:

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-17
cd /d P:\Projects\ProjectSecureCodeLibrary\SDK\java\eclipse\SCDataMinifier\native\scripts
powershell -ExecutionPolicy Bypass -File build-windows.ps1
```

(Adjust the drive/path to wherever P_DRIVE is mounted on this PC, and `JAVA_HOME` to your
JDK.) The build takes a few minutes — aom is the slow part.

On success you'll see:
```
Built: ...\out\windows-x64\scimage.dll
Installed: resources\windows-x86_64\scimage.dll  (run build-jar.sh next)
```

## 3. Package the jar

`resources/native/windows-x86_64/scimage.dll` is now in place. Build the jar (on Windows via
`jar`/`javac`, or back on any machine that has the tree):

```bat
cd ..\..
:: needs JDK 17 on PATH; on Windows run the equivalent of build-jar.sh, or just:
javac --release 17 -encoding UTF-8 -d build\jar-classes @build\sources.txt
```

Easiest is to run `build-jar.sh` (Git-Bash) or let CI/the Mac assemble the final jar once the
`.dll` is committed to `resources/native/windows-x86_64/`.

## 4. Quick smoke test on Windows (optional)

```bat
javac -cp dist\scdataminifier.jar -d build\smoke path\to\BundledLoadTest.java
java -cp dist\scdataminifier.jar;build\smoke BundledLoadTest
```
Expected: `resolved resource: native/windows-x86_64/scimage.dll`, `loaded=true`, and non-zero
WEBP/AVIF byte counts.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `cl : command not found` / CMake picks the wrong compiler | You're not in the **x64 Native Tools** prompt. Open that specific Start-menu entry. |
| `Could NOT find JNI` | `JAVA_HOME` not set to a JDK (not a JRE). `set JAVA_HOME=...` then re-run. |
| aom fails with assembler errors / `nasm not found` | Install NASM and ensure `nasm -v` works in the same shell. |
| `AVIF_LIB not found under .../lib` | A dependency's `cmake --install` didn't finish — scroll up for the first error (usually aom/nasm). Delete `native\out\windows-x64` and re-run. |
| DLL builds but `UnsatisfiedLinkError` at load | Mismatched arch (building 32-bit) — confirm the **x64** Native Tools prompt. |
