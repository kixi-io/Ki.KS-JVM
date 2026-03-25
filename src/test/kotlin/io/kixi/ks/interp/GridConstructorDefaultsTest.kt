package io.kixi.ks.interp

import io.kixi.Grid
import io.kixi.ks.KSRuntime
import io.kixi.ks.RuntimeError
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Tests for Grid constructor default value behavior.
 *
 * Covers:
 *   - Non-nullable typed grids fill with type-appropriate zero-values
 *   - Nullable typed grids fill with nil
 *   - Untyped grids fill with nil
 *   - Explicit `default = value` named argument
 *   - Error cases: missing default for non-primitive types, type mismatches
 *   - Grid literal nil rejection for non-nullable types (pre-existing)
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.GridConstructorDefaultsTest"
 */
class GridConstructorDefaultsTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

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

    fun evalError(source: String): String {
        try {
            eval(source)
            throw AssertionError("Expected a RuntimeError but execution succeeded")
        } catch (e: RuntimeError) {
            return e.message ?: e.toString()
        }
    }

    // ====================================================================
    // Non-nullable typed grids: built-in zero-value defaults
    // ====================================================================

    context("Non-nullable typed Grid constructors use zero-value defaults") {

        test("Grid<Int> defaults to 0") {
            val result = eval("Grid<Int>(2, 2)")
            result.shouldBeInstanceOf<Grid<*>>()
            val grid = result as Grid<*>
            grid.width shouldBe 2
            grid.height shouldBe 2
            grid[0, 0] shouldBe 0
            grid[1, 0] shouldBe 0
            grid[0, 1] shouldBe 0
            grid[1, 1] shouldBe 0
        }

        test("Grid<Int> cell access returns 0") {
            eval(
                """
                var g = Grid<Int>(3, 3)
                g[1, 1]
                """.trimIndent()
            ) shouldBe 0
        }

        test("Grid<Long> defaults to 0L") {
            val result = eval("Grid<Long>(2, 1)")
            result.shouldBeInstanceOf<Grid<*>>()
            val grid = result as Grid<*>
            grid[0, 0] shouldBe 0L
            grid[1, 0] shouldBe 0L
        }

        test("Grid<Float> defaults to 0.0f") {
            val result = eval("Grid<Float>(1, 2)")
            result.shouldBeInstanceOf<Grid<*>>()
            val grid = result as Grid<*>
            grid[0, 0] shouldBe 0.0f
            grid[0, 1] shouldBe 0.0f
        }

        test("Grid<Double> defaults to 0.0") {
            val result = eval("Grid<Double>(2, 1)")
            result.shouldBeInstanceOf<Grid<*>>()
            val grid = result as Grid<*>
            grid[0, 0] shouldBe 0.0
            grid[1, 0] shouldBe 0.0
        }

        test("Grid<Dec> defaults to BigDecimal.ZERO") {
            val result = eval("Grid<Dec>(1, 1)")
            result.shouldBeInstanceOf<Grid<*>>()
            val grid = result as Grid<*>
            grid[0, 0] shouldBe java.math.BigDecimal.ZERO
        }

        test("Grid<String> defaults to empty string") {
            val result = eval("Grid<String>(2, 1)")
            result.shouldBeInstanceOf<Grid<*>>()
            val grid = result as Grid<*>
            grid[0, 0] shouldBe ""
            grid[1, 0] shouldBe ""
        }

        test("Grid<Bool> defaults to false") {
            val result = eval("Grid<Bool>(1, 2)")
            result.shouldBeInstanceOf<Grid<*>>()
            val grid = result as Grid<*>
            grid[0, 0] shouldBe false
            grid[0, 1] shouldBe false
        }

        test("Grid<Char> defaults to null char") {
            val result = eval("Grid<Char>(1, 1)")
            result.shouldBeInstanceOf<Grid<*>>()
            val grid = result as Grid<*>
            grid[0, 0] shouldBe '\u0000'
        }
    }

    // ====================================================================
    // Non-nullable typed grids: elementNullable = false
    // ====================================================================

    context("Non-nullable typed Grid metadata") {

        test("Grid<Int> has elementNullable = false") {
            val grid = eval("Grid<Int>(2, 2)") as Grid<*>
            grid.elementNullable shouldBe false
        }

        test("Grid<String> has elementNullable = false") {
            val grid = eval("Grid<String>(1, 1)") as Grid<*>
            grid.elementNullable shouldBe false
        }

        test("Grid<Int> has correct elementType") {
            val grid = eval("Grid<Int>(2, 2)") as Grid<*>
            grid.elementType shouldBe Int::class.javaObjectType
        }
    }

    // ====================================================================
    // Nullable typed grids: default is nil
    // ====================================================================

    context("Nullable typed Grid constructors default to nil") {

        test("Grid<Int?> defaults to nil") {
            val result = eval("Grid<Int?>(2, 2)")
            result.shouldBeInstanceOf<Grid<*>>()
            val grid = result as Grid<*>
            grid[0, 0].shouldBeNull()
            grid[1, 0].shouldBeNull()
            grid[0, 1].shouldBeNull()
            grid[1, 1].shouldBeNull()
        }

        test("Grid<Int?> has elementNullable = true") {
            val grid = eval("Grid<Int?>(2, 2)") as Grid<*>
            grid.elementNullable shouldBe true
        }

        test("Grid<String?> defaults to nil") {
            val grid = eval("Grid<String?>(1, 1)") as Grid<*>
            grid[0, 0].shouldBeNull()
            grid.elementNullable shouldBe true
        }
    }

    // ====================================================================
    // Untyped grids: default is nil (unchanged behavior)
    // ====================================================================

    context("Untyped Grid constructors default to nil") {

        test("Grid(2, 2) defaults to nil") {
            val result = eval("Grid(2, 2)")
            result.shouldBeInstanceOf<Grid<*>>()
            val grid = result as Grid<*>
            grid[0, 0].shouldBeNull()
            grid[1, 1].shouldBeNull()
        }

        test("Grid(2, 2) has elementNullable = true") {
            val grid = eval("Grid(2, 2)") as Grid<*>
            grid.elementNullable shouldBe true
        }
    }

    // ====================================================================
    // Explicit default = value (named argument)
    // ====================================================================

    context("Grid constructor with explicit default named argument") {

        test("Grid<Int> with default = 42") {
            val grid = eval("Grid<Int>(2, 3, default = 42)") as Grid<*>
            grid.width shouldBe 2
            grid.height shouldBe 3
            for (y in 0 until 3) {
                for (x in 0 until 2) {
                    grid[x, y] shouldBe 42
                }
            }
        }

        test("Grid<String> with default = hello") {
            val grid = eval("""Grid<String>(2, 1, default = "hello")""") as Grid<*>
            grid[0, 0] shouldBe "hello"
            grid[1, 0] shouldBe "hello"
        }

        test("Grid<Bool> with default = true") {
            val grid = eval("Grid<Bool>(1, 1, default = true)") as Grid<*>
            grid[0, 0] shouldBe true
        }

        test("Grid<Double> with default = 3.14") {
            val grid = eval("Grid<Double>(2, 1, default = 3.14)") as Grid<*>
            grid[0, 0] shouldBe 3.14
            grid[1, 0] shouldBe 3.14
        }

        test("Explicit default overrides built-in zero-value") {
            val grid = eval("Grid<Int>(2, 2, default = -1)") as Grid<*>
            grid[0, 0] shouldBe -1
            grid[1, 1] shouldBe -1
        }

        test("Explicit default with expression") {
            val grid = eval(
                """
                var x = 10
                Grid<Int>(2, 2, default = x * 5)
                """.trimIndent()
            ) as Grid<*>
            grid[0, 0] shouldBe 50
            grid[1, 1] shouldBe 50
        }

        test("Untyped grid with explicit default") {
            val grid = eval("Grid(2, 2, default = 99)") as Grid<*>
            grid[0, 0] shouldBe 99
            grid[1, 1] shouldBe 99
            grid.elementNullable shouldBe false
        }
    }

    // ====================================================================
    // Mutation after construction
    // ====================================================================

    context("Grid values can be mutated after default-initialized construction") {

        test("Set cell in Grid<Int> after construction") {
            eval(
                """
                var g = Grid<Int>(3, 3)
                g[1, 1] = 42
                g[1, 1]
                """.trimIndent()
            ) shouldBe 42
        }

        test("Other cells remain at default after setting one") {
            eval(
                """
                var g = Grid<Int>(2, 2)
                g[0, 0] = 99
                g[1, 1]
                """.trimIndent()
            ) shouldBe 0
        }
    }

    // ====================================================================
    // Error cases
    // ====================================================================

    context("Grid constructor error cases") {

        test("Grid<Int> default = nil is rejected") {
            val msg = evalError("Grid<Int>(2, 2, default = nil)")
            msg shouldContain "nil"
        }

        test("Grid<Int> default with wrong type is rejected") {
            val msg = evalError("""Grid<Int>(2, 2, default = "hello")""")
            msg shouldContain "incompatible type"
        }

        test("Unknown named argument is rejected") {
            val msg = evalError("Grid<Int>(2, 2, fill = 0)")
            msg shouldContain "unknown named argument"
        }

        test("Wrong number of positional arguments is rejected") {
            val msg = evalError("Grid<Int>(2)")
            msg shouldContain "2 positional arguments"
        }

        test("Three positional arguments treats third as default") {
            val grid = eval("Grid<Int>(2, 2, 42)") as Grid<*>
            grid[0, 0] shouldBe 42
            grid[1, 1] shouldBe 42
        }

        test("Four positional arguments is rejected") {
            val msg = evalError("Grid<Int>(2, 2, 0, 0)")
            msg shouldContain "positional arguments"
        }

        test("Non-Int width is rejected") {
            val msg = evalError("""Grid<Int>("two", 2)""")
            msg shouldContain "Int"
        }

        test("Non-Int height is rejected") {
            val msg = evalError("""Grid<Int>(2, "two")""")
            msg shouldContain "Int"
        }
    }

    // ====================================================================
    // Grid literal nil validation (pre-existing, verify still works)
    // ====================================================================

    context("Grid literal nil validation for non-nullable types") {

        test("Grid literal rejects nil in non-nullable typed grid") {
            val msg = evalError(
                """
                .grid<Int>(
                    nil 1
                    2   nil
                )
                """.trimIndent()
            )
            msg shouldContain "cannot contain nil"
            msg shouldContain "Grid<Int>"
        }

        test("Grid literal allows nil in nullable typed grid") {
            val grid = eval(
                """
                .grid<Int?>(
                    nil 1
                    2   nil
                )
                """.trimIndent()
            ) as Grid<*>
            grid[0, 0].shouldBeNull()
            grid[1, 0] shouldBe 1
            grid[0, 1] shouldBe 2
            grid[1, 1].shouldBeNull()
        }
    }

    // ====================================================================
    // Grid size property on default-initialized grids
    // ====================================================================

    context("Grid properties on default-initialized grids") {

        test("Grid<Int> size is width * height") {
            eval(
                """
                var g = Grid<Int>(4, 3)
                g.size
                """.trimIndent()
            ) shouldBe 12
        }

        test("Grid<Int> width and height are correct") {
            run(
                """
                var g = Grid<Int>(5, 2)
                say g.width
                say g.height
                """.trimIndent()
            ) shouldBe "5\n2"
        }
    }
})