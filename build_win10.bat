@echo off
chcp 65001 >nul 2>&1
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "PS_ARGS="
set "NOPAUSE="
set "PROG_TOTAL=7"

if /i "%~1"=="skipclean" set "PS_ARGS=-SkipClean"
if /i "%~2"=="skipclean" set "PS_ARGS=-SkipClean"
if /i "%~1"=="nopause" set "NOPAUSE=1"
if /i "%~2"=="nopause" set "NOPAUSE=1"

call :show_progress 1 %PROG_TOTAL% "Preparing build env (fDroid signed, single APK)"
echo     Output: %CD%\Klickr-fDroid-release-signed-^<versionName^>.apk
echo     3.5.x: no ABI splits (unlike 4.0+)
echo     Upstream release.yml: assembleFDroidRelease bundleFDroidRelease
echo.

call :show_progress 2 %PROG_TOTAL% "Check PowerShell and Gradle wrapper"
where powershell >nul 2>&1
if errorlevel 1 (
    call :fail "PowerShell not found in PATH"
)
if not exist "%~dp0gradlew.bat" (
    call :fail "gradlew.bat not found; run from project root"
)
if not exist "%~dp0build-Klickr-fDroid-release-signed.ps1" (
    call :fail "build-Klickr-fDroid-release-signed.ps1 not found"
)

call :show_progress 3 %PROG_TOTAL% "Remove old APKs if present"
set "OLD_REMOVED=0"
if exist "Klickr-fDroid-release-signed.apk" (
    attrib -r "Klickr-fDroid-release-signed.apk" >nul 2>&1
    del /f /q "Klickr-fDroid-release-signed.apk" >nul 2>&1
    set "OLD_REMOVED=1"
)
for %%F in ("Klickr-fDroid-release-signed-*.apk") do (
    if exist %%F (
        attrib -r "%%F" >nul 2>&1
        del /f /q "%%F" >nul 2>&1
        if exist "%%F" (
            call :fail "Cannot delete old file: %%F"
        )
        set "OLD_REMOVED=1"
    )
)
if "!OLD_REMOVED!"=="1" (
    echo     Removed old APKs
) else (
    echo     No old APKs, skip
)
echo.

call :show_progress 4 %PROG_TOTAL% "Run build script (sign, clean, Gradle, copy)"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-Klickr-fDroid-release-signed.ps1" %PS_ARGS%
if errorlevel 1 (
    call :fail "Build script exited non-zero"
)

call :show_progress 6 %PROG_TOTAL% "Verify output APK"
set "APK_COUNT=0"
set "LAST_APK="
for %%F in ("Klickr-fDroid-release-signed-*.apk") do (
    if exist %%F (
        set /a APK_COUNT+=1
        set "LAST_APK=%%~nxF"
    )
)
if !APK_COUNT! LSS 1 (
    call :fail "APK not found: Klickr-fDroid-release-signed-*.apk"
)

call :show_progress 7 %PROG_TOTAL% "Build complete"
echo     Exported: !LAST_APK!
goto :end_ok

:fail
call :show_progress 0 %PROG_TOTAL% "Build failed"
if not "%~1"=="" echo     %~1
goto :end_fail

:show_progress
set /a "_pct=(%~1*100)/%~2"
if %~1 LEQ 0 set "_pct=0"
echo [%~1/%~2 !_pct!%%] %~3
exit /b 0

:end_fail
echo.
if defined NOPAUSE exit /b 1
pause
exit /b 1

:end_ok
echo.
if defined NOPAUSE exit /b 0
pause
exit /b 0
