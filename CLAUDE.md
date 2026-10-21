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
- Never start a test name with `!`: Kotest treats a leading `!` as "disabled" and silently skips the test. Quote the operator instead: `test("'!in' check")`.
- Publish to GitHub Packages: `./gradlew publish` (needs `GITHUB_ACTOR` / `GITHUB_TOKEN`) — **don't run without asking.**

## Notes
- `io.kixi.ks.interp.Interpreter` is the engine that LabNexus's `KS` facade drives.
- `lang Name { ... }` is an embedded language (`io.kixi.lang.EmbeddedLanguage` in Ki.Core, e.g. RevDB's KQ): the lexer asks the language to `scan` its block (`Lexer.scanLangBody`), so never add KS-side rules for reading a block's text; `LangTextParser` only parses the interpolation expressions the scan reports. KD is one too: Ki.KD's `KDLanguage` scans and reads `lang KD` blocks with KDParser (KS calls it directly, since KS depends on Ki.KD); there is no KS-side KD parser. KS must not depend on any other language's library.
- `replJar` and `runtimeJar` overlap — both bundle the full runtime classpath; a merge candidate if you touch them.

## Working agreements
- Commit changes here separately from LabNexus.
- Don't publish or change the version without confirmation.