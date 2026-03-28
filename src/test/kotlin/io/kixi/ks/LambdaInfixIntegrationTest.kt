package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Integration tests for lambdas and infix functions working together.
 *
 * Validates that the lambda/HOF implementation coexists correctly with:
 *   - Infix function declarations and calls
 *   - Block scoping (standalone `{ ... }` executes as a block)
 *   - Trailing lambda syntax on HOFs (map, filter, reduce, etc.)
 *   - Lambda storage in variables and closure capture
 *   - Chained HOF pipelines
 *   - Class/struct methods combined with HOFs
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.LambdaInfixIntegrationTest"
 */
class LambdaInfixIntegrationTest : FunSpec({

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
            errorWriter = PrintWriter(StringWriter(), true)
        )
        val interpreter = Interpreter(runtime)
        return interpreter.execute(source)
    }

    // ====================================================================
    // 1. Lambda Basics — Filter, Map, Chaining
    // ====================================================================

    context("Lambda HOFs") {

        test("filter with implicit it") {
            eval("[1, 2, 3, 4, 5, 6].filter { it > 3 }") shouldBe
                    mutableListOf(4, 5, 6)
        }

        test("map with implicit it") {
            eval("[1, 2, 3, 4, 5, 6].map { it * 10 }") shouldBe
                    mutableListOf(10, 20, 30, 40, 50, 60)
        }

        test("chained filter and map") {
            eval("[1, 2, 3, 4, 5, 6].filter { it % 2 == 0 }.map { it * it }") shouldBe
                    mutableListOf(4, 16, 36)
        }

        test("map with explicit param") {
            eval("[1, 2, 3].map { x -> x + 100 }") shouldBe
                    mutableListOf(101, 102, 103)
        }

        test("reduce with two params") {
            eval("[1, 2, 3, 4].reduce { acc, x -> acc + x }") shouldBe 10
        }

        test("forEach with output") {
            val result = run("""
                [10, 20, 30].forEach { say it }
            """.trimIndent())
            result shouldBe "10\n20\n30"
        }
    }

    // ====================================================================
    // 2. Infix Functions
    // ====================================================================

    context("Infix functions") {

        test("basic infix call on class") {
            val result = run("""
                class Vec(let x: Int, let y: Int) {
                    infix fun dot(other: Vec): Int = this.x * other.x + this.y * other.y
                }

                let a = Vec(1, 2)
                let b = Vec(3, 4)
                say a dot b
            """.trimIndent())
            result shouldBe "11"
        }

        test("infix and dot-call equivalence") {
            val result = run("""
                class Vec(let x: Int, let y: Int) {
                    infix fun dot(other: Vec): Int = this.x * other.x + this.y * other.y
                }

                let a = Vec(1, 2)
                let b = Vec(3, 4)
                say a dot b
                say a.dot(b)
            """.trimIndent())
            result shouldBe "11\n11"
        }

        test("infix on struct") {
            val result = run("""
                struct Point(let x: Int, let y: Int) {
                    infix fun add(other: Point): Point = Point(this.x + other.x, this.y + other.y)
                }

                let p = Point(1, 2) add Point(3, 4)
                say p.x
                say p.y
            """.trimIndent())
            result shouldBe "4\n6"
        }

        test("infix chaining is left-associative") {
            val result = run("""
                class Num(let v: Int) {
                    infix fun plus(other: Num): Num = Num(this.v + other.v)
                }

                let a = Num(1)
                let b = Num(2)
                let c = Num(3)
                let result = a plus b plus c
                say result.v
            """.trimIndent())
            result shouldBe "6"
        }
    }

    // ====================================================================
    // 3. Infix + Lambda Integration
    // ====================================================================

    context("Infix and lambda integration") {

        test("class methods with map lambda") {
            val result = run("""
                class Vec(let x: Int, let y: Int) {
                    fun label(): String = "(" + x + ", " + y + ")"
                }

                let vecs = [Vec(1, 0), Vec(0, 1), Vec(1, 1)]
                say vecs.map { it.label() }
            """.trimIndent())
            result shouldBe "[(1, 0), (0, 1), (1, 1)]"
        }

        test("infix result used in HOF chain") {
            val result = run("""
                class Vec(let x: Int, let y: Int) {
                    infix fun add(other: Vec): Vec = Vec(this.x + other.x, this.y + other.y)
                }

                let a = Vec(1, 2)
                let b = Vec(3, 4)
                let c = a add b
                say [c.x, c.y].map { it * 10 }
            """.trimIndent())
            result shouldBe "[40, 60]"
        }

        test("filter objects then call methods") {
            val result = run("""
                class Item(let name: String, let price: Int) {
                    fun label(): String = name + ": " + price
                }

                let items = [Item("Apple", 2), Item("Book", 15), Item("Pen", 1)]
                let expensive = items.filter { it.price > 5 }
                say expensive.map { it.label() }
            """.trimIndent())
            result shouldBe "[Book: 15]"
        }
    }

    // ====================================================================
    // 4. Block Scoping (non-interference)
    // ====================================================================

    context("Block scoping with lambdas") {

        test("standalone block still creates scope") {
            val result = run("""
                var x = 1
                {
                    var y = 2
                    say x
                    say y
                }
                say x
            """.trimIndent())
            result shouldBe "1\n2\n1"
        }

        test("block scope shadows outer variable") {
            val result = run("""
                var x = 1
                {
                    var x = 99
                    say x
                }
                say x
            """.trimIndent())
            result shouldBe "99\n1"
        }

        test("block scope assignment modifies outer") {
            val result = run("""
                var x = 1
                {
                    x = 42
                }
                say x
            """.trimIndent())
            result shouldBe "42"
        }

        test("lambda stored in variable does not execute immediately") {
            val result = run("""
                var counter = 0
                let inc = { -> counter = counter + 1 }
                say counter
                inc()
                say counter
                inc()
                say counter
            """.trimIndent())
            result shouldBe "0\n1\n2"
        }
    }

    // ====================================================================
    // 5. Closures and Higher-Order Patterns
    // ====================================================================

    context("Closures") {

        test("lambda captures enclosing variable") {
            eval("""
                var factor = 10
                let result = [1, 2, 3].map { it * factor }
                result
            """.trimIndent()) shouldBe mutableListOf(10, 20, 30)
        }

        test("function returning lambda as closure") {
            val result = run("""
                fun multiplier(n: Int): Any {
                    return { x: Int -> x * n }
                }

                let triple = multiplier(3)
                say triple(5)
                say triple(10)
            """.trimIndent())
            result shouldBe "15\n30"
        }

        test("nested closures") {
            val result = run("""
                var total = 0
                [1, 2, 3].forEach {
                    let n = it
                    [10, 20].forEach {
                        total = total + n * it
                    }
                }
                say total
            """.trimIndent())
            // 1*10 + 1*20 + 2*10 + 2*20 + 3*10 + 3*20 = 10+20+20+40+30+60 = 180
            result shouldBe "180"
        }
    }

    // ====================================================================
    // 6. Control Flow Non-Interference
    // ====================================================================

    context("Control flow unaffected") {

        test("if/else with block bodies") {
            val result = run("""
                let x = 10
                if x > 5 {
                    say "big"
                } else {
                    say "small"
                }
            """.trimIndent())
            result shouldBe "big"
        }

        test("while with block body") {
            val result = run("""
                var i = 0
                while i < 3 {
                    i = i + 1
                }
                say i
            """.trimIndent())
            result shouldBe "3"
        }

        test("for with block body") {
            val result = run("""
                var sum = 0
                for i in [1, 2, 3] {
                    sum = sum + i
                }
                say sum
            """.trimIndent())
            result shouldBe "6"
        }

        test("function with block body") {
            val result = run("""
                fun greet(name: String): String {
                    return "Hello, " + name + "!"
                }
                say greet("World")
            """.trimIndent())
            result shouldBe "Hello, World!"
        }
    }
})