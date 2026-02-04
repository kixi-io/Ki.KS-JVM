package io.kixi.ks

/**
 * Represents a position in KS source code. Used by tokens, AST nodes, and error messages.
 * Designed to support future IntelliJ IDE plugin integration.
 *
 * @property file   The source file name (or "<repl>" for REPL input)
 * @property line   1-based line number
 * @property column 1-based column number
 * @property offset 0-based character offset from start of source
 */
data class SourceLocation(
    val line: Int = 1,
    val column: Int = 1,
    val offset: Int = 0,
    val file: String = "<unknown>"
) {
    override fun toString(): String = "$file:$line:$column"

    companion object {
        val UNKNOWN = SourceLocation(0, 0, 0, "<unknown>")
    }
}