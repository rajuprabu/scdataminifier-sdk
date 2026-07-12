# Windows native build: produces out\windows-x64\scimage.dll with JNI, then installs it to
# resources\native\windows-x86_64\scimage.dll for the SDK jar.
#
# Prerequisites (native build on Windows):
#   - Visual Studio 2019+ with "Desktop development with C++" (or Build Tools)
#   - cmake, nasm (winget install nasm / choco install nasm), JDK 17 (JAVA_HOME set)
#   - Run from "x64 Native Tools" prompt (or CI with the VS environment active).
$ErrorActionPreference = "Stop"

# Native (exe) commands don't trip $ErrorActionPreference; check exit codes explicitly.
function Invoke-Checked {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Cmd)
    Write-Host ">> $($Cmd -join ' ')"
    & $Cmd[0] $Cmd[1..($Cmd.Length-1)]
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $($Cmd -join ' ')" }
}

# aom's x86-64 assembly needs nasm on PATH *in this process*. Fail early with a clear message.
$nasm = Get-Command nasm -ErrorAction SilentlyContinue
if (-not $nasm) {
    $guess = "C:\Program Files\NASM"
    if (Test-Path (Join-Path $guess "nasm.exe")) { $env:PATH = "$guess;$env:PATH" }
}
Invoke-Checked nasm -v

$NativeDir = Split-Path -Parent $PSScriptRoot
$Out = Join-Path $NativeDir "out\windows-x64"

# Sources are vendored under native\third_party (fetch only if missing).
$LIBWEBP_VERSION = "1.6.0"; $LIBAVIF_VERSION = "1.4.2"; $AOM_VERSION = "3.14.1"
Push-Location $NativeDir
New-Item -ItemType Directory -Force -Path third_party | Out-Null
Set-Location third_party
if (-not (Test-Path "libwebp-$LIBWEBP_VERSION")) {
    curl.exe -fL -o webp.tar.gz "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-$LIBWEBP_VERSION.tar.gz"
    tar xzf webp.tar.gz; Remove-Item webp.tar.gz
}
if (-not (Test-Path "libavif-$LIBAVIF_VERSION")) {
    curl.exe -fL -o avif.tar.gz "https://github.com/AOMediaCodec/libavif/archive/refs/tags/v$LIBAVIF_VERSION.tar.gz"
    tar xzf avif.tar.gz; Remove-Item avif.tar.gz
}
if (-not (Test-Path "libaom-$AOM_VERSION")) {
    curl.exe -fL -o aom.tar.gz "https://storage.googleapis.com/aom-releases/libaom-$AOM_VERSION.tar.gz"
    tar xzf aom.tar.gz; Remove-Item aom.tar.gz
}
Pop-Location

$Prefix = Join-Path $Out "deps"
# Static libs, static CRT (/MT) so the DLL needs no VC++ redist. CMP0091=NEW makes
# CMAKE_MSVC_RUNTIME_LIBRARY authoritative for the dependency sub-builds.
$Common = @("-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_INSTALL_PREFIX=$Prefix",
            "-DBUILD_SHARED_LIBS=OFF", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
            "-DCMAKE_POLICY_DEFAULT_CMP0091=NEW",
            "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded")

Write-Host "=== libwebp $LIBWEBP_VERSION ==="
Invoke-Checked cmake -S "$NativeDir\third_party\libwebp-$LIBWEBP_VERSION" -B "$Out\deps-build\webp" @Common `
    -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF `
    -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_VWEBP=OFF `
    -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_EXTRAS=OFF
Invoke-Checked cmake --build "$Out\deps-build\webp" --config Release
Invoke-Checked cmake --install "$Out\deps-build\webp" --config Release

Write-Host "=== aom $AOM_VERSION ==="
Invoke-Checked cmake -S "$NativeDir\third_party\libaom-$AOM_VERSION" -B "$Out\deps-build\aom" @Common `
    -DENABLE_TESTS=0 -DENABLE_EXAMPLES=0 -DENABLE_DOCS=0 -DENABLE_TOOLS=0 `
    -DCONFIG_AV1_ENCODER=1 -DCONFIG_AV1_DECODER=1
Invoke-Checked cmake --build "$Out\deps-build\aom" --config Release
Invoke-Checked cmake --install "$Out\deps-build\aom" --config Release

Write-Host "=== libavif $LIBAVIF_VERSION ==="
Invoke-Checked cmake -S "$NativeDir\third_party\libavif-$LIBAVIF_VERSION" -B "$Out\deps-build\avif" @Common `
    -DAVIF_CODEC_AOM=SYSTEM -DAVIF_LIBYUV=OFF -DAVIF_BUILD_APPS=OFF `
    -DAVIF_BUILD_EXAMPLES=OFF -DAVIF_BUILD_TESTS=OFF -DCMAKE_PREFIX_PATH="$Prefix"
Invoke-Checked cmake --build "$Out\deps-build\avif" --config Release
Invoke-Checked cmake --install "$Out\deps-build\avif" --config Release

Write-Host "=== installed dependency libs ==="
Get-ChildItem "$Prefix\lib" -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "   $($_.Name)" }

Write-Host "=== scimage.dll ==="
Invoke-Checked cmake -S $NativeDir -B "$Out\build" -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$Prefix" -DSCIMG_JNI=ON `
    -DCMAKE_POLICY_DEFAULT_CMP0091=NEW -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded
Invoke-Checked cmake --build "$Out\build" --config Release
Copy-Item "$Out\build\Release\scimage.dll" $Out
Write-Host "Built: $Out\scimage.dll"

# Install straight into the jar-resource layout that BundledNativeLoader reads.
$SdkDir = Split-Path -Parent $NativeDir
$ResDir = Join-Path $SdkDir "resources\native\windows-x86_64"
New-Item -ItemType Directory -Force -Path $ResDir | Out-Null
Copy-Item "$Out\scimage.dll" (Join-Path $ResDir "scimage.dll") -Force
Write-Host "Installed: resources\native\windows-x86_64\scimage.dll  (run build-jar.sh next)"
