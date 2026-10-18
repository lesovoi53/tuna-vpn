param(
 [Parameter(Mandatory=$true)][string]$GoExe,
 [Parameter(Mandatory=$true)][string]$AndroidSdk
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$GoExe=(Resolve-Path -LiteralPath $GoExe).Path
$cache=Join-Path $root 'build/native-cache'
New-Item -ItemType Directory -Force $cache | Out-Null
$spec=(Get-Content -LiteralPath (Join-Path $root 'native-lock.json') -Raw | ConvertFrom-Json).Mieru
$source=(Resolve-Path -LiteralPath (Join-Path $root $spec.sourceDir)).Path
$sourceTop=(& git -C $source rev-parse --show-toplevel)
if($LASTEXITCODE -or [IO.Path]::GetFullPath($sourceTop) -ne $source) { throw 'Mieru source must be its own pinned checkout, not a parent repository snapshot' }
$commit=(& git -C $source rev-parse HEAD).Trim()
if($LASTEXITCODE -or $commit -ne $spec.commit) { throw 'Unexpected Mieru revision' }
$dirty=& git -C $source status --porcelain
if($LASTEXITCODE -or $dirty) { throw 'Mieru source must be clean; use documented overlays' }
$map=@{}
$map[(Join-Path $source 'pkg/socks5/auth.go')]=Join-Path $PSScriptRoot 'auth.go'
# Add our regression separately: never replace upstream authentication tests.
$map[(Join-Path $source 'pkg/socks5/tuna_auth_test.go')]=Join-Path $PSScriptRoot 'auth_test.go'
# Preserve every upstream case, adapting only its expected EOF to TUNA's
# explicit SOCKS "no acceptable authentication" response for this same denial.
$upstreamTest=[IO.File]::ReadAllText((Join-Path $source 'pkg/socks5/auth_test.go'))
$expectation='wantSelected: 0,'
if([regex]::Matches($upstreamTest,[regex]::Escape($expectation)).Count -ne 1) { throw 'Mieru upstream authentication test anchor changed' }
$upstreamTest=$upstreamTest.Replace($expectation,'wantSelected: constant.Socks5NoAcceptableAuth,')
$upstreamTestPath=Join-Path $cache 'mieru-upstream-auth_test.go'
[IO.File]::WriteAllText($upstreamTestPath,$upstreamTest,[Text.UTF8Encoding]::new($false))
$map[(Join-Path $source 'pkg/socks5/auth_test.go')]=$upstreamTestPath
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
 $versionOutput=& (Join-Path $cache 'mieru-check.exe') --version
 if($LASTEXITCODE -or $versionOutput -ne "Mieru $($spec.version) (TUNA adapter)") { throw 'Mieru runtime version does not match lock' }
 Write-Output $versionOutput
 $compiler=Join-Path $AndroidSdk 'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd'
 if(!(Test-Path -LiteralPath $compiler)) { throw 'Mieru NDK compiler missing' }
 $env:GOOS='android'; $env:GOARCH='arm64'; $env:CGO_ENABLED='1'; $env:CC='"'+$compiler+'"'
 $env:CGO_CFLAGS='-target aarch64-linux-android26 -fPIC'
 $env:CGO_LDFLAGS='-target aarch64-linux-android26 -Wl,--no-undefined -Wl,-z,max-page-size=16384'
 $binary=Join-Path $cache 'libmieru.so'
 & $GoExe build -overlay $overlay -buildvcs=false -trimpath -ldflags='-s -w' -o $binary .
 if($LASTEXITCODE) { throw 'Mieru Android build failed' }
 $record=[ordered]@{commit=$commit;version=$spec.version;tag=$spec.tag;goVersion=(& $GoExe version);ndk='28.2.13676358';sources=[ordered]@{};binarySha256=(Get-FileHash -LiteralPath $binary).Hash.ToLower()}
 $sources=@('external/mieru-adapter','external/mieru','native/mieru') | ForEach-Object {
  Get-ChildItem -LiteralPath (Join-Path $root $_) -Recurse -File | Where-Object { $_.Extension -in '.go','.mod','.sum','.ps1' }
 }
 foreach($file in $sources) { $name=[IO.Path]::GetRelativePath($root,$file.FullName).Replace('\','/'); $record.sources[$name]=(Get-FileHash -LiteralPath $file.FullName).Hash.ToLower() }
 $record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $cache 'mieru-provenance.json') -Encoding utf8
} finally {
 Pop-Location
 foreach($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
}
