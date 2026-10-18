param(
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$BuildToolsVersion = '37.0.0',
    [string]$Platform = 'android-37.0',
    [string]$SigningRoot,
    [switch]$ContinuityOnly,
    [switch]$AcceptanceOnly,
    [switch]$ProbeOnly
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$publicRoot = [IO.Path]::GetFullPath((Join-Path $root '../..'))
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$jar = Join-Path $JavaHome 'bin/jar.exe'
$keytool = Join-Path $JavaHome 'bin/keytool.exe'
$sdkTools = Join-Path $AndroidSdk ('build-tools/' + $BuildToolsVersion)
$androidJar = Join-Path $AndroidSdk ('platforms/' + $Platform + '/android.jar')
foreach ($file in @($java,$javac,$jar,$keytool,$androidJar,(Join-Path $sdkTools 'aapt2.exe'),(Join-Path $sdkTools 'zipalign.exe'),(Join-Path $sdkTools 'lib/d8.jar'),(Join-Path $sdkTools 'lib/apksigner.jar'))) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing build input: $file" }
}
$output = Join-Path $root 'build'
$work = Join-Path $output ('work-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $output,$work | Out-Null

function Build-UnsignedApk([string]$kind, [string]$name) {
    $stage = Join-Path $work $kind
    $classes = Join-Path $stage 'classes'
    $dex = Join-Path $stage 'dex'
    New-Item -ItemType Directory -Force -Path $classes,$dex | Out-Null
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $root $kind) -Filter '*.java' -File | ForEach-Object { $_.FullName })
    if (!$sources) { throw "No Java sources for $kind" }
    & $javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar -d $classes @sources
    if ($LASTEXITCODE) { throw "$kind Java compilation failed" }
    $classJar = Join-Path $stage 'classes.jar'
    & $jar --create --file $classJar -C $classes .
    if ($LASTEXITCODE) { throw "$kind class archive failed" }
    & $java -cp (Join-Path $sdkTools 'lib/d8.jar') com.android.tools.r8.D8 --release --min-api 26 --lib $androidJar --output $dex $classJar
    if ($LASTEXITCODE) { throw "$kind DEX compilation failed" }
    $unsigned = Join-Path $stage 'unsigned.apk'
    & (Join-Path $sdkTools 'aapt2.exe') link -I $androidJar --manifest (Join-Path $root ($kind+'/AndroidManifest.xml')) --min-sdk-version 26 --target-sdk-version 37 --version-code 1 --version-name 1.0 -o $unsigned
    if ($LASTEXITCODE) { throw "$kind APK link failed" }
    & $jar --update --no-manifest --file $unsigned -C $dex classes.dex
    if ($LASTEXITCODE) { throw "$kind DEX packaging failed" }
    $aligned = Join-Path $output $name
    & (Join-Path $sdkTools 'zipalign.exe') -f 4 $unsigned $aligned
    if ($LASTEXITCODE) { throw "$kind ZIP alignment failed" }
    & (Join-Path $sdkTools 'zipalign.exe') -c 4 $aligned
    if ($LASTEXITCODE) { throw "$kind ZIP alignment verification failed" }
    return $aligned
}

if ($AcceptanceOnly) {
    $acceptance = Build-UnsignedApk 'acceptance' 'SecurityAcceptance-unsigned.apk'
    [ordered]@{ acceptanceUnsignedSha256=(Get-FileHash -LiteralPath $acceptance -Algorithm SHA256).Hash.ToLower() } | ConvertTo-Json
    return
}
$manifest = [ordered]@{
    buildTools=$BuildToolsVersion; platform=$Platform; minSdk=26; targetSdk=37
}
if (!$ProbeOnly) {
    $continuity = Build-UnsignedApk 'continuity' 'SecurityContinuity-unsigned.apk'
    $manifest.continuityUnsignedSha256=(Get-FileHash -LiteralPath $continuity -Algorithm SHA256).Hash.ToLower()
} elseif (Test-Path -LiteralPath (Join-Path $output 'SecurityContinuity-unsigned.apk')) {
    $manifest.continuityUnsignedSha256=(Get-FileHash -LiteralPath (Join-Path $output 'SecurityContinuity-unsigned.apk') -Algorithm SHA256).Hash.ToLower()
}
if (!$ContinuityOnly) {
    if (!$SigningRoot) { $SigningRoot = Join-Path (Split-Path -Parent $publicRoot) '.scratch/tuna-security-probe-signing' }
    $signingPath = [IO.Path]::GetFullPath($SigningRoot)
    if ($signingPath.Equals($publicRoot,[StringComparison]::OrdinalIgnoreCase) -or $signingPath.StartsWith($publicRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Ephemeral probe signing material must remain outside the public source tree'
    }
    $signingPath = Join-Path $signingPath 'reusable-ephemeral'
    New-Item -ItemType Directory -Force -Path $signingPath | Out-Null
    $keystore = Join-Path $signingPath 'probe-ephemeral.p12'
    $protectedPassword = Join-Path $signingPath 'probe-password.dpapi'
    $unsignedProbe = Build-UnsignedApk 'probe' 'SecurityProbe-unsigned.apk'
    $signedProbe = Join-Path $output 'SecurityProbe.apk'
    $savedPassword = [Environment]::GetEnvironmentVariable('TUNA_PROBE_EPHEMERAL_PASSWORD','Process')
    $random = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    try {
        if (Test-Path -LiteralPath $keystore) {
            if (!(Test-Path -LiteralPath $protectedPassword)) { throw 'Existing ephemeral signer has no DPAPI password; refusing to replace it' }
            $secret = ConvertTo-SecureString ([IO.File]::ReadAllText($protectedPassword))
            $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
            try { $env:TUNA_PROBE_EPHEMERAL_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secret.Dispose() }
        } else {
            if (Test-Path -LiteralPath $protectedPassword) { throw 'Ephemeral signing state is incomplete; refusing to replace it' }
            $env:TUNA_PROBE_EPHEMERAL_PASSWORD = [Convert]::ToBase64String($random)
            & $keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias probe -keyalg EC -groupname secp256r1 -validity 30 -dname 'CN=TUNA Security Probe Ephemeral' -storepass:env TUNA_PROBE_EPHEMERAL_PASSWORD -keypass:env TUNA_PROBE_EPHEMERAL_PASSWORD -noprompt
            if ($LASTEXITCODE) { throw 'Ephemeral test signer generation failed' }
            $secret = ConvertTo-SecureString $env:TUNA_PROBE_EPHEMERAL_PASSWORD -AsPlainText -Force
            try { [IO.File]::WriteAllText($protectedPassword,(ConvertFrom-SecureString $secret),[Text.UTF8Encoding]::new($false)) }
            finally { $secret.Dispose() }
        }
        & $java -jar (Join-Path $sdkTools 'lib/apksigner.jar') sign --ks $keystore --ks-key-alias probe --ks-pass env:TUNA_PROBE_EPHEMERAL_PASSWORD --key-pass env:TUNA_PROBE_EPHEMERAL_PASSWORD --min-sdk-version 26 --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $signedProbe $unsignedProbe
        if ($LASTEXITCODE) { throw 'Probe signing failed' }
    } finally {
        [Array]::Clear($random,0,$random.Length)
        [Environment]::SetEnvironmentVariable('TUNA_PROBE_EPHEMERAL_PASSWORD',$savedPassword,'Process')
    }
    $verification = (& $java -jar (Join-Path $sdkTools 'lib/apksigner.jar') verify --verbose --print-certs $signedProbe | Out-String)
    if ($LASTEXITCODE) { throw 'Probe APK signature verification failed' }
    $certificate = [regex]::Match($verification,'certificate SHA-256 digest: ([0-9a-fA-F]{64})').Groups[1].Value.ToLower()
    if (!$certificate -or $certificate -eq 'c3c08cdde6a991b036a5b14f07680116355ac8b8e0eadf44144d82e7c514131b') { throw 'Probe signer must differ from original TUNA signer' }
    [IO.File]::WriteAllText((Join-Path $output 'Probe-signature.txt'),$verification,[Text.UTF8Encoding]::new($false))
    $manifest.probeSha256=(Get-FileHash -LiteralPath $signedProbe -Algorithm SHA256).Hash.ToLower()
    $manifest.probeSignerSha256=$certificate
}
[IO.File]::WriteAllText((Join-Path $output 'artifacts.json'),($manifest | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$manifest | ConvertTo-Json
