<#
  Cross-compile OpenRender for Android arm64 (libopenrender.so) with:
    - feature `jni`   : exports Java_com_hxy_codeforge_render_OpenRenderNative_*
    - feature `ratex` : pure-Rust KaTeX-compatible math -> PNG (embedded fonts)

  Pinned upstream: raine-x/OpenRender @ 6f11851722772b1a18388364b20a613a0aa2a020
  (2026-10-28). The vendored patch `app_jni.rs` adds the CodeForge JNI surface.
  It also embeds `fonts/NotoSansSC-Subset.ttf` (OFL-1.1) as the Unicode
  fallback font — Android has no discoverable system CJK font, so without it
  every formula containing CJK fails to render.

  Prerequisites:
    rustup target add aarch64-linux-android
    Android NDK r25+ (r28 tested)

  Example:
    .\tools\openrender\build-android.ps1
#>
param(
  [string]$WorkDir = 'D:\temp\OpenRender-src',
  [string]$Ndk = 'D:\AndroidSDK\ndk\28.2.13676358',
  [string]$OutDir = (Join-Path $PSScriptRoot '..\..\app\src\main\jniLibs\arm64-v8a')
)

$ErrorActionPreference = 'Stop'
$commit = '6f11851722772b1a18388364b20a613a0aa2a020'

if (-not (Test-Path (Join-Path $WorkDir 'Cargo.toml'))) {
  Write-Host "==> fetching OpenRender @ $commit"
  $zip = Join-Path $env:TEMP 'openrender-src.zip'
  Invoke-WebRequest -Uri "https://api.github.com/repos/raine-x/OpenRender/zipball/$commit" `
    -Headers @{ 'User-Agent' = 'CodeForge' } -OutFile $zip
  $extract = "$WorkDir-extract"
  if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
  Expand-Archive -Path $zip -DestinationPath $extract -Force
  $inner = Get-ChildItem $extract | Select-Object -First 1
  New-Item -ItemType Directory -Path (Split-Path $WorkDir -Parent) -Force | Out-Null
  Move-Item $inner.FullName $WorkDir -Force
  Remove-Item $extract -Recurse -Force
}

$src = Join-Path $WorkDir 'crates\openrender-core\src'
Copy-Item (Join-Path $PSScriptRoot 'app_jni.rs') (Join-Path $src 'app_jni.rs') -Force
# Unicode 回退字体：app_jni.rs 用 include_bytes! 引用它，必须与 app_jni.rs 同目录。
# Android 上没有可发现的系统 CJK 字体，没有它则含中文的公式整块渲染不出来。
Copy-Item (Join-Path $PSScriptRoot 'fonts\NotoSansSC-Subset.ttf') (Join-Path $src 'NotoSansSC-Subset.ttf') -Force
$lib = Join-Path $src 'lib.rs'
$libText = Get-Content $lib -Raw
if ($libText -notmatch 'mod app_jni;') {
  $libText = $libText -replace '(?m)^(\s*mod jni_bridge;)$', "`$1`r`n`r`n// CodeForge host JNI bridge (com.hxy.codeforge.render).`r`n#[cfg(feature = `"jni`")]`r`nmod app_jni;"
  Set-Content -Path $lib -Value $libText -NoNewline
}

$bin = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$env:ANDROID_NDK_HOME = $Ndk
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = Join-Path $bin 'aarch64-linux-android26-clang.cmd'
$env:CC_aarch64_linux_android = Join-Path $bin 'aarch64-linux-android26-clang.cmd'
$env:AR_aarch64_linux_android = Join-Path $bin 'llvm-ar.exe'
$env:RANLIB_aarch64_linux_android = Join-Path $bin 'llvm-ranlib.exe'

$cargo = if (Get-Command cargo -ErrorAction SilentlyContinue) {
  'cargo'
} else {
  Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe'
}
if (-not (Test-Path $cargo) -and $cargo -ne 'cargo') {
  throw 'cargo not found; install Rust (rustup) first'
}

Push-Location $WorkDir
try {
  & $cargo build --release --target aarch64-linux-android -p openrender-core --features jni,ratex
  if ($LASTEXITCODE -ne 0) { throw 'cargo build failed' }
} finally {
  Pop-Location
}

$so = Join-Path $WorkDir 'target\aarch64-linux-android\release\libopenrender.so'
if (-not (Test-Path $so)) { throw "missing $so" }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
Copy-Item $so (Join-Path $OutDir 'libopenrender.so') -Force

$item = Get-Item (Join-Path $OutDir 'libopenrender.so')
Write-Host ("==> {0}  {1:N0} bytes  sha256 {2}" -f $item.FullName, $item.Length, (Get-FileHash $item.FullName -Algorithm SHA256).Hash)
