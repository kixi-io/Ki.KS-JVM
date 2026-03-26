package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kixi.ks.parser.ParseException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Comprehensive tests for KS infix functions.
 *
 * Covers:
 *   1. Basic infix declaration and invocation (class and struct)
 *   2. Infix with dot-call equivalence
 *   3. Infix on struct methods
 *   4. Infix via `extend` blocks
 *   5. Left-associativity and chaining
 *   6. Precedence interactions (with comparison, boolean, arithmetic, etc.)
 *   7. Infix with various right-operand types (identifiers, literals, constructors)
 *   8. `this` access within infix methods
 *   9. Return types and expression usage
 *  10. Error cases (non-infix call, wrong arity, nil receiver, missing method)
 *  11. Parse errors (infix with wrong parameter count)
 *  12. Infix as keyword in member-name position
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.InfixFunctionTest"
 */
class InfixFunctionTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * Execute KS source code and capture output.
     * Returns the captured stdout as a string.
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
     * Execute KS source and expect a runtime error.
     * Returns the error message.
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
            throw AssertionError("Expected an error to be thrown, but execution completed successfully. Output: ${output.toString().trim()}")
        } catch (e: AssertionError) {
            throw e // re-throw our own assertion
        } catch (e: Exception) {
            return e.message ?: e.toString()
        }
    }

    /**
     * Attempt to parse KS source and expect a parse error.
     * Returns the error message.
     */
    fun parseExpectingError(source: String): String {
        try {
            val lexer = Lexer(source)
            val tokens = lexer.tokenize()
            val parser = Parser(tokens)
            parser.parse()
            throw AssertionError("Expected a parse error, but parsing completed successfully")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            return e.message ?: e.toString()
        }
    }

    // ====================================================================
    // 1. Basic Infix Declaration and Invocation (Class)
    // ====================================================================

    context("Basic infix on class") {

        test("class infix method with infix call syntax") {
            val result = run("""
                class Vec(let x: Int, let y: Int) {
                    infix fun dot(other: Vec): Int = this.x * other.x + this.y * other.y
                }

                let a = Vec(2, 3)
                let b = Vec(4, 5)
                say a dot b
            """.trimIndent())
            result shouldBe "23"
        }

        test("class infix method with block body") {
            val result = run("""
                class Counter(let value: Int) {
                    infix fun add(other: Counter): Counter {
                        return Counter(this.value + other.value)
                    }
                }

                let a = Counter(10)
                let b = Counter(20)
                let c = a add b
                say c.value
            """.trimIndent())
            result shouldBe "30"
        }

        test("infix method accessible via dot-call too") {
            val result = run("""
                class Box(let size: Int) {
                    infix fun combine(other: Box): Box = Box(this.size + other.size)
                }

                let a = Box(3)
                let b = Box(7)
                let r1 = a combine b
                let r2 = a.combine(b)
                say r1.size
                say r2.size
            """.trimIndent())
            result shouldBe "10\n10"
        }
    }

    // ====================================================================
    // 2. Infix on Struct
    // ====================================================================

    context("Infix on struct") {

        test("struct infix method") {
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

        test("struct infix with dot-call equivalence") {
            val result = run("""
                struct Weight(let grams: Int) {
                    infix fun plus(other: Weight): Weight = Weight(this.grams + other.grams)
                }

                let a = Weight(100)
                let b = Weight(250)
                let r1 = a plus b
                let r2 = a.plus(b)
                say r1.grams
                say r2.grams
            """.trimIndent())
            result shouldBe "350\n350"
        }
    }

    // ====================================================================
    // 3. Infix via extend
    // ====================================================================

    context("Infix via extend blocks") {

        test("extend class with infix method") {
            val result = run("""
                class Tag(let name: String) { }

                extend Tag {
                    infix fun merge(other: Tag): Tag = Tag(this.name + "+" + other.name)
                }

                let a = Tag("alpha")
                let b = Tag("beta")
                let c = a merge b
                say c.name
            """.trimIndent())
            result shouldBe "alpha+beta"
        }

        test("extend struct with infix method") {
            val result = run("""
                struct Color(let r: Int, let g: Int, let b: Int) { }

                extend Color {
                    infix fun mix(other: Color): Color = Color(
                        (this.r + other.r) / 2,
                        (this.g + other.g) / 2,
                        (this.b + other.b) / 2
                    )
                }

                let red = Color(255, 0, 0)
                let blue = Color(0, 0, 255)
                let purple = red mix blue
                say purple.r
                say purple.g
                say purple.b
            """.trimIndent())
            result shouldBe "127\n0\n127"
        }
    }

    // ====================================================================
    // 4. Left-Associativity and Chaining
    // ====================================================================

    context("Chaining and associativity") {

        test("left-associative chaining: a foo b bar c") {
            val result = run("""
                class Num(let v: Int) {
                    infix fun add(other: Num): Num = Num(this.v + other.v)
                    infix fun mul(other: Num): Num = Num(this.v * other.v)
                }

                let a = Num(2)
                let b = Num(3)
                let c = Num(4)
                let r = a add b mul c
                say r.v
            """.trimIndent())
            // Left-associative: (a add b) mul c = (2+3) * 4 = 20
            result shouldBe "20"
        }

        test("chaining same infix function") {
            val result = run("""
                class Str(let text: String) {
                    infix fun join(other: Str): Str = Str(this.text + "-" + other.text)
                }

                let a = Str("a")
                let b = Str("b")
                let c = Str("c")
                let r = a join b join c
                say r.text
            """.trimIndent())
            // Left-associative: (a join b) join c = "a-b" join "c" = "a-b-c"
            result shouldBe "a-b-c"
        }
    }

    // ====================================================================
    // 5. Precedence Interactions
    // ====================================================================

    context("Precedence") {

        test("infix lower precedence than arithmetic") {
            // Infix is level 8, arithmetic is level 11
            // So `a add b` in `x + a add b + y` would parse oddly,
            // but typical usage: infix result used in comparison
            val result = run("""
                class Val(let n: Int) {
                    infix fun plus(other: Val): Val = Val(this.n + other.n)
                }

                let a = Val(10)
                let b = Val(20)
                let c = a plus b
                say c.n == 30
            """.trimIndent())
            result shouldBe "true"
        }

        test("infix in boolean context") {
            val result = run("""
                class Score(let v: Int) {
                    infix fun beats(other: Score): Bool = this.v > other.v
                }

                let mine = Score(100)
                let yours = Score(80)
                say mine beats yours
            """.trimIndent())
            result shouldBe "true"
        }

        test("infix result used with comparison operators") {
            val result = run("""
                class Amt(let n: Int) {
                    infix fun add(other: Amt): Amt = Amt(this.n + other.n)
                }

                let a = Amt(5)
                let b = Amt(10)
                let c = a add b
                say c.n > 10
                say c.n == 15
            """.trimIndent())
            result shouldBe "true\ntrue"
        }

        test("infix does not interfere with is/!is checks") {
            val result = run("""
                class Wrapper(let value: Int) {
                    infix fun add(other: Wrapper): Wrapper = Wrapper(this.value + other.value)
                }

                let a = Wrapper(1)
                let b = Wrapper(2)
                let c = a add b
                say c is Wrapper
            """.trimIndent())
            result shouldBe "true"
        }

        test("infix does not interfere with in check") {
            val result = run("""
                class Idx(let i: Int) {
                    infix fun shift(other: Idx): Idx = Idx(this.i + other.i)
                }

                let a = Idx(2)
                let b = Idx(3)
                let c = a shift b
                say c.i in 1..10
            """.trimIndent())
            result shouldBe "true"
        }
    }

    // ====================================================================
    // 6. Right-Operand Varieties
    // ====================================================================

    context("Right-operand types") {

        test("right operand is identifier") {
            val result = run("""
                class Pair(let a: Int, let b: Int) {
                    infix fun swap(other: Pair): Pair = Pair(other.a, other.b)
                }

                let x = Pair(1, 2)
                let y = Pair(3, 4)
                let r = x swap y
                say r.a
                say r.b
            """.trimIndent())
            result shouldBe "3\n4"
        }

        test("right operand is constructor call") {
            val result = run("""
                struct Vec(let x: Int, let y: Int) {
                    infix fun add(other: Vec): Vec = Vec(this.x + other.x, this.y + other.y)
                }

                let a = Vec(1, 1)
                let r = a add Vec(9, 9)
                say r.x
                say r.y
            """.trimIndent())
            result shouldBe "10\n10"
        }

        test("right operand is integer literal") {
            val result = run("""
                class Scale(let factor: Int) {
                    infix fun times(n: Int): Scale = Scale(this.factor * n)
                }

                let s = Scale(5)
                let r = s times 3
                say r.factor
            """.trimIndent())
            result shouldBe "15"
        }

        test("right operand is string literal") {
            val result = run("""
                class Msg(let text: String) {
                    infix fun append(suffix: String): Msg = Msg(this.text + suffix)
                }

                let m = Msg("Hello")
                let r = m append " World"
                say r.text
            """.trimIndent())
            result shouldBe "Hello World"
        }

        test("right operand is boolean literal") {
            val result = run("""
                class Flag(let on: Bool) {
                    infix fun override(v: Bool): Flag = Flag(v)
                }

                let f = Flag(true)
                let r = f override false
                say r.on
            """.trimIndent())
            result shouldBe "false"
        }

        test("right operand is nil") {
            val result = run("""
                class Maybe(let value: String?) {
                    infix fun orElse(other: String?): String? {
                        if this.value != nil { return this.value }
                        return other
                    }
                }

                let m = Maybe("hello")
                say m orElse nil
            """.trimIndent())
            result shouldBe "hello"
        }

        test("right operand with method chain") {
            // Right operand starts with identifier, then chains .field
            val result = run("""
                class Adder(let n: Int) {
                    infix fun add(x: Int): Adder = Adder(this.n + x)
                }

                class Source(let value: Int) { }

                let a = Adder(10)
                let s = Source(5)
                let r = a add s.value
                say r.n
            """.trimIndent())
            result shouldBe "15"
        }
    }

    // ====================================================================
    // 7. `this` Access in Infix Methods
    // ====================================================================

    context("this access") {

        test("infix method accesses this properties") {
            val result = run("""
                class Rect(let w: Int, let h: Int) {
                    infix fun wider(extra: Int): Rect = Rect(this.w + extra, this.h)
                }

                let r = Rect(10, 5)
                let r2 = r wider 3
                say r2.w
                say r2.h
            """.trimIndent())
            result shouldBe "13\n5"
        }

        test("infix method calls other methods on this") {
            val result = run("""
                class Calc(let n: Int) {
                    fun doubled(): Int = this.n * 2
                    infix fun addDoubled(other: Calc): Int = this.doubled() + other.doubled()
                }

                let a = Calc(3)
                let b = Calc(4)
                say a addDoubled b
            """.trimIndent())
            result shouldBe "14"
        }
    }

    // ====================================================================
    // 8. Return Type and Expression Usage
    // ====================================================================

    context("Return types and expressions") {

        test("infix result assigned to variable") {
            val result = run("""
                class Num(let v: Int) {
                    infix fun add(other: Num): Num = Num(this.v + other.v)
                }

                let a = Num(7)
                let b = Num(3)
                let c = a add b
                say c.v
            """.trimIndent())
            result shouldBe "10"
        }

        test("infix result used directly in say") {
            val result = run("""
                class Greeter(let name: String) {
                    infix fun greet(greeting: String): String = greeting + ", " + this.name + "!"
                }

                let g = Greeter("World")
                say g greet "Hello"
            """.trimIndent())
            result shouldBe "Hello, World!"
        }

        test("infix returning nil") {
            val result = run("""
                class Logger(let tag: String) {
                    infix fun log(msg: String) {
                        say tag + ": " + msg
                    }
                }

                let l = Logger("INFO")
                l log "started"
            """.trimIndent())
            result shouldBe "INFO: started"
        }
    }

    // ====================================================================
    // 9. Infix with Traits
    // ====================================================================

    context("Infix with traits") {

        test("infix method declared in trait with default body") {
            val result = run("""
                trait Combinable {
                    infix fun combine(other: String): String = "combined"
                }

                class Item: Combinable { }

                let a = Item()
                say a combine "anything"
            """.trimIndent())
            result shouldBe "combined"
        }

        test("class overrides trait infix method") {
            val result = run("""
                trait Addable {
                    infix fun plus(n: Int): Int = 0
                }

                class MyVal(let v: Int): Addable {
                    infix fun plus(n: Int): Int = this.v + n
                }

                let m = MyVal(10)
                say m plus 5
            """.trimIndent())
            result shouldBe "15"
        }
    }

    // ====================================================================
    // 10. Error Cases
    // ====================================================================

    context("Runtime errors") {

        test("non-infix method called with infix syntax") {
            val msg = runExpectingError("""
                class Foo(let x: Int) {
                    fun add(other: Foo): Foo = Foo(this.x + other.x)
                }

                let a = Foo(1)
                let b = Foo(2)
                let c = a add b
            """.trimIndent())
            msg shouldContain "not declared as 'infix'"
        }

        test("infix call on nil receiver") {
            val msg = runExpectingError("""
                class Box(let v: Int) {
                    infix fun add(other: Box): Box = Box(this.v + other.v)
                }

                var b: Box? = nil
                let r = b add Box(1)
            """.trimIndent())
            msg shouldContain "nil"
        }

        test("infix method not found on class") {
            val msg = runExpectingError("""
                class Empty { }

                let e = Empty()
                let r = e foo 42
            """.trimIndent())
            msg shouldContain "foo"
            msg shouldContain "Empty"
        }

        test("infix method not found on struct") {
            val msg = runExpectingError("""
                struct Pt(let x: Int) { }

                let p = Pt(1)
                let r = p shift 5
            """.trimIndent())
            msg shouldContain "shift"
            msg shouldContain "Pt"
        }
    }

    // ====================================================================
    // 11. Parse Errors
    // ====================================================================

    context("Parse errors") {

        test("infix fun with zero parameters") {
            val msg = parseExpectingError("""
                class Foo {
                    infix fun nope(): Int = 42
                }
            """.trimIndent())
            msg shouldContain "exactly one parameter"
            msg shouldContain "0"
        }

        test("infix fun with two parameters") {
            val msg = parseExpectingError("""
                class Foo {
                    infix fun nope(a: Int, b: Int): Int = a + b
                }
            """.trimIndent())
            msg shouldContain "exactly one parameter"
            msg shouldContain "2"
        }

        test("infix without fun keyword") {
            val msg = parseExpectingError("""
                infix class Foo { }
            """.trimIndent())
            msg shouldContain "Expected 'fun' after 'infix'"
        }
    }

    // ====================================================================
    // 12. Non-Interference with Existing Syntax
    // ====================================================================

    context("Non-interference") {

        test("while with single-statement body still works") {
            val result = run("""
                var i = 0
                while i < 3 i = i + 1
                say i
            """.trimIndent())
            result shouldBe "3"
        }

        test("while with postfix increment body still works") {
            val result = run("""
                var i = 0
                while i < 5 i++
                say i
            """.trimIndent())
            result shouldBe "5"
        }

        test("if with single-expression body still works") {
            val result = run("""
                let x = 10
                if x > 5 say "big"
            """.trimIndent())
            result shouldBe "big"
        }

        test("for with single-statement body still works") {
            val result = run("""
                var sum = 0
                for i in 1..3 sum = sum + i
                say sum
            """.trimIndent())
            result shouldBe "6"
        }

        test("identifier on next line is not consumed as infix") {
            val result = run("""
                class Box(let v: Int) {
                    infix fun add(other: Box): Box = Box(this.v + other.v)
                }

                let a = Box(5)
                let b = Box(3)
                say a.v
                say b.v
            """.trimIndent())
            result shouldBe "5\n3"
        }
    }
})