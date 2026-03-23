#!/usr/bin/env bash
cd "$(dirname "$0")"
./gradlew -q replJar || exit 1

# Use the same JDK that Gradle uses
JAVA=$(./gradlew -q javaPath 2>/dev/null)
if [ -z "$JAVA" ]; then
    JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
fi
exec "$JAVA" -jar build/libs/ks-repl.jar "$@"