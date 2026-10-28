@echo off
setlocal
cd /d "%~dp0"
if exist "%USERPROFILE%\.jdks\jbr-21.0.11\bin\java.exe" (
  set "JAVA_HOME=%USERPROFILE%\.jdks\jbr-21.0.11"
)
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo JDK 21 was not found. Set JAVA_HOME to JDK 21 before building.
  pause
  exit /b 1
)
call gradlew.bat assembleDebug
if errorlevel 1 (
  echo BUILD FAILED. Please send a screenshot of this window.
) else (
  echo BUILD SUCCESSFUL. APK: app\build\outputs\apk\debug\app-debug.apk
)
pause
