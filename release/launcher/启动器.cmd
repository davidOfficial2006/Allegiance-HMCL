@echo off
setlocal
pushd "%~dp0"
set "LAUNCH_JAVA="
if exist "%JAVA_HOME%\bin\javaw.exe" set "LAUNCH_JAVA=%JAVA_HOME%\bin\javaw.exe"
if not defined LAUNCH_JAVA if exist "%ProgramFiles%\Java\jdk-21\bin\javaw.exe" set "LAUNCH_JAVA=%ProgramFiles%\Java\jdk-21\bin\javaw.exe"
if not defined LAUNCH_JAVA (
  where javaw.exe >nul 2>&1
  if errorlevel 1 (
    echo Java 21 was not found. Install 64-bit Java 21, then reopen this launcher.
    pause
    popd
    exit /b 1
  )
  set "LAUNCH_JAVA=javaw.exe"
)
start "" "%LAUNCH_JAVA%" -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djdk.net.unixdomain.tmpdir=C:/Windows/Temp -Dhmcl.curseforge.source=auto -jar "%~dp0Allegiance-HMCL-maint-20261103.3.jar"
popd
endlocal
