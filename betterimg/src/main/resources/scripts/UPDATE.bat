@echo off
setlocal enabledelayedexpansion
title BetterIMG - Update Check

set "APP_DIR=%~dp0"
set "LOCAL_VERSION_FILE=%APP_DIR%version.json"

set "GITHUB_RAW_BASE=https://raw.githubusercontent.com/WindsOf/plugin-toolkit-plugins/main/betterimg/src/main/resources/scripts"
set "REMOTE_VERSION_URL=%GITHUB_RAW_BASE%/version.json"
set "TEMP_REMOTE_JSON=%TEMP%\betterimg_remote_version.json"

:: ============================================================
:: 1. Read local version
:: ============================================================
if not exist "%LOCAL_VERSION_FILE%" (
    echo [UPDATE] local version.json not found, skipping update.
    goto :EOF
)

for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command ^
    "(Get-Content '%LOCAL_VERSION_FILE%' | ConvertFrom-Json).version"`) do (
    set "LOCAL_SCRIPT_VER=%%V"
)

for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command ^
    "(Get-Content '%LOCAL_VERSION_FILE%' | ConvertFrom-Json).plugin"`) do (
    set "LOCAL_PLUGIN_VER=%%P"
)

if "%LOCAL_SCRIPT_VER%"=="" (
    echo [UPDATE] Unable to read local script version.
    goto :EOF
)

echo [UPDATE] Local script version  : %LOCAL_SCRIPT_VER%
echo [UPDATE] Local plugin version  : %LOCAL_PLUGIN_VER%

:: ============================================================
:: 2. Download version.json from GitHub
:: ============================================================
echo [UPDATE] Fetching remote version from GitHub...
curl --ssl-no-revoke -fsSL "%REMOTE_VERSION_URL%" -o "%TEMP_REMOTE_JSON%" 2>nul

if not exist "%TEMP_REMOTE_JSON%" (
    echo [UPDATE] Unable to reach GitHub. Skipping update.
    goto :EOF
)

for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command ^
    "(Get-Content '%TEMP_REMOTE_JSON%' | ConvertFrom-Json).version"`) do (
    set "REMOTE_SCRIPT_VER=%%V"
)

for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command ^
    "(Get-Content '%TEMP_REMOTE_JSON%' | ConvertFrom-Json).plugin"`) do (
    set "REMOTE_PLUGIN_VER=%%P"
)

if "%REMOTE_SCRIPT_VER%"=="" (
    echo [UPDATE] Unable to read remote script version.
    del "%TEMP_REMOTE_JSON%" 2>nul
    goto :EOF
)

echo [UPDATE] Remote script version  : %REMOTE_SCRIPT_VER%
echo [UPDATE] Remote plugin version  : %REMOTE_PLUGIN_VER%

:: ============================================================
:: 3. Check if SCRIPTS need update (auto-proceed, no user input)
:: ============================================================
for /f "usebackq delims=" %%R in (`powershell -NoProfile -Command ^
    "$l=[version]'%LOCAL_SCRIPT_VER%'; $r=[version]'%REMOTE_SCRIPT_VER%'; if($r -gt $l){'1'}else{'0'}"`) do (
    set "SCRIPT_NEEDS_UPDATE=%%R"
)

if "%SCRIPT_NEEDS_UPDATE%"=="1" (
    echo.
    echo ==========================================================
    echo   SCRIPT Update: v%LOCAL_SCRIPT_VER% -^> v%REMOTE_SCRIPT_VER%
    echo ==========================================================
    call :UPDATE_SCRIPTS
) else (
    echo [UPDATE] Scripts are up to date. No update needed.
)

:: ============================================================
:: 4. Check if PLUGINS (VapourSynth DLLs) need update (auto-proceed, no user input)
:: ============================================================
if "%REMOTE_PLUGIN_VER%"=="" goto :CLEANUP

for /f "usebackq delims=" %%R in (`powershell -NoProfile -Command ^
    "$l=[version]'%LOCAL_PLUGIN_VER%'; $r=[version]'%REMOTE_PLUGIN_VER%'; if($r -gt $l){'1'}else{'0'}"`) do (
    set "PLUGIN_NEEDS_UPDATE=%%R"
)

if "%PLUGIN_NEEDS_UPDATE%"=="1" (
    echo.
    echo ==========================================================
    echo   VapourSynth PLUGIN Update: v%LOCAL_PLUGIN_VER% -^> v%REMOTE_PLUGIN_VER%
    echo   Reinstalling vapoursynth-portable...
    echo ==========================================================
    call :UPDATE_PLUGIN
) else (
    echo [UPDATE] VapourSynth plugins are up to date. No update needed.
)

:CLEANUP
del "%TEMP_REMOTE_JSON%" 2>nul
endlocal
goto :EOF

:: ============================================================
:: SUBROUTINE: update only script files from GitHub raw
:: (vapoursynth-portable is NOT touched)
:: ============================================================
:UPDATE_SCRIPTS
echo.
echo [UPDATE] Downloading and replacing script files...

set "SCRIPT_FILES=UPDATE.bat install.bat Install-Portable-VapourSynth-R73.ps1 launchCLI.bat launchCLInoUpdate.bat requirements.txt upscaler_core.py version.json vsmlrt.py"

for %%F in (%SCRIPT_FILES%) do (
    echo [UPDATE]   Updating: %%F
    curl --ssl-no-revoke -fsSL "%GITHUB_RAW_BASE%/%%F" -o "%APP_DIR%%%F" 2>nul
    if errorlevel 1 (
        echo [UPDATE]   WARNING: unable to download %%F
    )
)

echo.
echo [UPDATE] Script update v%REMOTE_SCRIPT_VER% completed successfully.
echo [UPDATE] NOTE: vapoursynth-portable was not modified.
goto :EOF

:: ============================================================
:: SUBROUTINE: delete vapoursynth-portable and reinstall
:: ============================================================
:UPDATE_PLUGIN
echo.
echo [UPDATE] Removing vapoursynth-portable folder...
set "VS_DIR=%APP_DIR%vapoursynth-portable"
if exist "%VS_DIR%" (
    rmdir /s /q "%VS_DIR%"
    echo [UPDATE] Folder removed.
) else (
    echo [UPDATE] Folder not found, skipping removal.
)

echo [UPDATE] Starting VapourSynth and DLL plugins reinstallation...
set "INSTALL_BAT=%APP_DIR%install.bat"
if exist "%INSTALL_BAT%" (
    call "%INSTALL_BAT%"
    echo [UPDATE] Plugin reinstallation completed.
) else (
    echo [UPDATE] ERROR: install.bat not found in %APP_DIR%
)
goto :EOF
