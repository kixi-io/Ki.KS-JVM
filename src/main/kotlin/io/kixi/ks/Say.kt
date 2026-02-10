package io.kixi.ks

import java.io.PrintWriter

/**
 * Kotlin equivalent of the KS `say` statement and its variants.
 *
 * Provides four output methods mirroring KS syntax:
 *
 * | KS                   | Kotlin                        | Stream | Style  |
 * |----------------------|-------------------------------|--------|--------|
 * | `say "hello"`        | `say("hello")`                | stdout | plain  |
 * | `say.warn "caution"` | `say.warn("caution")`         | stdout | orange |
 * | `say.error "oops"`   | `say.error("oops")`           | stderr | red    |
 * | `say.note "notice"`  | `say.note("notice")`          | stdout | bold   |
 *
 * Multiple values are joined with ", " (comma-space):
 *
 * ```kotlin
 * say("hello!", "bula!")           // hello!, bula!
 * say.warn("danger")              // danger       (orange)
 * say.error("Explosion", "Yikes") // Explosion, Yikes  (red, stderr)
 * say.note("Notice")              // Notice       (bold)
 * ```
 *
 * Color output is enabled by default and uses the same [ANSI] codes as the
 * KS interpreter. Disable with [say.colorEnabled] = false, or redirect
 * output with [say.out] / [say.err].
 */
object say {

    // ====================================================================
    // Configuration
    // ====================================================================

    /** Whether ANSI color/style codes are emitted. Default: `true`. */
    var colorEnabled: Boolean = true

    /** Standard output writer. Default: `System.out`. */
    var out: PrintWriter = PrintWriter(System.out, true)

    /** Error output writer. Default: `System.err`. */
    var err: PrintWriter = PrintWriter(System.err, true)

    // ====================================================================
    // Public API
    // ====================================================================

    /**
     * Print values to stdout (plain — no styling).
     *
     * Calling with no arguments prints a blank line.
     *
     * ```kotlin
     * Say("hello!", "bula!")   // hello!, bula!
     * Say()                    // (blank line)
     * ```
     */
    operator fun invoke(vararg values: Any?) {
        out.println(join(values))
    }

    /**
     * Print values to stdout in **orange** (warning).
     *
     * ```kotlin
     * Say.warn("low disk space")
     * ```
     */
    fun warn(vararg values: Any?) {
        out.println(ANSI.warn(join(values), colorEnabled))
    }

    /**
     * Print values to **stderr** in **red** (error).
     *
     * ```kotlin
     * Say.error("Explosion", "Yikes")
     * ```
     */
    fun error(vararg values: Any?) {
        out.println(ANSI.error(join(values), colorEnabled))
    }

    /**
     * Print values to stdout in **bold** (note / emphasis).
     *
     * ```kotlin
     * Say.note("Build succeeded")
     * ```
     */
    fun note(vararg values: Any?) {
        out.println(ANSI.bold(join(values), colorEnabled))
    }

    // ====================================================================
    // Internal
    // ====================================================================

    /**
     * Join values with ", ". Each value is stringified via [stringify].
     */
    private fun join(values: Array<out Any?>): String =
        values.joinToString(", ") { stringify(it) }

    /**
     * Convert a value to its display string.
     *
     * `null` renders as `"nil"` for consistency with KS.
     */
    private fun stringify(value: Any?): String = when (value) {
        null -> "nil"
        is String -> value
        else -> value.toString()
    }
}