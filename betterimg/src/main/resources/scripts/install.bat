@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
echo ==========================================================
echo    Standalone VapourSynth Environment Initialization
echo ==========================================================

set "VS_DIR=%~dp0vapoursynth-portable"
set "PYTHON_EXE=%VS_DIR%\python.exe"

REM Detect and purge legacy VapourSynth portable (R73 / portable.vs / vs-plugins)
if exist "%VS_DIR%\portable.vs" (
    echo Detected legacy VapourSynth portable installation. Removing for clean Python 3.13 setup...
    rd /s /q "%VS_DIR%"
)
if exist "%VS_DIR%\VSScript.dll" (
    echo Detected legacy VapourSynth installation. Removing for clean Python 3.13 setup...
    rd /s /q "%VS_DIR%"
)
if exist "%VS_DIR%\vs-plugins" (
    echo Detected legacy vs-plugins folder. Removing for clean Python 3.13 setup...
    rd /s /q "%VS_DIR%"
)
if exist "%VS_DIR%" (
    if not exist "%VS_DIR%\python313._pth" (
        echo Existing environment is not Python 3.13 embeddable. Removing for clean setup...
        rd /s /q "%VS_DIR%"
    )
)

if not exist "%PYTHON_EXE%" (
    echo.
    echo [1/4] Installing Python 3.13 Portable...
    if not exist "%VS_DIR%" mkdir "%VS_DIR%"
    
    echo Downloading Python 3.13 embeddable...
    curl -fsSL "https://www.python.org/ftp/python/3.13.2/python-3.13.2-embed-amd64.zip" -o "%~dp0python-embed.zip"
    if errorlevel 1 (
        echo ERROR: Failed to download Python 3.13 embeddable.
        exit /b 1
    )
    
    echo Extracting Python...
    tar -xf "%~dp0python-embed.zip" -C "%VS_DIR%"
    del "%~dp0python-embed.zip" 2>nul
    
    echo Configuring python313._pth for site-packages...
    echo import site>> "%VS_DIR%\python313._pth"
    echo Lib\site-packages>> "%VS_DIR%\python313._pth"
    echo .>> "%VS_DIR%\python313._pth"
    
    echo Downloading get-pip.py...
    curl -fsSL "https://bootstrap.pypa.io/get-pip.py" -o "%VS_DIR%\get-pip.py"
    if errorlevel 1 (
        echo ERROR: Failed to download get-pip.py.
        exit /b 1
    )
    
    echo Installing pip...
    "%PYTHON_EXE%" "%VS_DIR%\get-pip.py" --no-warn-script-location --quiet
    del "%VS_DIR%\get-pip.py" 2>nul
)

if not exist "%PYTHON_EXE%" (
    echo ERROR: python.exe not found at "%PYTHON_EXE%".
    exit /b 1
)

echo.
echo [2/4] Installing dependencies via pip...
set PYTHONNOUSERSITE=1
"%PYTHON_EXE%" -m pip install --upgrade --quiet pip setuptools wheel
"%PYTHON_EXE%" -m pip install -r "%~dp0requirements.txt"
if errorlevel 1 (
    echo ERROR: pip install failed.
    exit /b 1
)

echo.
echo [3/4] Configuring VapourSynth...
"%PYTHON_EXE%" -m vapoursynth config

echo.
echo [4/4] Setting up ArtCNN models...
set "MODEL_DIR=%~dp0.vsjet\vsscale\onnx\artcnn\v1.6.2"
set "MODEL_FILE=%MODEL_DIR%\ArtCNN_R8F64_JPEG444.onnx"
if not exist "%MODEL_FILE%" (
    if not exist "%MODEL_DIR%" mkdir "%MODEL_DIR%"
    echo Downloading ArtCNN_R8F64_JPEG444.onnx...
    curl -fsSL "https://github.com/Artoriuz/ArtCNN/releases/download/v1.6.2/ArtCNN_R8F64_JPEG444.onnx" -o "%MODEL_FILE%"
    if errorlevel 1 (
        echo Fallback: downloading via vsscale...
        "%PYTHON_EXE%" -c "from vsscale.mlrt.cli import app; import sys; sys.argv=['vsscale', 'onnx', 'download', 'ArtCNN', '--latest', '-y']; app.meta()"
        "%PYTHON_EXE%" -c "import os, glob; [os.remove(f) for f in glob.glob(r'%~dp0.vsjet\vsscale\onnx\artcnn\**\*.onnx', recursive=True) if not f.endswith('ArtCNN_R8F64_JPEG444.onnx')]"
    )
)

echo.
echo Setup completed successfully!
exit /b 0