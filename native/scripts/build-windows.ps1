# Windows native build: produces out\windows-x64\scimage.dll with JNI.
#
# Prerequisites (native build on Windows):
#   - Visual Studio 2019+ with "Desktop development with C++" (or Build Tools)
#   - cmake, nasm (winget install nasm / choco install nasm), JDK 17 (JAVA_HOME set)
#   - git-bash or WSL is NOT required; run from "x64 Native Tools" prompt.
#
# Alternative: cross-compile from Linux with mingw-w64, see build-windows-cross.sh.
$ErrorActionPreference = "Stop"
$NativeDir = Split-Path -Parent $PSScriptRoot
$Out = Join-Path $NativeDir "out\windows-x64"

# fetch sources (requires curl + tar, both ship with Windows 10+)
Push-Location $NativeDir
Get-Content VERSIONS.env | Where-Object { $_ -match '^\w+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    Set-Variable -Name $k -Value $ExecutionContext.InvokeCommand.ExpandString($v.Replace('${', '$(Get-Variable -ValueOnly ''').Replace('}', ''')'))
}
# simpler: hardcoded from VERSIONS.env
$LIBWEBP_VERSION = "1.6.0"; $LIBAVIF_VERSION = "1.4.2"; $AOM_VERSION = "3.14.1"
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
$Common = @("-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_INSTALL_PREFIX=$Prefix",
            "-DBUILD_SHARED_LIBS=OFF", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
            "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded")  # static CRT

cmake -S "$NativeDir\third_party\libwebp-$LIBWEBP_VERSION" -B "$Out\deps-build\webp" @Common `
    -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF `
    -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_VWEBP=OFF `
    -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_EXTRAS=OFF
cmake --build "$Out\deps-build\webp" --config Release; cmake --install "$Out\deps-build\webp" --config Release

cmake -S "$NativeDir\third_party\libaom-$AOM_VERSION" -B "$Out\deps-build\aom" @Common `
    -DENABLE_TESTS=0 -DENABLE_EXAMPLES=0 -DENABLE_DOCS=0 -DENABLE_TOOLS=0
cmake --build "$Out\deps-build\aom" --config Release; cmake --install "$Out\deps-build\aom" --config Release

cmake -S "$NativeDir\third_party\libavif-$LIBAVIF_VERSION" -B "$Out\deps-build\avif" @Common `
    -DAVIF_CODEC_AOM=SYSTEM -DAVIF_LIBYUV=OFF -DAVIF_BUILD_APPS=OFF `
    -DAVIF_BUILD_EXAMPLES=OFF -DAVIF_BUILD_TESTS=OFF -DCMAKE_PREFIX_PATH="$Prefix"
cmake --build "$Out\deps-build\avif" --config Release; cmake --install "$Out\deps-build\avif" --config Release

cmake -S $NativeDir -B "$Out\build" -DCMAKE_BUILD_TYPE=Release -DDEPS_PREFIX="$Prefix" -DSCIMG_JNI=ON `
    -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded
cmake --build "$Out\build" --config Release
Copy-Item "$Out\build\Release\scimage.dll" $Out
Write-Host "Built: $Out\scimage.dll"

# Install straight into the jar-resource layout that BundledNativeLoader reads.
$SdkDir = Split-Path -Parent $NativeDir
$ResDir = Join-Path $SdkDir "resources\native\windows-x86_64"
New-Item -ItemType Directory -Force -Path $ResDir | Out-Null
Copy-Item "$Out\scimage.dll" (Join-Path $ResDir "scimage.dll") -Force
Write-Host "Installed: resources\windows-x86_64\scimage.dll  (run build-jar.sh next)"
