#!/bin/sh
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
#  ks — Ki Script Launcher
#
#  Runs KS script files (.ks) and compiled KS files (.ksc).
#
#  Usage:
#      ks <file.ks>              Run a script
#      ks <file>                 Run file.ks (extension auto-appended)
#      ks ~/path/to/script.ks   Absolute or relative paths
#      ks file1.ks file2.ks     Multiple files (sequential)
#      ks --version              Show version
#      ks --help                 Show help
#
#  Shebang:
#      #!/usr/bin/env ks
#      say "Hello from KS!"
#
#  Environment:
#      KI_HOME        Installation root (default: ~/.ki)
#      KI_JAVA_HOME   Override JVM location
#      KS_OPTS        Extra JVM options (e.g., -Xmx512m)
#      NO_COLOR        Disable ANSI color output
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

set -e

# ── Resolve KI_HOME ──────────────────────────────────────────────────────────
if [ -z "$KI_HOME" ]; then
    # Default: ~/.ki
    KI_HOME="$HOME/.ki"
fi

# ── Locate the runtime ───────────────────────────────────────────────────────
KI_LIB="$KI_HOME/lib"
KS_RUNTIME="$KI_LIB/ks-runtime.jar"

if [ ! -f "$KS_RUNTIME" ]; then
    echo "Error: Ki Script runtime not found at $KS_RUNTIME" >&2
    echo "Run the Ki Script installer or set KI_HOME to your installation directory." >&2
    exit 1
fi

# ── Locate Java ──────────────────────────────────────────────────────────────
find_java() {
    # 1. KI_JAVA_HOME (explicit override)
    if [ -n "$KI_JAVA_HOME" ] && [ -x "$KI_JAVA_HOME/bin/java" ]; then
        echo "$KI_JAVA_HOME/bin/java"
        return
    fi

    # 2. JAVA_HOME
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "$JAVA_HOME/bin/java"
        return
    fi

    # 3. java on PATH
    if command -v java >/dev/null 2>&1; then
        command -v java
        return
    fi

    echo ""
}

JAVA=$(find_java)
if [ -z "$JAVA" ]; then
    echo "Error: Java not found." >&2
    echo "Ki Script requires Java 21 or later." >&2
    echo "Set JAVA_HOME or KI_JAVA_HOME, or add java to your PATH." >&2
    exit 1
fi

# ── Version check (optional, warn only) ─────────────────────────────────────
check_java_version() {
    version=$("$JAVA" -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/' | cut -d. -f1)
    if [ -n "$version" ] && [ "$version" -lt 21 ] 2>/dev/null; then
        echo "Warning: Java $version detected. Ki Script requires Java 21+." >&2
    fi
}
check_java_version

# ── Handle flags ─────────────────────────────────────────────────────────────
case "${1:-}" in
    --version|-v)
        exec "$JAVA" -jar "$KS_RUNTIME" --version
        ;;
    --help|-h)
        cat <<'HELP'
Ki Script — https://github.com/kixi-io

Usage:
    ks <file[.ks]> [file2[.ks] ...]    Run one or more KS scripts
    ks --version                        Show version
    ks --help                           Show this help

Options:
    --no-color    Disable ANSI color output
    --debug       Enable debug output

Environment:
    KI_HOME          Installation root (default: ~/.ki)
    KI_JAVA_HOME     Override JVM location
    KS_OPTS          Extra JVM options
    NO_COLOR         Disable color (same as --no-color)

Examples:
    ks hello.ks                  Run hello.ks in current directory
    ks hello                     Same (auto-appends .ks)
    ks ~/scripts/report.ks       Run with full path
    ks test1.ks test2.ks         Run multiple scripts

Shebang:
    #!/usr/bin/env ks
    say "Hello from KS!"
HELP
        exit 0
        ;;
    "")
        echo "Usage: ks <file[.ks]> [file2[.ks] ...]" >&2
        echo "       ks --help for more information" >&2
        exit 1
        ;;
esac

# ── Launch ───────────────────────────────────────────────────────────────────
# shellcheck disable=SC2086
exec "$JAVA" \
    -Dfile.encoding=UTF-8 \
    -Dstdout.encoding=UTF-8 \
    -Dstderr.encoding=UTF-8 \
    $KS_OPTS \
    -cp "$KS_RUNTIME" \
    io.kixi.ks.Run \
    "$@"
