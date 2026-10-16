$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$builderPython = Join-Path $PSScriptRoot '.build-env/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $builderPython)) { throw 'Create .build-env and install requirements.txt plus pyinstaller==6.16.0 first.' }
& $builderPython -m PyInstaller --noconfirm --onefile --windowed --name TunaRoutingBuilder-v2.3.1 --collect-all customtkinter --collect-all darkdetect --add-data 'licenses;licenses' app.py
if ($LASTEXITCODE -ne 0) { throw 'EXE build failed' }
