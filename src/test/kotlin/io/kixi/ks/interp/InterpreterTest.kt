package io.kixi.ks.interp

import io.kixi.ks.KSRuntime
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kixi.uom.Quantity
import java.io.StringWriter
import java.math.BigDecimal

/**
 * Comprehensive Kotest tests for the KS Interpreter.
 *
 * Covers:
 *   - Literals (Int, Long, Float, Double, Dec, String, Char, Bool, Nil, URL)
 *   - Quantity and Currency literals (suffix and prefix notation)
 *   - String interpolation (simple and expression-based)
 *   - Multiline strings
 *   - Arithmetic expressions with correct precedence
 *   - Comparison, logical, and null-safety operators
 *   - Unary operators (negate, not, increment, decrement, non-null assert)
 *   - Variable declarations (var/let), assignments, compound assignments
 *   - Control flow: if/else, when, for, while, try/catch/finally
 *   - Functions: declarations, calls, closures, recursion, default params
 *   - Classes: declarations, instantiation, methods, properties
 *   - Traits: declarations, implementation, default methods
 *   - Enums: declarations, DPEC matching, ordinal/name access
 *   - Collections: List, Map, member access
 *   - Ranges: inclusive, exclusive, open-ended
 *   - Type operations: is, !is, as, in, !in, matches
 *   - Lang blocks: lang KD { ... }
 *   - Reflection: ::class
 *   - Say statement with variants
 *   - Elvis (?:), safe navigation (?.), non-null assert (!!)
 *   - Combine operator (⚭) for unit composition
 *
 * Run with: ./gradlew test
 */
class InterpreterTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

    /** Create a fresh interpreter for each test */
    fun interp() = Interpreter()

    /** Execute source and return result */
    fun exec(source: String): Any? = interp().execute(source)

    /** Execute source with captured output, return pair of (result, output) */
    fun execWithOutput(source: String): Pair<Any?, String> {
        val output = StringWriter()
        val error = StringWriter()
        val runtime = KSRuntime.forTesting(output, error)
        val interpreter = Interpreter(runtime)
        val result = interpreter.execute(source)
        return Pair(result, output.toString().trim())
    }

    /** Execute source with captured error output */
    fun execWithError(source: String): Pair<Any?, String> {
        val output = StringWriter()
        val error = StringWriter()
        val runtime = KSRuntime.forTesting(output, error)
        val interpreter = Interpreter(runtime)
        val result = interpreter.execute(source)
        return Pair(result, error.toString().trim())
    }

    // ====================================================================
    // Literal Expressions
    // ====================================================================

    context("Literal expressions") {

        test("integer literal") {
            exec("42") shouldBe 42
        }

        test("zero") {
            exec("0") shouldBe 0
        }

        test("negative integer") {
            exec("-42") shouldBe -42
        }

        test("long literal") {
            exec("100L") shouldBe 100L
        }

        test("float literal") {
            exec("3.14f") shouldBe 3.14f
        }

        test("double literal") {
            exec("3.14") shouldBe 3.14
        }

        test("decimal literal") {
            val result = exec("3.14BD")
            result.shouldBeInstanceOf<BigDecimal>()
        }

        test("string literal") {
            exec(""""hello"""") shouldBe "hello"
        }

        test("empty string") {
            exec("""""""") shouldBe ""
        }

        test("char literal") {
            exec("'A'") shouldBe 'A'
        }

        test("boolean true") {
            exec("true") shouldBe true
        }

        test("boolean false") {
            exec("false") shouldBe false
        }

        test("nil literal") {
            exec("nil").shouldBeNull()
        }

        test("string with escape sequences") {
            exec(""""hello\nworld"""") shouldBe "hello\nworld"
        }

        test("string with tab escape") {
            exec(""""col1\tcol2"""") shouldBe "col1\tcol2"
        }
    }

    // ====================================================================
    // String Interpolation
    // ====================================================================

    context("String interpolation") {

        test("simple variable interpolation") {
            val source = """
                var name = "World"
                "Hello ${'$'}name"
            """.trimIndent()
            exec(source) shouldBe "Hello World"
        }

        test("expression interpolation") {
            val source = """
                var a = 3
                var b = 4
                "Sum: ${'$'}{a + b}"
            """.trimIndent()
            exec(source) shouldBe "Sum: 7"
        }

        test("multiple interpolations in one string") {
            val source = """
                var first = "Ada"
                var last = "Lovelace"
                "${'$'}first ${'$'}last"
            """.trimIndent()
            exec(source) shouldBe "Ada Lovelace"
        }

        test("nested expression interpolation") {
            val source = """
                var x = 10
                "Value: ${'$'}{if x > 5 { "big" } else { "small" }}"
            """.trimIndent()
            exec(source) shouldBe "Value: big"
        }

        test("interpolation with method call on string") {
            val source = """
                var name = "hello"
                "Upper: ${'$'}{name.uppercase}"
            """.trimIndent()
            exec(source) shouldBe "Upper: HELLO"
        }
    }

    // ====================================================================
    // Multiline Strings
    // ====================================================================

    context("Multiline strings") {

        test("basic multiline string") {
            val source = "\"\"\"hello\nworld\"\"\""
            val result = exec(source)
            result.shouldBeInstanceOf<String>()
        }
    }

    // ====================================================================
    // Arithmetic Expressions & Precedence
    // ====================================================================

    context("Arithmetic expressions") {

        test("addition") {
            exec("2 + 3") shouldBe 5
        }

        test("subtraction") {
            exec("10 - 4") shouldBe 6
        }

        test("multiplication") {
            exec("6 * 7") shouldBe 42
        }

        test("division") {
            exec("15 / 3") shouldBe 5
        }

        test("modulo") {
            exec("17 % 5") shouldBe 2
        }

        test("exponentiation") {
            exec("2 ** 10") shouldBe 1024
        }

        test("negation") {
            exec("-5") shouldBe -5
        }

        test("double negation") {
            exec("-(-5)") shouldBe 5
        }

        test("string concatenation via +") {
            exec(""""hello" + " " + "world"""") shouldBe "hello world"
        }

        test("string repetition via *") {
            exec(""""ha" * 3""") shouldBe "hahaha"
        }

        test("int * string repetition (commutative)") {
            exec("""3 * "ha"""") shouldBe "hahaha"
        }

        test("list concatenation via +") {
            val result = exec("[1, 2] + [3, 4]")
            result shouldBe listOf(1, 2, 3, 4)
        }
    }

    context("Operator precedence") {

        test("multiplication before addition: 2 + 3 * 4 == 14") {
            exec("2 + 3 * 4") shouldBe 14
        }

        test("division before subtraction: 10 - 6 / 2 == 7") {
            exec("10 - 6 / 2") shouldBe 7
        }

        test("exponentiation before multiplication: 2 * 3 ** 2 == 18") {
            exec("2 * 3 ** 2") shouldBe 18
        }

        test("parentheses override precedence: (2 + 3) * 4 == 20") {
            exec("(2 + 3) * 4") shouldBe 20
        }

        test("chained operations: 2 + 3 * 4 - 1 == 13") {
            exec("2 + 3 * 4 - 1") shouldBe 13
        }

        test("modulo same precedence as multiply: 10 % 3 + 2 == 3") {
            exec("10 % 3 + 2") shouldBe 3
        }

        test("complex expression: (1 + 2) * (3 + 4) == 21") {
            exec("(1 + 2) * (3 + 4)") shouldBe 21
        }

        test("nested exponentiation is right-associative: 2 ** 3 ** 2 == 512") {
            // 2 ** (3 ** 2) = 2 ** 9 = 512
            exec("2 ** 3 ** 2") shouldBe 512
        }

        test("unary minus higher than binary: -2 ** 2 == 4") {
            // (-2) ** 2 = 4
            exec("-2 ** 2") shouldBe 4
        }

        test("logical AND before OR: true || false && false == true") {
            exec("true || false && false") shouldBe true
        }

        test("comparison before logical: 1 < 2 && 3 > 2 == true") {
            exec("1 < 2 && 3 > 2") shouldBe true
        }
    }

    // ====================================================================
    // Comparison & Logical Operators
    // ====================================================================

    context("Comparison operators") {

        test("equal") {
            exec("5 == 5") shouldBe true
        }

        test("not equal") {
            exec("5 != 3") shouldBe true
        }

        test("less than") {
            exec("3 < 5") shouldBe true
        }

        test("greater than") {
            exec("5 > 3") shouldBe true
        }

        test("less than or equal") {
            exec("5 <= 5") shouldBe true
        }

        test("greater than or equal") {
            exec("5 >= 4") shouldBe true
        }

        test("string comparison") {
            exec(""""apple" < "banana"""") shouldBe true
        }

        test("numeric equality across types") {
            exec("5 == 5.0") shouldBe true
        }

        test("nil equality") {
            exec("nil == nil") shouldBe true
        }

        test("nil not equal to value") {
            exec("nil != 0") shouldBe true
        }
    }

    context("Logical operators") {

        test("logical AND true") {
            exec("true && true") shouldBe true
        }

        test("logical AND false") {
            exec("true && false") shouldBe false
        }

        test("logical OR true") {
            exec("false || true") shouldBe true
        }

        test("logical OR false") {
            exec("false || false") shouldBe false
        }

        test("logical NOT") {
            exec("!true") shouldBe false
        }

        test("double NOT") {
            exec("!!true") shouldBe true
        }

        test("short-circuit AND does not evaluate right side") {
            // If short-circuit works, this should not throw
            val source = """
                var evaluated = false
                false && { evaluated = true; true }
                evaluated
            """.trimIndent()
            exec(source) shouldBe false
        }

        test("short-circuit OR does not evaluate right side") {
            val source = """
                var evaluated = false
                true || { evaluated = true; false }
                evaluated
            """.trimIndent()
            exec(source) shouldBe false
        }
    }

    // ====================================================================
    // Null Safety Operators
    // ====================================================================

    context("Null safety") {

        test("elvis operator with non-null left") {
            val source = """
                var x = 42
                x ?: 0
            """.trimIndent()
            exec(source) shouldBe 42
        }

        test("elvis operator with null left") {
            val source = """
                var x = nil
                x ?: 99
            """.trimIndent()
            exec(source) shouldBe 99
        }

        test("safe navigation on non-null") {
            val source = """
                var s = "hello"
                s?.length
            """.trimIndent()
            exec(source) shouldBe 5
        }

        test("safe navigation on nil returns nil") {
            val source = """
                var s = nil
                s?.length
            """.trimIndent()
            exec(source).shouldBeNull()
        }

        test("non-null assertion on non-null value") {
            val source = """
                var x = 42
                x!!
            """.trimIndent()
            exec(source) shouldBe 42
        }
    }

    // ====================================================================
    // Variable Declarations & Assignments
    // ====================================================================

    context("Variable declarations") {

        test("var declaration with initializer") {
            val source = """
                var x = 42
                x
            """.trimIndent()
            exec(source) shouldBe 42
        }

        test("let declaration (immutable)") {
            val source = """
                let x = 42
                x
            """.trimIndent()
            exec(source) shouldBe 42
        }

        test("var reassignment") {
            val source = """
                var x = 1
                x = 2
                x
            """.trimIndent()
            exec(source) shouldBe 2
        }

        test("compound assignment +=") {
            val source = """
                var x = 10
                x += 5
                x
            """.trimIndent()
            exec(source) shouldBe 15
        }

        test("compound assignment -=") {
            val source = """
                var x = 10
                x -= 3
                x
            """.trimIndent()
            exec(source) shouldBe 7
        }

        test("compound assignment *=") {
            val source = """
                var x = 5
                x *= 4
                x
            """.trimIndent()
            exec(source) shouldBe 20
        }

        test("compound assignment /=") {
            val source = """
                var x = 20
                x /= 4
                x
            """.trimIndent()
            exec(source) shouldBe 5
        }

        test("compound assignment %=") {
            val source = """
                var x = 17
                x %= 5
                x
            """.trimIndent()
            exec(source) shouldBe 2
        }

        test("compound assignment **=") {
            val source = """
                var x = 3
                x **= 3
                x
            """.trimIndent()
            exec(source) shouldBe 27
        }
    }

    context("Increment and decrement") {

        test("prefix increment") {
            val source = """
                var x = 5
                ++x
            """.trimIndent()
            exec(source) shouldBe 6
        }

        test("postfix increment returns old value") {
            val source = """
                var x = 5
                x++
            """.trimIndent()
            exec(source) shouldBe 5
        }

        test("prefix decrement") {
            val source = """
                var x = 5
                --x
            """.trimIndent()
            exec(source) shouldBe 4
        }

        test("postfix decrement returns old value") {
            val source = """
                var x = 5
                x--
            """.trimIndent()
            exec(source) shouldBe 5
        }

        test("increment mutates variable") {
            val source = """
                var x = 5
                x++
                x
            """.trimIndent()
            exec(source) shouldBe 6
        }
    }

    // ====================================================================
    // Quantity & Currency Literals
    // ====================================================================

    context("Quantity literals") {

        test("simple length quantity - cm") {
            val result = exec("23cm")
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "23"
            result.toString() shouldContain "cm"
        }

        test("decimal mass quantity - kg") {
            val result = exec("51.4kg")
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "51.4"
            result.toString() shouldContain "kg"
        }

        test("temperature quantity - degrees Celsius") {
            val result = exec("25\u00B0C")
            result.shouldBeInstanceOf<Quantity<*>>()
        }

        test("quantity member access - value") {
            val source = """
                let len = 23cm
                len.value
            """.trimIndent()
            val result = exec(source)
            result.shouldNotBeNull()
        }

        test("quantity member access - unit") {
            val source = """
                let len = 23cm
                len.unit
            """.trimIndent()
            exec(source) shouldBe "cm"
        }

        test("quantity addition (same unit)") {
            val source = """
                let a = 10cm
                let b = 5cm
                a + b
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "15"
        }

        test("quantity subtraction") {
            val source = """
                let a = 20kg
                let b = 8kg
                a - b
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "12"
        }

        test("quantity scalar multiplication") {
            val source = """
                let len = 5cm
                len * 3
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "15"
        }

        test("quantity scalar division") {
            val source = """
                let len = 12cm
                len / 4
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "3"
        }

        test("quantity negation") {
            val result = exec("-5cm")
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "-5"
        }
    }

    context("Currency quantity literals") {

        test("USD prefix notation") {
            val result = exec("${'$'}100")
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "100"
        }

        test("EUR prefix notation") {
            val result = exec("\u20AC50.25")
            result.shouldBeInstanceOf<Quantity<*>>()
        }

        test("GBP prefix notation") {
            val result = exec("\u00A375.50")
            result.shouldBeInstanceOf<Quantity<*>>()
        }

        test("JPY prefix notation") {
            val result = exec("\u00A510000")
            result.shouldBeInstanceOf<Quantity<*>>()
        }

        test("BTC prefix notation") {
            val result = exec("\u20BF0.5")
            result.shouldBeInstanceOf<Quantity<*>>()
        }

        test("ETH prefix notation") {
            val result = exec("\u039E2.5")
            result.shouldBeInstanceOf<Quantity<*>>()
        }

        test("USD suffix notation") {
            val result = exec("100USD")
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "100"
        }

        test("EUR suffix notation") {
            val result = exec("50.25EUR")
            result.shouldBeInstanceOf<Quantity<*>>()
        }

        test("currency addition (same currency)") {
            val source = """
                let a = ${'$'}100
                let b = ${'$'}50
                a + b
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "150"
        }

        test("currency subtraction") {
            val source = """
                let a = ${'$'}100
                let b = ${'$'}30
                a - b
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "70"
        }

        test("currency scalar multiplication") {
            val source = """
                let price = ${'$'}25
                price * 4
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "100"
        }

        test("currency comparison") {
            val source = """
                ${'$'}100 > ${'$'}50
            """.trimIndent()
            exec(source) shouldBe true
        }
    }

    context("Combine operator ⚭") {

        test("length × length = area: 4cm ⚭ 3cm") {
            val source = "4cm ⚭ 3cm"
            val result = exec(source)
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "12"
        }

        test("combine with variables") {
            val source = """
                let w = 5m
                let h = 3m
                w ⚭ h
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<Quantity<*>>()
            result.toString() shouldContain "15"
        }
    }

    // ====================================================================
    // Control Flow: if/else
    // ====================================================================

    context("If/else expressions") {

        test("if true returns then-branch") {
            exec("if true { 42 }") shouldBe 42
        }

        test("if false with else returns else-branch") {
            exec("if false { 1 } else { 2 }") shouldBe 2
        }

        test("if as expression") {
            val source = """
                let x = 10
                let result = if x > 5 { "big" } else { "small" }
                result
            """.trimIndent()
            exec(source) shouldBe "big"
        }

        test("if-else chain") {
            val source = """
                let x = 50
                if x < 0 { "negative" } else if x == 0 { "zero" } else { "positive" }
            """.trimIndent()
            exec(source) shouldBe "positive"
        }

        test("if without else and false condition returns nil") {
            exec("if false { 42 }").shouldBeNull()
        }
    }

    // ====================================================================
    // Control Flow: when expression
    // ====================================================================

    context("When expression") {

        test("when with subject - value matching") {
            val source = """
                let x = 2
                when x {
                    1 -> "one"
                    2 -> "two"
                    3 -> "three"
                    else -> "other"
                }
            """.trimIndent()
            exec(source) shouldBe "two"
        }

        test("when with else fallback") {
            val source = """
                let x = 99
                when x {
                    1 -> "one"
                    else -> "unknown"
                }
            """.trimIndent()
            exec(source) shouldBe "unknown"
        }

        test("when without subject (condition-style)") {
            val source = """
                let x = 42
                when {
                    x < 0 -> "negative"
                    x == 0 -> "zero"
                    x > 0 -> "positive"
                    else -> "impossible"
                }
            """.trimIndent()
            exec(source) shouldBe "positive"
        }

        test("when with type matching (is)") {
            val source = """
                let x = "hello"
                when x {
                    is Int -> "integer"
                    is String -> "string"
                    else -> "other"
                }
            """.trimIndent()
            exec(source) shouldBe "string"
        }

        test("when with range matching (in)") {
            val source = """
                let score = 85
                when score {
                    in 90..100 -> "A"
                    in 80..89 -> "B"
                    in 70..79 -> "C"
                    else -> "F"
                }
            """.trimIndent()
            exec(source) shouldBe "B"
        }

        test("when with enum and DPEC matching") {
            val source = """
                enum Color { RED GREEN BLUE }
                let c = Color.RED
                when c {
                    .RED -> "red"
                    .GREEN -> "green"
                    .BLUE -> "blue"
                    else -> "unknown"
                }
            """.trimIndent()
            exec(source) shouldBe "red"
        }

        test("when with block body") {
            val source = """
                let x = 2
                when x {
                    1 -> {
                        let v = "one"
                        v
                    }
                    2 -> {
                        let v = "two"
                        v
                    }
                    else -> "other"
                }
            """.trimIndent()
            exec(source) shouldBe "two"
        }
    }

    // ====================================================================
    // Control Flow: for statement
    // ====================================================================

    context("For statement") {

        test("for with explicit variable over list") {
            val (_, output) = execWithOutput("""
                for i in [1, 2, 3] {
                    say i
                }
            """.trimIndent())
            output shouldBe "1\n2\n3"
        }

        test("for over range") {
            val (_, output) = execWithOutput("""
                for i in 1..3 {
                    say i
                }
            """.trimIndent())
            output shouldBe "1\n2\n3"
        }

        test("for over exclusive range") {
            val (_, output) = execWithOutput("""
                for i in 0..<3 {
                    say i
                }
            """.trimIndent())
            output shouldBe "0\n1\n2"
        }

        test("for with break") {
            val (_, output) = execWithOutput("""
                for i in 1..10 {
                    if i > 3 break
                    say i
                }
            """.trimIndent())
            output shouldBe "1\n2\n3"
        }

        test("for with continue") {
            val (_, output) = execWithOutput("""
                for i in 1..5 {
                    if i == 3 continue
                    say i
                }
            """.trimIndent())
            output shouldBe "1\n2\n4\n5"
        }

        test("for over string iterates characters") {
            val (_, output) = execWithOutput("""
                for c in "abc" {
                    say c
                }
            """.trimIndent())
            output shouldBe "a\nb\nc"
        }

        test("for accumulation pattern") {
            val source = """
                var sum = 0
                for i in 1..10 {
                    sum += i
                }
                sum
            """.trimIndent()
            exec(source) shouldBe 55
        }

        test("nested for loops") {
            val source = """
                var count = 0
                for i in 1..3 {
                    for j in 1..3 {
                        count += 1
                    }
                }
                count
            """.trimIndent()
            exec(source) shouldBe 9
        }

        test("for over enum constants") {
            val (_, output) = execWithOutput("""
                enum Color { RED GREEN BLUE }
                for c in Color {
                    say c
                }
            """.trimIndent())
            output shouldBe "RED\nGREEN\nBLUE"
        }
    }

    // ====================================================================
    // Control Flow: while statement
    // ====================================================================

    context("While statement") {

        test("basic while loop") {
            val source = """
                var x = 0
                while x < 5 {
                    x += 1
                }
                x
            """.trimIndent()
            exec(source) shouldBe 5
        }

        test("while with break") {
            val source = """
                var x = 0
                while true {
                    x += 1
                    if x >= 3 break
                }
                x
            """.trimIndent()
            exec(source) shouldBe 3
        }

        test("while with continue") {
            val (_, output) = execWithOutput("""
                var x = 0
                while x < 5 {
                    x += 1
                    if x == 3 continue
                    say x
                }
            """.trimIndent())
            output shouldBe "1\n2\n4\n5"
        }

        test("while false never executes") {
            val source = """
                var x = 42
                while false {
                    x = 0
                }
                x
            """.trimIndent()
            exec(source) shouldBe 42
        }
    }

    // ====================================================================
    // Functions
    // ====================================================================

    context("Function declarations and calls") {

        test("simple function declaration and call") {
            val source = """
                fun add(a, b) { return a + b }
                add(3, 4)
            """.trimIndent()
            exec(source) shouldBe 7
        }

        test("single-expression function") {
            val source = """
                fun double(x) = x * 2
                double(21)
            """.trimIndent()
            exec(source) shouldBe 42
        }

        test("function with default parameters") {
            val source = """
                fun greet(name, greeting = "Hello") = "${'$'}greeting, ${'$'}name!"
                greet("World")
            """.trimIndent()
            exec(source) shouldBe "Hello, World!"
        }

        test("function with default param overridden") {
            val source = """
                fun greet(name, greeting = "Hello") = "${'$'}greeting, ${'$'}name!"
                greet("World", "Hi")
            """.trimIndent()
            exec(source) shouldBe "Hi, World!"
        }

        test("recursive function - factorial") {
            val source = """
                fun factorial(n) {
                    if n <= 1 return 1
                    return n * factorial(n - 1)
                }
                factorial(5)
            """.trimIndent()
            exec(source) shouldBe 120
        }

        test("recursive function - fibonacci") {
            val source = """
                fun fib(n) {
                    if n <= 1 return n
                    return fib(n - 1) + fib(n - 2)
                }
                fib(10)
            """.trimIndent()
            exec(source) shouldBe 55
        }

        test("closure captures environment") {
            val source = """
                fun makeCounter() {
                    var count = 0
                    fun increment() {
                        count += 1
                        return count
                    }
                    return increment
                }
                let counter = makeCounter()
                counter()
                counter()
                counter()
            """.trimIndent()
            exec(source) shouldBe 3
        }

        test("function returning nil implicitly") {
            val source = """
                fun doNothing() { }
                doNothing()
            """.trimIndent()
            exec(source).shouldBeNull()
        }
    }

    // ====================================================================
    // Classes
    // ====================================================================

    context("Class declarations and instantiation") {

        test("simple class with constructor params") {
            val source = """
                class Point(let x, let y)
                let p = Point(3, 4)
                p.x
            """.trimIndent()
            exec(source) shouldBe 3
        }

        test("class with method") {
            val source = """
                class Point(let x, let y) {
                    fun sum() = x + y
                }
                let p = Point(10, 20)
                p.sum()
            """.trimIndent()
            exec(source) shouldBe 30
        }

        test("class with mutable property") {
            val source = """
                class Counter(var count) {
                    fun increment() {
                        count += 1
                    }
                }
                let c = Counter(0)
                c.increment()
                c.increment()
                c.count
            """.trimIndent()
            exec(source) shouldBe 2
        }

        test("class with default constructor parameter") {
            val source = """
                class Greeter(let name, let greeting = "Hello") {
                    fun greet() = "${'$'}greeting, ${'$'}name!"
                }
                let g = Greeter("World")
                g.greet()
            """.trimIndent()
            exec(source) shouldBe "Hello, World!"
        }

        test("class with static block") {
            val source = """
                class MathUtils {
                    static {
                        let PI = 3.14159
                    }
                }
                MathUtils.PI
            """.trimIndent()
            val result = exec(source)
            result shouldBe 3.14159
        }
    }

    // ====================================================================
    // Traits
    // ====================================================================

    context("Trait declarations and implementation") {

        test("class implementing trait with default method") {
            val source = """
                trait Describable {
                    fun describe() = "I am describable"
                }
                class Widget: Describable
                let w = Widget()
                w.describe()
            """.trimIndent()
            exec(source) shouldBe "I am describable"
        }

        test("class overriding trait method") {
            val source = """
                trait Greeter {
                    fun greet() = "Hello"
                }
                class FrenchGreeter: Greeter {
                    fun greet() = "Bonjour"
                }
                let g = FrenchGreeter()
                g.greet()
            """.trimIndent()
            exec(source) shouldBe "Bonjour"
        }
    }

    // ====================================================================
    // Enums
    // ====================================================================

    context("Enum declarations") {

        test("simple enum") {
            val source = """
                enum Color { RED GREEN BLUE }
                Color.RED
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<KSEnumConstant>()
            result.toString() shouldBe "RED"
        }

        test("enum constant ordinal") {
            val source = """
                enum Color { RED GREEN BLUE }
                Color.GREEN.ordinal
            """.trimIndent()
            exec(source) shouldBe 1
        }

        test("enum constant name") {
            val source = """
                enum Color { RED GREEN BLUE }
                Color.BLUE.name
            """.trimIndent()
            exec(source) shouldBe "BLUE"
        }

        test("enum with value assignments") {
            val source = """
                enum Priority { LOW = 1 MEDIUM = 5 HIGH = 10 }
                Priority.HIGH
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<KSEnumConstant>()
            result.toString() shouldBe "HIGH"
        }

        test("enum with constructor params") {
            val source = """
                enum HttpStatus(code, msg) {
                    OK(200, "OK")
                    NOT_FOUND(404, "Not Found")
                }
                HttpStatus.NOT_FOUND.code
            """.trimIndent()
            exec(source) shouldBe 404
        }

        test("enum equality") {
            val source = """
                enum Color { RED GREEN BLUE }
                Color.RED == Color.RED
            """.trimIndent()
            exec(source) shouldBe true
        }

        test("enum inequality") {
            val source = """
                enum Color { RED GREEN BLUE }
                Color.RED == Color.BLUE
            """.trimIndent()
            exec(source) shouldBe false
        }
    }

    // ====================================================================
    // Collections
    // ====================================================================

    context("Lists") {

        test("list literal") {
            val result = exec("[1, 2, 3]")
            result shouldBe listOf(1, 2, 3)
        }

        test("empty list") {
            val result = exec("[]")
            result shouldBe emptyList<Any?>()
        }

        test("list index access") {
            exec("[10, 20, 30][1]") shouldBe 20
        }

        test("list size member") {
            exec("[1, 2, 3].size") shouldBe 3
        }

        test("list isEmpty") {
            exec("[].isEmpty") shouldBe true
        }

        test("list isNotEmpty") {
            exec("[1].isNotEmpty") shouldBe true
        }

        test("list first") {
            exec("[10, 20, 30].first") shouldBe 10
        }

        test("list last") {
            exec("[10, 20, 30].last") shouldBe 30
        }

        test("list reversed") {
            exec("[1, 2, 3].reversed") shouldBe listOf(3, 2, 1)
        }

        test("nested list") {
            val result = exec("[[1, 2], [3, 4]]")
            result shouldBe listOf(listOf(1, 2), listOf(3, 4))
        }

        test("list of mixed types") {
            val result = exec("""[1, "two", true, nil]""")
            result shouldBe listOf(1, "two", true, null)
        }
    }

    context("Maps") {

        test("map literal") {
            val result = exec("""["a" = 1, "b" = 2]""")
            result shouldBe mapOf("a" to 1, "b" to 2)
        }

        test("map index access") {
            exec("""["x" = 10, "y" = 20]["x"]""") shouldBe 10
        }

        test("map size") {
            exec("""["a" = 1, "b" = 2].size""") shouldBe 2
        }

        test("map isEmpty") {
            // Empty map is tricky - test with variable
            val source = """
                let m = ["a" = 1]
                m.isEmpty
            """.trimIndent()
            exec(source) shouldBe false
        }

        test("map keys") {
            val source = """
                let m = ["a" = 1, "b" = 2]
                m.keys
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<List<*>>()
            (result as List<*>).toSet() shouldBe setOf("a", "b")
        }

        test("map values") {
            val source = """
                let m = ["a" = 1, "b" = 2]
                m.values
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<List<*>>()
            (result as List<*>).toSet() shouldBe setOf(1, 2)
        }
    }

    // ====================================================================
    // String Members
    // ====================================================================

    context("String member access") {

        test("string length") {
            exec(""""hello".length""") shouldBe 5
        }

        test("string uppercase") {
            exec(""""hello".uppercase""") shouldBe "HELLO"
        }

        test("string lowercase") {
            exec(""""HELLO".lowercase""") shouldBe "hello"
        }

        test("string trim") {
            exec(""""  hello  ".trim""") shouldBe "hello"
        }

        test("string reversed") {
            exec(""""hello".reversed""") shouldBe "olleh"
        }

        test("string isEmpty on empty") {
            exec(""""".isEmpty""") shouldBe true
        }

        test("string isNotEmpty on non-empty") {
            exec(""""hello".isNotEmpty""") shouldBe true
        }

        test("string first") {
            exec(""""hello".first""") shouldBe 'h'
        }

        test("string last") {
            exec(""""hello".last""") shouldBe 'o'
        }

        test("string index access") {
            exec(""""hello"[1]""") shouldBe 'e'
        }
    }

    // ====================================================================
    // Ranges
    // ====================================================================

    context("Ranges") {

        test("inclusive range creation") {
            val result = exec("1..5")
            result.shouldBeInstanceOf<KSRange>()
        }

        test("exclusive end range creation") {
            val result = exec("0..<5")
            result.shouldBeInstanceOf<KSRange>()
            (result as KSRange).endExclusive shouldBe true
        }

        test("range contains check (in)") {
            exec("3 in 1..5") shouldBe true
        }

        test("range does not contain (in)") {
            exec("6 in 1..5") shouldBe false
        }

        test("range negated contains (!in)") {
            exec("6 !in 1..5") shouldBe true
        }

        test("exclusive range boundary") {
            exec("5 in 0..<5") shouldBe false
        }

        test("range start member") {
            val source = """
                let r = 1..10
                r.start
            """.trimIndent()
            exec(source) shouldBe 1
        }

        test("range end member") {
            val source = """
                let r = 1..10
                r.end
            """.trimIndent()
            exec(source) shouldBe 10
        }
    }

    // ====================================================================
    // Type Operations
    // ====================================================================

    context("Type checking and casting") {

        test("is Int") {
            exec("42 is Int") shouldBe true
        }

        test("is String") {
            exec(""""hello" is String""") shouldBe true
        }

        test("is Bool") {
            exec("true is Bool") shouldBe true
        }

        test("!is String on Int") {
            exec("42 !is String") shouldBe true
        }

        test("as Int from Double") {
            val source = """
                let x = 3.14
                x as Int
            """.trimIndent()
            exec(source) shouldBe 3
        }

        test("as String") {
            exec("42 as String") shouldBe "42"
        }

        test("nil is not Int") {
            exec("nil is Int") shouldBe false
        }
    }

    context("Pattern matching (matches)") {

        test("matches regex positive") {
            exec(""""hello123" matches @"[a-z]+\d+"""") shouldBe true
        }

        test("matches regex negative") {
            exec(""""hello" matches @"\d+"""") shouldBe false
        }
    }

    // ====================================================================
    // Try / Catch / Finally
    // ====================================================================

    context("Try/catch/finally") {

        test("try without error returns body value") {
            exec("try { 42 } catch(e) { -1 }") shouldBe 42
        }

        test("try with error caught") {
            val source = """
                try {
                    throw "boom"
                } catch(e) {
                    "caught"
                }
            """.trimIndent()
            exec(source) shouldBe "caught"
        }

        test("finally block executes on success") {
            val (_, output) = execWithOutput("""
                try {
                    say "body"
                } catch(e) {
                    say "catch"
                } finally {
                    say "finally"
                }
            """.trimIndent())
            output shouldContain "body"
            output shouldContain "finally"
        }

        test("finally block executes on error") {
            val (_, output) = execWithOutput("""
                try {
                    throw "error"
                } catch(e) {
                    say "caught"
                } finally {
                    say "cleanup"
                }
            """.trimIndent())
            output shouldContain "caught"
            output shouldContain "cleanup"
        }

        test("catch-all with wildcard") {
            val source = """
                try {
                    throw "anything"
                } catch(*) {
                    "caught all"
                }
            """.trimIndent()
            exec(source) shouldBe "caught all"
        }
    }

    // ====================================================================
    // Say Statement
    // ====================================================================

    context("Say statement") {

        test("say basic string") {
            val (_, output) = execWithOutput("""say "Hello, World!" """)
            output shouldBe "Hello, World!"
        }

        test("say integer") {
            val (_, output) = execWithOutput("say 42")
            output shouldBe "42"
        }

        test("say multiple arguments") {
            val (_, output) = execWithOutput("""say "a" "b" "c" """)
            output shouldBe "a b c"
        }

        test("say.error outputs to error stream") {
            val (_, error) = execWithError("""say.error "oops!" """)
            error shouldBe "oops!"
        }

        test("say.warn outputs warning") {
            val (_, output) = execWithOutput("""say.warn "caution" """)
            output shouldBe "caution"
        }

        test("say.note outputs bold text") {
            val (_, output) = execWithOutput("""say.note "important" """)
            output shouldBe "important"
        }

        test("say with interpolated string") {
            val (_, output) = execWithOutput("""
                let name = "Claude"
                say "Hello, ${'$'}name!"
            """.trimIndent())
            output shouldBe "Hello, Claude!"
        }

        test("say nil") {
            val (_, output) = execWithOutput("say nil")
            output shouldBe "nil"
        }
    }

    // ====================================================================
    // Lang KD Blocks
    // ====================================================================

    context("Lang KD blocks") {

        test("single KD tag with values") {
            val source = """
                lang KD {
                    book "The Hobbit"
                }
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<KDTag>()
            (result as KDTag).name shouldBe "book"
            result.values shouldBe listOf("The Hobbit")
        }

        test("KD tag with attributes") {
            val source = """
                lang KD {
                    book "The Hobbit" author="Tolkien" year=1937
                }
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<KDTag>()
            (result as KDTag).attributes["author"] shouldBe "Tolkien"
            result.attributes["year"] shouldBe 1937
        }

        test("KD tag name access") {
            val source = """
                let tag = lang KD {
                    book "Dune"
                }
                tag.name
            """.trimIndent()
            exec(source) shouldBe "book"
        }

        test("KD tag values access") {
            val source = """
                let tag = lang KD {
                    point 3 4
                }
                tag.values
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<List<*>>()
            result shouldBe listOf(3, 4)
        }

        test("KD tag attribute access by name") {
            val source = """
                let tag = lang KD {
                    person name="Ada" age=36
                }
                tag.name
            """.trimIndent()
            // tag.name returns the tag name itself, not the attribute
            exec(source) shouldBe "person"
        }

        test("KD tag attribute access via attributes map") {
            val source = """
                let tag = lang KD {
                    person name="Ada" age=36
                }
                tag.attributes
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<Map<*, *>>()
            (result as Map<*, *>)["name"] shouldBe "Ada"
            result["age"] shouldBe 36
        }

        test("KD tag with children") {
            val source = """
                let doc = lang KD {
                    library {
                        book "The Hobbit"
                        book "Dune"
                    }
                }
                doc.children.size
            """.trimIndent()
            exec(source) shouldBe 2
        }

        test("multiple root tags produce KDDocument") {
            val source = """
                let doc = lang KD {
                    book "The Hobbit"
                    book "Dune"
                }
                doc.size
            """.trimIndent()
            exec(source) shouldBe 2
        }

        test("KDDocument tag access by index") {
            val source = """
                let doc = lang KD {
                    book "The Hobbit"
                    book "Dune"
                }
                doc[0].values
            """.trimIndent()
            val result = exec(source)
            result shouldBe listOf("The Hobbit")
        }

        test("KD tag with annotation") {
            val source = """
                lang KD {
                    @Important book "Special"
                }
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<KDTag>()
            (result as KDTag).annotations.size shouldBe 1
            result.annotations[0].name shouldBe "Important"
        }

        test("KD tag with boolean and nil values") {
            val source = """
                lang KD {
                    config true nil 42
                }
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<KDTag>()
            (result as KDTag).values shouldBe listOf(true, null, 42)
        }
    }

    // ====================================================================
    // Reflection
    // ====================================================================

    context("Reflection with ::class") {

        test("Int type") {
            val result = exec("42::class")
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "Int"
        }

        test("String type") {
            val result = exec(""""hello"::class""")
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "String"
        }

        test("Bool type") {
            val result = exec("true::class")
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "Bool"
        }

        test("Nil type") {
            val result = exec("nil::class")
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "Nil"
        }

        test("List type") {
            val result = exec("[1, 2, 3]::class")
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "List"
        }

        test("Quantity type") {
            val result = exec("23cm::class")
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "Quantity"
        }

        test("user class type") {
            val source = """
                class Dog(let name)
                let d = Dog("Rex")
                d::class
            """.trimIndent()
            val result = exec(source)
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "Dog"
        }
    }

    // ====================================================================
    // Truthiness
    // ====================================================================

    context("Truthiness") {

        test("nil is falsy") {
            exec("if nil { true } else { false }") shouldBe false
        }

        test("false is falsy") {
            exec("if false { true } else { false }") shouldBe false
        }

        test("0 is falsy") {
            exec("if 0 { true } else { false }") shouldBe false
        }

        test("empty string is falsy") {
            exec("""if "" { true } else { false }""") shouldBe false
        }

        test("empty list is falsy") {
            exec("if [] { true } else { false }") shouldBe false
        }

        test("non-zero is truthy") {
            exec("if 1 { true } else { false }") shouldBe true
        }

        test("non-empty string is truthy") {
            exec("""if "hello" { true } else { false }""") shouldBe true
        }

        test("non-empty list is truthy") {
            exec("if [1] { true } else { false }") shouldBe true
        }

        test("quantity is truthy") {
            exec("if 5cm { true } else { false }") shouldBe true
        }
    }

    // ====================================================================
    // Scoping
    // ====================================================================

    context("Lexical scoping") {

        test("block creates new scope") {
            val source = """
                var x = 1
                {
                    var x = 2
                    x
                }
            """.trimIndent()
            // Block returns the inner x
            exec(source) shouldBe 2
        }

        test("outer variable accessible in inner scope") {
            val source = """
                var x = 10
                {
                    x + 5
                }
            """.trimIndent()
            exec(source) shouldBe 15
        }

        test("inner scope modification affects outer via assignment") {
            val source = """
                var x = 10
                {
                    x = 20
                }
                x
            """.trimIndent()
            exec(source) shouldBe 20
        }
    }

    // ====================================================================
    // Constraints
    // ====================================================================

    context("Constraints") {

        test("comparison constraint satisfied") {
            val source = """
                let age: Int > 0 = 25
                age
            """.trimIndent()
            exec(source) shouldBe 25
        }

        test("range constraint satisfied") {
            val source = """
                let score: Int 0..100 = 85
                score
            """.trimIndent()
            exec(source) shouldBe 85
        }
    }

    // ====================================================================
    // Ternary Expressions
    // ====================================================================

    context("Ternary expressions") {

        test("ternary true branch") {
            exec("true ? 1 : 2") shouldBe 1
        }

        test("ternary false branch") {
            exec("false ? 1 : 2") shouldBe 2
        }

        test("ternary with complex condition") {
            val source = """
                let x = 10
                (x > 5) ? "big" : "small"
            """.trimIndent()
            exec(source) shouldBe "big"
        }
    }

    // ====================================================================
    // Division by Zero & Edge Cases
    // ====================================================================

    context("Edge cases") {

        test("integer division truncates") {
            exec("7 / 2") shouldBe 3
        }

        test("double division preserves fraction") {
            exec("7.0 / 2.0") shouldBe 3.5
        }

        test("large exponent") {
            exec("2 ** 20") shouldBe 1048576
        }

        test("string + number concatenation") {
            exec(""""count: " + 42""") shouldBe "count: 42"
        }

        test("number + string concatenation") {
            exec("""42 + " items"""") shouldBe "42 items"
        }
    }

    // ====================================================================
    // Containment (in / !in)
    // ====================================================================

    context("Containment checks") {

        test("in list") {
            exec("2 in [1, 2, 3]") shouldBe true
        }

        test("not in list") {
            exec("5 !in [1, 2, 3]") shouldBe true
        }

        test("in string") {
            exec(""""ell" in "hello"""") shouldBe true
        }

        test("in range") {
            exec("5 in 1..10") shouldBe true
        }

        test("in map (checks keys)") {
            exec(""""a" in ["a" = 1, "b" = 2]""") shouldBe true
        }
    }

    // ====================================================================
    // Complex Integration Tests
    // ====================================================================

    context("Integration tests") {

        test("FizzBuzz") {
            val (_, output) = execWithOutput("""
                for i in 1..15 {
                    if i % 15 == 0 {
                        say "FizzBuzz"
                    } else if i % 3 == 0 {
                        say "Fizz"
                    } else if i % 5 == 0 {
                        say "Buzz"
                    } else {
                        say i
                    }
                }
            """.trimIndent())
            val lines = output.split("\n")
            lines[0] shouldBe "1"
            lines[2] shouldBe "Fizz"
            lines[4] shouldBe "Buzz"
            lines[14] shouldBe "FizzBuzz"
        }

        test("class with methods and state") {
            val source = """
                class Stack(var items = []) {
                    fun push(item) {
                        items = items + [item]
                    }
                    fun size() = items.size
                }
                let s = Stack()
                s.push(1)
                s.push(2)
                s.push(3)
                s.size()
            """.trimIndent()
            exec(source) shouldBe 3
        }

        test("enum with when and DPEC") {
            val source = """
                enum Season { SPRING SUMMER FALL WINTER }
                fun describe(s) {
                    return when s {
                        .SPRING -> "flowers"
                        .SUMMER -> "sun"
                        .FALL -> "leaves"
                        .WINTER -> "snow"
                        else -> "unknown"
                    }
                }
                describe(Season.FALL)
            """.trimIndent()
            exec(source) shouldBe "leaves"
        }

        test("quantity in expressions with comparisons") {
            val source = """
                let distance = 100m
                let threshold = 50m
                if distance > threshold { "far" } else { "close" }
            """.trimIndent()
            exec(source) shouldBe "far"
        }

        test("higher-order function pattern") {
            val source = """
                fun apply(f, x) = f(x)
                fun square(n) = n * n
                apply(square, 7)
            """.trimIndent()
            exec(source) shouldBe 49
        }

        test("complex when with multiple matcher types") {
            val source = """
                fun classify(x) {
                    return when x {
                        is String -> "text"
                        in 1..10 -> "small number"
                        42 -> "the answer"
                        else -> "something else"
                    }
                }
                let r1 = classify("hi")
                let r2 = classify(5)
                let r3 = classify(42)
                let r4 = classify(100)
                [r1, r2, r3, r4]
            """.trimIndent()
            exec(source) shouldBe listOf("text", "small number", "the answer", "something else")
        }

        test("multi-line program with many features") {
            val (_, output) = execWithOutput("""
                enum Animal { CAT DOG BIRD }

                fun makeSound(animal) {
                    return when animal {
                        .CAT -> "meow"
                        .DOG -> "woof"
                        .BIRD -> "tweet"
                        else -> "?"
                    }
                }

                for a in Animal {
                    say "${'$'}{a.name}: ${'$'}{makeSound(a)}"
                }
            """.trimIndent())
            output shouldContain "CAT: meow"
            output shouldContain "DOG: woof"
            output shouldContain "BIRD: tweet"
        }
    }
})