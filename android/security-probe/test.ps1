param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$PreferencesProtoJar,
    [Parameter(Mandatory=$true)][string]$PreferencesProtobufJar
)
$ErrorActionPreference='Stop'
$JavaHome=(Resolve-Path -LiteralPath $JavaHome).Path
$stage=Join-Path $PSScriptRoot ('build/t-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory($PreferencesProtoJar,(Join-Path $stage 'proto'))
[IO.Compression.ZipFile]::ExtractToDirectory($PreferencesProtobufJar,(Join-Path $stage 'protobuf'))
Push-Location $stage
try {
    & (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp 'proto;protobuf' -d . (Join-Path $PSScriptRoot 'continuity/PreferencesSnapshot.java') (Join-Path $PSScriptRoot 'tests/PreferencesSnapshotTest.java')
    if($LASTEXITCODE){throw 'Continuity parser test compilation failed'}
    & (Join-Path $JavaHome 'bin/java.exe') -cp '.;proto;protobuf' app.tuna.securityprobe.PreferencesSnapshotTest
    if($LASTEXITCODE){throw 'Continuity parser regression failed'}
} finally { Pop-Location }
