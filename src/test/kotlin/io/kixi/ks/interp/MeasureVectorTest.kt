package io.kixi.ks.interp

import io.kixi.Measure
import io.kixi.Vector
import io.kixi.ks.*
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.PrintWriter
import java.io.StringWriter
import java.math.BigDecimal as Dec

/**
 * Measures (`0.512±0.008`, `mean ± sd`) and vectors (`.vec<Float> { 1.0 2.5 }`) in KS
 * (type bags design, TB7). `±` is an operator binding tighter than `*` and `/`, so a
 * measure written as a literal reads as one value; `+-` stays `+ -`.
 */
class MeasureVectorTest : FunSpec({

    fun eval(source: String): Any? {
        val runtime = KSRuntime(
            hostLang = false, colorOutput = false,
            outputWriter = PrintWriter(StringWriter(), true), errorWriter = PrintWriter(StringWriter(), true), debugMode = false
        )
        return Interpreter(runtime).executeProgram(Parser(Lexer(source).tokenize()).parse())
    }

    fun error(source: String): String = (shouldThrowAny { eval(source) }).message ?: ""

    context("measures") {
        test("a literal reads as Ki.Core reads it") {
            for (text in listOf("0.0042±0.0006µM", "0.512±0.008", "120±11", "5±1cm:i", "5.0±0.1cm:d", "1.5bd±0.1bd",
                "2L±1L", "0.5f±0.1f", "20±0.5°C", "5cm±1mm")) {
                eval(text) shouldBe Measure.parse(text)
            }
            eval("-3.25±0.5") shouldBe Measure.parse("-3.25±0.5")
        }

        test("± takes any number or quantity expressions") {
            eval("let mean = 0.512\nlet sd = 0.008\nmean ± sd") shouldBe Measure.parse("0.512±0.008")
            eval("let c = 10µM\nc ± 1µM") shouldBe Measure.parse("10±1µM")
            eval("2 * 5±1") shouldBe Measure.parse("10.0±2.0")
            eval("let sd = 2.0\n10.0 ± (sd / 2)") shouldBe Measure.parse("10.0±1.0")
        }

        test("+- is plus and minus, not a measure") {
            eval("5+-3") shouldBe 2
        }

        test("arithmetic propagates the uncertainty") {
            eval("(10±3) + (20±4)") shouldBe Measure.parse("30.0±5.0")
            eval("(10±3) - 5") shouldBe Measure.parse("5.0±3.0")
            eval("(4±0.3) * 2") shouldBe Measure.parse("8.0±0.6")
            eval("(8±0.6) / 2") shouldBe Measure.parse("4.0±0.3")
            eval("-(10±3)") shouldBe Measure.parse("-10±3")
            eval("(5±0.1cm:d) + (2±0.2mm:d)").toString() shouldContain "mm"
        }

        test("members, comparison and type checks") {
            eval("(0.512±0.008).value") shouldBe 0.512
            eval("(0.512±0.008).uncertainty") shouldBe 0.008
            eval("(10±0.5).relative") shouldBe 0.05
            eval("(1±0.1cm) == (10±1mm)") shouldBe true
            eval("(5±1) < (5±2)") shouldBe true
            eval("(5±1) is Measure") shouldBe true
            eval("let m = 5±1\n\"\$m\"") shouldBe "5±1"
            error("(5±1) < 6") shouldContain "compare its value"
            error("5 ± -1") shouldContain "not negative"
            error("5 ± \"a\"") shouldContain "measure"
            error("1 ± 2 ± 3") shouldContain "one uncertainty"
        }
    }

    context("vectors") {
        test("a literal reads as Ki.Core reads it") {
            eval(".vec<Float> { 1.0 2.5 3.1 }") shouldBe Vector.parse(".vec<Float> { 1.0 2.5 3.1 }")
            eval(".vec<Int> { 1 -2, 3 }") shouldBe Vector.ofInts(1, -2, 3)
            eval(".vec<Dec> { 0.1 68.2 }") shouldBe Vector.ofDecs(Dec("0.1"), Dec("68.2"))
            eval(".vec<Int> { }") shouldBe Vector.ofInts()
            eval("let x = 4\n.vec<Long> {\n  x, (x * 2)\n  3\n}") shouldBe Vector.ofLongs(4, 8, 3)
            eval("let v = .vec<Double> { 0.1 1.0E-5 -2.5 }\n\"\$v\"") shouldBe ".vec<Double> { 0.1 1.0E-5 -2.5 }"
        }

        test("members, indexing, iteration and arithmetic") {
            eval("let v = .vec<Int> { 3 1 4 1 5 }\nv.size") shouldBe 5
            eval("let v = .vec<Int> { 3 1 4 1 5 }\nv[2]") shouldBe 4
            eval("let v = .vec<Int> { 3 1 4 1 5 }\nv.sum()") shouldBe 14L
            eval("let v = .vec<Int> { 3 1 4 1 5 }\nv.mean()") shouldBe 2.8
            eval("var t = 0\nfor x in .vec<Int> { 1 2 3 } { t += x }\nt") shouldBe 6
            eval(".vec<Int> { 1 2 } + .vec<Int> { 10 20 }") shouldBe Vector.ofInts(11, 22)
            eval(".vec<Int> { 1 2 } * 0.5") shouldBe Vector.ofDoubles(0.5, 1.0)
            eval("2 * .vec<Int> { 1 2 }") shouldBe Vector.ofInts(2, 4)
            eval(".vec<Int> { 1 2 3 }.dot(.vec<Int> { 4 5 6 })") shouldBe 32L
            eval(".vec<Int> { 1 } == .vec<Double> { 1.0 }") shouldBe true
            eval(".vec<Int> { 1 2 } is Vector") shouldBe true
        }

        test("errors") {
            error(".vec<Int> { 1.5 }") shouldContain ".vec<Int>"
            error(".vec<String> { }") shouldContain "Int, Long, Float, Double or Dec"
            error(".vec<Int> { \"a\" }") shouldContain "numbers"
            error(".vec { 1 }") shouldContain "<"
            error("let v = .vec<Int> { 1 }\nv[3]") shouldContain "3"
            error(".vec<Int> { 1 } + .vec<Int> { 1 2 }") shouldContain "combined"
        }
    }

    // The review of 2026-10-28: Ki.Core's exceptions reach a script as errors it can catch, as 1.0/0 does.
    test("try/catch catches measure and vector errors; == of measures in two dimensions is false") {
        eval("try { 1.0/0 } catch (e) { \"caught\" }") shouldBe "caught"
        for (expr in listOf("(5±1) / 0", ".vec<Int> { 1 } / 0", ".vec<Int> { 2147483647 } + .vec<Int> { 1 }", "(5±1cm) + (5±1g)", "(5±1cm) < (5±1g)")) {
            eval("try { $expr } catch (e) { \"caught\" }") shouldBe "caught"
        }
        eval("(5±1cm) == (5±1g)") shouldBe false
        error("(5±1) / 0") shouldContain "Division by zero"
    }
})
