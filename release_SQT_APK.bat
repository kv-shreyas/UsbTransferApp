@echo off
setlocal enabledelayedexpansion

SET "PROJECT_DIR=%~dp0"
SET "SIGNER_DIR=%PROJECT_DIR%SQT-APK-Signer"

cd /d "%PROJECT_DIR%"

SET "COPY_DEST="
IF NOT "%~1"=="" (
    SET "GRADLE_TASK=%~1"
    IF NOT "%~2"=="" (
        SET "COPY_DEST=%~2"
    )
) ELSE (
    echo ==========================================================
    echo  📋 Please select a build type to generate and sign:
    echo ==========================================================
    echo 1^) assembleRelease
    echo 2^) assembleDebug
    echo 3^) Quit
    set /p CHOICE="Select an option (1-3): "
    
    if "!CHOICE!"=="1" SET "GRADLE_TASK=:composeApp:assembleRelease"
    if "!CHOICE!"=="2" SET "GRADLE_TASK=:composeApp:assembleDebug"
    if "!CHOICE!"=="3" (
        echo Pipeline aborted.
        exit /b 0
    )
    if "!GRADLE_TASK!"=="" (
        echo ❌ Invalid choice!
        exit /b 1
    )
    
    echo.
    set /p COPY_ANS="Do you want to copy the release directory to a destination? (y/n): "
    if /i "!COPY_ANS!"=="y" (
        set /p COPY_DEST="Please enter the destination path: "
    )
)

echo.
echo ==========================================================
echo    🚀 SQT PIPELINE: BUILD ^& SIGNING
echo ==========================================================
echo Task     : %GRADLE_TASK%
echo Start    : %TIME%
echo.

SET "GRADLE_FILE=composeApp\build.gradle.kts"
IF EXIST "!GRADLE_FILE!" (
    FOR /F "tokens=1,2,3 delims= " %%A IN ('%SystemRoot%\System32\findstr.exe /C:"versionCode =" "!GRADLE_FILE!"') DO SET "VERSION_CODE=%%C"
    FOR /F "tokens=1,2,3 delims= " %%A IN ('%SystemRoot%\System32\findstr.exe /C:"versionName =" "!GRADLE_FILE!" ^| %SystemRoot%\System32\findstr.exe /V "val"') DO (
        SET "VERSION_NAME=%%C"
        SET "VERSION_NAME=!VERSION_NAME:"=!"
    )
    
    echo ==========================================================
    echo  ℹ️ Current Version Info
    echo ==========================================================
    echo  Version Name: !VERSION_NAME!
    echo  Version Code: !VERSION_CODE!
    echo ==========================================================
    
    set /p CHANGE_VER="Do you want to change the version name and code? (y/N): "
    if /i "!CHANGE_VER!"=="y" (
        set /p NEW_VNAME="Enter new Version Name (e.g. 1.0.1): "
        set /p NEW_VCODE="Enter new Version Code (e.g. 2): "
        
        if not "!NEW_VNAME!"=="" if not "!NEW_VCODE!"=="" (
            %SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe -Command "(Get-Content '!GRADLE_FILE!') -replace '^\s*versionCode\s*=\s*[0-9]+', '        versionCode = !NEW_VCODE!' -replace '^\s*versionName\s*=\s*\"".*\""', '        versionName = \""!NEW_VNAME!\""' | Out-File -Encoding ASCII '!GRADLE_FILE!'"
            SET "VERSION_NAME=!NEW_VNAME!"
            echo ✅ Version updated to !NEW_VNAME! ^(!NEW_VCODE!^).
            echo.
        ) else (
            echo ❌ Invalid input. Proceeding with original version.
            echo.
        )
    )
)

REM --- STEP 1: CLEAN AND BUILD ---
echo [1/2] 🔨 STEP 1: Cleaning and Building Project...
echo       (Estimated time: 1-3 minutes depending on gradle cache)

IF EXIST "gradle\gradle-daemon-jvm.properties" del /f /q "gradle\gradle-daemon-jvm.properties"
call gradlew.bat clean %GRADLE_TASK%
IF %ERRORLEVEL% NEQ 0 (
    echo       ❌ ERROR: Gradle build failed!
    exit /b %ERRORLEVEL%
)

echo       ✅ Build completed.
echo.

REM --- FIND THE GENERATED APK ---
echo       🔍 Locating newly generated APK...
SET "APK_PATH="
FOR /F "delims=" %%I IN ('dir /s /b /a-d "%PROJECT_DIR%composeApp\build\outputs\apk\*.apk" 2^>nul ^| %SystemRoot%\System32\findstr.exe /v /i "_aligned.apk" ^| %SystemRoot%\System32\findstr.exe /v /i "_signed.apk"') DO (
    SET "APK_PATH=%%I"
)

IF "%APK_PATH%"=="" (
    echo       ❌ ERROR: No APK found after build! Pipeline aborted.
    exit /b 1
)

echo       🎯 Found APK: %APK_PATH%
echo.

REM --- STEP 2: SIGN WITH AOSP PLATFORM KEYS ---
echo [2/2] 🔐 STEP 2: Signing APK with SQT-APK-Signer...
echo       (Estimated time: ~5-15 seconds)

IF NOT EXIST "%SIGNER_DIR%\sign_auto.bat" (
    echo       ❌ ERROR: sign_auto.bat not found in %SIGNER_DIR%!
    exit /b 1
)

cd /d "%SIGNER_DIR%"
call sign_auto.bat "%APK_PATH%"

echo.
echo ==========================================================
echo  🎉 PIPELINE COMPLETED SUCCESSFULLY!
echo ==========================================================

IF NOT "!COPY_DEST!"=="" (
    FOR %%I IN ("!APK_PATH!") DO (
        SET "OUT_DIR=%%~dpI"
    )
    SET "FINAL_DEST=!COPY_DEST!\!VERSION_NAME!"
    echo.
    echo Copying release directory to !FINAL_DEST!...
    if not exist "!FINAL_DEST!" mkdir "!FINAL_DEST!"
    %SystemRoot%\System32\xcopy.exe /E /I /Y /Q "!OUT_DIR!\*" "!FINAL_DEST!\" > nul
    if !ERRORLEVEL! EQU 0 (
        echo ✅ Successfully copied to !FINAL_DEST!
    ) else (
        echo ❌ ERROR: Failed to copy to !FINAL_DEST!
    )
)
