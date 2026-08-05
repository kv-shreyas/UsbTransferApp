@echo off
REM Automatic APK signing for AOSP system apps
REM - Auto-detects apksigner and zipalign in Android SDK
REM - Signs using platform.pk8 + platform.x509.pem

IF "%1"=="" (
    ECHO Usage: sign_auto.bat ^<unsigned-apk^>
    EXIT /B 1
)

SET "INPUT_APK=%~1"
SET "BASE=%~n1"
SET "OUT_DIR=%~dp1%BASE%_output"

IF NOT EXIST "%OUT_DIR%" (
    mkdir "%OUT_DIR%"
)

SET "ALIGNED=%OUT_DIR%\%BASE%_aligned.apk"
SET "SIGNED=%OUT_DIR%\%BASE%_platform_signed.apk"

ECHO ===================================================
ECHO Starting APK Signing Process
ECHO ===================================================
ECHO Input APK: "%INPUT_APK%"
ECHO Output Directory: "%OUT_DIR%"
ECHO.

REM --- Locate Signing Tools ---
SET "TOOLS_DIR=%~dp0Tools\"

SET "APKSIGNER=%TOOLS_DIR%apksigner.bat"
SET "ZIPALIGN=%TOOLS_DIR%zipalign.exe"

IF NOT EXIST "%APKSIGNER%" (
    ECHO ERROR: apksigner.bat not found in %TOOLS_DIR%!
    EXIT /B 1
)

IF NOT EXIST "%ZIPALIGN%" (
    ECHO ERROR: zipalign.exe not found in %TOOLS_DIR%!
    EXIT /B 1
)

REM --- Verify platform keys ---
SET "PK8_KEY=%TOOLS_DIR%platform.pk8"
SET "PEM_CERT=%TOOLS_DIR%platform.x509.pem"

IF NOT EXIST "%PK8_KEY%" (
    ECHO ERROR: platform.pk8 missing!
    EXIT /B 1
)

IF NOT EXIST "%PEM_CERT%" (
    ECHO ERROR: platform.x509.pem missing!
    EXIT /B 1
)

ECHO [Info] Tools located successfully.
ECHO.

REM --- Align APK ---
ECHO [1/2] Aligning APK...
"%ZIPALIGN%" -f 4 "%INPUT_APK%" "%ALIGNED%"
IF %ERRORLEVEL% NEQ 0 (
    ECHO [ERROR] Zipalign failed!
    EXIT /B %ERRORLEVEL%
)
ECHO [Success] APK aligned properly.

ECHO.
REM --- Sign APK ---
ECHO [2/2] Signing APK with platform keys...
call "%APKSIGNER%" sign ^
  --key "%PK8_KEY%" ^
  --cert "%PEM_CERT%" ^
  --out "%SIGNED%" ^
  "%ALIGNED%"
IF %ERRORLEVEL% NEQ 0 (
    ECHO [ERROR] Apksigner failed!
    EXIT /B %ERRORLEVEL%
)
ECHO [Success] APK signed securely.

ECHO.
ECHO ===================================================
ECHO DONE! Process completed successfully.
ECHO Signed APK: "%SIGNED%"
ECHO ===================================================

