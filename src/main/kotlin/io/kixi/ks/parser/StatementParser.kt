package io.kixi.ks.parser

import io.kixi.ks.lexer.TokenType.*

/**
 * Statement parser for the KS language.
 *
 * Handles:
 *   - `for` loops — both traditional (`for i in list`) and simplified (`for list`)
 *   - `while` loops
 *   - `say` statements with optional dot-variant and optional parentheses
 *   - `return` with optional value
 *   - `throw` with expression
 *
 * Note: `break` and `continue` are trivial single-token statements handled
 * directly in [Parser.parseItem].
 *
 * @param p Reference to the parent [Parser] for token stream access.
 */
class StatementParser(internal val p: Parser) {

    // ====================================================================
    // For Statement
    // ====================================================================

    /**
     * Parse a for loop. KS supports two forms:
     *
     * **Traditional** (explicit variable):
     *     for i in [1, 2, 3] { say i }
     *     for item in elems say item
     *     for char in ["a", "b"] { ... }
     *
     * **Simplified** (implicit `it`):
     *     for [1, 2, 3] say "Number: $it"
     *     for numbers { say it }
     *     for 1..10 { say "Count: $it" }
     *     for Color { say it }                      // enum iteration
     *     for "Hello" say "Character: $it"
     *     for getNumbers() { say "Got: $it" }
     *
     * Disambiguation: after `for`, if the token pattern is `IDENTIFIER in ...`
     * (identifier followed by the `in` keyword), it's traditional form.
     * Otherwise, parse an expression as the iterable (simplified form).
     *
     * Both forms accept a block `{ ... }` or a single statement as the body.
     */
    fun parseForStmt(): ForStmt {
        val loc = p.advance().location // consume FOR

        // Check for traditional form: `for IDENTIFIER in ...`
        if (p.check(IDENTIFIER) && p.checkNext(IN)) {
            // Traditional form
            val variable = p.advance().value // consume variable name
            p.advance() // consume 'in'
            val iterable = p.expr.parseExpression()
            val body = p.parseSingleOrBlock()
            return ForStmt(variable, iterable, body, loc)
        }

        // Simplified form: parse expression as iterable
        val iterable = p.expr.parseExpression()
        val body = p.parseSingleOrBlock()
        return ForStmt(null, iterable, body, loc)
    }

    // ====================================================================
    // While Statement
    // ====================================================================

    /**
     * Parse a while loop.
     *
     *     while x > 0 { x-- }
     *     while running doWork()
     *
     * The condition is a full expression (no parentheses required, like `if`).
     * The body can be a block or a single statement.
     */
    fun parseWhileStmt(): WhileStmt {
        val loc = p.advance().location // consume WHILE
        val condition = p.expr.parseExpression()
        val body = p.parseSingleOrBlock()
        return WhileStmt(condition, body, loc)
    }

    // ====================================================================
    // Say Statement
    // ====================================================================

    /**
     * Parse a say statement.
     *
     *     say "hello"                          // standard
     *     say.error "oops"                     // error variant (red)
     *     say.warn "caution"                   // warning variant (orange)
     *     say.note "note"                      // note variant (bold)
     *     say.note("note", bold=true)          // parenthesized args
     *     say "Sum: " + a + b                  // expression argument
     *
     * The lexer tokenizes `say` as [SAY], `.` as [DOT], and `error`/`warn`/`info`
     * as [IDENTIFIER]. Parentheses are optional — without them, a single
     * expression is parsed as the sole argument.
     *
     * Recognized variants: "error", "warn", "note". Any other dot-accessed
     * identifier after `say` is an error.
     */
    fun parseSayStmt(): SayStmt {
        val loc = p.advance().location // consume SAY

        // Check for variant: say.error, say.warn, say.info
        val variant: String? = if (p.check(DOT)) {
            p.advance() // consume .
            val variantToken = p.expect(IDENTIFIER, "Expected say variant (error, warn, note)")
            val v = variantToken.value
            if (v != "error" && v != "warn" && v != "note") {
                p.errorAt(variantToken, "Unknown say variant '$v'. Use 'error', 'warn', or 'note'")
            }
            v
        } else {
            null
        }

        // Parse arguments
        val arguments = parseSayArguments()
        return SayStmt(variant, arguments, loc)
    }

    /**
     * Parse say arguments — parenthesized or bare.
     *
     *     say("hello", bold=true)   // parenthesized: full argument list
     *     say "hello"               // bare: single expression
     *     say "Sum: " + a + b       // bare: single expression (operator chain)
     *
     * For bare arguments, the expression continues until a statement terminator
     * (newline, semicolon, EOF, or closing brace).
     */
    private fun parseSayArguments(): List<Argument> {
        // Parenthesized form
        if (p.check(LPAREN)) {
            p.advance() // consume (
            val args = p.expr.parseArgumentList()
            p.expect(RPAREN, "Expected ')' after say arguments")
            return args
        }

        // Bare form: parse space-separated expressions as arguments
        // say "a" "b" "c"  → three arguments, printed as "a b c"
        // say "Sum: " + a  → one expression argument (operator chains parse as single expr)
        if (!p.canStartExpression()) {
            // say with no arguments (just prints a blank line)
            return emptyList()
        }

        val args = mutableListOf<Argument>()
        while (p.canStartExpression() && !p.check(NEWLINE) && !p.check(SEMICOLON) && !p.atBlockEnd()) {
            val argLoc = p.currentLocation()
            val value = p.expr.parseExpression()
            args.add(Argument(null, value, argLoc))
        }
        return args
    }

    // ====================================================================
    // Return Statement
    // ====================================================================

    /**
     * Parse a return statement.
     *
     *     return              // bare return (void)
     *     return 42           // return with value
     *     return x + y
     *
     * A return value is parsed if an expression-starting token follows on
     * the same logical line (checked via [Parser.canStartExpression]).
     * A newline or semicolon after `return` means bare return.
     */
    fun parseReturnStmt(): ReturnStmt {
        val loc = p.advance().location // consume RETURN

        // Check for value: if a separator or block-end follows, it's bare return
        val value = if (!p.atBlockEnd() && !p.check(NEWLINE) && !p.check(SEMICOLON) && p.canStartExpression()) {
            p.expr.parseExpression()
        } else {
            null
        }

        return ReturnStmt(value, loc)
    }

    // ====================================================================
    // Throw Statement
    // ====================================================================

    /**
     * Parse a throw statement.
     *
     *     throw KSException("Yikes!")
     *     throw error
     *
     * The expression is always required (you can't bare `throw`).
     */
    fun parseThrowStmt(): ThrowStmt {
        val loc = p.advance().location // consume THROW
        val expression = p.expr.parseExpression()
        return ThrowStmt(expression, loc)
    }
}