@echo off
rem ============================================================
rem  deploy-mcp.bat - Build & deploy configUi MCP server
rem  Double-click to run. Steps:
rem    1) Build WeightHandlerStrategy with -P release (shade koin dep)
rem    2) Build configUi without -P release (deps from ../lib/*)
rem    3) Copy two jars to MCP server dir
rem  After deploy, restart / reconnect MCP server for new tools.
rem ============================================================
setlocal enabledelayedexpansion

rem ---- Target deploy dir (MCP server root) ----
set "PLUGIN_DIR=F:\myApp\HBuddy_2\plugin"
if not exist "%PLUGIN_DIR%" set "PLUGIN_DIR=%~dp0deploy_out"

rem ---- Project root (script dir) ----
cd /d "%~dp0"

echo ============================================================
echo  [1/3] Build WeightHandlerStrategy (-P release, shaded koin)
echo ============================================================
call mvn -o -pl WeightHanderStrategy -am install -P release -DskipTests
if errorlevel 1 (
    echo [FAILED] WeightHandlerStrategy build failed.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo  [2/3] Build configUi (no -P release)
echo ============================================================
call mvn -o -pl configUi package -DskipTests
if errorlevel 1 (
    echo [FAILED] configUi build failed.
    pause
    exit /b 1
)

set "ENGINE_JAR=WeightHanderStrategy\target\WeightHandlerStrategy.jar"
set "CONFIGUI_JAR=configUi\target\configUi.jar"

if not exist "%ENGINE_JAR%" (
    echo [FAILED] engine jar not found: %ENGINE_JAR%
    pause
    exit /b 1
)
if not exist "%CONFIGUI_JAR%" (
    echo [FAILED] configUi jar not found: %CONFIGUI_JAR%
    pause
    exit /b 1
)

if not exist "%PLUGIN_DIR%" mkdir "%PLUGIN_DIR%"

echo.
echo ============================================================
echo  [3/3] Copy jars to: %PLUGIN_DIR%
echo ============================================================
copy /y "%ENGINE_JAR%"   "%PLUGIN_DIR%\WeightHandlerStrategy.jar" >nul
copy /y "%CONFIGUI_JAR%" "%PLUGIN_DIR%\configUi.jar" >nul
if errorlevel 1 (
    echo [FAILED] copy failed.
    pause
    exit /b 1
)

echo.
echo [OK] Deployed:
echo   %PLUGIN_DIR%\WeightHandlerStrategy.jar
echo   %PLUGIN_DIR%\configUi.jar
echo.
echo Restart / reconnect MCP server, then delete_condition_tree etc.
echo will be available.
echo.
pause
