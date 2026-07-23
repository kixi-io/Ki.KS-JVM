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
 * Tests for named-argument binding in calls.
 *
 * Functions, class methods, and struct methods now bind arguments by
 * parameter name (when a name is given), by position otherwise, and fall
 * back to defaults — the same rule already used for class/struct
 * construction. Built-in member methods (`NativeCallable`, e.g. `count`,
 * `picked`) remain positional: a `name = value` argument there is treated
 * positionally, the name ignored.
 *
 * The subtraction helper `a - b` is used throughout because it is
 * order-sensitive: if names were ignored (the old behavior), `f(b = 3, a = 10)`
 * would compute `3 - 10 = -7` instead of `10 - 3 = 7`, so these tests fail
 * loudly against a regression.
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.NamedArgumentTest"
 */
class NamedArgumentTest : FunSpec({

    fun eval(source: String): Any? {
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = PrintWriter(StringWriter(), true),
            errorWriter = PrintWriter(StringWriter(), true),
            debugMode = false
        )
        val tokens = Lexer(source).tokenize()
        val program = Parser(tokens).parse()
        return Interpreter(runtime).executeProgram(program)
    }

    fun runExpectingError(source: String): String {
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = PrintWriter(StringWriter(), true),
            errorWriter = PrintWriter(StringWriter(), true)
        )
        val tokens = Lexer(source).tokenize()
        val program = Parser(tokens).parse()
        val interpreter = Interpreter(runtime)
        return try {
            interpreter.executeProgram(program)
            throw AssertionError("Expected an error but execution completed.")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    // ====================================================================
    // Free functions
    // ====================================================================

    context("free functions") {

        test("positional binding is unchanged (regression)") {
            eval("fun f(a, b) = a - b\nf(10, 3)") shouldBe 7
        }

        test("named args bind by name regardless of order") {
            eval("fun f(a, b) = a - b\nf(b = 3, a = 10)") shouldBe 7
        }

        test("named args in declaration order") {
            eval("fun f(a, b) = a - b\nf(a = 10, b = 3)") shouldBe 7
        }

        test("mixed: leading positional, then named") {
            eval("fun f(a, b, c) = a * 100 + b * 10 + c\nf(1, c = 3, b = 2)") shouldBe 123
        }

        test("a named arg supplies its value; defaults fill the rest") {
            eval("fun f(a, b = 5) = a - b\nf(a = 10)") shouldBe 5
        }

        test("a named arg overrides a default") {
            eval("fun f(a, b = 5) = a - b\nf(a = 10, b = 2)") shouldBe 8
        }

        test("a named arg claims its parameter; a positional fills the next open one") {
            // Mirrors constructor binding: 'a' is named (= 2), so the positional
            // 1 fills the next unbound parameter (b): a = 2, b = 1.
            eval("fun f(a, b) = a * 10 + b\nf(1, a = 2)") shouldBe 21
        }

        test("an unknown named argument throws") {
            runExpectingError("fun f(a) = a\nf(x = 1)") shouldContain "Unknown named argument 'x'"
        }
    }

    // ====================================================================
    // Class methods
    // ====================================================================

    context("class methods") {

        test("named args reorder for a class method") {
            eval(
                """
                class C { fun m(a, b) = a - b }
                let c = C()
                c.m(b = 3, a = 10)
                """.trimIndent()
            ) shouldBe 7
        }

        test("positional still works for a class method") {
            eval(
                """
                class C { fun m(a, b) = a - b }
                let c = C()
                c.m(10, 3)
                """.trimIndent()
            ) shouldBe 7
        }
    }

    // ====================================================================
    // Struct methods
    // ====================================================================

    context("struct methods") {

        test("named args reorder for a struct method") {
            eval(
                """
                struct S(let n: Int) { fun m(a, b) = a - b }
                let s = S(0)
                s.m(b = 3, a = 10)
                """.trimIndent()
            ) shouldBe 7
        }

        test("positional still works for a struct method") {
            eval(
                """
                struct S(let n: Int) { fun m(a, b) = a - b }
                let s = S(0)
                s.m(10, 3)
                """.trimIndent()
            ) shouldBe 7
        }
    }

    // ====================================================================
    // Built-in members stay positional
    // ====================================================================

    context("built-in members") {

        test("a named arg to a native member is treated positionally") {
            // count() is a NativeCallable: the name is dropped and the value
            // lands in position 0. Both forms count 1,3,5,7,9 -> 5.
            eval("(1..10).count(step = 2)") shouldBe 5
            eval("(1..10).count(2)") shouldBe 5
        }
    }

    // ====================================================================
    // Constructors (unified through the same binder)
    // ====================================================================

    context("class construction") {

        test("named args reorder") {
            // Named binding: x = 1, y = 2 -> 1 - 2 = -1.
            // (If names were ignored, x = 2, y = 1 -> 1.)
            eval(
                """
                class P(let x: Int, let y: Int)
                let p = P(y = 2, x = 1)
                p.x - p.y
                """.trimIndent()
            ) shouldBe -1
        }

        test("positional still works") {
            eval(
                """
                class P(let x: Int, let y: Int)
                let p = P(1, 2)
                p.x - p.y
                """.trimIndent()
            ) shouldBe -1
        }

        test("an unknown named argument throws") {
            runExpectingError(
                """
                class P(let x: Int)
                P(z = 1)
                """.trimIndent()
            ) shouldContain "Unknown named argument 'z'"
        }
    }

    context("struct construction") {

        test("named args reorder") {
            eval(
                """
                struct V(let x: Int, let y: Int)
                let v = V(y = 2, x = 1)
                v.x - v.y
                """.trimIndent()
            ) shouldBe -1
        }

        test("an unknown named argument throws") {
            runExpectingError(
                """
                struct V(let x: Int)
                V(z = 1)
                """.trimIndent()
            ) shouldContain "Unknown named argument 'z'"
        }
    }
})