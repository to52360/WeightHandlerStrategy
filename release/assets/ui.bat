@echo off
chcp 65001 > nul
cd /d "%~dp0"

rem NOTE: keep this file ASCII-only -- cmd reads .bat line by line as ANSI,
rem       so UTF-8 non-ASCII comments get mis-decoded and executed as commands.
rem All paths are relative to this dir, so moving the plugin folder needs no edit.
rem The * wildcard is expanded by java itself (do NOT build -cp with a for loop:
rem %%j expansion breaks on quoted paths).
rem   configUi.jar                       this dir (UI entry + config-side SPI impls,
rem                                      own third-party deps shaded in by -P release)
rem   ..\WeightHandlerStrategy.jar       engine jar (parent dir, koin shaded in)
rem   ..\..\lib\*                        host dependency dir (kotlin / jackson / spring /
rem                                      slf4j / logback / sqlite / javafx ... provided by the host)
rem   mcp-lib\*                          OPTIONAL local dep slot (dev loop only) - see mcp.bat;
rem                                      absent in a release package, ignored by java when absent.
set CP=configUi.jar;..\WeightHandlerStrategy.jar;..\..\lib\*;mcp-lib\*

java -Dapp.config.file=%~dp0plugin-config.properties -Dlogback.configurationFile=%~dp0logback.xml -cp "%CP%" lin.MainKt %*
if errorlevel 1 (
    echo [ERROR] ui failed - see the exception above
    pause
)
