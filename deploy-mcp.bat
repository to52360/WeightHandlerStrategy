@echo off
rem ============================================================
rem  deploy-mcp.bat - build & deploy the engine jar + configUi jar
rem
rem  Usage (double-click friendly):
rem    deploy-mcp.bat          full build (maven offline) then deploy
rem    deploy-mcp.bat fast     skip maven, deploy the jars already in target\
rem
rem  Layout it maintains:
rem    <PLUGIN_DIR>\WeightHandlerStrategy.jar        host plugin slot (HS-Script only reads here)
rem    <ASSET_DIR>\configUi.jar                      engine asset dir: config-side extension
rem                                                  (ModulesInfo SPI) + MCP/UI main jar
rem    ASSET_DIR = PLUGIN_DIR\WeightHandlerStrategy  (same dir as mcp.bat / ui.bat / the db)
rem
rem  The engine only scans ONE hard-coded dir (JarClassLoader): the asset dir.
rem  Putting configUi.jar anywhere else silently degrades to "engine-only mode"
rem  (evaluator trees / condition trees / aura / combo / cardgroup / purpose tags
rem  all stop working) - see the note printed at the end.
rem
rem  ------------------------------------------------------------
rem  KEEP THIS FILE ASCII-ONLY.
rem  cmd reads a .bat line by line using the *active ANSI codepage*, so UTF-8
rem  non-ASCII text (e.g. Chinese comments) gets mis-decoded and executed as
rem  commands. Depending on the bytes that can break block parsing and abort the
rem  batch BEFORE any pause -> double-click shows a flash and nothing else.
rem  Same rule as the deployed mcp.bat / ui.bat. Write logs/messages in English.
rem  ------------------------------------------------------------
rem  Never exits silently: every failure path ends with pause + a log line in
rem  deploy-mcp.log (so a vanished window still leaves evidence).
rem ============================================================

chcp 65001 >nul
setlocal enabledelayedexpansion
set "ROOT=%~dp0"
set "LOG=%ROOT%deploy-mcp.log"
cd /d "%ROOT%"

set "MODE=full"
if /i "%~1"=="fast" set "MODE=fast"
if /i "%~1"=="/fast" set "MODE=fast"

echo ============================================================
echo  deploy-mcp   mode=%MODE%
echo  root: %ROOT%
echo ============================================================
echo.

rem ---------- locate maven ----------
set "MVN="
for %%C in (mvn.cmd mvn.bat mvn) do (
    if not defined MVN (
        for /f "delims=" %%P in ('where %%C 2^>nul') do if not defined MVN set "MVN=%%P"
    )
)
if not defined MVN if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MVN if defined M2_HOME if exist "%M2_HOME%\bin\mvn.cmd" set "MVN=%M2_HOME%\bin\mvn.cmd"
if not defined MVN for /d %%D in ("%ProgramFiles%\JetBrains\*") do if exist "%%D\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" set "MVN=%%D\plugins\maven-plugin\lib\maven3\bin\mvn.cmd"
if not defined MVN for /d %%D in ("G:\Program Files\IntelliJ IDEA*") do if exist "%%D\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" set "MVN=%%D\plugins\maven-plugin\lib\maven3\bin\mvn.cmd"
if not defined MVN for /d %%D in ("C:\Program Files\JetBrains\*") do if exist "%%D\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" set "MVN=%%D\plugins\maven-plugin\lib\maven3\bin\mvn.cmd"

rem ---------- locate jdk (needed by maven; a double-clicked shell often has no JAVA_HOME) ----------
if not defined JAVA_HOME if exist "%USERPROFILE%\.jdks" for /d %%J in ("%USERPROFILE%\.jdks\*") do if not defined JAVA_HOME if exist "%%J\bin\javac.exe" set "JAVA_HOME=%%J"
if not defined JAVA_HOME for /d %%J in ("C:\Program Files\Java\jdk*") do if not defined JAVA_HOME if exist "%%J\bin\javac.exe" set "JAVA_HOME=%%J"
if not defined JAVA_HOME for /d %%J in ("C:\Program Files\Eclipse Adoptium\jdk*") do if not defined JAVA_HOME if exist "%%J\bin\javac.exe" set "JAVA_HOME=%%J"

echo [env] maven    : %MVN%
echo [env] JAVA_HOME: %JAVA_HOME%
echo.

rem ---------- target dirs ----------
set "PLUGIN_DIR=F:\myApp\HBuddy_2\plugin"
if not exist "%PLUGIN_DIR%" (
    echo [WARN] plugin dir not found: %PLUGIN_DIR%
    echo [WARN] falling back to %ROOT%deploy_out
    set "PLUGIN_DIR=%ROOT%deploy_out"
)
set "ASSET_DIR=%PLUGIN_DIR%\WeightHandlerStrategy"
for %%I in ("%PLUGIN_DIR%\..") do set "HOST_DIR=%%~fI"

set "ENGINE_JAR=%ROOT%WeightHanderStrategy\target\WeightHandlerStrategy.jar"
set "CONFIGUI_JAR=%ROOT%configUi\target\configUi.jar"

if /i "%MODE%"=="full" goto :build
goto :deploy

rem ============================================================
:build
if not defined MVN (
    echo [FAILED] maven not found on PATH and no known fallback exists.
    echo          install maven, or set MAVEN_HOME, or run:
    echo          ^> deploy-mcp.bat fast
    goto :fail
)
if not defined JAVA_HOME (
    echo [FAILED] JAVA_HOME not found and no JDK fallback matched.
    echo          set JAVA_HOME, then retry.
    goto :fail
)

echo ============================================================
echo  [1/3] mvn install WeightHanderStrategy -P release   (shades koin)
echo ============================================================
call "%MVN%" -o -pl WeightHanderStrategy -am install -P release -DskipTests
if errorlevel 1 (
    echo [FAILED] WeightHandlerStrategy build failed.
    goto :fail
)

echo.
echo ============================================================
echo  [2/3] mvn package configUi                          (no shade)
echo ============================================================
call "%MVN%" -o -pl configUi package -DskipTests
if errorlevel 1 (
    echo [FAILED] configUi build failed.
    goto :fail
)

rem ============================================================
:deploy
echo.
echo ============================================================
echo  [3/3] copy jars
echo ============================================================
if not exist "%ENGINE_JAR%" (
    echo [FAILED] engine jar not found: %ENGINE_JAR%
    echo          run without "fast" to build it first.
    goto :fail
)
if not exist "%CONFIGUI_JAR%" (
    echo [FAILED] configUi jar not found: %CONFIGUI_JAR%
    echo          run without "fast" to build it first.
    goto :fail
)
if not exist "%PLUGIN_DIR%" mkdir "%PLUGIN_DIR%"
if not exist "%ASSET_DIR%"  mkdir "%ASSET_DIR%"

copy /y "%ENGINE_JAR%"   "%PLUGIN_DIR%\WeightHandlerStrategy.jar" >nul
if errorlevel 1 (
    echo [FAILED] copy engine jar failed: %PLUGIN_DIR%
    goto :fail
)
copy /y "%CONFIGUI_JAR%" "%ASSET_DIR%\configUi.jar" >nul
if errorlevel 1 (
    echo [FAILED] copy configUi jar failed: %ASSET_DIR%
    goto :fail
)

echo [ok] %PLUGIN_DIR%\WeightHandlerStrategy.jar
echo [ok] %ASSET_DIR%\configUi.jar
echo.
for %%F in ("%PLUGIN_DIR%\WeightHandlerStrategy.jar" "%ASSET_DIR%\configUi.jar") do echo      %%~tF  %%~zF bytes  %%~nxF

rem ---------- sanity checks (these are the silent-failure traps) ----------
echo.
if not exist "%ASSET_DIR%\configUi.jar" (
    echo [WARN] asset dir has no configUi.jar - engine will run in engine-only mode.
)
if not exist "%ASSET_DIR%\mcp.bat" (
    echo [WARN] asset dir has no mcp.bat - MCP side not launched from here.
)
rem loose check: host root logback must mention package "lin" (a logger or its own appender),
rem otherwise engine INFO/ERROR are filtered by the root level and stay invisible.
if exist "%HOST_DIR%\logback.xml" (
    findstr /i /c:"lin" "%HOST_DIR%\logback.xml" >nul 2>&1
    if errorlevel 1 echo [WARN] host logback.xml has no "lin" logger/appender - engine logs may be invisible. See %HOST_DIR%\log\weightHandler.log
)

echo.
echo ============================================================
echo  done. next steps:
echo    1) restart the host (HS-Script) so the new engine jar loads
echo    2) reconnect the MCP server (new tools show up after reconnect)
echo    3) engine log: %HOST_DIR%\log\weightHandler.log
echo ============================================================
echo [deploy-mcp] OK %DATE% %TIME% mode=%MODE% >> "%LOG%"
echo.
pause
endlocal
exit /b 0

:fail
echo.
echo ============================================================
echo  DEPLOY FAILED - see messages above.
echo ============================================================
echo [deploy-mcp] FAILED %DATE% %TIME% mode=%MODE% >> "%LOG%"
echo.
pause
endlocal
exit /b 1
