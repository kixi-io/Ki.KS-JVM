package io.kixi.ks.lexer

/**
 * A single token produced by the KS Lexer.
 *
 * @property type     The token type (keyword, operator, literal, etc.)
 * @property value    The raw source text that produced this token
 * @property literal  The parsed literal value (e.g. Int, String, Double), or null for non-literals
 * @property location The position in source code for error reporting / IDE integration
 */
data class Token(
    val type: TokenType,
    val value: String,
    val literal: Any? = null,
    val location: SourceLocation = SourceLocation()
) {
    override fun toString(): String {
        return if (literal != null) {
            "Token($type, '$value', literal=$literal, ${location.line}:${location.column})"
        } else {
            "Token($type, '$value', ${location.line}:${location.column})"
        }
    }
}