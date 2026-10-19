package io.kixi.ks.interp

import io.kixi.ks.KSRuntime
import io.kixi.ks.RuntimeError
import io.kixi.ks.lexer.LangBody
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.lexer.LexerException
import io.kixi.ks.lexer.TokenType
import io.kixi.ks.parser.ExprStmt
import io.kixi.ks.parser.LangBlockExpr
import io.kixi.ks.parser.Parser
import io.kixi.ks.parser.VarDecl
import io.kixi.lang.EmbeddedBlock
import io.kixi.lang.EmbeddedInterpolation
import io.kixi.lang.EmbeddedLanguage
import io.kixi.lang.EmbeddedSource
import io.kixi.lang.EmbeddedLanguageException
import io.kixi.lang.EmbeddedLanguages
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.PrintWriter
import java.io.StringWriter

/** What [Probe] received: the block's text, where it is, and its interpolation values. */
private data class Received(val text: String, val values: Map<String, Any?>, val line: Int = 0, val column: Int = 0)

/** A test language that returns what it was given, or fails at an offset given in the text as `!fail@N`. */
private object Probe : EmbeddedLanguage {
    override val name = "Probe"
    override fun evaluate(source: EmbeddedSource, values: Map<String, Any?>): Any? {
        Regex("!fail@(\\d+)").find(source.text)?.let { throw EmbeddedLanguageException("bad thing", it.groupValues[1].toInt()) }
        return Received(source.text, values, source.line, source.column)
    }
}

/**
 * A language with its own scan: `#` starts a line comment, so a brace or `$` after
 * it is text, and `@name` is its interpolation (key: the name).
 */
private object Hash : EmbeddedLanguage {
    override val name = "Hash"
    override fun scan(source: String, start: Int): EmbeddedBlock {
        val found = ArrayList<EmbeddedInterpolation>()
        var i = start
        while (i < source.length) {
            when (source[i]) {
                '}' -> return EmbeddedBlock(i, found)
                '#' -> while (i < source.length && source[i] != '\n') i++
                '@' -> {
                    var e = i + 1
                    while (e < source.length && source[e].isLetter()) e++
                    val n = source.substring(i + 1, e)
                    found.add(EmbeddedInterpolation(i, e, n, n, i + 1)); i = e
                }
                else -> i++
            }
        }
        throw EmbeddedLanguageException("no closing brace", start - 1)
    }
    override fun evaluate(source: EmbeddedSource, values: Map<String, Any?>): Any? = Received(source.text, values)
}

/**
 * `lang Name { ... }` for an embedded language other than KD: the lexer keeps the
 * block's text unchanged, the parser finds the KS interpolations, and the
 * interpreter passes text and values to the io.kixi.lang.EmbeddedLanguage of
 * that name (Ki.Core).
 */
class EmbeddedLanguageTest : FunSpec({

    beforeSpec { EmbeddedLanguages.register(Probe); EmbeddedLanguages.register(Hash) }
    afterSpec { EmbeddedLanguages.unregister("Probe"); EmbeddedLanguages.unregister("Hash") }

    fun eval(source: String): Any? {
        val runtime = KSRuntime(
            hostLang = false, colorOutput = false,
            outputWriter = PrintWriter(StringWriter(), true), errorWriter = PrintWriter(StringWriter(), true), debugMode = false,
        )
        return Interpreter(runtime).executeProgram(Parser(Lexer(source).tokenize()).parse())
    }

    fun block(source: String): LangBlockExpr {
        val stmt = Parser(Lexer(source).tokenize()).parse().body.first()
        return when (stmt) {
            is ExprStmt -> stmt.expression
            is VarDecl -> stmt.initializer!!
            else -> error("unexpected $stmt")
        } as LangBlockExpr
    }

    context("lexer") {
        test("the text between the braces is one token, unchanged, and lexing continues after the block") {
            val src = "let q = lang Probe {\n  from c in compounds // }\n  where c.name == \"}\" and c.x == '}'\n}\nsay q"
            val tokens = Lexer(src).tokenize()
            val body = tokens.single { it.type == TokenType.LANG_BODY }
            (body.literal as LangBody).text shouldBe "\n  from c in compounds // }\n  where c.name == \"}\" and c.x == '}'\n"
            body.location.line shouldBe 1
            body.location.column shouldBe 21
            tokens.map { it.type }.takeLast(4) shouldBe listOf(TokenType.NEWLINE, TokenType.SAY, TokenType.IDENTIFIER, TokenType.EOF)
        }

        test("nested braces, block comments, triple-quoted and verbatim strings, and an unpaired apostrophe") {
            val text = " a { b ${'$'}{ [1] } } /* } */ \"\"\"\n}\n\"\"\" @\"\\}\" don't "
            val body = Lexer("lang Probe {$text}").tokenize().single { it.type == TokenType.LANG_BODY }
            (body.literal as LangBody).text shouldBe text
        }

        test("lang KD is unchanged, and an unterminated block is a lexer error") {
            Lexer("lang KD { run x=1 }").tokenize().none { it.type == TokenType.LANG_BODY } shouldBe true
            val e = shouldThrow<LexerException> { Lexer("let a = 1\nlang Probe { from c in c").tokenize() }
            e.message shouldContain "Probe: Unterminated block"
            e.location!!.line shouldBe 2
            e.location!!.column shouldBe 12
        }
    }

    context("the language scans its own block") {
        test("the lexer uses the language's scan: its comments, its interpolations") {
            val r = eval("let who = \"Kai\"\nlang Hash {\n  hello @who # a } and \$x here are text\n}")
            r.shouldBeInstanceOf<Received>()
            r.text shouldBe "\n  hello @who # a } and \$x here are text\n"
            r.values shouldBe mapOf("who" to "Kai")
        }

        test("the language gets where its text starts in the script") {
            val r = eval("let a = 1\nlet q = lang Probe { x }\nq")
            r.shouldBeInstanceOf<Received>()
            (r.line to r.column) shouldBe (2 to 21)
        }

        test("an unreadable interpolation is reported at its place in the script") {
            val e = shouldThrow<io.kixi.ks.parser.ParseException> { block("let q = lang Probe {\n  where x < \${ 1 + }\n}") }
            e.location!!.line shouldBe 2
        }
    }

    context("interpolations") {
        test("\$name, \${expr} and \$(expr), with their keys; \$ before a digit, in strings and in comments is the language's") {
            val b = block("lang Probe { a \$x and \${ y + 1 } \$(z) \$p.mw \$5 \"\$s\" '\$t' // \$u\n /* \$v */ \$w }")
            b.interpolations.map { it.key } shouldBe listOf("x", "y + 1", "z", "p", "w")
            b.text!!.text shouldBe " a \$x and \${ y + 1 } \$(z) \$p.mw \$5 \"\$s\" '\$t' // \$u\n /* \$v */ \$w "
        }

        test("an interpolation's KS expression has locations in the KS source") {
            val b = block("let q = lang Probe {\n  where x < \${ limit }\n}")
            b.interpolations.single().location.line shouldBe 2
            b.interpolations.single().location.column shouldBe 13
            b.interpolations.single().expr.location.line shouldBe 2
            b.interpolations.single().expr.location.column shouldBe 16
        }
    }

    context("evaluation") {
        test("the language gets the text unchanged and the values of the interpolations, evaluated in KS") {
            val r = eval("let maxMw = 500Da\nlet n = 3\nlang Probe { where c.mw < \$maxMw limit \${n * 2} }")
            r.shouldBeInstanceOf<Received>()
            r.text shouldBe " where c.mw < \$maxMw limit \${n * 2} "
            r.values["maxMw"].toString() shouldBe "500Da"
            r.values["n * 2"] shouldBe 6
        }

        test("the language is found without regard to case") {
            eval("lang probe { x }").shouldBeInstanceOf<Received>()
        }

        test("an EmbeddedLanguageException is reported at its line and column in the KS source") {
            val e = shouldThrow<RuntimeError> { eval("let a = 1\nlet q = lang Probe {\n  ok\n  !fail@8\n}") }
            e.message shouldContain "[4:3]"
            e.message shouldContain "Probe: bad thing"
        }

        test("an unknown language names the ones available") {
            val e = shouldThrow<RuntimeError> { eval("lang Nope { x }") }
            e.message shouldContain "No embedded language 'Nope' is available"
            e.message shouldContain "PROBE"
        }
    }
})
