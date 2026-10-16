param(
 [Parameter(Mandatory=$true)][string]$GoExe,
 [Parameter(Mandatory=$true)][string]$AndroidSdk
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$GoExe=(Resolve-Path -LiteralPath $GoExe).Path
$cache=Join-Path $root 'build/native-cache'
New-Item -ItemType Directory -Force $cache | Out-Null
$commit=(& git -C (Join-Path $root 'external/mieru') rev-parse HEAD).Trim()
if($LASTEXITCODE -or $commit -ne '162c240b5366139a83dbd338e89907e8575ce0d8') { throw 'Unexpected Mieru revision' }
$map=@{}
foreach($name in @('auth.go','auth_test.go')) {
 $map[(Join-Path $root "external/mieru/pkg/socks5/$name")]=Join-Path $PSScriptRoot $name
}
$overlay=Join-Path $cache 'mieru-overlay.json'
[IO.File]::WriteAllText($overlay,(@{Replace=$map}|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
$saved=@{}
foreach($name in @('GOOS','GOARCH','CGO_ENABLED','CC','CGO_CFLAGS','CGO_LDFLAGS')) { $saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
Push-Location (Join-Path $root 'external/mieru-adapter')
try {
 $env:GOOS='windows'; $env:GOARCH='amd64'; $env:CGO_ENABLED='0'
 & $GoExe test -count=1 -timeout=120s -overlay $overlay . github.com/enfein/mieru/v3/pkg/socks5
 if($LASTEXITCODE) { throw 'Mieru adapter/auth regression failed' }
 & $GoExe build -overlay $overlay -buildvcs=false -trimpath -ldflags='-s -w' -o (Join-Path $cache 'mieru-check.exe') .
 if($LASTEXITCODE) { throw 'Mieru host build failed' }
 $compiler=Join-Path $AndroidSdk 'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd'
 if(!(Test-Path -LiteralPath $compiler)) { throw 'Mieru NDK compiler missing' }
 $env:GOOS='android'; $env:GOARCH='arm64'; $env:CGO_ENABLED='1'; $env:CC='"'+$compiler+'"'
 $env:CGO_CFLAGS='-target aarch64-linux-android26 -fPIC'
 $env:CGO_LDFLAGS='-target aarch64-linux-android26 -Wl,--no-undefined -Wl,-z,max-page-size=16384'
 $binary=Join-Path $cache 'libmieru.so'
 & $GoExe build -overlay $overlay -buildvcs=false -trimpath -ldflags='-s -w' -o $binary .
 if($LASTEXITCODE) { throw 'Mieru Android build failed' }
 $record=[ordered]@{commit=$commit;goVersion=(& $GoExe version);ndk='28.2.13676358';sources=[ordered]@{};binarySha256=(Get-FileHash -LiteralPath $binary).Hash.ToLower()}
 $sources=@('external/mieru-adapter','external/mieru','native/mieru') | ForEach-Object {
  Get-ChildItem -LiteralPath (Join-Path $root $_) -Recurse -File | Where-Object { $_.Extension -in '.go','.mod','.sum','.ps1' }
 }
 foreach($file in $sources) { $name=[IO.Path]::GetRelativePath($root,$file.FullName).Replace('\','/'); $record.sources[$name]=(Get-FileHash -LiteralPath $file.FullName).Hash.ToLower() }
 $record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $cache 'mieru-provenance.json') -Encoding utf8
} finally {
 Pop-Location
 foreach($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
}
