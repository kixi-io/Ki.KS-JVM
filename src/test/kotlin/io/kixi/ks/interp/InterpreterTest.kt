package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Comprehensive tests for the KS Interpreter.
 *
 * Covers:
 *   - Literals (Int, Long, Float, Double, Dec, String, Char, Bool, Nil)
 *   - Variable declarations (var/let, type annotations, constraints)
 *   - Arithmetic, comparison, logical, and special operators
 *   - Unary operators (-, !, ++, --, !!)
 *   - String interpolation and string operations
 *   - Control flow (if/else, when, for, while, break, continue)
 *   - Functions (declarations, closures, recursion, default params)
 *   - Collections (List, Map)
 *   - Ranges and in-checks
 *   - Type checking (is/!is) and casting (as)
 *   - Classes (instantiation, properties, methods, static members)
 *   - Enums (declaration, DPEC, member access, iteration)
 *   - Structs (value semantics, copy, equality)
 *   - Try/catch/finally
 *   - Say variants (say, say.error, say.warn, say.note)
 *   - Reflection (::class)
 *   - Ternary expressions
 *   - Elvis operator (?:)
 *   - Compound assignment (+=, -=, *=, /=, %=, **=)
 *   - Built-in member access (String, List, Map)
 *   - Edge cases and error conditions
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.InterpreterTest"
 */
class InterpreterTest : FunSpec({

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
     * Execute KS source code and capture stderr output.
     */
    fun runError(source: String): String {
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
        return error.toString().trim()
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
    // 1. Literals
    // ====================================================================

    context("Literals") {

        test("integer literal") {
            run("say 42") shouldBe "42"
        }

        test("negative integer literal") {
            run("say -7") shouldBe "-7"
        }

        test("long literal") {
            run("say 100L") shouldBe "100"
        }

        test("float literal") {
            run("say 3.14f") shouldBe "3.14"
        }

        test("double literal") {
            run("say 2.718") shouldBe "2.718"
        }

        test("boolean literals") {
            run("""
                say true
                say false
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("nil literal") {
            run("say nil") shouldBe "nil"
        }

        test("string literal") {
            run("say \"hello world\"") shouldBe "hello world"
        }

        test("char literal") {
            run("say 'A'") shouldBe "A"
        }
    }

    // ====================================================================
    // 2. Variable Declarations
    // ====================================================================

    context("Variable declarations") {

        test("var declaration with initializer") {
            run("""
                var x = 10
                say x
            """.trimIndent()) shouldBe "10"
        }

        test("let declaration (immutable)") {
            run("""
                let name = "Alice"
                say name
            """.trimIndent()) shouldBe "Alice"
        }

        test("var can be reassigned") {
            run("""
                var x = 1
                x = 2
                say x
            """.trimIndent()) shouldBe "2"
        }

        test("let cannot be reassigned") {
            val error = runExpectingError("""
                let x = 1
                x = 2
            """.trimIndent())
            error shouldContain "immutable"
        }

        test("var with type annotation") {
            run("""
                var age: Int = 25
                say age
            """.trimIndent()) shouldBe "25"
        }

        test("var with constraint") {
            val error = runExpectingError("""
                var age: Int > 0 = -5
            """.trimIndent())
            error shouldContain "Constraint"
        }

        test("constraint checked on assignment") {
            val error = runExpectingError("""
                var score: Int > 0 = 10
                score = -1
            """.trimIndent())
            error shouldContain "Constraint"
        }

        test("nil initializer") {
            run("""
                var x = nil
                say x
            """.trimIndent()) shouldBe "nil"
        }
    }

    // ====================================================================
    // 3. Arithmetic Operators
    // ====================================================================

    context("Arithmetic operators") {

        test("addition") {
            run("say 3 + 4") shouldBe "7"
        }

        test("subtraction") {
            run("say 10 - 3") shouldBe "7"
        }

        test("multiplication") {
            run("say 6 * 7") shouldBe "42"
        }

        test("integer division truncates") {
            run("say 7 / 2") shouldBe "3"
        }

        test("double division") {
            run("say 7.0 / 2.0") shouldBe "3.5"
        }

        test("modulo") {
            run("say 10 % 3") shouldBe "1"
        }

        test("power") {
            run("say 2 ** 10") shouldBe "1024"
        }

        test("operator precedence") {
            run("say 2 + 3 * 4") shouldBe "14"
        }

        test("parentheses override precedence") {
            run("say ((2 + 3) * 4)") shouldBe "20"
        }

        test("string concatenation with +") {
            run("say \"Hello\" + \" \" + \"World\"") shouldBe "Hello World"
        }

        test("string repetition with *") {
            run("""say "ha" * 3""") shouldBe "hahaha"
        }

        test("list concatenation with +") {
            run("say [1, 2] + [3, 4]") shouldBe "[1, 2, 3, 4]"
        }

        test("division by zero throws error") {
            val error = runExpectingError("say 1 / 0")
            error shouldContain "ivision by zero"
        }

        test("mixed int and double arithmetic promotes to double") {
            run("say 5 + 2.5") shouldBe "7.5"
        }
    }

    // ====================================================================
    // 4. Comparison Operators
    // ====================================================================

    context("Comparison operators") {

        test("equality") {
            run("""
                say 1 == 1
                say 1 == 2
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("inequality") {
            run("""
                say 1 != 2
                say 1 != 1
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("less than") {
            run("""
                say 1 < 2
                say 2 < 1
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("greater than") {
            run("""
                say 2 > 1
                say 1 > 2
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("less than or equal") {
            run("""
                say 1 <= 2
                say 2 <= 2
                say 3 <= 2
            """.trimIndent()) shouldBe "true\ntrue\nfalse"
        }

        test("greater than or equal") {
            run("""
                say 2 >= 1
                say 2 >= 2
                say 2 >= 3
            """.trimIndent()) shouldBe "true\ntrue\nfalse"
        }

        test("string comparison") {
            run("""
                say "abc" < "def"
                say "xyz" > "abc"
            """.trimIndent()) shouldBe "true\ntrue"
        }

        test("nil equality") {
            run("""
                say nil == nil
                say nil != nil
                say nil == 1
            """.trimIndent()) shouldBe "true\nfalse\nfalse"
        }

        test("numeric cross-type equality") {
            run("say 1 == 1.0") shouldBe "true"
        }
    }

    // ====================================================================
    // 5. Logical Operators
    // ====================================================================

    context("Logical operators") {

        test("logical AND") {
            run("""
                say true && true
                say true && false
                say false && true
                say false && false
            """.trimIndent()) shouldBe "true\nfalse\nfalse\nfalse"
        }

        test("logical OR") {
            run("""
                say true || false
                say false || true
                say false || false
                say true || true
            """.trimIndent()) shouldBe "true\ntrue\nfalse\ntrue"
        }

        test("logical NOT") {
            run("""
                say !true
                say !false
            """.trimIndent()) shouldBe "false\ntrue"
        }

        test("short-circuit AND") {
            // Second operand should not be evaluated if first is false
            run("""
                var x = 0
                false && { x = 1; true }
                say x
            """.trimIndent()) shouldBe "0"
        }

        test("short-circuit OR") {
            // Second operand should not be evaluated if first is true
            run("""
                var x = 0
                true || { x = 1; false }
                say x
            """.trimIndent()) shouldBe "0"
        }
    }

    // ====================================================================
    // 6. Unary Operators
    // ====================================================================

    context("Unary operators") {

        test("unary negate") {
            run("""
                let x = 5
                say -x
            """.trimIndent()) shouldBe "-5"
        }

        test("unary not") {
            run("""
                let x = true
                say !x
            """.trimIndent()) shouldBe "false"
        }

        test("prefix increment") {
            run("""
                var x = 5
                say ++x
                say x
            """.trimIndent()) shouldBe "6\n6"
        }

        test("postfix increment") {
            run("""
                var x = 5
                say x++
                say x
            """.trimIndent()) shouldBe "5\n6"
        }

        test("prefix decrement") {
            run("""
                var x = 5
                say --x
                say x
            """.trimIndent()) shouldBe "4\n4"
        }

        test("postfix decrement") {
            run("""
                var x = 5
                say x--
                say x
            """.trimIndent()) shouldBe "5\n4"
        }

        test("non-null assertion on non-nil value") {
            run("""
                var x = 42
                say x!!
            """.trimIndent()) shouldBe "42"
        }

        test("non-null assertion on nil throws error") {
            val error = runExpectingError("""
                var x = nil
                say x!!
            """.trimIndent())
            error shouldContain "null"
        }
    }

    // ====================================================================
    // 7. Elvis Operator
    // ====================================================================

    context("Elvis operator") {

        test("elvis returns left when non-nil") {
            run("""
                var x = 42
                say x ?: 0
            """.trimIndent()) shouldBe "42"
        }

        test("elvis returns right when left is nil") {
            run("""
                var x = nil
                say x ?: "default"
            """.trimIndent()) shouldBe "default"
        }
    }

    // ====================================================================
    // 8. Compound Assignment
    // ====================================================================

    context("Compound assignment") {

        test("plus-assign") {
            run("""
                var x = 10
                x += 5
                say x
            """.trimIndent()) shouldBe "15"
        }

        test("minus-assign") {
            run("""
                var x = 10
                x -= 3
                say x
            """.trimIndent()) shouldBe "7"
        }

        test("times-assign") {
            run("""
                var x = 4
                x *= 3
                say x
            """.trimIndent()) shouldBe "12"
        }

        test("divide-assign") {
            run("""
                var x = 20
                x /= 4
                say x
            """.trimIndent()) shouldBe "5"
        }

        test("modulo-assign") {
            run("""
                var x = 17
                x %= 5
                say x
            """.trimIndent()) shouldBe "2"
        }

        test("power-assign") {
            run("""
                var x = 3
                x **= 3
                say x
            """.trimIndent()) shouldBe "27"
        }

        test("string plus-assign") {
            run("""
                var s = "Hello"
                s += " World"
                say s
            """.trimIndent()) shouldBe "Hello World"
        }
    }

    // ====================================================================
    // 9. String Interpolation
    // ====================================================================

    context("String interpolation") {

        test("simple variable interpolation") {
            run("""
                let name = "World"
                say "Hello ${'$'}name"
            """.trimIndent()) shouldBe "Hello World"
        }

        test("expression interpolation") {
            run("""
                let a = 3
                let b = 4
                say "Sum: ${'$'}{a + b}"
            """.trimIndent()) shouldBe "Sum: 7"
        }

        test("nested expression interpolation") {
            run("""
                let x = 10
                let label = if (x > 5) { "big" } else { "small" }
                say "Result: ${'$'}{label}"
            """.trimIndent()) shouldBe "Result: big"
        }
    }

    // ====================================================================
    // 10. Control Flow — If/Else
    // ====================================================================

    context("If/else expressions") {

        test("if true branch") {
            run("""
                let x = if (true) { "yes" } else { "no" }
                say x
            """.trimIndent()) shouldBe "yes"
        }

        test("if false branch") {
            run("""
                let x = if (false) { "yes" } else { "no" }
                say x
            """.trimIndent()) shouldBe "no"
        }

        test("if with block bodies") {
            run("""
                var x = 10
                if (x > 5) {
                    say "big"
                } else {
                    say "small"
                }
            """.trimIndent()) shouldBe "big"
        }

        test("if without else returns nil for false") {
            run("""
                let result = if (false) "yes"
                say result
            """.trimIndent()) shouldBe "nil"
        }

        test("chained if-else") {
            run("""
                let x = 2
                let label = if (x == 1) {
                    "one"
                } else if (x == 2) {
                    "two"
                } else {
                    "other"
                }
                say label
            """.trimIndent()) shouldBe "two"
        }
    }

    // ====================================================================
    // 11. Control Flow — When
    // ====================================================================

    context("When expressions") {

        test("when with subject — value matching") {
            run("""
                let x = 2
                let name = when x {
                    1 -> "one"
                    2 -> "two"
                    3 -> "three"
                    else -> "other"
                }
                say name
            """.trimIndent()) shouldBe "two"
        }

        test("when with subject — else branch") {
            run("""
                let x = 99
                let name = when x {
                    1 -> "one"
                    2 -> "two"
                    else -> "other"
                }
                say name
            """.trimIndent()) shouldBe "other"
        }

        test("when without subject — condition style") {
            run("""
                let x = 15
                let result = when {
                    x > 20 -> "big"
                    x > 10 -> "medium"
                    else -> "small"
                }
                say result
            """.trimIndent()) shouldBe "medium"
        }

        test("when with type matching") {
            run("""
                let x = 42
                let result = when x {
                    is String -> "string"
                    is Int -> "int"
                    else -> "unknown"
                }
                say result
            """.trimIndent()) shouldBe "int"
        }

        test("when with in-range matching") {
            run("""
                let score = 85
                let grade = when score {
                    in 90..100 -> "A"
                    in 80..89 -> "B"
                    in 70..79 -> "C"
                    else -> "F"
                }
                say grade
            """.trimIndent()) shouldBe "B"
        }

        test("when with enum DPEC matching") {
            run("""
                enum Color { RED, GREEN, BLUE }
                let c = Color.GREEN
                let name = when c {
                    .RED -> "red"
                    .GREEN -> "green"
                    .BLUE -> "blue"
                    else -> "unknown"
                }
                say name
            """.trimIndent()) shouldBe "green"
        }
    }

    // ====================================================================
    // 12. Control Flow — If/Else as Expression
    // ====================================================================

    context("If/else as expression") {

        test("if expression true branch") {
            run("""
                let x = if true { "yes" } else { "no" }
                say x
            """.trimIndent()) shouldBe "yes"
        }

        test("if expression false branch") {
            run("""
                let x = if false { "yes" } else { "no" }
                say x
            """.trimIndent()) shouldBe "no"
        }
    }

    // ====================================================================
    // 13. Control Flow — For Loops
    // ====================================================================

    context("For loops") {

        test("for with list") {
            run("""
                for item in [1, 2, 3] {
                    say item
                }
            """.trimIndent()) shouldBe "1\n2\n3"
        }

        test("for with implicit 'it'") {
            run("""
                for [10, 20, 30] {
                    say it
                }
            """.trimIndent()) shouldBe "10\n20\n30"
        }

        test("for with range") {
            run("""
                for i in 1..3 {
                    say i
                }
            """.trimIndent()) shouldBe "1\n2\n3"
        }

        test("for with exclusive range") {
            run("""
                for i in 0..<3 {
                    say i
                }
            """.trimIndent()) shouldBe "0\n1\n2"
        }

        test("for with break") {
            run("""
                for i in 1..10 {
                    if (i == 4) break
                    say i
                }
            """.trimIndent()) shouldBe "1\n2\n3"
        }

        test("for with continue") {
            run("""
                for i in 1..5 {
                    if (i == 3) continue
                    say i
                }
            """.trimIndent()) shouldBe "1\n2\n4\n5"
        }

        test("for iterating over string characters") {
            run("""
                for ch in "abc" {
                    say ch
                }
            """.trimIndent()) shouldBe "a\nb\nc"
        }

        test("for iterating over map entries") {
            run("""
                let m = ["a"=1, "b"=2]
                var count = 0
                for m {
                    count++
                }
                say count
            """.trimIndent()) shouldBe "2"
        }
    }

    // ====================================================================
    // 14. Control Flow — While Loops
    // ====================================================================

    context("While loops") {

        test("basic while loop") {
            run("""
                var i = 0
                while (i < 3) {
                    say i
                    i++
                }
            """.trimIndent()) shouldBe "0\n1\n2"
        }

        test("while with break") {
            run("""
                var i = 0
                while (true) {
                    if (i == 3) break
                    say i
                    i++
                }
            """.trimIndent()) shouldBe "0\n1\n2"
        }

        test("while with continue") {
            run("""
                var i = 0
                while (i < 5) {
                    i++
                    if (i == 3) continue
                    say i
                }
            """.trimIndent()) shouldBe "1\n2\n4\n5"
        }
    }

    // ====================================================================
    // 15. Functions
    // ====================================================================

    context("Functions") {

        test("basic function declaration and call") {
            run("""
                fun greet(): String = "Hello!"
                say greet()
            """.trimIndent()) shouldBe "Hello!"
        }

        test("function with parameters") {
            run("""
                fun add(a: Int, b: Int): Int = a + b
                say add(3, 4)
            """.trimIndent()) shouldBe "7"
        }

        test("function with block body and return") {
            run("""
                fun max(a: Int, b: Int): Int {
                    if (a > b) {
                        return a
                    }
                    return b
                }
                say max(10, 20)
            """.trimIndent()) shouldBe "20"
        }

        test("function with default parameters") {
            run("""
                fun greet(name: String = "World"): String = "Hello ${'$'}name"
                say greet()
                say greet("Alice")
            """.trimIndent()) shouldBe "Hello World\nHello Alice"
        }

        test("recursive function — factorial") {
            run("""
                fun factorial(n: Int): Int {
                    if (n <= 1) return 1
                    return n * factorial(n - 1)
                }
                say factorial(5)
            """.trimIndent()) shouldBe "120"
        }

        test("recursive function — fibonacci") {
            run("""
                fun fib(n: Int): Int {
                    if (n <= 1) return n
                    return fib(n - 1) + fib(n - 2)
                }
                say fib(10)
            """.trimIndent()) shouldBe "55"
        }

        test("closure captures enclosing scope") {
            run("""
                var count = 0
                fun increment(): Int {
                    count = count + 1
                    return count
                }
                say increment()
                say increment()
                say increment()
            """.trimIndent()) shouldBe "1\n2\n3"
        }

        test("function as first-class value") {
            run("""
                fun double(x: Int): Int = x * 2
                let fn = double
                say fn(5)
            """.trimIndent()) shouldBe "10"
        }

        test("wrong arity throws error") {
            val error = runExpectingError("""
                fun add(a: Int, b: Int): Int = a + b
                add(1)
            """.trimIndent())
            error shouldContain "expects 2 argument(s) but got 1"
        }
    }

    // ====================================================================
    // 16. Collections — Lists
    // ====================================================================

    context("Lists") {

        test("list literal") {
            run("say [1, 2, 3]") shouldBe "[1, 2, 3]"
        }

        test("empty list") {
            run("say []") shouldBe "[]"
        }

        test("list index access") {
            run("""
                let nums = [10, 20, 30]
                say nums[0]
                say nums[2]
            """.trimIndent()) shouldBe "10\n30"
        }

        test("list size") {
            run("""
                let nums = [1, 2, 3, 4, 5]
                say nums.size
            """.trimIndent()) shouldBe "5"
        }

        test("list isEmpty / isNotEmpty") {
            run("""
                say [].isEmpty
                say [1].isEmpty
                say [].isNotEmpty
                say [1].isNotEmpty
            """.trimIndent()) shouldBe "true\nfalse\nfalse\ntrue"
        }

        test("list first / last") {
            run("""
                let nums = [10, 20, 30]
                say nums.first
                say nums.last
            """.trimIndent()) shouldBe "10\n30"
        }

        test("list reversed") {
            run("""
                say [1, 2, 3].reversed
            """.trimIndent()) shouldBe "[3, 2, 1]"
        }

        test("list index out of bounds") {
            val error = runExpectingError("""
                let nums = [1, 2, 3]
                say nums[5]
            """.trimIndent())
            error shouldContain "ndex"
        }

        test("nested list") {
            run("""
                let matrix = [[1, 2], [3, 4]]
                say matrix[0]
                say matrix[1][1]
            """.trimIndent()) shouldBe "[1, 2]\n4"
        }

        test("heterogeneous list") {
            run("""say [1, "two", true, nil]""") shouldBe "[1, two, true, nil]"
        }
    }

    // ====================================================================
    // 17. Collections — Maps
    // ====================================================================

    context("Maps") {

        test("map literal") {
            run("""
                let m = ["name"="Alice", "age"=30]
                say m["name"]
                say m["age"]
            """.trimIndent()) shouldBe "Alice\n30"
        }

        test("map size") {
            run("""
                let m = ["a"=1, "b"=2, "c"=3]
                say m.size
            """.trimIndent()) shouldBe "3"
        }

        test("map isEmpty / isNotEmpty") {
            run("""
                say [=].isEmpty
                say ["a"=1].isEmpty
                say [=].isNotEmpty
                say ["a"=1].isNotEmpty
            """.trimIndent()) shouldBe "true\nfalse\nfalse\ntrue"
        }

        test("map keys / values") {
            run("""
                let m = ["x"=1, "y"=2]
                say m.keys.size
                say m.values.size
            """.trimIndent()) shouldBe "2\n2"
        }

        test("nil returned for missing map key") {
            run("""
                let m = ["a"=1]
                say m["b"]
            """.trimIndent()) shouldBe "nil"
        }
    }

    // ====================================================================
    // 18. Ranges
    // ====================================================================

    context("Ranges") {

        test("inclusive range") {
            run("""
                for i in 1..5 {
                    say i
                }
            """.trimIndent()) shouldBe "1\n2\n3\n4\n5"
        }

        test("exclusive end range") {
            run("""
                for i in 0..<3 {
                    say i
                }
            """.trimIndent()) shouldBe "0\n1\n2"
        }

        test("in check with range") {
            run("""
                say 5 in 1..10
                say 11 in 1..10
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("!in check with range") {
            run("""
                say 5 !in 1..3
                say 2 !in 1..3
            """.trimIndent()) shouldBe "true\nfalse"
        }
    }

    // ====================================================================
    // 19. In Checks (Collections)
    // ====================================================================

    context("In checks") {

        test("in list") {
            run("""
                say 2 in [1, 2, 3]
                say 5 in [1, 2, 3]
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("in map (checks keys)") {
            run("""
                let m = ["a"=1, "b"=2]
                say "a" in m
                say "c" in m
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("in string") {
            run("""
                say "ell" in "Hello"
                say "xyz" in "Hello"
            """.trimIndent()) shouldBe "true\nfalse"
        }
    }

    // ====================================================================
    // 20. Type Checking and Casting
    // ====================================================================

    context("Type checking (is / !is)") {

        test("is Int") {
            run("""
                say 42 is Int
                say "hello" is Int
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("is String") {
            run("""
                say "hello" is String
                say 42 is String
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("is Bool") {
            run("""
                say true is Bool
                say 1 is Bool
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("is List") {
            run("""
                say [1, 2] is List
                say "hi" is List
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("is Map") {
            run("""
                say ["a"=1] is Map
                say [1, 2] is Map
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("!is negated type check") {
            run("""
                say 42 !is String
                say 42 !is Int
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("is with user-defined class") {
            run("""
                class Dog { }
                let d = Dog()
                say d is Dog
            """.trimIndent()) shouldBe "true"
        }

        test("nil is nullable") {
            run("""
                say nil is Int?
            """.trimIndent()) shouldBe "true"
        }

        test("is Any matches everything") {
            run("""
                say 42 is Any
                say "hi" is Any
                say true is Any
            """.trimIndent()) shouldBe "true\ntrue\ntrue"
        }
    }

    context("Type casting (as)") {

        test("cast Int to String") {
            run("""
                let x = 42
                let s = x as String
                say s
            """.trimIndent()) shouldBe "42"
        }

        test("cast to Int") {
            run("""
                let x = 3.7
                let i = x as Int
                say i
            """.trimIndent()) shouldBe "3"
        }

        test("cast to Bool") {
            run("""
                say (1 as Bool)
                say (0 as Bool)
            """.trimIndent()) shouldBe "true\nfalse"
        }
    }

    // ====================================================================
    // 21. String Member Access
    // ====================================================================

    context("String members") {

        test("string length") {
            run("""say "hello".length""") shouldBe "5"
        }

        test("string size (alias)") {
            run("""say "hello".size""") shouldBe "5"
        }

        test("string isEmpty / isNotEmpty") {
            run("""
                say "".isEmpty
                say "hi".isEmpty
                say "".isNotEmpty
                say "hi".isNotEmpty
            """.trimIndent()) shouldBe "true\nfalse\nfalse\ntrue"
        }

        test("string uppercase / lowercase") {
            run("""
                say "hello".uppercase
                say "HELLO".lowercase
            """.trimIndent()) shouldBe "HELLO\nhello"
        }

        test("string trim") {
            run("""say "  hello  ".trim""") shouldBe "hello"
        }

        test("string reversed") {
            run("""say "abcde".reversed""") shouldBe "edcba"
        }

        test("string first / last") {
            run("""
                say "hello".first
                say "hello".last
            """.trimIndent()) shouldBe "h\no"
        }

        test("string index access") {
            run("""
                let s = "abcde"
                say s[0]
                say s[4]
            """.trimIndent()) shouldBe "a\ne"
        }
    }

    // ====================================================================
    // 22. Classes
    // ====================================================================

    context("Classes") {

        test("basic class instantiation") {
            run("""
                class Dog(let name: String) { }
                let d = Dog("Rex")
                say d.name
            """.trimIndent()) shouldBe "Rex"
        }

        test("class with mutable property") {
            run("""
                class Counter(var count: Int) { }
                let c = Counter(0)
                c.count = 5
                say c.count
            """.trimIndent()) shouldBe "5"
        }

        test("class with methods") {
            run("""
                class Greeter(let name: String) {
                    fun greet(): String = "Hello, ${'$'}{this.name}!"
                }
                let g = Greeter("Alice")
                say g.greet()
            """.trimIndent()) shouldBe "Hello, Alice!"
        }

        test("class with default constructor parameter") {
            run("""
                class Config(let debug: Bool = false) { }
                let c1 = Config()
                let c2 = Config(true)
                say c1.debug
                say c2.debug
            """.trimIndent()) shouldBe "false\ntrue"
        }

        test("class with body properties") {
            run("""
                class Dog(let name: String) {
                    var tricks = 0
                }
                let d = Dog("Rex")
                say d.tricks
                d.tricks = 3
                say d.tricks
            """.trimIndent()) shouldBe "0\n3"
        }

        test("class with static members") {
            run("""
                class MathUtils {
                    static {
                        fun square(x: Int): Int = x * x
                        let PI = 3
                    }
                }
                say MathUtils.square(5)
                say MathUtils.PI
            """.trimIndent()) shouldBe "25\n3"
        }

        test("class method calling another method") {
            run("""
                class Calc {
                    fun double(x: Int): Int = x * 2
                    fun quadruple(x: Int): Int = double(double(x))
                }
                let c = Calc()
                say c.quadruple(3)
            """.trimIndent()) shouldBe "12"
        }

        test("class with named constructor arguments") {
            run("""
                class Point(let x: Int, let y: Int) { }
                let p = Point(y = 20, x = 10)
                say p.x
                say p.y
            """.trimIndent()) shouldBe "10\n20"
        }
    }

    // ====================================================================
    // 23. Class Inheritance
    // ====================================================================

    context("Class inheritance") {

        test("subclass inherits superclass methods") {
            run("""
                class Animal {
                    fun speak(): String = "..."
                }
                class Dog: Animal {
                    fun speak(): String = "Woof!"
                }
                let d = Dog()
                say d.speak()
            """.trimIndent()) shouldBe "Woof!"
        }

        test("subclass is-check against superclass") {
            run("""
                class Animal { }
                class Dog: Animal { }
                let d = Dog()
                say d is Animal
                say d is Dog
            """.trimIndent()) shouldBe "true\ntrue"
        }

        test("subclass inherits body properties from superclass") {
            run("""
                class Animal {
                    var alive = true
                }
                class Dog: Animal { }
                let d = Dog()
                say d.alive
            """.trimIndent()) shouldBe "true"
        }
    }

    // ====================================================================
    // 24. Enums
    // ====================================================================

    context("Enums") {

        test("basic enum declaration and access") {
            run("""
                enum Color { RED, GREEN, BLUE }
                say Color.RED
                say Color.GREEN
                say Color.BLUE
            """.trimIndent()) shouldBe "RED\nGREEN\nBLUE"
        }

        test("enum ordinal") {
            run("""
                enum Color { RED, GREEN, BLUE }
                say Color.RED.ordinal
                say Color.GREEN.ordinal
                say Color.BLUE.ordinal
            """.trimIndent()) shouldBe "0\n1\n2"
        }

        test("enum name") {
            run("""
                enum Color { RED, GREEN, BLUE }
                say Color.RED.name
            """.trimIndent()) shouldBe "RED"
        }

        test("enum equality") {
            run("""
                enum Color { RED, GREEN, BLUE }
                let a = Color.RED
                let b = Color.RED
                let c = Color.GREEN
                say a == b
                say a == c
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("enum with constructor parameters") {
            run("""
                enum Planet(let mass: Double) {
                    EARTH(5.97),
                    MARS(0.642)
                }
                say Planet.EARTH.mass
                say Planet.MARS.mass
            """.trimIndent()) shouldBe "5.97\n0.642"
        }

        test("enum iteration with for") {
            run("""
                enum Color { RED, GREEN, BLUE }
                for Color {
                    say it
                }
            """.trimIndent()) shouldBe "RED\nGREEN\nBLUE"
        }

        test("DPEC — dot-prefixed enum constant") {
            run("""
                enum Direction { UP, DOWN, LEFT, RIGHT }
                let d = Direction.UP
                let label = when d {
                    .UP -> "going up"
                    .DOWN -> "going down"
                    .LEFT -> "going left"
                    .RIGHT -> "going right"
                    else -> "unknown"
                }
                say label
            """.trimIndent()) shouldBe "going up"
        }

        test("enum in-check") {
            run("""
                enum Color { RED, GREEN, BLUE }
                let c = Color.RED
                say c in Color
            """.trimIndent()) shouldBe "true"
        }

        test("enum type checking with is") {
            run("""
                enum Color { RED, GREEN, BLUE }
                let c = Color.RED
                say c is Color
            """.trimIndent()) shouldBe "true"
        }
    }

    // ====================================================================
    // 25. Structs
    // ====================================================================

    context("Structs") {

        test("basic struct instantiation") {
            run("""
                struct Point(let x: Double, let y: Double) { }
                let p = Point(3.0, 4.0)
                say p.x
                say p.y
            """.trimIndent()) shouldBe "3\n4"
        }

        test("struct value semantics — copy on assign") {
            run("""
                struct Point(var x: Double, var y: Double) { }
                var a = Point(1.0, 2.0)
                var b = a
                b.x = 99.0
                say a.x
                say b.x
            """.trimIndent()) shouldBe "1\n99"
        }

        test("struct structural equality") {
            run("""
                struct Point(let x: Int, let y: Int) { }
                let a = Point(1, 2)
                let b = Point(1, 2)
                let c = Point(3, 4)
                say a == b
                say a == c
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("struct copy method") {
            run("""
                struct Point(let x: Int, let y: Int) { }
                let p = Point(1, 2)
                let q = p.copy()
                say q.x
                say q.y
            """.trimIndent()) shouldBe "1\n2"
        }

        test("struct copy with named overrides") {
            run("""
                struct Point(let x: Int, let y: Int) { }
                let p = Point(1, 2)
                let q = p.copy(x = 10)
                say q.x
                say q.y
            """.trimIndent()) shouldBe "10\n2"
        }

        test("struct with methods") {
            run("""
                struct Point(let x: Double, let y: Double) {
                    fun sum(): Double = this.x + this.y
                }
                let p = Point(3.0, 4.0)
                say p.sum()
            """.trimIndent()) shouldBe "7"
        }

        test("struct with static members") {
            run("""
                struct Point(let x: Double, let y: Double) {
                    static {
                        fun origin(): String = "0,0"
                    }
                }
                say Point.origin()
            """.trimIndent()) shouldBe "0,0"
        }

        test("struct is-check") {
            run("""
                struct Point(let x: Int, let y: Int) { }
                let p = Point(1, 2)
                say p is Point
            """.trimIndent()) shouldBe "true"
        }
    }

    // ====================================================================
    // 26. Try/Catch/Finally
    // ====================================================================

    context("Try/catch/finally") {

        test("try without error") {
            run("""
                let result = try {
                    42
                } catch(e) {
                    0
                }
                say result
            """.trimIndent()) shouldBe "42"
        }

        test("try with catch") {
            run("""
                let result = try {
                    throw "oops"
                } catch(e) {
                    say e
                    -1
                }
            """.trimIndent()) shouldContain "oops"
        }

        test("try with finally") {
            run("""
                try {
                    say "try"
                } finally {
                    say "finally"
                }
            """.trimIndent()) shouldBe "try\nfinally"
        }

        test("try with catch and finally") {
            run("""
                try {
                    throw "error"
                } catch(e) {
                    say "caught"
                } finally {
                    say "done"
                }
            """.trimIndent()) shouldBe "caught\ndone"
        }
    }

    // ====================================================================
    // 27. Say Variants
    // ====================================================================

    context("Say variants") {

        test("say outputs to stdout") {
            run("say 42") shouldBe "42"
        }

        test("say with multiple arguments") {
            run("say 1 2 3") shouldBe "1 2 3"
        }

        test("say.error outputs to stderr") {
            val result = runError("say.error \"something went wrong\"")
            result shouldBe "something went wrong"
        }

        test("say.warn outputs to stdout") {
            val result = run("say.warn \"caution\"")
            result shouldBe "caution"
        }

        test("say.note outputs to stdout") {
            val result = run("say.note \"FYI\"")
            result shouldBe "FYI"
        }
    }

    // ====================================================================
    // 28. Reflection
    // ====================================================================

    context("Reflection (::class)") {

        test("int type") {
            run("say 42::class") shouldBe "Int"
        }

        test("string type") {
            run("""say "hello"::class""") shouldBe "String"
        }

        test("bool type") {
            run("say true::class") shouldBe "Bool"
        }

        test("nil type") {
            run("say nil::class") shouldBe "Nil"
        }

        test("list type") {
            run("say [1, 2]::class") shouldBe "List"
        }

        test("map type") {
            run("""say ["a"=1]::class""") shouldBe "Map"
        }

        test("class instance type") {
            run("""
                class Dog { }
                let d = Dog()
                say d::class
            """.trimIndent()) shouldBe "Dog"
        }

        test("enum constant type") {
            run("""
                enum Color { RED }
                say Color.RED::class
            """.trimIndent()) shouldBe "Color"
        }

        test("struct instance type") {
            run("""
                struct Point(let x: Int, let y: Int) { }
                let p = Point(1, 2)
                say p::class
            """.trimIndent()) shouldBe "Point"
        }
    }

    // ====================================================================
    // 29. Truthiness
    // ====================================================================

    context("Truthiness") {

        test("nil is falsy") {
            run("say if (nil) \"yes\" else \"no\"") shouldBe "no"
        }

        test("false is falsy") {
            run("say if (false) \"yes\" else \"no\"") shouldBe "no"
        }

        test("zero is falsy") {
            run("say if (0) \"yes\" else \"no\"") shouldBe "no"
        }

        test("empty string is falsy") {
            run("say if (\"\") \"yes\" else \"no\"") shouldBe "no"
        }

        test("empty list is falsy") {
            run("say if ([]) \"yes\" else \"no\"") shouldBe "no"
        }

        test("non-zero int is truthy") {
            run("say if (1) \"yes\" else \"no\"") shouldBe "yes"
        }

        test("non-empty string is truthy") {
            run("say if (\"hi\") \"yes\" else \"no\"") shouldBe "yes"
        }

        test("non-empty list is truthy") {
            run("say if ([1]) \"yes\" else \"no\"") shouldBe "yes"
        }

        test("true is truthy") {
            run("say if (true) \"yes\" else \"no\"") shouldBe "yes"
        }
    }

    // ====================================================================
    // 30. Scoping
    // ====================================================================

    context("Scoping") {

        test("block scope isolates variables") {
            run("""
                var x = 1
                {
                    var y = 2
                    say x
                    say y
                }
                say x
            """.trimIndent()) shouldBe "1\n2\n1"
        }

        test("inner scope can shadow outer variable") {
            run("""
                var x = 1
                {
                    var x = 99
                    say x
                }
                say x
            """.trimIndent()) shouldBe "99\n1"
        }

        test("inner scope assignment modifies outer variable") {
            run("""
                var x = 1
                {
                    x = 42
                }
                say x
            """.trimIndent()) shouldBe "42"
        }

        test("function has own scope") {
            run("""
                var x = 1
                fun f() {
                    var x = 99
                    say x
                }
                f()
                say x
            """.trimIndent()) shouldBe "99\n1"
        }
    }

    // ====================================================================
    // 31. Extend (Type Extensions)
    // ====================================================================

    context("Type extensions") {

        test("extend class with new method") {
            run("""
                class Dog {
                    fun speak(): String = "Woof"
                }
                extend Dog {
                    fun loud(): String = "WOOF!"
                }
                let d = Dog()
                say d.speak()
                say d.loud()
            """.trimIndent()) shouldBe "Woof\nWOOF!"
        }

        test("extend struct with new method") {
            run("""
                struct Point(let x: Int, let y: Int) { }
                extend Point {
                    fun label(): String = "point"
                }
                let p = Point(1, 2)
                say p.label()
            """.trimIndent()) shouldBe "point"
        }
    }

    // ====================================================================
    // 32. Matches Operator (Regex)
    // ====================================================================

    context("Matches operator") {

        test("matches pattern") {
            run("""
                say "hello123" matches "[a-z]+[0-9]+"
                say "hello" matches "[0-9]+"
            """.trimIndent()) shouldBe "true\nfalse"
        }
    }

    // ====================================================================
    // 33. Index Operations on Classes (get/set)
    // ====================================================================

    context("Index operations on user types") {

        test("class with get method enables index access") {
            run("""
                class Grid(var data: List) {
                    fun get(i: Int): Int = this.data[i]
                }
                let g = Grid([10, 20, 30])
                say g[0]
                say g[2]
            """.trimIndent()) shouldBe "10\n30"
        }

        test("class with multi-index get") {
            run("""
                class Matrix(var data: List) {
                    fun get(row: Int, col: Int): Int {
                        return this.data[row][col]
                    }
                }
                let m = Matrix([[1, 2], [3, 4]])
                say m[0, 0]
                say m[1, 1]
            """.trimIndent()) shouldBe "1\n4"
        }
    }

    // ====================================================================
    // 34. Error Conditions
    // ====================================================================

    context("Error conditions") {

        test("undefined variable") {
            val error = runExpectingError("say undefined_var")
            error shouldContain "undefined"
        }

        test("calling non-callable") {
            val error = runExpectingError("""
                let x = 42
                x()
            """.trimIndent())
            error shouldContain "call"
        }

        test("member not found on object") {
            val error = runExpectingError("""
                class Dog { }
                let d = Dog()
                say d.missing
            """.trimIndent())
            error shouldContain "missing"
        }

        test("member access on nil without safe operator") {
            val error = runExpectingError("""
                var x = nil
                say x.foo
            """.trimIndent())
            error shouldContain "nil"
        }

        test("safe member access on nil returns nil") {
            run("""
                var x = nil
                say x?.foo
            """.trimIndent()) shouldBe "nil"
        }

        test("string index out of bounds") {
            val error = runExpectingError("""
                let s = "abc"
                say s[10]
            """.trimIndent())
            error shouldContain "ndex"
        }

        test("non-exhaustive when throws error") {
            val error = runExpectingError("""
                let x = 99
                when x {
                    1 -> "one"
                    2 -> "two"
                }
            """.trimIndent())
            error shouldContain "exhaustive"
        }
    }

    // ====================================================================
    // 35. Recursion Depth Limit
    // ====================================================================

    context("Recursion depth limit") {

        test("maximum recursion depth exceeded") {
            val output = StringWriter()
            val runtime = KSRuntime(
                hostLang = false,
                colorOutput = false,
                outputWriter = PrintWriter(output, true),
                errorWriter = PrintWriter(StringWriter(), true),
                maxRecursionDepth = 10
            )
            val source = """
                fun infiniteLoop() {
                    infiniteLoop()
                }
                infiniteLoop()
            """.trimIndent()
            val lexer = Lexer(source)
            val tokens = lexer.tokenize()
            val parser = Parser(tokens)
            val program = parser.parse()
            val interpreter = Interpreter(runtime)

            try {
                interpreter.executeProgram(program)
                throw AssertionError("Expected recursion depth error")
            } catch (e: AssertionError) {
                throw e
            } catch (e: Exception) {
                e.message!! shouldContain "recursion"
            }
        }
    }

    // ====================================================================
    // 36. Complex Integration Tests
    // ====================================================================

    context("Integration tests") {

        test("FizzBuzz") {
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

        test("class with methods using control flow") {
            run("""
                class Calculator {
                    fun abs(x: Int): Int = if (x < 0) { -x } else { x }
                    fun clamp(x: Int, lo: Int, hi: Int): Int {
                        return when {
                            x < lo -> lo
                            x > hi -> hi
                            else -> x
                        }
                    }
                }
                let calc = Calculator()
                say calc.abs(-5)
                say calc.abs(5)
                say calc.clamp(15, 0, 10)
                say calc.clamp(-5, 0, 10)
                say calc.clamp(5, 0, 10)
            """.trimIndent()) shouldBe "5\n5\n10\n0\n5"
        }

        test("accumulator with closure") {
            run("""
                var total = 100
                fun accumulate(x: Int): Int {
                    total = total + x
                    return total
                }
                say accumulate(10)
                say accumulate(20)
                say accumulate(30)
            """.trimIndent()) shouldBe "110\n130\n160"
        }

        test("enum with when and methods") {
            run("""
                enum Season { SPRING, SUMMER, FALL, WINTER }

                fun describe(s): String {
                    return when s {
                        .SPRING -> "flowers bloom"
                        .SUMMER -> "sun shines"
                        .FALL -> "leaves fall"
                        .WINTER -> "snow falls"
                        else -> "unknown"
                    }
                }

                for Season {
                    say describe(it)
                }
            """.trimIndent()) shouldBe "flowers bloom\nsun shines\nleaves fall\nsnow falls"
        }

        test("struct with trait and methods") {
            run("""
                trait Measurable {
                    fun area(): Double
                }

                struct Rect(let w: Double, let h: Double): Measurable {
                    fun area(): Double = this.w * this.h
                }

                let r = Rect(3.0, 4.0)
                say r.area()
                say r is Measurable
            """.trimIndent()) shouldBe "12\ntrue"
        }

        test("nested function calls and expressions") {
            run("""
                fun max(a: Int, b: Int): Int = if a > b { a } else { b }
                fun min(a: Int, b: Int): Int = if a < b { a } else { b }

                say max(min(10, 20), min(5, 15))
            """.trimIndent()) shouldBe "10"
        }

        test("nested function calls and expressions: ternary operator") {
            run("""
                fun max(a: Int, b: Int): Int = a > b ? a : b 
                fun min(a: Int, b: Int): Int = a < b ? a : b 

                say max(min(10, 20), min(5, 15))
            """.trimIndent()) shouldBe "10"
        }

        test("nested function calls and expressions: ternary operator") {
            run("""
                fun max(a: Int, b: Int): Int {
                    if a > b {
                        a
                    } else {
                        b
                    }
                }
                
               fun min(a: Int, b: Int): Int {
                    if a < b {
                        a
                    } else {
                        b
                    }
                }               
                say max(min(10, 20), min(5, 15))
            """.trimIndent()) shouldBe "10"
        }
    }
})