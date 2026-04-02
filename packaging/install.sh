#!/bin/sh
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
#  Ki Script Installer (Unix — macOS / Linux)
#
#  Installs the Ki Script runtime, `ks` and `ksr` commands.
#
#  Usage:
#      ./install.sh                   Install to ~/.ki (default)
#      KI_HOME=/opt/ki ./install.sh   Install to custom location
#
#  What this does:
#      1. Creates the KI_HOME directory structure
#      2. Copies the runtime JAR to KI_HOME/lib/
#      3. Installs the `ks` and `ksr` launcher scripts to KI_HOME/bin/
#      4. Adds KI_HOME/bin to PATH in shell profile (if not already present)
#
#  Supported shells: bash, zsh, ksh, fish
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

set -e

# ── Configuration ────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
KI_HOME="${KI_HOME:-$HOME/.ki}"
KI_VERSION_FILE="$SCRIPT_DIR/VERSION"

# Colors (disable with NO_COLOR=1)
if [ -z "$NO_COLOR" ] && [ -t 1 ]; then
    BOLD="\033[1m"
    CYAN="\033[36m"
    GREEN="\033[32m"
    RED="\033[31m"
    YELLOW="\033[33m"
    RESET="\033[0m"
else
    BOLD="" CYAN="" GREEN="" RED="" YELLOW="" RESET=""
fi

info()  { printf "${CYAN}ℹ${RESET}  %s\n" "$1"; }
ok()    { printf "${GREEN}✔${RESET}  %s\n" "$1"; }
warn()  { printf "${YELLOW}⚠${RESET}  %s\n" "$1"; }
fail()  { printf "${RED}✘${RESET}  %s\n" "$1" >&2; exit 1; }

# ── Preflight Checks ────────────────────────────────────────────────────────

echo ""
echo "${BOLD}Ki Script Installer${RESET}"
echo ""

# Check for Java
info "Checking for Java..."
JAVA=""
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA="$(command -v java)"
fi

if [ -z "$JAVA" ]; then
    fail "Java not found. Ki Script requires Java 21 or later.\n   Install from: https://adoptium.net"
fi

# Check Java version
JAVA_VER=$("$JAVA" -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/' | cut -d. -f1)
if [ -n "$JAVA_VER" ] && [ "$JAVA_VER" -lt 21 ] 2>/dev/null; then
    warn "Java $JAVA_VER detected. Ki Script requires Java 21+."
    warn "Install from: https://adoptium.net"
else
    ok "Java $JAVA_VER found at $JAVA"
fi

# Check for the runtime JAR in the distribution
RUNTIME_JAR="$SCRIPT_DIR/lib/ks-runtime.jar"
if [ ! -f "$RUNTIME_JAR" ]; then
    # Also check if we're running from a Gradle build output
    RUNTIME_JAR="$SCRIPT_DIR/build/libs/ks-repl.jar"
    if [ ! -f "$RUNTIME_JAR" ]; then
        fail "Runtime not found. Expected at $SCRIPT_DIR/lib/ks-runtime.jar"
    fi
fi
ok "Runtime found: $(basename "$RUNTIME_JAR")"

# ── Create Directory Structure ───────────────────────────────────────────────

info "Installing to $KI_HOME ..."

mkdir -p "$KI_HOME/bin"
mkdir -p "$KI_HOME/lib"

# ── Copy Runtime ─────────────────────────────────────────────────────────────

cp "$RUNTIME_JAR" "$KI_HOME/lib/ks-runtime.jar"
ok "Runtime installed to $KI_HOME/lib/"

# ── Copy Version Info ────────────────────────────────────────────────────────

if [ -f "$KI_VERSION_FILE" ]; then
    cp "$KI_VERSION_FILE" "$KI_HOME/VERSION"
fi

# ── Install Launchers ────────────────────────────────────────────────────────

cp "$SCRIPT_DIR/bin/ks" "$KI_HOME/bin/ks"
cp "$SCRIPT_DIR/bin/ksr" "$KI_HOME/bin/ksr"
chmod +x "$KI_HOME/bin/ks"
chmod +x "$KI_HOME/bin/ksr"
ok "Launchers installed to $KI_HOME/bin/"

# ── Update PATH ──────────────────────────────────────────────────────────────

KI_BIN="$KI_HOME/bin"
PATH_LINE="export PATH=\"\$HOME/.ki/bin:\$PATH\""
# Use the actual KI_HOME if not the default
if [ "$KI_HOME" != "$HOME/.ki" ]; then
    PATH_LINE="export KI_HOME=\"$KI_HOME\"; export PATH=\"$KI_HOME/bin:\$PATH\""
fi

add_to_profile() {
    profile="$1"
    if [ -f "$profile" ]; then
        if grep -q '.ki/bin' "$profile" 2>/dev/null || grep -q 'KI_HOME' "$profile" 2>/dev/null; then
            info "PATH already configured in $(basename "$profile")"
            return 0
        fi
    fi

    echo "" >> "$profile"
    echo "# Ki Script" >> "$profile"
    echo "$PATH_LINE" >> "$profile"
    ok "Added to $profile"
    return 1
}

updated_profile=false

# Detect current shell and update its profile
CURRENT_SHELL="$(basename "${SHELL:-/bin/sh}")"

case "$CURRENT_SHELL" in
    zsh)
        add_to_profile "$HOME/.zshrc" && true || updated_profile=true
        ;;
    bash)
        # macOS uses .bash_profile; Linux uses .bashrc
        if [ "$(uname)" = "Darwin" ]; then
            add_to_profile "$HOME/.bash_profile" && true || updated_profile=true
        else
            add_to_profile "$HOME/.bashrc" && true || updated_profile=true
        fi
        ;;
    ksh)
        add_to_profile "$HOME/.kshrc" && true || updated_profile=true
        ;;
    fish)
        FISH_CONFIG="$HOME/.config/fish/config.fish"
        if [ -f "$FISH_CONFIG" ]; then
            if ! grep -q '.ki/bin' "$FISH_CONFIG" 2>/dev/null; then
                echo "" >> "$FISH_CONFIG"
                echo "# Ki Script" >> "$FISH_CONFIG"
                echo "set -gx PATH \$HOME/.ki/bin \$PATH" >> "$FISH_CONFIG"
                ok "Added to config.fish"
                updated_profile=true
            else
                info "PATH already configured in config.fish"
            fi
        fi
        ;;
    *)
        warn "Unrecognized shell: $CURRENT_SHELL"
        warn "Add this to your shell profile manually:"
        echo "    $PATH_LINE"
        ;;
esac

# ── Done ─────────────────────────────────────────────────────────────────────

echo ""
echo "${BOLD}${GREEN}Ki Script installed successfully!${RESET}"
echo ""
echo "  ${BOLD}ks${RESET}   — Run KS scripts     (ks hello.ks)"
echo "  ${BOLD}ksr${RESET}  — Interactive REPL    (ksr)"
echo ""

if [ "$updated_profile" = true ]; then
    echo "  ${YELLOW}Restart your shell or run:${RESET}"
    echo "    source ~/.$(echo "$CURRENT_SHELL")rc"
    echo ""
fi

# Quick verification
if [ -x "$KI_BIN/ks" ]; then
    info "Verify: $KI_BIN/ks --version"
    "$KI_BIN/ks" --version 2>/dev/null || true
fi

echo ""