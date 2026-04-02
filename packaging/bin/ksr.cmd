@echo off
rem ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
rem  ksr.cmd — Ki Script REPL (Windows)
rem
rem  Usage:
rem      ksr                  Start the REPL
rem      ksr --no-color       Start without ANSI colors
rem      ksr --version        Show version
rem ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

setlocal enabledelayedexpansion

rem ── Resolve KI_HOME ────────────────────────────────────────────────────────
if "%KI_HOME%"=="" set "KI_HOME=%USERPROFILE%\.ki"

rem ── Locate runtime ─────────────────────────────────────────────────────────
set "KS_RUNTIME=%KI_HOME%\lib\ks-runtime.jar"
if not exist "%KS_RUNTIME%" (
    echo Error: Ki Script runtime not found at %KS_RUNTIME% 1>&2
    echo Run the Ki Script installer or set KI_HOME to your installation directory. 1>&2
    exit /b 1
)

rem ── Locate Java ────────────────────────────────────────────────────────────
set "JAVA="

if defined KI_JAVA_HOME (
    if exist "%KI_JAVA_HOME%\bin\java.exe" (
        set "JAVA=%KI_JAVA_HOME%\bin\java.exe"
        goto :java_found
    )
)

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA=%JAVA_HOME%\bin\java.exe"
        goto :java_found
    )
)

where java >nul 2>&1
if %errorlevel% equ 0 (
    set "JAVA=java"
    goto :java_found
)

echo Error: Java not found. 1>&2
echo Ki Script requires Java 21 or later. 1>&2
echo Set JAVA_HOME or KI_JAVA_HOME, or add java to your PATH. 1>&2
exit /b 1

:java_found

rem ── Handle flags ───────────────────────────────────────────────────────────
if "%~1"=="--version" (
    "%JAVA%" -jar "%KS_RUNTIME%" --repl-version
    exit /b %errorlevel%
)
if "%~1"=="-v" (
    "%JAVA%" -jar "%KS_RUNTIME%" --repl-version
    exit /b %errorlevel%
)

if "%~1"=="--help" goto :show_help
if "%~1"=="-h" goto :show_help

rem ── Launch REPL ────────────────────────────────────────────────────────────
"%JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 %KS_OPTS% -cp "%KS_RUNTIME%" io.kixi.ks.tools.ReplKt %*
exit /b %errorlevel%

:show_help
echo Ki Script REPL — https://github.com/kixi-io
echo.
echo Usage:
echo     ksr                  Start the interactive REPL
echo     ksr --no-color       Disable ANSI colors
echo     ksr --debug          Enable debug output
echo     ksr --version        Show version
echo     ksr --help           Show this help
echo.
echo REPL Commands (once inside):
echo     :help  :h            Show REPL help
echo     :quit  :q            Exit
echo     :reset :r            Reset interpreter state
echo     :env   :e            Show defined variables
echo     :type  :t ^<expr^>     Show expression type
echo     :clear :cls          Clear screen
exit /b 0