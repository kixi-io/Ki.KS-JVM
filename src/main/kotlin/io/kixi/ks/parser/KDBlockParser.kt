package io.kixi.ks.parser

import io.kixi.ks.lexer.TokenType
import io.kixi.ks.lexer.TokenType.*

/**
 * KD block parser for the KS language.
 *
 * Parses the contents of `lang KD { ... }` blocks into KD tag structures.
 * KD (Ki Data) is KS's native data format — tags with values, attributes,
 * annotations, and children.
 *
 * KD tag structure:
 *     annotation(s)
 *     namespace:name value(s) attribute(s) { children }
 *
 * Examples:
 *     book "The Hobbit" author="Tolkien" published=1937/9/21
 *     things { child1; child2 }
 *     @Personal favorite_books { ... }
 *     config:db host="localhost" port=5432
 *
 * The KS lexer already tokenizes KD content correctly — the same token
 * types (identifiers, literals, `=`, `{`, `}`, `@`, `:`) are used.
 * This parser just interprets them in KD's tag-based grammar instead
 * of KS's expression-based grammar.
 *
 * @param p Reference to the parent [Parser] for token stream access.
 */
class KDBlockParser(internal val p: Parser) {

    // ====================================================================
    // Entry Point
    // ====================================================================

    /**
     * Parse a KD block: `{ tag1; tag2; ... }`.
     *
     * The opening `{` and closing `}` are consumed here. Returns the list
     * of top-level KD tags within the block.
     *
     * Called by [ExpressionParser.parseLangBlock] after consuming `lang KD`.
     */
    fun parseKDBlock(): List<KDTagNode> {
        p.skipNewlines()
        p.expect(LBRACE, "Expected '{' to start KD block")

        val tags = mutableListOf<KDTagNode>()
        p.skipSeparators()

        while (!p.check(RBRACE) && !p.isAtEnd()) {
            tags.add(parseTag())
            p.skipSeparators()
        }

        p.expect(RBRACE, "Expected '}' to close KD block")
        return tags
    }

    // ====================================================================
    // Tag Parsing
    // ====================================================================

    /**
     * Parse a single KD tag.
     *
     * Structure:
     *     @Annotation1 @Annotation2(args)
     *     namespace:name value1 value2 key1=val1 key2=val2 {
     *         child1
     *         child2
     *     }
     *
     * All parts are optional except the name. Values and attributes are
     * interleaved on the same line. Children are in a `{ }` block.
     *
     * Disambiguation between values and attributes:
     *   - If IDENTIFIER is followed by `=`, it's an attribute
     *   - If IDENTIFIER is followed by `:` then `IDENTIFIER =`, it's a
     *     namespaced attribute
     *   - Otherwise, if it's a literal or expression, it's a value
     */
    private fun parseTag(): KDTagNode {
        val loc = p.currentLocation()

        // Parse leading annotations
        val annotations = mutableListOf<KDAnnotationNode>()
        while (p.check(AT)) {
            annotations.add(parseAnnotation())
            p.skipNewlines()
        }

        // Parse tag name with optional namespace
        val (namespace, name) = parseNamespacedIdentifier()

        // Parse values and attributes until end of tag
        val values = mutableListOf<Expr>()
        val attributes = mutableListOf<KDAttribute>()
        parseValuesAndAttributes(values, attributes)

        // Parse optional children block
        val children = if (p.check(LBRACE)) {
            parseChildBlock()
        } else {
            emptyList()
        }

        return KDTagNode(name, namespace, values, attributes, annotations, children, loc)
    }

    /**
     * Parse an identifier with optional namespace prefix.
     *
     *     name           -> (null, "name")
     *     config:db      -> ("config", "db")
     *
     * Namespace is detected by IDENTIFIER followed by `:` followed by
     * another IDENTIFIER (not `::` which is the reflection operator).
     */
    private fun parseNamespacedIdentifier(): Pair<String?, String> {
        val first = p.expectIdentifier("Expected tag name")

        // Check for namespace: first:second
        if (p.check(COLON) && !p.checkNext(COLON)) {
            // Peek ahead to ensure the next token after : is an identifier
            // (not another operator or value)
            val nextType = p.peekAt(2).type
            if (nextType == IDENTIFIER) {
                p.advance() // consume :
                val second = p.advance().value // consume name
                return Pair(first, second)
            }
        }

        return Pair(null, first)
    }

    /**
     * Parse the values and attributes of a KD tag.
     *
     * Values are positional expressions. Attributes are `key=value` or
     * `ns:key=value` pairs. They can be freely interleaved:
     *
     *     book "The Hobbit" author="Tolkien" 1937 genre="Fantasy"
     *
     * Parsing continues until a tag boundary: newline, semicolon, `{`, `}`, EOF.
     */
    private fun parseValuesAndAttributes(
        values: MutableList<Expr>,
        attributes: MutableList<KDAttribute>
    ) {
        while (!isTagBoundary()) {
            if (isAttributeStart()) {
                attributes.add(parseAttribute())
            } else if (isKDValueStart()) {
                values.add(parseKDValue())
            } else {
                break
            }
        }
    }

    /**
     * Check if the current position looks like an attribute: `name=` or `ns:name=`.
     *
     * Must be an IDENTIFIER followed by `=` (simple) or IDENTIFIER followed by
     * `:` then IDENTIFIER then `=` (namespaced).
     */
    private fun isAttributeStart(): Boolean {
        if (p.peek().type != IDENTIFIER) return false

        // Simple attribute: name=
        if (p.peekNext().type == EQUAL) return true

        // Namespaced attribute: ns:name=
        if (p.peekNext().type == COLON) {
            val third = p.peekAt(2)
            if (third.type == IDENTIFIER) {
                val fourth = p.peekAt(3)
                return fourth.type == EQUAL
            }
        }

        return false
    }

    /**
     * Parse a single KD attribute: `key=value` or `ns:key=value`.
     */
    private fun parseAttribute(): KDAttribute {
        val loc = p.currentLocation()
        val first = p.advance().value // consume identifier

        return if (p.check(COLON) && p.peekNext().type == IDENTIFIER) {
            // Namespaced: ns:key=value
            p.advance() // consume :
            val attrName = p.advance().value // consume key
            p.expect(EQUAL, "Expected '=' in KD attribute")
            val value = parseKDValue()
            KDAttribute(attrName, first, value, loc)
        } else {
            // Simple: key=value
            p.expect(EQUAL, "Expected '=' in KD attribute")
            val value = parseKDValue()
            KDAttribute(first, null, value, loc)
        }
    }

    /**
     * Parse a KD value expression.
     *
     * KD values are a subset of KS expressions: literals, identifiers,
     * lists, maps, and ranges. Complex expressions (operators, calls) are
     * not typically used in KD data, but we delegate to the expression
     * parser to handle them uniformly.
     *
     * Before delegating, we check for KD-specific patterns that would
     * confuse the expression parser:
     * - Version literals like `5.2.beta-2` (DOUBLE DOT IDENTIFIER pattern)
     *   which the expression parser would misinterpret as member access
     */
    private fun parseKDValue(): Expr {
        val versionExpr = tryParseVersionLiteral()
        if (versionExpr != null) {
            // Check for range continuation: version .. end
            return tryParseRangeContinuation(versionExpr) ?: versionExpr
        }

        return p.expr.parseExpression()
    }

    /**
     * Tries to parse a version-like literal in KD context.
     *
     * Detects patterns where a number is followed by `.identifier` which in
     * KD represents a version qualifier (e.g., `5.2.beta-2`) rather than
     * member access.
     *
     * Standard multi-dot-digit versions like `5.0.0` are already handled by
     * the lexer. This method catches the remaining case where a dot-separated
     * component is an identifier (qualifier) rather than digits.
     *
     * Returns null if the pattern doesn't match a version.
     */
    private fun tryParseVersionLiteral(): Expr? {
        // Pattern: (DOUBLE|INT) DOT IDENTIFIER (MINUS (INT|IDENTIFIER))*
        val numType = p.peek().type
        if (numType != DOUBLE_LITERAL && numType != INT_LITERAL) return null
        if (p.peekNext().type != DOT) return null

        val afterDot = p.peekAt(2)
        if (afterDot.type != IDENTIFIER) return null

        // This looks like a version qualifier: 5.2.beta or 5.2.rc1
        val loc = p.currentLocation()

        val numToken = p.advance() // consume the number
        p.advance() // consume DOT
        val qualPart = p.advance().value // consume IDENTIFIER (e.g., "beta")

        var versionText = "${numToken.value}.$qualPart"

        // Continue consuming dash-separated qualifier parts: -2, -rc1, etc.
        while (p.check(MINUS) && !isTagBoundary()) {
            val afterMinus = p.peekNext()
            if (afterMinus.type == INT_LITERAL || afterMinus.type == IDENTIFIER) {
                p.advance() // consume MINUS
                versionText += "-${p.advance().value}"
            } else {
                break
            }
        }

        return LiteralExpr(versionText, LiteralKind.STRING, loc)
    }

    /**
     * Checks for a range operator after a parsed value and constructs a
     * RangeExpr if found.
     *
     * This handles cases like `5.2.beta-2 .. 5.9` where the left side was
     * parsed by [tryParseVersionLiteral] and the range operator needs to be
     * consumed separately.
     */
    private fun tryParseRangeContinuation(left: Expr): Expr? {
        val loc = p.currentLocation()
        val startExclusive: Boolean
        val endExclusive: Boolean

        when {
            p.match(DOT_DOT) -> { startExclusive = false; endExclusive = false }
            p.match(DOT_DOT_LESS) -> { startExclusive = false; endExclusive = true }
            p.match(LESS_DOT_DOT) -> { startExclusive = true; endExclusive = false }
            p.match(LESS_DOT_DOT_LESS) -> { startExclusive = true; endExclusive = true }
            else -> return null
        }

        // Parse right side: _ means open-end (null)
        val right: Expr? = if (p.match(UNDERSCORE)) null else parseKDValue()

        return RangeExpr(left, right, startExclusive, endExclusive, loc)
    }

    /**
     * Check if a tag boundary has been reached.
     *
     * Tags are terminated by: newline, semicolon, closing brace, or EOF.
     * An opening brace starts a children block (handled by the caller).
     */
    private fun isTagBoundary(): Boolean {
        val type = p.peek().type
        return type == NEWLINE || type == SEMICOLON || type == RBRACE ||
                type == LBRACE || type == EOF || type == AT
    }

    /**
     * Check if the current token can start a KD value.
     *
     * KD values include: all literals, identifiers, lists `[...]`,
     * parenthesized expressions, DPEC `.NAME`, and unary minus for
     * negative numbers.
     */
    private fun isKDValueStart(): Boolean {
        return p.canStartExpression()
    }

    /**
     * Parse a children block: `{ tag1; tag2; ... }`.
     */
    private fun parseChildBlock(): List<KDTagNode> {
        p.advance() // consume {
        val children = mutableListOf<KDTagNode>()
        p.skipSeparators()

        while (!p.check(RBRACE) && !p.isAtEnd()) {
            children.add(parseTag())
            p.skipSeparators()
        }

        p.expect(RBRACE, "Expected '}' to close KD tag children")
        return children
    }

    // ====================================================================
    // Annotation Parsing
    // ====================================================================

    /**
     * Parse a KD annotation.
     *
     *     @Test                              simple
     *     @Test(true)                        with values
     *     @Test(true log="output.txt")       with values and attributes
     *     @Personal                          custom annotation
     *
     * Annotations are `@Name` optionally followed by `(values attributes)`.
     */
    private fun parseAnnotation(): KDAnnotationNode {
        val loc = p.currentLocation()
        p.advance() // consume @

        val name = p.expectIdentifier("Expected annotation name after '@'")

        val values = mutableListOf<Expr>()
        val attributes = mutableListOf<KDAttribute>()

        // Optional argument list: (values attributes)
        if (p.match(LPAREN)) {
            parseAnnotationArgs(values, attributes)
            p.expect(RPAREN, "Expected ')' to close annotation arguments")
        }

        return KDAnnotationNode(name, values, attributes, loc)
    }

    /**
     * Parse annotation arguments (inside parentheses).
     *
     * KD annotations use the same value/attribute pattern as tags:
     * positional values followed by or interleaved with `key=value` attributes.
     * Commas are optional (KD-style).
     */
    private fun parseAnnotationArgs(
        values: MutableList<Expr>,
        attributes: MutableList<KDAttribute>
    ) {
        p.skipSeparators()

        while (!p.check(RPAREN) && !p.isAtEnd()) {
            if (isAttributeStart()) {
                attributes.add(parseAttribute())
            } else if (p.canStartExpression()) {
                values.add(parseKDValue())
            } else {
                break
            }

            // Optional comma/newline separators
            p.match(COMMA)
            p.skipSeparators()
        }
    }
}