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
 * Tests for declared return-type enforcement.
 *
 * The declared return type of a function/method is validated against the
 * value the body actually produces, using the same `InterpreterOps` checks
 * applied to parameters and variable declarations. Consequences exercised
 * here:
 *
 *   - Numeric widening applies: an `Int` result satisfies `Long`/`Double`/`Dec`.
 *   - Genuine mismatches (e.g. `Int` for `String`) raise a `TypeError`.
 *   - A nil result (a body ending in `say`, which returns nil) against a
 *     non-nullable return type is governed by `strictNullSafety`.
 *
 * The check runs in all three body executors, so the tests cover free
 * functions (`callFunction`), class methods (`callMethod`), and struct
 * methods (`callStructMethod` → `TypeDeclarationEvaluator`, the path that
 * was previously unchecked).
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.ReturnTypeTest"
 */
class ReturnTypeTest : FunSpec({

    // ====================================================================
    // Helpers (strictNullSafety configurable; default matches the runtime)
    // ====================================================================

    fun eval(source: String, strictNullSafety: Boolean = true): Any? {
        val runtime = KSRuntime(
            hostLang = false,
            strictNullSafety = strictNullSafety,
            colorOutput = false,
            outputWriter = PrintWriter(StringWriter(), true),
            errorWriter = PrintWriter(StringWriter(), true),
            debugMode = false
        )
        val tokens = Lexer(source).tokenize()
        val program = Parser(tokens).parse()
        return Interpreter(runtime).executeProgram(program)
    }

    fun runExpectingError(source: String, strictNullSafety: Boolean = true): String {
        val runtime = KSRuntime(
            hostLang = false,
            strictNullSafety = strictNullSafety,
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
    // Free functions — matching types and numeric widening (no throw)
    // ====================================================================

    context("free functions — accepted") {

        test("exact type passes") {
            eval("fun f(): Int = 42\nf()") shouldBe 42
        }

        test("Int result satisfies Long (widening)") {
            eval("fun f(): Long = 42\nf()") shouldBe 42
        }

        // Dec/Double widening: assert only that no TypeError is thrown.
        // (Value is not asserted to avoid Dec/Int comparison pitfalls.)
        test("Int result satisfies Dec (widening)") {
            eval("fun f(): Dec = 42\nf()")
        }

        test("Int result satisfies Double (widening)") {
            eval("fun f(): Double = 42\nf()")
        }

        test("no annotation performs no check") {
            eval("fun f() = 42\nf()") shouldBe 42
        }
    }

    // ====================================================================
    // Free functions — genuine mismatches throw
    // ====================================================================

    context("free functions — rejected") {

        test("Int returned for a String return type throws") {
            runExpectingError("fun f(): String = 42\nf()") shouldContain "String"
        }

        test("Int returned for a Bool return type throws") {
            runExpectingError("fun f(): Bool = 42\nf()") shouldContain "Bool"
        }

        test("mismatch message names the conflict") {
            runExpectingError("fun f(): String = 42\nf()") shouldContain "Cannot assign"
        }
    }

    // ====================================================================
    // Nil returns governed by strictNullSafety
    // ====================================================================

    context("nil returns and null safety") {

        test("non-null return ending in say throws under strict null safety") {
            // `say` returns nil, so the block's value is nil; Int is non-nullable.
            runExpectingError(
                "fun f(): Int { say \"x\" }\nf()",
                strictNullSafety = true
            ) shouldContain "Nil"
        }

        test("same body is allowed when strict null safety is off") {
            eval(
                "fun f(): Int { say \"x\" }\nf()",
                strictNullSafety = false
            ) shouldBe null
        }

        test("nullable return type allows nil under strict null safety") {
            eval(
                "fun f(): Int? { say \"x\" }\nf()",
                strictNullSafety = true
            ) shouldBe null
        }
    }

    // ====================================================================
    // Class methods (callMethod)
    // ====================================================================

    context("class methods") {

        test("matching return type passes") {
            eval(
                """
                class C { fun m(): Int = 42 }
                let c = C()
                c.m()
                """.trimIndent()
            ) shouldBe 42
        }

        test("mismatched return type throws") {
            runExpectingError(
                """
                class C { fun m(): String = 42 }
                let c = C()
                c.m()
                """.trimIndent()
            ) shouldContain "String"
        }
    }

    // ====================================================================
    // Struct methods (callStructMethod → TypeDeclarationEvaluator)
    // Previously unchecked — this is the bypass path that was fixed.
    // ====================================================================

    context("struct methods") {

        test("matching return type passes") {
            eval(
                """
                struct S(let x: Int) { fun m(): Int = 42 }
                let s = S(1)
                s.m()
                """.trimIndent()
            ) shouldBe 42
        }

        test("mismatched return type throws") {
            runExpectingError(
                """
                struct S(let x: Int) { fun m(): String = 42 }
                let s = S(1)
                s.m()
                """.trimIndent()
            ) shouldContain "String"
        }
    }
})