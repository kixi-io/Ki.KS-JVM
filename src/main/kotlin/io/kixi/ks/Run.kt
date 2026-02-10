package io.kixi.ks

import io.kixi.ks.interp.Interpreter
import java.io.File
import kotlin.system.exitProcess

object Run {

    /**
     * KS Language Launcher
     *
     * Runs one or more KS script files and reports results.
     *
     * Usage:
     *     ks <file.ks> [file2.ks ...]
     *
     * Each script runs in its own Interpreter instance (isolated state).
     * A summary is printed at the end with file names and execution times.
     */
    fun main(args: Array<String>) {

        if (args.isEmpty()) {
            System.err.println("Usage: ks <file.ks> [file2.ks ...]")
            exitProcess(1)
        }

        // Validate all files before running any
        val scripts = args.map { path ->
            val file = File(path)
            if (!file.exists()) {
                System.err.println("${ANSI.RED}Error:${ANSI.RESET} File not found: $path")
                exitProcess(1)
            }
            if (!file.name.endsWith(".ks")) {
                System.err.println("${ANSI.RED}Error:${ANSI.RESET} Not a .ks file: $path")
                exitProcess(1)
            }
            file
        }

        val runtime = KSRuntime(colorOutput = true, debugMode = false)
        val results = mutableListOf<ScriptResult>()

        for (script in scripts) {
            results.add(runScript(runtime, script))
        }

        printSummary(results)

        // Exit with error if any script failed
        if (results.any { !it.success }) exitProcess(1)
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

/**
 * Run a single KS script in a fresh interpreter.
 */
private fun runScript(runtime: KSRuntime, scriptFile: File): ScriptResult {
    val name = scriptFile.name

    // ── File header ──
    println()
    println("${ANSI.CYAN}───${ANSI.RESET} ${ANSI.BOLD}$name${ANSI.RESET} ${ANSI.CYAN}${"─".repeat(maxOf(1, 62 - name.length))}${ANSI.RESET}")
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

/**
 * Print a summary table of all script results.
 */
private fun printSummary(results: List<ScriptResult>) {
    println("${ANSI.CYAN}${"═".repeat(66)}${ANSI.RESET}")
    println("${ANSI.BOLD}Summary${ANSI.RESET}")
    println()

    val passed = results.count { it.success }
    val failed = results.size - passed

    // Per-file results
    for (r in results) {
        val status = if (r.success)
            "${ANSI.GREEN}✔${ANSI.RESET}"
        else
            "${ANSI.RED}✘${ANSI.RESET}"

        val time = if (r.elapsedMs >= 0) "${r.elapsedMs}ms" else "—"
        println("  $status  ${r.file.name}  ${ANSI.CYAN}$time${ANSI.RESET}")
    }

    println()

    // File list
    val fileList = results.joinToString(", ") { it.file.name }
    println("  Files: $fileList")

    // Total time (only successful runs)
    val totalMs = results.filter { it.success }.sumOf { it.elapsedMs }
    println("  Total: ${ANSI.BOLD}${totalMs}ms${ANSI.RESET}  " +
            "(${ANSI.GREEN}$passed passed${ANSI.RESET}" +
            if (failed > 0) ", ${ANSI.RED}$failed failed${ANSI.RESET})" else ")")

    println("${ANSI.CYAN}${"═".repeat(66)}${ANSI.RESET}")
    println()
}






