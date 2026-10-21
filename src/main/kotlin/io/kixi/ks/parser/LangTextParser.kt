package io.kixi.ks.parser

import io.kixi.ks.lexer.LangBody
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.lexer.Token
import io.kixi.ks.lexer.TokenType

/**
 * Parses the interpolations of an embedded-language block (`lang Name { ... }`,
 * KD included). The lexer has already asked the language where
 * they are ([io.kixi.lang.EmbeddedLanguage.scan]); each one's expression is KS:
 * `$name` is the variable, `${expression}` and `$(expression)` the expression.
 *
 * See `io.kixi.lang.EmbeddedLanguage` in Ki.Core for the contract with the language.
 */
internal object LangTextParser {

    fun interpolations(body: LangBody): List<LangInterpolation> = body.interpolations.map {
        LangInterpolation(it.key, parseExpression(body, it.expressionStart, it.expression), body.locationOf(it.start))
    }

    /** Parses [source], found at [offset] in the block, as one KS expression, with locations in the KS source. */
    private fun parseExpression(body: LangBody, offset: Int, source: String): Expr {
        val raw = try {
            Lexer(source).tokenize()
        } catch (e: io.kixi.ks.lexer.LexerException) {
            throw ParseException(e.message ?: "Cannot read interpolation", body.locationOf(offset + (e.location?.offset ?: 0)))
        }
        val tokens = raw
            .filter { it.type != TokenType.NEWLINE }
            .map { t: Token -> t.copy(location = body.locationOf(offset + t.location.offset)) }
        val parser = Parser(tokens)
        if (parser.isAtEnd()) throw ParseException("Empty interpolation", body.locationOf(offset))
        val expr = parser.expr.parseExpression()
        if (!parser.isAtEnd()) {
            throw ParseException("Unexpected '${parser.peek().value}' in interpolation", parser.peek().location)
        }
        return expr
    }
}
