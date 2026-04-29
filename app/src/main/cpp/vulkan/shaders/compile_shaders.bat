@echo off
setlocal enabledelayedexpansion

echo ========================================
echo   SPIR-V Shader Compiler
echo ========================================
echo.

:: 设置路径
set SHADER_DIR=%~dp0
set PROJECT_DIR=%~dp0..\..\..\..
set OUTPUT_DIR=%PROJECT_DIR%\assets\shaders

:: 查找glslc
set GLSLC=
set ANDROID_SDK=%ANDROID_HOME%

if "%ANDROID_SDK%"=="" (
    set ANDROID_SDK=%LOCALAPPDATA%\Android\Sdk
)

:: 尝试多个可能的路径
for %%p in (
    "%ANDROID_SDK%\cmake\3.22.1\bin\glslc.exe"
    "%ANDROID_SDK%\cmake\3.21.1\bin\glslc.exe"
    "%ANDROID_SDK%\cmake\3.20.0\bin\glslc.exe"
    "%ANDROID_SDK%\ndk-bundle\shader-tools\windows-x86_64\glslc.exe"
) do (
    if exist %%p (
        set GLSLC=%%p
        goto :found_glslc
    )
)

echo ERROR: glslc not found!
echo.
echo Please install CMake via Android SDK Manager or set ANDROID_HOME.
echo.
pause
exit /b 1

:found_glslc
echo Found glslc: %GLSLC%
echo.

:: 创建输出目录
if not exist "%OUTPUT_DIR%" (
    mkdir "%OUTPUT_DIR%"
    echo Created output directory: %OUTPUT_DIR%
)

:: 编译着色器
set COMPILE_COUNT=0
set ERROR_COUNT=0

echo Compiling shaders...
echo.

for %%f in ("%SHADER_DIR%*.comp") do (
    set "INPUT_FILE=%%f"
    set "FILENAME=%%~nf"
    set "OUTPUT_FILE=%OUTPUT_DIR%\%%~nf.spv"
    
    echo Compiling: %%~nf.comp
    
    "%GLSLC%" ^
        --target-env=vulkan1.0 ^
        -O ^
        "!INPUT_FILE!" ^
        -o "!OUTPUT_FILE!"
    
    if !errorlevel! equ 0 (
        echo   SUCCESS: %%~nf.spv
        set /a COMPILE_COUNT+=1
    ) else (
        echo   FAILED: %%~nf.comp
        set /a ERROR_COUNT+=1
    )
    echo.
)

:: 也编译.vert和.frag文件
for %%f in ("%SHADER_DIR%*.vert") do (
    set "INPUT_FILE=%%f"
    set "FILENAME=%%~nf"
    set "OUTPUT_FILE=%OUTPUT_DIR%\%%~nf_vert.spv"
    
    echo Compiling: %%~nf.vert
    
    "%GLSLC%" ^
        --target-env=vulkan1.0 ^
        -O ^
        "!INPUT_FILE!" ^
        -o "!OUTPUT_FILE!"
    
    if !errorlevel! equ 0 (
        echo   SUCCESS: %%~nf_vert.spv
        set /a COMPILE_COUNT+=1
    ) else (
        echo   FAILED: %%~nf.vert
        set /a ERROR_COUNT+=1
    )
    echo.
)

for %%f in ("%SHADER_DIR%*.frag") do (
    set "INPUT_FILE=%%f"
    set "FILENAME=%%~nf"
    set "OUTPUT_FILE=%OUTPUT_DIR%\%%~nf_frag.spv"
    
    echo Compiling: %%~nf.frag
    
    "%GLSLC%" ^
        --target-env=vulkan1.0 ^
        -O ^
        "!INPUT_FILE!" ^
        -o "!OUTPUT_FILE!"
    
    if !errorlevel! equ 0 (
        echo   SUCCESS: %%~nf_frag.spv
        set /a COMPILE_COUNT+=1
    ) else (
        echo   FAILED: %%~nf.frag
        set /a ERROR_COUNT+=1
    )
    echo.
)

:: 总结
echo ========================================
echo   Compilation Summary
echo ========================================
echo.
echo   Compiled: %COMPILE_COUNT%
echo   Failed:   %ERROR_COUNT%
echo.

if %ERROR_COUNT% gtr 0 (
    echo WARNING: Some shaders failed to compile!
    echo.
    pause
    exit /b 1
) else (
    echo All shaders compiled successfully!
    echo.
    pause
    exit /b 0
)
