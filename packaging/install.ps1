# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
#  Ki Script Installer (Windows PowerShell)
#
#  Usage:
#      .\install.ps1                                Install to %USERPROFILE%\.ki
#      $env:KI_HOME = "C:\ki"; .\install.ps1        Install to custom location
#
#  What this does:
#      1. Creates the KI_HOME directory structure
#      2. Copies the runtime to KI_HOME\lib\
#      3. Installs ks.cmd and ksr.cmd to KI_HOME\bin\
#      4. Adds KI_HOME\bin to the user PATH
#
#  Requires: PowerShell 5.1+ (built into Windows 10/11)
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$ErrorActionPreference = "Stop"

# ── Configuration ────────────────────────────────────────────────────────────

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $env:KI_HOME) {
    $KiHome = Join-Path $env:USERPROFILE ".ki"
} else {
    $KiHome = $env:KI_HOME
}

# ── Helpers ──────────────────────────────────────────────────────────────────

function Write-Info($msg)  { Write-Host "  i  $msg" -ForegroundColor Cyan }
function Write-Ok($msg)    { Write-Host "  +  $msg" -ForegroundColor Green }
function Write-Warn($msg)  { Write-Host "  !  $msg" -ForegroundColor Yellow }
function Write-Fail($msg)  { Write-Host "  X  $msg" -ForegroundColor Red; exit 1 }

# ── Banner ───────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "  Ki Script Installer" -ForegroundColor White -NoNewline
Write-Host " (Windows)" -ForegroundColor DarkGray
Write-Host ""

# ── Preflight: Java ──────────────────────────────────────────────────────────

Write-Info "Checking for Java..."

$JavaExe = $null

if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $JavaExe = "$env:JAVA_HOME\bin\java.exe"
} else {
    $JavaExe = Get-Command java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
}

if (-not $JavaExe) {
    Write-Fail "Java not found. Ki Script requires Java 21 or later.`n   Install from: https://adoptium.net"
}

# Check version
try {
    $verOutput = & $JavaExe -version 2>&1 | Select-Object -First 1
    if ($verOutput -match '"(\d+)') {
        $javaMajor = [int]$Matches[1]
        if ($javaMajor -lt 21) {
            Write-Warn "Java $javaMajor detected. Ki Script requires Java 21+."
        } else {
            Write-Ok "Java $javaMajor found"
        }
    }
} catch {
    Write-Warn "Could not determine Java version"
}

# ── Preflight: Runtime ───────────────────────────────────────────────────────

$RuntimeJar = Join-Path $ScriptDir "lib\ks-runtime.jar"
if (-not (Test-Path $RuntimeJar)) {
    $RuntimeJar = Join-Path $ScriptDir "build\libs\ks-repl.jar"
    if (-not (Test-Path $RuntimeJar)) {
        Write-Fail "Runtime not found. Expected at $ScriptDir\lib\ks-runtime.jar"
    }
}
Write-Ok "Runtime found"

# ── Create Directories ───────────────────────────────────────────────────────

Write-Info "Installing to $KiHome ..."

New-Item -ItemType Directory -Path "$KiHome\bin" -Force | Out-Null
New-Item -ItemType Directory -Path "$KiHome\lib" -Force | Out-Null

# ── Copy Runtime ─────────────────────────────────────────────────────────────

Copy-Item $RuntimeJar "$KiHome\lib\ks-runtime.jar" -Force
Write-Ok "Runtime installed"

# ── Copy Version Info ────────────────────────────────────────────────────────

$VersionFile = Join-Path $ScriptDir "VERSION"
if (Test-Path $VersionFile) {
    Copy-Item $VersionFile "$KiHome\VERSION" -Force
}

# ── Install Launchers ────────────────────────────────────────────────────────

Copy-Item (Join-Path $ScriptDir "bin\ks.cmd")  "$KiHome\bin\ks.cmd" -Force
Copy-Item (Join-Path $ScriptDir "bin\ksr.cmd") "$KiHome\bin\ksr.cmd" -Force
Write-Ok "Launchers installed"

# Also copy Unix scripts (for WSL / Git Bash)
if (Test-Path (Join-Path $ScriptDir "bin\ks")) {
    Copy-Item (Join-Path $ScriptDir "bin\ks")  "$KiHome\bin\ks" -Force
    Copy-Item (Join-Path $ScriptDir "bin\ksr") "$KiHome\bin\ksr" -Force
}

# ── Update PATH ──────────────────────────────────────────────────────────────

$KiBin = "$KiHome\bin"
$currentPath = [Environment]::GetEnvironmentVariable("PATH", "User")

if ($currentPath -notlike "*$KiBin*") {
    [Environment]::SetEnvironmentVariable("PATH", "$KiBin;$currentPath", "User")
    # Also update current session
    $env:PATH = "$KiBin;$env:PATH"
    Write-Ok "Added $KiBin to user PATH"
} else {
    Write-Info "PATH already configured"
}

# Set KI_HOME if non-default
if ($KiHome -ne (Join-Path $env:USERPROFILE ".ki")) {
    [Environment]::SetEnvironmentVariable("KI_HOME", $KiHome, "User")
    Write-Ok "Set KI_HOME=$KiHome"
}

# ── Done ─────────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "  Ki Script installed successfully!" -ForegroundColor Green
Write-Host ""
Write-Host "  ks   - Run KS scripts     (ks hello.ks)" -ForegroundColor White
Write-Host "  ksr  - Interactive REPL    (ksr)" -ForegroundColor White
Write-Host ""

# Verify
Write-Info "Verify:"
try {
    & "$KiBin\ks.cmd" --version
} catch {
    Write-Warn "Could not verify installation"
}

Write-Host ""
Write-Host "  You may need to restart your terminal for PATH changes to take effect." -ForegroundColor Yellow
Write-Host ""