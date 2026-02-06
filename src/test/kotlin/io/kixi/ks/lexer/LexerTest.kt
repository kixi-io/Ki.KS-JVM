package io.kixi.ks.lexer

import io.kixi.ks.SourceLocation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import java.math.BigDecimal

/**
 * Comprehensive Kotest tests for the KS Lexer.
 *
 * Run with: ./gradlew test
 */
class LexerTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

    /** Tokenize source, returning all tokens including EOF */
    fun tokenize(source: String): List<Token> = Lexer(source).tokenize()

    /** Tokenize and return only meaningful tokens (no NEWLINE, no EOF) */
    fun tokens(source: String): List<Token> =
        tokenize(source).filter { it.type != TokenType.NEWLINE && it.type != TokenType.EOF }

    /** Tokenize and return just the types, excluding EOF */
    fun types(source: String): List<TokenType> =
        tokenize(source).map { it.type }.dropLast(1) // drop trailing EOF

    // ====================================================================
    // Variable Declarations
    // ====================================================================

    context("Variable declarations") {
        test("var with string value") {
            val toks = tokens("""var name = "Akiko"""")
            toks.map { it.type } shouldBe listOf(
                TokenType.VAR, TokenType.IDENTIFIER, TokenType.EQUAL, TokenType.STRING_LITERAL
            )
            toks[1].value shouldBe "name"
            toks[3].literal shouldBe "Akiko"
        }

        test("let with integer value") {
            val toks = tokens("let age = 42")
            toks.map { it.type } shouldBe listOf(
                TokenType.LET, TokenType.IDENTIFIER, TokenType.EQUAL, TokenType.INT_LITERAL
            )
            toks[3].literal shouldBe 42
        }

        test("var with explicit type annotation") {
            val toks = tokens("var height: Double = 68.0")
            toks.map { it.type } shouldBe listOf(
                TokenType.VAR, TokenType.IDENTIFIER, TokenType.COLON,
                TokenType.IDENTIFIER, TokenType.EQUAL, TokenType.DOUBLE_LITERAL
            )
            toks[3].value shouldBe "Double"
            toks[5].literal shouldBe 68.0
        }

        test("multiple declarations separated by newlines") {
            val src = "var a = 1\nlet b = 2"
            val all = tokenize(src).map { it.type }
            // Should be: VAR IDENTIFIER EQUAL INT_LITERAL NEWLINE LET IDENTIFIER EQUAL INT_LITERAL EOF
            all shouldBe listOf(
                TokenType.VAR, TokenType.IDENTIFIER, TokenType.EQUAL, TokenType.INT_LITERAL,
                TokenType.NEWLINE,
                TokenType.LET, TokenType.IDENTIFIER, TokenType.EQUAL, TokenType.INT_LITERAL,
                TokenType.EOF
            )
        }
    }

    // ====================================================================
    // Integer Literals
    // ====================================================================

    context("Integer literals") {
        test("simple integer") {
            val toks = tokens("42")
            toks shouldHaveSize 1
            toks[0].type shouldBe TokenType.INT_LITERAL
            toks[0].literal shouldBe 42
        }

        test("zero") {
            tokens("0")[0].literal shouldBe 0
        }

        test("integer with underscores") {
            val tok = tokens("424_235_412")[0]
            tok.type shouldBe TokenType.INT_LITERAL
            tok.literal shouldBe 424235412
        }

        test("hex literal lowercase") {
            val tok = tokens("0xFF")[0]
            tok.type shouldBe TokenType.INT_LITERAL
            tok.literal shouldBe 255
        }

        test("hex literal uppercase prefix") {
            val tok = tokens("0XAB")[0]
            tok.type shouldBe TokenType.INT_LITERAL
            tok.literal shouldBe 0xAB
        }

        test("binary literal") {
            val tok = tokens("0b1010")[0]
            tok.type shouldBe TokenType.INT_LITERAL
            tok.literal shouldBe 10
        }

        test("binary literal with underscores") {
            val tok = tokens("0b1010_0011")[0]
            tok.type shouldBe TokenType.INT_LITERAL
            tok.literal shouldBe 0b10100011
        }

        test("large hex overflows to Long") {
            val tok = tokens("0xFFFFFFFF")[0]
            tok.type shouldBe TokenType.LONG_LITERAL
            tok.literal shouldBe 0xFFFFFFFFL
        }
    }

    // ====================================================================
    // Long Literals
    // ====================================================================

    context("Long literals") {
        test("L suffix") {
            val tok = tokens("123L")[0]
            tok.type shouldBe TokenType.LONG_LITERAL
            tok.literal shouldBe 123L
        }

        test("lowercase l suffix") {
            val tok = tokens("999l")[0]
            tok.type shouldBe TokenType.LONG_LITERAL
            tok.literal shouldBe 999L
        }
    }

    // ====================================================================
    // Float Literals
    // ====================================================================

    context("Float literals") {
        test("f suffix with decimal") {
            val tok = tokens("3.14f")[0]
            tok.type shouldBe TokenType.FLOAT_LITERAL
            tok.literal shouldBe 3.14f
        }

        test("F suffix") {
            val tok = tokens("100.0F")[0]
            tok.type shouldBe TokenType.FLOAT_LITERAL
            tok.literal shouldBe 100.0f
        }

        test("integer with f suffix becomes float") {
            val tok = tokens("42f")[0]
            tok.type shouldBe TokenType.FLOAT_LITERAL
            tok.literal shouldBe 42.0f
        }
    }

    // ====================================================================
    // Double Literals
    // ====================================================================

    context("Double literals") {
        test("implicit double (decimal point, no suffix)") {
            val tok = tokens("3.14")[0]
            tok.type shouldBe TokenType.DOUBLE_LITERAL
            tok.literal shouldBe 3.14
        }

        test("explicit d suffix") {
            val tok = tokens("100.0d")[0]
            tok.type shouldBe TokenType.DOUBLE_LITERAL
            tok.literal shouldBe 100.0
        }

        test("explicit D suffix") {
            val tok = tokens("2.718D")[0]
            tok.type shouldBe TokenType.DOUBLE_LITERAL
            tok.literal shouldBe 2.718
        }
    }

    // ====================================================================
    // BigDecimal Literals
    // ====================================================================

    context("BigDecimal (Dec) literals") {
        test("BD suffix") {
            val tok = tokens("123.44BD")[0]
            tok.type shouldBe TokenType.DEC_LITERAL
            tok.literal shouldBe BigDecimal("123.44")
        }

        test("bd suffix") {
            val tok = tokens("0.001bd")[0]
            tok.type shouldBe TokenType.DEC_LITERAL
            tok.literal shouldBe BigDecimal("0.001")
        }

        test("zero BD") {
            val tok = tokens("0BD")[0]
            tok.type shouldBe TokenType.DEC_LITERAL
            (tok.literal as BigDecimal).compareTo(BigDecimal.ZERO) shouldBe 0
        }
    }

    // ====================================================================
    // Standard String Literals
    // ====================================================================

    context("Standard strings") {
        test("simple string") {
            tokens(""""Hello, world!"""")[0].literal shouldBe "Hello, world!"
        }

        test("empty string") {
            val tok = tokens("\"\"")[0]
            tok.type shouldBe TokenType.STRING_LITERAL
            tok.literal shouldBe ""
        }

        test("escape sequences") {
            tokens(""""tab\there\nnewline"""")[0].literal shouldBe "tab\there\nnewline"
        }

        test("escaped quote") {
            tokens(""""she said \"hi\""""")[0].literal shouldBe """she said "hi""""
        }

        test("unicode escape") {
            tokens(""""sakura: \u685C"""")[0].literal shouldBe "sakura: 桜"
        }

        test("backslash escape") {
            tokens(""""path\\file"""")[0].literal shouldBe "path\\file"
        }

        test("unterminated string throws LexerError") {
            shouldThrow<LexerException> { tokenize(""""unterminated""") }
        }

        test("newline in standard string throws LexerError") {
            shouldThrow<LexerException> { tokenize("\"line1\nline2\"") }
        }
    }

    // ====================================================================
    // Verbatim Strings
    // ====================================================================

    context("Verbatim strings") {
        test("no escape processing") {
            val tok = tokens("""@"C:\Users\file.txt"""")[0]
            tok.type shouldBe TokenType.VERBATIM_STRING
            tok.literal shouldBe """C:\Users\file.txt"""
        }

        test("backslash-n preserved literally") {
            tokens("""@"no\n escapes"""")[0].literal shouldBe """no\n escapes"""
        }
    }

    // ====================================================================
    // Multiline Strings (KD spec / Swift-style dedenting)
    // ====================================================================

    context("Multiline strings") {
        test("KD spec example: closing indent determines base") {
            // From KD spec:
            //   text="""
            //       ABC
            //           def
            //       123
            //       """
            // Closing """ has 4 spaces → strip 4 from each line
            val src = "\"\"\"\n    ABC\n        def\n    123\n    \"\"\""
            val tok = tokens(src)[0]
            tok.type shouldBe TokenType.MULTILINE_STRING
            tok.literal shouldBe "ABC\n    def\n123"
        }

        test("uniform indentation stripped") {
            val src = "\"\"\"\n    line1\n    line2\n    line3\n    \"\"\""
            tokens(src)[0].literal shouldBe "line1\nline2\nline3"
        }

        test("closing at column 1 strips no indent") {
            val src = "\"\"\"\nhello\nworld\n\"\"\""
            tokens(src)[0].literal shouldBe "hello\nworld"
        }

        test("blank line in content preserved") {
            val src = "\"\"\"\n    hello\n\n    world\n    \"\"\""
            tokens(src)[0].literal shouldBe "hello\n\nworld"
        }

        test("nested indentation preserved relative to base") {
            val src = "\"\"\"\n    if x {\n        say \"hi\"\n    }\n    \"\"\""
            tokens(src)[0].literal shouldBe "if x {\n    say \"hi\"\n}"
        }

        test("tab-based indentation") {
            val src = "\"\"\"\n\tABC\n\t\tdef\n\t\"\"\""
            tokens(src)[0].literal shouldBe "ABC\n\tdef"
        }

        test("inline multiline (no newlines)") {
            val src = "\"\"\"hello\"\"\""
            tokens(src)[0].literal shouldBe "hello"
        }

        test("escape sequences still processed") {
            val src = "\"\"\"\n    tab\\there\n    \"\"\""
            tokens(src)[0].literal shouldBe "tab\there"
        }

        test("under-indented line throws LexerError") {
            // Base indent is 8 spaces, but one line only has 4
            shouldThrow<LexerException> {
                tokenize("\"\"\"\n        deep\n    shallow\n        \"\"\"")
            }
        }

        test("unterminated multiline throws LexerError") {
            shouldThrow<LexerException> { tokenize("\"\"\"never closed") }
        }
    }

    // ====================================================================
    // Verbatim Multiline Strings (KD spec dedenting, no escapes)
    // ====================================================================

    context("Verbatim multiline strings") {
        test("dedenting applied, escapes preserved literally") {
            val src = "@\"\"\"\n    raw\\n content\n    has\\t tabs\n    \"\"\""
            val tok = tokens(src)[0]
            tok.type shouldBe TokenType.VERBATIM_MULTILINE
            tok.literal shouldBe "raw\\n content\nhas\\t tabs"
        }

        test("no base indent when closing at column 1") {
            val src = "@\"\"\"\nhello\\nworld\n\"\"\""
            tokens(src)[0].literal shouldBe "hello\\nworld"
        }

        test("under-indented line in verbatim throws LexerError") {
            shouldThrow<LexerException> {
                tokenize("@\"\"\"\n        deep\n    shallow\n        \"\"\"")
            }
        }
    }

    // ====================================================================
    // Backtick Strings
    // ====================================================================

    context("Backtick strings") {
        test("basic backtick") {
            val tok = tokens("""`backtick raw`""")[0]
            tok.type shouldBe TokenType.BACKTICK_STRING
            tok.literal shouldBe "backtick raw"
        }

        test("empty backtick") {
            val tok = tokens("``")[0]
            tok.type shouldBe TokenType.BACKTICK_STRING
            tok.literal shouldBe ""
        }

        test("unterminated backtick throws LexerError") {
            shouldThrow<LexerException> { tokenize("`never closed") }
        }
    }

    // ====================================================================
    // Char Literals
    // ====================================================================

    context("Char literals") {
        test("simple char") {
            val tok = tokens("'A'")[0]
            tok.type shouldBe TokenType.CHAR_LITERAL
            tok.literal shouldBe 'A'
        }

        test("escape char") {
            tokens("'\\n'")[0].literal shouldBe '\n'
        }

        test("tab escape") {
            tokens("'\\t'")[0].literal shouldBe '\t'
        }

        test("unicode char") {
            tokens("'\\u685C'")[0].literal shouldBe '桜'
        }

        test("unterminated char literal throws LexerError") {
            shouldThrow<LexerException> { tokenize("'A") }
        }
    }

    // ====================================================================
    // Bool & Nil Literals
    // ====================================================================

    context("Boolean and nil literals") {
        test("true") {
            val tok = tokens("true")[0]
            tok.type shouldBe TokenType.TRUE
            tok.literal shouldBe true
        }

        test("false") {
            val tok = tokens("false")[0]
            tok.type shouldBe TokenType.FALSE
            tok.literal shouldBe false
        }

        test("nil") {
            val tok = tokens("nil")[0]
            tok.type shouldBe TokenType.NIL
            tok.literal shouldBe null
        }

        test("true is not an identifier") {
            tokens("true")[0].type shouldBe TokenType.TRUE
        }
    }

    // ====================================================================
    // URL Literals
    // ====================================================================

    context("URL literals") {
        test("https URL") {
            val tok = tokens("<https://www.nasa.gov>")[0]
            tok.type shouldBe TokenType.URL_LITERAL
            tok.literal shouldBe "https://www.nasa.gov"
        }

        test("http URL") {
            tokens("<http://example.com>")[0].literal shouldBe "http://example.com"
        }

        test("URL with path and query") {
            tokens("<https://api.example.com/v1/users?page=2>")[0]
                .literal shouldBe "https://api.example.com/v1/users?page=2"
        }

        test("ftp URL") {
            tokens("<ftp://files.example.com/data>")[0].literal shouldBe "ftp://files.example.com/data"
        }

        test("unterminated URL throws LexerError") {
            shouldThrow<LexerException> { tokenize("<https://example.com") }
        }
    }

    // ====================================================================
    // Arithmetic Operators
    // ====================================================================

    context("Arithmetic operators") {
        test("plus") { tokens("+")[0].type shouldBe TokenType.PLUS }
        test("minus") { tokens("-")[0].type shouldBe TokenType.MINUS }
        test("star") { tokens("*")[0].type shouldBe TokenType.STAR }
        test("slash") { tokens("1 / 2")[1].type shouldBe TokenType.SLASH }
        test("percent") { tokens("%")[0].type shouldBe TokenType.PERCENT }
    }

    // ====================================================================
    // Increment / Decrement
    // ====================================================================

    context("Increment and decrement") {
        test("plus-plus") {
            val toks = tokens("c++")
            toks.map { it.type } shouldBe listOf(TokenType.IDENTIFIER, TokenType.PLUS_PLUS)
        }

        test("minus-minus") {
            val toks = tokens("c--")
            toks.map { it.type } shouldBe listOf(TokenType.IDENTIFIER, TokenType.MINUS_MINUS)
        }
    }

    // ====================================================================
    // Comparison Operators
    // ====================================================================

    context("Comparison operators") {
        test("less than") { tokens("a < b")[1].type shouldBe TokenType.LESS }
        test("greater than") { tokens("a > b")[1].type shouldBe TokenType.GREATER }
        test("less or equal") { tokens("a <= b")[1].type shouldBe TokenType.LESS_EQUAL }
        test("greater or equal") { tokens("a >= b")[1].type shouldBe TokenType.GREATER_EQUAL }
        test("equal-equal") { tokens("a == b")[1].type shouldBe TokenType.EQUAL_EQUAL }
        test("bang-equal") { tokens("a != b")[1].type shouldBe TokenType.BANG_EQUAL }
    }

    // ====================================================================
    // Logical Operators
    // ====================================================================

    context("Logical operators") {
        test("and") { tokens("a && b")[1].type shouldBe TokenType.AMP_AMP }
        test("or") { tokens("a || b")[1].type shouldBe TokenType.PIPE_PIPE }
        test("not") { tokens("!x")[0].type shouldBe TokenType.BANG }
        test("double-bang (non-null assertion)") { tokens("x!!")[1].type shouldBe TokenType.BANG_BANG }
    }

    // ====================================================================
    // Assignment Operators
    // ====================================================================

    context("Assignment operators") {
        test("simple assign") { tokens("x = 1")[1].type shouldBe TokenType.EQUAL }
        test("plus-assign") { tokens("x += 1")[1].type shouldBe TokenType.PLUS_EQUAL }
        test("minus-assign") { tokens("x -= 1")[1].type shouldBe TokenType.MINUS_EQUAL }
        test("star-assign") { tokens("x *= 2")[1].type shouldBe TokenType.STAR_EQUAL }
        test("slash-assign") { tokens("x /= 2")[1].type shouldBe TokenType.SLASH_EQUAL }
        test("percent-assign") { tokens("x %= 3")[1].type shouldBe TokenType.PERCENT_EQUAL }
    }

    // ====================================================================
    // Range Operators
    // ====================================================================

    context("Range operators") {
        test("inclusive range (..)") {
            val toks = tokens("1..10")
            toks[1].type shouldBe TokenType.DOT_DOT
        }

        test("exclusive right (..<)") {
            val toks = tokens("0..<100")
            toks[1].type shouldBe TokenType.DOT_DOT_LESS
        }

        test("exclusive left (<..)") {
            val toks = tokens("0<..100")
            toks[1].type shouldBe TokenType.LESS_DOT_DOT
        }

        test("exclusive both (<..<)") {
            val toks = tokens("0<..<100")
            toks[1].type shouldBe TokenType.LESS_DOT_DOT_LESS
        }
    }

    // ====================================================================
    // Null Safety Operators
    // ====================================================================

    context("Null safety operators") {
        test("safe call (?.)") {
            val toks = tokens("name?.length")
            toks.map { it.type } shouldBe listOf(
                TokenType.IDENTIFIER, TokenType.QUESTION_DOT, TokenType.IDENTIFIER
            )
        }

        test("elvis (?:)") {
            val toks = tokens("""name ?: "default"""")
            toks[1].type shouldBe TokenType.ELVIS
        }

        test("non-null assertion (!!)") {
            val toks = tokens("value!!")
            toks[1].type shouldBe TokenType.BANG_BANG
        }

        test("nullable type (?)") {
            val toks = tokens("String?")
            toks[1].type shouldBe TokenType.QUESTION
        }
    }

    // ====================================================================
    // Punctuation & Delimiters
    // ====================================================================

    context("Punctuation and delimiters") {
        test("dot") { tokens("a.b")[1].type shouldBe TokenType.DOT }
        test("comma") { tokens("a, b")[1].type shouldBe TokenType.COMMA }
        test("colon") { tokens("a: Int")[1].type shouldBe TokenType.COLON }
        test("semicolon") { tokens(";")[0].type shouldBe TokenType.SEMICOLON }
        test("arrow") { tokens("->")[0].type shouldBe TokenType.ARROW }
        test("at") { tokens("@ x")[0].type shouldBe TokenType.AT }
        test("double-colon (reflection)") { tokens("x::class")[1].type shouldBe TokenType.COLON_COLON }
        test("underscore (standalone)") { tokens("_")[0].type shouldBe TokenType.UNDERSCORE }

        test("matched parens") {
            tokens("()").map { it.type } shouldBe listOf(TokenType.LPAREN, TokenType.RPAREN)
        }

        test("matched brackets") {
            tokens("[]").map { it.type } shouldBe listOf(TokenType.LBRACKET, TokenType.RBRACKET)
        }

        test("matched braces") {
            tokens("{}").map { it.type } shouldBe listOf(TokenType.LBRACE, TokenType.RBRACE)
        }
    }

    // ====================================================================
    // Ternary
    // ====================================================================

    context("Ternary operator") {
        test("condition ? a : b") {
            val toks = tokens("""(age > 18) ? "adult" : "minor"""")
            toks.map { it.type } shouldBe listOf(
                TokenType.LPAREN, TokenType.IDENTIFIER, TokenType.GREATER, TokenType.INT_LITERAL,
                TokenType.RPAREN, TokenType.QUESTION, TokenType.STRING_LITERAL,
                TokenType.COLON, TokenType.STRING_LITERAL
            )
        }
    }

    // ====================================================================
    // Keywords
    // ====================================================================

    context("Keywords") {

        context("Declaration keywords") {
            test("var") { tokens("var")[0].type shouldBe TokenType.VAR }
            test("let") { tokens("let")[0].type shouldBe TokenType.LET }
            test("fun") { tokens("fun")[0].type shouldBe TokenType.FUN }
            test("class") { tokens("class")[0].type shouldBe TokenType.CLASS }
            test("trait") { tokens("trait")[0].type shouldBe TokenType.TRAIT }
            test("enum") { tokens("enum")[0].type shouldBe TokenType.ENUM }
            test("static") { tokens("static")[0].type shouldBe TokenType.STATIC }
            test("extend") { tokens("extend")[0].type shouldBe TokenType.EXTEND }
        }

        context("Control flow keywords") {
            test("if") { tokens("if")[0].type shouldBe TokenType.IF }
            test("else") { tokens("else")[0].type shouldBe TokenType.ELSE }
            test("for") { tokens("for")[0].type shouldBe TokenType.FOR }
            test("while") { tokens("while")[0].type shouldBe TokenType.WHILE }
            test("when") { tokens("when")[0].type shouldBe TokenType.WHEN }
            test("return") { tokens("return")[0].type shouldBe TokenType.RETURN }
            test("break") { tokens("break")[0].type shouldBe TokenType.BREAK }
            test("continue") { tokens("continue")[0].type shouldBe TokenType.CONTINUE }
        }

        context("Error handling keywords") {
            test("try") { tokens("try")[0].type shouldBe TokenType.TRY }
            test("catch") { tokens("catch")[0].type shouldBe TokenType.CATCH }
            test("finally") { tokens("finally")[0].type shouldBe TokenType.FINALLY }
            test("throw") { tokens("throw")[0].type shouldBe TokenType.THROW }
        }

        context("Type check keywords") {
            test("in") { tokens("in")[0].type shouldBe TokenType.IN }
            test("is") { tokens("is")[0].type shouldBe TokenType.IS }
            test("matches") { tokens("matches")[0].type shouldBe TokenType.MATCHES }
            test("as") { tokens("as")[0].type shouldBe TokenType.AS }
        }

        context("Other keywords") {
            test("use") { tokens("use")[0].type shouldBe TokenType.USE }
            test("say") { tokens("say")[0].type shouldBe TokenType.SAY }
            test("lang") { tokens("lang")[0].type shouldBe TokenType.LANG }
        }

        test("identifier that starts with a keyword is not a keyword") {
            tokens("variable")[0].type shouldBe TokenType.IDENTIFIER
            tokens("letter")[0].type shouldBe TokenType.IDENTIFIER
            tokens("format")[0].type shouldBe TokenType.IDENTIFIER
            tokens("classify")[0].type shouldBe TokenType.IDENTIFIER
            tokens("iffy")[0].type shouldBe TokenType.IDENTIFIER
        }
    }

    // ====================================================================
    // !in and !is
    // ====================================================================

    context("Compound negation operators") {
        test("!in is a single token") {
            val toks = tokens("5 !in list")
            toks[1].type shouldBe TokenType.NOT_IN
            toks[1].value shouldBe "!in"
        }

        test("!is is a single token") {
            val toks = tokens("x !is String")
            toks[1].type shouldBe TokenType.NOT_IS
            toks[1].value shouldBe "!is"
        }

        test("!identifier is BANG + IDENTIFIER (not !in or !is)") {
            val toks = tokens("!done")
            toks[0].type shouldBe TokenType.BANG
            toks[1].type shouldBe TokenType.IDENTIFIER
        }
    }

    // ====================================================================
    // Comments
    // ====================================================================

    context("Comments") {
        test("// line comment is skipped") {
            val toks = tokens("// this is a comment")
            toks shouldHaveSize 0
        }

        test("# line comment is skipped") {
            val toks = tokens("# hash comment")
            toks shouldHaveSize 0
        }

        test("inline comment preserves surrounding tokens") {
            val toks = tokens("let x = 42 /* inline */ + 1")
            toks.map { it.type } shouldBe listOf(
                TokenType.LET, TokenType.IDENTIFIER, TokenType.EQUAL,
                TokenType.INT_LITERAL, TokenType.PLUS, TokenType.INT_LITERAL
            )
        }

        test("nested block comments") {
            val toks = tokens("a /* outer /* inner */ still comment */ b")
            toks shouldHaveSize 2
            toks[0].value shouldBe "a"
            toks[1].value shouldBe "b"
        }

        test("unterminated block comment throws LexerError") {
            shouldThrow<LexerException> { tokenize("/* never closed") }
        }
    }

    // ====================================================================
    // Newline Significance
    // ====================================================================

    context("Newline significance") {
        test("newline after statement-ending token emits NEWLINE") {
            val all = types("let a = 1\nlet b = 2")
            all shouldBe listOf(
                TokenType.LET, TokenType.IDENTIFIER, TokenType.EQUAL, TokenType.INT_LITERAL,
                TokenType.NEWLINE,
                TokenType.LET, TokenType.IDENTIFIER, TokenType.EQUAL, TokenType.INT_LITERAL
            )
        }

        test("newline after open brace does NOT emit NEWLINE") {
            val all = types("if x {\nfoo\n}")
            all shouldBe listOf(
                TokenType.IF, TokenType.IDENTIFIER, TokenType.LBRACE,
                TokenType.IDENTIFIER, TokenType.NEWLINE,
                TokenType.RBRACE
            )
        }

        test("newline after operator does NOT emit NEWLINE (line continuation)") {
            val all = types("a +\nb")
            all shouldBe listOf(
                TokenType.IDENTIFIER, TokenType.PLUS, TokenType.IDENTIFIER
            )
        }

        test("newline after comma does NOT emit NEWLINE") {
            val all = types("a,\nb")
            all shouldBe listOf(
                TokenType.IDENTIFIER, TokenType.COMMA, TokenType.IDENTIFIER
            )
        }

        test("newline after arrow does NOT emit NEWLINE") {
            val all = types("x ->\ny")
            all shouldBe listOf(
                TokenType.IDENTIFIER, TokenType.ARROW, TokenType.IDENTIFIER
            )
        }

        test("blank lines do not produce multiple NEWLINEs") {
            val all = types("a\n\n\nb")
            // First newline after 'a' emits NEWLINE; subsequent newlines don't
            // because last token is already NEWLINE
            all shouldBe listOf(
                TokenType.IDENTIFIER, TokenType.NEWLINE, TokenType.IDENTIFIER
            )
        }

        test("no newline at start of file") {
            val all = types("\n\na")
            all shouldBe listOf(TokenType.IDENTIFIER)
        }
    }

    // ====================================================================
    // Control Flow Constructs
    // ====================================================================

    context("Control flow constructs") {
        test("if / else") {
            val toks = tokens("""
                if age >= 18 {
                    say "Adult"
                } else {
                    say "Minor"
                }
            """.trimIndent())
            toks.map { it.type } shouldBe listOf(
                TokenType.IF, TokenType.IDENTIFIER, TokenType.GREATER_EQUAL, TokenType.INT_LITERAL,
                TokenType.LBRACE,
                TokenType.SAY, TokenType.STRING_LITERAL,
                TokenType.RBRACE, TokenType.ELSE, TokenType.LBRACE,
                TokenType.SAY, TokenType.STRING_LITERAL,
                TokenType.RBRACE
            )
        }

        test("for / in") {
            val toks = tokens("for n in nums { say n }")
            toks.map { it.type } shouldBe listOf(
                TokenType.FOR, TokenType.IDENTIFIER, TokenType.IN, TokenType.IDENTIFIER,
                TokenType.LBRACE, TokenType.SAY, TokenType.IDENTIFIER, TokenType.RBRACE
            )
        }

        test("while") {
            val toks = tokens("while x > 0 { x-- }")
            toks.map { it.type } shouldBe listOf(
                TokenType.WHILE, TokenType.IDENTIFIER, TokenType.GREATER, TokenType.INT_LITERAL,
                TokenType.LBRACE, TokenType.IDENTIFIER, TokenType.MINUS_MINUS, TokenType.RBRACE
            )
        }

        test("when with arrow branches") {
            val toks = tokens("""
                when {
                    score >= 90 -> say "A"
                    else -> say "F"
                }
            """.trimIndent())
            val filtered = toks.map { it.type }
            filtered shouldBe listOf(
                TokenType.WHEN, TokenType.LBRACE,
                TokenType.IDENTIFIER, TokenType.GREATER_EQUAL, TokenType.INT_LITERAL,
                TokenType.ARROW, TokenType.SAY, TokenType.STRING_LITERAL,
                TokenType.ELSE, TokenType.ARROW, TokenType.SAY, TokenType.STRING_LITERAL,
                TokenType.RBRACE
            )
        }
    }

    // ====================================================================
    // Functions
    // ====================================================================

    context("Functions") {
        test("function with typed params and return type") {
            val toks = tokens("fun add(a: Int, b: Int): Int { return a + b }")
            toks[0].type shouldBe TokenType.FUN
            toks[1].value shouldBe "add"
            toks[2].type shouldBe TokenType.LPAREN
            // Contains COLON, COMMA, RPAREN, COLON, LBRACE, RETURN, PLUS, RBRACE
            toks.map { it.type }.contains(TokenType.RETURN) shouldBe true
        }

        test("single-expression function") {
            val toks = tokens("""fun greet(name: String) = "Hello"""")
            toks.map { it.type } shouldBe listOf(
                TokenType.FUN, TokenType.IDENTIFIER, TokenType.LPAREN,
                TokenType.IDENTIFIER, TokenType.COLON, TokenType.IDENTIFIER,
                TokenType.RPAREN, TokenType.EQUAL, TokenType.STRING_LITERAL
            )
        }
    }

    // ====================================================================
    // Try / Catch / Finally
    // ====================================================================

    context("Error handling") {
        test("try / catch(*) / finally") {
            val toks = tokens("""
                try {
                    let r = 10 / 0
                } catch(*) {
                    say.error "oops"
                } finally {
                    say "done"
                }
            """.trimIndent())
            val typeList = toks.map { it.type }
            typeList.contains(TokenType.TRY) shouldBe true
            typeList.contains(TokenType.CATCH) shouldBe true
            typeList.contains(TokenType.FINALLY) shouldBe true
            // catch(*) should produce CATCH LPAREN STAR RPAREN
            val catchIdx = typeList.indexOf(TokenType.CATCH)
            typeList[catchIdx + 1] shouldBe TokenType.LPAREN
            typeList[catchIdx + 2] shouldBe TokenType.STAR
            typeList[catchIdx + 3] shouldBe TokenType.RPAREN
        }
    }

    // ====================================================================
    // Imports (use)
    // ====================================================================

    context("Imports") {
        test("use with dotted path") {
            val toks = tokens("use io.kixi.kd.Tag")
            toks.map { it.type } shouldBe listOf(
                TokenType.USE, TokenType.IDENTIFIER, TokenType.DOT,
                TokenType.IDENTIFIER, TokenType.DOT,
                TokenType.IDENTIFIER, TokenType.DOT,
                TokenType.IDENTIFIER
            )
        }

        test("use with wildcard star") {
            val toks = tokens("use io.kixi.kd.*")
            toks.last().type shouldBe TokenType.STAR
        }

        test("use with as alias") {
            val toks = tokens("use collections.OrderedMap as OMap")
            toks.map { it.type } shouldBe listOf(
                TokenType.USE, TokenType.IDENTIFIER, TokenType.DOT,
                TokenType.IDENTIFIER, TokenType.AS, TokenType.IDENTIFIER
            )
        }
    }

    // ====================================================================
    // Classes and Traits
    // ====================================================================

    context("Classes and traits") {
        test("class with primary constructor") {
            val toks = tokens("class Point(x: Double, y: Double)")
            toks[0].type shouldBe TokenType.CLASS
            toks[1].value shouldBe "Point"
            toks[2].type shouldBe TokenType.LPAREN
        }

        test("trait declaration") {
            val toks = tokens("trait Shape { fun area(): Double }")
            toks[0].type shouldBe TokenType.TRAIT
            toks[1].value shouldBe "Shape"
        }

        test("extend trait") {
            val toks = tokens("extend trait Comparable")
            toks.map { it.type } shouldBe listOf(
                TokenType.EXTEND, TokenType.TRAIT, TokenType.IDENTIFIER
            )
        }

        test("static block") {
            val toks = tokens("static { let PI = 3.14 }")
            toks[0].type shouldBe TokenType.STATIC
        }

        test("enum keyword") {
            val toks = tokens("enum Color { }")
            toks[0].type shouldBe TokenType.ENUM
            toks[1].value shouldBe "Color"
        }
    }

    // ====================================================================
    // Annotations
    // ====================================================================

    context("Annotations") {
        test("annotation before function") {
            val toks = tokens("@Deprecated\nfun old() { }")
            toks[0].type shouldBe TokenType.AT
            toks[1].type shouldBe TokenType.IDENTIFIER
            toks[1].value shouldBe "Deprecated"
        }

        test("@ followed by verbatim string is verbatim string, not annotation") {
            val toks = tokens("""@"raw string"""")
            toks shouldHaveSize 1
            toks[0].type shouldBe TokenType.VERBATIM_STRING
        }
    }

    // ====================================================================
    // Lang Block
    // ====================================================================

    context("Lang block") {
        test("lang KD { ... }") {
            val toks = tokens("""
                var root = lang KD {
                    book "The Hobbit"
                }
            """.trimIndent())
            val typeList = toks.map { it.type }
            typeList.contains(TokenType.LANG) shouldBe true
            val langIdx = typeList.indexOf(TokenType.LANG)
            toks[langIdx + 1].value shouldBe "KD"
            typeList[langIdx + 2] shouldBe TokenType.LBRACE
        }
    }

    // ====================================================================
    // Lists (comma optional)
    // ====================================================================

    context("Lists") {
        test("list with commas") {
            val toks = tokens("[1, 2, 3]")
            toks.map { it.type } shouldBe listOf(
                TokenType.LBRACKET,
                TokenType.INT_LITERAL, TokenType.COMMA,
                TokenType.INT_LITERAL, TokenType.COMMA,
                TokenType.INT_LITERAL,
                TokenType.RBRACKET
            )
        }

        test("list without commas (comma-optional)") {
            val toks = tokens("[4 5 6]")
            toks.map { it.type } shouldBe listOf(
                TokenType.LBRACKET,
                TokenType.INT_LITERAL, TokenType.INT_LITERAL, TokenType.INT_LITERAL,
                TokenType.RBRACKET
            )
        }
    }

    // ====================================================================
    // Say Variants
    // ====================================================================

    context("Say") {
        test("simple say") {
            val toks = tokens("""say "hello"""")
            toks[0].type shouldBe TokenType.SAY
        }

        test("say.error tokenizes as SAY DOT IDENTIFIER") {
            val toks = tokens("""say.error "oops"""")
            toks.map { it.type } shouldBe listOf(
                TokenType.SAY, TokenType.DOT, TokenType.IDENTIFIER, TokenType.STRING_LITERAL
            )
            toks[2].value shouldBe "error"
        }

        test("say.warn tokenizes as SAY DOT IDENTIFIER") {
            val toks = tokens("""say.warn "caution"""")
            toks[2].value shouldBe "warn"
        }

        test("say.note tokenizes as SAY DOT IDENTIFIER") {
            val toks = tokens("""say.note "note"""")
            toks[2].value shouldBe "note"
        }
    }

    // ====================================================================
    // Reflection
    // ====================================================================

    context("Reflection") {
        test("::class") {
            val toks = tokens("x::class")
            toks.map { it.type } shouldBe listOf(
                TokenType.IDENTIFIER, TokenType.COLON_COLON, TokenType.CLASS
            )
            toks[2].value shouldBe "class"
        }
    }

    // ====================================================================
    // Underscore
    // ====================================================================

    context("Underscore") {
        test("standalone underscore is UNDERSCORE token") {
            tokens("_")[0].type shouldBe TokenType.UNDERSCORE
        }

        test("underscore followed by alpha is an IDENTIFIER") {
            tokens("_name")[0].type shouldBe TokenType.IDENTIFIER
            tokens("_name")[0].value shouldBe "_name"
        }

        test("underscore followed by digit is an IDENTIFIER") {
            tokens("_1")[0].type shouldBe TokenType.IDENTIFIER
        }
    }

    // ====================================================================
    // Source Locations
    // ====================================================================

    context("Source locations") {
        test("first token starts at line 1, column 1") {
            val tok = tokenize("hello")[0]
            tok.location.line shouldBe 1
            tok.location.column shouldBe 1
            tok.location.offset shouldBe 0
        }

        test("second line token has correct line number") {
            val toks = tokenize("a\nb")
            val bToken = toks.first { it.value == "b" }
            bToken.location.line shouldBe 2
            bToken.location.column shouldBe 1
        }

        test("column tracks correctly within a line") {
            val toks = tokenize("let x = 42")
            val xToken = toks.first { it.value == "x" }
            xToken.location.column shouldBe 5 // "let " is 4 chars
        }

        test("offset tracks absolute position") {
            val toks = tokenize("ab\ncd")
            val cdToken = toks.first { it.value == "cd" }
            cdToken.location.offset shouldBe 3 // "ab\n" = 3 chars
        }
    }

    // ====================================================================
    // EOF
    // ====================================================================

    context("EOF") {
        test("empty source produces only EOF") {
            val toks = tokenize("")
            toks shouldHaveSize 1
            toks[0].type shouldBe TokenType.EOF
        }

        test("every tokenization ends with EOF") {
            tokenize("hello").last().type shouldBe TokenType.EOF
            tokenize("42").last().type shouldBe TokenType.EOF
            tokenize("").last().type shouldBe TokenType.EOF
        }
    }

    // ====================================================================
    // Error Cases
    // ====================================================================

    context("Error cases") {
        test("unexpected character throws LexerError") {
            shouldThrow<LexerException> { tokenize("§") }
        }

        test("lone ampersand throws LexerError") {
            shouldThrow<LexerException> { tokenize("&") }
        }

        test("lone pipe throws LexerError") {
            shouldThrow<LexerException> { tokenize("|") }
        }

        test("invalid hex literal throws LexerError") {
            shouldThrow<LexerException> { tokenize("0xZZ") }
        }

        test("invalid binary literal throws LexerError") {
            shouldThrow<LexerException> { tokenize("0b22") }
        }

        test("unknown escape sequence throws LexerError") {
            shouldThrow<LexerException> { tokenize(""""bad \q escape"""") }
        }

        test("LexerError includes location info") {
            val err = shouldThrow<LexerException> { tokenize("let x = §") }
            err.location shouldBe SourceLocation(line = 1, column = 10, offset = 9)
        }
    }

    // ====================================================================
    // Dot Ambiguity: member access vs. range vs. decimal
    // ====================================================================

    context("Dot disambiguation") {
        test("integer followed by .. is range, not decimal") {
            val toks = tokens("1..10")
            toks.map { it.type } shouldBe listOf(
                TokenType.INT_LITERAL, TokenType.DOT_DOT, TokenType.INT_LITERAL
            )
        }

        test("integer followed by .< starts exclusive range") {
            val toks = tokens("0..<5")
            toks.map { it.type } shouldBe listOf(
                TokenType.INT_LITERAL, TokenType.DOT_DOT_LESS, TokenType.INT_LITERAL
            )
        }

        test("float followed by member access") {
            val toks = tokens("3.14.toString")
            // 3.14 is double, then .toString is member access
            toks[0].type shouldBe TokenType.DOUBLE_LITERAL
            toks[1].type shouldBe TokenType.DOT
            toks[2].value shouldBe "toString"
        }
    }

    // ====================================================================
    // Integration: Realistic KS Snippets
    // ====================================================================

    context("Integration: realistic KS code") {
        test("fibonacci function") {
            val toks = tokens("""
                fun fib(n: Int): Int {
                    if n <= 1 return n
                    return fib(n - 1) + fib(n - 2)
                }
            """.trimIndent())
            // Should parse without error and contain expected keywords
            val typeList = toks.map { it.type }
            typeList.contains(TokenType.FUN) shouldBe true
            typeList.contains(TokenType.IF) shouldBe true
            typeList.contains(TokenType.RETURN) shouldBe true
            typeList.contains(TokenType.MINUS) shouldBe true
            typeList.contains(TokenType.PLUS) shouldBe true
        }

        test("class with trait and constructor") {
            val src = """
                class Dog(name: String, breed: String) : Animal {
                    fun speak(): String = "Woof!"
                    static {
                        let count = 0
                    }
                }
            """.trimIndent()
            val toks = tokens(src)
            val typeList = toks.map { it.type }
            typeList.contains(TokenType.CLASS) shouldBe true
            typeList.contains(TokenType.STATIC) shouldBe true
            typeList.contains(TokenType.FUN) shouldBe true
        }

        test("when expression with various matchers") {
            val src = """
                when score {
                    in 90..100 -> say "A"
                    in 80..<90 -> say "B"
                    else -> say "F"
                }
            """.trimIndent()
            val toks = tokens(src)
            val typeList = toks.map { it.type }
            typeList.contains(TokenType.WHEN) shouldBe true
            typeList.contains(TokenType.IN) shouldBe true
            typeList.contains(TokenType.DOT_DOT) shouldBe true
            typeList.contains(TokenType.DOT_DOT_LESS) shouldBe true
            typeList.contains(TokenType.ARROW) shouldBe true
        }

        test("nullable type with safe navigation chain") {
            val src = """let len = user?.address?.city?.length ?: 0"""
            val toks = tokens(src)
            val typeList = toks.map { it.type }
            typeList.count { it == TokenType.QUESTION_DOT } shouldBe 3
            typeList.contains(TokenType.ELVIS) shouldBe true
        }
    }
})