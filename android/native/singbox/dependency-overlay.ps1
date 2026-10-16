param(
    [Parameter(Mandatory=$true)][string]$GoExe,
    [Parameter(Mandatory=$true)][string]$Root,
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$ModFile
)
$ErrorActionPreference = 'Stop'
$specPath = (Get-Content -LiteralPath (Join-Path $Root 'native-lock.json') -Raw | ConvertFrom-Json).SingBoxExtended.dependencyOverlay
$spec = Get-Content -LiteralPath (Join-Path $Root $specPath) -Raw | ConvertFrom-Json
$mapping = @{}
$inputs = @{}
$generatedMod = Join-Path $Root 'build/native-cache/singbox.mod'
Copy-Item -LiteralPath $ModFile -Destination $generatedMod -Force
Copy-Item -LiteralPath ([IO.Path]::ChangeExtension($ModFile, '.sum')) -Destination ([IO.Path]::ChangeExtension($generatedMod, '.sum')) -Force
foreach ($path in @($specPath, 'native/singbox/dependency-overlay.ps1', 'native/singbox/build.ps1', 'native/singbox/snell_roundtrip_test.go', 'native/singbox/snell_v6_regression_test.go')) {
    $inputs[$path] = (Get-FileHash -LiteralPath (Join-Path $Root $path) -Algorithm SHA256).Hash
}
foreach ($module in $spec.PSObject.Properties) {
    $info = (& $GoExe -C $Source list -mod=readonly "-modfile=$ModFile" -m -json $module.Name | ConvertFrom-Json)
    $expectedReplacement = $module.Value.PSObject.Properties['replace']
    $replacementMatches = if ($expectedReplacement) {
        $info.Replace -and $info.Replace.Path -eq $expectedReplacement.Value.path -and $info.Replace.Version -eq $expectedReplacement.Value.version
    } else { !$info.Replace }
    if ($LASTEXITCODE -or $info.Version -ne $module.Value.version -or !$replacementMatches) {
        throw "Dependency overlay revision mismatch: $($module.Name)"
    }
    # Go forbids overlays inside GOMODCACHE. Copy the pinned module into an
    # isolated build directory; never patch the shared module cache.
    $revision = (Get-FileHash -LiteralPath (Join-Path $Root $specPath) -Algorithm SHA256).Hash.Substring(0,16)
    $localModule = Join-Path $Root ("build/native-cache/dependencies/" + $module.Name + '/' + $revision)
    foreach ($sourceFile in Get-ChildItem -LiteralPath $(if ($info.Replace) { $info.Replace.Dir } else { $info.Dir }) -File -Recurse) {
        $relative = [IO.Path]::GetRelativePath($(if ($info.Replace) { $info.Replace.Dir } else { $info.Dir }), $sourceFile.FullName)
        $targetFile = Join-Path $localModule $relative
        New-Item -ItemType Directory -Force -Path (Split-Path $targetFile) | Out-Null
        [IO.File]::WriteAllBytes($targetFile, [IO.File]::ReadAllBytes($sourceFile.FullName))
    }
    & $GoExe -C $Source mod edit "-modfile=$generatedMod" "-replace=$($module.Name)=$($localModule.Replace('\','/'))"
    if ($LASTEXITCODE) { throw 'Unable to record local dependency overlay' }
    foreach ($file in $module.Value.files.PSObject.Properties) {
        $original = Join-Path $localModule $file.Name
        if ((Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash -ne $file.Value.sha256) {
            throw "Dependency overlay source hash mismatch: $($file.Name)"
        }
        $patched = [IO.File]::ReadAllText($original).Replace("`r`n", "`n")
        foreach ($edit in $file.Value.edits) {
            if ([regex]::Matches($patched, [regex]::Escape($edit.old)).Count -ne 1) {
                throw "Dependency overlay anchor mismatch: $($file.Name)"
            }
            $patched = $patched.Replace($edit.old, $edit.new)
        }
        $destination = Join-Path $Root ("build/native-cache/dependency-overlay/" + $module.Name + '/' + $file.Name)
        New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
        [IO.File]::WriteAllText($destination, $patched, [Text.UTF8Encoding]::new($false))
        $mapping[$original] = $destination
    }
    foreach ($file in $module.Value.addFiles.PSObject.Properties) {
        $original = Join-Path $localModule $file.Name
        if (Test-Path -LiteralPath $original) { throw "Dependency overlay cannot replace existing file: $($file.Name)" }
        $mapping[$original] = Join-Path $Root $file.Value
        $inputs[$file.Value] = (Get-FileHash -LiteralPath (Join-Path $Root $file.Value) -Algorithm SHA256).Hash
    }
}
@{ Replace=$mapping; Inputs=$inputs; ModFile=$generatedMod }
