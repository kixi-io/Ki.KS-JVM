package io.kixi.ks

import io.kixi.ks.interp.Environment
import io.kixi.ks.interp.Interpreter
import io.kixi.ks.parser.FunDecl
import io.kixi.ks.parser.Parameter

/**
 * Runtime representation of a KS function.
 *
 * KSFunction wraps a [FunDecl] AST node along with its captured closure environment.
 * This allows functions to be treated as first-class values — passed as arguments,
 * stored in variables, and returned from other functions.
 *
 * ## Closure Semantics
 *
 * When a function is defined, it captures a reference to its enclosing [Environment].
 * This captured environment is used when the function is called, allowing the function
 * to access variables from its definition site (lexical scoping).
 *
 * ```ks
 * fun makeCounter(): () -> Int {
 *     var count = 0
 *     return fun(): Int {
 *         count = count + 1
 *         return count
 *     }
 * }
 *
 * let counter = makeCounter()
 * say counter()  // 1
 * say counter()  // 2
 * ```
 *
 * ## Infix Functions
 *
 * Functions declared with the `infix` modifier can be called using infix notation:
 *
 * ```ks
 * class Vec(let x: Int, let y: Int) {
 *     infix fun dot(other: Vec): Int = this.x * other.x + this.y * other.y
 * }
 *
 * let a = Vec(1, 2)
 * let b = Vec(3, 4)
 * say a dot b        // infix call: 11
 * say a.dot(b)       // equivalent dot-call
 * ```
 *
 * Infix functions must have exactly one parameter (enforced at parse time).
 *
 * ## Design Notes
 *
 * This class is in the `interp` package but could be moved to `runtime` if the
 * Compiler needs to generate similar function representations for JVM bytecode.
 *
 * @property declaration The AST node containing the function's name, parameters, body, etc.
 * @property closure The environment captured when the function was defined.
 * @property name The function name (convenience accessor)
 */
class KSFunction(
    val declaration: FunDecl,
    val closure: Environment
): Callable {

    override fun call(
        interpreter: Interpreter,
        arguments: List<Any?>,
        location: SourceLocation?
    ): Any? = interpreter.callFunction(this, arguments, location)


    /** Function name (from the declaration). */
    val name: String get() = declaration.name

    /** Parameter list (from the declaration). */
    val params: List<Parameter> get() = declaration.params

    /** Number of required parameters (those without default values). */
    val requiredArity: Int
        get() = params.count { it.defaultValue == null }

    /** Total number of parameters. */
    val totalArity: Int
        get() = params.size

    /** Whether this function has a single-expression body (`= expr`). */
    val isSingleExpr: Boolean
        get() = declaration.isSingleExpr

    /** Whether this function is abstract (no body, valid only in traits). */
    val isAbstract: Boolean
        get() = declaration.body == null

    /** Whether this function is declared with the `infix` modifier. */
    val isInfix: Boolean
        get() = declaration.isInfix

    /** Source location of the function definition. */
    val location: SourceLocation
        get() = declaration.location

    /**
     * Get a parameter by name.
     */
    fun getParameter(name: String): Parameter? = params.find { it.name == name }

    /**
     * Check if a named argument exists.
     */
    fun hasParameter(name: String): Boolean = params.any { it.name == name }

    /**
     * Get parameter names in order.
     */
    fun parameterNames(): List<String> = params.map { it.name }

    override fun toString(): String {
        val paramStr = params.joinToString(", ") { p ->
            val typeStr = p.type?.let { ": ${it.name}" } ?: ""
            val defaultStr = if (p.defaultValue != null) " = ..." else ""
            "${p.name}$typeStr$defaultStr"
        }
        val returnStr = declaration.returnType?.let { ": ${it.name}" } ?: ""
        val infixStr = if (isInfix) "infix " else ""
        return "${infixStr}fun $name($paramStr)$returnStr"
    }
}

/**
 * Marker interface for callable values.
 *
 * Both [KSFunction] (user-defined) and [NativeFunction] (built-in) implement this.
 * The interpreter checks `is Callable` to determine if a value can be called.
 */
interface Callable {
    /**
     * Invoke the callable with the given arguments.
     *
     * @param interpreter The interpreter instance (for evaluating bodies, etc.)
     * @param arguments Evaluated argument values (positional order)
     * @param location Call site location for error reporting
     * @return The result of the call
     */
    fun call(interpreter: Interpreter, arguments: List<Any?>, location: SourceLocation?): Any?
}

/**
 * A built-in (native) function implemented in Kotlin.
 *
 * Used for standard library functions like String methods, math functions, etc.
 *
 * @property name Function name
 * @property arity Number of expected arguments (-1 for variadic)
 * @property impl The Kotlin implementation
 */
class NativeFunction(
    val name: String,
    val arity: Int,
    private val impl: (List<Any?>) -> Any?
) : Callable {

    override fun call(interpreter: Interpreter, arguments: List<Any?>, location: SourceLocation?): Any? {
        return impl(arguments)
    }

    override fun toString(): String = "<native fun $name>"
}

/**
 * Wrapper to make [KSFunction] implement [Callable].
 *
 * This is separate from KSFunction itself to avoid circular dependencies
 * with Interpreter. The FunctionEvaluator handles the actual invocation.
 */
class KSFunctionCallable(val function: KSFunction) : Callable {

    override fun call(interpreter: Interpreter, arguments: List<Any?>, location: SourceLocation?): Any? {
        // Delegate to interpreter's function evaluation
        // This will be implemented in FunctionEvaluator
        return interpreter.callFunction(function, arguments, location)
    }

    override fun toString(): String = function.toString()
}

/**
 * Runtime representation of a KS lambda (anonymous function / closure).
 *
 * Unlike [KSFunction], which wraps a [FunDecl] AST node, a lambda wraps a
 * [LambdaExpr] directly. Lambdas are always anonymous and capture the
 * enclosing environment at definition time (lexical scoping).
 *
 * ## Implicit `it`
 *
 * When [hasArrow] is `false` and [params] is empty, the lambda has a single
 * implicit parameter named `it`. The interpreter binds `it` automatically
 * when the lambda is called with one argument:
 *
 * ```ks
 * [1, 2, 3].map { it * 2 }   // implicit it
 * ```
 *
 * When [hasArrow] is `true` and [params] is empty, it's a zero-arg lambda:
 *
 * ```ks
 * val thunk = { -> say "hello" }
 * ```
 *
 * @property params Explicit parameters (empty for implicit `it` or zero-arg)
 * @property hasArrow Whether `->` was present in the source
 * @property body The lambda body statements
 * @property closure The environment captured at definition time
 */
class KSLambda(
    val params: List<io.kixi.ks.parser.LambdaParam>,
    val hasArrow: Boolean,
    val body: List<io.kixi.ks.parser.Node>,
    val closure: Environment
) : Callable {

    /**
     * Whether this lambda uses implicit `it` (no arrow, no explicit params).
     */
    val isImplicitIt: Boolean get() = !hasArrow && params.isEmpty()

    /**
     * The expected number of arguments.
     *
     * - Implicit `it`: 1
     * - Zero-arg (`{ -> }`): 0
     * - Explicit params: params.size
     */
    val arity: Int get() = when {
        isImplicitIt -> 1
        else -> params.size
    }

    override fun call(interpreter: Interpreter, arguments: List<Any?>, location: SourceLocation?): Any? {
        return interpreter.callLambda(this, arguments, location)
    }

    override fun toString(): String {
        val paramStr = when {
            isImplicitIt -> "it"
            params.isEmpty() -> ""
            else -> params.joinToString(", ") { p ->
                val typeStr = p.type?.let { ": ${it.name}" } ?: ""
                "${p.name}$typeStr"
            }
        }
        return "{ $paramStr -> ... }"
    }
}