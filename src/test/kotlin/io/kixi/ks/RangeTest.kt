package io.kixi.ks

import io.kixi.Range
import io.kixi.Range.Bound
import io.kixi.ks.ext.asSequence
import io.kixi.ks.ext.count
import io.kixi.ks.ext.toList
import io.kixi.ks.interp.Interpreter
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Comprehensive tests for Range support in Ki Script.
 *
 * Covers:
 *   - KS range literals (`..`, `..<`, `<..`, `<..<`)
 *   - io.kixi.Range core properties (start, end, bound, min, max, reversed)
 *   - io.kixi.Range openness properties (isOpen, isClosed, isOpenStart, isOpenEnd)
 *   - io.kixi.Range methods (contains, overlaps, clamp, intersect)
 *   - RangeExt enumeration (toList, toSequence, count) via interpreter
 *   - RangeExt Kotlin-level API (direct extension function tests)
 *   - Range `in` / `not in` containment operators
 *   - Range `is` / `is not` type checks
 *   - Range iteration with `for` loops
 *   - Reversed ranges (e.g. `5..1`)
 *   - Char and Long ranges
 *   - Ranges with step
 *   - Error conditions and edge cases
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.RangeTest"
 */
class RangeTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * Execute KS source code and capture stdout output.
     */
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

    /**
     * Execute KS source and return the result of the last expression.
     */
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

    /**
     * Execute KS source and expect a runtime error. Returns the error message.
     */
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
    // 1. Range Literal Syntax
    // ====================================================================

    context("Range literal syntax") {

        test("inclusive range: 1..5") {
            val result = eval("1..5")
            result.shouldBeInstanceOf<Range<*>>()
            result as Range<*>
            result.start shouldBe 1
            result.end shouldBe 5
            result.bound shouldBe Bound.Inclusive
        }

        test("exclusive end: 1..<5") {
            val result = eval("1..<5")
            result.shouldBeInstanceOf<Range<*>>()
            result as Range<*>
            result.start shouldBe 1
            result.end shouldBe 5
            result.bound shouldBe Bound.ExclusiveEnd
        }

        test("exclusive start: 1<..5") {
            val result = eval("1<..5")
            result.shouldBeInstanceOf<Range<*>>()
            result as Range<*>
            result.start shouldBe 1
            result.end shouldBe 5
            result.bound shouldBe Bound.ExclusiveStart
        }

        test("exclusive both: 1<..<5") {
            val result = eval("1<..<5")
            result.shouldBeInstanceOf<Range<*>>()
            result as Range<*>
            result.start shouldBe 1
            result.end shouldBe 5
            result.bound shouldBe Bound.Exclusive
        }

        test("range stored in variable") {
            run("""
                let r = 1..10
                say r.start
                say r.end
            """.trimIndent()) shouldBe "1\n10"
        }

        test("range with negative values") {
            val result = eval("-5..5")
            result.shouldBeInstanceOf<Range<*>>()
            result as Range<*>
            result.start shouldBe -5
            result.end shouldBe 5
        }

        test("range with expressions") {
            run("""
                let a = 2
                let b = 8
                let r = a..b
                say r.start
                say r.end
            """.trimIndent()) shouldBe "2\n8"
        }

        test("single-element range: 5..5") {
            val result = eval("5..5")
            result.shouldBeInstanceOf<Range<*>>()
            result as Range<*>
            result.start shouldBe 5
            result.end shouldBe 5
        }
    }

    // ====================================================================
    // 2. Core Properties
    // ====================================================================

    context("Core properties") {

        test("start property") {
            run("""
                let r = 1..10
                say r.start
            """.trimIndent()) shouldBe "1"
        }

        test("end property") {
            run("""
                let r = 1..10
                say r.end
            """.trimIndent()) shouldBe "10"
        }

        test("bound property — inclusive") {
            run("""
                let r = 1..5
                say r.bound
            """.trimIndent()) shouldBe ".."
        }

        test("bound property — exclusive end") {
            run("""
                let r = 1..<5
                say r.bound
            """.trimIndent()) shouldBe "..<"
        }

        test("bound property — exclusive start") {
            run("""
                let r = 1<..5
                say r.bound
            """.trimIndent()) shouldBe "<.."
        }

        test("bound property — exclusive both") {
            run("""
                let r = 1<..<5
                say r.bound
            """.trimIndent()) shouldBe "<..<"
        }

        test("min property — forward range") {
            run("""
                let r = 3..7
                say r.min
            """.trimIndent()) shouldBe "3"
        }

        test("max property — forward range") {
            run("""
                let r = 3..7
                say r.max
            """.trimIndent()) shouldBe "7"
        }

        test("min property — reversed range") {
            run("""
                let r = 7..3
                say r.min
            """.trimIndent()) shouldBe "3"
        }

        test("max property — reversed range") {
            run("""
                let r = 7..3
                say r.max
            """.trimIndent()) shouldBe "7"
        }

        test("reversed property — forward") {
            run("""
                let r = 1..5
                say r.reversed
            """.trimIndent()) shouldBe "false"
        }

        test("reversed property — backward") {
            run("""
                let r = 5..1
                say r.reversed
            """.trimIndent()) shouldBe "true"
        }
    }

    // ====================================================================
    // 3. Openness Properties
    // ====================================================================

    context("Openness properties") {

        test("isClosed — true for bounded range") {
            run("""
                let r = 1..5
                say r.isClosed
            """.trimIndent()) shouldBe "true"
        }

        test("isOpen — false for bounded range") {
            run("""
                let r = 1..5
                say r.isOpen
            """.trimIndent()) shouldBe "false"
        }

        test("isOpenStart — false for bounded range") {
            run("""
                let r = 1..5
                say r.isOpenStart
            """.trimIndent()) shouldBe "false"
        }

        test("isOpenEnd — false for bounded range") {
            run("""
                let r = 1..5
                say r.isOpenEnd
            """.trimIndent()) shouldBe "false"
        }
    }

    // ====================================================================
    // 4. Backward-Compatible Properties
    // ====================================================================

    context("Backward-compatible boolean properties") {

        test("startExclusive — false for inclusive") {
            run("""
                let r = 1..5
                say r.startExclusive
            """.trimIndent()) shouldBe "false"
        }

        test("startExclusive — true for exclusive start") {
            run("""
                let r = 1<..5
                say r.startExclusive
            """.trimIndent()) shouldBe "true"
        }

        test("startExclusive — true for exclusive both") {
            run("""
                let r = 1<..<5
                say r.startExclusive
            """.trimIndent()) shouldBe "true"
        }

        test("endExclusive — false for inclusive") {
            run("""
                let r = 1..5
                say r.endExclusive
            """.trimIndent()) shouldBe "false"
        }

        test("endExclusive — true for exclusive end") {
            run("""
                let r = 1..<5
                say r.endExclusive
            """.trimIndent()) shouldBe "true"
        }

        test("endExclusive — true for exclusive both") {
            run("""
                let r = 1<..<5
                say r.endExclusive
            """.trimIndent()) shouldBe "true"
        }
    }

    // ====================================================================
    // 5. contains() Method
    // ====================================================================

    context("contains() method") {

        test("contains — value inside inclusive range") {
            run("""
                let r = 1..10
                say r.contains(5)
            """.trimIndent()) shouldBe "true"
        }

        test("contains — value at start of inclusive range") {
            run("""
                let r = 1..10
                say r.contains(1)
            """.trimIndent()) shouldBe "true"
        }

        test("contains — value at end of inclusive range") {
            run("""
                let r = 1..10
                say r.contains(10)
            """.trimIndent()) shouldBe "true"
        }

        test("contains — value outside range") {
            run("""
                let r = 1..10
                say r.contains(11)
            """.trimIndent()) shouldBe "false"
        }

        test("contains — exclusive end excludes end value") {
            run("""
                let r = 1..<10
                say r.contains(10)
            """.trimIndent()) shouldBe "false"
        }

        test("contains — exclusive end includes value just before end") {
            run("""
                let r = 1..<10
                say r.contains(9)
            """.trimIndent()) shouldBe "true"
        }

        test("contains — exclusive start excludes start value") {
            run("""
                let r = 1<..10
                say r.contains(1)
            """.trimIndent()) shouldBe "false"
        }

        test("contains — exclusive both excludes both endpoints") {
            run("""
                let r = 1<..<10
                say r.contains(1)
                say r.contains(10)
                say r.contains(5)
            """.trimIndent()) shouldBe "false\nfalse\ntrue"
        }

        test("contains — nil returns false") {
            run("""
                let r = 1..10
                say r.contains(nil)
            """.trimIndent()) shouldBe "false"
        }

        test("contains — stored in variable and called") {
            run("""
                let r = 1..100
                let check = r.contains
                say check(50)
                say check(101)
            """.trimIndent()) shouldBe "true\nfalse"
        }
    }

    // ====================================================================
    // 6. in / not-in Operators
    // ====================================================================

    context("in and not-in operators") {

        test("in — value inside range") {
            run("say 5 in 1..10") shouldBe "true"
        }

        test("in — value outside range") {
            run("say 15 in 1..10") shouldBe "false"
        }

        test("not-in — value outside range") {
            run("""
                let x = 15
                say x !in 1..10
            """.trimIndent()) shouldBe "true"
        }

        test("not-in — value inside range") {
            run("""
                let x = 5
                say x !in 1..10
            """.trimIndent()) shouldBe "false"
        }

        test("in — respects exclusive end") {
            run("say 10 in 1..<10") shouldBe "false"
        }

        test("in — respects exclusive start") {
            run("say 1 in 1<..10") shouldBe "false"
        }

        test("in — with expression on left side") {
            run("""
                let x = 3 + 2
                say x in 1..10
            """.trimIndent()) shouldBe "true"
        }

        test("in — in conditional") {
            run("""
                let score = 85
                if score in 90..100 {
                    say "A"
                } else if score in 80..<90 {
                    say "B"
                } else {
                    say "C"
                }
            """.trimIndent()) shouldBe "B"
        }
    }

    // ====================================================================
    // 7. is / is-not Type Checks
    // ====================================================================

    context("is and is-not type checks") {

        // TODO: `is Range` requires registering Range as a type name in the parser.
        //       Currently causes ParseException. Uncomment when Range is added to
        //       the type system's recognized names.

        test("range is Range") {
             run("""
                 let r = 1..5
                 say r is Range
             """.trimIndent()) shouldBe "true"
         }

        test("non-range is not Range") {
            run("say 42 is Range") shouldBe "false"
        }

        test("is-not Range for non-range value") {
            run("""
                let s = "hello"
                say s !is Range
            """.trimIndent()) shouldBe "true"
        }

        test("range variable is Range") {
            run("""
                let r = 1..10
                say r is Range
            """.trimIndent()) shouldBe "true"
        }
    }

    // ====================================================================
    // 8. for-loop Iteration
    // ====================================================================

    context("for-loop iteration") {

        test("iterate inclusive range") {
            run("""
                for i in 1..5 {
                    say i
                }
            """.trimIndent()) shouldBe "1\n2\n3\n4\n5"
        }

        test("iterate exclusive end range") {
            run("""
                for i in 1..<5 {
                    say i
                }
            """.trimIndent()) shouldBe "1\n2\n3\n4"
        }

        test("iterate exclusive start range") {
            run("""
                for i in 1<..5 {
                    say i
                }
            """.trimIndent()) shouldBe "2\n3\n4\n5"
        }

        test("iterate exclusive both range") {
            run("""
                for i in 1<..<5 {
                    say i
                }
            """.trimIndent()) shouldBe "2\n3\n4"
        }

        test("iterate reversed range") {
            run("""
                for i in 5..1 {
                    say i
                }
            """.trimIndent()) shouldBe "5\n4\n3\n2\n1"
        }

        test("iterate reversed exclusive end range") {
            run("""
                for i in 5..<1 {
                    say i
                }
            """.trimIndent()) shouldBe "5\n4\n3\n2"
        }

        test("iterate single element range") {
            run("""
                for i in 3..3 {
                    say i
                }
            """.trimIndent()) shouldBe "3"
        }

        test("for-loop with implicit it") {
            run("""
                for 1..3 say it
            """.trimIndent()) shouldBe "1\n2\n3"
        }

        test("for-loop range with break") {
            run("""
                for i in 1..10 {
                    if i == 4 { break }
                    say i
                }
            """.trimIndent()) shouldBe "1\n2\n3"
        }

        test("for-loop range with continue") {
            run("""
                for i in 1..5 {
                    if i == 3 { continue }
                    say i
                }
            """.trimIndent()) shouldBe "1\n2\n4\n5"
        }

        test("nested range loops") {
            run("""
                for i in 1..3 {
                    for j in 1..3 {
                        if i == j { say "${'$'}i" }
                    }
                }
            """.trimIndent()) shouldBe "1\n2\n3"
        }

        test("FizzBuzz with range") {
            run("""
                for i in 1..15 {
                    let result = when {
                        i % 15 == 0 -> "FizzBuzz"
                        i % 3 == 0 -> "Fizz"
                        i % 5 == 0 -> "Buzz"
                        else -> i
                    }
                    say result
                }
            """.trimIndent()) shouldBe "1\n2\nFizz\n4\nBuzz\nFizz\n7\n8\nFizz\nBuzz\n11\nFizz\n13\n14\nFizzBuzz"
        }
    }

    // ====================================================================
    // 9. toList() Method
    // ====================================================================

    context("toList() method") {

        test("toList — inclusive range") {
            run("""
                let r = 1..5
                say r.toList()
            """.trimIndent()) shouldBe "[1, 2, 3, 4, 5]"
        }

        test("toList — exclusive end") {
            run("""
                let r = 1..<5
                say r.toList()
            """.trimIndent()) shouldBe "[1, 2, 3, 4]"
        }

        test("toList — exclusive start") {
            run("""
                let r = 1<..5
                say r.toList()
            """.trimIndent()) shouldBe "[2, 3, 4, 5]"
        }

        test("toList — exclusive both") {
            run("""
                let r = 1<..<5
                say r.toList()
            """.trimIndent()) shouldBe "[2, 3, 4]"
        }

        test("toList — reversed range") {
            run("""
                let r = 5..1
                say r.toList()
            """.trimIndent()) shouldBe "[5, 4, 3, 2, 1]"
        }

        test("toList — reversed exclusive end") {
            run("""
                let r = 5..<1
                say r.toList()
            """.trimIndent()) shouldBe "[5, 4, 3, 2]"
        }

        test("toList — reversed exclusive both") {
            run("""
                let r = 5<..<1
                say r.toList()
            """.trimIndent()) shouldBe "[4, 3, 2]"
        }

        test("toList — single element") {
            run("""
                let r = 3..3
                say r.toList()
            """.trimIndent()) shouldBe "[3]"
        }

        test("toList — with step") {
            run("""
                let r = 0..10
                say r.toList(2)
            """.trimIndent()) shouldBe "[0, 2, 4, 6, 8, 10]"
        }

        test("toList — with step 3") {
            run("""
                let r = 1..10
                say r.toList(3)
            """.trimIndent()) shouldBe "[1, 4, 7, 10]"
        }

        test("toList — reversed with step") {
            run("""
                let r = 10..0
                say r.toList(3)
            """.trimIndent()) shouldBe "[10, 7, 4, 1]"
        }

        test("toList — empty range (exclusive both, adjacent values)") {
            run("""
                let r = 5<..<6
                say r.toList()
            """.trimIndent()) shouldBe "[]"
        }

        test("toList result used in for-loop") {
            run("""
                let items = (1..4).toList()
                for item in items {
                    say item * 10
                }
            """.trimIndent()) shouldBe "10\n20\n30\n40"
        }

        test("toList result size") {
            run("""
                let items = (1..5).toList()
                say items.size
            """.trimIndent()) shouldBe "5"
        }
    }

    // ====================================================================
    // 10. toSequence() Method
    // ====================================================================

    context("toSequence() method") {

        test("toSequence — materializes same as toList") {
            run("""
                let r = 1..5
                say r.toSequence()
            """.trimIndent()) shouldBe "[1, 2, 3, 4, 5]"
        }

        test("toSequence — with step") {
            run("""
                let r = 0..10
                say r.toSequence(2)
            """.trimIndent()) shouldBe "[0, 2, 4, 6, 8, 10]"
        }

        test("toSequence — reversed") {
            run("""
                let r = 5..1
                say r.toSequence()
            """.trimIndent()) shouldBe "[5, 4, 3, 2, 1]"
        }
    }

    // ====================================================================
    // 11. count() Method
    // ====================================================================

    context("count() method") {

        test("count — inclusive range") {
            run("""
                let r = 1..10
                say r.count()
            """.trimIndent()) shouldBe "10"
        }

        test("count — exclusive end") {
            run("""
                let r = 1..<10
                say r.count()
            """.trimIndent()) shouldBe "9"
        }

        test("count — exclusive start") {
            run("""
                let r = 1<..10
                say r.count()
            """.trimIndent()) shouldBe "9"
        }

        test("count — exclusive both") {
            run("""
                let r = 1<..<10
                say r.count()
            """.trimIndent()) shouldBe "8"
        }

        test("count — with step") {
            run("""
                let r = 0..10
                say r.count(2)
            """.trimIndent()) shouldBe "6"
        }

        test("count — single element") {
            run("""
                let r = 5..5
                say r.count()
            """.trimIndent()) shouldBe "1"
        }

        test("count — empty range") {
            run("""
                let r = 5<..<6
                say r.count()
            """.trimIndent()) shouldBe "0"
        }

        test("count — reversed range") {
            run("""
                let r = 10..1
                say r.count()
            """.trimIndent()) shouldBe "10"
        }

        test("count matches toList size") {
            run("""
                let r = 1..20
                say r.count() == r.toList().size
            """.trimIndent()) shouldBe "true"
        }
    }

    // ====================================================================
    // 12. overlaps() Method
    // ====================================================================

    context("overlaps() method") {

        test("overlapping ranges") {
            run("""
                let a = 1..5
                let b = 3..8
                say a.overlaps(b)
            """.trimIndent()) shouldBe "true"
        }

        test("non-overlapping ranges") {
            run("""
                let a = 1..3
                let b = 5..8
                say a.overlaps(b)
            """.trimIndent()) shouldBe "false"
        }

        test("adjacent ranges — inclusive") {
            run("""
                let a = 1..5
                let b = 5..10
                say a.overlaps(b)
            """.trimIndent()) shouldBe "true"
        }

        test("adjacent ranges — exclusive end overlap behavior") {
            // Note: Range.overlaps() in Ki.Core compares raw endpoints without
            // accounting for exclusivity. For exclusivity-aware overlap checking,
            // a future Range.overlapsExclusive() or updated Ki.Core is needed.
            // Currently 1..<5 and 5..10 report overlapping (raw endpoints touch).
            run("""
                let a = 1..<5
                let b = 5..10
                say a.overlaps(b)
            """.trimIndent()) shouldBe "true"
        }

        test("contained range overlaps") {
            run("""
                let a = 1..10
                let b = 3..7
                say a.overlaps(b)
            """.trimIndent()) shouldBe "true"
        }
    }

    // ====================================================================
    // 13. clamp() Method
    // ====================================================================

    context("clamp() method") {

        test("clamp — value within range") {
            run("""
                let r = 1..10
                say r.clamp(5)
            """.trimIndent()) shouldBe "5"
        }

        test("clamp — value below range") {
            run("""
                let r = 1..10
                say r.clamp(-3)
            """.trimIndent()) shouldBe "1"
        }

        test("clamp — value above range") {
            run("""
                let r = 1..10
                say r.clamp(15)
            """.trimIndent()) shouldBe "10"
        }

        test("clamp — at boundaries") {
            run("""
                let r = 1..10
                say r.clamp(1)
                say r.clamp(10)
            """.trimIndent()) shouldBe "1\n10"
        }
    }

    // ====================================================================
    // 14. intersect() Method
    // ====================================================================

    context("intersect() method") {

        test("intersect — overlapping ranges") {
            run("""
                let a = 1..8
                let b = 5..12
                let r = a.intersect(b)
                say r.start
                say r.end
            """.trimIndent()) shouldBe "5\n8"
        }

        test("intersect — contained range") {
            run("""
                let a = 1..10
                let b = 3..7
                let r = a.intersect(b)
                say r.start
                say r.end
            """.trimIndent()) shouldBe "3\n7"
        }

        test("intersect — non-overlapping returns nil") {
            run("""
                let a = 1..3
                let b = 5..8
                say a.intersect(b)
            """.trimIndent()) shouldBe "nil"
        }
    }

    // ====================================================================
    // 15. Char Ranges
    // ====================================================================

    context("Char ranges") {

        test("char range toList") {
            run("""
                let r = 'a'..'e'
                say r.toList()
            """.trimIndent()) shouldBe "[a, b, c, d, e]"
        }

        test("char range exclusive end") {
            run("""
                let r = 'a'..<'e'
                say r.toList()
            """.trimIndent()) shouldBe "[a, b, c, d]"
        }

        test("char range iteration") {
            run("""
                for c in 'a'..'d' {
                    say c
                }
            """.trimIndent()) shouldBe "a\nb\nc\nd"
        }

        test("char range reversed") {
            run("""
                let r = 'z'..'v'
                say r.toList()
            """.trimIndent()) shouldBe "[z, y, x, w, v]"
        }

        test("char range contains") {
            run("""
                let r = 'a'..'z'
                say r.contains('m')
                say r.contains('A')
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("char range count") {
            run("""
                let r = 'a'..'z'
                say r.count()
            """.trimIndent()) shouldBe "26"
        }

        test("char in range") {
            run("say 'c' in 'a'..'z'") shouldBe "true"
        }
    }

    // ====================================================================
    // 16. Long Ranges
    // ====================================================================

    context("Long ranges") {

        test("Long range toList") {
            run("""
                let r = 1L..5L
                say r.toList()
            """.trimIndent()) shouldBe "[1, 2, 3, 4, 5]"
        }

        test("Long range count") {
            run("""
                let r = 1L..100L
                say r.count()
            """.trimIndent()) shouldBe "100"
        }

        test("Long range contains") {
            run("""
                let r = 1L..100L
                say r.contains(50L)
            """.trimIndent()) shouldBe "true"
        }
    }

    // ====================================================================
    // 17. Range in when Expressions
    // ====================================================================

    context("Range in when expressions") {

        test("when with in-range checks") {
            run("""
                let grade = 85
                let letter = when {
                    grade in 90..100 -> "A"
                    grade in 80..<90 -> "B"
                    grade in 70..<80 -> "C"
                    else -> "F"
                }
                say letter
            """.trimIndent()) shouldBe "B"
        }

        test("when with multiple range branches") {
            run("""
                fun classify(n: Int): String {
                    return when {
                        n in 1..10 -> "small"
                        n in 11..100 -> "medium"
                        n in 101..1000 -> "large"
                        else -> "huge"
                    }
                }
                say classify(5)
                say classify(50)
                say classify(500)
                say classify(5000)
            """.trimIndent()) shouldBe "small\nmedium\nlarge\nhuge"
        }
    }

    // ====================================================================
    // 18. Range as Function Parameter and Return
    // ====================================================================

    context("Range as function parameter and return") {

        test("pass range to function") {
            run("""
                fun sumRange(r): Int {
                    var total = 0
                    for i in r { total = total + i }
                    return total
                }
                say sumRange(1..5)
            """.trimIndent()) shouldBe "15"
        }

        test("return range from function") {
            run("""
                fun makeRange(start: Int, end: Int) = start..end
                let r = makeRange(3, 7)
                say r.start
                say r.end
                say r.toList()
            """.trimIndent()) shouldBe "3\n7\n[3, 4, 5, 6, 7]"
        }
    }

    // ====================================================================
    // 19. Range Reflection
    // ====================================================================

    context("Range reflection properties") {

        test("range .type") {
            run("""
                let r = 1..5
                say r.type
            """.trimIndent()) shouldBe "Range"
        }

        test("range .typeName") {
            run("""
                let r = 1..5
                say r.typeName
            """.trimIndent()) shouldBe "Range"
        }
    }

    // ====================================================================
    // 20. RangeExt Kotlin-level API
    // ====================================================================

    context("RangeExt — Kotlin-level extension functions") {

        test("Int toList — inclusive") {
            val range = Range(1, 5, Bound.Inclusive)
            range.toList() shouldContainExactly listOf(1, 2, 3, 4, 5)
        }

        test("Int toList — exclusive end") {
            val range = Range(1, 5, Bound.ExclusiveEnd)
            range.toList() shouldContainExactly listOf(1, 2, 3, 4)
        }

        test("Int toList — exclusive start") {
            val range = Range(1, 5, Bound.ExclusiveStart)
            range.toList() shouldContainExactly listOf(2, 3, 4, 5)
        }

        test("Int toList — exclusive both") {
            val range = Range(1, 5, Bound.Exclusive)
            range.toList() shouldContainExactly listOf(2, 3, 4)
        }

        test("Int toList — reversed inclusive") {
            val range = Range(5, 1, Bound.Inclusive)
            range.toList() shouldContainExactly listOf(5, 4, 3, 2, 1)
        }

        test("Int toList — reversed exclusive end") {
            val range = Range(5, 1, Bound.ExclusiveEnd)
            range.toList() shouldContainExactly listOf(5, 4, 3, 2)
        }

        test("Int toList — reversed exclusive start") {
            val range = Range(5, 1, Bound.ExclusiveStart)
            range.toList() shouldContainExactly listOf(4, 3, 2, 1)
        }

        test("Int toList — reversed exclusive both") {
            val range = Range(5, 1, Bound.Exclusive)
            range.toList() shouldContainExactly listOf(4, 3, 2)
        }

        test("Int toList — with step") {
            val range = Range(0, 10, Bound.Inclusive)
            range.toList(step = 3) shouldContainExactly listOf(0, 3, 6, 9)
        }

        test("Int toList — empty range") {
            val range = Range(5, 6, Bound.Exclusive)
            range.toList() shouldContainExactly emptyList()
        }

        test("Char toList — lowercase letters") {
            val range = Range('a', 'f', Bound.Inclusive)
            range.toList() shouldContainExactly listOf('a', 'b', 'c', 'd', 'e', 'f')
        }

        test("Char toList — reversed") {
            val range = Range('f', 'a', Bound.Inclusive)
            range.toList() shouldContainExactly listOf('f', 'e', 'd', 'c', 'b', 'a')
        }

        test("Long toList") {
            val range = Range(1L, 5L, Bound.Inclusive)
            range.toList() shouldContainExactly listOf(1L, 2L, 3L, 4L, 5L)
        }

        test("Long toList — reversed") {
            val range = Range(5L, 1L, Bound.Inclusive)
            range.toList() shouldContainExactly listOf(5L, 4L, 3L, 2L, 1L)
        }

        test("asSequence — matches toList") {
            val range = Range(1, 10, Bound.Inclusive)
            range.asSequence().toList() shouldContainExactly range.toList()
        }

        test("asSequence — lazy evaluation") {
            val range = Range(1, 1_000_000, Bound.Inclusive)
            range.asSequence().take(5).toList() shouldContainExactly listOf(1, 2, 3, 4, 5)
        }

        test("count — inclusive") {
            Range(1, 10, Bound.Inclusive).count() shouldBe 10
        }

        test("count — exclusive end") {
            Range(1, 10, Bound.ExclusiveEnd).count() shouldBe 9
        }

        test("count — exclusive both") {
            Range(1, 10, Bound.Exclusive).count() shouldBe 8
        }

        test("count — with step") {
            Range(0, 10, Bound.Inclusive).count(step = 2) shouldBe 6
        }

        test("count — reversed") {
            Range(10, 1, Bound.Inclusive).count() shouldBe 10
        }

        test("count — empty range") {
            Range(5, 6, Bound.Exclusive).count() shouldBe 0
        }

        test("count matches toList size") {
            val range = Range(1, 20, Bound.ExclusiveEnd)
            range.count() shouldBe range.toList().size
        }

        test("count matches toList size — with step") {
            val range = Range(0, 100, Bound.Inclusive)
            range.count(step = 7) shouldBe range.toList(step = 7).size
        }

        test("open range — toList throws") {
            val range = Range(1, null, Bound.Inclusive)
            shouldThrow<IllegalArgumentException> {
                range.toList()
            }.message shouldContain "open"
        }

        test("open range — count throws") {
            val range = Range(null, 10, Bound.Inclusive)
            shouldThrow<IllegalArgumentException> {
                range.count()
            }.message shouldContain "open"
        }

        test("Double range — toList throws") {
            val range = Range(1.0, 5.0, Bound.Inclusive)
            shouldThrow<IllegalArgumentException> {
                range.toList()
            }.message shouldContain "discrete"
        }

        test("step less than 1 throws") {
            val range = Range(1, 10, Bound.Inclusive)
            shouldThrow<IllegalArgumentException> {
                range.toList(step = 0)
            }.message shouldContain "Step"
        }
    }

    // ====================================================================
    // 21. Range String Representation
    // ====================================================================

    context("Range string representation") {

        test("inclusive range toString") {
            run("""
                let r = 1..5
                say r
            """.trimIndent()) shouldBe "1..5"
        }

        test("exclusive end toString") {
            run("""
                let r = 1..<5
                say r
            """.trimIndent()) shouldBe "1..<5"
        }

        test("exclusive start toString") {
            run("""
                let r = 1<..5
                say r
            """.trimIndent()) shouldBe "1<..5"
        }

        test("exclusive both toString") {
            run("""
                let r = 1<..<5
                say r
            """.trimIndent()) shouldBe "1<..<5"
        }
    }

    // ====================================================================
    // 22. Complex Integration Tests
    // ====================================================================

    context("Integration tests") {

        test("range-based accumulator (Gauss sum)") {
            run("""
                var sum = 0
                for i in 1..100 {
                    sum = sum + i
                }
                say sum
            """.trimIndent()) shouldBe "5050"
        }

        test("range toList as function input") {
            run("""
                fun first(list): Int = list[0]
                fun last(list): Int = list[list.size - 1]
                let items = (1..10).toList()
                say first(items)
                say last(items)
            """.trimIndent()) shouldBe "1\n10"
        }

        test("range in class property") {
            run("""
                class Slider(let range, var value: Int) {
                    fun clamp() {
                        this.value = this.range.clamp(this.value)
                    }
                }
                let s = Slider(0..100, 150)
                s.clamp()
                say s.value
            """.trimIndent()) shouldBe "100"
        }

        test("range-based password length validator") {
            run("""
                fun isValidLength(password: String): Bool {
                    return password.length in 8..64
                }
                say isValidLength("short")
                say isValidLength("adequate1")
                say isValidLength("a")
            """.trimIndent()) shouldBe "false\ntrue\nfalse"
        }

        test("range with collect squares") {
            run("""
                var squares = []
                for i in 1..5 {
                    squares = squares + [i * i]
                }
                say squares
            """.trimIndent()) shouldBe "[1, 4, 9, 16, 25]"
        }

        test("nested ranges — multiplication table count") {
            run("""
                var count = 0
                for i in 1..3 {
                    for j in 1..3 {
                        count = count + 1
                    }
                }
                say count
            """.trimIndent()) shouldBe "9"
        }

        test("char range to build alphabet string") {
            run("""
                var s = ""
                for c in 'a'..'e' {
                    s = s + c
                }
                say s
            """.trimIndent()) shouldBe "abcde"
        }
    }

    // ====================================================================
    // 23. Error Conditions
    // ====================================================================

    context("Error conditions") {

        test("member not found on range") {
            val error = runExpectingError("""
                let r = 1..5
                r.nonexistent
            """.trimIndent())
            error shouldContain "nonexistent"
        }

        test("toList on non-discrete range throws") {
            val error = runExpectingError("""
                let r = 1.0..5.0
                r.toList()
            """.trimIndent())
            error shouldContain "discrete"
        }

        test("contains with no argument throws") {
            val error = runExpectingError("""
                let r = 1..10
                let c = r.contains
                c()
            """.trimIndent())
            error shouldContain "requires"
        }
    }
})