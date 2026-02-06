package io.kixi.ks.lexer

import io.kixi.ks.SourceLocation
import io.kixi.text.ParseException

/**
 * Exception thrown during lexing when invalid or unexpected characters are encountered.
 *
 * Extends Ki.Core's [ParseException] (which extends [io.kixi.KiException]) because
 * lexing is part of the source-processing pipeline and lex failures are recoverable:
 * the REPL catches them and re-prompts, IDE integration uses them as diagnostics, and
 * string interpolation (`${expr}`) falls back gracefully on failure.
 *
 * Renamed from `LexerError` to follow the convention: recoverable conditions are
 * `*Exception`, runtime violations are `*Error`.
 *
 * ## Message Format
 *
 * Uses KS's standard `[line:col]` format:
 *
 *     [3:15] Unexpected character: '@'
 *     [3:15] Unterminated string literal Suggestion: Add a closing '"'
 *
 * @param msg The error description
 * @param location Source position of the error (null for position-unknown errors)
 * @param suggestion Optional actionable hint for fixing the error
 */
class LexerException(
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
     * Formatted error message with location prefix and optional suggestion.
     *
     * Built directly from [msg] and [location] to avoid the super-chain's
     * formatting (which uses `ClassName "msg" line: N index: N`).
     */
    override val message: String
        get() {
            var result = if (location != null) "[${location.line}:${location.column}] $msg" else msg
            if (suggestion != null) result += " Suggestion: $suggestion"
            return result
        }
}