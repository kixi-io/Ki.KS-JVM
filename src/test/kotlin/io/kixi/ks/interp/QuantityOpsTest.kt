package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Tests for Quantity operations in KS.
 *
 * Covers unit conversion via the `as` keyword:
 *     (10cm + 4mm) as cm   \u2192 10.4cm
 *     2km as m              \u2192 2000m
 *     500g as kg             \u2192 0.5kg
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.QuantityOpsTest"
 */
class QuantityOpsTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

    fun run(source: String): String {
        val output = StringWriter()
        val error = StringWriter()
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = PrintWriter(output, true),
            errorWriter = PrintWriter(error, true),
            debugMode = false
        )
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = Interpreter(runtime)
        interpreter.executeProgram(program)
        return output.toString().trim()
    }

    fun eval(source: String): Any? {
        val output = StringWriter()
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = PrintWriter(output, true),
            errorWriter = PrintWriter(StringWriter(), true),
            debugMode = false
        )
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = Interpreter(runtime)
        return interpreter.executeProgram(program)
    }

    fun runExpectingError(source: String): String {
        val output = StringWriter()
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = PrintWriter(output, true),
            errorWriter = PrintWriter(StringWriter(), true)
        )
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = Interpreter(runtime)

        try {
            interpreter.executeProgram(program)
            throw AssertionError("Expected an error but execution completed. Output: ${output.toString().trim()}")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            return e.message ?: e.toString()
        }
    }

    // ====================================================================
    // Unit Conversion via `as`
    // ====================================================================

    context("unit conversion via as - length") {

        test("cm to mm") {
            run("""
                say 5cm as mm
            """.trimIndent()) shouldBe "50mm"
        }

        test("expression result: (10cm + 4mm) as cm") {
            run("""
                let result = (10cm + 4mm) as cm
                say result
            """.trimIndent()) shouldBe "10.4cm"
        }

        test("mm to m") {
            run("""
                say 1500mm as m
            """.trimIndent()) shouldBe "1.5m"
        }

        test("km to m") {
            run("""
                say 2km as m
            """.trimIndent()) shouldBe "2000m"
        }
    }

    context("unit conversion via as - mass") {

        test("kg to g") {
            run("""
                say 3kg as g
            """.trimIndent()) shouldBe "3000g"
        }

        test("g to kg") {
            run("""
                say 500g as kg
            """.trimIndent()) shouldBe "0.5kg"
        }
    }

    context("unit conversion via as - temperature") {

        test("Celsius to Kelvin") {
            run("""
                let temp = Quantity(25, "dC")
                say temp as K
            """.trimIndent()) shouldBe "298.15K"
        }
    }

    context("unit conversion via as - area") {

        test("cm\u00B2 to m\u00B2") {
            run("""
                let a = Quantity(10000, "cm2")
                say a as m2
            """.trimIndent()) shouldBe "1m\u00B2:i"
        }
    }

    context("unit conversion via as - numeric type behavior") {

        test("whole result preserves numeric value") {
            run("""
                let q = 5cm as mm
                say q.value
            """.trimIndent()) shouldBe "50"
        }

        test("non-whole result promotes to Dec") {
            run("""
                let q = 1cm as m
                say q
            """.trimIndent()) shouldBe "0.01m"
        }
    }

    context("unit conversion via as - usage patterns") {

        test("chained conversion") {
            run("""
                let inMeters = 1km as m
                let inCm = inMeters as cm
                say inCm
            """.trimIndent()) shouldBe "100000cm"
        }

        test("conversion with variable") {
            run("""
                let dist = 250cm
                let inMeters = dist as m
                say inMeters
            """.trimIndent()) shouldBe "2.5m"
        }
    }

    context("unit conversion via as - errors") {

        test("incompatible units throws IncompatibleUnitsException") {
            val msg = runExpectingError("5cm as kg")
            msg shouldContain "convert"
        }
    }
})