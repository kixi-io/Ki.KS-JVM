package io.kixi.ks.lexer
/**
 * Error thrown during lexing when invalid or unexpected characters are encountered.
 */
class LexerError(
    message: String,
    val location: SourceLocation? = null
) : RuntimeException(
    if (location != null) "[${location.line}:${location.column}] $message"
    else message
)