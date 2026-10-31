package io.kixi.ks.interp

import io.kixi.Measure
import io.kixi.kd.KD
import io.kixi.ks.KSRuntime
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kixi.uom.Quantity
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The liter written in ASCII, `1.L` (2026-10-30). `L` after a number is a Long
 * (`1L`), so the liter puts a dot between the number and the `L`; Ki.Core reads
 * it as `ℓ`, the symbol a liter always prints with. KS, KD and Ki.Core read the
 * same text to the same value.
 */
class LiterLiteralTest : FunSpec({

    fun eval(source: String): Any? {
        val runtime = KSRuntime(
            hostLang = false, colorOutput = false,
            outputWriter = PrintWriter(StringWriter(), true), errorWriter = PrintWriter(StringWriter(), true), debugMode = false
        )
        return Interpreter(runtime).executeProgram(Parser(Lexer(source).tokenize()).parse())
    }

    test("KS, KD and Ki.Core read 1.L alike") {
        for (text in listOf("1.L", "2.5.L", "1_000.L", "1e3.L", "1.5e(3).L", "1.L:d", "0.25.L")) {
            val core = Quantity.parse(text)
            withClue(text) {
                eval(text) shouldBe core
                KD.read("t $text").values.single() shouldBe core
                core.unit.symbol shouldBe "ℓ"
            }
        }
        eval("1±0.1.L") shouldBe Measure.parse("1±0.1ℓ")
    }

    test("it is a liter in every calculation") {
        eval("1.L").toString() shouldBe "1ℓ"
        eval("-0.5.L").toString() shouldBe "-0.5ℓ"
        eval("1.L + 500mL").toString() shouldBe "1500mℓ"
        eval("1.L == 1000mL") shouldBe true
        eval("2.L / 4").toString() shouldBe "0.5ℓ"
        eval("1.5M * 2.L").toString() shouldBe "3mol"
    }

    test("a conversion target may be a plain L, since no number precedes it") {
        eval("2500mL as L").toString() shouldBe "2.5ℓ"
        eval("(1.L + 500mL) as L").toString() shouldBe "1.5ℓ"
        eval("2.L as mL").toString() shouldBe "2000mℓ"
        eval("(750mL).convertTo(\"L\")").toString() shouldBe "0.75ℓ"
        eval("Quantity(5, \"L\") == 5.L") shouldBe true
        eval("(500±5mL).to(\"L\")") shouldBe Measure.parse("0.5±0.005ℓ")
    }

    test("1L stays a Long and 1..L a range") {
        eval("1L") shouldBe 1L
        eval("let L = 3\n(1..L).toList()") shouldBe listOf(1, 2, 3)
        eval("[1.L, 2.L]").toString() shouldBe "[1ℓ, 2ℓ]"
    }
})
