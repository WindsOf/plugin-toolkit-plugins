@echo off
echo ==========================================================
echo    Standalone VapourSynth Environment Initialization
echo ==========================================================

set VS_DIR=%~dp0vapoursynth-portable
set PYTHON_EXE="%VS_DIR%\python.exe"
set CORE_PLUGINS_DIR="%VS_DIR%\vs-coreplugins"
set DLL_FOLDER="%CORE_PLUGINS_DIR%\models"

if not exist %VS_DIR% (
    echo.
    echo [1/4] Starting VapourSynth and Python installation...
    PowerShell -NoProfile -ExecutionPolicy Bypass -File ".\Install-Portable-VapourSynth-R73.ps1" -Unattended
)

if not exist %PYTHON_EXE% (
    echo ERROR: python.exe not found. VapourSynth installation failed.
    exit /b 1
)

echo.
echo [2/4] Installing Python libraries...
%PYTHON_EXE% -m pip install --upgrade --quiet pip setuptools wheel
%PYTHON_EXE% -m pip install --quiet -r requirements.txt

if not exist %DLL_FOLDER% (
    echo.
    echo [3/4] Downloading necessary DLLs...
    set DLL_ZIP_URL="https://www.windsofresub.cloud/BetterIMG/vs-coreplugins.zip"

    curl --ssl-no-revoke -L %DLL_ZIP_URL% -o "temp_dlls.zip" -#

    echo.
    echo Extracting...
    if not exist %CORE_PLUGINS_DIR% mkdir %CORE_PLUGINS_DIR%
    tar -xf "temp_dlls.zip" -C %CORE_PLUGINS_DIR%

    del "temp_dlls.zip"
)

echo.
echo [4/4] Setup completed successfully!
exit /b 0