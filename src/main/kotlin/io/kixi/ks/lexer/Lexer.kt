package io.kixi.ks.lexer

import io.kixi.ks.SourceLocation
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * KS Language Lexer
 *
 * Tokenizes KS source code into a stream of [Token]s. Handles:
 * - All KD-inherited literal types (numbers, strings, chars, booleans, nil, URLs,
 *   dates, durations, versions, blobs, ranges, quantities)
 * - Quantity literals with unit suffixes: 23cm, 51.4m³, 1000kg, 25°C, 97ℓ
 * - Currency quantity literals with prefix notation: $23.53, €50.25, ¥10000
 * - Scientific notation in quantities: 5.5e(-7)m, 5.5en7m, 5.5e8km
 * - Unit composition operator: ⚭ (e.g., 4cm ⚭ 3cm → 12cm²)
 * - Five string variants: standard, verbatim, multiline, verbatim-multiline, backtick
 * - String interpolation markers ($var and ${expr})
 * - All operators including range (.., ..<, <.., <..<), null-safety (?., ?:, !!),
 *   and compound assignment (+=, -=, etc.)
 * - Both # and // line comments, and /* */ block comments
 * - Newline-significant tokenization (Kotlin-style)
 * - Keywords including `say`, `lang`, `use`, `extend`, `matches`
 * - !in and !is as single tokens
 *
 * Design notes:
 * - All types are reference types (like Kotlin)
 * - Reified generics — no type erasure
 * - Commas are optional in lists, maps, and arguments
 * - Constraints are separate from types (runtime guards, compile-time where provable)
 *
 * Compatibility: Written for Kotlin 1.3+ (the version on this system). Clean enough
 * to port to Kotlin 2.3.0 trivially — no deprecated APIs used.
 *
 * @param source The KS source code to tokenize
 */
class Lexer(private val source: String) {

    private val tokens = mutableListOf<Token>()
    private var start = 0       // start of current token
    private var current = 0     // current character position
    private var line = 1        // current line number (1-based)
    private var column = 1      // current column number (1-based)
    private var startLine = 1   // line at start of current token
    private var startColumn = 1 // column at start of current token

    /**
     * Tokenize the entire source and return the list of tokens.
     * The list always ends with an EOF token.
     */
    fun tokenize(): List<Token> {
        while (!isAtEnd()) {
            start = current
            startLine = line
            startColumn = column
            scanToken()
        }

        // Add EOF
        tokens.add(Token(TokenType.EOF, "", null, SourceLocation(line = line, column = column, offset = current)))
        return tokens
    }

    // ========================================================================
    // Core scanning
    // ========================================================================

    private fun scanToken() {
        when (val c = advance()) {
            // --- Whitespace (non-newline) ---
            ' ', '\t', '\r' -> { /* skip */ }

            // --- Newlines (significant) ---
            '\n' -> {
                // Only emit NEWLINE if the previous token could end a statement.
                // This prevents spurious newlines after operators, open brackets, etc.
                if (shouldEmitNewline()) {
                    addToken(TokenType.NEWLINE)
                }
                line++
                column = 1
            }

            // --- Single-char delimiters ---
            '(' -> addToken(TokenType.LPAREN)
            ')' -> addToken(TokenType.RPAREN)
            '[' -> addToken(TokenType.LBRACKET)
            ']' -> addToken(TokenType.RBRACKET)
            '{' -> addToken(TokenType.LBRACE)
            '}' -> addToken(TokenType.RBRACE)
            ',' -> addToken(TokenType.COMMA)
            ';' -> addToken(TokenType.SEMICOLON)
            '@' -> {
                // Could be @"..." verbatim string or @"""...""" verbatim multiline
                // or @Annotation
                if (peek() == '"') {
                    if (peekNext() == '"' && peekAt(current + 2) == '"') {
                        scanVerbatimMultilineString()
                    } else {
                        scanVerbatimString()
                    }
                } else {
                    addToken(TokenType.AT)
                }
            }
            '_' -> {
                // Underscore as a standalone token (wildcard in ranges, catch)
                // vs part of an identifier
                if (isAlphaNumeric(peek())) {
                    scanIdentifier()
                } else {
                    addToken(TokenType.UNDERSCORE)
                }
            }

            // --- Operators ---
            '+' -> {
                when {
                    match('+') -> addToken(TokenType.PLUS_PLUS)
                    match('=') -> addToken(TokenType.PLUS_EQUAL)
                    else -> addToken(TokenType.PLUS)
                }
            }
            '-' -> {
                when {
                    match('-') -> addToken(TokenType.MINUS_MINUS)
                    match('=') -> addToken(TokenType.MINUS_EQUAL)
                    match('>') -> addToken(TokenType.ARROW)
                    else -> addToken(TokenType.MINUS)
                }
            }
            '*' -> {
                when {
                    match('*') -> {
                        // ** or **=
                        if (match('=')) addToken(TokenType.STAR_STAR_EQUAL)
                        else addToken(TokenType.STAR_STAR)
                    }
                    match('=') -> addToken(TokenType.STAR_EQUAL)
                    else -> addToken(TokenType.STAR)
                }
            }
            '/' -> {
                when {
                    match('/') -> skipLineComment()
                    match('*') -> skipBlockComment()
                    match('=') -> addToken(TokenType.SLASH_EQUAL)
                    else -> addToken(TokenType.SLASH)
                }
            }
            '%' -> {
                when {
                    match('=') -> addToken(TokenType.PERCENT_EQUAL)
                    else -> addToken(TokenType.PERCENT)
                }
            }

            '#' -> skipLineComment()

            '!' -> {
                when {
                    match('=') -> addToken(TokenType.BANG_EQUAL)
                    match('!') -> addToken(TokenType.BANG_BANG)
                    else -> {
                        // Check for !in and !is
                        if (peek() == 'i') {
                            val saved = current
                            advance() // consume 'i'
                            when {
                                peek() == 'n' && !isAlphaNumeric(peekAt(current + 1)) -> {
                                    advance() // consume 'n'
                                    addToken(TokenType.NOT_IN)
                                }
                                peek() == 's' && !isAlphaNumeric(peekAt(current + 1)) -> {
                                    advance() // consume 's'
                                    addToken(TokenType.NOT_IS)
                                }
                                else -> {
                                    current = saved
                                    addToken(TokenType.BANG)
                                }
                            }
                        } else {
                            addToken(TokenType.BANG)
                        }
                    }
                }
            }

            '=' -> {
                when {
                    match('=') -> addToken(TokenType.EQUAL_EQUAL)
                    else -> addToken(TokenType.EQUAL)
                }
            }

            '<' -> {
                when {
                    match('=') -> addToken(TokenType.LESS_EQUAL)
                    match('.') -> {
                        // <.. or <..<
                        if (match('.')) {
                            if (match('<')) {
                                addToken(TokenType.LESS_DOT_DOT_LESS)
                            } else {
                                addToken(TokenType.LESS_DOT_DOT)
                            }
                        } else {
                            // Just '<' followed by '.', back up the '.'
                            current--
                            column--
                            addToken(TokenType.LESS)
                        }
                    }
                    else -> {
                        // Check for URL literal: <https://...>
                        if (isUrlStart()) {
                            scanUrlLiteral()
                        } else {
                            addToken(TokenType.LESS)
                        }
                    }
                }
            }

            '>' -> {
                when {
                    match('=') -> addToken(TokenType.GREATER_EQUAL)
                    else -> addToken(TokenType.GREATER)
                }
            }

            '&' -> {
                if (match('&')) addToken(TokenType.AMP_AMP)
                else error("Unexpected character '&'. Did you mean '&&'?")
            }

            '|' -> {
                if (match('|')) addToken(TokenType.PIPE_PIPE)
                else error("Unexpected character '|'. Did you mean '||'?")
            }

            '?' -> {
                when {
                    match('.') -> addToken(TokenType.QUESTION_DOT)
                    match(':') -> addToken(TokenType.ELVIS)
                    else -> addToken(TokenType.QUESTION)
                }
            }

            ':' -> {
                when {
                    match(':') -> addToken(TokenType.COLON_COLON)
                    else -> addToken(TokenType.COLON)
                }
            }

            '.' -> {
                when {
                    match('.') -> {
                        // .. or ..<
                        if (match('<')) {
                            addToken(TokenType.DOT_DOT_LESS)
                        } else {
                            addToken(TokenType.DOT_DOT)
                        }
                    }
                    match('b') -> {
                        // .blob(...) literal
                        if (peekWord() == "lob") {
                            advance() // l
                            advance() // o
                            advance() // b
                            if (match('(')) {
                                scanBlobLiteral()
                            } else {
                                // Not a blob, back up
                                current -= 3
                                column -= 3
                                addToken(TokenType.DOT)
                            }
                        } else {
                            // . followed by something starting with 'b' but not 'blob'
                            current--
                            column--
                            addToken(TokenType.DOT)
                        }
                    }
                    else -> addToken(TokenType.DOT)
                }
            }

            // --- String Literals ---
            '"' -> {
                // Check for multiline """ or single "
                if (peek() == '"' && peekNext() == '"') {
                    advance() // consume second "
                    advance() // consume third "
                    scanMultilineString()
                } else {
                    scanString()
                }
            }

            '\'' -> scanChar()

            '`' -> scanBacktickString()

            // --- Number Literals ---
            in '0'..'9' -> scanNumber()

            // --- Currency Quantity Literals ---
            // Prefix notation: $23.53, €50.25, ¥10000, £75.50, ₿0.5, Ξ2.5
            '$', '€', '¥', '£', '₿', 'Ξ' -> {
                if (isDigit(peek())) {
                    scanCurrencyQuantity()
                } else if (c == '$') {
                    // $ not followed by a digit = interpolation reference (used in KD blocks)
                    addToken(TokenType.DOLLAR)
                } else {
                    error("Currency symbol '${c}' must be followed by a digit (e.g., ${c}100)")
                }
            }

            // --- Unit Composition ---
            '⚭' -> addToken(TokenType.COMBINE)

            // --- Identifiers and Keywords ---
            else -> {
                if (isAlpha(c)) {
                    scanIdentifier()
                } else {
                    error("Unexpected character '${c}'")
                }
            }
        }
    }

    // ========================================================================
    // Number scanning
    // ========================================================================

    /**
     * Scans a number literal. Handles:
     * - Int: 42, 424_235_412
     * - Hex: 0xFF
     * - Binary: 0b1010
     * - Long: 42L
     * - Float: 3.14f, 3.14F
     * - Double: 3.14, 3.14d, 3.14D
     * - Dec: 3.14BD, 3.14bd
     *
     * Also detects date/datetime patterns (2026/2/4) and duration patterns.
     */
    private fun scanNumber() {
        // Check for hex or binary
        if (previous() == '0') {
            when {
                match('x') || match('X') -> return scanHexNumber()
                match('b') || match('B') -> {
                    // Check it's not 'BD' (dec suffix on 0)
                    if (peek() == 'D' || peek() == 'd') {
                        // 0BD -> Dec literal
                        advance()
                        addToken(TokenType.DEC_LITERAL, java.math.BigDecimal.ZERO)
                        return
                    }
                    return scanBinaryNumber()
                }
            }
        }

        // Consume integer digits (with optional underscores for legibility)
        consumeDigits()

        // Check what follows the integer part
        when {
            // Date literal: digits followed by / (e.g. 2026/2/4)
            peek() == '/' && isDigit(peekNext()) -> {
                scanDateOrDateTime()
                return
            }

            // Decimal point: could be a float/double/dec OR a range (..)
            peek() == '.' && peekNext() != '.' && peekNext() != '<' -> {
                advance() // consume '.'

                // Must have digits after the decimal point for it to be a number
                if (isDigit(peek())) {
                    consumeDigits()
                    // Check for version pattern: 5.0 followed by .digit (e.g., 5.0.0, 1.2.3)
                    if (peek() == '.' && isDigit(peekNext())) {
                        scanVersionContinuation()
                        return
                    }
                    finishNumberWithSuffix(hasDecimalPoint = true)
                } else {
                    // It's an integer followed by a dot (member access)
                    // Back up the dot
                    current--
                    column--
                    finishNumberWithSuffix(hasDecimalPoint = false)
                }
                return
            }

            // Colon after digits: could be a duration (12:30:00)
            peek() == ':' && isDigit(peekNext()) -> {
                scanDuration()
                return
            }

            else -> {
                finishNumberWithSuffix(hasDecimalPoint = false)
                return
            }
        }
    }

    private fun scanHexNumber() {
        if (!isHexDigit(peek())) {
            error("Expected hex digit after '0x'")
        }
        while (isHexDigit(peek()) || peek() == '_') advance()
        val text = currentText().replace("_", "")
        val value = java.lang.Long.parseLong(text.substring(2), 16)
        if (value <= Int.MAX_VALUE && value >= Int.MIN_VALUE) {
            addToken(TokenType.INT_LITERAL, value.toInt())
        } else {
            addToken(TokenType.LONG_LITERAL, value)
        }
    }

    private fun scanBinaryNumber() {
        if (peek() != '0' && peek() != '1') {
            error("Expected binary digit after '0b'")
        }
        while (peek() == '0' || peek() == '1' || peek() == '_') advance()
        val text = currentText().replace("_", "")
        val value = java.lang.Long.parseLong(text.substring(2), 2)
        if (value <= Int.MAX_VALUE && value >= Int.MIN_VALUE) {
            addToken(TokenType.INT_LITERAL, value.toInt())
        } else {
            addToken(TokenType.LONG_LITERAL, value)
        }
    }

    /**
     * After consuming all digits (and possible decimal point + digits),
     * check for:
     *   1. Scientific notation exponent (e.g., 5.5e8, 5.5e(-7), 5.5en7)
     *   2. Unit suffix → QUANTITY_LITERAL (e.g., 23cm, 1000kg, 25°C)
     *   3. Number type suffix → typed number (L, f/F, d/D, BD/bd)
     *   4. No suffix → plain number
     *
     * Disambiguation: If the suffix is exactly a bare number type marker
     * (L, f, F, d, D, BD, bd) with nothing following, it's a typed number.
     * Otherwise, the suffix is treated as a unit symbol (quantity literal).
     * Examples: `42L` → Long, `42LT` → liters quantity, `23d` → Double,
     * `23dC` → degrees Celsius quantity.
     */
    private fun finishNumberWithSuffix(hasDecimalPoint: Boolean) {
        // --- Phase 1: Try to consume scientific notation ---
        val hasSciNotation = tryConsumeScientificNotation()

        // --- Phase 2: Check for unit suffix vs number type suffix ---
        val suffix = peekFullSuffix()

        if (suffix.isEmpty()) {
            // No suffix at all → plain number
            emitPlainNumber(hasDecimalPoint || hasSciNotation)
            return
        }

        // If we have scientific notation, any alpha suffix MUST be a unit
        // (e.g., 5.5e8m → quantity, not a type-suffixed number)
        if (hasSciNotation) {
            consumeFullSuffix()
            tryConsumeQuantityTypeSpec()
            addToken(TokenType.QUANTITY_LITERAL, currentText())
            return
        }

        // No scientific notation. Check if suffix is EXACTLY a number type marker.
        if (isExactlyNumberTypeSuffix(suffix, hasDecimalPoint)) {
            emitTypedNumber(hasDecimalPoint)
            return
        }

        // Suffix is a unit → consume and emit quantity literal
        consumeFullSuffix()
        tryConsumeQuantityTypeSpec()
        addToken(TokenType.QUANTITY_LITERAL, currentText())
    }

    /**
     * Checks if the peeked suffix is exactly a bare number type marker with
     * no further alphanumeric characters following it.
     *
     * Number type suffixes: L (Long), f/F (Float), d/D (Double), BD/bd (Dec).
     * If ANY additional characters follow (e.g., "LT", "dC"), it's NOT a
     * pure type suffix — it's a unit symbol.
     */
    private fun isExactlyNumberTypeSuffix(suffix: String, hasDecimalPoint: Boolean): Boolean {
        return when (suffix) {
            "L", "l" -> !hasDecimalPoint  // L is only valid for integers
            "f", "F" -> true
            "d", "D" -> true
            "BD", "bd", "Bd", "bD" -> true
            else -> false
        }
    }

    /**
     * Emits a plain (unsuffixed) number token.
     * If hasDecimalOrSci is true → Double, otherwise Int or Long.
     */
    private fun emitPlainNumber(hasDecimalOrSci: Boolean) {
        val text = currentText().replace("_", "")
        if (hasDecimalOrSci) {
            addToken(TokenType.DOUBLE_LITERAL, text.toDouble())
        } else {
            val value = text.toLong()
            if (value <= Int.MAX_VALUE && value >= Int.MIN_VALUE) {
                addToken(TokenType.INT_LITERAL, value.toInt())
            } else {
                addToken(TokenType.LONG_LITERAL, value)
            }
        }
    }

    /**
     * Emits a typed number token using the number type suffix at peek position.
     * Assumes [isExactlyNumberTypeSuffix] already returned true.
     */
    private fun emitTypedNumber(hasDecimalPoint: Boolean) {
        val c = peek()
        val cn = peekNext()

        when {
            // BD / bd → Dec
            (c == 'B' || c == 'b') && (cn == 'D' || cn == 'd') -> {
                advance() // consume B/b
                advance() // consume D/d
                val text = currentText().replace("_", "").let {
                    it.substring(0, it.length - 2) // strip BD
                }
                addToken(TokenType.DEC_LITERAL, java.math.BigDecimal(text))
            }

            // L / l → Long (only without decimal point)
            (c == 'L' || c == 'l') && !hasDecimalPoint -> {
                advance()
                val text = currentText().replace("_", "").let {
                    it.substring(0, it.length - 1) // strip L
                }
                addToken(TokenType.LONG_LITERAL, text.toLong())
            }

            // f / F → Float
            c == 'f' || c == 'F' -> {
                advance()
                val text = currentText().replace("_", "").let {
                    it.substring(0, it.length - 1) // strip f
                }
                addToken(TokenType.FLOAT_LITERAL, text.toFloat())
            }

            // d / D → Double (explicit)
            c == 'd' || c == 'D' -> {
                advance()
                val text = currentText().replace("_", "").let {
                    it.substring(0, it.length - 1) // strip d
                }
                addToken(TokenType.DOUBLE_LITERAL, text.toDouble())
            }
        }
    }

    // ========================================================================
    // Quantity / UoM support
    // ========================================================================

    /**
     * Attempts to consume a scientific notation exponent after the mantissa.
     *
     * Supported forms (from KD spec):
     * - Parenthesized: `5.5e(-7)`, `1.5e(8)`, `5.5E(-7)`
     * - Letter style: `5.5en7` (n = negative), `5.5ep8` (p = positive)
     * - Standard: `5.5e8`, `5.5e+8`, `5.5e-8`
     *
     * If the 'e'/'E' is not followed by a valid exponent pattern, the lexer
     * backtracks and returns false (the 'e' may be part of a unit symbol).
     *
     * @return true if scientific notation was consumed
     */
    private fun tryConsumeScientificNotation(): Boolean {
        if (peek() != 'e' && peek() != 'E') return false

        val saved = current
        val savedCol = column
        advance() // consume 'e' or 'E'

        when {
            // Parenthesized: e(-7), e(8), e(+3)
            peek() == '(' -> {
                advance() // consume '('
                if (peek() == '-' || peek() == '+') advance()
                if (!isDigit(peek())) { current = saved; column = savedCol; return false }
                consumeDigits()
                if (peek() != ')') { current = saved; column = savedCol; return false }
                advance() // consume ')'
                return true
            }
            // Negative letter style: en7
            peek() == 'n' && isDigit(peekNext()) -> {
                advance() // consume 'n'
                consumeDigits()
                return true
            }
            // Positive letter style: ep8
            peek() == 'p' && isDigit(peekNext()) -> {
                advance() // consume 'p'
                consumeDigits()
                return true
            }
            // Standard with sign: e+7, e-7
            (peek() == '+' || peek() == '-') && isDigit(peekNext()) -> {
                advance() // consume sign
                consumeDigits()
                return true
            }
            // Standard without sign: e8
            isDigit(peek()) -> {
                consumeDigits()
                return true
            }
            // 'e' not followed by valid exponent → backtrack
            else -> {
                current = saved
                column = savedCol
                return false
            }
        }
    }

    /**
     * Scans a currency quantity literal after the prefix symbol has been consumed.
     * The prefix character ($, €, ¥, £, ₿, Ξ) is already in the token text.
     *
     * Consumes: digits, optional decimal point + digits, optional scientific
     * notation, and optional quantity type specifier (e.g., `:d`, `:L`).
     *
     * Examples: `$23.53`, `€50.25`, `¥10000`, `₿0.5e8`, `£75.50:d`
     */
    private fun scanCurrencyQuantity() {
        // Consume integer digits (with optional underscores)
        consumeDigits()

        // Optional decimal point + fractional digits
        if (peek() == '.' && isDigit(peekNext())) {
            advance() // consume '.'
            consumeDigits()
        }

        // Optional scientific notation
        tryConsumeScientificNotation()

        // Optional quantity type specifier (:d, :L, :f, :i)
        tryConsumeQuantityTypeSpec()

        addToken(TokenType.CURRENCY_QUANTITY_LITERAL, currentText())
    }

    /**
     * Peeks at the full unit/type suffix without consuming any characters.
     * Collects consecutive unit-suffix characters: alpha, °, ², ³, ℓ, µ.
     *
     * Examples:
     * - After `23`: peeks "cm" → "cm"
     * - After `42`: peeks "L" → "L"
     * - After `42`: peeks "LT" → "LT"
     * - After `25`: peeks "°C" → "°C"
     * - After `51.4`: peeks "m³" → "m³"
     */
    private fun peekFullSuffix(): String {
        val sb = StringBuilder()
        var i = current
        while (i < source.length && isUnitSuffixChar(source[i])) {
            sb.append(source[i])
            i++
        }
        return sb.toString()
    }

    /**
     * Consumes consecutive unit-suffix characters at the current position.
     * Must only be called after [peekFullSuffix] confirmed a non-empty suffix.
     */
    private fun consumeFullSuffix() {
        while (!isAtEnd() && isUnitSuffixChar(peek())) advance()
    }

    /**
     * Checks if a character can appear in a unit suffix.
     * Includes ASCII letters and Unicode symbols used in Ki units:
     * °, ², ³, ℓ, µ
     */
    private fun isUnitSuffixChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c == '°' || c == '²' || c == '³' || c == 'ℓ' || c == 'µ'

    /**
     * Tries to consume a quantity type specifier at the current position.
     * Form: `:` followed by a type character (d, D, L, f, F, i, I).
     *
     * Example: `23cm:d` → the `:d` part is the quantity type specifier,
     * meaning the numeric value should be stored as a Double.
     */
    private fun tryConsumeQuantityTypeSpec() {
        if (peek() == ':' && isQuantityTypeChar(peekNext())) {
            advance() // consume ':'
            advance() // consume type char
        }
    }

    /**
     * Checks if a character is a valid quantity type indicator.
     * These correspond to Ki's numeric types when used in quantity literals:
     * - d/D → Double
     * - L → Long
     * - f/F → Float
     * - i/I → Int
     */
    private fun isQuantityTypeChar(c: Char): Boolean =
        c == 'd' || c == 'D' || c == 'L' || c == 'f' || c == 'F' || c == 'i' || c == 'I'

    // ========================================================================
    // Date / DateTime scanning
    // ========================================================================

    /**
     * Scans a version continuation after an initial "major.minor" has been consumed.
     *
     * Called when we've consumed digits like "5.0" and see another "." followed by
     * a digit, indicating a version literal like "5.0.0" or "1.2.3.4".
     *
     * Continues consuming ".digit" sequences and an optional "-qualifier" suffix
     * (e.g., "5.0.0-beta", "1.2.3-rc1", "5.0.0-beta-2").
     *
     * Emits a STRING_LITERAL token (consistent with date/duration handling) so
     * the parser/interpreter can convert it to a Version object.
     */
    private fun scanVersionContinuation() {
        // We've already consumed "major.minor" (e.g., "5.0").
        // Continue consuming ".digits" sequences.
        while (peek() == '.' && isDigit(peekNext())) {
            advance() // consume '.'
            consumeDigits()
        }

        // Check for optional qualifier: -alpha, -beta, -rc1, -beta-2, etc.
        if (peek() == '-' && peekNext().isLetter()) {
            advance() // consume '-'
            // Consume qualifier: alphanumeric, hyphens, underscores, dots
            while (!isAtEnd()) {
                val c = peek()
                if (c.isLetterOrDigit() || c == '-' || c == '_') {
                    advance()
                } else {
                    break
                }
            }
        }

        addToken(TokenType.STRING_LITERAL, currentText())
    }

    /**
     * Scans a date or datetime literal.
     * Date:          2026/2/4
     * LocalDateTime: 2026/2/4@14:30
     * ZonedDateTime: 2026/2/4@14:30:00-Z or 2026/2/4@14:30+9
     *
     * For now, the entire date/datetime is stored as a string literal value.
     * The parser or a later phase will parse it into the appropriate KD type.
     */
    private fun scanDateOrDateTime() {
        // We've already consumed the year digits. Now consume /month/day
        advance() // consume '/'
        consumeDigits() // month
        if (peek() == '/') {
            advance() // consume '/'
            consumeDigits() // day
        }

        var hasTime = false
        var hasTimezone = false

        // Check for time component: @hh:mm...
        if (peek() == '@') {
            hasTime = true
            advance() // consume '@'
            consumeDigits() // hours
            if (peek() == ':') {
                advance() // consume ':'
                consumeDigits() // minutes
                if (peek() == ':') {
                    advance() // consume ':'
                    consumeDigits() // seconds
                    if (peek() == '.') {
                        advance() // consume '.'
                        consumeDigits() // fractional seconds
                    }
                }
            }

            // Check for timezone: -Z, -UTC, +offset, -offset
            if (peek() == '-' || peek() == '+') {
                hasTimezone = true
                advance()
                if (previous() == '-' && (peek() == 'Z' || peek() == 'U')) {
                    if (peek() == 'Z') {
                        advance()
                    } else {
                        // -UTC
                        if (peekWord() == "UTC") {
                            advance(); advance(); advance()
                        }
                    }
                } else {
                    // Numeric offset: +9, -5, +5:30
                    consumeDigits()
                    if (peek() == ':') {
                        advance()
                        consumeDigits()
                    }
                }
            }
        }

        val text = currentText()

        // Parse into the appropriate java.time type, falling back to string
        val parsedValue: Any = when {
            hasTimezone -> tryParseZonedDateTime(text) ?: text
            hasTime -> tryParseLocalDateTime(text) ?: text
            else -> tryParseLocalDate(text) ?: text
        }

        addToken(TokenType.STRING_LITERAL, parsedValue)
    }

    /**
     * Parses a KD date literal (y/M/d) into a [LocalDate].
     * Returns null if parsing fails.
     */
    private fun tryParseLocalDate(text: String): LocalDate? {
        return try {
            val parts = text.split('/')
            if (parts.size == 3) {
                LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            } else null
        } catch (e: Exception) { null }
    }

    /**
     * Parses a KD datetime literal (y/M/d@H:mm or y/M/d@H:mm:ss.fff)
     * into a [LocalDateTime]. Returns null if parsing fails.
     */
    private fun tryParseLocalDateTime(text: String): LocalDateTime? {
        return try {
            val atIdx = text.indexOf('@')
            if (atIdx < 0) return null
            val date = tryParseLocalDate(text.substring(0, atIdx)) ?: return null
            val time = parseTimePart(text.substring(atIdx + 1))
            LocalDateTime.of(date, time)
        } catch (e: Exception) { null }
    }

    /**
     * Parses a KD zoned datetime literal (y/M/d@H:mm:ss-Z, y/M/d@H:mm:ss+5, etc.)
     * into an [OffsetDateTime]. Returns null if parsing fails.
     */
    private fun tryParseZonedDateTime(text: String): OffsetDateTime? {
        return try {
            val atIdx = text.indexOf('@')
            if (atIdx < 0) return null
            val date = tryParseLocalDate(text.substring(0, atIdx)) ?: return null
            val afterAt = text.substring(atIdx + 1)

            // Find timezone separator: last + or - in the time part
            val tzIdx = findTzSeparator(afterAt)
            if (tzIdx < 0) return null

            val timeStr = afterAt.substring(0, tzIdx)
            val tzStr = afterAt.substring(tzIdx)
            val time = parseTimePart(timeStr)
            val offset = parseTzOffset(tzStr)
            OffsetDateTime.of(date, time, offset)
        } catch (e: Exception) { null }
    }

    /** Parses a KD time part: H:mm, H:mm:ss, or H:mm:ss.fff */
    private fun parseTimePart(text: String): LocalTime {
        val parts = text.split(':')
        val hour = parts[0].toInt()
        val minute = if (parts.size > 1) parts[1].toInt() else 0
        var second = 0
        var nano = 0
        if (parts.size > 2) {
            val secParts = parts[2].split('.')
            second = secParts[0].toInt()
            if (secParts.size > 1) {
                nano = secParts[1].padEnd(9, '0').take(9).toInt()
            }
        }
        return LocalTime.of(hour, minute, second, nano)
    }

    /** Finds the start of the timezone separator (+/-) after the time digits. */
    private fun findTzSeparator(afterAt: String): Int {
        // Walk backwards from the end to find the timezone separator
        // Timezone patterns: -Z, -UTC, +N, -N, +N:NN, -N:NN
        for (i in afterAt.indices.reversed()) {
            if ((afterAt[i] == '+' || afterAt[i] == '-') && i > 0) {
                // Make sure this isn't inside the time part (i.e., after last ':')
                val beforeSign = afterAt.substring(0, i)
                if (beforeSign.last().isDigit()) return i
            }
        }
        return -1
    }

    /** Parses a timezone offset: -Z, -UTC, +5, -5:30, etc. */
    private fun parseTzOffset(tzStr: String): ZoneOffset {
        val stripped = tzStr.removePrefix("+").removePrefix("-")
        val negative = tzStr.startsWith("-")

        return when {
            stripped == "Z" || stripped == "UTC" -> ZoneOffset.UTC
            ':' in stripped -> {
                val parts = stripped.split(':')
                val hours = parts[0].toInt()
                val minutes = parts[1].toInt()
                val totalSeconds = (hours * 3600 + minutes * 60) * (if (negative) -1 else 1)
                ZoneOffset.ofTotalSeconds(totalSeconds)
            }
            else -> {
                val hours = stripped.toInt()
                ZoneOffset.ofHours(if (negative) -hours else hours)
            }
        }
    }

    // ========================================================================
    // Duration scanning
    // ========================================================================

    /**
     * Scans a duration literal.
     * Forms: 12:30:00, 30days:05:21:23.53, 85ms
     * Stored as string for parser interpretation.
     */
    private fun scanDuration() {
        // We've consumed initial digits. Peek shows ':'
        // Consume the full duration pattern: hh:mm(:ss(.frac)?)?
        // Or Ndays:hh:mm:ss
        advance() // consume ':'
        consumeDigits() // minutes or next component

        while (peek() == ':') {
            advance()
            consumeDigits()
            if (peek() == '.') {
                advance()
                consumeDigits()
            }
        }

        val text = currentText()
        addToken(TokenType.STRING_LITERAL, text)
    }

    // ========================================================================
    // String scanning
    // ========================================================================

    /**
     * Scans a standard string: "..."
     * Supports escape characters and string interpolation ($var, ${expr}).
     *
     * For interpolation, the lexer emits the string parts as STRING_LITERAL tokens.
     * A more sophisticated approach would be to emit interpolation markers, but
     * for the initial implementation we resolve the string as a whole and mark
     * that it contains interpolation for the parser to handle.
     */
    private fun scanString() {
        val sb = StringBuilder()

        while (!isAtEnd() && peek() != '"') {
            when {
                peek() == '\\' -> {
                    advance() // consume backslash
                    sb.append(scanEscapeChar())
                }
                peek() == '$' && peekNext() == '{' -> {
                    // Interpolation block: ${ ... }
                    // Consume the entire block including nested strings/braces
                    sb.append(advance()) // consume '$'
                    sb.append(advance()) // consume '{'
                    scanInterpolationBlock(sb)
                }
                peek() == '\n' -> {
                    error("Unterminated string (use \"\"\" for multiline strings)")
                }
                else -> {
                    sb.append(advance())
                }
            }
        }

        if (isAtEnd()) {
            error("Unterminated string")
        }

        advance() // consume closing "
        addToken(TokenType.STRING_LITERAL, sb.toString())
    }

    /**
     * Scans the contents of a `${...}` interpolation block inside a string.
     * Tracks brace depth and handles nested strings so that a `"` inside
     * the interpolation doesn't prematurely terminate the outer string.
     *
     * The opening `${` has already been consumed and appended to [sb].
     * This method consumes up to and including the matching closing `}`.
     */
    private fun scanInterpolationBlock(sb: StringBuilder) {
        var depth = 1
        while (!isAtEnd() && depth > 0) {
            when {
                peek() == '{' -> {
                    depth++
                    sb.append(advance())
                }
                peek() == '}' -> {
                    depth--
                    sb.append(advance())
                }
                peek() == '"' -> {
                    // Nested string inside interpolation — consume it entirely
                    sb.append(advance()) // consume opening "
                    while (!isAtEnd() && peek() != '"') {
                        if (peek() == '\\') {
                            sb.append(advance()) // consume backslash
                            if (!isAtEnd()) sb.append(advance()) // consume escaped char
                        } else {
                            sb.append(advance())
                        }
                    }
                    if (!isAtEnd()) sb.append(advance()) // consume closing "
                }
                peek() == '\'' -> {
                    // Char literal inside interpolation
                    sb.append(advance()) // consume opening '
                    while (!isAtEnd() && peek() != '\'') {
                        if (peek() == '\\') {
                            sb.append(advance())
                            if (!isAtEnd()) sb.append(advance())
                        } else {
                            sb.append(advance())
                        }
                    }
                    if (!isAtEnd()) sb.append(advance()) // consume closing '
                }
                peek() == '\n' -> {
                    sb.append(advance())
                    line++
                    column = 1
                }
                else -> sb.append(advance())
            }
        }
        if (depth > 0) error("Unterminated interpolation block in string")
    }

    /**
     * Scans escape character after backslash.
     */
    private fun scanEscapeChar(): Char {
        if (isAtEnd()) error("Unterminated escape sequence")
        return when (val c = advance()) {
            't' -> '\t'
            'b' -> '\b'
            'n' -> '\n'
            'r' -> '\r'
            '\\' -> '\\'
            '\'' -> '\''
            '"' -> '"'
            'u' -> {
                // Unicode escape: \uXXXX
                val hex = StringBuilder()
                repeat(4) {
                    if (isAtEnd() || !isHexDigit(peek())) {
                        error("Invalid unicode escape sequence")
                    }
                    hex.append(advance())
                }
                hex.toString().toInt(16).toChar()
            }
            else -> error("Unknown escape character '\\$c'")
        }
    }

    /**
     * Scans a verbatim string: @"..."
     * No escape processing. Leading @ and opening " already consumed.
     */
    private fun scanVerbatimString() {
        advance() // consume opening "
        val sb = StringBuilder()

        while (!isAtEnd() && peek() != '"') {
            if (peek() == '\n') {
                error("Unterminated verbatim string (use @\"\"\"...\"\"\" for multiline)")
            }
            sb.append(advance())
        }

        if (isAtEnd()) error("Unterminated verbatim string")
        advance() // consume closing "
        addToken(TokenType.VERBATIM_STRING, sb.toString())
    }

    /**
     * Scans a multiline string: """..."""
     * KD/Swift-style dedenting based on closing """ indentation.
     *
     * Rules (from KD spec, identical to Swift):
     * 1. Content starts on the line after the opening """
     * 2. The whitespace before the closing """ determines the base indentation
     * 3. That base indentation is stripped from the start of every content line
     * 4. Lines with less indentation than the base are an error
     * 5. The leading newline (after opening """) and trailing newline + indent
     *    (before closing """) are stripped
     *
     * Opening """ already consumed.
     */
    private fun scanMultilineString() {
        val sb = StringBuilder()

        while (!isAtEnd()) {
            if (peek() == '"' && peekNext() == '"' && peekAt(current + 2) == '"') {
                advance(); advance(); advance() // consume closing """
                addToken(TokenType.MULTILINE_STRING, dedentMultiline(sb.toString()))
                return
            }
            when {
                peek() == '\\' -> {
                    advance()
                    sb.append(scanEscapeChar())
                }
                peek() == '\n' -> {
                    sb.append(advance())
                    line++
                    column = 1
                }
                else -> sb.append(advance())
            }
        }

        error("Unterminated multiline string")
    }

    /**
     * Scans a verbatim multiline string: @"""..."""
     * No escape processing, but KD/Swift-style dedenting still applies.
     * Leading @ already consumed.
     */
    private fun scanVerbatimMultilineString() {
        advance() // consume first "
        advance() // consume second "
        advance() // consume third "
        val sb = StringBuilder()

        while (!isAtEnd()) {
            if (peek() == '"' && peekNext() == '"' && peekAt(current + 2) == '"') {
                advance(); advance(); advance() // consume closing """
                addToken(TokenType.VERBATIM_MULTILINE, dedentMultiline(sb.toString()))
                return
            }
            if (peek() == '\n') {
                sb.append(advance())
                line++
                column = 1
            } else {
                sb.append(advance())
            }
        }

        error("Unterminated verbatim multiline string")
    }

    /**
     * Scans a backtick string: `...`
     */
    private fun scanBacktickString() {
        val sb = StringBuilder()

        while (!isAtEnd() && peek() != '`') {
            if (peek() == '\n') {
                sb.append(advance())
                line++
                column = 1
            } else {
                sb.append(advance())
            }
        }

        if (isAtEnd()) error("Unterminated backtick string")
        advance() // consume closing `
        addToken(TokenType.BACKTICK_STRING, sb.toString())
    }

    // ========================================================================
    // Char scanning
    // ========================================================================

    /**
     * Scans a character literal: 'X'
     * Supports escape sequences: '\n', '\t', etc.
     */
    private fun scanChar() {
        val ch: Char
        if (peek() == '\\') {
            advance() // consume backslash
            ch = scanEscapeChar()
        } else {
            ch = advance()
        }

        if (!match('\'')) {
            error("Unterminated character literal")
        }

        addToken(TokenType.CHAR_LITERAL, ch)
    }

    // ========================================================================
    // URL scanning
    // ========================================================================

    /**
     * Check if current position looks like a URL after '<'.
     * We look for known scheme prefixes.
     */
    private fun isUrlStart(): Boolean {
        val remaining = source.substring(current)
        return remaining.startsWith("http://") ||
                remaining.startsWith("https://") ||
                remaining.startsWith("ftp://") ||
                remaining.startsWith("sftp://") ||
                remaining.startsWith("ftps://") ||
                remaining.startsWith("ldap://") ||
                remaining.startsWith("file://") ||
                remaining.startsWith("mailto:") ||
                remaining.startsWith("data://") ||
                remaining.startsWith("blob://")
    }

    /**
     * Scans a URL literal: <https://example.com>
     * Opening '<' already consumed.
     */
    private fun scanUrlLiteral() {
        val sb = StringBuilder()

        while (!isAtEnd() && peek() != '>') {
            if (peek() == '\n') error("Unterminated URL literal")
            sb.append(advance())
        }

        if (isAtEnd()) error("Unterminated URL literal (missing '>')")
        advance() // consume '>'
        addToken(TokenType.URL_LITERAL, sb.toString())
    }

    // ========================================================================
    // Blob scanning
    // ========================================================================

    /**
     * Scans a blob literal: .blob(base64data)
     * ".blob(" already consumed.
     */
    private fun scanBlobLiteral() {
        val sb = StringBuilder()

        while (!isAtEnd() && peek() != ')') {
            if (peek() == '\n') error("Unterminated blob literal")
            sb.append(advance())
        }

        if (isAtEnd()) error("Unterminated blob literal (missing ')')")
        advance() // consume ')'
        addToken(TokenType.STRING_LITERAL, ".blob($sb)") // stored as string for now
    }

    // ========================================================================
    // Identifier / keyword scanning
    // ========================================================================

    /**
     * Scans an identifier or keyword.
     * Identifiers start with a letter or underscore, followed by letters, digits, underscores.
     * If the identifier matches a keyword, the keyword token type is used.
     */
    private fun scanIdentifier() {
        while (isAlphaNumeric(peek())) advance()

        // Check for naked URL: identifier followed by ://
        // e.g., http://example.com, https://foo.bar/path
        if (peek() == ':' && peekNext() == '/' && peekAt(current + 2) == '/') {
            scanNakedUrl()
            return
        }

        val text = currentText()
        val keywordType = TokenType.KEYWORDS[text]

        if (keywordType != null) {
            when (keywordType) {
                TokenType.TRUE -> addToken(TokenType.TRUE, true)
                TokenType.FALSE -> addToken(TokenType.FALSE, false)
                TokenType.NIL -> addToken(TokenType.NIL, null)
                else -> addToken(keywordType)
            }
        } else {
            addToken(TokenType.IDENTIFIER)
        }
    }

    /**
     * Scans a naked URL literal (no angle brackets): http://example.com/path
     *
     * Called from [scanIdentifier] when the identifier is followed by `://`.
     * The protocol identifier (e.g., "http", "https") has already been consumed.
     * Consumes `://` and the rest of the URL until whitespace or a delimiter.
     *
     * Naked URLs are common in KD data blocks:
     *     module http://kixi.io/packages/widgets version=5.0.0
     */
    private fun scanNakedUrl() {
        advance() // consume ':'
        advance() // consume first '/'
        advance() // consume second '/'

        // Consume URL characters until a terminator
        while (!isAtEnd() && !isNakedUrlTerminator(peek())) {
            if (peek() == '\n') break
            advance()
        }

        addToken(TokenType.URL_LITERAL, currentText())
    }

    /**
     * Characters that terminate a naked URL.
     * Matches KD parser behavior for naked URL boundaries.
     */
    private fun isNakedUrlTerminator(c: Char): Boolean =
        c.isWhitespace() || c == '{' || c == '}' || c == '[' || c == ']' ||
                c == '(' || c == ')' || c == ',' || c == ';'

    // ========================================================================
    // Comments
    // ========================================================================

    private fun skipLineComment() {
        while (!isAtEnd() && peek() != '\n') advance()
    }

    private fun skipBlockComment() {
        var depth = 1
        while (!isAtEnd() && depth > 0) {
            when {
                peek() == '/' && peekNext() == '*' -> {
                    advance(); advance()
                    depth++
                }
                peek() == '*' && peekNext() == '/' -> {
                    advance(); advance()
                    depth--
                }
                peek() == '\n' -> {
                    advance()
                    line++
                    column = 1
                }
                else -> advance()
            }
        }
        if (depth > 0) error("Unterminated block comment")
    }

    // ========================================================================
    // Newline significance
    // ========================================================================

    /**
     * Determines whether a newline should be emitted as a NEWLINE token.
     * A newline is NOT a statement separator when the previous token is an
     * operator, open bracket, comma, or similar continuation token.
     */
    private fun shouldEmitNewline(): Boolean {
        if (tokens.isEmpty()) return false
        val lastType = tokens.last().type
        return when (lastType) {
            // These tokens indicate the line continues
            TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.STAR_STAR,
            TokenType.SLASH, TokenType.PERCENT, TokenType.COMBINE,
            TokenType.EQUAL, TokenType.PLUS_EQUAL, TokenType.MINUS_EQUAL,
            TokenType.STAR_EQUAL, TokenType.STAR_STAR_EQUAL,
            TokenType.SLASH_EQUAL, TokenType.PERCENT_EQUAL,
            TokenType.AMP_AMP, TokenType.PIPE_PIPE,
            TokenType.LESS, TokenType.GREATER, TokenType.LESS_EQUAL, TokenType.GREATER_EQUAL,
            TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL,
            TokenType.DOT, TokenType.QUESTION_DOT, TokenType.ELVIS,
            TokenType.DOT_DOT, TokenType.DOT_DOT_LESS, TokenType.LESS_DOT_DOT,
            TokenType.LESS_DOT_DOT_LESS,
            TokenType.COMMA,
            TokenType.LPAREN, TokenType.LBRACKET, TokenType.LBRACE,
            TokenType.ARROW, TokenType.COLON,
            TokenType.NEWLINE -> false

            // These tokens can end a statement
            else -> true
        }
    }

    // ========================================================================
    // Multiline string dedenting (KD spec / Swift-style)
    // ========================================================================

    /**
     * Applies KD/Swift-style dedenting to a raw multiline string.
     *
     * From the KD spec:
     *   "The white space prefix of lines in Block and BlockRaw is truncated
     *    if it matches the white space before the closing quotes (""")."
     *
     * Algorithm:
     * 1. Strip the leading newline (the one immediately after opening """)
     * 2. The last line (before closing """) must be whitespace-only — that
     *    whitespace is the base indentation level
     * 3. Strip the base indentation from the start of every content line
     * 4. If a non-blank line does not start with the base indentation, error
     * 5. Strip the trailing newline + indent line
     *
     * The opening quote mark's location is disregarded (per spec).
     * This behavior is identical to Swift's multi-line String literals.
     */
    private fun dedentMultiline(raw: String): String {
        // Step 1: Strip leading newline (content starts after first \n)
        val content = if (raw.startsWith("\n")) raw.substring(1) else raw

        // If empty after stripping, return empty
        if (content.isEmpty()) return ""

        // Step 2: Find the last line — this determines base indentation
        val lastNewline = content.lastIndexOf('\n')

        if (lastNewline == -1) {
            // No newlines in content — single line between """ markers
            // No dedenting needed, just return trimmed
            return content.trimEnd()
        }

        val lastLine = content.substring(lastNewline + 1)

        // Step 3: Determine the base indentation
        // The last line should be whitespace-only (the indent before closing """)
        val baseIndent = if (lastLine.all { it == ' ' || it == '\t' }) {
            lastLine
        } else {
            // Closing """ is on a line with content — no dedenting
            return content
        }

        // Step 4: Strip trailing indent line and dedent all content lines
        val bodyContent = content.substring(0, lastNewline)
        val lines = bodyContent.split("\n")

        val dedented = lines.map { line ->
            if (line.startsWith(baseIndent)) {
                line.substring(baseIndent.length)
            } else if (line.all { it == ' ' || it == '\t' } || line.length == 0) {
                // Blank lines are preserved as empty
                ""
            } else {
                // Line has less indentation than base — this is an error (Swift behavior)
                error("Insufficient indentation in multiline string literal")
            }
        }

        return dedented.joinToString("\n")
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private fun isAtEnd(): Boolean = current >= source.length

    private fun advance(): Char {
        val c = source[current]
        current++
        column++
        return c
    }

    private fun peek(): Char = if (isAtEnd()) '\u0000' else source[current]

    private fun peekNext(): Char = if (current + 1 >= source.length) '\u0000' else source[current + 1]

    private fun peekAt(index: Int): Char = if (index >= source.length) '\u0000' else source[index]

    private fun previous(): Char = source[current - 1]

    /**
     * Peek at the next few characters to form a word (for lookahead).
     */
    private fun peekWord(): String {
        val sb = StringBuilder()
        var i = current
        while (i < source.length && isAlpha(source[i])) {
            sb.append(source[i])
            i++
            if (sb.length > 10) break // reasonable limit
        }
        return sb.toString()
    }

    private fun match(expected: Char): Boolean {
        if (isAtEnd() || source[current] != expected) return false
        current++
        column++
        return true
    }

    private fun currentText(): String = source.substring(start, current)

    private fun addToken(type: TokenType, literal: Any? = null) {
        val text = currentText()
        tokens.add(Token(type, text, literal, SourceLocation(line = startLine, column = startColumn, offset = start)))
    }

    private fun consumeDigits() {
        while (isDigit(peek()) || peek() == '_') advance()
    }

    private fun isDigit(c: Char): Boolean = c in '0'..'9'

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun isAlpha(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c.isLetter()

    private fun isAlphaNumeric(c: Char): Boolean = isAlpha(c) || isDigit(c)

    private fun error(message: String): Nothing {
        throw LexerException(message, SourceLocation(line = line, column = column, offset = current))
    }
}