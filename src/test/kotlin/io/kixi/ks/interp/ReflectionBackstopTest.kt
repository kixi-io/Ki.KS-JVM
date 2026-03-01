package io.kixi.ks.interp

import io.kixi.ks.KSRuntime
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Tests for the universal JVM reflection backstop in ExpressionEvaluator.
 *
 * The backstop ensures that ANY public member on a JVM object is accessible
 * from KS code without requiring manual registration in the curated
 * `getXxxMember` methods. It works in three tiers:
 *
 *   1. Kotlin reflection (`memberProperties`) — correct property detection
 *   2. Java reflection — method resolution with overload dispatch
 *   3. Java getter convention — `getFoo()` accessible as `.foo`
 *
 * ## Test Strategy
 *
 * Each section targets a specific aspect of the backstop:
 *
 *   - **Curated regression**: Members that ARE in curated `when` blocks
 *     still work (fast path preserved).
 *   - **Reflection on known types**: Members that exist on Ki.Core types
 *     but are NOT in the curated blocks — resolved via fallback.
 *   - **Reflection on arbitrary JVM objects**: Objects with no curated
 *     handler at all — pure reflection path.
 *   - **Portable mode**: hostLang=false blocks the reflection backstop;
 *     only curated members are accessible.
 *   - **Error conditions**: Nonexistent members, safe navigation, etc.
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.ReflectionBackstopTest"
 */
class ReflectionBackstopTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * Execute KS source with interop mode (hostLang=true) and capture stdout.
     */
    fun run(source: String): String {
        val output = StringWriter()
        val error = StringWriter()
        val runtime = KSRuntime(
            hostLang = true,
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
     * Execute KS source with interop mode and return the last expression result.
     */
    fun eval(source: String): Any? {
        val output = StringWriter()
        val runtime = KSRuntime(
            hostLang = true,
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
     * Execute KS source in portable mode (hostLang=false) and capture stdout.
     */
    fun runPortable(source: String): String {
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
     * Execute KS source and expect a runtime error. Returns the error message.
     */
    fun runExpectingError(source: String, hostLang: Boolean = true): String {
        val output = StringWriter()
        val runtime = KSRuntime(
            hostLang = hostLang,
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
    // 1. Curated Members Still Work (Regression / Fast Path)
    // ====================================================================

    context("Curated members — fast path regression") {

        test("Version curated properties") {
            run("""
                let v = Version(1, 2, 3, "beta", 4)
                say v.major
                say v.minor
                say v.micro
                say v.qualifier
                say v.qualifierNumber
                say v.hasQualifier
                say v.isStable
                say v.isPreRelease
            """.trimIndent()) shouldBe "1\n2\n3\nbeta\n4\ntrue\nfalse\ntrue"
        }

        test("Version curated methods") {
            run("""
                say Version(5, 0, 0).toShortString()
                say Version(1, 2, 3).incrementMajor()
                say Version(1, 2, 3).incrementMinor()
                say Version(1, 2, 3).incrementMicro()
                say Version(1, 0, 0, "beta").toStable()
            """.trimIndent()) shouldBe "5\n2.0.0\n1.3.0\n1.2.4\n1.0.0"
        }

        test("Grid curated properties") {
            run("""
                let g = Grid(3, 4, 0)
                say g.width
                say g.height
                say g.size
                say g.isEmpty
                say g.isNotEmpty
            """.trimIndent()) shouldBe "3\n4\n12\nfalse\ntrue"
        }

        test("Grid curated methods") {
            run("""
                let g = Grid(2, 2, 1)
                say g.toList()
                say g.copy().width
                say g.getRowCopy(0)
            """.trimIndent()) shouldBe "[1, 1, 1, 1]\n2\n[1, 1]"
        }

        test("Email curated properties") {
            run("""
                let e = Email.of("dan+spam@leuck.org")
                say e.address
                say e.localPart
                say e.domain
                say e.tld
                say e.hasTag
                say e.tag
                say e.baseLocalPart
            """.trimIndent()) shouldBe "dan+spam@leuck.org\ndan+spam\nleuck.org\norg\ntrue\nspam\ndan"
        }

        test("Coordinate curated properties") {
            run("""
                let c = Coordinate(3, 5)
                say c.x
                say c.y
                say c.column
                say c.row
                say c.hasZ
                say c.isOrigin
            """.trimIndent()) shouldBe "3\n5\nD\n6\nfalse\nfalse"
        }

        test("Blob curated members") {
            run("""
                let b = Blob("hello")
                say b.size
                say b.isEmpty
                say b.isNotEmpty
                say b.decodeToString()
            """.trimIndent()) shouldBe "5\nfalse\ntrue\nhello"
        }

        test("Range curated properties") {
            run("""
                let r = 1..10
                say r.start
                say r.end
                say r.isOpen
                say r.isClosed
            """.trimIndent()) shouldBe "1\n10\nfalse\ntrue"
        }

        test("String curated members") {
            run("""
                let s = "Hello, World!"
                say s.size
                say s.length
                say s.uppercase
                say s.isEmpty
            """.trimIndent()) shouldBe "13\n13\nHELLO, WORLD!\nfalse"
        }

        test("List curated members") {
            run("""
                let list = [1, 2, 3]
                say list.size
                say list.isEmpty
                say list.first
                say list.last
            """.trimIndent()) shouldBe "3\nfalse\n1\n3"
        }

        test("Map curated members") {
            run("""
                let m = ["a" = 1, "b" = 2]
                say m.size
                say m.isEmpty
                say m.keys
            """.trimIndent()) shouldBe "2\nfalse\n[a, b]"
        }
    }

    // ====================================================================
    // 2. Reflection Fallback on Known Types (hostLang=true)
    //
    // Members on Ki.Core JVM classes resolved via the universal reflection
    // backstop. For Range, all members happen to be curated in
    // getRangeMember — these tests verify they still work through
    // resolveWithFallback's fast path. For Grid, elementType/rows/columns
    // are genuinely un-curated and go through resolveObjectMember.
    // ====================================================================

    context("Range members via resolveWithFallback fast path") {

        test("Range.isOpenStart property via reflection") {
            run("""
                let r = 1..10
                say r.isOpenStart
            """.trimIndent()) shouldBe "false"
        }

        test("Range.isOpenEnd property via reflection") {
            run("""
                let r = 1..10
                say r.isOpenEnd
            """.trimIndent()) shouldBe "false"
        }

        test("Range.min and max via reflection") {
            run("""
                let r = 3..7
                say r.min
                say r.max
            """.trimIndent()) shouldBe "3\n7"
        }

        test("Range.reversed property via reflection") {
            run("""
                let r = 1..5
                say r.reversed
            """.trimIndent()) shouldBe "false"
        }

        test("Range.reversed is true for descending range") {
            run("""
                let r = 5..1
                say r.reversed
            """.trimIndent()) shouldBe "true"
        }

        test("Range.overlaps method via reflection") {
            run("""
                let a = 1..10
                let b = 5..15
                let c = 20..30
                say a.overlaps(b)
                say a.overlaps(c)
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("Range.intersect method via reflection") {
            run("""
                let a = 1..10
                let b = 5..15
                let result = a.intersect(b)
                say result.start
                say result.end
            """.trimIndent()) shouldBe "5\n10"
        }

        test("Range.clamp method via reflection") {
            run("""
                let r = 1..10
                say r.clamp(5)
                say r.clamp(-3)
                say r.clamp(99)
            """.trimIndent()) shouldBe "5\n1\n10"
        }
    }

    context("Reflection fallback — Grid uncurated members") {

        test("Grid.elementType property via reflection") {
            // elementType is a constructor property not in the curated when block
            // For Grid(3, 3, 0), elementType will be java.lang.Integer
            val result = eval("""
                let g = Grid(3, 3, 0)
                g.elementType
            """.trimIndent())
            result.shouldBeInstanceOf<Class<*>>()
        }

        test("Grid.rows property via reflection") {
            // rows is a Kotlin val returning RowAccessor — not in curated when
            run("""
                let g = Grid(3, 2, 0)
                say g.rows.size
            """.trimIndent()) shouldBe "2"
        }

        test("Grid.columns property via reflection") {
            // columns is a Kotlin val returning ColumnAccessor — not curated
            run("""
                let g = Grid(3, 2, 0)
                say g.columns.size
            """.trimIndent()) shouldBe "3"
        }
    }

    context("Reflection fallback — Blob uncurated members") {

        test("Blob.toStringUrlSafe via reflection") {
            // toStringUrlSafe() is a function on Blob not in curated getBlobMember
            run("""
                let b = Blob("Hello")
                let result = b.toStringUrlSafe()
                say result.contains(".blob(")
            """.trimIndent()) shouldBe "true"
        }
    }

    context("Reflection fallback — Version uncurated members") {

        test("Version.satisfies via reflection") {
            // satisfies(range) is not in curated getVersionMember
            run("""
                let v = Version(2, 0, 0)
                let r = Range(Version(1, 0, 0), Version(3, 0, 0))
                say v.satisfies(r)
            """.trimIndent()) shouldBe "true"
        }

        test("Version.satisfies false case") {
            run("""
                let v = Version(5, 0, 0)
                let r = Range(Version(1, 0, 0), Version(3, 0, 0))
                say v.satisfies(r)
            """.trimIndent()) shouldBe "false"
        }

        test("Version.hashCode via reflection") {
            // hashCode() inherited from Any — not curated
            val result = eval("""
                Version(1, 2, 3).hashCode()
            """.trimIndent())
            result.shouldBeInstanceOf<Int>()
        }
    }

    context("Reflection fallback — Email uncurated members") {

        test("Email.withoutTag via curated then reflection chain") {
            // withoutTag is curated, but the returned Email's members
            // exercise the full chain
            run("""
                let e = Email.of("dan+spam@leuck.org")
                let clean = e.withoutTag()
                say clean.address
                say clean.hasTag
            """.trimIndent()) shouldBe "dan@leuck.org\nfalse"
        }
    }

    // ====================================================================
    // 3. Reflection on Arbitrary JVM Objects (else branch)
    //
    // Objects from `use` imports that have NO curated getXxxMember method.
    // These go through the full path: evaluateMemberAccess -> else ->
    // resolveWithFallback -> CuratedNotFound -> resolveObjectMember.
    // ====================================================================

    context("Reflection on arbitrary JVM objects") {

        test("StringBuilder — no curated handler, Kotlin property") {
            run("""
                use java.lang.StringBuilder
                let sb = StringBuilder("hello")
                say sb.length
            """.trimIndent()) shouldBe "5"
        }

        test("StringBuilder — method returning NativeCallable") {
            run("""
                use java.lang.StringBuilder
                let sb = StringBuilder()
                sb.append("Hello")
                sb.append(" ")
                sb.append("World")
                say sb.toString()
            """.trimIndent()) shouldBe "Hello World"
        }

        test("StringBuilder — zero-arg method auto-invokes") {
            run("""
                use java.lang.StringBuilder
                let sb = StringBuilder("abc")
                let reversed = sb.reverse()
                say reversed.toString()
            """.trimIndent()) shouldBe "cba"
        }

        test("LocalDate — Kotlin/Java class with properties and methods") {
            run("""
                use java.time.LocalDate
                let d = LocalDate.of(2025, 6, 15)
                say d.year
                say d.monthValue
                say d.dayOfMonth
            """.trimIndent()) shouldBe "2025\n6\n15"
        }

        test("LocalDate — method with parameters") {
            run("""
                use java.time.LocalDate
                let d = LocalDate.of(2025, 1, 15)
                let d2 = d.plusDays(10)
                say d2.dayOfMonth
            """.trimIndent()) shouldBe "25"
        }

        test("LocalDate — zero-arg method as property-style access") {
            // getDayOfWeek() should be accessible as .dayOfWeek via getter convention
            run("""
                use java.time.LocalDate
                let d = LocalDate.of(2025, 6, 15)
                say d.dayOfWeek
            """.trimIndent()) shouldBe "SUNDAY"
        }

        test("Random — instantiation and method call") {
            // Random is a pure JVM class with no curated handler
            run("""
                use java.util.Random
                let r = Random(42)
                let n = r.nextInt(100)
                say n is Int
            """.trimIndent()) shouldBe "true"
        }

        test("UUID — static method and property access") {
            run("""
                use java.util.UUID
                let id = UUID.randomUUID()
                let s = id.toString()
                say s.size > 0
                say s.contains("-")
            """.trimIndent()) shouldBe "true\ntrue"
        }

        test("ArrayList — JVM collection with no curated handler path") {
            // When obtained via `use`, ArrayList goes through else -> resolveWithFallback
            // But it also is List<*> which has a curated handler. This tests that
            // curated handler fires first (via is List<*> check).
            run("""
                use java.util.ArrayList
                let list = ArrayList()
                list.add("hello")
                list.add("world")
                say list.size
                say list[0]
            """.trimIndent()) shouldBe "2\nhello"
        }
    }

    // ====================================================================
    // 4. Java Getter Convention
    //
    // Tests that `getFoo()` methods are accessible as `.foo` via the
    // getter convention tier in resolveObjectMember.
    // ====================================================================

    context("Java getter convention") {

        test("LocalDate getYear accessible as .year") {
            // LocalDate.getYear() should be accessible as .year
            run("""
                use java.time.LocalDate
                let d = LocalDate.of(2025, 3, 1)
                say d.year
            """.trimIndent()) shouldBe "2025"
        }

        test("LocalDate getMonthValue accessible as .monthValue") {
            run("""
                use java.time.LocalDate
                let d = LocalDate.of(2025, 6, 15)
                say d.monthValue
            """.trimIndent()) shouldBe "6"
        }
    }

    // ====================================================================
    // 5. Portable Mode (hostLang=false) Blocks Reflection
    //
    // In portable mode, only curated members are available. Un-curated
    // members that would otherwise resolve via reflection should throw
    // MemberNotFoundError.
    // ====================================================================

    context("Portable mode — reflection blocked") {

        test("curated members work in portable mode") {
            runPortable("""
                let v = Version(1, 2, 3)
                say v.major
                say v.minor
                say v.micro
            """.trimIndent()) shouldBe "1\n2\n3"
        }

        test("curated Grid members work in portable mode") {
            runPortable("""
                let g = Grid(3, 3, 0)
                say g.width
                say g.height
                say g.size
            """.trimIndent()) shouldBe "3\n3\n9"
        }

        test("curated Range members work in portable mode") {
            runPortable("""
                let r = 1..10
                say r.start
                say r.end
                say r.isClosed
            """.trimIndent()) shouldBe "1\n10\ntrue"
        }

        test("un-curated Grid.elementType blocked in portable mode") {
            val error = runExpectingError("""
                let g = Grid(3, 3, 0)
                say g.elementType
            """.trimIndent(), hostLang = false)
            error shouldContain "elementType"
        }

        test("un-curated Grid.columns blocked in portable mode") {
            val error = runExpectingError("""
                let g = Grid(3, 3, 0)
                say g.columns
            """.trimIndent(), hostLang = false)
            error shouldContain "columns"
        }

        test("un-curated Grid.rows blocked in portable mode") {
            val error = runExpectingError("""
                let g = Grid(3, 3, 0)
                say g.rows
            """.trimIndent(), hostLang = false)
            error shouldContain "rows"
        }
    }

    // ====================================================================
    // 6. Error Conditions
    // ====================================================================

    context("Error conditions") {

        test("nonexistent member on Version — even reflection fails") {
            val error = runExpectingError("""
                let v = Version(1, 0, 0)
                say v.totallyFakeMember
            """.trimIndent())
            error shouldContain "totallyFakeMember"
        }

        test("nonexistent member on Grid — even reflection fails") {
            val error = runExpectingError("""
                let g = Grid(2, 2, 0)
                say g.nonExistent
            """.trimIndent())
            error shouldContain "nonExistent"
        }

        test("nonexistent member on Range — even reflection fails") {
            val error = runExpectingError("""
                let r = 1..10
                say r.doesNotExist
            """.trimIndent())
            error shouldContain "doesNotExist"
        }

        test("nonexistent member on arbitrary JVM object") {
            val error = runExpectingError("""
                use java.lang.StringBuilder
                let sb = StringBuilder("test")
                say sb.completelyMadeUp
            """.trimIndent())
            error shouldContain "completelyMadeUp"
        }

        test("safe navigation with reflection") {
            run("""
                let v = nil
                say v?.major
            """.trimIndent()) shouldBe "nil"
        }

        test("null access on reflected member throws") {
            val error = runExpectingError("""
                let v = nil
                say v.major
            """.trimIndent())
            error shouldContain "nil"
        }
    }

    // ====================================================================
    // 7. Method Overload Resolution via Reflection
    //
    // Methods with multiple overloads should be dispatched correctly
    // based on arity.
    // ====================================================================

    context("Method overload resolution") {

        test("StringBuilder.append dispatches by arity") {
            run("""
                use java.lang.StringBuilder
                let sb = StringBuilder()
                sb.append("hello")
                sb.append(42)
                sb.append(true)
                say sb.toString()
            """.trimIndent()) shouldBe "hello42true"
        }

        test("wrong arity throws descriptive error") {
            val error = runExpectingError("""
                let v = Version(1, 0, 0)
                v.isCompatibleWith()
            """.trimIndent())
            error shouldContain "argument"
        }
    }

    // ====================================================================
    // 8. Integration — Chaining Reflected Members
    //
    // Ensures reflected member access composes with other KS features
    // like method chaining, assignments, and expressions.
    // ====================================================================

    context("Integration — chaining and composition") {

        test("chain reflected property into curated method") {
            run("""
                let r = 1..10
                let m = r.min
                say m + 5
            """.trimIndent()) shouldBe "6"
        }

        test("reflected method result used in expression") {
            run("""
                let a = 1..10
                let b = 8..20
                let c = a.intersect(b)
                say c.start + c.end
            """.trimIndent()) shouldBe "18"
        }

        test("reflected Grid property chained into further access") {
            run("""
                let g = Grid(4, 3, 0)
                g[1, 0] = 42
                let rowAccessor = g.rows
                let allRows = rowAccessor.toList()
                say allRows[0][1]
            """.trimIndent()) shouldBe "42"
        }

        test("store reflected callable in variable and invoke") {
            run("""
                let r = 1..100
                let clamper = r.clamp
                say clamper(-5)
                say clamper(50)
                say clamper(200)
            """.trimIndent()) shouldBe "1\n50\n100"
        }

        test("reflected member in for loop") {
            run("""
                let ranges = [1..5, 10..20, 100..200]
                for r in ranges {
                    say r.min
                }
            """.trimIndent()) shouldBe "1\n10\n100"
        }

        test("reflected member in when expression") {
            run("""
                let r = 1..10
                let desc = when {
                    r.min == 1 && r.max == 10 -> "standard"
                    else -> "other"
                }
                say desc
            """.trimIndent()) shouldBe "standard"
        }

        test("reflected member as function argument") {
            run("""
                fun describe(min, max): String = "range ${'$'}min to ${'$'}max"
                let r = 3..7
                say describe(r.min, r.max)
            """.trimIndent()) shouldBe "range 3 to 7"
        }

        test("LocalDate method chaining via reflection") {
            run("""
                use java.time.LocalDate
                let d = LocalDate.of(2025, 1, 1)
                let result = d.plusMonths(6).plusDays(14)
                say result.monthValue
                say result.dayOfMonth
            """.trimIndent()) shouldBe "7\n15"
        }
    }

    // ====================================================================
    // 9. Reflection on Curated Type — Curated Wins, Reflection Fallback
    //
    // Verifies that curated entries are the fast path and reflection
    // only fires when curated doesn't match.
    // ====================================================================

    context("Curated overrides reflection") {

        test("Grid.size returns KS override (width * height), not JVM property") {
            // The curated getGridMember defines `size` as `grid.width * grid.height`
            // which matches what Grid.kt defines. This confirms curated fires first.
            run("""
                let g = Grid(3, 4, 0)
                say g.size
            """.trimIndent()) shouldBe "12"
        }

        test("String.size returns KS override (length)") {
            // Curated getStringMember maps `size` -> str.length
            run("""
                say "hello".size
            """.trimIndent()) shouldBe "5"
        }

        test("Blob.size returns curated value") {
            run("""
                let b = Blob("test")
                say b.size
            """.trimIndent()) shouldBe "4"
        }
    }
})