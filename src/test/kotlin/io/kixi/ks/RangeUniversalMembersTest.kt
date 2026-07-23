package io.kixi.ks

import io.kixi.ks.interp.Interpreter
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Tests that Range exposes `equals`, `hashCode`, and `toString` through the
 * curated `getRangeMember` dispatch, so they resolve in portable mode
 * (`hostLang = false`).
 *
 * This is the key regression guard: Range has no internal reflection tier,
 * and the universal reflection backstop is gated behind `hostLang = true`.
 * Before these members were wired explicitly, calling them in portable mode
 * raised `MemberNotFoundError`. Every run here uses `hostLang = false`, so a
 * regression that removed the explicit dispatch would fail these tests.
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.RangeUniversalMembersTest"
 */
class RangeUniversalMembersTest : FunSpec({

    fun run(source: String): String {
        val output = StringWriter()
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = PrintWriter(output, true),
            errorWriter = PrintWriter(StringWriter(), true),
            debugMode = false
        )
        val tokens = Lexer(source).tokenize()
        val program = Parser(tokens).parse()
        Interpreter(runtime).executeProgram(program)
        return output.toString().trim()
    }

    context("toString") {
        test("inclusive")      { run("say (1..5).toString()")   shouldBe "1..5" }
        test("exclusive end")  { run("say (1..<5).toString()")  shouldBe "1..<5" }
        test("exclusive start"){ run("say (1<..5).toString()")  shouldBe "1<..5" }
        test("exclusive both") { run("say (1<..<5).toString()") shouldBe "1<..<5" }
    }

    context("equals") {
        test("equal ranges are equal") {
            run("say (1..5).equals(1..5)") shouldBe "true"
        }
        test("different end is not equal") {
            run("say (1..5).equals(1..6)") shouldBe "false"
        }
        test("a non-range argument is not equal") {
            run("say (1..5).equals(\"x\")") shouldBe "false"
        }
    }

    context("hashCode") {
        test("equal ranges produce equal hash codes") {
            run("say (1..5).hashCode() == (1..5).hashCode()") shouldBe "true"
        }
    }
})