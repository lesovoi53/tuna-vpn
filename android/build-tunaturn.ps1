param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$GoExe,
    [string]$OriginalApk,
    [string]$QwdttArchive,
    [string]$PythonExe = 'python',
    [string]$NdkVersion = '28.2.13676358'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$PythonExe = (Get-Command $PythonExe -ErrorAction Stop).Source
if ($NdkVersion -ne '28.2.13676358') { throw 'This build pipeline pins NDK 28.2.13676358; do not mix native toolchains.' }
$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME','ANDROID_HOME','PATH','CGO_ENABLED','GOOS','GOARCH','CC','CGO_CFLAGS','CGO_LDFLAGS')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Push-Location $root
try {
    $lock = Get-Content (Join-Path $root 'native-lock.json') -Raw | ConvertFrom-Json
    $cache = Join-Path $root 'build/native-cache'
    New-Item -ItemType Directory -Path $cache -Force | Out-Null
    function Resolve-Apk($path, $spec, $name) {
        if (!$path) {
            $path = Join-Path $cache $name
            if (!(Test-Path -LiteralPath $path)) {
                Invoke-WebRequest -UseBasicParsing -Uri $spec.url -OutFile $path
            }
        }
        $path = (Resolve-Path -LiteralPath $path).Path
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $spec.sha256) {
            throw "SHA-256 mismatch: $name. Expected the official release asset in native-lock.json."
        }
        return $path
    }
    $OriginalApk = Resolve-Apk $OriginalApk $lock.WireTurnApk 'WireTurn-v8.3.apk'
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    $env:ANDROID_HOME = (Resolve-Path -LiteralPath $AndroidSdk).Path
    $GoExe = (Resolve-Path -LiteralPath $GoExe).Path
    $env:PATH = "$env:JAVA_HOME\bin;$(Split-Path -Parent $GoExe);$env:PATH"
    $nativeDir = Join-Path $root 'app/src/main/jniLibs/arm64-v8a'
    New-Item -ItemType Directory -Path $nativeDir -Force | Out-Null
    $allowed = @('libhevsocks5.so','libqwdtt.so','libxray.so','libsingbox.so','libmieru.so','libmasterdns.so','libwebdav.so','libtuna-runtime.so','libtuna-memory.so')
    foreach ($existing in Get-ChildItem -LiteralPath $nativeDir -Filter '*.so') {
        if ($existing.Name -notin $allowed) { throw "Unexpected native library: $($existing.Name). Remove it before building." }
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    function Extract-Native($apk, $entries) {
        $archive = [IO.Compression.ZipFile]::OpenRead($apk)
        try {
            foreach ($source in $entries.Keys) {
                $entry = $archive.GetEntry("lib/arm64-v8a/$source")
                if (!$entry) { throw "Missing native library $source" }
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $nativeDir $entries[$source]), $true)
            }
        } finally { $archive.Dispose() }
    }
    Extract-Native $OriginalApk @{'libhevsocks5.so'='libhevsocks5.so'}
    if (!$QwdttArchive) {
        $QwdttArchive=Join-Path $cache 'qwdtt-a296c57eaba69bb9479a24f9157856490890e47d.tar.gz'
        if (!(Test-Path -LiteralPath $QwdttArchive)) {
            Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/SpaceNeuroX/proxy-turn-vk-android/archive/a296c57eaba69bb9479a24f9157856490890e47d.tar.gz' -OutFile $QwdttArchive
        }
    }
    & (Join-Path $root 'native/qwdtt/build.ps1') -GoExe $GoExe -PythonExe $PythonExe -Archive $QwdttArchive -AndroidSdk $env:ANDROID_HOME
    Copy-Item -LiteralPath (Join-Path $cache 'libqwdtt.so') -Destination (Join-Path $nativeDir 'libqwdtt.so') -Force
    Copy-Item -LiteralPath (Join-Path $cache 'qwdtt-provenance.json') -Destination (Join-Path $root 'native/qwdtt/provenance.json') -Force
    & (Join-Path $root 'native/runtime-launcher/build.ps1') -AndroidSdk $env:ANDROID_HOME
    $compiler = Join-Path $env:ANDROID_HOME "ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd"
    if (!(Test-Path -LiteralPath $compiler)) { throw "NDK compiler not found: $compiler" }
    $env:CGO_ENABLED='1'; $env:GOOS='android'; $env:GOARCH='arm64'
    $env:CC='"'+$compiler+'"'
    $env:CGO_CFLAGS='-target aarch64-linux-android26 -fPIC'
    $env:CGO_LDFLAGS='-target aarch64-linux-android26 -Wl,--no-undefined -Wl,-z,max-page-size=16384'
    & (Join-Path $root 'native/routing/build.ps1') -GoExe $GoExe -PythonExe $PythonExe -AndroidSdk $env:ANDROID_HOME
    Copy-Item -LiteralPath (Join-Path $cache 'libxray-network.so') -Destination (Join-Path $nativeDir 'libxray.so') -Force
    Copy-Item -LiteralPath (Join-Path $cache 'xray-network-provenance.json') -Destination (Join-Path $root 'native/routing/provenance-current.json') -Force
    $routingOverlayPath = Join-Path $root 'build/routing-overlay/overlay.json'
    $routingModFile = Join-Path $root 'build/routing-overlay/native.mod'
    & (Join-Path $root 'native/singbox/build.ps1') -GoExe $GoExe -AndroidSdk $env:ANDROID_HOME -NdkVersion $NdkVersion
    & (Join-Path $root 'native/webdav/build.ps1') -GoExe $GoExe -PythonExe $PythonExe -AndroidSdk $env:ANDROID_HOME
    Copy-Item -LiteralPath (Join-Path $root 'build/native-cache/libwebdav.so') -Destination (Join-Path $nativeDir 'libwebdav.so') -Force
    Copy-Item -LiteralPath (Join-Path $root 'build/native-cache/webdav-provenance.json') -Destination (Join-Path $root 'native/webdav/provenance.json') -Force
    & (Join-Path $root 'native/mieru/build.ps1') -GoExe $GoExe -AndroidSdk $env:ANDROID_HOME
    Copy-Item -LiteralPath (Join-Path $cache 'libmieru.so') -Destination (Join-Path $nativeDir 'libmieru.so') -Force
    Copy-Item -LiteralPath (Join-Path $cache 'mieru-provenance.json') -Destination (Join-Path $root 'native/mieru/provenance.json') -Force
    $mieruOverlayPath = Join-Path $cache 'mieru-overlay.json'
    & (Join-Path $root 'native/masterdns/build.ps1') -GoExe $GoExe -PythonExe $PythonExe
    & $PythonExe (Join-Path $root "tools/core_manifest.py")
    if ($LASTEXITCODE) { throw "Core manifest generation failed" }
    & ./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug -PprebuiltNative=true -PtargetAbis=arm64-v8a -I tools/synthetic-tests.init.gradle --console=plain
    if ($LASTEXITCODE) { throw 'Android checks/build failed' }
    foreach ($name in @('CGO_ENABLED','GOOS','GOARCH','CC','CGO_CFLAGS','CGO_LDFLAGS')) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    Push-Location (Join-Path $root 'external/vless-client')
    try {
        & $GoExe test -modfile $routingModFile -overlay $routingOverlayPath -ldflags=-checklinkname=0 -v -timeout 120s .
        if ($LASTEXITCODE) { throw 'Native network tests failed' }
    } finally { Pop-Location }
    Push-Location (Join-Path $root 'external/mieru-adapter')
    try {
        & $GoExe test -overlay $mieruOverlayPath -timeout 120s .
        if ($LASTEXITCODE) { throw 'Mieru extended URI tests failed' }
        & $GoExe test -overlay $mieruOverlayPath -timeout 120s -run TestTunaAuthenticationCannotDowngrade github.com/enfein/mieru/v3/pkg/socks5
        if ($LASTEXITCODE) { throw 'Mieru local authentication tests failed' }
    } finally { Pop-Location }
    $hostCore = Join-Path $cache 'singbox-extended-check.exe'
    foreach ($configFile in Get-ChildItem (Join-Path $root 'app/build/vpn-test-configs') -Filter '*.json') {
        & $hostCore check -c $configFile.FullName
        if ($LASTEXITCODE) { throw "Native configuration check failed: $($configFile.Name)" }
    }
    & $GoExe run (Join-Path $root 'tests/singbox_smoke.go') $hostCore
    if ($LASTEXITCODE) { throw 'Snell local network test failed' }
    foreach ($fixture in @('extended-xhttp-packet-up.json','extended-xhttp-stream-up.json','extended-xhttp-stream-one.json','extended-xhttp-extra.json','extended-encrypted-tcp.json','extended-encrypted-xhttp.json')) {
        & $GoExe run (Join-Path $root 'tests/singbox_smoke.go') $hostCore (Join-Path $root "app/build/vpn-test-configs/$fixture")
        if ($LASTEXITCODE) { throw "Extended local network test failed: $fixture" }
    }
    Get-ChildItem (Join-Path $root 'app/build/outputs/apk/debug') -Filter '*.apk' | Get-FileHash -Algorithm SHA256
} finally {
    Pop-Location
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
