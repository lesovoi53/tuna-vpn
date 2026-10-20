param(
 [Parameter(Mandatory=$true)][string]$GoExe,
 [Parameter(Mandatory=$true)][string]$PythonExe,
 [Parameter(Mandatory=$true)][string]$Archive,
 [Parameter(Mandatory=$true)][string]$AndroidSdk
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$GoExe=(Resolve-Path -LiteralPath $GoExe).Path
$PythonExe=(Resolve-Path -LiteralPath $PythonExe).Path
$Archive=(Resolve-Path -LiteralPath $Archive).Path
& $PythonExe (Join-Path $PSScriptRoot 'prepare.py') $Archive
if($LASTEXITCODE) { throw 'qWDTT preparation failed' }
$cache=Join-Path $root 'build/native-cache'
New-Item -ItemType Directory -Force $cache | Out-Null
$overlay=Join-Path $root 'build/qwdtt-overlay/overlay.json'
$saved=@{}
foreach($name in @('GOOS','GOARCH','CGO_ENABLED','CC','CGO_CFLAGS','CGO_LDFLAGS')) { $saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
Push-Location (Join-Path $root 'build/qwdtt-overlay/source')
try {
 $env:GOOS='windows'; $env:GOARCH='amd64'; $env:CGO_ENABLED='0'
 # Upstream has no tests and its Unix TUN source cannot compile for Windows.
 # Exercise the actual overlay parser, then compile the entire Android client.
 & $GoExe test -count=1 -timeout=120s (Join-Path $PSScriptRoot 'config.go') (Join-Path $PSScriptRoot 'config_test.go')
 if($LASTEXITCODE) { throw 'qWDTT regression failed' }
 $compiler=Join-Path $AndroidSdk 'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd'
 if(!(Test-Path -LiteralPath $compiler)) { throw 'qWDTT NDK compiler missing' }
 $env:GOOS='android'; $env:GOARCH='arm64'; $env:CGO_ENABLED='1'; $env:CC='"'+$compiler+'"'
 $env:CGO_CFLAGS='-target aarch64-linux-android26 -fPIC'
 $env:CGO_LDFLAGS='-target aarch64-linux-android26 -Wl,--no-undefined -Wl,-z,max-page-size=16384'
 $binary=Join-Path $cache 'libqwdtt.so'
 & $GoExe build -overlay $overlay -buildvcs=false -trimpath -ldflags='-s -w -checklinkname=0' -o $binary .
 if($LASTEXITCODE) { throw 'qWDTT Android build failed' }
 $record=[ordered]@{commit='a296c57eaba69bb9479a24f9157856490890e47d';archiveSha256=(Get-FileHash $Archive).Hash.ToLower();goVersion=(& $GoExe version);ndk='28.2.13676358';sources=[ordered]@{};binarySha256=(Get-FileHash $binary).Hash.ToLower()}
 foreach($file in Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object { $_.Extension -in '.go','.ps1','.py','.mod','.sum' }) { $record.sources['native/qwdtt/'+$file.Name]=(Get-FileHash $file.FullName).Hash.ToLower() }
 $record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $cache 'qwdtt-provenance.json') -Encoding utf8
} finally { Pop-Location; foreach($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') } }
