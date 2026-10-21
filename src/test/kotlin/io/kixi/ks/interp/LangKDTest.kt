package io.kixi.ks.interp

import io.kixi.kd.Tag
import io.kixi.ks.KSRuntime
import io.kixi.ks.RuntimeError
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kixi.ks.tb.Seq
import io.kixi.ks.tb.withSampleBags
import io.kixi.uom.Quantity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.PrintWriter
import java.io.StringWriter

/**
 * `lang KD { ... }` is read by Ki.KD's KDLanguage exactly as a KD file is, with
 * KS's values put in where `$x`, `${expr}` and `$(expr)` stand.
 */
class LangKDTest : FunSpec({

    fun interpreter(out: StringWriter = StringWriter()) = Interpreter(KSRuntime(
        hostLang = false, colorOutput = false,
        outputWriter = PrintWriter(out, true), errorWriter = PrintWriter(StringWriter(), true),
    ))

    /** Runs [source] and returns the value of variable `kd`. */
    fun kd(source: String): Any? {
        val i = interpreter()
        i.executeProgram(Parser(Lexer(source).tokenize()).parse())
        return i.environment.get("kd", null)
    }

    fun tag(source: String): Tag = kd(source) as Tag

    test("a KD block reads as KD: bare map keys and bare words are strings") {
        val t = tag("var kd = lang KD {\n  map1 [fee = 23, fi = 26, foe = 12, fum = 16]\n}")
        t.name shouldBe "map1"
        t.values.single() shouldBe mapOf("fee" to 23, "fi" to 26, "foe" to 12, "fum" to 16)
        tag("let kd = lang KD { t status=active colors=[purple orange] }").let {
            it["status"] shouldBe "active"
            it["colors"] shouldBe listOf("purple", "orange")
        }
    }

    test("values, attributes, namespaces, children, annotations and several tags") {
        tag("let kd = lang KD {\n    book \"The Hobbit\" author=\"Tolkien\" year=1937\n}").let {
            it.values shouldBe listOf("The Hobbit"); it["author"] shouldBe "Tolkien"; it["year"] shouldBe 1937
        }
        tag("let kd = lang KD { config:db host=\"localhost\" port=5432 }").let { it.namespace shouldBe "config"; it.name shouldBe "db" }
        tag("let kd = lang KD {\n    library {\n        book \"Book 1\"\n        book \"Book 2\"\n    }\n}").children.size shouldBe 2
        tag("let kd = lang KD {\n    @Test(true enabled=false)\n    testCase \"example\"\n}").annotations.single().let {
            it.name shouldBe "Test"; it.values shouldBe listOf(true); it.getAttribute<Any?>("enabled") shouldBe false
        }
        tag("let kd = lang KD {\n    tag1 \"value1\"\n    tag2 \"value2\"\n}").let { it.name shouldBe "root"; it.children.size shouldBe 2 }
        // KD's own comments and literals, as in a KD file.
        tag("let kd = lang KD {\n    # a comment\n    app \"MyApp\" version=\"2.0.1\" // another\n    temp 18°C..20°C date 2026/7/15\n}").children.size shouldBe 2
    }

    test("interpolations bring KS values in as they are") {
        val t = tag("""
            let plateId = "PL-0001"
            let signal = [1.5, 2.5]
            let baseline = 0.5
            let items = [1, 2]
            let kd = lang KD {
                run plate=${'$'}plateId items=${'$'}items {
                    read ${'$'}{signal[0] - baseline} well="A1"
                    read ${'$'}(signal[1] - baseline) well="A2"
                    timeout ${'$'}(30 * 60)
                }
            }
        """.trimIndent())
        t["plate"] shouldBe "PL-0001"
        t["items"] shouldBe listOf(1, 2)
        t.children.map { it.values.single() } shouldBe listOf(1.0, 2.0, 1800)
    }

    test("inside strings an interpolation puts its text in, as KS prints it; raw strings stay as written") {
        val t = tag("""
            let last = "Smith"
            let mw = 180.16Da
            let kd = lang KD { t a="Dr. ${'$'}last" b="mass ${'$'}mw" c="\${'$'}5 is ${'$'}5" d=@"${'$'}last" }
        """.trimIndent())
        t["a"] shouldBe "Dr. Smith"
        t["b"] shouldBe "mass 180.16Da"
        t["c"] shouldBe "${'$'}5 is ${'$'}5"
        t["d"] shouldBe "${'$'}last"
    }

    test("a currency amount is KD's, not an interpolation") {
        tag("let kd = lang KD { t price=${'$'}5.25 }")["price"].shouldBeInstanceOf<Quantity<*>>()
    }

    test("type bag literals are KD's, wherever they stand") {
        withSampleBags {
            tag("let kd = lang KD { gene .seq(\"ACGT\") probe=.sq(\"GA\") }").let {
                it.values shouldBe listOf(Seq("ACGT")); it["probe"] shouldBe Seq("GA")
            }
        }
    }

    test("a KD error is reported at its line in the script") {
        val e = shouldThrow<RuntimeError> { kd("let a = 1\nlet kd = lang KD {\n  t a=\n}") }
        e.message shouldContain "KD: "
        e.location!!.line shouldBe 3
    }

    test("an undefined variable in an interpolation is a KS error") {
        shouldThrow<RuntimeError> { kd("let kd = lang KD { t a=${'$'}nope }") }.message shouldContain "nope"
    }

    test("a KD error on a later line of the block has that line and column") {
        val e = shouldThrow<RuntimeError> { kd("let x = 1\nlet kd = lang KD {\n  a 1\n  b \"x\\b\"\n}") }
        e.message shouldContain "Invalid escape"
        e.location!!.line shouldBe 4
        e.location!!.column shouldBe 5
    }

    test("the language name is matched without case, and braces inside an interpolation's strings are its own") {
        tag("let m = [\"}\"=1]\nlet kd = lang kd { t a=${'$'}{m[\"}\"]} }")["a"] shouldBe 1
    }

    test("ranges, naked URLs and words with a dollar read as KD") {
        tag("let lo = 1\nlet hi = 5\nlet kd = lang KD { t r=${'$'}lo..${'$'}hi u=http://kixi.io/a#b w=on${'$'}x }").let {
            it["r"].toString() shouldBe "1..5"
            it["u"].toString() shouldBe "http://kixi.io/a#b"
            it["w"] shouldBe "on${'$'}x"
        }
    }

    test("outside strings and lang blocks a dollar is an error that says why") {
        shouldThrow<Exception> { kd("let b = 1\nlet kd = ${'$'}b") }.message shouldContain "interpolates only inside a string or a lang block"
    }
})
