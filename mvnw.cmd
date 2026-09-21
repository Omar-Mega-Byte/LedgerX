@echo off
setlocal

set "WRAPPER_PROPERTIES=%~dp0.mvn\wrapper\maven-wrapper.properties"
if not exist "%WRAPPER_PROPERTIES%" (
  echo Maven Wrapper properties not found: %WRAPPER_PROPERTIES%
  exit /b 1
)

for /f "tokens=1,* delims==" %%A in (%WRAPPER_PROPERTIES%) do (
  if "%%A"=="distributionUrl" set "DISTRIBUTION_URL=%%B"
  if "%%A"=="distributionSha512Sum" set "DISTRIBUTION_SHA512=%%B"
)

if "%DISTRIBUTION_URL%"=="" (
  echo distributionUrl is missing from %WRAPPER_PROPERTIES%
  exit /b 1
)

set "MAVEN_USER_HOME=%MAVEN_USER_HOME%"
if "%MAVEN_USER_HOME%"=="" set "MAVEN_USER_HOME=%USERPROFILE%\.m2"
set "MAVEN_HOME=%MAVEN_USER_HOME%\wrapper\dists\apache-maven-3.9.16"
set "MAVEN_COMMAND=%MAVEN_HOME%\bin\mvn.cmd"
set "ARCHIVE=%MAVEN_USER_HOME%\wrapper\dists\apache-maven-3.9.16-bin.zip"

if not exist "%MAVEN_COMMAND%" (
  echo Downloading Maven distribution...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; $destination = '%ARCHIVE%'; $directory = Split-Path -Parent $destination; New-Item -ItemType Directory -Force -Path $directory | Out-Null; Invoke-WebRequest -UseBasicParsing -Uri '%DISTRIBUTION_URL%' -OutFile $destination; if ((Get-FileHash -Algorithm SHA512 -LiteralPath $destination).Hash.ToLowerInvariant() -ne '%DISTRIBUTION_SHA512%') { Remove-Item -LiteralPath $destination -Force; throw 'Maven distribution checksum verification failed.' }; Expand-Archive -LiteralPath $destination -DestinationPath $directory -Force"
  if errorlevel 1 exit /b %errorlevel%
)

call "%MAVEN_COMMAND%" %*
