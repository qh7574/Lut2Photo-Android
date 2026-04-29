@echo off
setlocal enabledelayedexpansion

echo ========================================
echo   SPIR-V Shader Validator
echo ========================================
echo.

:: 设置路径
set SHADER_DIR=%~dp0
set PROJECT_DIR=%~dp0..\..\..\..
set OUTPUT_DIR=%PROJECT_DIR%\assets\shaders

:: 查找spirv-val
set SPIRV_VAL=
set ANDROID_SDK=%ANDROID_HOME%

if "%ANDROID_SDK%"=="" (
    set ANDROID_SDK=%LOCALAPPDATA%\Android\Sdk
)

:: 尝试多个可能的路径
for %%p in (
    "%ANDROID_SDK%\cmake\3.22.1\bin\spirv-val.exe"
    "%ANDROID_SDK%\cmake\3.21.1\bin\spirv-val.exe"
    "%ANDROID_SDK%\cmake\3.20.0\bin\spirv-val.exe"
    "%VULKAN_SDK%\Bin\spirv-val.exe"
) do (
    if exist %%p (
        set SPIRV_VAL=%%p
        goto :found_spirv_val
    )
)

echo ERROR: spirv-val not found!
echo.
echo Please install Vulkan SDK or Android CMake tools.
echo.
pause
exit /b 1

:found_spirv_val
echo Found spirv-val: %SPIRV_VAL%
echo.

:: 验证着色器
set VALID_COUNT=0
set ERROR_COUNT=0

echo Validating SPIR-V files...
echo.

for %%f in ("%OUTPUT_DIR%\*.spv") do (
    set "INPUT_FILE=%%f"
    set "FILENAME=%%~nf"
    
    echo Validating: %%~nf.spv
    
    "%SPIRV_VAL%" --target-env vulkan1.0 "!INPUT_FILE!"
    
    if !errorlevel! equ 0 (
        echo   VALID
        set /a VALID_COUNT+=1
    ) else (
        echo   INVALID
        set /a ERROR_COUNT+=1
    )
    echo.
)

:: 总结
echo ========================================
echo   Validation Summary
echo ========================================
echo.
echo   Valid:   %VALID_COUNT%
echo   Invalid: %ERROR_COUNT%
echo.

if %ERROR_COUNT% gtr 0 (
    echo WARNING: Some shaders are invalid!
    echo.
    pause
    exit /b 1
) else (
    echo All shaders are valid!
    echo.
    pause
    exit /b 0
)
