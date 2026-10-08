#!/usr/bin/env bash
# Run the KDS example scripts against YOUR local Ki.KS-JVM / Ki.KD-JVM / Ki.Core-JVM.
#
# Usage (from the Ki.KS-JVM project root):
#     ./run-kds-examples.sh                 # probe + all six examples
#     ./run-kds-examples.sh examples/kds-00-probe.ks
#     ./run-kds-examples.sh examples/kds-03-definitions-quantities.ks
#
# It builds the normal runtime jar with the composite build (so ../Ki.KD-JVM
# and ../Ki.Core-JVM from your disk are what gets used), then runs the
# scripts through io.kixi.ks.Run. Nothing is installed and no source is
# modified.
set -euo pipefail
cd -- "$(dirname -- "$0")"

if [[ ! -f settings.gradle.kts ]] || ! grep -q 'Ki.KD-JVM' settings.gradle.kts; then
    echo "Run this from the Ki.KS-JVM project root (settings.gradle.kts with includeBuild(\"../Ki.KD-JVM\"))." >&2
    exit 1
fi

./gradlew -q --console=plain runtimeJar

# Same Java 21 toolchain resolution as ksr.sh.
ks_java=$(./gradlew -q --console=plain javaPath)
if [[ ! -x "$ks_java" ]]; then
    printf 'Could not resolve an executable Java 21 toolchain: %s\n' "$ks_java" >&2
    exit 1
fi

if [[ $# -eq 0 ]]; then
    set -- examples/kds-00-probe.ks examples/kds-0[1-6]-*.ks
fi

exec "$ks_java" -Dfile.encoding=UTF-8 -jar build/libs/ks-runtime.jar "$@"
