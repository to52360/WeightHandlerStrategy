@echo off
chcp 65001 > nul
cd /d "%~dp0"

rem NOTE: keep this file ASCII-only -- cmd reads .bat line by line as ANSI,
rem       so UTF-8 non-ASCII comments get mis-decoded and executed as commands.
rem All paths are relative to this dir, so moving the plugin folder needs no edit.
rem   configUi.jar                       this dir (MCP server + config-side SPI impls,
rem                                      own third-party deps shaded in by -P release)
rem   ..\WeightHandlerStrategy.jar       engine jar (parent dir, koin shaded in)
rem   ..\..\lib\*                        host dependency dir (kotlin / jackson / spring /
rem                                      slf4j / logback / sqlite / javafx ... provided by the host)
rem   mcp-lib\*                          OPTIONAL local dep slot (dev loop only).
rem     A release package has its deps shaded into configUi.jar and ships NO mcp-lib
rem     dir; java silently ignores a wildcard classpath entry that resolves to nothing,
rem     so this single launcher serves both paths:
rem       - release package / installed jar : deps are inside configUi.jar
rem       - fast local deploy (deploy-mcp.bat ships an unshaded jar) : deps live here
rem     Keep it LAST so the real jars always win over this slot.
set CP=configUi.jar;..\WeightHandlerStrategy.jar;..\..\lib\*;mcp-lib\*

java -Dapp.config.file=%~dp0plugin-config.properties -Dlogback.configurationFile=%~dp0logback.xml -cp "%CP%" lin.mcp.McpServerMainKt
if errorlevel 1 pause
