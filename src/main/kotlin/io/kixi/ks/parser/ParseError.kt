package io.kixi.ks.parser

import io.kixi.ks.SourceLocation

/**
 * Error thrown during parsing when unexpected tokens or invalid syntax are encountered.
 *
 * Mirrors [io.kixi.ks.lexer.LexerError] in structure. The [location] points to the
 * offending token's position in source code for error reporting and IDE integration.
 */
class ParseError(
    message: String,
    val location: SourceLocation? = null
) : RuntimeException(
    if (location != null) "[${location.line}:${location.column}] $message"
    else message
)