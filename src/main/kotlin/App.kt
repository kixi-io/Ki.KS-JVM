// import com.jogamp.common.ExceptionUtils.printStackTrace
import io.kixi.ks.ANSI
import io.kixi.ks.KSRuntime
import io.kixi.ks.RuntimeError
import io.kixi.ks.interp.Interpreter
import java.io.File

/**
 * KS Language Runner
 *
 * Entry point for running KS scripts from the command line.
 *
 * Usage:
 *   java -jar ks.jar [script.ks]
 *
 * If no script is provided, runs the default Demo.ks from the source directory.
 */
fun main() { // args: Array<String>

    println("begin")

    var demoPath = "src/Demo.ks"
    // var demoPath = "src/main/kotlin/Demo.ks"

    // var args = arrayOf<String>()

    var demoFile = File(demoPath)

    println("demoFile: ${demoFile.exists()}")

    if(demoFile.exists()) {

    }

    try {
        val runtime = KSRuntime(
            colorOutput = true,
            debugMode = false
        )
        val interpreter = Interpreter(runtime)

        // Determine script file
        val scriptFile = when {
            // args.isNotEmpty() -> File(args[0])
            !demoFile.exists() -> demoFile
            else -> findDemoScript()
        }

        if (scriptFile == null || !scriptFile.exists()) {
            runInteractiveDemo(interpreter)
            return
        }

        runScript(interpreter, scriptFile)
    } catch (e: Exception) {
        println(e.message)
        e.printStackTrace()
    }
}

/**
 * Find the Demo.ks script in common locations.
 */
private fun findDemoScript(): File? {
    val candidates = listOf(
        "src/main/kotlin/Demo.ks",
        "src/Demo.ks",
        "Demo.ks",
        "../Demo.ks"
    )

    for (path in candidates) {
        val file = File(path)
        if (file.exists()) {
            println("Found Demo.ks at ${file.absolutePath}")
            return file
        }
    }

    return null
}

/**
 * Run a KS script file.
 */
private fun runScript(interpreter: Interpreter, scriptFile: File) {
    println()
    println("${ANSI.BOLD}${ANSI.CYAN}┌──────────────────────────────────────────────────────────────────┐${ANSI.RESET}")
    println("${ANSI.BOLD}${ANSI.CYAN}│${ANSI.RESET}  ${ANSI.BOLD}KS Interpreter${ANSI.RESET}                                                  ${ANSI.BOLD}${ANSI.CYAN}│${ANSI.RESET}")
    println("${ANSI.BOLD}${ANSI.CYAN}│${ANSI.RESET}  Running: ${ANSI.GREEN}${scriptFile.name}${ANSI.RESET}                                              ${ANSI.BOLD}${ANSI.CYAN}│${ANSI.RESET}")
    println("${ANSI.BOLD}${ANSI.CYAN}└──────────────────────────────────────────────────────────────────┘${ANSI.RESET}")
    println()

    try {
        val source = scriptFile.readText()
        val startTime = System.currentTimeMillis()

        interpreter.execute(source)

        val elapsed = System.currentTimeMillis() - startTime
        println()
        println("${ANSI.CYAN}──────────────────────────────────────────────────────────────────${ANSI.RESET}")
        println("${ANSI.GREEN}✓${ANSI.RESET} Completed in ${elapsed}ms")

    } catch (e: RuntimeError) {
        System.err.println()
        System.err.println("${ANSI.RED}${ANSI.BOLD}Error:${ANSI.RESET} ${e.message}")
        if (e.location != null) {
            System.err.println("       at ${e.location}")
        }
        System.exit(1)

    } catch (e: Exception) {
        System.err.println()
        System.err.println("${ANSI.RED}${ANSI.BOLD}Internal Error:${ANSI.RESET} ${e.message}")
        e.printStackTrace()
        System.exit(1)
    }
}

/**
 * Run an interactive demo when no script is found.
 */
private fun runInteractiveDemo(interpreter: Interpreter) {
    println()
    println("${ANSI.BOLD}${ANSI.CYAN}═══════════════════════════════════════════${ANSI.RESET}")
    println("${ANSI.BOLD}       KS Interpreter - Quick Demo${ANSI.RESET}")
    println("${ANSI.BOLD}${ANSI.CYAN}═══════════════════════════════════════════${ANSI.RESET}")
    println()
    println("${ANSI.YELLOW}Note:${ANSI.RESET} No Demo.ks found. Running built-in examples.")
    println()

    val examples = listOf(
        "# Hello World" to """say "Aloha world!" """,

        "# Say variants" to """
            say.note "This is a note (bold)"
            say.warn "This is a warning (orange)"
            say.error "This is an error (red)"
        """.trimIndent(),

        "# Variables and arithmetic" to """
            let x = 10
            let y = 3
            say "x = ${"$"}x, y = ${"$"}y"
            say "x + y = ${"$"}{x + y}"
            say "x * y = ${"$"}{x * y}"
            say "x ** y = ${"$"}{x ** y}"
        """.trimIndent(),

        "# When expression" to """
            fun describe(n: Int): String {
                return when n {
                    0 -> "zero"
                    1 -> "one"
                    else -> "many"
                }
            }
            say "0 is: " + describe(0)
            say "1 is: " + describe(1)
            say "5 is: " + describe(5)
        """.trimIndent(),

        "# Function with constraint" to """
            fun safeDivide(a: Int, b: Int != 0): Int = a / b
            say "10 / 2 = " + safeDivide(10, 2)
            say "100 / 4 = " + safeDivide(100, 4)
        """.trimIndent()
    )

    for ((title, code) in examples) {
        println("${ANSI.CYAN}─────────────────────────────────────────${ANSI.RESET}")
        println("${ANSI.BOLD}$title${ANSI.RESET}")
        println()

        try {
            interpreter.execute(code)
        } catch (e: Exception) {
            println("${ANSI.RED}Error: ${e.message}${ANSI.RESET}")
        }
        println()
    }

    println("${ANSI.CYAN}═══════════════════════════════════════════${ANSI.RESET}")
    println("${ANSI.GREEN}Tip:${ANSI.RESET} Create a Demo.ks file for a full demonstration!")
    println()
}