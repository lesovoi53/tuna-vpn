param([string]$PythonExe = (Join-Path $PSScriptRoot '.build-env/Scripts/python.exe'))
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$builderPython = $PythonExe
if (-not (Test-Path -LiteralPath $builderPython)) { throw 'Create .build-env and install requirements.txt plus pyinstaller==6.16.0 first.' }
# Embedded/relocatable Python distributions can contain Tcl/Tk without exposing
# its script directories to tkinter. Use only this interpreter's own runtime.
$builderBase = & $builderPython -c 'import sys; print(sys.base_prefix)'
if ($LASTEXITCODE -ne 0) { throw 'Python runtime discovery failed' }
foreach ($runtime in @(@('TCL_LIBRARY', 'tcl/tcl8.6'), @('TK_LIBRARY', 'tcl/tk8.6'))) {
    $candidate = Join-Path $builderBase $runtime[1]
    if (-not [Environment]::GetEnvironmentVariable($runtime[0]) -and (Test-Path -LiteralPath $candidate)) {
        [Environment]::SetEnvironmentVariable($runtime[0], $candidate, 'Process')
    }
}
& $builderPython -c 'import tkinter; t = tkinter.Tk(); t.withdraw(); t.destroy()'
if ($LASTEXITCODE -ne 0) { throw 'Tcl/Tk runtime is unavailable; EXE build stopped' }
& $builderPython -m PyInstaller --noconfirm --onefile --windowed --name TunaRoutingBuilder-v2.4 --collect-all customtkinter --collect-all darkdetect --add-data 'licenses;licenses' app.py
if ($LASTEXITCODE -ne 0) { throw 'EXE build failed' }
