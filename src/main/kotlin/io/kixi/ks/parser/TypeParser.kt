package io.kixi.ks.parser

import io.kixi.ks.SourceLocation
import io.kixi.ks.lexer.TokenType
import io.kixi.ks.lexer.TokenType.*

/**
 * Type and parameter parser for the KS language.
 *
 * Handles:
 *   - Type references: `Int`, `String?`, `List<Int>`, `Map<String, Any?>`,
 *     `[Int]` (list shorthand), `[String:Int]` (map shorthand)
 *   - Constraints: `> 25`, `1..100`, `in [1,2,3]`, `matches "pattern"`
 *   - Function parameters: `name: String`, `level`, `n: Int in 1..100 = 5`
 *   - Constructor parameters: `let name: String`, `var age: Int = 0`
 *
 * Called by [DeclarationParser] for var/let types, function params, class
 * constructors, and enum value types. Also called by [ExpressionParser] for
 * `is`/`!is`/`as` type operands.
 *
 * @param p Reference to the parent [Parser] for token stream access.
 */
class TypeParser(internal val p: Parser) {

    // ====================================================================
    // Type References
    // ====================================================================

    /**
     * Parse a type reference.
     *
     *     Int                  simple
     *     String?              nullable
     *     List<Int>            generic with type args
     *     Map<String, Any?>    multi-param generic
     *     [Int]                list shorthand -> List<Int>
     *     [String:Int]         map shorthand  -> Map<String, Int>
     */
    fun parseTypeRef(): TypeRef {
        val loc = p.currentLocation()

        // List/Map shorthand: [Int] or [String:Int]
        if (p.check(LBRACKET)) {
            return parseCollectionShorthand(loc)
        }

        // Standard type: Name<TypeArgs>?
        val name = p.expectIdentifier("Expected type name")
        val typeArgs = if (p.check(LESS)) parseTypeArgList() else emptyList()
        val nullable = p.match(QUESTION)

        return TypeRef(name, typeArgs, nullable, loc)
    }

    /**
     * Parse collection type shorthand.
     *
     *     [Int]          -> List<Int>
     *     [String:Int]   -> Map<String, Int>
     */
    private fun parseCollectionShorthand(loc: SourceLocation): TypeRef {
        p.advance() // consume [

        val firstType = parseTypeRef()

        return if (p.match(COLON)) {
            // Map shorthand: [Key:Value]
            val valueType = parseTypeRef()
            p.expect(RBRACKET, "Expected ']' in map type shorthand")
            val nullable = p.match(QUESTION)
            TypeRef.mapOf(firstType, valueType, loc).let {
                if (nullable) it.copy(nullable = true) else it
            }
        } else {
            // List shorthand: [Element]
            p.expect(RBRACKET, "Expected ']' in list type shorthand")
            val nullable = p.match(QUESTION)
            TypeRef.listOf(firstType, loc).let {
                if (nullable) it.copy(nullable = true) else it
            }
        }
    }

    /**
     * Parse generic type argument list: `<Int>`, `<String, Any?>`.
     *
     * The `<` has been detected but not consumed.
     */
    private fun parseTypeArgList(): List<TypeRef> {
        p.advance() // consume <
        val args = mutableListOf<TypeRef>()

        args.add(parseTypeRef())
        while (p.match(COMMA)) {
            args.add(parseTypeRef())
        }

        p.expect(GREATER, "Expected '>' to close type arguments")
        return args
    }

    // ====================================================================
    // Constraints
    // ====================================================================

    /**
     * Try to parse a constraint following a type annotation.
     *
     * Returns `null` if no constraint follows. Called by var/let parsing
     * and parameter parsing after the type reference.
     *
     * Constraint forms:
     *     > 25            ComparisonConstraint (GT)
     *     >= 18           ComparisonConstraint (GTE)
     *     < 100           ComparisonConstraint (LT)
     *     <= 50           ComparisonConstraint (LTE)
     *     == 0            ComparisonConstraint (EQ)
     *     != -1           ComparisonConstraint (NEQ)
     *     1..100          RangeConstraint (parsed as expression)
     *     0.0..<1.0       RangeConstraint
     *     in [1, 2, 3]    InConstraint
     *     matches "[A-Z]+"  MatchesConstraint
     *
     * Disambiguation: a number literal directly after a type (without separator)
     * is treated as the start of a range constraint expression. The expression
     * parser will produce a [RangeExpr] which we wrap in [RangeConstraint].
     */
    fun tryParseConstraint(): Constraint? {
        val loc = p.currentLocation()

        return when {
            // Comparison operators
            p.check(GREATER) && !p.checkNext(GREATER) -> {
                p.advance() // consume >
                val value = p.expr.parseExpression()
                ComparisonConstraint(ComparisonOp.GT, value, loc)
            }
            p.match(GREATER_EQUAL) -> {
                val value = p.expr.parseExpression()
                ComparisonConstraint(ComparisonOp.GTE, value, loc)
            }
            p.check(LESS) && !p.checkNext(DOT) -> {
                p.advance() // consume <
                val value = p.expr.parseExpression()
                ComparisonConstraint(ComparisonOp.LT, value, loc)
            }
            p.match(LESS_EQUAL) -> {
                val value = p.expr.parseExpression()
                ComparisonConstraint(ComparisonOp.LTE, value, loc)
            }
            p.match(EQUAL_EQUAL) -> {
                val value = p.expr.parseExpression()
                ComparisonConstraint(ComparisonOp.EQ, value, loc)
            }
            p.match(BANG_EQUAL) -> {
                val value = p.expr.parseExpression()
                ComparisonConstraint(ComparisonOp.NEQ, value, loc)
            }

            // in [...] or in expr
            p.match(IN) -> {
                val collection = p.expr.parseExpression()
                InConstraint(collection, loc)
            }

            // matches "pattern"
            p.match(MATCHES) -> {
                val pattern = p.expr.parseExpression()
                MatchesConstraint(pattern, loc)
            }

            // Range constraint: a number or expression starting a range
            // e.g. `Int 1..100`, `Double -40.0..60.0`
            isRangeConstraintStart() -> {
                val range = p.expr.parseExpression()
                RangeConstraint(range, loc)
            }

            else -> null
        }
    }

    /**
     * Detect if the current position looks like the start of a range constraint.
     *
     * A range constraint starts with a number literal (or `-` followed by a
     * number) that will eventually contain a `..` operator. We check for
     * numeric literals and negative numbers as a heuristic.
     */
    private fun isRangeConstraintStart(): Boolean {
        val type = p.peek().type
        return type == INT_LITERAL || type == LONG_LITERAL ||
                type == FLOAT_LITERAL || type == DOUBLE_LITERAL ||
                type == DEC_LITERAL ||
                (type == MINUS && isNumericLiteral(p.peekNext().type))
    }

    private fun isNumericLiteral(type: TokenType): Boolean =
        type == INT_LITERAL || type == LONG_LITERAL ||
                type == FLOAT_LITERAL || type == DOUBLE_LITERAL ||
                type == DEC_LITERAL

    // ====================================================================
    // Function Parameters
    // ====================================================================

    /**
     * Parse a parenthesized parameter list for function declarations.
     *
     *     (name: String, age: Int = 0)
     *     (level)                          // untyped
     *     (n: Int in 1..100)               // with constraint
     *     ()                               // empty
     *
     * Opening `(` must already be consumed.
     */
    fun parseParameterList(): List<Parameter> {
        val params = mutableListOf<Parameter>()
        p.skipSeparators()

        if (p.check(RPAREN)) return params

        params.add(parseParameter())
        while (p.match(COMMA)) {
            p.skipSeparators()
            params.add(parseParameter())
        }
        p.skipSeparators()
        return params
    }

    /**
     * Parse a single function parameter.
     *
     *     name: String                typed, no default
     *     level                       untyped (inferred)
     *     pretty: Bool = true         typed, with default
     *     n: Int in 1..100            typed, with constraint
     *     n: Int > 0 = 5              typed, constraint, default
     */
    fun parseParameter(): Parameter {
        val loc = p.currentLocation()
        val name = p.expectIdentifier("Expected parameter name")

        var type: TypeRef? = null
        var constraint: Constraint? = null
        var defaultValue: Expr? = null

        // Optional type annotation: : Type
        if (p.match(COLON)) {
            type = parseTypeRef()
            // Optional constraint after type
            constraint = tryParseConstraint()
        }

        // Optional default value: = expr
        if (p.match(EQUAL)) {
            defaultValue = p.expr.parseExpression()
        }

        return Parameter(name, type, defaultValue, constraint, loc)
    }

    // ====================================================================
    // Constructor Parameters
    // ====================================================================

    /**
     * Parse a parenthesized constructor parameter list.
     *
     *     (let name: String, var age: Int = 0)
     *     (x: Double, y: Double)
     *     (let category = .Shrub)
     *
     * Constructor params optionally have `var`/`let` binding to declare
     * the parameter as a class property.
     *
     * Opening `(` must already be consumed.
     */
    fun parseConstructorParamList(): List<ConstructorParam> {
        val params = mutableListOf<ConstructorParam>()
        p.skipSeparators()

        if (p.check(RPAREN)) return params

        params.add(parseConstructorParam())
        while (p.match(COMMA)) {
            p.skipSeparators()
            params.add(parseConstructorParam())
        }
        p.skipSeparators()
        return params
    }

    /**
     * Parse a single constructor parameter.
     *
     *     let name: String           immutable property
     *     var age: Int = 0           mutable property with default
     *     x: Double                  constructor-only param (no binding)
     *     let category = .Shrub      inferred type with DPEC default
     */
    private fun parseConstructorParam(): ConstructorParam {
        val loc = p.currentLocation()

        // Optional binding: var / let
        val binding: BindingType? = when {
            p.check(VAR) && p.checkNext(IDENTIFIER) -> { p.advance(); BindingType.VAR }
            p.check(LET) && p.checkNext(IDENTIFIER) -> { p.advance(); BindingType.LET }
            else -> null
        }

        val name = p.expectIdentifier("Expected parameter name")

        var type: TypeRef? = null
        var constraint: Constraint? = null
        var defaultValue: Expr? = null

        // Optional type annotation
        if (p.match(COLON)) {
            type = parseTypeRef()
            constraint = tryParseConstraint()
        }

        // Optional default value
        if (p.match(EQUAL)) {
            defaultValue = p.expr.parseExpression()
        }

        return ConstructorParam(binding, name, type, defaultValue, constraint, loc)
    }
}