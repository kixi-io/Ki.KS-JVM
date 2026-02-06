package io.kixi.ks

import io.kixi.KiError

/**
 * Base class for all KS runtime errors.
 *
 * Extends Ki.Core's [KiError] for ecosystem consistency and access to the
 * [suggestion] property, which provides actionable fix hints in error messages.
 *
 * This hierarchy is shared between the Interpreter and Compiler:
 * - Interpreter throws these at runtime
 * - Compiler may throw some statically (e.g., ConstraintError for provable violations)
 *
 * All errors carry an optional [SourceLocation] for IDE integration and error reporting.
 *
 * ## Exception Hierarchy
 *
 * ```
 * RuntimeException (JVM)
 * ├── KiException              (recoverable: parse/lex failures)
 * │   └── ParseException
 * │       ├── LexerException
 * │       └── ParseError
 * └── KiError                  (runtime violations)
 *     └── RuntimeError
 *         ├── TypeError
 *         ├── CastError
 *         ├── ConstraintError
 *         ├── UndefinedNameError
 *         ├── ImmutableAssignmentError
 *         ├── RedefinitionError
 *         ├── ReturnOutsideFunctionError
 *         ├── BreakOutsideLoopError
 *         ├── ContinueOutsideLoopError
 *         ├── NullAssertionError
 *         ├── NullPointerError
 *         ├── NotCallableError
 *         ├── ArityError
 *         ├── UnknownArgumentError
 *         ├── IndexOutOfBoundsError
 *         ├── KeyNotFoundError
 *         ├── MemberNotFoundError
 *         ├── DivisionByZeroError
 *         ├── OverflowError
 *         ├── NonExhaustiveWhenError
 *         ├── InvalidPatternError
 *         └── InternalError
 *
 * RuntimeException (control flow signals — not user-facing)
 * ├── ReturnValue
 * ├── BreakSignal
 * └── ContinueSignal
 * ```
 *
 * @param message Human-readable error description
 * @param location Source location where the error occurred (null for runtime-only errors)
 * @param cause The underlying cause of this error
 * @param suggestion Optional actionable hint for fixing the error
 */
open class RuntimeError(
    message: String,
    val location: SourceLocation? = null,
    cause: Throwable? = null,
    suggestion: String? = null
) : KiError(
    if (location != null) "[${location.line}:${location.column}] $message"
    else message,
    suggestion,
    cause
)

// ============================================================================
// Type Errors
// ============================================================================

/**
 * Thrown when a type mismatch occurs.
 *
 * Examples:
 *     - `"hello" + true` (invalid operand types)
 *     - `let x: Int = "five"` (incompatible initializer)
 *     - `foo(42)` where foo expects String
 */
class TypeError(
    message: String,
    location: SourceLocation? = null,
    suggestion: String? = null
) : RuntimeError(message, location, suggestion = suggestion)

/**
 * Thrown when a cast fails.
 *
 * Example: `"hello" as Int`
 */
class CastError(
    val value: Any?,
    val targetType: String,
    location: SourceLocation? = null,
    suggestion: String? = "Use 'is' to check the type before casting with 'as'"
) : RuntimeError(
    "Cannot cast ${formatValue(value)} to $targetType",
    location,
    suggestion = suggestion
)

// ============================================================================
// Constraint Errors
// ============================================================================

/**
 * Thrown when a constraint is violated.
 *
 * Constraints are checked:
 * - At runtime by the Interpreter (always)
 * - At compile time by the Compiler (where statically provable)
 *
 * Examples:
 *     - `let age: Int > 0 = -5` → ConstraintError
 *     - `fun setLevel(n: Int in 1..10)` called with 15 → ConstraintError
 *
 * @property variableName The variable or parameter name (null for anonymous expressions)
 * @property constraintExpr String representation of the constraint (e.g., "> 0", "1..100")
 * @property actualValue The value that violated the constraint
 */
class ConstraintError(
    val variableName: String?,
    val constraintExpr: String,
    val actualValue: Any?,
    location: SourceLocation? = null,
    suggestion: String? = "Ensure the value satisfies: $constraintExpr"
) : RuntimeError(
    buildConstraintMessage(variableName, constraintExpr, actualValue),
    location,
    suggestion = suggestion
) {
    companion object {
        private fun buildConstraintMessage(
            name: String?,
            constraint: String,
            value: Any?
        ): String {
            return if (name != null) {
                "Constraint violation: '$name' must satisfy $constraint (got ${formatValue(value)})"
            } else {
                "Constraint violation: value must satisfy $constraint (got ${formatValue(value)})"
            }
        }
    }
}

// ============================================================================
// Name Resolution Errors
// ============================================================================

/**
 * Thrown when a variable, function, or type name cannot be resolved.
 *
 * Examples:
 *     - `say x` where x is not defined
 *     - `foo()` where foo is not declared
 */
class UndefinedNameError(
    val name: String,
    val kind: NameKind = NameKind.VARIABLE,
    location: SourceLocation? = null,
    suggestion: String? = "Check the spelling or ensure '$name' is defined before use"
) : RuntimeError(
    "Undefined ${kind.displayName}: '$name'",
    location,
    suggestion = suggestion
)

enum class NameKind(val displayName: String) {
    VARIABLE("variable"),
    FUNCTION("function"),
    TYPE("type"),
    CLASS("class"),
    TRAIT("trait"),
    ENUM("enum"),
    ENUM_CONSTANT("enum constant"),
    MEMBER("member")
}

/**
 * Thrown when attempting to reassign an immutable variable.
 *
 * Example: `let x = 5; x = 10`
 */
class ImmutableAssignmentError(
    val name: String,
    location: SourceLocation? = null,
    suggestion: String? = "Declare with 'var' instead of 'let' if reassignment is needed"
) : RuntimeError(
    "Cannot reassign immutable variable '$name' (declared with 'let')",
    location,
    suggestion = suggestion
)

/**
 * Thrown when a name is already defined in the current scope.
 *
 * Example: `let x = 1; let x = 2` in the same block
 */
class RedefinitionError(
    val name: String,
    location: SourceLocation? = null,
    suggestion: String? = "Use a different name or remove the duplicate declaration of '$name'"
) : RuntimeError(
    "Variable '$name' is already defined in this scope",
    location,
    suggestion = suggestion
)

// ============================================================================
// Control Flow Errors
// ============================================================================

/**
 * Thrown when `return` is used outside of a function.
 */
class ReturnOutsideFunctionError(
    location: SourceLocation? = null,
    suggestion: String? = "Place 'return' inside a function body"
) : RuntimeError("'return' used outside of a function", location, suggestion = suggestion)

/**
 * Thrown when `break` is used outside of a loop.
 */
class BreakOutsideLoopError(
    location: SourceLocation? = null,
    suggestion: String? = "Place 'break' inside a 'for' or 'while' loop"
) : RuntimeError("'break' used outside of a loop", location, suggestion = suggestion)

/**
 * Thrown when `continue` is used outside of a loop.
 */
class ContinueOutsideLoopError(
    location: SourceLocation? = null,
    suggestion: String? = "Place 'continue' inside a 'for' or 'while' loop"
) : RuntimeError("'continue' used outside of a loop", location, suggestion = suggestion)

/**
 * Internal signal for return statement — not a true error.
 * Caught by function call evaluation to return the value.
 */
class ReturnValue(val value: Any?) : RuntimeException()

/**
 * Internal signal for break statement — not a true error.
 * Caught by loop evaluation to exit the loop.
 */
class BreakSignal : RuntimeException()

/**
 * Internal signal for continue statement — not a true error.
 * Caught by loop evaluation to skip to next iteration.
 */
class ContinueSignal : RuntimeException()

// ============================================================================
// Null Safety Errors
// ============================================================================

/**
 * Thrown when a non-null assertion (`!!`) is applied to a null value.
 *
 * Example: `val x: String? = nil; val y = x!!`
 */
class NullAssertionError(
    location: SourceLocation? = null,
    suggestion: String? = "Use '?.' for safe access or check for nil before using '!!'"
) : RuntimeError("Non-null assertion failed: value is nil", location, suggestion = suggestion)

/**
 * Thrown when a null value is used where non-null is required.
 *
 * Example: Calling a method on a null receiver without safe navigation.
 */
class NullPointerError(
    message: String = "Null pointer: operation on nil value",
    location: SourceLocation? = null,
    suggestion: String? = "Use '?.' for safe navigation or add a nil check"
) : RuntimeError(message, location, suggestion = suggestion)

// ============================================================================
// Invocation Errors
// ============================================================================

/**
 * Thrown when attempting to call something that isn't callable.
 *
 * Example: `let x = 5; x()` or `let s = "hello"; s()`
 */
class NotCallableError(
    val value: Any?,
    location: SourceLocation? = null,
    suggestion: String? = null
) : RuntimeError(
    "${formatValue(value)} is not callable",
    location,
    suggestion = suggestion
)

/**
 * Thrown when a function is called with the wrong number of arguments.
 */
class ArityError(
    val functionName: String,
    val expected: Int,
    val actual: Int,
    location: SourceLocation? = null,
    suggestion: String? = "Provide exactly $expected argument(s) to '$functionName'"
) : RuntimeError(
    "Function '$functionName' expects $expected argument(s) but got $actual",
    location,
    suggestion = suggestion
)

/**
 * Thrown when a named argument doesn't match any parameter.
 */
class UnknownArgumentError(
    val functionName: String,
    val argumentName: String,
    location: SourceLocation? = null,
    suggestion: String? = "Check the parameter names in the definition of '$functionName'"
) : RuntimeError(
    "Function '$functionName' has no parameter named '$argumentName'",
    location,
    suggestion = suggestion
)

// ============================================================================
// Index/Access Errors
// ============================================================================

/**
 * Thrown when an index is out of bounds.
 *
 * Example: `let list = [1, 2, 3]; list[10]`
 */
class IndexOutOfBoundsError(
    val index: Int,
    val size: Int,
    location: SourceLocation? = null,
    suggestion: String? = if (size > 0) "Valid indices are 0..${size - 1}" else "The collection is empty"
) : RuntimeError(
    "Index $index out of bounds for collection of size $size",
    location,
    suggestion = suggestion
)

/**
 * Thrown when a map key is not found.
 *
 * Example: `let map = ["a"=1]; map["b"]` (if strict mode)
 */
class KeyNotFoundError(
    val key: Any?,
    location: SourceLocation? = null,
    suggestion: String? = "Use 'in' to check if the key exists before accessing"
) : RuntimeError(
    "Key ${formatValue(key)} not found in map",
    location,
    suggestion = suggestion
)

/**
 * Thrown when accessing a member that doesn't exist.
 *
 * Example: `point.z` where Point only has x and y
 */
class MemberNotFoundError(
    val memberName: String,
    val typeName: String,
    location: SourceLocation? = null,
    suggestion: String? = "Check the available members of type '$typeName'"
) : RuntimeError(
    "Type '$typeName' has no member '$memberName'",
    location,
    suggestion = suggestion
)

// ============================================================================
// Arithmetic Errors
// ============================================================================

/**
 * Thrown on division by zero.
 */
class DivisionByZeroError(
    location: SourceLocation? = null,
    suggestion: String? = "Check that the divisor is not zero before dividing"
) : RuntimeError("Division by zero", location, suggestion = suggestion)

/**
 * Thrown when an arithmetic operation overflows.
 */
class OverflowError(
    message: String = "Arithmetic overflow",
    location: SourceLocation? = null,
    suggestion: String? = "Use a larger numeric type (e.g. Long or Dec)"
) : RuntimeError(message, location, suggestion = suggestion)

// ============================================================================
// Pattern Matching Errors
// ============================================================================

/**
 * Thrown when a `when` expression has no matching branch and no `else`.
 *
 * Example: `when x { 1 -> "one" }` where x is 2
 */
class NonExhaustiveWhenError(
    val value: Any?,
    location: SourceLocation? = null,
    suggestion: String? = "Add an 'else' branch to handle all cases"
) : RuntimeError(
    "Non-exhaustive 'when': no branch matches ${formatValue(value)} and no 'else' provided",
    location,
    suggestion = suggestion
)

/**
 * Thrown when a regex pattern is invalid.
 */
class InvalidPatternError(
    val pattern: String,
    cause: Throwable? = null,
    location: SourceLocation? = null,
    suggestion: String? = "Verify the regex syntax in: $pattern"
) : RuntimeError(
    "Invalid regex pattern: '$pattern'",
    location,
    cause,
    suggestion
)

// ============================================================================
// Internal / Assertion Errors
// ============================================================================

/**
 * Thrown when an internal interpreter error occurs.
 * This indicates a bug in the interpreter, not user code.
 */
class InternalError(
    message: String,
    location: SourceLocation? = null,
    suggestion: String? = null
) : RuntimeError("Internal error: $message", location, suggestion = suggestion)

// ============================================================================
// Utility
// ============================================================================

/**
 * Format a value for error messages.
 *
 * Accessible within the `io.kixi.ks` package for use by error classes
 * and interpreter diagnostics.
 */
internal fun formatValue(value: Any?): String {
    return when (value) {
        null -> "nil"
        is String -> "\"$value\""
        is Char -> "'$value'"
        is List<*> -> "List (size=${value.size})"
        is Map<*, *> -> "Map (size=${value.size})"
        else -> value.toString()
    }
}