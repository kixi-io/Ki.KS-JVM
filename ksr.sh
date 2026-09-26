#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
./gradlew -q --console=plain replJar

# Resolve the Java 21 toolchain; do not fall back to an unrelated PATH runtime.
ks_java=$(./gradlew -q --console=plain javaPath)
if [[ ! -x "$ks_java" ]]; then
    printf 'Could not resolve an executable Java 21 toolchain: %s\n' "$ks_java" >&2
    exit 1
fi
exec "$ks_java" -jar build/libs/ks-repl.jar "$@"
