package io.kixi.ks.repl

import io.kixi.ks.ANSI
import io.kixi.ks.KSRuntime
import io.kixi.ks.RuntimeError
import io.kixi.ks.SourceLocation
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.lexer.LexerException
import io.kixi.ks.parser.*
import io.kixi.ks.interp.Interpreter
import io.kixi.text.ParseException as KiParseException

import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.DefaultParser
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder

import java.io.PrintWriter
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Ki Script Interactive REPL (Read\u2013Eval\u2013Print Loop).
 *
 * Provides a Python-style interactive shell for the KS language with:
 *
 *   - **Smart multi-line input** \u2014 automatically detects incomplete expressions,
 *     unclosed brackets, trailing operators, and unterminated strings without
 *     requiring any special key combinations
 *   - **Line editing** \u2014 arrow keys, Home/End, Ctrl+A/E, Ctrl+W, and other
 *     Emacs-style keybindings via JLine3
 *   - **Input history** \u2014 Up/Down arrow recalls previous inputs across the session
 *   - **Persistent state** \u2014 variables, functions, classes, enums, and traits
 *     defined in one line are available in subsequent lines
 *   - **Expression auto-display** \u2014 bare expressions print their result (like
 *     Python), while statements and declarations are silent
 *   - **Colored output** \u2014 full ANSI color support matching `say.error` (red),
 *     `say.warn` (orange), and `say.note` (bold)
 *   - **REPL commands** \u2014 `:help`, `:quit`, `:reset`, `:env`, `:type`
 *   - **Full UTF-8** \u2014 Unicode everywhere: identifiers, strings, output
 *
 * ## Architecture
 *
 * The REPL owns a single [Interpreter] instance whose [Environment][io.kixi.ks.interp.Environment]
 * persists across input cycles. Terminal I/O is handled by JLine3, which
 * provides raw terminal mode (no echo artifacts), line editing, and history.
 * Input completeness is determined by [InputAnalyzer] which probes the
 * [Lexer][io.kixi.ks.lexer.Lexer] and inspects token structure. Output
 * formatting is handled by [ReplFormatter].
 *
 * The execution pipeline bypasses [Interpreter.execute] (which has debug
 * output) and instead drives Lexer \u2192 Parser \u2192 [Interpreter.executeProgram]
 * directly so the REPL has full control over what gets printed.
 *
 * ## Usage
 *
 * ```kotlin
 * fun io.kixi.ks.main() {
 *     Repl().run()
 * }
 * ```
 *
 * Or with custom configuration:
 *
 * ```kotlin
 * val runtime = KSRuntime(colorOutput = true, maxRecursionDepth = 500)
 * Repl(runtime).run()
 * ```
 *
 * @param runtime The [KSRuntime] configuration for the interpreter.
 */
class Repl(private val runtime: KSRuntime = KSRuntime.DEFAULT) {

    companion object {
        /** KS version string displayed in the banner. */
        const val VERSION = "2.3.1"

        /** Maximum number of continuation lines before forcing execution. */
        private const val MAX_CONTINUATION_LINES = 200
    }

    // ====================================================================
    // Components
    // ====================================================================

    private val interpreter = Interpreter(runtime)
    private val analyzer = InputAnalyzer()
    private val fmt = ReplFormatter
    private val colorEnabled = runtime.colorOutput

    /** JLine terminal — handles raw mode, echo, and signal trapping. */
    private val terminal: Terminal = TerminalBuilder.builder()
        .system(true)
        .encoding(Charsets.UTF_8)
        .build()

    /**
     * JLine parser configured to pass input through verbatim.
     *
     * By default, JLine's DefaultParser treats `\` as an escape character
     * (escapeChars = {'\\'}), which causes it to collapse `\\` into `\`
     * before the application receives the input. This breaks string escape
     * sequences in KS -- e.g. the user types `"\\d"` intending the KS
     * Lexer to process `\\` as an escaped backslash producing `\d`, but
     * JLine silently eats one backslash and the Lexer only sees `"\d"`,
     * triggering an unknown escape error.
     *
     * KS handles all escape processing in its own Lexer, so JLine must
     * not interfere. Setting escapeChars to empty disables this behavior.
     */
    private val jlineParser = DefaultParser().apply {
        escapeChars = charArrayOf()   // no escape processing -- KS Lexer handles it
    }

    /** JLine line reader -- handles prompts, line editing, and history. */
    private val lineReader: LineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .parser(jlineParser)
        .build()

    /** Writer for output, taken from JLine's terminal for correct coordination. */
    private val out: PrintWriter = terminal.writer()

    /** Line counter for the session (1-based). */
    private var lineNumber = 1

    /** Whether the REPL is running. */
    private var running = true

    // ====================================================================
    // Public API
    // ====================================================================

    /**
     * Start the REPL loop.
     *
     * Prints the welcome banner, then enters the read\u2013eval\u2013print cycle
     * until the user types `:quit`, `:q`, or sends EOF (Ctrl+D / Ctrl+Z).
     */
    fun run() {
        printBanner()

        while (running) {
            try {
                val input = readInput() ?: break  // null = EOF
                if (input.isBlank()) continue

                // Check for REPL commands
                val trimmed = input.trim()
                if (trimmed.startsWith(":")) {
                    handleCommand(trimmed)
                    continue
                }

                // Execute KS code
                executeAndDisplay(input)

            } catch (e: UserInterruptException) {
                // Ctrl+C — cancel current input, print fresh prompt
                out.println()
                continue
            } catch (e: EndOfFileException) {
                // Ctrl+D — exit
                break
            } catch (e: Exception) {
                // Catch-all for unexpected errors (should be rare)
                out.println(fmt.error("Internal error: ${e.message}", colorEnabled))
            }
        }

        printGoodbye()
        terminal.close()
    }

    // ====================================================================
    // Input Reading with Multi-Line Detection
    // ====================================================================

    /**
     * Read a complete unit of input, potentially spanning multiple lines.
     *
     * Uses JLine's [LineReader] for prompt display and input reading. JLine
     * controls the terminal in raw mode, so there are no echo artifacts,
     * blank lines, or double-prompt issues.
     *
     * Shows the primary prompt for the first line and the continuation
     * prompt for subsequent lines. Uses [InputAnalyzer] to determine when
     * the input is complete.
     *
     * Returns `null` on EOF (Ctrl+D).
     *
     * Special case: an empty line during continuation forces execution of
     * whatever has been accumulated, allowing the user to "escape" from
     * multi-line mode if the analyzer misjudges completeness.
     */
    private fun readInput(): String? {
        val prompt = fmt.prompt(colorEnabled)
        val contPrompt = fmt.continuation(colorEnabled)

        // Read first line via JLine (handles prompt display + terminal echo)
        val firstLine = try {
            lineReader.readLine(prompt)
        } catch (e: EndOfFileException) {
            return null
        }
        lineNumber++

        // Empty input — loop back immediately
        if (firstLine.isBlank()) return ""

        // Check if first line is already complete
        var accumulated = firstLine
        var state = analyzer.analyze(accumulated)

        if (state == InputAnalyzer.InputState.COMPLETE ||
            state == InputAnalyzer.InputState.EMPTY) {
            return accumulated
        }

        // Multi-line mode: keep reading until complete
        var continuationCount = 0
        while (state == InputAnalyzer.InputState.INCOMPLETE) {
            if (continuationCount >= MAX_CONTINUATION_LINES) {
                out.println(fmt.warning(
                    "Maximum continuation depth reached ($MAX_CONTINUATION_LINES lines). Executing as-is.",
                    colorEnabled
                ))
                break
            }

            val nextLine = try {
                lineReader.readLine(contPrompt)
            } catch (e: EndOfFileException) {
                break
            }

            lineNumber++
            continuationCount++

            // Empty line during continuation = force execute
            if (nextLine.isBlank()) {
                break
            }

            accumulated += "\n" + nextLine
            state = analyzer.analyze(accumulated)
        }

        return accumulated
    }

    // ====================================================================
    // Execution Pipeline
    // ====================================================================

    /**
     * Lex, parse, execute the input, and display the result if appropriate.
     *
     * The pipeline is:
     * 1. Lex the input into tokens
     * 2. Parse tokens into an AST [Program]
     * 3. Execute the program via the persistent [Interpreter]
     * 4. If the last AST node was an expression statement, display the result
     *
     * Errors at any stage are caught and displayed with appropriate formatting.
     */
    private fun executeAndDisplay(input: String) {
        try {
            // Step 1: Lex
            val tokens = Lexer(input).tokenize()

            // Step 2: Parse
            val program = Parser(tokens).parse()

            // Step 3: Determine if we should show the result
            val lastNode = program.body.lastOrNull()
            val shouldDisplayResult = lastNode is ExprStmt

            // Step 4: Execute
            val result = interpreter.executeProgram(program)

            // Step 5: Display result for expression statements
            if (shouldDisplayResult && result != null) {
                val valueStr = interpreter.stringify(result)
                val typeName = fmt.ksTypeName(result)

                out.println(fmt.result(valueStr, colorEnabled) +
                        fmt.typeHint(typeName, colorEnabled))
            }

        } catch (e: Exception) {
            displayError(e)
        }
    }

    // ====================================================================
    // Error Display
    // ====================================================================

    /**
     * Display an error with formatting appropriate to its type.
     *
     * Uses the KS exception hierarchy to access [suggestion] and [location]
     * properties directly rather than parsing them from the message string.
     *
     * ## Error Categories
     *
     * - [LexerException] / [ParseException] → labeled "Syntax Error"
     * - [RuntimeError] and subclasses → labeled "Error" (subclass name when specific)
     * - Other [Exception] → labeled "Error" (fallback)
     *
     * ## Source Location
     *
     * For multi-line REPL input, the `[line:col]` is shown to help the user
     * locate the problem. For single-line input it's omitted (obvious context).
     */
    private fun displayError(e: Exception) {
        when (e) {
            // --- Lex errors → "Syntax Error" ---
            is LexerException -> {
                val label = "Syntax Error"
                val msg = stripLocationPrefix(e.message ?: "Unknown lexer error")
                val location = formatLocation(e.location)
                out.println(fmt.error("$label$location: $msg", colorEnabled))
                showSuggestion(e.suggestion)
            }

            // --- KS parse errors → "Syntax Error" (has SourceLocation) ---
            is ParseException -> {
                val label = "Syntax Error"
                val msg = stripLocationPrefix(e.message ?: "Unknown parse error")
                val location = formatLocation(e.location)
                out.println(fmt.error("$label$location: $msg", colorEnabled))
                showSuggestion(e.suggestion)
            }

            // --- Ki.Core parse errors (e.g. from KD parsing) ---
            is KiParseException -> {
                val label = "Syntax Error"
                val msg = stripLocationPrefix(e.message ?: "Unknown parse error")
                out.println(fmt.error("$label: $msg", colorEnabled))
                showSuggestion(e.suggestion)
            }

            // --- Runtime errors → type-specific label ---
            is RuntimeError -> {
                val label = runtimeErrorLabel(e)
                val msg = stripLocationPrefix(e.message ?: "Unknown runtime error")
                val location = formatLocation(e.location)
                out.println(fmt.error("$label$location: $msg", colorEnabled))
                showSuggestion(e.suggestion)
            }

            // --- Fallback for unexpected exceptions ---
            else -> {
                val msg = e.message ?: "Unknown error"
                out.println(fmt.error(msg, colorEnabled))
            }
        }
    }

    /**
     * Derive a human-readable label from the [RuntimeError] subclass name.
     *
     * Maps class names like `TypeError`, `ConstraintError`, `UndefinedNameError`
     * to display labels like "Type Error", "Constraint Error", "Undefined Name".
     * Falls back to "Error" for the base [RuntimeError].
     */
    private fun runtimeErrorLabel(e: RuntimeError): String {
        val className = e::class.simpleName ?: return "Error"

        // Base RuntimeError → just "Error"
        if (className == "RuntimeError") return "Error"

        // Strip "Error" suffix and split camelCase → "TypeError" → "Type Error"
        val base = className.removeSuffix("Error")
        return base.replace(Regex("([a-z])([A-Z])")) { m ->
            "${m.groupValues[1]} ${m.groupValues[2]}"
        } + " Error"
    }

    /**
     * Show a suggestion on its own line if present.
     *
     * Uses the [suggestion] property from [RuntimeError] (via [KiError]) or
     * [ParseException] directly — no string parsing needed.
     */
    private fun showSuggestion(suggestion: String?) {
        if (suggestion != null) {
            val bulb = "\uD83D\uDCA1" // 💡
            out.println(fmt.note("$bulb $suggestion", colorEnabled))
        }
    }

    /**
     * Format a [SourceLocation] for REPL display.
     *
     * Returns ` [line:col]` for multi-line context or empty string for
     * line 1 (where location is obvious from context).
     */
    private fun formatLocation(location: SourceLocation?): String {
        if (location == null) return ""
        // For single-line input, location is obvious
        if (location.line <= 1) return ""
        return " [${location.line}:${location.column}]"
    }

    /**
     * Strip `[line:col]` prefix and `Suggestion: ...` suffix from a message.
     *
     * Both [RuntimeError] and [ParseException] embed `[line:col]` in the message
     * passed to their superclass, and [KiError.message] / [ParseException.message]
     * appends `Suggestion: ...`. Since we display these separately, we strip them
     * from the core message.
     */
    private fun stripLocationPrefix(message: String): String {
        var cleaned = message

        // Strip [line:col] prefix
        cleaned = cleaned.replace(Regex("""^\[\d+:\d+]\s*"""), "")

        // Strip "Suggestion: ..." suffix (displayed separately via showSuggestion)
        val sugIdx = cleaned.indexOf(" Suggestion: ")
        if (sugIdx >= 0) {
            cleaned = cleaned.substring(0, sugIdx)
        }

        return cleaned.trim()
    }

    // ====================================================================
    // REPL Commands
    // ====================================================================

    /**
     * Handle a REPL command (input starting with `:`).
     */
    private fun handleCommand(input: String) {
        val parts = input.split(Regex("\\s+"), limit = 2)
        val command = parts[0].lowercase()
        val args = if (parts.size > 1) parts[1] else ""

        when (command) {
            ":quit", ":q", ":exit" -> {
                running = false
            }

            ":help", ":h" -> {
                out.println(fmt.helpText(colorEnabled))
            }

            ":reset", ":r" -> {
                resetInterpreter()
                out.println(fmt.note("Interpreter state reset.", colorEnabled))
            }

            ":env", ":e" -> {
                showEnvironment()
            }

            ":type", ":t" -> {
                if (args.isBlank()) {
                    out.println(fmt.warning("Usage: :type <expression>", colorEnabled))
                } else {
                    showType(args)
                }
            }

            ":version", ":v" -> {
                out.println(fmt.bold("Ki Script $VERSION", colorEnabled))
            }

            ":clear", ":cls" -> {
                out.print("\u001B[2J\u001B[H")  // clear screen + cursor to top-left
                out.flush()
            }

            else -> {
                out.println(fmt.warning("Unknown command: $command. Type :help for available commands.", colorEnabled))
            }
        }
    }

    /**
     * Reset the interpreter to a clean state.
     *
     * Creates a fresh interpreter with the same runtime configuration.
     * Note: This replaces the environment but cannot fully reset the
     * interpreter's internal registries (classes, traits, enums) since
     * those are private. A full reset creates a new interpreter.
     */
    private fun resetInterpreter() {
        interpreter.environment = io.kixi.ks.interp.Environment.global()
        interpreter.resetImports()
    }

    /**
     * Display all variables defined in the current environment.
     */
    private fun showEnvironment() {
        val env = interpreter.environment
        val names = env.localNames()

        if (names.isEmpty()) {
            out.println(fmt.note("No variables defined.", colorEnabled))
            return
        }

        out.println(fmt.bold("Defined variables:", colorEnabled))
        for (name in names.sorted()) {
            try {
                val value = env.get(name)
                val valueStr = interpreter.stringify(value)
                val typeName = fmt.ksTypeName(value)
                val mutability = if (env.isMutable(name)) "var" else "let"

                out.println("  ${formatBinding(mutability, colorEnabled)} $name = " +
                        "${ANSI.green(valueStr, colorEnabled)} " +
                        "${fmt.typeHint(typeName, colorEnabled)}")
            } catch (e: Exception) {
                out.println("  $name = ${fmt.error("<error>", colorEnabled)}")
            }
        }
    }

    /**
     * Evaluate an expression and show its type.
     */
    private fun showType(exprSource: String) {
        try {
            val tokens = Lexer(exprSource).tokenize()
            val program = Parser(tokens).parse()
            val result = interpreter.executeProgram(program)
            val typeName = fmt.ksTypeName(result)
            val valueStr = interpreter.stringify(result)

            out.println(fmt.result(valueStr, colorEnabled) +
                    fmt.typeHint(typeName, colorEnabled))
        } catch (e: Exception) {
            displayError(e)
        }
    }

    // ====================================================================
    // Banner & Goodbye
    // ====================================================================

    private fun printBanner() {
        out.println(fmt.banner(VERSION, colorEnabled))
        out.println()
    }

    private fun printGoodbye() {
        val wave = "\uD83D\uDC4B" // 👋
        out.println()
        out.println(fmt.note("$wave Goodbye!", colorEnabled))
    }

    // ====================================================================
    // Formatting Helpers
    // ====================================================================

    private fun formatBinding(keyword: String, colorEnabled: Boolean): String {
        return if (colorEnabled) {
            "${ANSI.CYAN}$keyword${ANSI.RESET}"
        } else {
            keyword
        }
    }
}

// ============================================================================
// Entry Point
// ============================================================================

/**
 * Launch the KS REPL.
 *
 * Configures UTF-8 output, suppresses JLine's fallback warning, and starts
 * the interactive loop.
 *
 * ## Running from Gradle
 *
 * JLine requires a real TTY to enable raw mode (line editing, history, clean
 * prompt handling). Add this task to `build.gradle.kts`:
 *
 * ```kotlin
 * tasks.register<JavaExec>("repl") {
 *     group = "application"
 *     description = "Launch the KS interactive REPL"
 *     mainClass.set("io.kixi.ks.repl.ReplKt")
 *     classpath = sourceSets["io.kixi.ks.main"].runtimeClasspath
 *     standardInput = System.`in`   // critical: pass real TTY to JVM
 * }
 * ```
 *
 * Then run from a **real terminal** (not IntelliJ's Run panel):
 *
 * ```
 * $ ./gradlew -q --console=plain repl
 * ```
 *
 * The `-q` flag suppresses Gradle's own output, and `--console=plain`
 * prevents Gradle from fighting JLine for terminal control.
 *
 * ## Running directly
 *
 * ```
 * $ java -cp ks.jar io.kixi.ks.repl.ReplKt
 * ```
 */
fun main(args: Array<String>) {
    // Suppress JLine's "Unable to create a system terminal" warning.
    // This fires when there's no real TTY (e.g. IDE console, piped input).
    // JLine still works in dumb mode — it just can't do line editing.
    Logger.getLogger("org.jline").level = Level.OFF

    // Ensure UTF-8 output on all platforms
    System.setProperty("stdout.encoding", "UTF-8")
    System.setProperty("stderr.encoding", "UTF-8")

    // Parse flags
    val noColor = args.contains("--no-color") || args.contains("--plain")
    val debug = args.contains("--debug")

    val runtime = KSRuntime(
        colorOutput = !noColor && System.getenv("NO_COLOR") == null,
        debugMode = debug
    )

    Repl(runtime).run()
}