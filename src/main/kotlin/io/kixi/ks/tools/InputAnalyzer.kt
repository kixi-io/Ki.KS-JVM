package io.kixi.ks.tools

import io.kixi.ks.lexer.Lexer
import io.kixi.ks.lexer.LexerException
import io.kixi.ks.lexer.Token
import io.kixi.ks.lexer.TokenType

/**
 * Analyzes REPL input to determine whether it forms a complete statement
 * or expression, or whether the user needs to provide more lines.
 *
 * This is the core of the "smart Enter" behavior: the REPL should know
 * when to execute and when to show a continuation prompt, without requiring
 * any special key combinations.
 *
 * ## Detection Strategy
 *
 * The analyzer uses a layered approach:
 *
 * 1. **Lexer probe** \u2014 Attempt to tokenize the input. If the Lexer throws
 *    an "Unterminated ..." error, the input is definitely incomplete (unclosed
 *    string, block comment, etc.).
 *
 * 2. **Bracket balance** \u2014 If tokenization succeeds, count open vs. closed
 *    `()`, `[]`, `{}`. Any positive nesting depth means incomplete input.
 *
 * 3. **Trailing continuation** \u2014 Check whether the last meaningful token
 *    is a continuation token (operator, comma, open bracket, arrow, colon).
 *    This mirrors the Lexer's [shouldEmitNewline] logic: if the Lexer would
 *    suppress a newline after this token, the line is not finished.
 *
 * 4. **Block-expecting keywords** \u2014 If the last meaningful token is a
 *    keyword that expects a body (`if`, `else`, `for`, `while`, `fun`,
 *    `class`, `trait`, `enum`, `extend`, `static`) and no block follows,
 *    the input is likely incomplete.
 *
 * ## Usage
 *
 * ```kotlin
 * val analyzer = InputAnalyzer()
 * val result = analyzer.analyze("if x > 5 {")
 * // result == InputState.INCOMPLETE
 * ```
 */
class InputAnalyzer {

    /**
     * The result of analyzing a chunk of REPL input.
     */
    enum class InputState {
        /** Input is a complete statement/expression \u2014 ready to execute. */
        COMPLETE,

        /** Input needs more lines (unclosed bracket, trailing operator, etc.). */
        INCOMPLETE,

        /** Input is empty or whitespace-only \u2014 nothing to do. */
        EMPTY
    }

    /**
     * Analyze the given [input] and determine its completeness.
     *
     * @param input The accumulated REPL input (may span multiple lines).
     * @return The [InputState] indicating whether the input is ready to execute.
     */
    fun analyze(input: String): InputState {
        // Trim and check for empty
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return InputState.EMPTY

        // --- Layer 1: Lexer probe ---
        val tokens: List<Token>
        try {
            tokens = Lexer(input).tokenize()
        } catch (e: LexerException) {
            // Use the message to distinguish unterminated constructs from
            // genuine syntax errors. Unterminated strings, block comments,
            // etc. mean the user hasn't finished typing yet.
            val msg = e.message ?: ""
            if (isUnterminatedError(msg)) {
                return InputState.INCOMPLETE
            }
            // Other lexer errors \u2192 the input is malformed but complete;
            // let the interpreter report the real error with its suggestion.
            return InputState.COMPLETE
        }

        // --- Layer 2: Bracket balance ---
        if (hasUnclosedBrackets(tokens)) {
            return InputState.INCOMPLETE
        }

        // --- Layer 3: Trailing continuation token ---
        if (endsWithContinuation(tokens)) {
            return InputState.INCOMPLETE
        }

        // --- Layer 4: Block-expecting keyword at end ---
        if (endsWithBlockKeyword(tokens)) {
            return InputState.INCOMPLETE
        }

        return InputState.COMPLETE
    }

    // ====================================================================
    // Layer 1: Unterminated Error Detection
    // ====================================================================

    /**
     * Returns `true` if the error message indicates an unterminated construct
     * that the user can complete by typing more input.
     */
    private fun isUnterminatedError(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("unterminated") ||
                lower.contains("unclosed") ||
                lower.contains("expected '\"'") ||
                lower.contains("expected '}'") ||
                lower.contains("expected ')'") ||
                lower.contains("expected ']'")
    }

    // ====================================================================
    // Layer 2: Bracket Balance
    // ====================================================================

    /**
     * Returns `true` if there are more openers than closers for any bracket
     * type.  Does not flag negative depth (extra closers) \u2014 that's an error
     * for the parser to report.
     */
    private fun hasUnclosedBrackets(tokens: List<Token>): Boolean {
        var parenDepth   = 0
        var bracketDepth = 0
        var braceDepth   = 0

        for (token in tokens) {
            when (token.type) {
                TokenType.LPAREN   -> parenDepth++
                TokenType.RPAREN   -> parenDepth--
                TokenType.LBRACKET -> bracketDepth++
                TokenType.RBRACKET -> bracketDepth--
                TokenType.LBRACE   -> braceDepth++
                TokenType.RBRACE   -> braceDepth--
                else -> { /* skip */ }
            }
        }

        return parenDepth > 0 || bracketDepth > 0 || braceDepth > 0
    }

    // ====================================================================
    // Layer 3: Trailing Continuation Token
    // ====================================================================

    /**
     * Returns `true` if the last meaningful token (ignoring NEWLINE and EOF)
     * is a continuation token \u2014 one that the Lexer's `shouldEmitNewline()`
     * would suppress a newline after.
     *
     * This mirrors the exact same set of tokens from the Lexer to keep
     * behavior consistent.
     */
    private fun endsWithContinuation(tokens: List<Token>): Boolean {
        val last = lastMeaningfulToken(tokens) ?: return false
        return isContinuationToken(last.type)
    }

    /**
     * Continuation tokens \u2014 copied from the Lexer's `shouldEmitNewline()`.
     *
     * When the last token on a line is one of these, the expression/statement
     * is not finished yet.
     */
    private fun isContinuationToken(type: TokenType): Boolean = when (type) {
        // Arithmetic & assignment operators
        TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.STAR_STAR,
        TokenType.SLASH, TokenType.PERCENT, TokenType.COMBINE,
        TokenType.EQUAL, TokenType.PLUS_EQUAL, TokenType.MINUS_EQUAL,
        TokenType.STAR_EQUAL, TokenType.STAR_STAR_EQUAL,
        TokenType.SLASH_EQUAL, TokenType.PERCENT_EQUAL,

            // Logical operators
        TokenType.AMP_AMP, TokenType.PIPE_PIPE,

            // Comparison operators
        TokenType.LESS, TokenType.GREATER, TokenType.LESS_EQUAL, TokenType.GREATER_EQUAL,
        TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL,

            // Dot & null-safety operators
        TokenType.DOT, TokenType.QUESTION_DOT, TokenType.ELVIS,

            // Range operators
        TokenType.DOT_DOT, TokenType.DOT_DOT_LESS, TokenType.LESS_DOT_DOT,
        TokenType.LESS_DOT_DOT_LESS,

            // Structural continuation
        TokenType.COMMA,
        TokenType.LPAREN, TokenType.LBRACKET, TokenType.LBRACE,
        TokenType.ARROW, TokenType.COLON -> true

        else -> false
    }

    // ====================================================================
    // Layer 4: Block-Expecting Keywords
    // ====================================================================

    /**
     * Returns `true` if the last meaningful token is a keyword that typically
     * expects a body block (or at least more tokens) to follow.
     *
     * For example, `fun greet()` alone is incomplete \u2014 it needs `= expr`
     * or `{ body }`.  Similarly, `if x > 5` needs a then-branch.
     *
     * This heuristic errs on the side of waiting for more input. If the user
     * really did mean to type just `class Foo` with no body, a blank line
     * (empty input) will force execution.
     */
    private fun endsWithBlockKeyword(tokens: List<Token>): Boolean {
        val last = lastMeaningfulToken(tokens) ?: return false
        return when (last.type) {
            // Control flow that needs a body
            TokenType.ELSE -> true

            // These are only incomplete if they appear as the very last token
            // (i.e. just the keyword was typed, nothing after it)
            TokenType.IF, TokenType.FOR, TokenType.WHILE -> {
                // Check if it's the *only* meaningful token
                // `if` alone is incomplete, but `if true say "yes"` is complete
                val meaningful = tokens.filter {
                    it.type != TokenType.NEWLINE && it.type != TokenType.EOF
                }
                meaningful.size == 1
            }

            else -> false
        }
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * Find the last token that isn't NEWLINE or EOF.
     */
    private fun lastMeaningfulToken(tokens: List<Token>): Token? {
        return tokens.lastOrNull {
            it.type != TokenType.NEWLINE && it.type != TokenType.EOF
        }
    }
}