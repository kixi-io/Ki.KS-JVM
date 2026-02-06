package io.kixi.ks.parser

import io.kixi.ks.SourceLocation
import io.kixi.text.ParseException

/**
 * Exception thrown during parsing when unexpected tokens or invalid syntax are
 * encountered.
 *
 * Extends Ki.Core's [ParseException] (which extends [io.kixi.KiException]) for
 * ecosystem consistency and access to the [suggestion] property, which provides
 * actionable fix hints in error messages.
 *
 * Parse failures are recoverable — the REPL catches them and prompts again, IDE
 * parsers use them for diagnostics while continuing to parse, and `${expr}`
 * interpolation falls back gracefully on parse failure.
 *
 * Paired with [io.kixi.ks.lexer.LexerException], which covers the lexing phase.
 * Together they form the source-processing exception layer, both extending
 * [ParseException] so a single `catch(e: ParseException)` handles both.
 *
 * The [location] points to the offending token's position in source code for
 * error reporting and IDE integration.
 *
 * ## Message Format
 *
 * Overrides [ParseException.message] to use KS's `[line:col]` format instead of
 * ParseException's `ClassName "msg" line: N index: N` format:
 *
 *     [3:15] Expected ')' after expression
 *     [3:15] Expected ')' after expression Suggestion: Check for unmatched parentheses
 *
 * @param msg The error description
 * @param location Source position of the error (null for position-unknown errors)
 * @param suggestion Optional actionable hint for fixing the error
 */
class ParseException(
    private val msg: String,
    val location: SourceLocation? = null,
    suggestion: String? = null
) : ParseException(
    msg,
    location?.line ?: -1,
    location?.column ?: -1,
    null,
    suggestion
) {
    /**
     * Formatted an error message with location prefix and optional suggestion.
     *
     * Built directly from [msg] and [location] to avoid the super-chain's
     * formatting (which would double-append the suggestion).
     */
    override val message: String
        get() {
            var result = if (location != null) "[${location.line}:${location.column}] $msg" else msg
            if (suggestion != null) result += " Suggestion: $suggestion"
            return result
        }
}