@echo off
title BetterIMG - CLI Mode
echo ==========================================================
echo                Starting BetterIMG (CLI)
echo ==========================================================
echo.

set VS_DIR=%~dp0vapoursynth-portable
set PYTHON_EXE="%VS_DIR%\python.exe"

if not exist %PYTHON_EXE% (
    echo [ERROR] Portable environment not found!
    echo Please make sure you have run the Setup file first.
    echo.
    pause
    exit /b
)

echo [1/2] Checking for updates...
call "%~dp0UPDATE.bat"

echo.
echo [2/2] Starting CLI mode...
%PYTHON_EXE% upscaler_core.py

echo.
pause
