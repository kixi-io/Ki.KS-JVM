package io.kixi.ks

import io.kixi.Range
import io.kixi.Range.Bound
import io.kixi.ks.ext.picked
import io.kixi.ks.interp.Interpreter
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Tests for `pick` (mutating removal — mutable List only) and `picked`
 * (non-mutating random selection) across List, String, and Range.
 *
 * Selection is uniform via `Random.Default`, so these tests assert
 * *properties* — membership in the source, size change, member availability —
 * and use many trials for the "varies" checks, where a false pass is
 * astronomically unlikely, rather than pinning a fixed random value.
 *
 * All runs use portable mode (`hostLang = false`).
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.PickPickedTest"
 */
class PickPickedTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

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
    // List.picked — non-mutating random element
    // ====================================================================

    context("List.picked") {

        test("returns a member of the list") {
            repeat(200) {
                ((eval("[10, 20, 30].picked()") as Int) in listOf(10, 20, 30)) shouldBe true
            }
        }

        test("does not mutate the list") {
            eval(
                """
                var xs = [1, 2, 3]
                xs.picked()
                xs.size
                """.trimIndent()
            ) shouldBe 3
        }

        test("single-element list always returns that element") {
            repeat(20) { eval("[42].picked()") shouldBe 42 }
        }

        test("varies across calls") {
            val seen = (1..200).map { eval("[1, 2, 3].picked()") }.toSet()
            (seen.size > 1) shouldBe true
            seen.forEach { (it in listOf(1, 2, 3)) shouldBe true }
        }

        test("empty list throws out-of-bounds") {
            runExpectingError("[].picked()") shouldContain "out of bounds"
        }
    }

    // ====================================================================
    // List.pick — mutating removal (mutable List only)
    // ====================================================================

    context("List.pick") {

        test("returns a member of the list") {
            repeat(50) {
                val removed = eval(
                    """
                    var xs = [1, 2, 3]
                    xs.pick()
                    """.trimIndent()
                )
                (removed in listOf(1, 2, 3)) shouldBe true
            }
        }

        test("shrinks the list by one") {
            eval(
                """
                var xs = [1, 2, 3]
                xs.pick()
                xs.size
                """.trimIndent()
            ) shouldBe 2
        }

        test("removed element is not the survivor") {
            repeat(50) {
                val pair = eval(
                    """
                    var xs = [1, 2]
                    let removed = xs.pick()
                    [removed, xs[0]]
                    """.trimIndent()
                ) as List<*>
                (pair[0] != pair[1]) shouldBe true
            }
        }

        test("repeated pick drains the list to empty") {
            eval(
                """
                var xs = [1, 2, 3]
                xs.pick()
                xs.pick()
                xs.pick()
                xs.size
                """.trimIndent()
            ) shouldBe 0
        }

        test("pick past the last element throws out-of-bounds") {
            runExpectingError(
                """
                var xs = [1]
                xs.pick()
                xs.pick()
                """.trimIndent()
            ) shouldContain "out of bounds"
        }
    }

    // ====================================================================
    // String.picked — non-mutating random Char (no `pick`)
    // ====================================================================

    context("String.picked") {

        test("returns a Char from the string") {
            repeat(200) {
                ((eval("\"abc\".picked()") as Char) in listOf('a', 'b', 'c')) shouldBe true
            }
        }

        test("single-char string always returns that char") {
            repeat(20) { eval("\"x\".picked()") shouldBe 'x' }
        }

        test("varies across calls") {
            val seen = (1..200).map { eval("\"abc\".picked()") }.toSet()
            (seen.size > 1) shouldBe true
        }

        test("empty string throws out-of-bounds") {
            runExpectingError("\"\".picked()") shouldContain "out of bounds"
        }

        test("String has no mutating pick — only picked") {
            runExpectingError("\"abc\".pick()") shouldContain "no member 'pick'"
        }
    }

    // ====================================================================
    // Range.picked (interpreter level) — no `pick`
    // ====================================================================

    context("Range.picked (interpreter)") {

        test("Int range returns a member") {
            repeat(200) { ((eval("(1..6).picked()") as Int) in 1..6) shouldBe true }
        }

        test("stepped range — positional and named step agree") {
            val domain = listOf(10, 20, 30, 40, 50)
            repeat(100) {
                ((eval("(10..50).picked(10)") as Int) in domain) shouldBe true
                ((eval("(10..50).picked(step=10)") as Int) in domain) shouldBe true
            }
        }

        test("Char range returns a Char member") {
            repeat(100) { ((eval("('a'..'f').picked()") as Char) in 'a'..'f') shouldBe true }
        }

        test("reversed range returns a member of the span") {
            repeat(100) { ((eval("(5..1).picked()") as Int) in 1..5) shouldBe true }
        }

        test("exclusive-both narrows the domain (only 2 lies strictly between 1 and 3)") {
            repeat(50) { eval("(1<..<3).picked()") shouldBe 2 }
        }

        test("empty effective range throws out-of-bounds") {
            runExpectingError("(5<..<6).picked()") shouldContain "out of bounds"
        }

        test("Range has no mutating pick — only picked") {
            runExpectingError("(1..5).pick()") shouldContain "no member 'pick'"
        }
    }

    // ====================================================================
    // Range.picked — direct Kotlin-level extension
    // ====================================================================

    context("Range.picked (direct extension)") {

        test("Int range picks a member") {
            val r = Range(1, 6, Bound.Inclusive)
            repeat(200) { ((r.picked() as Int) in 1..6) shouldBe true }
        }

        test("stepped range stays on step") {
            val r = Range(10, 50, Bound.Inclusive)
            val domain = listOf(10, 20, 30, 40, 50)
            repeat(100) { ((r.picked(step = 10) as Int) in domain) shouldBe true }
        }

        test("open range throws") {
            shouldThrow<IllegalArgumentException> {
                Range(1, null, Bound.Inclusive).picked()
            }.message shouldContain "open"
        }

        test("non-discrete (Double) range throws") {
            shouldThrow<IllegalArgumentException> {
                Range(1.0, 5.0, Bound.Inclusive).picked()
            }.message shouldContain "discrete"
        }

        test("step < 1 throws") {
            shouldThrow<IllegalArgumentException> {
                Range(1, 10, Bound.Inclusive).picked(step = 0)
            }.message shouldContain "Step"
        }

        test("empty effective range throws") {
            shouldThrow<NoSuchElementException> {
                Range(5, 6, Bound.Exclusive).picked()
            }.message shouldContain "empty"
        }
    }
})