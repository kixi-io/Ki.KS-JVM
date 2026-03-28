package io.kixi.ks.repl

import io.kixi.ks.ANSI

/**
 * REPL-specific ANSI formatting.
 *
 * Extends the base [ANSI] palette with styles for the interactive shell:
 * prompt, continuation, results, type annotations, errors, and the welcome
 * banner.  All formatting respects the [colorEnabled] flag so the REPL works
 * cleanly in environments that don't support ANSI escapes.
 *
 * ## Color Palette
 *
 * | Element       | Color              | Rationale                          |
 * |---------------|--------------------|------------------------------------|
 * | Prompt arrow  | Cyan bold          | High-contrast, distinct from output|
 * | Continuation  | Cyan (dim)         | Clearly related to prompt          |
 * | Result prefix | Green              | "Success" signal                   |
 * | Result value  | Green bold         | Primary output, maximum visibility |
 * | Type hint     | Magenta            | Distinct metadata, not output      |
 * | Error         | Red bold           | Matches say.error                  |
 * | Warning       | Orange             | Matches say.warn                   |
 * | Note/info     | Bold               | Matches say.note                   |
 * | Banner        | Cyan + bold        | Welcoming, brand-consistent        |
 * | REPL command  | Yellow             | Distinct from KS code              |
 */
object ReplFormatter {

    // ====================================================================
    // ANSI Codes (supplement ANSI object)
    // ====================================================================

    private const val DIM         = "\u001B[2m"
    private const val ITALIC      = "\u001B[3m"
    private const val UNDERLINE   = "\u001B[4m"
    private const val BOLD_CYAN   = "\u001B[1;36m"
    private const val BOLD_GREEN  = "\u001B[1;32m"
    private const val BOLD_RED    = "\u001B[1;31m"
    private const val BOLD_YELLOW = "\u001B[1;33m"
    private const val GRAY        = "\u001B[90m"

    // ====================================================================
    // Prompt
    // ====================================================================

    /** Primary prompt: `ks\u276F ` */
    fun prompt(colorEnabled: Boolean): String {
        val arrow = "\u276F" // ❯  (HEAVY RIGHT-POINTING ANGLE QUOTATION MARK ORNAMENT)
        return if (colorEnabled) {
            "${BOLD_CYAN}ks${ANSI.RESET}${ANSI.CYAN}$arrow${ANSI.RESET} "
        } else {
            "ks> "
        }
    }

    /** Continuation prompt: `... ` — shown when input spans multiple lines. */
    fun continuation(colorEnabled: Boolean): String {
        val dots = "\u00B7\u00B7\u00B7" // ···  (MIDDLE DOT x3)
        return if (colorEnabled) {
            "${GRAY}$dots${ANSI.RESET} "
        } else {
            "... "
        }
    }

    // ====================================================================
    // Result Display
    // ====================================================================

    /** Format an expression result: `\u2192 value` */
    fun result(value: String, colorEnabled: Boolean): String {
        val arrow = "\u2192" // →
        return if (colorEnabled) {
            "${ANSI.GREEN}$arrow${ANSI.RESET} ${BOLD_GREEN}$value${ANSI.RESET}"
        } else {
            "$arrow $value"
        }
    }

    /** Format a type hint shown after results: `  : TypeName` */
    fun typeHint(typeName: String, colorEnabled: Boolean): String {
        return if (colorEnabled) {
            "  ${GRAY}:${ANSI.RESET} ${ANSI.MAGENTA}$typeName${ANSI.RESET}"
        } else {
            "  : $typeName"
        }
    }

    // ====================================================================
    // Error & Diagnostic Output
    // ====================================================================

    /** Format an error message. */
    fun error(message: String, colorEnabled: Boolean): String {
        val marker = "\u2718" // ✘
        return if (colorEnabled) {
            "${BOLD_RED}$marker${ANSI.RESET} ${ANSI.RED}$message${ANSI.RESET}"
        } else {
            "$marker $message"
        }
    }

    /** Format a warning message. */
    fun warning(message: String, colorEnabled: Boolean): String {
        val marker = "\u26A0" // ⚠
        return if (colorEnabled) {
            "${BOLD_YELLOW}$marker${ANSI.RESET} ${ANSI.ORANGE}$message${ANSI.RESET}"
        } else {
            "$marker $message"
        }
    }

    /** Format an informational note. */
    fun note(message: String, colorEnabled: Boolean): String {
        val marker = "\u2139" // ℹ
        return if (colorEnabled) {
            "${ANSI.BOLD}$marker $message${ANSI.RESET}"
        } else {
            "$marker $message"
        }
    }

    // ====================================================================
    // Welcome Banner
    // ====================================================================

    /**
     * The KS REPL welcome banner.
     *
     * Uses box-drawing characters for a clean border and displays version,
     * runtime info, and quick-start hints.
     */
    fun banner(version: String, colorEnabled: Boolean): String {
        // Box-drawing characters
        val tl = "\u256D" // ╭
        val tr = "\u256E" // ╮
        val bl = "\u2570" // ╰
        val br = "\u256F" // ╯
        val h  = "\u2500" // ─
        val v  = "\u2502" // │

        val ki = "Ki Script" // brand name
        val bar = h.repeat(44)

        val lines = listOf(
            "$tl$bar$tr",
            "$v  ${bold("$ki $version", colorEnabled)}${" ".repeat(44 - ki.length - version.length - 3)}$v",
            // "$v  Kotlin/JVM ${System.getProperty("java.version") ?: "??"}${" ".repeat(maxOf(0, 44 - 13 - (System.getProperty("java.version")?.length ?: 2)))}$v",
            "$v${" ".repeat(44)}$v",
            "$v  ${cmdStyle(":help", colorEnabled)} for commands, ${cmdStyle(":quit", colorEnabled)} to exit${" ".repeat(maxOf(0, 44 - 42))}       $v",
            "$bl$bar$br"
        )

        return if (colorEnabled) {
            lines.joinToString("\n") { "${ANSI.CYAN}$it${ANSI.RESET}" }
        } else {
            lines.joinToString("\n")
        }
    }

    // ====================================================================
    // Help Text
    // ====================================================================

    fun helpText(colorEnabled: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine(bold("REPL Commands", colorEnabled))
        sb.appendLine()
        sb.appendLine("  ${cmdStyle(":help", colorEnabled)}  ${cmdStyle(":h", colorEnabled)}      Show this help")
        sb.appendLine("  ${cmdStyle(":quit", colorEnabled)}  ${cmdStyle(":q", colorEnabled)}      Exit the REPL")
        sb.appendLine("  ${cmdStyle(":reset", colorEnabled)} ${cmdStyle(":r", colorEnabled)}      Reset interpreter state")
        sb.appendLine("  ${cmdStyle(":env", colorEnabled)}   ${cmdStyle(":e", colorEnabled)}      Show defined variables")
        sb.appendLine("  ${cmdStyle(":type", colorEnabled)}  ${cmdStyle(":t", colorEnabled)} expr Show expression type")
        sb.appendLine("  ${cmdStyle(":clear", colorEnabled)} ${cmdStyle(":cls", colorEnabled)}     Clear the screen")
        sb.appendLine()
        sb.appendLine(bold("Language Quick Reference", colorEnabled))
        sb.appendLine()
        sb.appendLine("  ${kw("say", colorEnabled)} \"Hello, world!\"          ${comment("# print to stdout", colorEnabled)}")
        sb.appendLine("  ${kw("say.warn", colorEnabled)} \"Caution\"              ${comment("# orange warning", colorEnabled)}")
        sb.appendLine("  ${kw("say.error", colorEnabled)} \"Oops\"               ${comment("# red error", colorEnabled)}")
        sb.appendLine("  ${kw("say.note", colorEnabled)} \"Note\"                ${comment("# bold note", colorEnabled)}")
        sb.appendLine("  ${kw("var", colorEnabled)} x = 42                      ${comment("# mutable variable", colorEnabled)}")
        sb.appendLine("  ${kw("let", colorEnabled)} name = \"Akiko\"              ${comment("# immutable binding", colorEnabled)}")
        sb.appendLine("  ${kw("fun", colorEnabled)} greet(n: String) = say \"Hi \$n\" ${comment("# function", colorEnabled)}")
        sb.append(    "  ${kw("for", colorEnabled)} 1..5 ${kw("say", colorEnabled)} it                 ${comment("# iteration", colorEnabled)}")
        return sb.toString()
    }

    // ====================================================================
    // Inline Styles
    // ====================================================================

    /** Bold text. */
    fun bold(text: String, colorEnabled: Boolean): String =
        if (colorEnabled) "${ANSI.BOLD}$text${ANSI.RESET}" else text

    /** REPL command style (yellow). */
    fun cmdStyle(text: String, colorEnabled: Boolean): String =
        if (colorEnabled) "${ANSI.YELLOW}$text${ANSI.RESET}" else text

    /** KS keyword style (cyan). */
    private fun kw(text: String, colorEnabled: Boolean): String =
        if (colorEnabled) "${ANSI.CYAN}$text${ANSI.RESET}" else text

    /** Comment style (gray). */
    private fun comment(text: String, colorEnabled: Boolean): String =
        if (colorEnabled) "${GRAY}$text${ANSI.RESET}" else text

    // ====================================================================
    // Type Name Derivation
    // ====================================================================

    /**
     * Derive a KS type name from a runtime value for display in the REPL.
     *
     * Maps Kotlin/JVM types back to their KS names (e.g. `Int`, `String`,
     * `List<...>`). Returns a user-friendly KS type name.
     */
    fun ksTypeName(value: Any?): String {
        return when (value) {
            null                          -> "Nil"
            is Int                        -> "Int"
            is Long                       -> "Long"
            is Float                      -> "Float"
            is Double                     -> "Double"
            is java.math.BigDecimal       -> "Dec"
            is String                     -> "String"
            is Char                       -> "Char"
            is Boolean                    -> "Bool"
            is List<*>                    -> "List"
            is Map<*, *>                  -> "Map"
            else -> {
                // Try known KS runtime types by simple name
                val name = value::class.simpleName ?: "Any"
                when {
                    name == "KSLambda"       -> "Lambda"
                    name == "KSFunction"     -> "Function"
                    name.startsWith("KS")    -> name.removePrefix("KS")
                    name.startsWith("KD")    -> name
                    else                     -> name
                }
            }
        }
    }
}