package io.kixi.ks.parser

import io.kixi.ks.lexer.Token
import io.kixi.ks.lexer.TokenType
import io.kixi.ks.lexer.TokenType.*

/**
 * Expression parser for the KS language.
 *
 * Implements precedence-climbing recursive descent for all KS expressions.
 * Handles 15 precedence levels (lowest to highest):
 *
 *   1. Assignment:     =  +=  -=  *=  /=  %=  **=   (right-assoc)
 *   2. Ternary:        condition ? then : else
 *   3. Disjunction:    ||
 *   4. Conjunction:    &&
 *   5. Equality:       ==  !=
 *   6. Comparison:     <  >  <=  >=
 *   7. Named checks:   in  !in  is  !is  matches  as
 *   8. Infix calls:    a add b, list zip other     (left-assoc)
 *   9. Elvis:          ?:
 *  10. Range:          ..  ..<  <..  <..<
 *  11. Addition:       +  -
 *  12. Multiplication: *  /  %  \u26ad
 *  13. Exponentiation: **                            (right-assoc)
 *  14. Unary prefix:   -  !  ++  --
 *  15. Postfix:        .  ?.  ()  []  !!  ++  --  ::class
 *
 * Primary expressions (literals, identifiers, if, when, try, etc.) form
 * the base of the precedence hierarchy.
 *
 * @param p Reference to the parent [Parser] for token stream access.
 */
class ExpressionParser(internal val p: Parser) {

    // ====================================================================
    // Entry Point
    // ====================================================================

    /** Parse a full expression (entry point for all expression parsing). */
    fun parseExpression(): Expr = parseAssignment()

    // ====================================================================
    // 1. Assignment (lowest precedence, right-associative)
    // ====================================================================

    /**
     * Parse assignment or fall through to ternary.
     *
     *     x = 5           ASSIGN
     *     x += 1          PLUS_ASSIGN
     *     arr[0] -= 2     MINUS_ASSIGN
     *     obj.f **= 3     POWER_ASSIGN
     *
     * Assignment is right-associative: `a = b = c` → `a = (b = c)`.
     * The left side must be a valid target: [IdentifierExpr], [MemberAccessExpr],
     * or [IndexExpr].
     */
    private fun parseAssignment(): Expr {
        val expr = parseTernary()
        val loc = p.currentLocation()

        val op = matchAssignOp() ?: return expr

        val value = parseExpression() // right-recursive for right-assoc
        validateAssignTarget(expr)
        return AssignExpr(expr, op, value, loc)
    }

    /** Check for and consume an assignment operator; return its [AssignOp] or null. */
    private fun matchAssignOp(): AssignOp? = when {
        p.match(EQUAL)           -> AssignOp.ASSIGN
        p.match(PLUS_EQUAL)      -> AssignOp.PLUS_ASSIGN
        p.match(MINUS_EQUAL)     -> AssignOp.MINUS_ASSIGN
        p.match(STAR_EQUAL)      -> AssignOp.STAR_ASSIGN
        p.match(SLASH_EQUAL)     -> AssignOp.SLASH_ASSIGN
        p.match(PERCENT_EQUAL)   -> AssignOp.MODULO_ASSIGN
        p.match(STAR_STAR_EQUAL) -> AssignOp.POWER_ASSIGN
        else -> null
    }

    /** Ensure the left side of an assignment is a valid target. */
    private fun validateAssignTarget(expr: Expr) {
        when (expr) {
            is IdentifierExpr, is MemberAccessExpr, is IndexExpr -> { /* valid */ }
            else -> p.errorAt(
                p.previous(),
                "Invalid assignment target (must be a variable, member, or index)"
            )
        }
    }

    // ====================================================================
    // 2. Ternary: condition ? thenExpr : elseExpr
    // ====================================================================

    private fun parseTernary(): Expr {
        val expr = parseDisjunction()

        if (p.match(QUESTION)) {
            val loc = p.previousLocation()
            val thenBranch = parseExpression()
            p.expect(COLON, "Expected ':' in ternary expression")
            val elseBranch = parseExpression()
            return TernaryExpr(expr, thenBranch, elseBranch, loc)
        }

        return expr
    }

    // ====================================================================
    // 3. Disjunction: ||
    // ====================================================================

    private fun parseDisjunction(): Expr {
        var expr = parseConjunction()
        while (p.match(PIPE_PIPE)) {
            val loc = p.previousLocation()
            val right = parseConjunction()
            expr = BinaryExpr(expr, BinaryOp.OR, right, loc)
        }
        return expr
    }

    // ====================================================================
    // 4. Conjunction: &&
    // ====================================================================

    private fun parseConjunction(): Expr {
        var expr = parseEquality()
        while (p.match(AMP_AMP)) {
            val loc = p.previousLocation()
            val right = parseEquality()
            expr = BinaryExpr(expr, BinaryOp.AND, right, loc)
        }
        return expr
    }

    // ====================================================================
    // 5. Equality: == !=
    // ====================================================================

    private fun parseEquality(): Expr {
        var expr = parseComparison()
        while (true) {
            val loc = p.currentLocation()
            val op = when {
                p.match(EQUAL_EQUAL) -> BinaryOp.EQUAL
                p.match(BANG_EQUAL)  -> BinaryOp.NOT_EQUAL
                else -> break
            }
            val right = parseComparison()
            expr = BinaryExpr(expr, op, right, loc)
        }
        return expr
    }

    // ====================================================================
    // 6. Comparison: < > <= >=
    // ====================================================================

    private fun parseComparison(): Expr {
        var expr = parseNamedCheck()
        while (true) {
            val loc = p.currentLocation()
            val op = when {
                p.match(LESS_EQUAL)    -> BinaryOp.LESS_EQUAL
                p.match(GREATER_EQUAL) -> BinaryOp.GREATER_EQUAL
                p.match(LESS)          -> BinaryOp.LESS
                p.match(GREATER)       -> BinaryOp.GREATER
                else -> break
            }
            val right = parseNamedCheck()
            expr = BinaryExpr(expr, op, right, loc)
        }
        return expr
    }

    // ====================================================================
    // 7. Named Checks: in, !in, is, !is, matches, as
    // ====================================================================

    /**
     * Named check operators have the same precedence and left-associate.
     *
     *     x in list          InCheckExpr
     *     x !in range        InCheckExpr(negated=true)
     *     x is String        TypeCheckExpr
     *     x !is Int          TypeCheckExpr(negated=true)
     *     text matches @"p"  MatchesExpr
     *     x as String        TypeCastExpr
     */
    private fun parseNamedCheck(): Expr {
        var expr = parseInfixCall()

        while (true) {
            val loc = p.currentLocation()
            when {
                p.match(IN) -> {
                    val container = parseInfixCall()
                    expr = InCheckExpr(expr, container, negated = false, loc)
                }
                p.match(NOT_IN) -> {
                    val container = parseInfixCall()
                    expr = InCheckExpr(expr, container, negated = true, loc)
                }
                p.match(IS) -> {
                    val type = p.types.parseTypeRef()
                    expr = TypeCheckExpr(expr, type, negated = false, loc)
                }
                p.match(NOT_IS) -> {
                    val type = p.types.parseTypeRef()
                    expr = TypeCheckExpr(expr, type, negated = true, loc)
                }
                p.match(MATCHES) -> {
                    val pattern = parseInfixCall()
                    expr = MatchesExpr(expr, pattern, loc)
                }
                p.match(AS) -> {
                    val type = p.types.parseTypeRef()
                    expr = TypeCastExpr(expr, type, loc)
                }
                else -> break
            }
        }

        return expr
    }

    // ====================================================================
    // 8. Infix Calls: a add b, list zip other
    // ====================================================================

    /**
     * Parse infix function calls: `receiver name argument`.
     *
     * After parsing the left operand via [parseElvis], if the next token is
     * an [IDENTIFIER] AND the token after that can start an infix right
     * operand, we consume the identifier as an infix function name and parse
     * the right operand.
     *
     * ## Lookahead Safety
     *
     * KS uses delimiter-free syntax for `while condition body`,
     * `if condition body`, etc. A naive "IDENTIFIER followed by
     * expression-start" check is too aggressive — it would eat into the
     * body statement:
     *
     *     while i < 5 i++            // i++ is the body, not an infix call
     *     while i < 5 doSomething()  // doSomething() is the body
     *
     * To prevent this, the right-operand lookahead is restricted to tokens
     * that unambiguously begin a value: **identifiers and literals only**.
     * Operators (`++`, `-`, `!`), delimiters (`(`, `[`, `{`), and keywords
     * (`if`, `when`) are excluded. For complex right operands, use dot-call
     * syntax: `a.add(b + c)` or `a.add(-b)`.
     *
     * Left-associative: `a foo b bar c` parses as `(a.foo(b)).bar(c)`.
     *
     * Runtime validation in [Interpreter.evaluateInfixCall] verifies that
     * the resolved function is declared with the `infix` modifier.
     */
    private fun parseInfixCall(): Expr {
        var expr = parseElvis()

        while (p.check(IDENTIFIER)) {
            // The token after the identifier must unambiguously begin a value.
            // See canStartInfixRightOperand() for the rationale.
            val afterIdent = p.peekAt(1).type
            if (!canStartInfixRightOperand(afterIdent)) break

            val loc = p.currentLocation()
            val name = p.advance().value   // consume infix function name
            val right = parseElvis()
            expr = InfixCallExpr(expr, name, right, loc)
        }

        return expr
    }

    /**
     * Whether a token type can start the right operand of an infix call.
     *
     * This is deliberately more conservative than [Parser.canStartExpression].
     * Only identifiers and literal tokens qualify — operators, delimiters, and
     * keywords are excluded to prevent the parser from greedily consuming
     * tokens that belong to a subsequent statement or block body.
     *
     * Excluded categories and their ambiguity:
     *   - `LPAREN`: `ident(args)` looks like a function call, not an infix
     *     operand. Use dot-call: `a.add(b + c)`.
     *   - `LBRACKET`: `ident[i]` looks like index access.
     *   - `LBRACE`: could be a block body (while/if/for).
     *   - `PLUS_PLUS`, `MINUS_MINUS`: ambiguous with postfix on the identifier
     *     itself (`i++`).
     *   - `MINUS`, `BANG`: ambiguous with the start of a new statement
     *     (`-x` or `!flag`).
     *   - `IF`, `WHEN`, `TRY`: could be the start of a control-flow body.
     *   - `DOT`, `UNDERSCORE`, `DOLLAR`, `LANG`: edge cases not worth the risk.
     *
     * For any excluded pattern, the user can use dot-call syntax instead:
     * `a.add(-b)`, `a.add(b + c)`, `a.add(someFunc(x))`.
     */
    private fun canStartInfixRightOperand(type: TokenType): Boolean = when (type) {
        // Identifiers
        IDENTIFIER,
            // Numeric literals
        INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL, DEC_LITERAL,
            // Quantity / currency / version literals
        QUANTITY_LITERAL, CURRENCY_QUANTITY_LITERAL, VERSION_LITERAL,
            // String literals
        STRING_LITERAL, VERBATIM_STRING, MULTILINE_STRING, VERBATIM_MULTILINE, BACKTICK_STRING,
            // Other literals
        CHAR_LITERAL, TRUE, FALSE, NIL, URL_LITERAL -> true
        else -> false
    }

    // ====================================================================
    // 9. Elvis: ?:
    // ====================================================================

    private fun parseElvis(): Expr {
        var expr = parseRange()
        while (p.match(ELVIS)) {
            val loc = p.previousLocation()
            val right = parseRange()
            expr = BinaryExpr(expr, BinaryOp.ELVIS, right, loc)
        }
        return expr
    }

    // ====================================================================
    // 10. Range: .. ..< <.. <..<
    // ====================================================================

    /**
     * Parse range expressions. Each range operator produces a [RangeExpr]
     * with appropriate exclusivity flags.
     *
     *     1..10        inclusive both (startExclusive=false, endExclusive=false)
     *     0..<5        exclusive end
     *     a<..b        exclusive start
     *     a<..<b       exclusive both
     *     x.._         open right (end=null)
     *     x<.._        open right, exclusive start
     *
     * Open-left ranges (`_..x`, `_<..x`) are handled in [parsePrimary].
     */
    private fun parseRange(): Expr {
        val expr = parseAddition()
        val loc = p.currentLocation()

        return when {
            p.match(DOT_DOT) -> {
                val end = parseRangeEnd()
                RangeExpr(expr, end, startExclusive = false, endExclusive = false, loc)
            }
            p.match(DOT_DOT_LESS) -> {
                val end = parseRangeEnd()
                RangeExpr(expr, end, startExclusive = false, endExclusive = true, loc)
            }
            p.match(LESS_DOT_DOT) -> {
                val end = parseRangeEnd()
                RangeExpr(expr, end, startExclusive = true, endExclusive = false, loc)
            }
            p.match(LESS_DOT_DOT_LESS) -> {
                val end = parseRangeEnd()
                RangeExpr(expr, end, startExclusive = true, endExclusive = true, loc)
            }
            else -> expr
        }
    }

    /** Parse the right side of a range, treating `_` as open-end (null). */
    private fun parseRangeEnd(): Expr? {
        return if (p.match(UNDERSCORE)) null else parseAddition()
    }

    // ====================================================================
    // 11. Addition: + -
    // ====================================================================

    private fun parseAddition(): Expr {
        var expr = parseMultiplication()
        while (true) {
            val loc = p.currentLocation()
            val op = when {
                p.match(PLUS)  -> BinaryOp.ADD
                p.match(MINUS) -> BinaryOp.SUBTRACT
                else -> break
            }
            val right = parseMultiplication()
            expr = BinaryExpr(expr, op, right, loc)
        }
        return expr
    }

    // ====================================================================
    // 12. Multiplication: * / % ⚭
    // ====================================================================

    private fun parseMultiplication(): Expr {
        var expr = parsePower()
        while (true) {
            val loc = p.currentLocation()
            val op = when {
                p.match(STAR)    -> BinaryOp.MULTIPLY
                p.match(SLASH)   -> BinaryOp.DIVIDE
                p.match(PERCENT) -> BinaryOp.MODULO
                p.match(COMBINE) -> BinaryOp.COMBINE
                else -> break
            }
            val right = parsePower()
            expr = BinaryExpr(expr, op, right, loc)
        }
        return expr
    }

    // ====================================================================
    // 13. Exponentiation: ** (right-associative)
    // ====================================================================

    /** Right-associative: `2**3**4` → `2**(3**4)`. */
    private fun parsePower(): Expr {
        val base = parseUnaryPrefix()
        if (p.match(STAR_STAR)) {
            val loc = p.previousLocation()
            val exponent = parsePower() // right-recursive
            return BinaryExpr(base, BinaryOp.POWER, exponent, loc)
        }
        return base
    }

    // ====================================================================
    // 14. Unary Prefix: - ! ++ --
    // ====================================================================

    private fun parseUnaryPrefix(): Expr {
        val loc = p.currentLocation()

        return when {
            p.match(MINUS) -> {
                val operand = parseUnaryPrefix()
                UnaryExpr(UnaryOp.NEGATE, operand, prefix = true, loc)
            }
            p.match(BANG) -> {
                val operand = parseUnaryPrefix()
                UnaryExpr(UnaryOp.NOT, operand, prefix = true, loc)
            }
            // !! in prefix position is double logical NOT: !!true → !(!true)
            // (In postfix position it remains the non-null assertion operator)
            p.match(BANG_BANG) -> {
                val operand = parseUnaryPrefix()
                UnaryExpr(UnaryOp.NOT, UnaryExpr(UnaryOp.NOT, operand, prefix = true, loc), prefix = true, loc)
            }
            p.match(PLUS_PLUS) -> {
                val operand = parseUnaryPrefix()
                UnaryExpr(UnaryOp.INCREMENT, operand, prefix = true, loc)
            }
            p.match(MINUS_MINUS) -> {
                val operand = parseUnaryPrefix()
                UnaryExpr(UnaryOp.DECREMENT, operand, prefix = true, loc)
            }
            else -> parsePostfix()
        }
    }

    /**
     * Lookahead to determine whether `<` starts generic type arguments for a
     * call (e.g. `Grid<Int>(`) rather than a less-than comparison.
     *
     * Scans forward from `<`, tracking nesting depth. Content between `<` and
     * `>` must consist of valid type-argument tokens: identifiers, commas, `?`,
     * nested `<>`, collection shorthand `[]`, and `:` for map shorthand. Returns
     * true only if `(` immediately follows the closing `>`.
     *
     * This is the same disambiguation rule used by Kotlin, Java, C#, TypeScript,
     * and Swift. In the rare case where `a<b>(c)` was intended as two comparisons,
     * the programmer writes `(a < b) > c`.
     */
    private fun isGenericCallStart(): Boolean {
        var depth = 1
        var offset = 1 // start after '<'

        while (depth > 0) {
            val token = p.peekAt(offset)
            when (token.type) {
                LESS -> depth++
                GREATER -> {
                    depth--
                    if (depth == 0) {
                        return p.peekAt(offset + 1).type == LPAREN
                    }
                }
                // Tokens valid inside type argument lists
                IDENTIFIER, COMMA, QUESTION, LBRACKET, RBRACKET, COLON -> {}
                // Anything else (operators, literals, EOF) → not type arguments
                else -> return false
            }
            offset++
        }
        return false
    }

    // ====================================================================
    // 15. Postfix: . ?. () [] !! ++ -- ::class
    // ====================================================================

    /**
     * Parse postfix operations chained onto a primary expression.
     *
     *     obj.field           member access
     *     obj?.field          safe navigation
     *     foo(1, 2)           call
     *     list[0]             index
     *     x!!                 non-null assertion
     *     i++                 postfix increment
     *     i--                 postfix decrement
     *     x::class            reflection
     */
    private fun parsePostfix(): Expr {
        var expr = parsePrimary()

        while (true) {
            val loc = p.currentLocation()
            expr = when {
                p.match(DOT) -> {
                    val member = p.expectMemberName("Expected member name after '.'")
                    MemberAccessExpr(expr, member, safe = false, loc)
                }

                p.match(QUESTION_DOT) -> {
                    val member = p.expectMemberName("Expected member name after '?.'")
                    MemberAccessExpr(expr, member, safe = true, loc)
                }

                // Generic call: Grid<Int>(2, 3), Map<String, Int>(), etc.
                // Lookahead confirms <TypeArgs>( pattern to avoid conflict
                // with less-than comparison. Same disambiguation rule as
                // Kotlin, Java, C#, TypeScript, and Swift.
                p.check(LESS) && expr is IdentifierExpr
                        && isGenericCallStart() -> {
                    p.advance() // consume
                    val typeArgs = mutableListOf<TypeRef>()
                    typeArgs.add(p.types.parseTypeRef())
                    while (p.match(COMMA)) {
                        typeArgs.add(p.types.parseTypeRef())
                    }
                    p.expect(GREATER, "Expected '>' after type arguments")
                    p.advance() // consume (
                    val args = parseArgumentList()
                    p.expect(RPAREN, "Expected ')' after arguments")
                    CallExpr(expr, args, loc, typeArgs)
                }

                p.check(LPAREN) -> {
                    p.advance() // consume (
                    val args = parseArgumentList()
                    p.expect(RPAREN, "Expected ')' after arguments")
                    CallExpr(expr, args, loc)
                }

                p.check(LBRACKET) -> {
                    p.advance() // consume [
                    val indices = mutableListOf(parseExpression())
                    // Parse additional indices (commas optional, KD-style)
                    while (!p.check(RBRACKET) && !p.isAtEnd()) {
                        p.match(COMMA) // optional comma
                        p.skipSeparators()
                        if (p.check(RBRACKET)) break
                        if (!p.canStartExpression()) break
                        indices.add(parseExpression())
                    }
                    p.expect(RBRACKET, "Expected ']' after index")
                    IndexExpr(expr, indices, loc)
                }

                p.match(BANG_BANG) -> {
                    UnaryExpr(UnaryOp.NON_NULL, expr, prefix = false, loc)
                }

                p.match(PLUS_PLUS) -> {
                    UnaryExpr(UnaryOp.INCREMENT, expr, prefix = false, loc)
                }

                p.match(MINUS_MINUS) -> {
                    UnaryExpr(UnaryOp.DECREMENT, expr, prefix = false, loc)
                }

                p.match(COLON_COLON) -> {
                    // Currently only ::class is supported
                    val memberToken = p.peek()
                    if (memberToken.type == CLASS || memberToken.value == "class") {
                        p.advance()
                        ReflectionExpr(expr, "class", loc)
                    } else {
                        p.error("Expected 'class' after '::' (got '${memberToken.value}')")
                    }
                }

                else -> return expr
            }
        }
    }

    // ====================================================================
    // Primary Expressions
    // ====================================================================

    /**
     * Parse a primary expression — the atoms of the expression grammar.
     *
     * Handles: literals, identifiers, `this`, grouping `(expr)`, collections
     * `[...]`, DPEC `.NAME`, quantities (`23cm`, `$50.25`), open-left ranges
     * `_..x`, and expression-level keywords (`if`, `when`, `try`, `lang`).
     */
    private fun parsePrimary(): Expr {
        val loc = p.currentLocation()

        return when (p.peek().type) {
            // --- Numeric literals ---
            INT_LITERAL    -> { val t = p.advance(); LiteralExpr(t.literal, LiteralKind.INT, t.location) }
            LONG_LITERAL   -> { val t = p.advance(); LiteralExpr(t.literal, LiteralKind.LONG, t.location) }
            FLOAT_LITERAL  -> { val t = p.advance(); LiteralExpr(t.literal, LiteralKind.FLOAT, t.location) }
            DOUBLE_LITERAL -> { val t = p.advance(); LiteralExpr(t.literal, LiteralKind.DOUBLE, t.location) }
            DEC_LITERAL    -> { val t = p.advance(); LiteralExpr(t.literal, LiteralKind.DEC, t.location) }

            // --- Quantity literals ---
            // Unit quantities: 23cm, 51.4m³, 1000kg, 25°C, 97ℓ, 100USD, 5.5e(-7)m
            QUANTITY_LITERAL -> {
                val t = p.advance()
                LiteralExpr(t.literal, LiteralKind.QUANTITY, t.location)
            }
            // Currency quantities with prefix notation: $23.53, €50.25, ¥10000, £75.50
            CURRENCY_QUANTITY_LITERAL -> {
                val t = p.advance()
                LiteralExpr(t.literal, LiteralKind.CURRENCY_QUANTITY, t.location)
            }

            VERSION_LITERAL -> {
                val t = p.advance()
                LiteralExpr(t.literal, LiteralKind.VERSION, t.location)
            }

            // --- String literals ---
            STRING_LITERAL -> {
                val t = p.advance()
                parseStringLiteral(t, LiteralKind.STRING, StringTemplateKind.STANDARD)
            }
            MULTILINE_STRING -> {
                val t = p.advance()
                parseStringLiteral(t, LiteralKind.MULTILINE_STRING, StringTemplateKind.MULTILINE)
            }
            // Verbatim and backtick strings never have interpolation
            VERBATIM_STRING    -> { val t = p.advance(); LiteralExpr(t.literal, LiteralKind.VERBATIM_STRING, t.location) }
            VERBATIM_MULTILINE -> { val t = p.advance(); LiteralExpr(t.literal, LiteralKind.VERBATIM_MULTILINE, t.location) }
            BACKTICK_STRING    -> { val t = p.advance(); LiteralExpr(t.literal, LiteralKind.BACKTICK_STRING, t.location) }

            // --- Other literals ---
            CHAR_LITERAL -> { val t = p.advance(); LiteralExpr(t.literal, LiteralKind.CHAR, t.location) }
            TRUE         -> { val t = p.advance(); LiteralExpr(true, LiteralKind.BOOL, t.location) }
            FALSE        -> { val t = p.advance(); LiteralExpr(false, LiteralKind.BOOL, t.location) }
            NIL          -> { val t = p.advance(); LiteralExpr(null, LiteralKind.NIL, t.location) }
            URL_LITERAL  -> { val t = p.advance(); LiteralExpr(t.literal, LiteralKind.URL, t.location) }

            // --- Identifier or 'this' ---
            IDENTIFIER -> {
                val t = p.advance()
                if (t.value == "this") ThisExpr(t.location)
                else IdentifierExpr(t.value, t.location)
            }

            // --- Interpolation reference: $var, ${expr}, or $(expr) (used in KD blocks) ---
            DOLLAR -> {
                p.advance() // consume $
                if (p.match(LBRACE)) {
                    // ${expr} form
                    val expr = parseExpression()
                    p.expect(RBRACE, "Expected '}' after interpolation expression")
                    expr
                } else if (p.match(LPAREN)) {
                    // $(expr) form
                    val expr = parseExpression()
                    p.expect(RPAREN, "Expected ')' after interpolation expression")
                    expr
                } else {
                    val name = p.expectIdentifier("Expected identifier after '\$'")
                    IdentifierExpr(name, loc)
                }
            }

            // --- Parenthesized expression ---
            LPAREN -> {
                p.advance() // consume (
                val inner = parseExpression()
                p.expect(RPAREN, "Expected ')' after expression")
                inner // parenthesized expression unwraps
            }

            // --- List or Map literal: [...] ---
            LBRACKET -> parseListOrMap()

            // --- Block expression: { ... } ---
            LBRACE -> p.parseBlock()

            // --- Expression keywords ---
            IF   -> parseIfExpr()
            WHEN -> parseWhenExpr()
            TRY  -> parseTryExpr()
            LANG -> parseLangBlock()

            // --- Dot-prefixed: DPEC (.NAME) or Ki literals (.grid, .coordinate) ---
            DOT -> {
                p.advance() // consume .
                val name = p.expectIdentifier("Expected name after '.'")
                when (name) {
                    "grid" -> parseGridLiteral(loc)
                    "coordinate" -> parseCoordinateLiteral(loc)
                    else -> DPECExpr(name, loc)
                }
            }

            // --- Open-left range: _..x or _<..x ---
            UNDERSCORE -> {
                p.advance() // consume _
                val rangeLoc = p.currentLocation()
                when {
                    p.match(DOT_DOT) -> {
                        val end = parseAddition()
                        RangeExpr(null, end, startExclusive = false, endExclusive = false, rangeLoc)
                    }
                    p.match(DOT_DOT_LESS) -> {
                        val end = parseAddition()
                        RangeExpr(null, end, startExclusive = false, endExclusive = true, rangeLoc)
                    }
                    p.match(LESS_DOT_DOT) -> {
                        val end = parseAddition()
                        RangeExpr(null, end, startExclusive = true, endExclusive = false, rangeLoc)
                    }
                    p.match(LESS_DOT_DOT_LESS) -> {
                        val end = parseAddition()
                        RangeExpr(null, end, startExclusive = true, endExclusive = true, rangeLoc)
                    }
                    else -> p.error("Expected range operator after '_'")
                }
            }

            else -> p.error("Expected expression")
        }
    }

    // ====================================================================
    // String Interpolation
    // ====================================================================

    /**
     * Handle string literals that may contain interpolation (`$var`, `${expr}`).
     *
     * The lexer stores the processed string (with escapes resolved) as the literal
     * value. This method checks for `$` markers in the string and, if found,
     * splits it into a [StringTemplateExpr]. If no interpolation is present,
     * returns a plain [LiteralExpr].
     *
     * For `$identifier`, the parser creates an [IdentifierExpr].
     * For `${expr}`, the expression substring is re-lexed and parsed.
     *
     * Note: Full `${expr}` support requires re-lexing. For the initial
     * implementation, `$identifier` is supported directly. Complex expressions
     * in `${...}` will be supported when the lexer emits interpolation tokens.
     */
    private fun parseStringLiteral(token: Token, literalKind: LiteralKind, templateKind: StringTemplateKind): Expr {
        val text = token.literal as? String ?: return LiteralExpr(token.literal, literalKind, token.location)

        // Quick check: if no $ in the string, it's a plain literal
        if ('$' !in text) {
            return LiteralExpr(text, literalKind, token.location)
        }

        // Parse interpolation markers into parts
        val parts = mutableListOf<StringPart>()
        val sb = StringBuilder()
        var i = 0

        while (i < text.length) {
            if (text[i] == '$' && i + 1 < text.length) {
                val next = text[i + 1]
                when {
                    // ${expr} — complex interpolation
                    next == '{' -> {
                        if (sb.isNotEmpty()) { parts.add(LiteralPart(sb.toString())); sb.clear() }
                        val closeIdx = findMatchingBrace(text, i + 2)
                        if (closeIdx == -1) {
                            // Malformed — treat rest as literal
                            sb.append(text.substring(i))
                            i = text.length
                        } else {
                            val exprStr = text.substring(i + 2, closeIdx)
                            val exprNode = parseInterpolatedExpression(exprStr, token.location)
                            parts.add(ExpressionPart(exprNode))
                            i = closeIdx + 1
                        }
                    }
                    // $identifier — simple interpolation
                    next.isLetter() || next == '_' -> {
                        if (sb.isNotEmpty()) { parts.add(LiteralPart(sb.toString())); sb.clear() }
                        val start = i + 1
                        var end = start
                        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
                        val name = text.substring(start, end)
                        parts.add(ExpressionPart(IdentifierExpr(name, token.location)))
                        i = end
                    }
                    else -> {
                        // $ followed by something unexpected — treat as literal $
                        sb.append('$')
                        i++
                    }
                }
            } else {
                sb.append(text[i])
                i++
            }
        }

        if (sb.isNotEmpty()) parts.add(LiteralPart(sb.toString()))

        // If only one literal part, return as plain literal
        if (parts.size == 1 && parts[0] is LiteralPart) {
            return LiteralExpr(text, literalKind, token.location)
        }

        return StringTemplateExpr(parts, templateKind, token.location)
    }

    /** Find the closing `}` that matches the opening `{`, handling nesting. */
    private fun findMatchingBrace(text: String, start: Int): Int {
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    /**
     * Parse a string expression from an interpolation `${expr}`.
     * Re-lexes and parses the expression substring.
     */
    private fun parseInterpolatedExpression(exprStr: String, baseLoc: io.kixi.ks.SourceLocation): Expr {
        return try {
            val lexer = io.kixi.ks.lexer.Lexer(exprStr)
            val tokens = lexer.tokenize()
            val subParser = Parser(tokens)
            subParser.expr.parseExpression()
        } catch (e: Exception) {
            // If re-parsing fails, fall back to an identifier reference
            IdentifierExpr(exprStr.trim(), baseLoc)
        }
    }

    // ====================================================================
    // If Expression
    // ====================================================================

    /**
     * Parse an if expression (returns a value, like Kotlin).
     *
     *     if condition { thenBlock }
     *     if condition { thenBlock } else { elseBlock }
     *     if condition { thenBlock } else if condition2 { ... }
     *     if n <= 1 return n               // single-statement body
     *
     * Parentheses around the condition are optional (parsed naturally
     * as a parenthesized expression if present).
     */
    internal fun parseIfExpr(): IfExpr {
        val loc = p.advance().location // consume IF
        val condition = parseExpression()
        val thenBranch = p.parseSingleOrBlock()

        // Check for else (may be on next line)
        p.skipNewlines()
        val elseBranch = if (p.match(ELSE)) {
            p.skipNewlines()
            if (p.check(IF)) {
                // else if chain — parse as another IfExpr
                parseIfExpr()
            } else {
                p.parseSingleOrBlock()
            }
        } else {
            null
        }

        return IfExpr(condition, thenBranch, elseBranch, loc)
    }

    // ====================================================================
    // When Expression
    // ====================================================================

    /**
     * Parse a when expression (Kotlin-style pattern matching).
     *
     * Subject-based:
     *     when problem {
     *         Problem.CONNECTION -> "connection"
     *         .SUCCESS, .WARNING -> "ok-ish"
     *         is String -> "text"
     *         in 1..10 -> "small"
     *         matches @"pattern" -> "matched"
     *         else -> "fallback"
     *     }
     *
     * Condition-based (no subject):
     *     when {
     *         level < 10 -> "OK"
     *         level > 9  -> "Danger"
     *         else -> "Huh?"
     *     }
     *
     * Subject presence: if `{` follows `when` (possibly after newlines),
     * it's condition-style. Otherwise, an expression is parsed as the subject.
     */
    internal fun parseWhenExpr(): WhenExpr {
        val loc = p.advance().location // consume WHEN
        p.skipNewlines()

        // Determine if there's a subject
        val subject: Expr? = if (p.check(LBRACE)) {
            null
        } else {
            val subj = parseExpression()
            p.skipNewlines()
            subj
        }

        val hasSubject = subject != null
        p.expect(LBRACE, "Expected '{' in when expression")

        val branches = mutableListOf<WhenBranch>()
        p.skipSeparators()

        while (!p.check(RBRACE) && !p.isAtEnd()) {
            if (p.check(ELSE)) {
                // Else branch — must be last
                val elseLoc = p.advance().location
                p.expect(ARROW, "Expected '->' after 'else' in when")
                val body = p.parseSingleOrBlock()
                branches.add(WhenBranch(emptyList(), body, isElse = true, elseLoc))
                p.skipSeparators()
                break
            }

            branches.add(parseWhenBranch(hasSubject))
            p.skipSeparators()
        }

        p.expect(RBRACE, "Expected '}' to close when expression")
        return WhenExpr(subject, branches, loc)
    }

    /**
     * Parse a single when branch: `matchers -> body`.
     * Matchers are comma-separated.
     */
    private fun parseWhenBranch(hasSubject: Boolean): WhenBranch {
        val loc = p.currentLocation()
        val matchers = mutableListOf<WhenMatcher>()

        matchers.add(parseWhenMatcher(hasSubject))
        while (p.match(COMMA)) {
            p.skipSeparators()
            matchers.add(parseWhenMatcher(hasSubject))
        }

        p.expect(ARROW, "Expected '->' in when branch")
        val body = p.parseSingleOrBlock()

        return WhenBranch(matchers, body, isElse = false, loc)
    }

    /**
     * Parse a single when matcher.
     *
     * For subject-based when, matchers can be:
     *   - `.NAME`          DPEC match
     *   - `is Type`        type check
     *   - `!is Type`       negated type check
     *   - `in expr`        containment
     *   - `!in expr`       negated containment
     *   - `matches expr`   regex pattern
     *   - any expression   value match
     *
     * For condition-based when (no subject), matchers are just expressions.
     */
    private fun parseWhenMatcher(hasSubject: Boolean): WhenMatcher {
        val loc = p.currentLocation()

        if (!hasSubject) {
            // Condition-style: each matcher is a full expression
            return ExpressionMatcher(parseExpression(), loc)
        }

        // Subject-based: check for pattern matchers first
        return when {
            // DPEC: .NAME
            p.check(DOT) && p.checkNext(IDENTIFIER) -> {
                p.advance() // consume .
                val name = p.advance().value
                DPECMatcher(name, loc)
            }

            // Type check: is Type / !is Type
            p.check(IS) -> {
                p.advance()
                val type = p.types.parseTypeRef()
                TypeMatcher(type, negated = false, loc)
            }
            p.check(NOT_IS) -> {
                p.advance()
                val type = p.types.parseTypeRef()
                TypeMatcher(type, negated = true, loc)
            }

            // Containment: in expr / !in expr
            p.check(IN) -> {
                p.advance()
                val container = parseExpression()
                InMatcher(container, negated = false, loc)
            }
            p.check(NOT_IN) -> {
                p.advance()
                val container = parseExpression()
                InMatcher(container, negated = true, loc)
            }

            // Pattern: matches expr
            p.check(MATCHES) -> {
                p.advance()
                val pattern = parseExpression()
                PatternMatcher(pattern, loc)
            }

            // Default: value match (expression)
            else -> ExpressionMatcher(parseExpression(), loc)
        }
    }

    // ====================================================================
    // Try Expression
    // ====================================================================

    /**
     * Parse a try expression.
     *
     *     try { body } catch(e: Type) { handler } finally { cleanup }
     *     try { body } catch(e) { handler }
     *     try { body } catch(*) { handler }           // catch-all wildcard
     *
     * The try body and catch/finally bodies are always blocks.
     * Multiple catch clauses are supported.
     */
    internal fun parseTryExpr(): TryExpr {
        val loc = p.advance().location // consume TRY
        p.skipNewlines()
        val body = p.parseBlock()

        val catches = mutableListOf<CatchClause>()
        p.skipNewlines()

        while (p.check(CATCH)) {
            val catchLoc = p.advance().location // consume CATCH
            p.expect(LPAREN, "Expected '(' after 'catch'")

            if (p.match(STAR)) {
                // catch(*) — wildcard catch-all
                p.expect(RPAREN, "Expected ')' after '*' in catch")
                p.skipNewlines()
                val catchBody = p.parseBlock()
                catches.add(CatchClause(null, null, isCatchAll = true, catchBody, catchLoc))
            } else {
                // catch(name) or catch(name: Type)
                val name = p.expectIdentifier("Expected exception variable name")
                val type = if (p.match(COLON)) p.types.parseTypeRef() else null
                p.expect(RPAREN, "Expected ')' in catch clause")
                p.skipNewlines()
                val catchBody = p.parseBlock()
                catches.add(CatchClause(name, type, isCatchAll = false, catchBody, catchLoc))
            }

            p.skipNewlines()
        }

        val finallyBlock = if (p.match(FINALLY)) {
            p.skipNewlines()
            p.parseBlock()
        } else {
            null
        }

        return TryExpr(body, catches, finallyBlock, loc)
    }

    // ====================================================================
    // Lang Block (DSL)
    // ====================================================================

    /**
     * Parse `lang KD { ... }` DSL block.
     *
     * The language name is an identifier. The block is delegated to the
     * appropriate DSL parser (currently only KD is supported).
     */
    private fun parseLangBlock(): LangBlockExpr {
        val loc = p.advance().location // consume LANG
        val language = p.expectIdentifier("Expected language name after 'lang'")
        val body = p.kd.parseKDBlock()
        return LangBlockExpr(language, body, loc)
    }

    // ====================================================================
    // List / Map Literals
    // ====================================================================

    /**
     * Parse an expression excluding assignment operators.
     *
     * Used for map key disambiguation and constraint parsing: we need to parse
     * without consuming `=` that might be a map separator or variable initializer.
     * Assignment expressions are the lowest precedence, so parsing from ternary
     * down gives us everything except `=`, `+=`, `-=`, etc.
     */
    internal fun parseNonAssignmentExpression(): Expr = parseTernary()

    /**
     * Parse a list or map literal.
     *
     *     []                  empty list
     *     [=]                 empty map
     *     [1, 2, 3]           list (commas)
     *     [1 2 3]             list (comma-optional, KD-style)
     *     [name="Rufus"]      map (key=value)
     *     ['a'=1 'b'=2]       map (comma-optional)
     *
     * Disambiguation: after the first element, if `=` follows, it's a map.
     * Commas are optional; elements can be separated by newlines or adjacency.
     */
    private fun parseListOrMap(): Expr {
        val loc = p.advance().location // consume [
        p.skipSeparators()

        // Empty list: []
        if (p.check(RBRACKET)) {
            p.advance()
            return ListExpr(emptyList(), loc)
        }

        // Empty map: [=]
        if (p.check(EQUAL) && p.checkNext(RBRACKET)) {
            p.advance() // consume =
            p.advance() // consume ]
            return MapExpr(emptyList(), loc)
        }

        // Parse first element WITHOUT assignment to preserve `=` for map detection
        val first = parseNonAssignmentExpression()

        // Check for map: first expression followed by = (but not == or =>)
        if (p.check(EQUAL) && !p.checkNext(EQUAL)) {
            return parseMapRest(first, loc)
        }

        // It's a list
        return parseListRest(first, loc)
    }

    /** Continue parsing map entries after the first key and detecting `=`. */
    private fun parseMapRest(firstKey: Expr, loc: io.kixi.ks.SourceLocation): MapExpr {
        val entries = mutableListOf<MapEntry>()

        // First entry: key already parsed, consume = and value
        p.advance() // consume =
        val firstValue = parseExpression()
        entries.add(MapEntry(firstKey, firstValue))

        // Remaining entries
        while (!p.check(RBRACKET) && !p.isAtEnd()) {
            p.match(COMMA)
            p.skipSeparators()
            if (p.check(RBRACKET)) break

            // Parse key WITHOUT assignment to preserve `=` separator
            val key = parseNonAssignmentExpression()
            p.expect(EQUAL, "Expected '=' in map entry")
            val value = parseExpression()
            entries.add(MapEntry(key, value))
        }

        p.expect(RBRACKET, "Expected ']' to close map literal")
        return MapExpr(entries, loc)
    }

    /** Continue parsing list elements after the first element. */
    private fun parseListRest(first: Expr, loc: io.kixi.ks.SourceLocation): ListExpr {
        val elements = mutableListOf(first)

        // Remaining elements: separated by commas, newlines, or adjacency
        while (!p.check(RBRACKET) && !p.isAtEnd()) {
            p.match(COMMA)
            p.skipSeparators()
            if (p.check(RBRACKET)) break
            if (!p.canStartExpression()) break
            elements.add(parseExpression())
        }

        p.expect(RBRACKET, "Expected ']' to close list literal")
        return ListExpr(elements, loc)
    }

    // ====================================================================
    // Argument Lists
    // ====================================================================

    /**
     * Parse a comma/newline-separated argument list (inside parentheses).
     *
     * The opening `(` must already be consumed. The closing `)` is NOT
     * consumed here — the caller handles it.
     *
     * Arguments can be positional or named:
     *     foo(1, 2, 3)
     *     foo(name = "Ada", age = 42)
     *     foo(1, name = "Ada")
     *
     * Commas are optional; newlines serve as separators.
     */
    internal fun parseArgumentList(): List<Argument> {
        val args = mutableListOf<Argument>()
        p.skipSeparators()

        if (p.check(RPAREN)) return args

        args.add(parseArgument())

        while (!p.check(RPAREN) && !p.isAtEnd()) {
            p.match(COMMA)
            p.skipSeparators()
            if (p.check(RPAREN)) break
            if (!p.canStartExpression()) break
            args.add(parseArgument())
        }

        return args
    }

    /**
     * Parse a single argument (positional or named).
     *
     * Named argument: `name = value` — disambiguated from assignment by
     * checking IDENTIFIER followed by single `=` (not `==`).
     */
    private fun parseArgument(): Argument {
        val loc = p.currentLocation()

        // Check for named argument: IDENTIFIER = expr
        if (p.check(IDENTIFIER) && p.checkNext(EQUAL)) {
            // Lookahead to ensure it's = (not ==)
            val afterEqual = p.peekAt(2)
            if (afterEqual.type != EQUAL) {
                val name = p.advance().value // consume identifier
                p.advance() // consume =
                val value = parseExpression()
                return Argument(name, value, loc)
            }
        }

        // Positional argument
        val value = parseExpression()
        return Argument(null, value, loc)
    }

    // ====================================================================
    // Ki Dot-Prefixed Literals: .grid, .coordinate
    // ====================================================================

    /**
     * Parse a grid literal after `.grid` has been consumed.
     *
     * **Data form** — braces contain inline row data:
     *     .grid {                               // untyped
     *         1    2    3
     *         4    5    6
     *     }
     *
     *     .grid<Int> {                          // typed
     *         10   20   30
     *         40   50   60
     *     }
     *
     *     .grid { 1 2 3; 4 5 6; 7 8 9 }        // semicolons separate rows
     *
     * **Dimension form** — parens contain width, height, optional default:
     *     .grid(2, 3)                           // untyped, nil-filled
     *     .grid<Int>(2, 3)                      // typed, zero-filled
     *     .grid<Int>(2, 3, default = 1)         // typed, explicit default
     *
     * Rows are separated by newlines or semicolons. Values within a row
     * are space-separated (commas optional, KD-style). All rows must
     * have the same number of values.
     */
    private fun parseGridLiteral(loc: io.kixi.ks.SourceLocation): GridLiteralExpr {
        // Optional type parameter: <Type>
        val typeParam = if (p.check(LESS)) {
            p.advance() // consume <
            val typeRef = p.types.parseTypeRef()
            p.expect(GREATER, "Expected '>' after grid type parameter")
            typeRef
        } else {
            null
        }

        val typeLabel = if (typeParam != null) "<${typeParam.name}${if (typeParam.nullable) "?" else ""}>" else ""

        // --- Data form: .grid { ... } or .grid<Int> { ... } ---
        if (p.check(LBRACE)) {
            p.advance() // consume {

            val rows = mutableListOf<List<Expr>>()

            // Skip leading separators (newlines/semicolons) inside the braces
            p.skipSeparators()

            // Parse rows until closing brace
            while (!p.check(RBRACE) && !p.isAtEnd()) {
                val row = parseGridRow()
                if (row.isNotEmpty()) {
                    rows.add(row)
                }
                // Rows separated by newlines or semicolons
                p.skipSeparators()
            }

            p.expect(RBRACE, "Expected '}' to close grid data block")

            return GridLiteralExpr(typeParam, rows, loc)
        }

        // --- Dimension form: .grid(w, h) or .grid<Int>(w, h, default = val) ---
        if (p.check(LPAREN)) {
            p.advance() // consume (

            val args = parseArgumentList()

            p.expect(RPAREN, "Expected ')' after .grid$typeLabel dimensions")

            return GridLiteralExpr(typeParam, emptyList(), loc, args)
        }

        p.errorAt(p.peek(), "Expected '{' (data) or '(' (dimensions) after .grid$typeLabel")
    }

    /**
     * Parse a single row of grid values.
     *
     * Values are space-separated (commas optional). A row ends at a
     * newline, semicolon, or closing paren.
     */
    private fun parseGridRow(): List<Expr> {
        val values = mutableListOf<Expr>()

        while (!isGridRowBoundary() && !p.isAtEnd()) {
            if (!p.canStartExpression()) break
            values.add(parseExpression())
            p.match(COMMA) // optional comma between values
        }

        return values
    }

    /**
     * Check if the current token marks the end of a grid row.
     *
     * Rows end at: newline, semicolon, closing brace, or EOF.
     */
    private fun isGridRowBoundary(): Boolean {
        val type = p.peek().type
        return type == NEWLINE || type == SEMICOLON || type == RBRACE || type == EOF
    }

    /**
     * Parse a coordinate literal after `.coordinate` has been consumed.
     *
     * Syntax:
     *     .coordinate(x=0, y=0)               // standard notation
     *     .coordinate(x=4, y=7)
     *     .coordinate(x=0, y=0, z=5)          // with optional z
     *     .coordinate(c="A", r=1)             // sheet notation
     *     .coordinate(c="E", r=8)
     *     .coordinate(c="AA", r=100, z=5)
     *
     * All arguments must be named. Commas are optional.
     */
    private fun parseCoordinateLiteral(loc: io.kixi.ks.SourceLocation): CoordinateLiteralExpr {
        p.expect(LPAREN, "Expected '(' after .coordinate")

        val args = parseArgumentList()

        p.expect(RPAREN, "Expected ')' to close coordinate literal")

        // Validate that all arguments are named
        for (arg in args) {
            if (arg.name == null) {
                p.errorAt(
                    p.previous(),
                    "Coordinate arguments must be named (e.g., x=0, y=0 or c=\"A\", r=1)"
                )
            }
        }

        return CoordinateLiteralExpr(args, loc)
    }
}