package io.kixi.ks

import io.kixi.ks.interp.Interpreter
import io.kixi.ks.tools.Repl
import java.io.File
import kotlin.system.exitProcess

/**
 * Ki Script Command-Line Runner
 *
 * Entry point for the `ks` command. Runs one or more KS script files
 * and reports results.
 *
 * ## Usage
 *
 *     ks <file[.ks]> [file2[.ks] ...]
 *     ks --version
 *     ks --help
 *
 * ## Features
 *
 * - **Extension auto-append**: `ks hello` resolves to `hello.ks`
 * - **Path support**: Absolute and relative paths work naturally
 * - **Shebang support**: `#!/usr/bin/env ks` at top of .ks files (# is a comment)
 * - **Multi-file**: Multiple scripts run sequentially with a summary
 * - **Exit codes**: 0 on success, 1 on failure
 *
 * ## Future Extensions
 *
 * - `.ksc` compiled script execution
 * - `.kap` package execution (via meta.kd entry point)
 */
object Run {

    /** Version — kept in sync with [Repl.VERSION]. */
    val VERSION = Repl.VERSION

    @JvmStatic
    fun main(args: Array<String>) {
        // ── Parse flags ─────────────────────────────────────────────────
        val flags = mutableListOf<String>()
        val files = mutableListOf<String>()

        for (arg in args) {
            if (arg.startsWith("-")) {
                flags.add(arg)
            } else {
                files.add(arg)
            }
        }

        // Handle --version / --repl-version
        if ("--version" in flags || "-v" in flags) {
            println("Ki Script $VERSION")
            exitProcess(0)
        }

        if ("--repl-version" in flags) {
            println("Ki Script REPL $VERSION")
            exitProcess(0)
        }

        // Handle --help
        if ("--help" in flags || "-h" in flags) {
            printUsage()
            exitProcess(0)
        }

        // ── Validate files ──────────────────────────────────────────────
        if (files.isEmpty()) {
            System.err.println("Usage: ks <file[.ks]> [file2[.ks] ...]")
            System.err.println("       ks --help for more information")
            exitProcess(1)
        }

        val noColor = "--no-color" in flags || "--plain" in flags ||
                System.getenv("NO_COLOR") != null
        val debug = "--debug" in flags

        // ── Resolve files ───────────────────────────────────────────────
        val scripts = files.map { path -> resolveScript(path) }

        val runtime = KSRuntime(colorOutput = !noColor, debugMode = debug)
        val results = mutableListOf<ScriptResult>()

        for (script in scripts) {
            results.add(runScript(runtime, script))
        }

        // ── Summary ─────────────────────────────────────────────────────
        // Only show full summary table for multi-file runs
        if (results.size > 1) {
            printSummary(results, !noColor)
        } else {
            // Single file: just show timing on success, error is already displayed
            val r = results[0]
            if (r.success && r.elapsedMs >= 0) {
                println("${if (!noColor) ANSI.CYAN else ""}── ${r.file.name}: ${r.elapsedMs}ms${if (!noColor) ANSI.RESET else ""}")
            }
        }

        if (results.any { !it.success }) exitProcess(1)
    }

    // ====================================================================
    // File Resolution
    // ====================================================================

    /**
     * Resolve a script path to a [File], auto-appending `.ks` if needed.
     *
     * Resolution order:
     * 1. Exact path as given
     * 2. Path with `.ks` appended (if not already present)
     *
     * Future: will also check `.ksc` and `.kap` extensions.
     */
    private fun resolveScript(path: String): File {
        // Try exact path first
        val exact = File(path)
        if (exact.exists() && exact.isFile) return exact

        // Auto-append .ks if the path doesn't have a recognized extension
        if (!hasKiExtension(path)) {
            val withExt = File("$path.ks")
            if (withExt.exists() && withExt.isFile) return withExt
        }

        // File not found — report with helpful context
        val tried = if (hasKiExtension(path)) {
            path
        } else {
            "$path (also tried $path.ks)"
        }
        System.err.println("${ANSI.RED}Error:${ANSI.RESET} File not found: $tried")
        exitProcess(1)
    }

    /**
     * Check if a path has a recognized Ki extension.
     */
    private fun hasKiExtension(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".ks") ||
                lower.endsWith(".ksc") ||
                lower.endsWith(".kap")
    }

    // ====================================================================
    // Script Execution
    // ====================================================================

    /**
     * Run a single KS script in a fresh interpreter.
     */
    private fun runScript(runtime: KSRuntime, scriptFile: File): ScriptResult {
        val name = scriptFile.name

        // ── File header ──
        println()
        val color = runtime.colorOutput
        if (color) {
            println("${ANSI.CYAN}───${ANSI.RESET} ${ANSI.BOLD}$name${ANSI.RESET} ${ANSI.CYAN}${"─".repeat(maxOf(1, 62 - name.length))}${ANSI.RESET}")
        } else {
            println("--- $name ${"─".repeat(maxOf(1, 62 - name.length))}")
        }
        println()

        val interpreter = Interpreter(runtime)

        return try {
            val source = scriptFile.readText()
            val start = System.currentTimeMillis()
            interpreter.execute(source)
            val elapsed = System.currentTimeMillis() - start

            println()
            ScriptResult(scriptFile, success = true, elapsedMs = elapsed)
        } catch (e: RuntimeError) {
            System.err.println()
            System.err.println("${ANSI.RED}${ANSI.BOLD}Error:${ANSI.RESET} ${e.message}")
            e.location?.let { System.err.println("       at $it") }
            println()
            ScriptResult(scriptFile, success = false, elapsedMs = -1, error = e.message)
        } catch (e: Throwable) {
            System.err.println()
            System.err.println("${ANSI.RED}${ANSI.BOLD}Internal Error:${ANSI.RESET} ${e.message}")
            e.printStackTrace()
            println()
            ScriptResult(scriptFile, success = false, elapsedMs = -1, error = e.message)
        }
    }

    // ====================================================================
    // Output
    // ====================================================================

    private fun printSummary(results: List<ScriptResult>, color: Boolean) {
        val sep = if (color) "${ANSI.CYAN}${"═".repeat(66)}${ANSI.RESET}" else "═".repeat(66)
        println(sep)
        println("${if (color) ANSI.BOLD else ""}Summary${if (color) ANSI.RESET else ""}")
        println()

        val passed = results.count { it.success }
        val failed = results.size - passed

        for (r in results) {
            val status = if (r.success) {
                if (color) "${ANSI.GREEN}✔${ANSI.RESET}" else "+"
            } else {
                if (color) "${ANSI.RED}✘${ANSI.RESET}" else "X"
            }
            val time = if (r.elapsedMs >= 0) "${r.elapsedMs}ms" else "—"
            println("  $status  ${r.file.name}  ${if (color) ANSI.CYAN else ""}$time${if (color) ANSI.RESET else ""}")
        }

        println()

        val fileList = results.joinToString(", ") { it.file.name }
        println("  Files: $fileList")

        val totalMs = results.filter { it.success }.sumOf { it.elapsedMs }
        val passStr = if (color) "${ANSI.GREEN}$passed passed${ANSI.RESET}" else "$passed passed"
        val failStr = if (failed > 0) {
            if (color) ", ${ANSI.RED}$failed failed${ANSI.RESET}" else ", $failed failed"
        } else ""
        println("  Total: ${if (color) ANSI.BOLD else ""}${totalMs}ms${if (color) ANSI.RESET else ""}  ($passStr$failStr)")

        println(sep)
        println()
    }

    private fun printUsage() {
        println("""
Ki Script $VERSION — https://github.com/kixi-io

Usage:
    ks <file[.ks]> [file2[.ks] ...]    Run one or more KS scripts
    ks --version                        Show version
    ks --help                           Show this help

Options:
    --no-color    Disable ANSI color output
    --debug       Enable debug output

Examples:
    ks hello.ks              Run hello.ks in current directory
    ks hello                 Same (auto-appends .ks extension)
    ks ~/scripts/report.ks   Run with full path
    ks test1.ks test2.ks     Run multiple scripts sequentially

File Extensions:
    .ks     Ki Script source file
    .ksc    Ki Script compiled file (future)
    .kap    Ki Application Package (future)

Shebang:
    #!/usr/bin/env ks
    say "Hello from KS!"
        """.trimIndent())
    }
}

/**
 * Result of running a single KS script.
 */
private data class ScriptResult(
    val file: File,
    val success: Boolean,
    val elapsedMs: Long,
    val error: String? = null
)