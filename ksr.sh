#!/usr/bin/env bash
cd "$(dirname "$0")"
./gradlew -q replJar && java -jar build/libs/ks-repl.jar "$@"
