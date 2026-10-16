param(
    [Parameter(Mandatory=$true)][string]$GoExe,
    [Parameter(Mandatory=$true)][string]$PythonExe,
    [Parameter(Mandatory=$true)][string]$AndroidSdk
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$GoExe = (Resolve-Path -LiteralPath $GoExe).Path
$PythonExe = (Resolve-Path -LiteralPath $PythonExe).Path
$output = Join-Path $root 'build/native-cache'
New-Item -ItemType Directory -Force $output | Out-Null
& $PythonExe (Join-Path $PSScriptRoot 'prepare.py')
if ($LASTEXITCODE) { throw 'WebDAV overlay preparation failed' }
$overlay = Join-Path $root 'build/webdav-overlay/overlay.json'
$saved = @{}
foreach ($name in @('GOOS','GOARCH','CGO_ENABLED','CC','CGO_CFLAGS','CGO_LDFLAGS')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Push-Location (Join-Path $root 'external/webdav-tunnel')
try {
    $env:GOOS='windows'; $env:GOARCH='amd64'; $env:CGO_ENABLED='0'
    & $GoExe test -overlay $overlay ./... *> (Join-Path $root 'build/stage-d-native-test.log')
    if ($LASTEXITCODE) { throw 'WebDAV native tests failed' }
    & $GoExe build -overlay $overlay -buildvcs=false -trimpath -ldflags='-s -w -checklinkname=0' -o (Join-Path $output 'webdav-check.exe') .
    if ($LASTEXITCODE) { throw 'Host WebDAV build failed' }
    & $PythonExe (Join-Path $PSScriptRoot 'loopback_test.py') *> (Join-Path $root 'build/stage-d-loopback.log')
    if ($LASTEXITCODE) { throw 'WebDAV storage/exit loopback test failed' }
    $compiler = Join-Path $AndroidSdk 'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd'
    if (!(Test-Path -LiteralPath $compiler)) { throw 'NDK compiler not found' }
    $env:GOOS='android'; $env:GOARCH='arm64'; $env:CGO_ENABLED='1'
    $env:CC='"'+$compiler+'"'
    $env:CGO_CFLAGS='-target aarch64-linux-android26 -fPIC'
    $env:CGO_LDFLAGS='-target aarch64-linux-android26 -Wl,--no-undefined -Wl,-z,max-page-size=16384'
    & $GoExe build -overlay $overlay -buildvcs=false -trimpath -ldflags='-s -w -checklinkname=0' -o (Join-Path $output 'libwebdav.so') .
    if ($LASTEXITCODE) { throw 'Android WebDAV build failed' }
    $lock = Get-Content -LiteralPath (Join-Path $root 'native-lock.json') -Raw | ConvertFrom-Json
    $provenance = [ordered]@{ commit=$lock.WebDAV.commit; goVersion=(& $GoExe version); sources=[ordered]@{}; binaries=[ordered]@{} }
    $sources = @('native/webdav/upstream-sha256.json') + @(Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object Extension -in '.py','.go','.ps1' | ForEach-Object { [IO.Path]::GetRelativePath($root,$_.FullName).Replace('\','/') })
    $sources += @(Get-ChildItem -LiteralPath (Join-Path $root 'external/webdav-tunnel') -Recurse -File | ForEach-Object { [IO.Path]::GetRelativePath($root,$_.FullName).Replace('\','/') })
    foreach ($name in $sources) { $provenance.sources[$name] = (Get-FileHash -LiteralPath (Join-Path $root $name) -Algorithm SHA256).Hash.ToLower() }
    foreach ($name in @('webdav-check.exe','libwebdav.so')) { $provenance.binaries[$name] = (Get-FileHash -LiteralPath (Join-Path $output $name) -Algorithm SHA256).Hash.ToLower() }
    $provenance | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'webdav-provenance.json') -Encoding utf8
} finally {
    Pop-Location
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
