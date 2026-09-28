@echo off
rem ============================================================
rem  release\package.bat - build the two distributable archives
rem
rem  Usage:
rem    release\package.bat                 maven build (offline) then package
rem    release\package.bat fast            skip maven, package target\*.jar as-is
rem    release\package.bat nopause         no "press any key" at the end (CI)
rem    args combine:  release\package.bat fast nopause
rem
rem  Output in release\dist\:
rem    <name>-<ver>-<stamp>-plugin.zip      unzip into the HOST ROOT (the folder holding
rem                                          hs-script.exe) -> runs as-is:
rem                                          -> plugin\WeightHandlerStrategy.jar
rem                                          -> plugin\WeightHandlerStrategy\configUi.jar + launchers + config
rem                                          -> data\cardgroup\*  (hs_cards.db is host-provided, never shipped)
rem    <name>-<ver>-<stamp>-mcp-skill.zip   MCP client config sample + the AI config-generation SOP skill
rem
rem  ------------------------------------------------------------
rem  KEEP THIS FILE ASCII-ONLY.
rem  cmd reads a .bat line by line using the active ANSI codepage, so UTF-8 non-ASCII
rem  text (Chinese comments, Chinese file names) gets mis-decoded and executed as
rem  commands. Messages here are English; Chinese user documentation lives in
rem  release\assets\*.txt and is copied byte-wise (never echoed through cmd).
rem  ------------------------------------------------------------
rem  Why this script exists:
rem   1) The engine jar is a HOST PLUGIN: the host JVM loads it and already provides
rem      lib\*.jar, so it only shades what the host lacks (koin).
rem   2) configUi.jar is used two ways - launched standalone by mcp.bat / ui.bat
rem      (classpath = configUi.jar; engine jar; host lib\*) and loaded by the engine
rem      as an SPI extension inside the host JVM. The host lib does NOT provide the
rem      MCP SDK / koin / victools / HikariCP / itu / snakeyaml / stately / reactor,
rem      so the configUi "release" profile shades them in. Without -P release the jar
rem      silently degrades - that is how the hand-copied "mcp-lib" dir came to exist
rem      next to the deployed jar (a second, drifting copy of host lib).
rem   3) mcp.bat / ui.bat / logback.xml used to live only inside the deployed folder,
rem      i.e. outside version control - a fresh release could not reproduce them.
rem      They now live in release\assets\ (versioned).
rem   4) Division of labour - keep the two scripts separate, never fold them together:
rem        deploy-mcp.bat : HIGH-frequency local deploy -> compile, NO shade, stays fast.
rem                         Its asset dir keeps a mcp-lib\* dep slot so an unshaded jar
rem                         still runs (see the classpath notes in release\assets\mcp.bat).
rem        release\package.bat : LOW-frequency release -> -P release shades everything
rem                         in, so the archive installs into a bare host root.
rem      Putting -P release into the frequent loop would pay the shade cost on every
rem      deploy for zero benefit - the shaded jar only matters when you ship.
rem   5) Shade-list contract: judge "what the host already provides" ONLY from a real
rem      host installation's own lib\. A local test rig with hand-added jars is not a
rem      contract - deriving build settings from it is how lib drift starts.
rem   6) Environment-neutral by default: no absolute path of the packaging machine is
rem      baked into the archives. Everything is relative, so the archive works under
rem      ANY host root; fill HOST_ROOT below only to pre-fill mcp.json for your own use.
rem ============================================================

chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0.."
set "ROOT=%CD%\"
set "LOG=%ROOT%release\package.log"

rem ==================== CONFIG (single source of truth) ====================
rem Every path decision lives HERE and only here. The archive layout, the generated
rem plugin-config.properties and the generated mcp.json are all produced from the
rem values below - there is no second copy of these paths to keep in sync.
rem
rem Host root. Leave EMPTY to stay environment-neutral: mcp.json then carries the
rem placeholder token, and the docs simply say "extract into the host root".
rem Fill it in only if you want mcp.json pre-filled with one specific installation.
set "HOST_ROOT="
rem Archive layout - NOT a free choice, it is the host/engine contract:
rem  - the host only reads plugin jars from  <host root>\plugin\
rem  - the asset dir must be named WeightHandlerStrategy and sit next to the engine
rem    jar - the engine scans  <engine jar dir>\WeightHandlerStrategy  for SPI jars
set "PLUGIN_SUBDIR=plugin"
set "ASSET_DIR_NAME=WeightHandlerStrategy"
set "ASSET_REL=%PLUGIN_SUBDIR%\%ASSET_DIR_NAME%"
rem Name of the runtime config file carried in the asset dir (read by mcp.bat/ui.bat).
set "CFG_FILE=plugin-config.properties"
rem FALLBACK values for that file - used only when the source env has no copy of it.
rem They are RELATIVE to the asset dir because mcp.bat / ui.bat cd there first. In ENV
rem mode the real values are read back out of the shipped config file, and those then
rem drive where the staged assets land - so what the config points at and where the
rem files sit can never disagree.
set "CFG_DB=weightHandlerStrategy.db"
set "CFG_HS_CARDS=../../hs_cards.db"
set "CFG_CARDGROUP=../../data/cardgroup"
rem ---- PAYLOAD SOURCE (launchers + runtime config) ----
rem ENV  = take plugin-config.properties / mcp.bat / ui.bat / logback.xml from
rem        DATA_SRC_ROOT, i.e. the environment you actually run and have validated.
rem        Each file is also diffed against the repo template in release\assets\ and a
rem        difference is reported, so the tracked baseline never goes silently stale.
rem REPO = use only the version-controlled templates; the config file is then
rem        generated from the fallback values above.
set "PAYLOAD_SRC=ENV"
rem ---- DATA SOURCE: where the DATA to ship is read from ----
rem Point it at the environment you actually AUTHOR configuration in - normally the
rem deployed host you drive over MCP: its asset dir holds the live engine db and the
rem cardgroup dir holds the cardgroup files. That env uses the same layout as the
rem archive, so the source paths are derived from ASSET_REL + the config values.
rem Leave EMPTY to ship the repo working copy instead - repo root engine db plus
rem repo data\cardgroup, i.e. the versioned baseline, NOT what you configured via MCP.
set "DATA_SRC_ROOT=F:\myApp\HBuddy_2"
set "SRC_ASSET_DIR="
if defined DATA_SRC_ROOT set "SRC_ASSET_DIR=%DATA_SRC_ROOT%\%ASSET_REL%"
if defined DATA_SRC_ROOT (
    set "SRC_DB=%SRC_ASSET_DIR%\%CFG_DB%"
    set "SRC_CARDGROUP=%SRC_ASSET_DIR%\%CFG_CARDGROUP%"
) else (
    set "SRC_DB=%ROOT%%CFG_DB%"
    set "SRC_CARDGROUP=%ROOT%data\cardgroup"
)
rem Normalize .\ and ..\ away so the log lines and the failure messages read cleanly.
for %%I in ("%SRC_DB%") do set "SRC_DB=%%~fI"
for %%I in ("%SRC_CARDGROUP%") do set "SRC_CARDGROUP=%%~fI"
rem What to bundle: yes / no. hs_cards.db is deliberately NOT bundleable - the host
rem installation ships it (and its engine process reads <host root>\hs_cards.db).
set "BUNDLE_ENGINE_DB=yes"
set "BUNDLE_CARDGROUP=yes"
set "BUNDLE_EXT_SAMPLE=no"
set "SRC_EXT_JAR=%ROOT%extWeightHandler\target\extWeightHandler.jar"
rem Skill source: .agents\skills\ is the canonical store, .codebuddy\skills\ is its link
rem target. Both live outside git, so fall back to the second one if the first is absent.
set "SKILL_SRC=%ROOT%.agents\skills\use-ai-config-generator"
if not exist "%SKILL_SRC%\SKILL.md" set "SKILL_SRC=%ROOT%.codebuddy\skills\use-ai-config-generator"
set "ASSET_TPL=%ROOT%release\assets"
set "OUT_DIR=%ROOT%release\dist"
set "PKG_NAME=Deck-Plugin-Market"
set "PKG_VERSION=1.0.0"
rem Placeholder for mcp.json when HOST_ROOT is empty. ASCII only - cmd echoes it.
set "MCP_PLUGIN_DIR_TOKEN=<PLUGIN_DIR>"
rem =======================================================================

set "MODE=full"
set "NOPAUSE="
if not "%~1"=="" for %%A in (%*) do (
    if /i "%%A"=="fast" set "MODE=fast"
    if /i "%%A"=="nopause" set "NOPAUSE=1"
)

echo ============================================================
echo  release\package.bat   mode=%MODE%
echo  repo root : %ROOT%
if defined HOST_ROOT echo  host root : %HOST_ROOT%
if not defined HOST_ROOT echo  host root : not set - archives stay environment-neutral
echo ------------------------------------------------------------
if defined DATA_SRC_ROOT echo  data source : %DATA_SRC_ROOT%
if not defined DATA_SRC_ROOT echo  data source : repo working copy
if /i "%PAYLOAD_SRC%"=="ENV" echo  payload src : env asset dir - falls back per file
if /i not "%PAYLOAD_SRC%"=="ENV" echo  payload src : repo templates release\assets
if /i "%BUNDLE_ENGINE_DB%"=="yes" echo    engine db : %SRC_DB%
if /i "%BUNDLE_CARDGROUP%"=="yes" echo    cardgroup : %SRC_CARDGROUP%
echo ============================================================
echo.

if not exist "%ASSET_TPL%\mcp.bat" (
    echo [FAILED] asset templates not found: %ASSET_TPL%\mcp.bat
    goto :fail
)

rem Pre-flight the data sources BEFORE the maven build: a wrong DATA_SRC_ROOT must not
rem surface two minutes later, at staging time.
if /i "%BUNDLE_ENGINE_DB%"=="yes" if not exist "%SRC_DB%" (
    echo [FAILED] engine db source not found: %SRC_DB%
    echo          fix DATA_SRC_ROOT in the CONFIG block, or set BUNDLE_ENGINE_DB=no.
    goto :fail
)
if /i "%BUNDLE_CARDGROUP%"=="yes" if not exist "%SRC_CARDGROUP%\" (
    echo [FAILED] cardgroup source dir not found: %SRC_CARDGROUP%
    echo          fix DATA_SRC_ROOT in the CONFIG block, or set BUNDLE_CARDGROUP=no.
    goto :fail
)

rem ---------- locate maven / jdk (full build only) ----------
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
if not defined JAVA_HOME if exist "%USERPROFILE%\.jdks" for /d %%J in ("%USERPROFILE%\.jdks\*") do if not defined JAVA_HOME if exist "%%J\bin\javac.exe" set "JAVA_HOME=%%J"
if not defined JAVA_HOME for /d %%J in ("C:\Program Files\Java\jdk*") do if not defined JAVA_HOME if exist "%%J\bin\javac.exe" set "JAVA_HOME=%%J"
if not defined JAVA_HOME for /d %%J in ("C:\Program Files\Eclipse Adoptium\jdk*") do if not defined JAVA_HOME if exist "%%J\bin\javac.exe" set "JAVA_HOME=%%J"

set "ENGINE_JAR=%ROOT%WeightHanderStrategy\target\WeightHandlerStrategy.jar"
set "CONFIGUI_JAR=%ROOT%configUi\target\configUi.jar"

if /i "%MODE%"=="fast" goto :package

rem ============================================================
rem  [1/3] build
rem ============================================================
if not defined MVN (
    echo [FAILED] maven not found on PATH and no known fallback matched.
    echo          install maven / set MAVEN_HOME, or run: release\package.bat fast
    goto :fail
)
if not defined JAVA_HOME (
    echo [FAILED] JAVA_HOME not found and no JDK fallback matched. set JAVA_HOME and retry.
    goto :fail
)
echo [env] maven    : %MVN%
echo [env] JAVA_HOME: %JAVA_HOME%
echo.
echo ------------------------------------------------------------
echo  mvn -pl WeightHanderStrategy -am install -P release   ^(shades koin^)
echo ------------------------------------------------------------
call "%MVN%" -o -pl WeightHanderStrategy -am install -P release -DskipTests
if errorlevel 1 (
    echo [FAILED] engine build failed.
    goto :fail
)
echo.
echo ------------------------------------------------------------
echo  mvn -pl configUi package -P release   ^(shades mcp sdk / koin / victools ...^)
echo ------------------------------------------------------------
call "%MVN%" -o -pl configUi package -P release -DskipTests
if errorlevel 1 (
    echo [FAILED] configUi build failed.
    goto :fail
)
if /i "%BUNDLE_EXT_SAMPLE%"=="yes" (
    echo.
    echo ------------------------------------------------------------
    echo  mvn -pl extWeightHandler package   ^(SPI sample extension^)
    echo ------------------------------------------------------------
    call "%MVN%" -o -pl extWeightHandler package -DskipTests
    if errorlevel 1 (
        echo [FAILED] extWeightHandler build failed.
        goto :fail
    )
)

rem ============================================================
:package
echo.
echo ============================================================
echo  [2/3] stage archives
echo ============================================================
if not exist "%ENGINE_JAR%" (
    echo [FAILED] engine jar missing: %ENGINE_JAR%
    echo          run without "fast" to build it first.
    goto :fail
)
if not exist "%CONFIGUI_JAR%" (
    echo [FAILED] configUi jar missing: %CONFIGUI_JAR%
    echo          run without "fast" to build it first.
    goto :fail
)

for /f "delims=" %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"') do set "STAMP=%%I"
if not defined STAMP set "STAMP=unknown"
set "BASE=%PKG_NAME%-%PKG_VERSION%-%STAMP%"

if exist "%OUT_DIR%" rd /s /q "%OUT_DIR%"
set "STAGE_MAIN=%OUT_DIR%\stage-plugin"
set "STAGE_MCP=%OUT_DIR%\stage-mcp"
mkdir "%STAGE_MAIN%\%ASSET_REL%" >nul 2>&1
if not exist "%STAGE_MAIN%\%ASSET_REL%" (
    echo [FAILED] cannot create staging dir: %STAGE_MAIN%\%ASSET_REL%
    goto :fail
)

rem ---------- archive 1: plugin (extract into the host root) ----------
call :putfile "%ENGINE_JAR%"                         "%STAGE_MAIN%\%PLUGIN_SUBDIR%\WeightHandlerStrategy.jar" || goto :fail
call :putfile "%CONFIGUI_JAR%"                       "%STAGE_MAIN%\%ASSET_REL%\configUi.jar"                   || goto :fail
rem Launchers + log config: the deployed file when available (that is the one you
rem actually run), otherwise the repo template. Divergence from the repo template is
rem reported rather than ignored, so the tracked baseline can not rot unnoticed.
call :pickfile "mcp.bat"     "mcp.bat"          "%STAGE_MAIN%\%ASSET_REL%\mcp.bat"      || goto :fail
call :pickfile "ui.bat"      "ui.bat"           "%STAGE_MAIN%\%ASSET_REL%\ui.bat"       || goto :fail
call :pickfile "logback.xml" "logback.mcp.xml"  "%STAGE_MAIN%\%ASSET_REL%\logback.xml"  || goto :fail
call :putfile "%ASSET_TPL%\README-INSTALL.txt"  "%STAGE_MAIN%\README-INSTALL.txt"       || goto :fail

rem ---------- runtime config: deployed copy when available, else generated ----------
set "PKG_CFG=%STAGE_MAIN%\%ASSET_REL%\%CFG_FILE%"
set "ENV_CFG="
if /i "%PAYLOAD_SRC%"=="ENV" if defined SRC_ASSET_DIR if exist "%SRC_ASSET_DIR%\%CFG_FILE%" set "ENV_CFG=%SRC_ASSET_DIR%\%CFG_FILE%"
if defined ENV_CFG (
    call :putfile "!ENV_CFG!" "%PKG_CFG%" || goto :fail
    echo        source: env !ENV_CFG!
) else (
    rem ASCII-only comments: cmd echoes this text and a .bat must not contain non-ASCII.
    (
        echo # Paths used by mcp.bat / ui.bat. Relative values resolve against THIS directory,
        echo # because both scripts cd here before starting java - so the folder stays movable.
        echo # The host engine process never reads this file: it derives its db path from its
        echo # own working directory as plugin\WeightHandlerStrategy\weightHandlerStrategy.db.
        echo # Keep the values relative unless a client starts java directly with a working
        echo # directory you cannot control - in that case use absolute paths.
        echo database.path=!CFG_DB!
        echo hs_cards.db.path=!CFG_HS_CARDS!
        echo cardgroup.dir.path=!CFG_CARDGROUP!
    ) > "%PKG_CFG%"
    echo        source: generated from the CFG_* fallbacks
)
call :require "%PKG_CFG%" || goto :fail
echo   [ok] %PKG_CFG%

rem The shipped config file is the contract: read the paths back OUT of it, so the staged
rem data always lands exactly where the shipped config points.
for /f "usebackq tokens=1,* delims==" %%A in ("%PKG_CFG%") do (
    if /i "%%A"=="database.path"      set "CFG_DB=%%B"
    if /i "%%A"=="hs_cards.db.path"   set "CFG_HS_CARDS=%%B"
    if /i "%%A"=="cardgroup.dir.path" set "CFG_CARDGROUP=%%B"
)
rem Guard: values must stay relative, or the archive bakes in one machine's layout.
for /f "usebackq tokens=1,* delims==" %%A in ("%PKG_CFG%") do (
    set "V=%%B"
    if not "!V!"=="" if not "!V!"=="!V::=!" echo   [WARN] %CFG_FILE% has an absolute value: %%A=!V!
)
rem Guard: keys the config layer expects, and the engine db name the host derives itself.
for %%K in (database.path hs_cards.db.path cardgroup.dir.path) do (
    findstr /b /c:"%%K=" "%PKG_CFG%" >nul || echo   [WARN] %CFG_FILE% has no %%K - the built-in default applies instead
)
if /i not "!CFG_DB!"=="weightHandlerStrategy.db" echo   [WARN] %CFG_FILE% sets database.path=!CFG_DB! - the host engine still reads weightHandlerStrategy.db in the asset dir

if /i "%BUNDLE_EXT_SAMPLE%"=="yes" (
    call :putfile "%SRC_EXT_JAR%" "%STAGE_MAIN%\%ASSET_REL%\extWeightHandler.jar" || goto :fail
)
if /i "%BUNDLE_ENGINE_DB%"=="yes" (
    call :snapshotdb "%SRC_DB%" "%STAGE_MAIN%\%ASSET_REL%\%CFG_DB%" || goto :fail
)
if /i "%BUNDLE_CARDGROUP%"=="yes" (
    if not exist "%SRC_CARDGROUP%" (
        echo [FAILED] cardgroup source dir missing: %SRC_CARDGROUP%
        goto :fail
    )
    rem Destination comes straight from CFG_CARDGROUP = asset dir\..\..\data\cardgroup,
    rem so the shipped files always land exactly where the shipped config points.
    if not exist "%STAGE_MAIN%\%ASSET_REL%\%CFG_CARDGROUP%\" mkdir "%STAGE_MAIN%\%ASSET_REL%\%CFG_CARDGROUP%" >nul 2>&1
    rem "*.cardgroup" only - never ship the .bak-* leftovers sitting next to them
    xcopy /y /q "%SRC_CARDGROUP%\*.cardgroup" "%STAGE_MAIN%\%ASSET_REL%\%CFG_CARDGROUP%\" >nul
    if errorlevel 1 (
        echo [FAILED] copy cardgroup failed: %SRC_CARDGROUP%
        goto :fail
    )
    echo   [ok] %STAGE_MAIN%\%ASSET_REL%\%CFG_CARDGROUP%\*.cardgroup
)

rem ---------- archive 2: MCP config sample + SOP skill ----------
mkdir "%STAGE_MCP%\skills" >nul 2>&1
call :putfile "%ASSET_TPL%\README-MCP-SKILL.txt" "%STAGE_MCP%\README-MCP-SKILL.txt" || goto :fail
if not exist "%SKILL_SRC%\SKILL.md" (
    echo [FAILED] skill source not found: %SKILL_SRC%\SKILL.md
    echo          the skill lives in .agents\skills\ - or .codebuddy\skills\ - working copy.
    goto :fail
)
xcopy /y /q /e /i "%SKILL_SRC%" "%STAGE_MCP%\skills\use-ai-config-generator\" >nul
if errorlevel 1 (
    echo [FAILED] copy skill failed: %SKILL_SRC%
    goto :fail
)
echo   [ok] %STAGE_MCP%\skills\use-ai-config-generator\
set "MCP_BAT_ABS=%MCP_PLUGIN_DIR_TOKEN%\mcp.bat"
if defined HOST_ROOT set "MCP_BAT_ABS=%HOST_ROOT%\%ASSET_REL%\mcp.bat"
set "MCP_BAT_JSON=!MCP_BAT_ABS:\=\\!"
(
    echo {
    echo   "mcpServers": {
    echo     "deck-plugin-market": {
    echo       "command": "cmd",
    echo       "args": ["/c", "!MCP_BAT_JSON!"],
    echo       "description": "Deck-Plugin-Market strategy config server"
    echo     }
    echo   }
    echo }
) > "%STAGE_MCP%\mcp.json"
call :require "%STAGE_MCP%\mcp.json" || goto :fail
echo   [ok] %STAGE_MCP%\mcp.json

rem ============================================================
echo.
echo ============================================================
echo  [3/3] zip
echo ============================================================
set "ZIP_PLUGIN=%OUT_DIR%\%BASE%-plugin.zip"
set "ZIP_MCP=%OUT_DIR%\%BASE%-mcp-skill.zip"
call :ziptree "%STAGE_MAIN%" "%ZIP_PLUGIN%" || goto :fail
call :ziptree "%STAGE_MCP%"  "%ZIP_MCP%"    || goto :fail

echo.
echo ------------------------------------------------------------
echo  staged content
echo ------------------------------------------------------------
cd /d "%STAGE_MAIN%"
powershell -NoProfile -Command "Get-ChildItem -Recurse -File | ForEach-Object { '  plugin-zip\' + $_.FullName.Substring((Get-Location).Path.Length+1) }"
cd /d "%STAGE_MCP%"
powershell -NoProfile -Command "Get-ChildItem -Recurse -File | ForEach-Object { '  mcp-zip\' + $_.FullName.Substring((Get-Location).Path.Length+1) }"
cd /d "%ROOT%"

echo.
echo ============================================================
echo  done.
echo    1^) %ZIP_PLUGIN%
echo       extract into the HOST ROOT - the folder holding hs-script.exe
echo       then restart the host, then reconnect the MCP server
echo    2^) %ZIP_MCP%
echo       MCP config sample ^(mcp.json^) + SOP skill for the AI client
echo ============================================================
for %%F in ("%ZIP_PLUGIN%" "%ZIP_MCP%") do echo        %%~zF bytes  %%~nxF
echo [package] OK %DATE% %TIME% mode=%MODE% >> "%LOG%"
echo.
if not defined NOPAUSE pause
endlocal
exit /b 0

rem ============================================================
rem  helpers
rem ============================================================
:pickfile
rem %~1 = file name inside the source asset dir, %~2 = repo template name, %~3 = dest.
rem Prefer the deployed file - that is the one actually running in DATA_SRC_ROOT - and
rem report when it differs from the tracked template, so release\assets\ can not go
rem silently stale. Falls back to the repo template when the env has no such file.
set "ENVFILE="
if /i "%PAYLOAD_SRC%"=="ENV" if defined SRC_ASSET_DIR if exist "%SRC_ASSET_DIR%\%~1" set "ENVFILE=%SRC_ASSET_DIR%\%~1"
if not defined ENVFILE (
    call :putfile "%ASSET_TPL%\%~2" "%~3" || exit /b 1
    echo        source: repo template release\assets\%~2
    goto :pickfile_done
)
call :putfile "!ENVFILE!" "%~3" || exit /b 1
echo        source: env !ENVFILE!
rem Text compare - byte compare would flag pure line-ending differences as "changed".
fc /l "%ASSET_TPL%\%~2" "%~3" >nul 2>&1
if errorlevel 1 echo        [note] text differs from the tracked template release\assets\%~2 - keep the template up to date
:pickfile_done
rem Release safety: a picked file must not carry this machine's absolute paths into the
rem archive, or "extract into the host root" only works on the packaging machine.
powershell -NoProfile -Command "if (Select-String -LiteralPath '%~3' -Pattern ':\\' -Quiet) { exit 1 } else { exit 0 }" >nul 2>&1
if errorlevel 1 echo        [WARN] %~1 contains an absolute path - a release must stay relocatable
exit /b 0

:snapshotdb
rem %~1 = source db, %~2 = destination file.
rem The source db normally lives in a running environment and may be held open, and a
rem plain file copy of a db caught mid-write can be torn. sqlite3's online backup takes
rem a consistent snapshot under concurrency, so prefer it and fall back to a file copy.
set "SQ3="
for %%C in (sqlite3.exe sqlite3) do (
    if not defined SQ3 (
        for /f "delims=" %%P in ('where %%C 2^>nul') do if not defined SQ3 set "SQ3=%%P"
    )
)
if defined SQ3 (
    if exist "%~2" del /q "%~2"
    "%SQ3%" "%~1" ".backup '%~2'" >nul 2>&1
    if exist "%~2" (
        echo   [ok] %~2
        echo        source snapshot taken with sqlite3 .backup
        exit /b 0
    )
    echo   [warn] sqlite3 .backup produced no file - falling back to a file copy
)
if exist "%~1-wal" echo   [WARN] source db has a -wal sidecar - a plain copy may lose un-checkpointed writes
if exist "%~1-shm" echo   [WARN] source db has a -shm sidecar - close the host/MCP process before packaging
call :putfile "%~1" "%~2"
if errorlevel 1 exit /b 1
exit /b 0

:putfile
rem %~1 = source file, %~2 = destination file
if not exist "%~1" (
    echo [FAILED] source missing: %~1
    exit /b 1
)
copy /y "%~1" "%~2" >nul
if errorlevel 1 (
    echo [FAILED] copy failed: %~1 to %~2
    exit /b 1
)
echo   [ok] %~2
exit /b 0

:require
if not exist "%~1" (
    echo [FAILED] expected output missing: %~1
    exit /b 1
)
exit /b 0

:ziptree
rem %~1 = source dir, %~2 = destination zip  (zip root = content of %~1)
if not exist "%~1\" (
    echo [FAILED] zip source missing: %~1
    exit /b 1
)
pushd "%~1"
if exist "%~2" del /q "%~2"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::CreateFromDirectory('%CD%','%~f2',[System.IO.Compression.CompressionLevel]::Optimal,$false)"
popd
if not exist "%~2" (
    echo [FAILED] zip creation failed: %~2
    exit /b 1
)
echo   [ok] %~2
exit /b 0

:fail
echo.
echo ============================================================
echo  PACKAGE FAILED - see messages above.
echo ============================================================
echo [package] FAILED %DATE% %TIME% mode=%MODE% >> "%LOG%"
echo.
if not defined NOPAUSE pause
endlocal
exit /b 1
