param([string]$JavaHome = $env:JAVA_HOME, [string]$AndroidSdk = $env:ANDROID_HOME,
      [switch]$ReleaseGate, [switch]$PrivateRelease, [string]$PythonExe, [string]$GoExe)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) { $JavaHome = Join-Path (Split-Path $repo -Parent) '.tools/jdk-21.0.12.1+1' }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk) { throw 'Set -AndroidSdk or ANDROID_HOME to the installed Android SDK' }
if (-not (Test-Path "$JavaHome/bin/java.exe")) { throw 'Set -JavaHome to JDK 21' }
if (-not (Test-Path $AndroidSdk)) { throw 'Set -AndroidSdk to the installed Android SDK' }
$env:JAVA_HOME=$JavaHome
$env:ANDROID_HOME=$AndroidSdk
$env:ANDROID_SDK_ROOT=$AndroidSdk
$env:GRADLE_USER_HOME=Join-Path $env:USERPROFILE '.gradle'
Remove-Item Env:TUNA_LIVE_SUBSCRIPTION_FILE -ErrorAction SilentlyContinue
Remove-Item Env:TUNA_TEST_PROFILES_FILE -ErrorAction SilentlyContinue
Push-Location $repo
try {
    New-Item -ItemType Directory -Path build -Force | Out-Null
    if ($PrivateRelease -and -not $ReleaseGate) { throw 'PrivateRelease requires ReleaseGate' }
    if ($ReleaseGate -and (-not $PythonExe -or -not (Test-Path -LiteralPath $PythonExe))) {
        throw 'ReleaseGate requires -PythonExe pointing to a Python 3 executable'
    }
    if ($ReleaseGate) {
        $releaseNotice = [IO.File]::ReadAllText((Join-Path $repo 'NOTICE.md')).Replace("`r`n", "`n")
        $packagedNotice = [IO.File]::ReadAllText((Join-Path $repo 'app/src/main/assets/licenses/NOTICE.txt')).Replace("`r`n", "`n")
        if ($releaseNotice -cne $packagedNotice) { throw 'NOTICE.md and packaged NOTICE.txt differ; synchronize attribution before release' }
        if (-not $GoExe) { $GoExe = Join-Path (Split-Path $repo -Parent) '.tools/go/bin/go.exe' }
        if (-not (Test-Path -LiteralPath $GoExe)) { throw 'ReleaseGate requires -GoExe pointing to Go' }
        if (-not (Test-Path build/routing-overlay/native.mod)) {
            throw 'Build the pinned Xray with native/routing/build.ps1 before running ReleaseGate'
        }
    }
    $gradleTasks = @(':app:cleanTestDebugUnitTest', ':app:testDebugUnitTest', ':app:lintDebug')
    if ($ReleaseGate) { $gradleTasks += @(':app:assembleRelease', ':app:lintRelease') }
    if ($PrivateRelease) { $gradleTasks += '-PprivateRelease=true' }
    & ./gradlew.bat @gradleTasks '-I' tools/synthetic-tests.init.gradle '-PtargetAbis=arm64-v8a' '-PprebuiltNative=true' --offline --no-daemon --console=plain *> build/synthetic-suite.log
    $result = $LASTEXITCODE
    $rows = Get-ChildItem app/build/test-results/testDebugUnitTest/TEST-*.xml | ForEach-Object {
        [xml]$xml = Get-Content -LiteralPath $_.FullName -Raw
        [pscustomobject]@{ Class=$xml.testsuite.name; Tests=[int]$xml.testsuite.tests; Failures=[int]$xml.testsuite.failures; Errors=[int]$xml.testsuite.errors; Skipped=[int]$xml.testsuite.skipped }
    }
    $rows | ConvertTo-Json | Set-Content build/synthetic-suite-results.json -Encoding utf8
    $rows | Measure-Object -Property Tests,Failures,Errors,Skipped -Sum | Format-Table Property,Sum
    Get-Content build/synthetic-suite.log -Tail 12
    if ($result -ne 0) { throw "Synthetic suite failed ($result); see build/synthetic-suite.log" }
    if ($ReleaseGate) {
        & $PythonExe tools/check_r8_models.py
        if ($LASTEXITCODE -ne 0) { throw 'Release reflection contract failed; do not distribute the APK' }
        & $PythonExe tools/check_native_contract.py --go $GoExe
        if ($LASTEXITCODE -ne 0) { throw 'Pinned Xray routing contract failed; do not distribute the APK' }
    }
} finally { Pop-Location }
