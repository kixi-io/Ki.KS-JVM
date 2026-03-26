package io.kixi.ks.lexer

/**
 * All token types for the KS language.
 *
 * Organized by category:
 * - Literals (numbers, strings, booleans, nil, etc.)
 * - Operators (arithmetic, comparison, logical, assignment, etc.)
 * - Delimiters (brackets, braces, parens, etc.)
 * - Keywords (var, let, fun, class, trait, enum, control flow, etc.)
 * - Special (EOF, NEWLINE, identifiers)
 *
 * Changes from previous version:
 * - Added STAR_STAR (**) for exponentiation: 5**3
 * - Added STAR_STAR_EQUAL (**=) for compound exponentiation assignment
 * - Added QUANTITY_LITERAL for unit-of-measure quantities: 23cm, 51.4m\u00b3, 1000kg
 * - Added CURRENCY_QUANTITY_LITERAL for prefix-notation currencies: $23.53, \u20ac50.25
 * - Added COMBINE (\u26ad) for unit composition: 4cm \u26ad 3cm \u2192 12cm\u00b2
 * - Added INFIX modifier keyword for infix function declarations
 */
enum class TokenType {

    // --- Identifiers ---
    IDENTIFIER,

    // --- Numeric Literals ---
    INT_LITERAL,            // 42, 0xFF, 0b1010
    LONG_LITERAL,           // 42L
    FLOAT_LITERAL,          // 3.14f
    DOUBLE_LITERAL,         // 3.14, 3.14d
    DEC_LITERAL,            // 3.14BD

    // --- Quantity Literals ---
    QUANTITY_LITERAL,           // 23cm, 51.4m\u00b3, 1000kg, 25\u00b0C, 97\u2113, 100USD, 5.5e(-7)m
    CURRENCY_QUANTITY_LITERAL,  // $23.53, \u20ac50.25, \u00a510000, \u00a375.50, \u20bf0.5, \u039e2.5

    // --- Version Literals ---
    VERSION_LITERAL,            // 5.0.0, 1.2.3_beta, 0.2.0_rc_1

    // --- String Literals ---
    STRING_LITERAL,         // "hello" (with escapes & interpolation)
    VERBATIM_STRING,        // @"no escapes"
    MULTILINE_STRING,       // """..."""
    VERBATIM_MULTILINE,     // @"""..."""
    BACKTICK_STRING,        // `raw`

    // --- Other Literals ---
    CHAR_LITERAL,           // 'A'
    TRUE,                   // true
    FALSE,                  // false
    NIL,                    // nil
    URL_LITERAL,            // <https://...>

    // --- Arithmetic Operators ---
    PLUS,                   // +
    MINUS,                  // -
    STAR,                   // *
    STAR_STAR,              // ** (exponentiation)
    SLASH,                  // /
    PERCENT,                // %

    // --- Unit Composition ---
    COMBINE,                // \u26ad (unit combine: 4cm \u26ad 3cm \u2192 12cm\u00b2)

    // --- Increment / Decrement ---
    PLUS_PLUS,              // ++
    MINUS_MINUS,            // --

    // --- Comparison Operators ---
    LESS,                   // <
    GREATER,                // >
    LESS_EQUAL,             // <=
    GREATER_EQUAL,          // >=
    EQUAL_EQUAL,            // ==
    BANG_EQUAL,             // !=

    // --- Logical Operators ---
    AMP_AMP,                // &&
    PIPE_PIPE,              // ||
    BANG,                   // !

    // --- Assignment Operators ---
    EQUAL,                  // =
    PLUS_EQUAL,             // +=
    MINUS_EQUAL,            // -=
    STAR_EQUAL,             // *=
    STAR_STAR_EQUAL,        // **= (compound exponentiation)
    SLASH_EQUAL,            // /=
    PERCENT_EQUAL,          // %=

    // --- Range Operators ---
    DOT_DOT,               // ..
    DOT_DOT_LESS,           // ..<
    LESS_DOT_DOT,           // <..
    LESS_DOT_DOT_LESS,      // <..<

    // --- Null Safety ---
    QUESTION_DOT,           // ?.
    ELVIS,                  // ?:
    BANG_BANG,              // !!

    // --- Delimiters ---
    LPAREN,                 // (
    RPAREN,                 // )
    LBRACKET,               // [
    RBRACKET,               // ]
    LBRACE,                 // {
    RBRACE,                 // }

    // --- Punctuation ---
    DOT,                    // .
    COMMA,                  // ,
    COLON,                  // :
    SEMICOLON,              // ;
    ARROW,                  // ->
    QUESTION,               // ?
    AT,                     // @
    UNDERSCORE,             // _
    COLON_COLON,            // ::
    DOLLAR,                 // $ (interpolation prefix in KD blocks: $var, ${expr})

    // --- Keywords: Declarations ---
    VAR,                    // var
    LET,                    // let
    FUN,                    // fun
    CLASS,                  // class
    TRAIT,                  // trait
    ENUM,                   // enum
    STRUCT,                 // struct
    STATIC,                 // static
    EXTEND,                 // extend

    // --- Keywords: Modifiers ---
    INFIX,                  // infix (modifier for fun declarations)

    // --- Keywords: Control Flow ---
    IF,                     // if
    ELSE,                   // else
    FOR,                    // for
    WHILE,                  // while
    WHEN,                   // when
    RETURN,                 // return
    BREAK,                  // break
    CONTINUE,               // continue

    // --- Keywords: Error Handling ---
    TRY,                    // try
    CATCH,                  // catch
    FINALLY,                // finally
    THROW,                  // throw

    // --- Keywords: Type / Check ---
    IN,                     // in
    NOT_IN,                 // !in (lexed as single token)
    IS,                     // is
    NOT_IS,                 // !is (lexed as single token)
    MATCHES,                // matches
    AS,                     // as

    // --- Keywords: Other ---
    USE,                    // use
    SAY,                    // say
    LANG,                   // lang

    // --- Special ---
    NEWLINE,                // significant newline (statement separator)
    EOF;                    // end of file

    companion object {
        /** Map of keyword strings to their TokenType. */
        val KEYWORDS: Map<String, TokenType> = mapOf(
            "var" to VAR,
            "let" to LET,
            "fun" to FUN,
            "class" to CLASS,
            "trait" to TRAIT,
            "enum" to ENUM,
            "struct" to STRUCT,
            "static" to STATIC,
            "extend" to EXTEND,
            "infix" to INFIX,
            "if" to IF,
            "else" to ELSE,
            "for" to FOR,
            "while" to WHILE,
            "when" to WHEN,
            "return" to RETURN,
            "break" to BREAK,
            "continue" to CONTINUE,
            "try" to TRY,
            "catch" to CATCH,
            "finally" to FINALLY,
            "throw" to THROW,
            "in" to IN,
            "is" to IS,
            "matches" to MATCHES,
            "as" to AS,
            "use" to USE,
            "say" to SAY,
            "lang" to LANG,
            "true" to TRUE,
            "false" to FALSE,
            "nil" to NIL
        )
    }
}