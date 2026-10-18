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
if ($LASTEXITCODE) { throw 'Overlay preparation failed' }
$overlay = Join-Path $root 'build/routing-overlay/overlay.json'
$modfile = Join-Path $root 'build/routing-overlay/native.mod'
$saved = @{}
foreach ($name in @('GOOS','GOARCH','CGO_ENABLED','CC','CGO_CFLAGS','CGO_LDFLAGS')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Push-Location (Join-Path $root 'external/vless-client')
try {
    $env:GOOS='windows'; $env:GOARCH='amd64'; $env:CGO_ENABLED='0'
    & $GoExe test -v -modfile $modfile -overlay $overlay . *> (Join-Path $root 'build/stage-b2-native-test.log')
    if ($LASTEXITCODE) { throw 'Native tests failed' }
    & $GoExe test -modfile $modfile -overlay $overlay github.com/xtls/xray-core/app/dns -run 'TestTuna(Adaptive|DoH|Legacy|Emergency|System)' -count=1
    if ($LASTEXITCODE) { throw 'Adaptive DNS regression failed' }
    & $GoExe test -modfile $modfile -overlay $overlay github.com/xtls/xray-core/transport/internet/splithttp -run TestTunaXHTTPExplicitHTTP1ALPN -count=1
    if ($LASTEXITCODE) { throw 'XHTTP ALPN regression failed' }
    & $GoExe build -modfile $modfile -overlay $overlay -buildvcs=false -trimpath -ldflags='-s -w -checklinkname=0' -o (Join-Path $output 'xray-network-check.exe') .
    if ($LASTEXITCODE) { throw 'Host Xray build failed' }
    $compiler = Join-Path $AndroidSdk 'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd'
    if (!(Test-Path -LiteralPath $compiler)) { throw 'NDK compiler not found' }
    $env:GOOS='android'; $env:GOARCH='arm64'; $env:CGO_ENABLED='1'
    $env:CC='"'+$compiler+'"'
    $env:CGO_CFLAGS='-target aarch64-linux-android26 -fPIC'
    $env:CGO_LDFLAGS='-target aarch64-linux-android26 -Wl,--no-undefined -Wl,-z,max-page-size=16384'
    & $GoExe build -modfile $modfile -overlay $overlay -buildvcs=false -trimpath -ldflags='-s -w -checklinkname=0' -o (Join-Path $output 'libxray-network.so') .
    if ($LASTEXITCODE) { throw 'Android Xray build failed' }
    $provenance = [ordered]@{ goVersion = (& $GoExe version); sourceOverlay = 'xhttp-explicit-http1-alpn-v1-adaptive-dns-v2-system-emergency'; sources = [ordered]@{}; binaries = [ordered]@{} }
    foreach ($name in @('native/routing/build.ps1','native/routing/bootstrap.go','native/routing/bootstrap_test.go','native/routing/policy.go','native/routing/policy_test.go','native/routing/socks_resolution_test.go','native/routing/prepare.py','native/routing/adaptive_dns.go','native/routing/adaptive_dns_test.go','native/routing/adaptive_policy_test.go','native/routing/emergency_policy_test.go','native/routing/emergency_dns_test.go','native/routing/system_dns.go','native/routing/system_dns_test.go','native/routing/doh_bootstrap.go','native/routing/doh_bootstrap_test.go','native/routing/xhttp_alpn_test.go','external/vless-client/main.go','external/vless-client/raw_xray.go','external/vless-client/raw_xray_test.go','external/vless-client/outbound_patch.go','external/vless-client/go.mod','external/vless-client/go.sum')) {
        $provenance.sources[$name] = (Get-FileHash -LiteralPath (Join-Path $root $name) -Algorithm SHA256).Hash.ToLower()
    }
    foreach ($name in @('xray-network-check.exe','libxray-network.so')) {
        $provenance.binaries[$name] = (Get-FileHash -LiteralPath (Join-Path $output $name) -Algorithm SHA256).Hash.ToLower()
    }
    $provenance | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'xray-network-provenance.json') -Encoding utf8
} finally {
    Pop-Location
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
