param([string]$GoExe, [string]$PythonExe='python', [string]$Archive)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$expectedCommit='7de2476f1c33e69eec35360c52810dab43c5c986'
$archiveSha=$null
if ($Archive) {
 $archiveSha=(Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLower()
 if($archiveSha -ne '432e2b8b496423da18c80654e3de36fc3646b068d1139b237d6fec540b5162d1') { throw 'Unexpected pinned MasterDNS archive hash' }
 $destination=Join-Path $root ('build/masterdns-source-'+[Guid]::NewGuid().ToString('N'))
 $extract=@'
import pathlib,sys,tarfile
archive,destination,commit=sys.argv[1:]
root=pathlib.Path(destination)
with tarfile.open(archive) as source:
    members=source.getmembers()
    expected='MasterDnsVPN-'+commit+'/'
    if any(not m.name.startswith(expected) and m.name != expected[:-1] for m in members):
        raise ValueError('Unexpected source archive root')
    source.extractall(root,filter='data')
print(root/expected[:-1])
'@
 $source=(& $PythonExe -c $extract $Archive $destination $expectedCommit).Trim()
 if($LASTEXITCODE) { throw 'Pinned MasterDNS archive extraction failed' }
 $commit=$expectedCommit
} else {
 $source=Join-Path $root 'external/masterdnsvpn'
 $commit=(& git -C $source rev-parse HEAD).Trim()
 if ($LASTEXITCODE -or $commit -ne $expectedCommit) { throw 'Unexpected MasterDnsVPN commit' }
}
$target=Join-Path $source 'cmd/tuna'
New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'main.go') -Destination (Join-Path $target 'main.go')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'adapter_test.go') -Destination (Join-Path $target 'main_test.go')
Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'pool*.go' | ForEach-Object {
 Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $target $_.Name)
}
& $PythonExe (Join-Path $PSScriptRoot 'prepare.py') --source $source
if($LASTEXITCODE) { throw 'MasterDNS overlay preparation failed' }
$saved=@{}
foreach($key in @('GOOS','GOARCH','CGO_ENABLED')) { $saved[$key]=[Environment]::GetEnvironmentVariable($key) }
Push-Location $source
try {
 $env:GOOS='windows'; $env:GOARCH='amd64'; $env:CGO_ENABLED='0'
 & $GoExe test -count=1 -timeout=120s -overlay (Join-Path $root 'build/masterdns-overlay/overlay.json') ./internal/config ./internal/client ./cmd/tuna
 if($LASTEXITCODE) { throw 'MasterDNS configuration/client host tests failed' }
 $env:GOOS='android'; $env:GOARCH='arm64'; $env:CGO_ENABLED='0'
 $binary=Join-Path $root 'app/src/main/jniLibs/arm64-v8a/libmasterdns.so'
 & $GoExe build -overlay (Join-Path $root 'build/masterdns-overlay/overlay.json') -buildvcs=false -trimpath -ldflags='-s -w' -o $binary ./cmd/tuna
 if($LASTEXITCODE) { throw 'MasterDnsVPN Android build failed' }
 $poolSources=@{}
 Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'pool*' -File | ForEach-Object { $poolSources[$_.Name]=(Get-FileHash -LiteralPath $_.FullName).Hash }
 $provenance=@{commit=$commit;archiveSha256=$archiveSha;binarySha256=(Get-FileHash $binary).Hash;adapterSha256=(Get-FileHash (Join-Path $PSScriptRoot 'main.go')).Hash;adapterTestsSha256=(Get-FileHash (Join-Path $PSScriptRoot 'adapter_test.go')).Hash;overlayScriptSha256=(Get-FileHash (Join-Path $PSScriptRoot 'prepare.py')).Hash;scanOverlaySha256=(Get-FileHash (Join-Path $PSScriptRoot 'scan.go.inc')).Hash;scanTestsSha256=(Get-FileHash (Join-Path $PSScriptRoot 'scan_test.go')).Hash;diagnosticOverlaySha256=(Get-FileHash (Join-Path $PSScriptRoot 'diagnostic.go')).Hash;poolSourcesSha256=$poolSources;goVersion=(& $GoExe version)} | ConvertTo-Json -Depth 4
 [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'provenance.json'), ($provenance -replace "`r`n","`n")+"`n", [Text.UTF8Encoding]::new($false))
} finally { Pop-Location; foreach($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') } }
