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
echo  [3/3] Copy jars
echo ============================================================
rem ---- 布局（单一资产目录）----
rem   %PLUGIN_DIR%\WeightHandlerStrategy.jar           宿主插件位（HS-Script 只认这里，动不了）
rem   %ASSET_DIR%\configUi.jar                        引擎资产目录：配置侧扩展(ModulesInfo SPI)
rem                                                     + MCP/UI 主体（mcp.bat/ui.bat 的同级 -cp）
rem 引擎只扫资产目录下的扩展 jar（JarClassLoader 单点）；放错位置会静默退化成
rem 「纯引擎模式」（评估树/条件树/光环/combo/卡组绑定/用途标签整体不生效）。
set "ASSET_DIR=%PLUGIN_DIR%\WeightHandlerStrategy"
if not exist "%ASSET_DIR%" mkdir "%ASSET_DIR%"

copy /y "%ENGINE_JAR%"   "%PLUGIN_DIR%\WeightHandlerStrategy.jar" >nul
if errorlevel 1 (
    echo [FAILED] copy engine jar failed.
    pause
    exit /b 1
)
copy /y "%CONFIGUI_JAR%" "%ASSET_DIR%\configUi.jar" >nul
if errorlevel 1 (
    echo [FAILED] copy configUi jar failed: %ASSET_DIR%
    pause
    exit /b 1
)

echo.
echo [OK] Deployed:
echo   %PLUGIN_DIR%\WeightHandlerStrategy.jar
echo   %ASSET_DIR%\configUi.jar
echo.
echo 资产目录内 mcp.bat / ui.bat / plugin-config*.properties 为手工维护，本脚本不覆盖。
echo Restart / reconnect MCP server, then delete_condition_tree etc.
echo will be available.
echo.
pause
