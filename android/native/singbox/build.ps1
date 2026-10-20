param(
    [Parameter(Mandatory=$true)][string]$GoExe,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [string]$NdkVersion = '28.2.13676358'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$spec = (Get-Content (Join-Path $root 'native-lock.json') -Raw | ConvertFrom-Json).SingBoxExtended
$source = Join-Path $root $spec.sourceDir
$GoExe = (Resolve-Path -LiteralPath $GoExe).Path
$compiler = Join-Path $AndroidSdk "ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
if (!(Test-Path -LiteralPath $compiler)) { throw "NDK compiler missing: $compiler" }
if ((git -C $source rev-parse HEAD) -ne $spec.commit) { throw 'sing-box-extended revision mismatch' }
if (git -C $source status --porcelain) { throw 'sing-box-extended source must be clean' }
$modFile = Join-Path $root $spec.utls.modFile
$sumFile = Join-Path $root $spec.utls.sumFile
$replace = "$($spec.utls.module) => $($spec.utls.module) $($spec.utls.version)"
if (!(Get-Content -LiteralPath $modFile -Raw).Contains($replace)) { throw 'uTLS override differs from native lock' }
$modArgs = @('-mod=readonly', "-modfile=$modFile")
$cache = Join-Path $root 'build/native-cache'
$nativeDir = Join-Path $root 'app/src/main/jniLibs/arm64-v8a'
$assetDir = Join-Path $root 'app/src/main/assets/native'
New-Item -ItemType Directory -Force -Path $cache,$nativeDir,$assetDir | Out-Null
$overlayInput = Join-Path $root $spec.sourceOverlay
$overlaySpec = Get-Content -LiteralPath $overlayInput -Raw | ConvertFrom-Json
$overlayMap = @{}
$sourceAdditionHashes = @{}
foreach ($file in $overlaySpec.PSObject.Properties) {
    $original = Join-Path $source $file.Name
    if ($file.Value -is [pscustomobject] -and $file.Value.source) {
        $addition = (Resolve-Path -LiteralPath (Join-Path $root $file.Value.source)).Path
        $overlayMap[$original] = $addition
        $sourceAdditionHashes[$file.Value.source] = (Get-FileHash -LiteralPath $addition -Algorithm SHA256).Hash
        continue
    }
    $patched = [IO.File]::ReadAllText($original).Replace("`r`n", "`n")
    foreach ($edit in $file.Value) {
        if ([regex]::Matches($patched, [regex]::Escape($edit.old)).Count -ne 1) { throw "Overlay anchor mismatch in $($file.Name)" }
        $patched = $patched.Replace($edit.old, $edit.new)
    }
    $destination = Join-Path $cache ("overlay/" + $file.Name)
    New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
    [IO.File]::WriteAllText($destination, $patched, [Text.UTF8Encoding]::new($false))
    $overlayMap[$original] = $destination
}
$dependencyOverlay = & (Join-Path $PSScriptRoot 'dependency-overlay.ps1') -GoExe $GoExe -Root $root -Source $source -ModFile $modFile
$modArgs = @('-mod=readonly', "-modfile=$($dependencyOverlay.ModFile)")
foreach ($entry in $dependencyOverlay.Replace.GetEnumerator()) { $overlayMap[$entry.Key] = $entry.Value }
$overlayFile = Join-Path $cache 'go-overlay.json'
[IO.File]::WriteAllText($overlayFile, (@{Replace=$overlayMap} | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
$modArgs += "-overlay=$overlayFile"
$saved = @{}
foreach ($name in @('GOOS','GOARCH','CGO_ENABLED','CC','CGO_CFLAGS','CGO_LDFLAGS')) { $saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
Push-Location $source
try {
    $flags = "-s -w -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=$($spec.version)"
    $env:GOOS='windows'; $env:GOARCH='amd64'; $env:CGO_ENABLED='0'
    & $GoExe test @modArgs -count=1 -timeout=60s (Join-Path $PSScriptRoot 'snell_roundtrip_test.go') (Join-Path $PSScriptRoot 'snell_v6_regression_test.go')
    if ($LASTEXITCODE) { throw 'Snell local transport regression tests failed' }
    $hostTags = $spec.buildTags
    & $GoExe test @modArgs -count=1 -timeout=45s -tags $hostTags ./transport/wireguard -run TestTunaKeepalive
    if ($LASTEXITCODE) { throw 'WireGuard keepalive range regression tests failed' }
    & $GoExe test @modArgs -count=1 -timeout=45s ./route -run TestTunaNetworkStartup
    if ($LASTEXITCODE) { throw 'Network startup readiness regression tests failed' }
    & $GoExe test @modArgs -count=1 -timeout=45s ./dns/transport/fallback -run TestTuna
    if ($LASTEXITCODE) { throw 'Adaptive DNS regression tests failed' }
    & $GoExe test @modArgs -count=1 -timeout=45s ./dns/transport/local -run TestTunaSystem
    if ($LASTEXITCODE) { throw 'System DNS bootstrap regression tests failed' }
    if ($hostTags -match 'with_naive_outbound') { $hostTags += ',with_purego' }
    & $GoExe test @modArgs -count=1 -timeout=45s -tags $hostTags ./transport/trusttunnel -run TestTuna
    if ($LASTEXITCODE) { throw 'TrustTunnel ClientHello regression tests failed' }
    & $GoExe build @modArgs -buildvcs=false -trimpath -tags $hostTags -ldflags $flags -o (Join-Path $cache 'singbox-extended-check.exe') ./cmd/sing-box
    if ($LASTEXITCODE) { throw 'Host sing-box-extended build failed' }
    if ($spec.buildTags -match 'with_naive_outbound') {
        $hostLibrary = Join-Path $cache 'libcronet.dll'
        if (!(Test-Path -LiteralPath $hostLibrary)) {
            Invoke-WebRequest -Uri $spec.cronet.windowsLibraryUrl -OutFile $hostLibrary -TimeoutSec 120
        }
        if ((Get-FileHash -LiteralPath $hostLibrary -Algorithm SHA256).Hash -ne $spec.cronet.windowsLibrarySha256) {
            throw 'Host Cronet DLL hash differs from native lock'
        }
    }
    $env:GOOS='android'; $env:GOARCH='arm64'; $env:CGO_ENABLED='1'; $env:CC='"'+$compiler+'"'
    $env:CGO_CFLAGS='-target aarch64-linux-android26 -fPIC'
    $env:CGO_LDFLAGS='-target aarch64-linux-android26 -Wl,--no-undefined -Wl,-z,max-page-size=16384'
    $candidate = Join-Path $cache 'libsingbox-extended.so'
    & $GoExe build @modArgs -buildvcs=false -trimpath -tags $spec.buildTags -ldflags $flags -o $candidate ./cmd/sing-box
    if ($LASTEXITCODE) { throw 'Android sing-box-extended build failed' }
    $buildInfo = (& $GoExe version -m $candidate | Out-String)
    if (!$buildInfo.Contains($spec.utls.version)) { throw 'Built binary does not contain pinned uTLS' }
    if ($spec.buildTags -match 'with_naive_outbound' -and
        !$buildInfo.Contains("github.com/sagernet/cronet-go/lib/android_arm64`t$($spec.cronet.libraryModule)")) {
        throw 'Built binary does not contain pinned Android Cronet library'
    }
    Copy-Item -LiteralPath $candidate -Destination (Join-Path $nativeDir 'libsingbox.so') -Force
    $manifest = [ordered]@{
        name='sing-box-extended'; version=$spec.version; commit=$spec.commit; repository=$spec.url
        buildTags=$spec.buildTags; abi='arm64-v8a'; androidApi=26
        sha256=(Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash
        goVersion=(& $GoExe version | Out-String).Trim(); ndkVersion=$NdkVersion
        utls=$spec.utls
        cronet=$spec.cronet
        sourceOverlay=$spec.sourceOverlay
        dependencyOverlay=$spec.dependencyOverlay
        dependencyFiles=@{
            $spec.utls.modFile=(Get-FileHash -LiteralPath $modFile -Algorithm SHA256).Hash
            $spec.utls.sumFile=(Get-FileHash -LiteralPath $sumFile -Algorithm SHA256).Hash
            $spec.sourceOverlay=(Get-FileHash -LiteralPath $overlayInput -Algorithm SHA256).Hash
        }
    }
    foreach ($entry in $dependencyOverlay.Inputs.GetEnumerator()) { $manifest.dependencyFiles[$entry.Key] = $entry.Value }
    foreach ($entry in $sourceAdditionHashes.GetEnumerator()) { $manifest.dependencyFiles[$entry.Key] = $entry.Value }
    [IO.File]::WriteAllText((Join-Path $assetDir 'singbox.json'),($manifest | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    $manifest | ConvertTo-Json
} finally {
    Pop-Location
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
}
