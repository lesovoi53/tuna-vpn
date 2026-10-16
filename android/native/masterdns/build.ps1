param([string]$GoExe, [string]$PythonExe='python')
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$source=Join-Path $root 'external/masterdnsvpn'
$commit=(& git -C $source rev-parse HEAD).Trim()
if ($commit -ne '7de2476f1c33e69eec35360c52810dab43c5c986') { throw 'Unexpected MasterDnsVPN commit' }
$target=Join-Path $source 'cmd/tuna'
New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'main.go') -Destination (Join-Path $target 'main.go')
& $PythonExe (Join-Path $PSScriptRoot 'prepare.py')
if($LASTEXITCODE) { throw 'MasterDNS overlay preparation failed' }
$saved=@{}
foreach($key in @('GOOS','GOARCH','CGO_ENABLED')) { $saved[$key]=[Environment]::GetEnvironmentVariable($key) }
Push-Location $source
try {
 $env:GOOS='android'; $env:GOARCH='arm64'; $env:CGO_ENABLED='0'
 $binary=Join-Path $root 'app/src/main/jniLibs/arm64-v8a/libmasterdns.so'
 & $GoExe build -overlay (Join-Path $root 'build/masterdns-overlay/overlay.json') -buildvcs=false -trimpath -ldflags='-s -w' -o $binary ./cmd/tuna
 if($LASTEXITCODE) { throw 'MasterDnsVPN Android build failed' }
 @{commit=$commit;binarySha256=(Get-FileHash $binary).Hash;adapterSha256=(Get-FileHash (Join-Path $PSScriptRoot 'main.go')).Hash;overlayScriptSha256=(Get-FileHash (Join-Path $PSScriptRoot 'prepare.py')).Hash} | ConvertTo-Json | Set-Content (Join-Path $PSScriptRoot 'provenance.json')
} finally { Pop-Location; foreach($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') } }
