@echo off
setlocal
set VERSION=8.10.2
set DIST_NAME=gradle-%VERSION%-bin.zip
set BASE_URL=https://services.gradle.org/distributions
if not defined GRADLE_USER_HOME set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set CACHE_DIR=%GRADLE_USER_HOME%\manual-wrapper\gradle-%VERSION%
set GRADLE_BIN=%CACHE_DIR%\gradle-%VERSION%\bin\gradle.bat

if not exist "%GRADLE_BIN%" (
  if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"
  set TEMP_DIR=%CACHE_DIR%\download
  if not exist "%TEMP_DIR%" mkdir "%TEMP_DIR%"
  echo Downloading Gradle %VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%BASE_URL%/%DIST_NAME%' -OutFile '%CACHE_DIR%\download\%DIST_NAME%'; Invoke-WebRequest -Uri '%BASE_URL%/%DIST_NAME%.sha256' -OutFile '%CACHE_DIR%\download\%DIST_NAME%.sha256'"
  if errorlevel 1 exit /b 1
  for /f "usebackq delims=" %%S in ("%CACHE_DIR%\download\%DIST_NAME%.sha256") do set EXPECTED_SHA=%%S
  for /f "tokens=1" %%S in ('certutil -hashfile "%CACHE_DIR%\download\%DIST_NAME%" SHA256 ^| findstr /R /V "hash CertUtil"') do if not defined ACTUAL_SHA set ACTUAL_SHA=%%S
  if /I not "%EXPECTED_SHA%"=="%ACTUAL_SHA%" (
    echo Gradle distribution checksum verification failed.
    exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%CACHE_DIR%\download\%DIST_NAME%' -DestinationPath '%CACHE_DIR%' -Force"
  if errorlevel 1 exit /b 1
  rmdir /s /q "%CACHE_DIR%\download"
)

call "%GRADLE_BIN%" %*
exit /b %ERRORLEVEL%
