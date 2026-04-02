@echo off
rem ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
rem  ks.cmd — Ki Script Launcher (Windows)
rem
rem  Usage:
rem      ks file.ks              Run a script
rem      ks file                 Run file.ks (extension auto-appended)
rem      ks C:\path\to\file.ks   Full path
rem      ks --version            Show version
rem      ks --help               Show help
rem
rem  Environment:
rem      KI_HOME        Installation root (default: %USERPROFILE%\.ki)
rem      KI_JAVA_HOME   Override JVM location
rem      KS_OPTS        Extra JVM options
rem      NO_COLOR       Disable ANSI color output
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
    "%JAVA%" -jar "%KS_RUNTIME%" --version
    exit /b %errorlevel%
)
if "%~1"=="-v" (
    "%JAVA%" -jar "%KS_RUNTIME%" --version
    exit /b %errorlevel%
)

if "%~1"=="--help" goto :show_help
if "%~1"=="-h" goto :show_help

if "%~1"=="" (
    echo Usage: ks ^<file[.ks]^> [file2[.ks] ...] 1>&2
    echo        ks --help for more information 1>&2
    exit /b 1
)

rem ── Launch ─────────────────────────────────────────────────────────────────
"%JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 %KS_OPTS% -cp "%KS_RUNTIME%" io.kixi.ks.Run %*
exit /b %errorlevel%

:show_help
echo Ki Script — https://github.com/kixi-io
echo.
echo Usage:
echo     ks ^<file[.ks]^> [file2[.ks] ...]    Run one or more KS scripts
echo     ks --version                          Show version
echo     ks --help                             Show this help
echo.
echo Options:
echo     --no-color    Disable ANSI color output
echo     --debug       Enable debug output
echo.
echo Environment:
echo     KI_HOME          Installation root (default: %%USERPROFILE%%\.ki)
echo     KI_JAVA_HOME     Override JVM location
echo     KS_OPTS          Extra JVM options
echo     NO_COLOR         Disable color
echo.
echo Examples:
echo     ks hello.ks              Run hello.ks in current directory
echo     ks hello                 Same (auto-appends .ks)
echo     ks C:\scripts\report.ks  Run with full path
echo     ks test1.ks test2.ks     Run multiple scripts
exit /b 0