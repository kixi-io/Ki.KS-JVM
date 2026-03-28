package io.kixi.ks

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import io.kixi.ks.interp.Interpreter

/**
 * Tests for lambda expressions and higher-order functions.
 *
 * Covers:
 *   - Lambda syntax (implicit it, explicit params, typed params, zero-arg)
 *   - Trailing lambda syntax
 *   - Closure capture / lexical scoping
 *   - Lambda stored in variables
 *   - Lambda as function argument
 *   - List HOFs: map, filter, forEach, reduce, fold, flatMap,
 *     take, drop, takeWhile, dropWhile, any, all, none
 *   - Chained HOFs
 *   - Edge cases (empty lists, single element, type errors)
 */
class LambdaTest : StringSpec({

    // ================================================================
    // Test Helpers
    // ================================================================

    fun eval(source: String): Any? {
        val output = java.io.StringWriter()
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = java.io.PrintWriter(output, true),
            errorWriter = java.io.PrintWriter(java.io.StringWriter(), true)
        )
        val interpreter = Interpreter(runtime)
        return interpreter.execute(source)
    }

    fun evalWithOutput(source: String): Pair<Any?, String> {
        val output = java.io.StringWriter()
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = java.io.PrintWriter(output, true),
            errorWriter = java.io.PrintWriter(java.io.StringWriter(), true)
        )
        val interpreter = Interpreter(runtime)
        val result = interpreter.execute(source)
        return result to output.toString().trim()
    }

    // ================================================================
    // 1. Lambda Syntax — Implicit `it`
    // ================================================================

    "lambda with implicit it - map" {
        eval("[1, 2, 3].map { it * 2 }") shouldBe mutableListOf(2, 4, 6)
    }

    "lambda with implicit it - filter" {
        eval("[1, 2, 3, 4, 5].filter { it > 3 }") shouldBe mutableListOf(4, 5)
    }

    "lambda with implicit it - complex expression" {
        eval("[1, 2, 3].map { it * it + 1 }") shouldBe mutableListOf(2, 5, 10)
    }

    // ================================================================
    // 2. Lambda Syntax — Explicit Parameters
    // ================================================================

    "lambda with single explicit param" {
        eval("[1, 2, 3].map { x -> x * 10 }") shouldBe mutableListOf(10, 20, 30)
    }

    "lambda with two explicit params — reduce" {
        eval("[1, 2, 3, 4].reduce { acc, x -> acc + x }") shouldBe 10
    }

    "lambda with typed params" {
        eval("[1, 2, 3].map { x: Int -> x + 100 }") shouldBe mutableListOf(101, 102, 103)
    }

    "lambda with mixed typed/untyped params" {
        eval("[1, 2, 3].fold(0) { acc: Int, x -> acc + x }") shouldBe 6
    }

    // ================================================================
    // 3. Lambda Syntax — Zero-Arg
    // ================================================================

    "zero-arg lambda with arrow" {
        val (_, output) = evalWithOutput("""
            let greet = { -> say "hello" }
            greet()
        """.trimIndent())
        output shouldBe "hello"
    }

    // ================================================================
    // 4. Lambda Stored in Variables
    // ================================================================

    "lambda assigned to variable and called" {
        eval("""
            let double = { x -> x * 2 }
            double(5)
        """.trimIndent()) shouldBe 10
    }

    "lambda assigned to var - reassigned" {
        eval("""
            var fn = { x -> x + 1 }
            fn = { x -> x + 10 }
            fn(5)
        """.trimIndent()) shouldBe 15
    }

    "implicit-it lambda stored and called" {
        eval("""
            let negate = { -it }
            negate(42)
        """.trimIndent()) shouldBe -42
    }

    // ================================================================
    // 5. Closure / Lexical Scoping
    // ================================================================

    "lambda captures enclosing variable" {
        eval("""
            var x = 10
            let fn = { x + 1 }
            x = 20
            fn()
        """.trimIndent()) shouldBe 21
    }

    "lambda captures loop variable" {
        eval("""
            var fns = []
            for i in 0..2 {
                var captured = i
                fns = fns + [{ captured }]
            }
            [fns[0](), fns[1](), fns[2]()]
        """.trimIndent()) shouldBe mutableListOf(0, 1, 2)
    }

    "nested lambda closures" {
        eval("""
            let adder = { x -> { y -> x + y } }
            let add5 = adder(5)
            add5(3)
        """.trimIndent()) shouldBe 8
    }

    // ================================================================
    // 6. Trailing Lambda Syntax
    // ================================================================

    "trailing lambda on method call" {
        eval("[1, 2, 3].map { it * 2 }") shouldBe mutableListOf(2, 4, 6)
    }

    "trailing lambda after parenthesized args — fold" {
        eval("[1, 2, 3].fold(0) { acc, x -> acc + x }") shouldBe 6
    }

    "trailing lambda after parenthesized args — fold with non-zero initial" {
        eval("[1, 2, 3].fold(100) { acc, x -> acc + x }") shouldBe 106
    }

    "trailing lambda does not consume if-body" {
        // The { x + 1 } must be the if-body, NOT a trailing lambda on x > 0
        eval("""
            var x = 5
            if x > 0 { x + 1 }
        """.trimIndent()) shouldBe 6
    }

    "trailing lambda does not consume while-body" {
        val (_, output) = evalWithOutput("""
            var i = 0
            while i < 3 {
                say i
                i = i + 1
            }
        """.trimIndent())
        output shouldBe "0\n1\n2"
    }

    "trailing lambda does not consume for-body" {
        val (_, output) = evalWithOutput("""
            for i in [1, 2, 3] { say i }
        """.trimIndent())
        output shouldBe "1\n2\n3"
    }

    "trailing lambda does not consume when-body" {
        eval("""
            let x = 5
            when x {
                5 -> "five"
                else -> "other"
            }
        """.trimIndent()) shouldBe "five"
    }

    // ================================================================
    // 7. List.map
    // ================================================================

    "map - basic transformation" {
        eval("[1, 2, 3].map { it * 2 }") shouldBe mutableListOf(2, 4, 6)
    }

    "map - to strings" {
        eval("""[1, 2, 3].map { x -> "n" + x }""") shouldBe mutableListOf("n1", "n2", "n3")
    }

    "map - empty list" {
        eval("[].map { it * 2 }") shouldBe mutableListOf<Any?>()
    }

    "map - returns new list (original unchanged)" {
        eval("""
            let nums = [1, 2, 3]
            let doubled = nums.map { it * 2 }
            nums
        """.trimIndent()) shouldBe mutableListOf(1, 2, 3)
    }

    // ================================================================
    // 8. List.filter
    // ================================================================

    "filter - basic predicate" {
        eval("[1, 2, 3, 4, 5].filter { it > 3 }") shouldBe mutableListOf(4, 5)
    }

    "filter - even numbers" {
        eval("[1, 2, 3, 4, 5, 6].filter { it % 2 == 0 }") shouldBe mutableListOf(2, 4, 6)
    }

    "filter - empty result" {
        eval("[1, 2, 3].filter { it > 10 }") shouldBe mutableListOf<Any?>()
    }

    "filter - empty list" {
        eval("[].filter { it > 0 }") shouldBe mutableListOf<Any?>()
    }

    // ================================================================
    // 9. List.forEach
    // ================================================================

    "forEach - side effects" {
        val (result, output) = evalWithOutput("""
            [1, 2, 3].forEach { say it }
        """.trimIndent())
        result shouldBe null
        output shouldBe "1\n2\n3"
    }

    "forEach - explicit param" {
        val (_, output) = evalWithOutput("""
            ["a", "b", "c"].forEach { item -> say item }
        """.trimIndent())
        output shouldBe "a\nb\nc"
    }

    "forEach - returns nil" {
        eval("[1, 2, 3].forEach { it }") shouldBe null
    }

    // ================================================================
    // 10. List.reduce
    // ================================================================

    "reduce - sum" {
        eval("[1, 2, 3, 4].reduce { acc, x -> acc + x }") shouldBe 10
    }

    "reduce - product" {
        eval("[1, 2, 3, 4].reduce { acc, x -> acc * x }") shouldBe 24
    }

    "reduce - single element" {
        eval("[42].reduce { acc, x -> acc + x }") shouldBe 42
    }

    "reduce - empty list throws" {
        shouldThrow<RuntimeException> {
            eval("[].reduce { acc, x -> acc + x }")
        }
    }

    "reduce - string concatenation" {
        eval("""["a", "b", "c"].reduce { acc, x -> acc + x }""") shouldBe "abc"
    }

    // ================================================================
    // 11. List.fold
    // ================================================================

    "fold - sum with initial value" {
        eval("[1, 2, 3].fold(0) { acc, x -> acc + x }") shouldBe 6
    }

    "fold - non-zero initial" {
        eval("[1, 2, 3].fold(100) { acc, x -> acc + x }") shouldBe 106
    }

    "fold - empty list returns initial" {
        eval("[].fold(42) { acc, x -> acc + x }") shouldBe 42
    }

    "fold - string accumulation" {
        eval("""[1, 2, 3].fold("numbers:") { acc, x -> acc + " " + x }""") shouldBe "numbers: 1 2 3"
    }

    // ================================================================
    // 12. List.flatMap
    // ================================================================

    "flatMap - basic" {
        eval("[1, 2, 3].flatMap { [it, it * 10] }") shouldBe
                mutableListOf(1, 10, 2, 20, 3, 30)
    }

    "flatMap - empty results" {
        eval("[1, 2, 3].flatMap { [] }") shouldBe mutableListOf<Any?>()
    }

    "flatMap - empty list" {
        eval("[].flatMap { [it, it] }") shouldBe mutableListOf<Any?>()
    }

    "flatMap - single element lists" {
        eval("[1, 2, 3].flatMap { [it] }") shouldBe mutableListOf(1, 2, 3)
    }

    // ================================================================
    // 13. List.take / List.drop
    // ================================================================

    "take - first n elements" {
        eval("[1, 2, 3, 4, 5].take(3)") shouldBe mutableListOf(1, 2, 3)
    }

    "take - more than size" {
        eval("[1, 2].take(10)") shouldBe mutableListOf(1, 2)
    }

    "take - zero" {
        eval("[1, 2, 3].take(0)") shouldBe mutableListOf<Any?>()
    }

    "drop - first n elements" {
        eval("[1, 2, 3, 4, 5].drop(2)") shouldBe mutableListOf(3, 4, 5)
    }

    "drop - more than size" {
        eval("[1, 2].drop(10)") shouldBe mutableListOf<Any?>()
    }

    "drop - zero" {
        eval("[1, 2, 3].drop(0)") shouldBe mutableListOf(1, 2, 3)
    }

    // ================================================================
    // 14. List.takeWhile / List.dropWhile
    // ================================================================

    "takeWhile - predicate" {
        eval("[1, 2, 3, 4, 5].takeWhile { it < 4 }") shouldBe mutableListOf(1, 2, 3)
    }

    "takeWhile - all match" {
        eval("[1, 2, 3].takeWhile { it < 10 }") shouldBe mutableListOf(1, 2, 3)
    }

    "takeWhile - none match" {
        eval("[5, 6, 7].takeWhile { it < 0 }") shouldBe mutableListOf<Any?>()
    }

    "dropWhile - predicate" {
        eval("[1, 2, 3, 4, 5].dropWhile { it < 3 }") shouldBe mutableListOf(3, 4, 5)
    }

    "dropWhile - all match" {
        eval("[1, 2, 3].dropWhile { it < 10 }") shouldBe mutableListOf<Any?>()
    }

    "dropWhile - none match" {
        eval("[5, 6, 7].dropWhile { it < 0 }") shouldBe mutableListOf(5, 6, 7)
    }

    // ================================================================
    // 15. List.any / List.all / List.none
    // ================================================================

    "any - some match" {
        eval("[1, 2, 3, 4, 5].any { it > 4 }") shouldBe true
    }

    "any - none match" {
        eval("[1, 2, 3].any { it > 10 }") shouldBe false
    }

    "any - empty list" {
        eval("[].any { it > 0 }") shouldBe false
    }

    "all - all match" {
        eval("[2, 4, 6].all { it % 2 == 0 }") shouldBe true
    }

    "all - some don't match" {
        eval("[2, 3, 6].all { it % 2 == 0 }") shouldBe false
    }

    "all - empty list" {
        eval("[].all { it > 0 }") shouldBe true
    }

    "none - none match" {
        eval("[1, 2, 3].none { it > 10 }") shouldBe true
    }

    "none - some match" {
        eval("[1, 2, 3].none { it > 2 }") shouldBe false
    }

    "none - empty list" {
        eval("[].none { it > 0 }") shouldBe true
    }

    // ================================================================
    // 16. Chained HOFs
    // ================================================================

    "filter then map" {
        eval("[1, 2, 3, 4, 5].filter { it > 2 }.map { it * 10 }") shouldBe
                mutableListOf(30, 40, 50)
    }

    "map then filter" {
        eval("[1, 2, 3, 4, 5].map { it * 2 }.filter { it > 6 }") shouldBe
                mutableListOf(8, 10)
    }

    "filter then reduce" {
        eval("[1, 2, 3, 4, 5].filter { it % 2 == 0 }.reduce { acc, x -> acc + x }") shouldBe 6
    }

    "map then take" {
        eval("[1, 2, 3, 4, 5].map { it * 10 }.take(3)") shouldBe
                mutableListOf(10, 20, 30)
    }

    "drop then map" {
        eval("[1, 2, 3, 4, 5].drop(2).map { it * 100 }") shouldBe
                mutableListOf(300, 400, 500)
    }

    "flatMap then filter" {
        eval("[1, 2, 3].flatMap { [it, it * 10] }.filter { it > 5 }") shouldBe
                mutableListOf(10, 20, 30)
    }

    // ================================================================
    // 17. Lambda with Named Functions (HOF interop)
    // ================================================================

    "named function passed to map via variable" {
        eval("""
            fun double(x) = x * 2
            let nums = [1, 2, 3]
            nums.map(double)
        """.trimIndent()) shouldBe mutableListOf(2, 4, 6)
    }

    "named function passed to filter" {
        eval("""
            fun isEven(x) = x % 2 == 0
            [1, 2, 3, 4, 5, 6].filter(isEven)
        """.trimIndent()) shouldBe mutableListOf(2, 4, 6)
    }

    // ================================================================
    // 18. Lambda as Return Value
    // ================================================================

    "function returning lambda" {
        eval("""
            fun multiplier(factor) = { x -> x * factor }
            let triple = multiplier(3)
            triple(7)
        """.trimIndent()) shouldBe 21
    }

    "function returning lambda - closure over argument" {
        eval("""
            fun makeGreeter(greeting) = { name -> greeting + ", " + name }
            let hi = makeGreeter("Hi")
            let hello = makeGreeter("Hello")
            hi("Alice") + " / " + hello("Bob")
        """.trimIndent()) shouldBe "Hi, Alice / Hello, Bob"
    }

    // ================================================================
    // 19. Lambda with Multi-Statement Body
    // ================================================================

    "multi-statement lambda body" {
        eval("""
            [1, 2, 3].map { x ->
                let doubled = x * 2
                let plusOne = doubled + 1
                plusOne
            }
        """.trimIndent()) shouldBe mutableListOf(3, 5, 7)
    }

    "multi-statement lambda with early return" {
        eval("""
            let classify = { x ->
                if x > 0 return "positive"
                if x < 0 return "negative"
                "zero"
            }
            [classify(-1), classify(0), classify(1)]
        """.trimIndent()) shouldBe mutableListOf("negative", "zero", "positive")
    }

    // ================================================================
    // 20. Lambda .type / .typeName
    // ================================================================

    "lambda .typeName" {
        eval("""
            let fn = { it * 2 }
            fn.typeName
        """.trimIndent()) shouldBe "Lambda"
    }

    // ================================================================
    // 21. Edge Cases
    // ================================================================

    "lambda with no body expressions returns nil" {
        // A lambda like { -> } has no statements, result should be nil
        eval("""
            let empty = { -> }
            empty()
        """.trimIndent()) shouldBe null
    }

    "lambda used inline without variable" {
        eval("""
            let result = { x -> x + 1 }(41)
            result
        """.trimIndent()) shouldBe 42
    }

    "map with nil elements" {
        eval("[1, nil, 3].map { it }") shouldBe mutableListOf(1, null, 3)
    }

    "filter with nil elements" {
        eval("[1, nil, 3, nil, 5].filter { it != nil }") shouldBe mutableListOf(1, 3, 5)
    }

    "forEach on empty list" {
        val (result, output) = evalWithOutput("[].forEach { say it }")
        result shouldBe null
        output shouldBe ""
    }

    "fold with different accumulator type" {
        eval("""[1, 2, 3, 4, 5].fold("") { acc, x -> if acc == "" acc + x else acc + ", " + x }""") shouldBe
                "1, 2, 3, 4, 5"
    }
})