@echo off
REM Starts VeriQra on the local network with HTTPS.
REM
REM The browser only hands a page the camera and the service worker when the origin is
REM secure, and "localhost" is the only insecure origin it allows. A phone on the same
REM Wi-Fi therefore needs HTTPS.
REM
REM The key store is created by scripts\make-local-certificate.ps1.

setlocal
cd /d "%~dp0"

if not exist "target\veriqra-2.0.0.jar" (
  echo Building the application...
  call mvnw.cmd -q clean package -DskipTests || exit /b 1
)

if not exist "config\veriqra.p12" (
  echo The key store is missing. Creating it now.
  powershell -ExecutionPolicy Bypass -File scripts\make-local-certificate.ps1
)

for /f "tokens=2 delims=:" %%a in ('netsh interface ipv4 show address ^| findstr /c:"IPv4 Address"') do (
  set IP=%%a
)
set IP=%IP: =%
if not "%IP%"=="" (
  echo.
  echo   application  https://%IP%:8443
  echo   certificate  config\veriqra-certificate.cer  ^(install it on the phone^)
  echo.
)

java -jar target\veriqra-2.0.0.jar --spring.profiles.active=https
