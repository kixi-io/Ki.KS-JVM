package io.kixi.ks.parser

import io.kixi.ks.SourceLocation
import io.kixi.ks.lexer.Token
import io.kixi.ks.lexer.TokenType
import io.kixi.ks.lexer.TokenType.*

/**
 * KS Language Parser
 *
 * Recursive descent parser that transforms a token stream from the [Lexer][io.kixi.ks.lexer.Lexer]
 * into an Abstract Syntax Tree defined in AST.kt.
 *
 * Architecture — the parser is split across six files for maintainability:
 *
 *   - **Parser.kt** (this file)
 *     Core infrastructure: token stream utilities, entry point, top-level dispatch,
 *     block/body parsing, separator handling, error helpers.
 *
 *   - **ExpressionParser.kt**
 *     All expression parsing with precedence climbing. Covers 14 precedence levels
 *     from assignment through primary expressions, including `if`, `when`, `try`,
 *     and `lang` blocks (which are expressions in KS).
 *
 *   - **DeclarationParser.kt**
 *     `var`/`let`, `fun`, `class`, `trait`, `enum`, `use`, `extend`, `static`.
 *
 *   - **StatementParser.kt**
 *     `for`, `while`, `say`, `return`, `throw`.
 *
 *   - **TypeParser.kt**
 *     Type references (`Int`, `String?`, `List<Int>`, `[Int]`, `[String:Int]`),
 *     constraints (`> 25`, `1..100`, `in [...]`, `matches "..."`),
 *     function parameters, and constructor parameters.
 *
 *   - **KDBlockParser.kt**
 *     Parsing of `lang KD { ... }` DSL blocks into [KDTagNode] trees.
 *
 * Each sub-parser holds a reference back to this [Parser] for shared token utilities.
 * Visibility is `internal` so sub-parsers can access the token stream directly.
 *
 * Key design points:
 *   - `if`, `when`, `try` are **expressions** (return values, like Kotlin)
 *   - `for`, `while` are **statements**
 *   - Newlines are significant statement separators (Kotlin-style); the lexer
 *     already filters non-significant newlines
 *   - Commas are optional in lists, maps, and argument lists
 *   - `this` is recognized by checking identifier name — no dedicated keyword token
 *   - DPEC (`.SUCCESS`) is parsed as a dot followed by an identifier in expression context
 *
 * Compatibility: Kotlin 1.3+ (no deprecated APIs). Trivially portable to 2.3.0.
 *
 * @param tokens The token list produced by [io.kixi.ks.lexer.Lexer.tokenize]
 */
class Parser(private val tokens: List<Token>) {

    // ====================================================================
    // State
    // ====================================================================

    /** Current position in the token stream (index into [tokens]). */
    internal var current = 0

    // ====================================================================
    // Sub-parsers
    // ====================================================================

    internal val expr   = ExpressionParser(this)
    internal val decl   = DeclarationParser(this)
    internal val stmt   = StatementParser(this)
    internal val types  = TypeParser(this)
    internal val kd     = KDBlockParser(this)

    // ====================================================================
    // Public API
    // ====================================================================

    /**
     * Parse the entire token stream into a [Program] AST node.
     *
     * The returned program's [body][Program.body] contains declarations,
     * statements, and expression-statements in source order. The token
     * list must end with an [EOF][TokenType.EOF] token (the lexer guarantees this).
     */
    fun parse(): Program {
        val loc = currentLocation()
        val body = mutableListOf<Node>()
        skipSeparators()

        while (!isAtEnd()) {
            body.add(parseItem())
            skipSeparators()
        }

        return Program(body, loc)
    }

    // ====================================================================
    // Item Parsing — top-level and inside blocks
    // ====================================================================

    /**
     * Parse a single item: declaration, statement, or expression-statement.
     *
     * This is the io.kixi.ks.main dispatch method, used both at the top level and inside
     * block bodies. The same constructs are syntactically valid everywhere;
     * semantic analysis (a later phase) enforces scoping rules (e.g. `use`
     * only at top level, `return` only inside functions).
     */
    internal fun parseItem(): Node {
        return when (peek().type) {
            // --- Declarations ---
            VAR, LET  -> decl.parseVarDecl()
            FUN       -> decl.parseFunDecl()
            CLASS     -> decl.parseClassDecl()
            STRUCT    -> decl.parseStructDecl()
            TRAIT     -> decl.parseTraitDecl()
            ENUM      -> decl.parseEnumDecl()
            USE       -> decl.parseUseDecl()
            EXTEND    -> decl.parseExtendDecl()
            STATIC    -> decl.parseStaticBlock()
            INFIX     -> decl.parseInfixFunDecl()

            // --- Statements ---
            FOR       -> stmt.parseForStmt()
            WHILE     -> stmt.parseWhileStmt()
            SAY       -> stmt.parseSayStmt()
            RETURN    -> stmt.parseReturnStmt()
            THROW     -> stmt.parseThrowStmt()

            BREAK -> {
                val loc = advance().location
                BreakStmt(loc)
            }

            CONTINUE -> {
                val loc = advance().location
                ContinueStmt(loc)
            }

            // --- Everything else is an expression ---
            // This includes if, when, try, lang (all expressions in KS),
            // as well as calls, assignments, literals, identifiers, etc.
            else -> {
                val expression = expr.parseExpression()
                ExprStmt(expression, expression.location)
            }
        }
    }

    // ====================================================================
    // Block Parsing
    // ====================================================================

    /**
     * Parse a brace-delimited block: `{ item1; item2; … }`
     *
     * Returns a [BlockExpr] whose value is its last expression (if any).
     * Used for function bodies, class/trait bodies, if/else branches, etc.
     */
    internal fun parseBlock(): BlockExpr {
        val loc = expect(LBRACE, "Expected '{'").location
        val stmts = parseBlockBody()
        expect(RBRACE, "Expected '}'")
        return BlockExpr(stmts, loc)
    }

    /**
     * Parse the contents between `{` and `}` (braces consumed by the caller).
     *
     * Items are separated by newlines or semicolons. Trailing separators
     * before the closing `}` are consumed silently.
     */
    internal fun parseBlockBody(): List<Node> {
        val stmts = mutableListOf<Node>()
        skipSeparators()

        while (!check(RBRACE) && !isAtEnd()) {
            stmts.add(parseItem())
            skipSeparators()
        }

        return stmts
    }

    /**
     * Parse either a block `{ … }` or a single statement/expression.
     *
     * Used for constructs that accept both forms:
     *
     *     if condition { block }       // block body
     *     if condition statement        // single-statement body
     *     for items { block }
     *     for items say it
     *
     * Leading newlines are skipped so that the following works:
     *
     *     if condition
     *     {
     *         ...
     *     }
     */
    internal fun parseSingleOrBlock(): Node {
        skipNewlines()
        return if (check(LBRACE)) {
            parseBlock()
        } else {
            parseItem()
        }
    }

    // ====================================================================
    // Token Utilities — Inspection
    // ====================================================================

    /** Current token without advancing. */
    internal fun peek(): Token = tokens[current]

    /** Token after current without advancing. */
    internal fun peekNext(): Token {
        val idx = current + 1
        return if (idx < tokens.size) tokens[idx] else tokens.last()
    }

    /** Token at a specific offset from current, without advancing. */
    internal fun peekAt(offset: Int): Token {
        val idx = current + offset
        return if (idx in tokens.indices) tokens[idx] else tokens.last()
    }

    /** Most recently consumed token. */
    internal fun previous(): Token = tokens[current - 1]

    /** True when the current token is [EOF]. */
    internal fun isAtEnd(): Boolean = peek().type == EOF

    /** True if the current token has the given [type] (does not advance). */
    internal fun check(type: TokenType): Boolean =
        !isAtEnd() && peek().type == type

    /** True if the next (non-current) token has the given [type]. */
    internal fun checkNext(type: TokenType): Boolean =
        peekNext().type == type

    // ====================================================================
    // Token Utilities — Consuming
    // ====================================================================

    /** Consume and return the current token. */
    internal fun advance(): Token {
        val token = tokens[current]
        if (!isAtEnd()) current++
        return token
    }

    /**
     * If the current token matches any of the given [types], consume it
     * and return `true`. Otherwise return `false` without advancing.
     */
    internal fun match(vararg types: TokenType): Boolean {
        for (type in types) {
            if (check(type)) {
                advance()
                return true
            }
        }
        return false
    }

    /**
     * Assert that the current token has the expected [type]. Consume and
     * return it if so; throw [ParseException] with [message] otherwise.
     */
    internal fun expect(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        throw errorAt(peek(), "$message (got ${peek().type}: '${peek().value}')")
    }

    /** Convenience: expect an [IDENTIFIER] token and return its text value. */
    internal fun expectIdentifier(message: String = "Expected identifier"): String {
        return expect(IDENTIFIER, message).value
    }

    /**
     * Expect a member name after `.` or `?.`.
     *
     * In member-access position, keywords are valid names (e.g. `regex.matches()`
     * `list.in`, `obj.class`). This accepts either an [IDENTIFIER] or any keyword
     * token and returns its text value.
     */
    internal fun expectMemberName(message: String = "Expected member name"): String {
        val token = peek()
        if (token.type == IDENTIFIER || token.type in MEMBER_NAME_KEYWORDS) {
            advance()
            return token.value
        }
        throw errorAt(token, "$message (got ${token.type}: '${token.value}')")
    }

    companion object {
        /**
         * Keyword token types that are valid as member/method names after `.` or `?.`.
         *
         * After a dot, these keywords lose their keyword meaning and behave as
         * plain identifiers. This follows the same convention as Kotlin and Swift.
         */
        val MEMBER_NAME_KEYWORDS: Set<TokenType> = setOf(
            // Type/check keywords commonly used as method names
            MATCHES, IN, IS, AS,

            // Declaration keywords (e.g. obj.class, obj.enum, obj.trait)
            VAR, LET, FUN, CLASS, TRAIT, ENUM, STRUCT, STATIC, EXTEND, INFIX,

            // Control flow keywords (e.g. obj.if, obj.for — rare but legal)
            IF, ELSE, FOR, WHILE, WHEN, RETURN, BREAK, CONTINUE,

            // Error handling
            TRY, CATCH, FINALLY, THROW,

            // Other keywords
            USE, SAY, LANG,

            // Literal keywords (e.g. obj.true — rare but legal)
            TRUE, FALSE, NIL,
        )
    }

    // ====================================================================
    // Newline / Separator Handling
    // ====================================================================

    /** Consume all [NEWLINE] tokens at the current position. */
    internal fun skipNewlines() {
        while (check(NEWLINE)) advance()
    }

    /** Consume all [NEWLINE] and [SEMICOLON] tokens at the current position. */
    internal fun skipSeparators() {
        while (check(NEWLINE) || check(SEMICOLON)) advance()
    }

    /**
     * True if the current token is a natural block/file boundary where
     * no explicit separator is required (e.g. before `}`, `)`, `]`, or at EOF).
     */
    internal fun atBlockEnd(): Boolean =
        isAtEnd() || check(RBRACE) || check(RPAREN) || check(RBRACKET)

    // ====================================================================
    // Lookahead Past Newlines
    // ====================================================================

    /**
     * Peek at the first non-[NEWLINE] token at or after the current position,
     * without consuming anything.
     *
     * Useful for decisions like: "after this expression, is there a `{` coming
     * (possibly on the next line)?"
     */
    internal fun peekAfterNewlines(): Token {
        var i = current
        while (i < tokens.size && tokens[i].type == NEWLINE) i++
        return if (i < tokens.size) tokens[i] else tokens.last()
    }

    /**
     * Check whether a specific token [type] follows, ignoring intervening
     * newlines. Does NOT consume anything.
     */
    internal fun checkAfterNewlines(type: TokenType): Boolean =
        peekAfterNewlines().type == type

    // ====================================================================
    // Expression Start Detection
    // ====================================================================

    /**
     * Returns `true` if the current token could begin an expression.
     *
     * Used for optional-expression parsing — e.g. `return` can be bare or
     * followed by a value, so the parser checks [canStartExpression] to decide.
     * Also used by the `for` statement to detect simplified form (no `in` keyword).
     *
     * This is intentionally conservative: false positives are resolved by the
     * expression parser; false negatives would silently misparse.
     */
    internal fun canStartExpression(): Boolean = canStartExpression(peek().type)

    /**
     * Whether a given [TokenType] could begin an expression.
     *
     * Overload used by the infix-call parser for lookahead: it checks the
     * token *after* the candidate infix name without consuming anything.
     */
    internal fun canStartExpression(type: TokenType): Boolean = when (type) {
        // Literals
        INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL, DEC_LITERAL,
        STRING_LITERAL, VERBATIM_STRING, MULTILINE_STRING, VERBATIM_MULTILINE,
        BACKTICK_STRING, CHAR_LITERAL, TRUE, FALSE, NIL, URL_LITERAL,
        QUANTITY_LITERAL, CURRENCY_QUANTITY_LITERAL -> true

        // Identifiers
        IDENTIFIER -> true

        // Interpolation reference: $var (used in KD blocks)
        DOLLAR -> true

        // Prefix operators
        MINUS, BANG, PLUS_PLUS, MINUS_MINUS -> true

        // Grouping and collections
        LPAREN, LBRACKET, LBRACE -> true

        // Expression-level keywords (if, when, try are expressions in KS)
        IF, WHEN, TRY, LANG -> true

        // DPEC: .NAME (dot-prefixed enum constant)
        DOT -> true

        // Underscore: open-end ranges (_..x)
        UNDERSCORE -> true

        else -> false
    }

    // ====================================================================
    // Location Helpers
    // ====================================================================

    /** [SourceLocation] of the current token. */
    internal fun currentLocation(): SourceLocation = peek().location

    /** [SourceLocation] of the most recently consumed token. */
    internal fun previousLocation(): SourceLocation = previous().location

    // ====================================================================
    // Error Handling
    // ====================================================================

    /** Throw a [ParseException] at the current token's position. */
    internal fun error(message: String): Nothing {
        throw ParseException(message, peek().location)
    }

    /** Throw a [ParseException] at a specific [token]'s position. */
    internal fun errorAt(token: Token, message: String): Nothing {
        throw ParseException(message, token.location)
    }
}