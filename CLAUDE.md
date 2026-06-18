# Ki.KS-JVM — CLAUDE.md

**Ki.KS (Ki Script)** — a JVM scripting language. Published as `io.kixi:Ki.KS-JVM` (currently `2.3.3`). Consumed by LabNexus as a live composite dependency. JDK 21.

## Composite build
This build includes `../Ki.Core-JVM` and `../Ki.KD-JVM` via `settings.gradle.kts`, so edits to them are live when building from here. It depends on them at `2.3.2` (`api`) — keep those versions published as the fallback for consumers without local checkouts.

## Build & run
- App entry point is `AppKt`: `./gradlew run`
- Interactive REPL (needs a real terminal, **not** IntelliJ's Run panel): `./gradlew -q --console=plain repl`
- Runtime fat jar: `./gradlew runtimeJar`
- Distribution / local install: `./gradlew kiDist` / `./gradlew installLocal` (require the `ksVersion` project property and a populated `packaging/` dir)
- Tests (Kotest on JUnit Platform): `./gradlew test`
- Publish to GitHub Packages: `./gradlew publish` (needs `GITHUB_ACTOR` / `GITHUB_TOKEN`) — **don't run without asking.**

## Notes
- `io.kixi.ks.interp.Interpreter` is the engine that LabNexus's `KS` facade drives.
- `replJar` and `runtimeJar` overlap — both bundle the full runtime classpath; a merge candidate if you touch them.

## Working agreements
- Commit changes here separately from LabNexus.
- Don't publish or change the version without confirmation.