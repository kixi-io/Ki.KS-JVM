package io.kixi.ks.lexer

import io.kixi.ks.SourceLocation
import io.kixi.lang.EmbeddedInterpolation

/**
 * The text of an embedded-language block, `lang Name { text }`, exactly as
 * written between the braces. [location] is where the text starts in the KS
 * source. [interpolations] are as the language's scan found them, with
 * positions in [text].
 */
data class LangBody(
    val text: String,
    val location: SourceLocation,
    val interpolations: List<EmbeddedInterpolation> = emptyList(),
) {

    /** The KS source location of [index], a position in [text]. */
    fun locationOf(index: Int): SourceLocation {
        var line = location.line
        var column = location.column
        for (k in 0 until index.coerceIn(0, text.length)) {
            if (text[k] == '\n') { line++; column = 1 } else column++
        }
        return location.copy(line = line, column = column, offset = location.offset + index)
    }
}
