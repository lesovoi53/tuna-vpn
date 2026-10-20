param(
    [Parameter(Mandatory=$true)][string]$AndroidSdk
)
$ErrorActionPreference = 'Stop'
$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$ndkVersion = '28.2.13676358'
$toolRoot = Join-Path $AndroidSdk "ndk/$ndkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin"
$compiler = Join-Path $toolRoot 'aarch64-linux-android26-clang.cmd'
$strip = Join-Path $toolRoot 'llvm-strip.exe'
$readelf = Join-Path $toolRoot 'llvm-readelf.exe'
foreach ($tool in @($compiler,$strip,$readelf)) {
    if (!(Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Missing NDK tool: $tool" }
}
$output = Join-Path $androidRoot 'build/native-cache/runtime-launcher'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$binary = Join-Path $output 'libtuna-runtime.so'
& $compiler -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie -fstack-protector-strong -D_FORTIFY_SOURCE=2 '-Wl,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384' '-Wl,--build-id=sha1' -o $binary (Join-Path $PSScriptRoot 'launcher.c')
if ($LASTEXITCODE) { throw 'Android memory launcher compilation failed' }
& $strip --strip-all $binary
if ($LASTEXITCODE) { throw 'Android memory launcher strip failed' }
$jniBinary = Join-Path $output 'libtuna-memory.so'
& $compiler -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared -fstack-protector-strong -D_FORTIFY_SOURCE=2 '-Wl,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384' '-Wl,--build-id=sha1' -o $jniBinary (Join-Path $PSScriptRoot 'memory_jni.c')
if ($LASTEXITCODE) { throw 'Android memory JNI compilation failed' }
& $strip --strip-all $jniBinary
if ($LASTEXITCODE) { throw 'Android memory JNI strip failed' }
$headers = & $readelf -h -l $binary | Out-String
if ($LASTEXITCODE -or $headers -notmatch 'AArch64' -or $headers -notmatch 'DYN' -or $headers -notmatch '/system/bin/linker64') {
    throw 'Android memory launcher is not the expected arm64 PIE executable'
}
$packaged = Join-Path $androidRoot 'app/src/main/jniLibs/arm64-v8a/libtuna-runtime.so'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $packaged) | Out-Null
Copy-Item -LiteralPath $binary -Destination $packaged -Force
$jniPackaged = Join-Path (Split-Path -Parent $packaged) 'libtuna-memory.so'
Copy-Item -LiteralPath $jniBinary -Destination $jniPackaged -Force
$record = [ordered]@{
    component = 'TUNA memory runtime launcher'
    protocol = 'TUNAMEM1'
    ndk = $ndkVersion
    api = 26
    abi = 'arm64-v8a'
    compiler = ((& $compiler --version | Select-Object -First 1) -join '')
    sources = [ordered]@{}
    binarySha256 = (Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLower()
    jniBinarySha256 = (Get-FileHash -LiteralPath $jniBinary -Algorithm SHA256).Hash.ToLower()
}
if ($LASTEXITCODE) { throw 'Compiler version inspection failed' }
foreach ($source in (Get-ChildItem -LiteralPath $PSScriptRoot -Recurse -File | Where-Object { $_.Extension -in '.c','.h','.py','.ps1','.java' } | Sort-Object FullName)) {
    $name = [IO.Path]::GetRelativePath($androidRoot,$source.FullName).Replace('\','/')
    $record.sources[$name] = (Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash.ToLower()
}
if ((Get-FileHash -LiteralPath $packaged -Algorithm SHA256).Hash.ToLower() -ne $record.binarySha256) { throw 'Packaged launcher hash mismatch' }
if ((Get-FileHash -LiteralPath $jniPackaged -Algorithm SHA256).Hash.ToLower() -ne $record.jniBinarySha256) { throw 'Packaged memory JNI hash mismatch' }
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'provenance.json'),($record | ConvertTo-Json -Depth 5).Replace("`r`n","`n")+"`n",[Text.UTF8Encoding]::new($false))
$record | ConvertTo-Json -Depth 5
