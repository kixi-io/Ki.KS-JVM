package io.kixi.ks

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kixi.ks.SourceLocation
import java.io.StringWriter

/**
 * Tests for the KS runtime infrastructure:
 * - RuntimeError hierarchy
 * - KSRuntime configuration
 * - ANSI color utilities
 */
class RuntimeTest : FunSpec({

    // ========================================================================
    // RuntimeError Tests
    // ========================================================================

    context("RuntimeError") {

        test("base error with location") {
            val loc = SourceLocation(line = 10, column = 5, offset = 100)
            val error = RuntimeError("Something went wrong", loc)

            error.message shouldContain "[10:5]"
            error.message shouldContain "Something went wrong"
            error.location shouldBe loc
        }

        test("base error without location") {
            val error = RuntimeError("Something went wrong")

            error.message shouldBe "Something went wrong"
            error.location shouldBe null
        }
    }

    context("TypeError") {

        test("type mismatch message") {
            val error = TypeError("Cannot add String and Boolean")
            error.message shouldContain "Cannot add String and Boolean"
        }

        test("with source location") {
            val loc = SourceLocation(line = 5, column = 10)
            val error = TypeError("Invalid operand types", loc)

            error.message shouldContain "[5:10]"
            error.location shouldBe loc
        }
    }

    context("CastError") {

        test("cast error with string value") {
            val error = CastError("hello", "Int")
            error.message shouldContain "Cannot cast"
            error.message shouldContain "\"hello\""
            error.message shouldContain "Int"
        }

        test("cast error with null value") {
            val error = CastError(null, "String")
            error.message shouldContain "nil"
            error.message shouldContain "String"
        }

        test("stores value and target type") {
            val error = CastError(42, "String")
            error.value shouldBe 42
            error.targetType shouldBe "String"
        }
    }

    context("ConstraintError") {

        test("constraint error with variable name") {
            val error = ConstraintError("age", "> 0", -5)

            error.message shouldContain "age"
            error.message shouldContain "> 0"
            error.message shouldContain "-5"
            error.variableName shouldBe "age"
            error.constraintExpr shouldBe "> 0"
            error.actualValue shouldBe -5
        }

        test("constraint error without variable name") {
            val error = ConstraintError(null, "1..100", 150)

            error.message shouldContain "value must satisfy"
            error.message shouldContain "1..100"
            error.message shouldContain "150"
            error.variableName shouldBe null
        }

        test("constraint error with string value") {
            val error = ConstraintError("name", "matches \"[A-Z]+\"", "abc123")
            error.message shouldContain "\"abc123\""
        }
    }

    context("UndefinedNameError") {

        test("undefined variable") {
            val error = UndefinedNameError("x", NameKind.VARIABLE)
            error.message shouldContain "Undefined variable"
            error.message shouldContain "'x'"
        }

        test("undefined function") {
            val error = UndefinedNameError("foo", NameKind.FUNCTION)
            error.message shouldContain "Undefined function"
            error.message shouldContain "'foo'"
        }

        test("undefined type") {
            val error = UndefinedNameError("Widget", NameKind.TYPE)
            error.message shouldContain "Undefined type"
            error.message shouldContain "'Widget'"
        }
    }

    context("ImmutableAssignmentError") {

        test("immutable assignment message") {
            val error = ImmutableAssignmentError("count")
            error.message shouldContain "Cannot reassign immutable variable"
            error.message shouldContain "'count'"
            error.message shouldContain "'let'"
        }
    }

    context("Control flow errors") {

        test("return outside function") {
            val error = ReturnOutsideFunctionError()
            error.message shouldContain "'return'"
            error.message shouldContain "outside of a function"
        }

        test("break outside loop") {
            val error = BreakOutsideLoopError()
            error.message shouldContain "'break'"
            error.message shouldContain "outside of a loop"
        }

        test("continue outside loop") {
            val error = ContinueOutsideLoopError()
            error.message shouldContain "'continue'"
            error.message shouldContain "outside of a loop"
        }
    }

    context("Control flow signals") {

        test("return value signal") {
            val signal = ReturnValue(42)
            signal.value shouldBe 42
            signal.shouldBeInstanceOf<RuntimeException>()
        }

        test("return value signal with null") {
            val signal = ReturnValue(null)
            signal.value shouldBe null
        }

        test("break signal") {
            val signal = BreakSignal()
            signal.shouldBeInstanceOf<RuntimeException>()
        }

        test("continue signal") {
            val signal = ContinueSignal()
            signal.shouldBeInstanceOf<RuntimeException>()
        }
    }

    context("Null safety errors") {

        test("null assertion error") {
            val error = NullAssertionError()
            error.message shouldContain "Non-null assertion failed"
            error.message shouldContain "nil"
        }

        test("null pointer error") {
            val error = NullPointerError()
            error.message shouldContain "Null pointer"
        }

        test("null pointer error with custom message") {
            val error = NullPointerError("Cannot access 'name' on nil")
            error.message shouldContain "Cannot access 'name' on nil"
        }
    }

    context("Invocation errors") {

        test("not callable error with int") {
            val error = NotCallableError(42)
            error.message shouldContain "42"
            error.message shouldContain "not callable"
        }

        test("not callable error with string") {
            val error = NotCallableError("hello")
            error.message shouldContain "\"hello\""
            error.message shouldContain "not callable"
        }

        test("arity error") {
            val error = ArityError("add", 2, 3)
            error.message shouldContain "add"
            error.message shouldContain "expects 2"
            error.message shouldContain "got 3"
        }

        test("unknown argument error") {
            val error = UnknownArgumentError("greet", "count")
            error.message shouldContain "greet"
            error.message shouldContain "'count'"
        }
    }

    context("Index/access errors") {

        test("index out of bounds") {
            val error = IndexOutOfBoundsError(10, 3)
            error.message shouldContain "Index 10"
            error.message shouldContain "size 3"
        }

        test("key not found") {
            val error = KeyNotFoundError("missing")
            error.message shouldContain "\"missing\""
            error.message shouldContain "not found"
        }

        test("member not found") {
            val error = MemberNotFoundError("z", "Point")
            error.message shouldContain "'z'"
            error.message shouldContain "'Point'"
        }
    }

    context("Arithmetic errors") {

        test("division by zero") {
            val error = DivisionByZeroError()
            error.message shouldContain "Division by zero"
        }

        test("overflow error") {
            val error = OverflowError()
            error.message shouldContain "overflow"
        }
    }

    context("Pattern matching errors") {

        test("non-exhaustive when") {
            val error = NonExhaustiveWhenError(42)
            error.message shouldContain "Non-exhaustive"
            error.message shouldContain "42"
            error.message shouldContain "no 'else'"
        }

        test("invalid pattern") {
            val error = InvalidPatternError("[invalid")
            error.message shouldContain "Invalid regex"
            error.message shouldContain "[invalid"
        }
    }

    // ========================================================================
    // KSRuntime Tests
    // ========================================================================

    context("KSRuntime") {

        test("default configuration") {
            val runtime = KSRuntime.DEFAULT

            runtime.hostLang shouldBe false
            runtime.strictNullSafety shouldBe true
            runtime.checkConstraints shouldBe true
            runtime.maxRecursionDepth shouldBe 1000
            runtime.colorOutput shouldBe true
            runtime.debugMode shouldBe false
        }

        test("interop configuration") {
            val runtime = KSRuntime.INTEROP

            runtime.hostLang shouldBe true
        }

        test("custom configuration") {
            val runtime = KSRuntime(
                hostLang = true,
                maxRecursionDepth = 500,
                colorOutput = false
            )

            runtime.hostLang shouldBe true
            runtime.maxRecursionDepth shouldBe 500
            runtime.colorOutput shouldBe false
        }

        test("fluent configuration") {
            val runtime = KSRuntime.DEFAULT
                .withHostLang(true)
                .withDebugMode(true)
                .withMaxRecursion(2000)

            runtime.hostLang shouldBe true
            runtime.debugMode shouldBe true
            runtime.maxRecursionDepth shouldBe 2000
        }

        test("testing configuration") {
            val output = StringWriter()
            val error = StringWriter()
            val runtime = KSRuntime.forTesting(output, error)

            runtime.hostLang shouldBe false
            runtime.colorOutput shouldBe false
            runtime.debugMode shouldBe true

            // Writers should be connected
            runtime.outputWriter.println("test output")
            runtime.errorWriter.println("test error")

            output.toString() shouldContain "test output"
            error.toString() shouldContain "test error"
        }

        test("copy preserves other values") {
            val original = KSRuntime(
                hostLang = false,
                maxRecursionDepth = 500,
                colorOutput = false
            )
            val modified = original.withHostLang(true)

            modified.hostLang shouldBe true
            modified.maxRecursionDepth shouldBe 500
            modified.colorOutput shouldBe false
        }
    }

    // ========================================================================
    // ANSI Color Tests
    // ========================================================================

    context("ANSI") {

        test("color wrapping when enabled") {
            val result = ANSI.red("error", enabled = true)
            result shouldContain ANSI.RED
            result shouldContain ANSI.RESET
            result shouldContain "error"
        }

        test("no color wrapping when disabled") {
            val result = ANSI.red("error", enabled = false)
            result shouldBe "error"
            result shouldNotBe ANSI.RED
        }

        test("bold wrapping") {
            val result = ANSI.bold("important", enabled = true)
            result shouldContain ANSI.BOLD
            result shouldContain "important"
        }

        test("warn uses orange") {
            val result = ANSI.warn("warning", enabled = true)
            result shouldContain ANSI.ORANGE
            result shouldContain "warning"
        }

        test("error uses red") {
            val result = ANSI.error("error", enabled = true)
            result shouldContain ANSI.RED
            result shouldContain "error"
        }
    }
})