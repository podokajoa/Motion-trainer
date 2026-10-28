@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
set "APP_ID=com.example.travelfootprints.demo"
set "APK=app\build\outputs\apk\debug\app-debug.apk"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
rem Android SDK is normally under the signed-in Windows user's local AppData.
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "REPORT=test-reports"
if not exist "%REPORT%" mkdir "%REPORT%"
set "RUN_LOG=%REPORT%\last-run.txt"

echo TravelFootprints DEMO automated check
echo Starting: %date% %time% > "%RUN_LOG%"
echo Demo package: %APP_ID% >> "%RUN_LOG%"
if exist "%USERPROFILE%\.jdks\jbr-21.0.11\bin\java.exe" set "JAVA_HOME=%USERPROFILE%\.jdks\jbr-21.0.11"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo ERROR: JDK not found. Expected JDK 21. >> "%RUN_LOG%"
  goto :failed
)
if not exist "gradlew.bat" (
  echo ERROR: Place this script next to gradlew.bat. >> "%RUN_LOG%"
  goto :failed
)
if not exist "%ANDROID_HOME%\platforms" (
  echo ERROR: Android SDK not found at %ANDROID_HOME% >> "%RUN_LOG%"
  goto :failed
)
findstr /c:"com.example.travelfootprints.demo" "app\build.gradle.kts" > nul
if errorlevel 1 (
  echo ERROR: This is not the separate DEMO project. Nothing was installed. >> "%RUN_LOG%"
  goto :failed
)
echo Building debug APK...
call gradlew.bat assembleDebug >> "%RUN_LOG%" 2>&1
if errorlevel 1 goto :failed
echo APK build passed. >> "%RUN_LOG%"
echo Running unit tests separately...
call gradlew.bat testDebugUnitTest --max-workers=1 >> "%RUN_LOG%" 2>&1
if errorlevel 1 (
  echo WARNING: Unit test runner failed. APK build passed; continue to device check. >> "%RUN_LOG%"
  echo WARNING: Unit test runner failed. Continuing with APK installation if phone is connected.
) else (
  echo Unit tests passed. >> "%RUN_LOG%"
)

if not exist "%ADB%" (
  echo Android platform-tools not found. Build-only complete. >> "%RUN_LOG%"
  goto :no_device
)
set "DEVICE="
for /f "tokens=1,2" %%A in ('"%ADB%" devices') do if /i "%%B"=="device" set "DEVICE=%%A"
if not defined DEVICE (
  rem First try ADB's ordinary network discovery; no firewall or network changes.
  set "AUTO_ENDPOINT="
  for /f "tokens=1,2" %%A in ('"%ADB%" mdns services 2^>nul ^| findstr /i "_adb-tls-connect._tcp"') do set "AUTO_ENDPOINT=%%B"
  if defined AUTO_ENDPOINT "%ADB%" connect "!AUTO_ENDPOINT!" >> "%RUN_LOG%" 2>&1
)
if not defined DEVICE if exist "device_endpoint.txt" (
  set "LAST_ENDPOINT="
  set /p LAST_ENDPOINT=<"device_endpoint.txt"
  if defined LAST_ENDPOINT "%ADB%" connect "!LAST_ENDPOINT!" >> "%RUN_LOG%" 2>&1
)
for /f "tokens=1,2" %%A in ('"%ADB%" devices') do if /i "%%B"=="device" set "DEVICE=%%A"
if not defined DEVICE goto :no_device

echo Connected device: !DEVICE! >> "%RUN_LOG%"
echo Installing only %APP_ID% (existing personal app is untouched)...
"%ADB%" -s "!DEVICE!" install -r "%APK%" >> "%RUN_LOG%" 2>&1
if errorlevel 1 (
  echo INSTALL FAILED. No uninstall was attempted. >> "%RUN_LOG%"
  goto :failed
)
"%ADB%" -s "!DEVICE!" shell am start -n "%APP_ID%/com.example.travelfootprints.MainActivity" >> "%RUN_LOG%" 2>&1
if errorlevel 1 goto :failed
timeout /t 5 /nobreak > nul
"%ADB%" -s "!DEVICE!" logcat -d -s TravelFootprintsMap:I Mbgl-HttpRequest:E >> "%RUN_LOG%" 2>&1
"%ADB%" -s "!DEVICE!" exec-out screencap -p > "%REPORT%\last-screen.png"
echo RESULT: Test app installed and started; inspect %REPORT%\last-screen.png
echo RESULT: TEST APP LAUNCH ATTEMPT COMPLETE >> "%RUN_LOG%"
goto :done

:no_device
echo RESULT: Build passed. No connected device; installation skipped.
echo RESULT: BUILD PASSED, INSTALL SKIPPED >> "%RUN_LOG%"
goto :done

:failed
echo RESULT: A step failed. See %RUN_LOG% . Existing app was not uninstalled.
echo RESULT: FAILED >> "%RUN_LOG%"

:done
echo Completed: %date% %time% >> "%RUN_LOG%"
echo.
echo Detailed result saved to %RUN_LOG%
echo Leave this window open to read the result, or close it any time.
pause
