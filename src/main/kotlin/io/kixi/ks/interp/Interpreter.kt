package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.lexer.*
import io.kixi.ks.parser.*

import java.math.BigDecimal as Dec
import java.math.RoundingMode

/**
 * KS Interpreter
 *
 * Tree-walking interpreter that executes KS programs by recursively evaluating
 * AST nodes. Uses [Environment] for lexical scoping and [KSFunction] for
 * first-class function support.
 *
 * ## Architecture
 *
 * The interpreter follows a modular design pattern where evaluation logic is
 * organized by AST node category. The core `evaluate()` method dispatches to
 * specialized evaluation methods based on node type.
 *
 * ## Supported Features
 *
 * Currently supports:
 * - Variables: `var`/`let` declarations, assignments (=, +=, -=, etc.)
 * - Literals: Int, Long, Float, Double, Dec, String, Char, Bool, Nil, URL
 * - String interpolation: `"Hello $name"`, `"Sum: ${a + b}"`
 * - Operators: arithmetic, comparison, logical, elvis (?:)
 * - Unary: -, !, ++, --, !!
 * - Control flow: if/else, blocks
 * - Functions: declarations, calls, closures, recursion
 * - Say: `say`, `say.error`, `say.warn`, `say.note`
 *
 * @param runtime Configuration for interpreter behavior (optional)
 */
class Interpreter(private val runtime: KSRuntime = KSRuntime.DEFAULT) {

    /** Current execution environment (scope chain). */
    internal var environment = Environment.global()

    /** Recursion depth counter for stack overflow protection. */
    private var recursionDepth = 0

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Execute KS source code.
     *
     * @param source The KS source code to execute
     * @return The result of the last evaluated expression/statement
     */
    fun execute(source: String): Any? {
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()

        return executeProgram(program)
    }

    /**
     * Execute a parsed program.
     *
     * @param program The AST program to execute
     * @return The result of the last evaluated node
     */
    fun executeProgram(program: Program): Any? {
        var result: Any? = null
        for (node in program.body) {
            result = evaluate(node)
        }
        return result
    }

    // ========================================================================
    // Core Evaluation Dispatch
    // ========================================================================

    /**
     * Evaluate any AST node.
     *
     * This is the main dispatch method that routes to specialized evaluators.
     */
    internal fun evaluate(node: Node): Any? {
        return when (node) {
            // --- Declarations ---
            is VarDecl -> evaluateVarDecl(node)
            is FunDecl -> evaluateFunDecl(node)

            // --- Statements ---
            is SayStmt -> evaluateSay(node)
            is ExprStmt -> evaluate(node.expression)
            is ReturnStmt -> evaluateReturn(node)
            is ForStmt -> evaluateFor(node)
            is WhileStmt -> evaluateWhile(node)
            is BreakStmt -> throw BreakSignal()
            is ContinueStmt -> throw ContinueSignal()
            is ThrowStmt -> evaluateThrow(node)

            // --- Expressions: Literals ---
            is LiteralExpr -> node.value
            is StringTemplateExpr -> evaluateStringTemplate(node)
            is ListExpr -> evaluateList(node)
            is MapExpr -> evaluateMap(node)

            // --- Expressions: Variables & Access ---
            is IdentifierExpr -> lookupIdentifier(node)
            is ThisExpr -> environment.get("this", node.location)
            is MemberAccessExpr -> evaluateMemberAccess(node)
            is IndexExpr -> evaluateIndex(node)

            // --- Expressions: Operators ---
            is BinaryExpr -> evaluateBinary(node)
            is UnaryExpr -> evaluateUnary(node)
            is AssignExpr -> evaluateAssign(node)
            is TernaryExpr -> evaluateTernary(node)

            // --- Expressions: Calls ---
            is CallExpr -> evaluateCall(node)

            // --- Expressions: Control Flow ---
            is IfExpr -> evaluateIf(node)
            is WhenExpr -> evaluateWhen(node)
            is TryExpr -> evaluateTry(node)
            is BlockExpr -> evaluateBlock(node)

            // --- Expressions: Type Operations ---
            is TypeCheckExpr -> evaluateTypeCheck(node)
            is TypeCastExpr -> evaluateTypeCast(node)
            is InCheckExpr -> evaluateInCheck(node)
            is MatchesExpr -> evaluateMatches(node)
            is RangeExpr -> evaluateRange(node)

            // --- Not yet implemented ---
            is ClassDecl -> TODO("ClassDecl not yet implemented")
            is TraitDecl -> TODO("TraitDecl not yet implemented")
            is EnumDecl -> TODO("EnumDecl not yet implemented")
            is UseDecl -> TODO("UseDecl not yet implemented")
            is ExtendDecl -> TODO("ExtendDecl not yet implemented")
            is StaticBlock -> TODO("StaticBlock not yet implemented")
            is LangBlockExpr -> TODO("LangBlockExpr not yet implemented")
            is DPECExpr -> TODO("DPECExpr not yet implemented")
            is ReflectionExpr -> TODO("ReflectionExpr not yet implemented")
            is KDTagNode -> TODO("KDTagNode not yet implemented - use within LangBlockExpr")
            is Program -> executeProgram(node)
        }
    }

    // ========================================================================
    // Variable Declarations
    // ========================================================================

    private fun evaluateVarDecl(decl: VarDecl): Any? {
        val value = if (decl.initializer != null) {
            evaluate(decl.initializer)
        } else {
            null
        }

        // Check constraint if present
        if (decl.constraint != null && value != null) {
            checkConstraint(decl.name, value, decl.constraint, decl.location)
        }

        environment.define(
            decl.name,
            value,
            decl.mutable,
            decl.typeAnnotation,
            decl.constraint,
            decl.location
        )
        return null
    }

    // ========================================================================
    // Function Declarations & Calls
    // ========================================================================

    private fun evaluateFunDecl(decl: FunDecl): Any? {
        // Create a function object that captures the current environment (closure)
        val function = KSFunction(decl, environment)
        environment.defineFunction(decl.name, function, decl.location)
        return null
    }

    /**
     * Call a KSFunction with the given arguments.
     *
     * This method is called by [KSFunctionCallable] and handles:
     * - Creating a new scope with the function's closure as parent
     * - Binding parameters to arguments (positional and default values)
     * - Executing the function body
     * - Handling return values
     *
     * @param function The function to call
     * @param arguments Evaluated argument values
     * @param location Call site location for error reporting
     * @return The function's return value
     */
    fun callFunction(function: KSFunction, arguments: List<Any?>, location: SourceLocation?): Any? {
        // Check recursion depth
        if (runtime.maxRecursionDepth > 0 && recursionDepth >= runtime.maxRecursionDepth) {
            throw RuntimeError("Maximum recursion depth exceeded (${runtime.maxRecursionDepth})", location)
        }

        // Check arity
        val params = function.params
        if (arguments.size < function.requiredArity) {
            throw ArityError(function.name, function.requiredArity, arguments.size, location)
        }
        if (arguments.size > function.totalArity) {
            throw ArityError(function.name, function.totalArity, arguments.size, location)
        }

        // Create new scope with function's closure as parent (lexical scoping)
        val functionEnv = function.closure.child("function:${function.name}")

        // Bind parameters
        for (i in params.indices) {
            val param = params[i]
            val value = if (i < arguments.size) {
                arguments[i]
            } else {
                // Use default value (must exist since we passed arity check)
                param.defaultValue?.let { evaluate(it) }
            }

            // Check parameter constraint if present
            if (param.constraint != null && value != null) {
                checkConstraint(param.name, value, param.constraint, param.location)
            }

            functionEnv.define(
                param.name,
                value,
                mutable = true, // Parameters can be reassigned within the function
                param.type,
                param.constraint,
                param.location
            )
        }

        // Execute function body
        val previousEnv = environment
        environment = functionEnv
        recursionDepth++

        try {
            val body = function.declaration.body
                ?: throw RuntimeError("Cannot call abstract function '${function.name}'", location)

            return if (function.isSingleExpr) {
                // Single-expression body: = expr
                evaluate(body)
            } else {
                // Block body: { ... }
                try {
                    evaluate(body)
                    null // Block without explicit return returns null
                } catch (ret: ReturnValue) {
                    ret.value
                }
            }
        } finally {
            recursionDepth--
            environment = previousEnv
        }
    }

    /**
     * Call a method on an object with proper `this` binding.
     *
     * This method is called by [BoundMethod.call] and handles:
     * - Creating a new scope with the method's closure as parent
     * - Binding `this` to the receiver object
     * - Binding parameters to arguments
     * - Executing the method body
     * - Handling return values
     *
     * @param receiver The object the method is being called on
     * @param method The method to call
     * @param arguments Evaluated argument values
     * @param location Call site location for error reporting
     * @return The method's return value
     */
    fun callMethod(receiver: KSObject, method: KSFunction, arguments: List<Any?>, location: SourceLocation?): Any? {
        // Check recursion depth
        if (runtime.maxRecursionDepth > 0 && recursionDepth >= runtime.maxRecursionDepth) {
            throw RuntimeError("Maximum recursion depth exceeded (${runtime.maxRecursionDepth})", location)
        }

        // Check arity
        val params = method.params
        if (arguments.size < method.requiredArity) {
            throw ArityError(method.name, method.requiredArity, arguments.size, location)
        }
        if (arguments.size > method.totalArity) {
            throw ArityError(method.name, method.totalArity, arguments.size, location)
        }

        // Create new scope with method's closure as parent (lexical scoping)
        val methodEnv = method.closure.child("method:${method.name}")

        // Bind `this` to the receiver object
        methodEnv.define("this", receiver, mutable = false, location = location)

        // Bind parameters
        for (i in params.indices) {
            val param = params[i]
            val value = if (i < arguments.size) {
                arguments[i]
            } else {
                // Use default value (must exist since we passed arity check)
                param.defaultValue?.let { evaluate(it) }
            }

            // Check parameter constraint if present
            if (param.constraint != null && value != null) {
                checkConstraint(param.name, value, param.constraint, param.location)
            }

            methodEnv.define(
                param.name,
                value,
                mutable = true,
                param.type,
                param.constraint,
                param.location
            )
        }

        // Execute method body
        val previousEnv = environment
        environment = methodEnv
        recursionDepth++

        try {
            val body = method.declaration.body
                ?: throw RuntimeError("Cannot call abstract method '${method.name}'", location)

            return if (method.isSingleExpr) {
                // Single-expression body: = expr
                evaluate(body)
            } else {
                // Block body: { ... }
                try {
                    evaluate(body)
                    null // Block without explicit return returns null
                } catch (ret: ReturnValue) {
                    ret.value
                }
            }
        } finally {
            recursionDepth--
            environment = previousEnv
        }
    }

    private fun evaluateCall(expr: CallExpr): Any? {
        val callee = evaluate(expr.callee)
        val location = expr.location

        // Evaluate arguments
        val args = expr.arguments.map { evaluate(it.value) }

        // Handle different callable types
        return when (callee) {
            is KSFunction -> callFunction(callee, args, location)
            is Callable -> callee.call(this, args, location)
            else -> {
                // Check if it's a function name (identifier that resolves to a function)
                if (expr.callee is IdentifierExpr) {
                    val name = (expr.callee as IdentifierExpr).name
                    if (environment.isFunctionDefined(name)) {
                        val fn = environment.getFunction(name, location)
                        return callFunction(fn, args, location)
                    }
                }
                throw NotCallableError(callee, location)
            }
        }
    }

    /**
     * Look up an identifier - could be a variable or a function.
     */
    private fun lookupIdentifier(expr: IdentifierExpr): Any? {
        // First try as a variable
        if (environment.isDefined(expr.name)) {
            return environment.get(expr.name, expr.location)
        }

        // Then try as a function (return the function object for first-class use)
        if (environment.isFunctionDefined(expr.name)) {
            return environment.getFunction(expr.name, expr.location)
        }

        throw UndefinedNameError(expr.name, NameKind.VARIABLE, expr.location)
    }

    // ========================================================================
    // Control Flow Statements
    // ========================================================================

    private fun evaluateReturn(stmt: ReturnStmt): Nothing {
        val value = stmt.value?.let { evaluate(it) }
        throw ReturnValue(value)
    }

    private fun evaluateFor(stmt: ForStmt): Any? {
        val iterable = evaluate(stmt.iterable)
        val items = toIterable(iterable, stmt.iterable.location)

        val loopEnv = environment.child("for")
        val previousEnv = environment
        environment = loopEnv

        var iterations = 0L

        try {
            for (item in items) {
                // Check iteration limit
                if (runtime.maxLoopIterations > 0 && iterations >= runtime.maxLoopIterations) {
                    throw RuntimeError("Maximum loop iterations exceeded (${runtime.maxLoopIterations})", stmt.location)
                }
                iterations++

                // Bind loop variable
                if (stmt.variable != null) {
                    // Traditional form: for i in list
                    if (!loopEnv.isDefined(stmt.variable)) {
                        loopEnv.define(stmt.variable, item, mutable = true)
                    } else {
                        loopEnv.assign(stmt.variable, item)
                    }
                } else {
                    // Simplified form: for list (uses implicit 'it')
                    loopEnv.defineIt(item)
                }

                // Execute body
                try {
                    evaluate(stmt.body)
                } catch (e: ContinueSignal) {
                    continue
                } catch (e: BreakSignal) {
                    break
                }
            }
        } finally {
            environment = previousEnv
        }

        return null
    }

    private fun evaluateWhile(stmt: WhileStmt): Any? {
        var iterations = 0L

        while (isTruthy(evaluate(stmt.condition))) {
            // Check iteration limit
            if (runtime.maxLoopIterations > 0 && iterations >= runtime.maxLoopIterations) {
                throw RuntimeError("Maximum loop iterations exceeded (${runtime.maxLoopIterations})", stmt.location)
            }
            iterations++

            try {
                evaluate(stmt.body)
            } catch (e: ContinueSignal) {
                continue
            } catch (e: BreakSignal) {
                break
            }
        }

        return null
    }

    private fun evaluateThrow(stmt: ThrowStmt): Nothing {
        val value = evaluate(stmt.expression)
        throw RuntimeError(stringify(value), stmt.location)
    }

    // ========================================================================
    // Control Flow Expressions
    // ========================================================================

    private fun evaluateIf(expr: IfExpr): Any? {
        val condition = evaluate(expr.condition)
        return if (isTruthy(condition)) {
            evaluate(expr.thenBranch)
        } else {
            expr.elseBranch?.let { evaluate(it) }
        }
    }

    private fun evaluateWhen(expr: WhenExpr): Any? {
        val subject = expr.subject?.let { evaluate(it) }

        for (branch in expr.branches) {
            if (branch.isElse) {
                return evaluate(branch.body)
            }

            for (matcher in branch.matchers) {
                val matches = if (subject != null) {
                    matchesWithSubject(subject, matcher)
                } else {
                    // Condition-style when: matcher is a boolean expression
                    isTruthy(evaluateMatcher(matcher))
                }

                if (matches) {
                    return evaluate(branch.body)
                }
            }
        }

        // No match and no else branch
        throw NonExhaustiveWhenError(subject, expr.location)
    }

    private fun matchesWithSubject(subject: Any?, matcher: WhenMatcher): Boolean {
        return when (matcher) {
            is ExpressionMatcher -> isEqual(subject, evaluate(matcher.expr))
            is TypeMatcher -> {
                val typeMatches = checkType(subject, matcher.type)
                if (matcher.negated) !typeMatches else typeMatches
            }
            is InMatcher -> {
                val container = evaluate(matcher.expr)
                val contains = checkContains(container, subject)
                if (matcher.negated) !contains else contains
            }
            is PatternMatcher -> {
                val pattern = evaluate(matcher.pattern)
                matchesPattern(subject, pattern)
            }
            is DPECMatcher -> {
                // DPEC matching requires enum support
                TODO("DPECMatcher not yet implemented")
            }
        }
    }

    private fun evaluateMatcher(matcher: WhenMatcher): Any? {
        return when (matcher) {
            is ExpressionMatcher -> evaluate(matcher.expr)
            else -> throw RuntimeError("Invalid matcher in condition-style when", matcher.location)
        }
    }

    private fun evaluateTry(expr: TryExpr): Any? {
        return try {
            evaluateBlock(expr.body)
        } catch (e: RuntimeError) {
            // Find matching catch clause
            for (catch in expr.catches) {
                if (catch.isCatchAll || catchMatches(e, catch)) {
                    val catchEnv = environment.child("catch")
                    val previousEnv = environment

                    if (catch.name != null) {
                        catchEnv.define(catch.name, e.message, mutable = false)
                    }

                    environment = catchEnv
                    try {
                        return evaluateBlock(catch.body)
                    } finally {
                        environment = previousEnv
                    }
                }
            }
            throw e // Re-throw if no matching catch
        } finally {
            expr.finallyBlock?.let { evaluateBlock(it) }
        }
    }

    private fun catchMatches(error: RuntimeError, catch: CatchClause): Boolean {
        // For now, all catches match. Type-based matching requires more infrastructure.
        return true
    }

    private fun evaluateBlock(block: BlockExpr): Any? {
        val blockEnv = environment.child("block")
        val previousEnv = environment
        environment = blockEnv

        try {
            var result: Any? = null
            for (stmt in block.statements) {
                result = evaluate(stmt)
            }
            return result
        } finally {
            environment = previousEnv
        }
    }

    private fun evaluateTernary(expr: TernaryExpr): Any? {
        val condition = evaluate(expr.condition)
        return if (isTruthy(condition)) {
            evaluate(expr.thenBranch)
        } else {
            evaluate(expr.elseBranch)
        }
    }

    // ========================================================================
    // Say Statement
    // ========================================================================

    private fun evaluateSay(stmt: SayStmt): Any? {
        val output = stmt.arguments.joinToString(" ") { arg ->
            stringify(evaluate(arg.value))
        }

        val colorEnabled = runtime.colorOutput

        when (stmt.variant) {
            "error" -> runtime.errorWriter.println(ANSI.error(output, colorEnabled))
            "warn" -> runtime.outputWriter.println(ANSI.warn(output, colorEnabled))
            "note" -> runtime.outputWriter.println(ANSI.bold(output, colorEnabled))
            else -> runtime.outputWriter.println(output)
        }
        return null
    }

    // ========================================================================
    // String Template
    // ========================================================================

    private fun evaluateStringTemplate(template: StringTemplateExpr): String {
        val sb = StringBuilder()
        for (part in template.parts) {
            when (part) {
                is LiteralPart -> sb.append(part.text)
                is ExpressionPart -> sb.append(stringify(evaluate(part.expr)))
            }
        }
        return sb.toString()
    }

    // ========================================================================
    // Collections
    // ========================================================================

    private fun evaluateList(expr: ListExpr): List<Any?> {
        return expr.elements.map { evaluate(it) }
    }

    private fun evaluateMap(expr: MapExpr): Map<Any?, Any?> {
        val result = mutableMapOf<Any?, Any?>()
        for (entry in expr.entries) {
            val key = evaluate(entry.key)
            val value = evaluate(entry.value)
            result[key] = value
        }
        return result
    }

    private fun evaluateRange(expr: RangeExpr): Any {
        val start = expr.start?.let { evaluate(it) }
        val end = expr.end?.let { evaluate(it) }

        // For now, return a simple representation
        // Full range support requires a KSRange class
        return KSRange(start, end, expr.startExclusive, expr.endExclusive)
    }

    // ========================================================================
    // Member & Index Access
    // ========================================================================

    private fun evaluateMemberAccess(expr: MemberAccessExpr): Any? {
        val obj = evaluate(expr.obj)

        if (expr.safe && obj == null) {
            return null
        }

        if (obj == null) {
            throw NullPointerError("Cannot access member '${expr.member}' on nil", expr.location)
        }

        // Handle built-in types and KS objects
        return when (obj) {
            is KSObject -> obj.get(expr.member, expr.location)
            is String -> getStringMember(obj, expr.member, expr.location)
            is List<*> -> getListMember(obj, expr.member, expr.location)
            is Map<*, *> -> getMapMember(obj, expr.member, expr.location)
            is KSRange -> getRangeMember(obj, expr.member, expr.location)
            else -> throw MemberNotFoundError(expr.member, obj::class.simpleName ?: "Unknown", expr.location)
        }
    }

    private fun evaluateIndex(expr: IndexExpr): Any? {
        val obj = evaluate(expr.obj)
        val index = evaluate(expr.index)

        return when (obj) {
            is List<*> -> {
                val i = toInt(index, expr.index.location)
                if (i < 0 || i >= obj.size) {
                    throw IndexOutOfBoundsError(i, obj.size, expr.location)
                }
                obj[i]
            }
            is Map<*, *> -> obj[index]
            is String -> {
                val i = toInt(index, expr.index.location)
                if (i < 0 || i >= obj.length) {
                    throw IndexOutOfBoundsError(i, obj.length, expr.location)
                }
                obj[i]
            }
            null -> throw NullPointerError("Cannot index into nil", expr.location)
            else -> throw TypeError("Cannot index into ${obj::class.simpleName}", expr.location)
        }
    }

    // ========================================================================
    // Assignment
    // ========================================================================

    private fun evaluateAssign(expr: AssignExpr): Any? {
        val value = evaluate(expr.value)

        when (val target = expr.target) {
            is IdentifierExpr -> {
                val newValue = when (expr.operator) {
                    AssignOp.ASSIGN -> value
                    AssignOp.PLUS_ASSIGN -> add(environment.get(target.name, target.location), value)
                    AssignOp.MINUS_ASSIGN -> subtract(environment.get(target.name, target.location), value)
                    AssignOp.STAR_ASSIGN -> multiply(environment.get(target.name, target.location), value)
                    AssignOp.SLASH_ASSIGN -> divide(environment.get(target.name, target.location), value)
                    AssignOp.MODULO_ASSIGN -> modulo(environment.get(target.name, target.location), value)
                    AssignOp.POWER_ASSIGN -> power(environment.get(target.name, target.location), value)
                }

                // Check constraint if present
                val constraint = environment.getConstraint(target.name)
                if (constraint != null && newValue != null) {
                    checkConstraint(target.name, newValue, constraint, expr.location)
                }

                environment.assign(target.name, newValue, expr.location)
                return newValue
            }
            is IndexExpr -> {
                // TODO: Index assignment (list[0] = value)
                TODO("Index assignment not yet implemented")
            }
            is MemberAccessExpr -> {
                val obj = evaluate(target.obj)

                if (obj == null) {
                    throw NullPointerError("Cannot assign to member '${target.member}' on nil", expr.location)
                }

                when (obj) {
                    is KSObject -> {
                        val newValue = when (expr.operator) {
                            AssignOp.ASSIGN -> value
                            AssignOp.PLUS_ASSIGN -> add(obj.get(target.member, target.location), value)
                            AssignOp.MINUS_ASSIGN -> subtract(obj.get(target.member, target.location), value)
                            AssignOp.STAR_ASSIGN -> multiply(obj.get(target.member, target.location), value)
                            AssignOp.SLASH_ASSIGN -> divide(obj.get(target.member, target.location), value)
                            AssignOp.MODULO_ASSIGN -> modulo(obj.get(target.member, target.location), value)
                            AssignOp.POWER_ASSIGN -> power(obj.get(target.member, target.location), value)
                        }
                        obj.set(target.member, newValue, expr.location)
                        return newValue
                    }
                    is MutableList<*> -> {
                        throw RuntimeError("Cannot assign to list member '${target.member}'", expr.location)
                    }
                    is MutableMap<*, *> -> {
                        throw RuntimeError("Cannot assign to map member '${target.member}'", expr.location)
                    }
                    else -> throw RuntimeError(
                        "Cannot assign to member '${target.member}' on ${obj::class.simpleName}",
                        expr.location
                    )
                }
            }
            else -> throw RuntimeError("Invalid assignment target", expr.location)
        }
    }

    // ========================================================================
    // Binary Expressions
    // ========================================================================

    private fun evaluateBinary(expr: BinaryExpr): Any? {
        // Short-circuit for logical operators
        if (expr.operator == BinaryOp.AND) {
            val left = evaluate(expr.left)
            if (!isTruthy(left)) return false
            return isTruthy(evaluate(expr.right))
        }
        if (expr.operator == BinaryOp.OR) {
            val left = evaluate(expr.left)
            if (isTruthy(left)) return true
            return isTruthy(evaluate(expr.right))
        }

        val left = evaluate(expr.left)
        val right = evaluate(expr.right)

        return when (expr.operator) {
            // Arithmetic
            BinaryOp.ADD -> add(left, right)
            BinaryOp.SUBTRACT -> subtract(left, right)
            BinaryOp.MULTIPLY -> multiply(left, right)
            BinaryOp.DIVIDE -> divide(left, right)
            BinaryOp.MODULO -> modulo(left, right)
            BinaryOp.POWER -> power(left, right)

            // Comparison
            BinaryOp.EQUAL -> isEqual(left, right)
            BinaryOp.NOT_EQUAL -> !isEqual(left, right)
            BinaryOp.LESS -> compare(left, right) < 0
            BinaryOp.GREATER -> compare(left, right) > 0
            BinaryOp.LESS_EQUAL -> compare(left, right) <= 0
            BinaryOp.GREATER_EQUAL -> compare(left, right) >= 0

            // Logical (already handled above for short-circuit)
            BinaryOp.AND -> isTruthy(left) && isTruthy(right)
            BinaryOp.OR -> isTruthy(left) || isTruthy(right)

            // Elvis
            BinaryOp.ELVIS -> left ?: right
        }
    }

    // ========================================================================
    // Unary Expressions
    // ========================================================================

    private fun evaluateUnary(expr: UnaryExpr): Any? {
        return when (expr.operator) {
            UnaryOp.NEGATE -> {
                val operand = evaluate(expr.operand)
                negate(operand)
            }
            UnaryOp.NOT -> {
                val operand = evaluate(expr.operand)
                !isTruthy(operand)
            }
            UnaryOp.INCREMENT -> {
                if (expr.operand is IdentifierExpr) {
                    val name = expr.operand.name
                    val current = environment.get(name, expr.location)
                    val newVal = add(current, 1)
                    environment.assign(name, newVal, expr.location)
                    if (expr.prefix) newVal else current
                } else {
                    add(evaluate(expr.operand), 1)
                }
            }
            UnaryOp.DECREMENT -> {
                if (expr.operand is IdentifierExpr) {
                    val name = expr.operand.name
                    val current = environment.get(name, expr.location)
                    val newVal = subtract(current, 1)
                    environment.assign(name, newVal, expr.location)
                    if (expr.prefix) newVal else current
                } else {
                    subtract(evaluate(expr.operand), 1)
                }
            }
            UnaryOp.NON_NULL -> {
                val operand = evaluate(expr.operand)
                operand ?: throw NullAssertionError(expr.location)
            }
        }
    }

    // ========================================================================
    // Type Operations
    // ========================================================================

    private fun evaluateTypeCheck(expr: TypeCheckExpr): Boolean {
        val value = evaluate(expr.expr)
        val matches = checkType(value, expr.type)
        return if (expr.negated) !matches else matches
    }

    private fun evaluateTypeCast(expr: TypeCastExpr): Any? {
        val value = evaluate(expr.expr)
        return castTo(value, expr.type, expr.location)
    }

    private fun evaluateInCheck(expr: InCheckExpr): Boolean {
        val value = evaluate(expr.expr)
        val container = evaluate(expr.container)
        val contains = checkContains(container, value)
        return if (expr.negated) !contains else contains
    }

    private fun evaluateMatches(expr: MatchesExpr): Boolean {
        val value = evaluate(expr.expr)
        val pattern = evaluate(expr.pattern)
        return matchesPattern(value, pattern)
    }

    private fun checkType(value: Any?, type: TypeRef): Boolean {
        if (value == null) return type.nullable

        return when (type.name) {
            "Int" -> value is Int
            "Long" -> value is Long || value is Int
            "Float" -> value is Float
            "Double" -> value is Double || value is Float
            "Dec" -> value is Dec
            "String" -> value is String
            "Char" -> value is Char
            "Bool" -> value is Boolean
            "List" -> value is List<*>
            "Map" -> value is Map<*, *>
            "Any" -> true
            else -> false // Unknown types don't match
        }
    }

    private fun castTo(value: Any?, type: TypeRef, location: SourceLocation?): Any? {
        if (value == null) {
            if (type.nullable) return null
            throw CastError(value, type.name, location)
        }

        return when (type.name) {
            "Int" -> toInt(value, location)
            "Long" -> toLong(value, location)
            "Float" -> toFloat(value, location)
            "Double" -> toDouble(value)
            "String" -> stringify(value)
            "Bool" -> isTruthy(value)
            else -> throw CastError(value, type.name, location)
        }
    }

    private fun checkContains(container: Any?, value: Any?): Boolean {
        return when (container) {
            is List<*> -> value in container
            is Map<*, *> -> value in container.keys
            is String -> {
                val str = value?.toString() ?: return false
                str in container
            }
            is KSRange -> container.contains(value)
            is ClosedRange<*> -> {
                @Suppress("UNCHECKED_CAST")
                val range = container as ClosedRange<Comparable<Any>>
                val comp = value as? Comparable<Any> ?: return false
                comp in range
            }
            else -> false
        }
    }

    private fun matchesPattern(value: Any?, pattern: Any?): Boolean {
        val str = value?.toString() ?: return false
        val patternStr = pattern?.toString() ?: return false

        return try {
            val regex = Regex(patternStr)
            regex.matches(str)
        } catch (e: Exception) {
            throw InvalidPatternError(patternStr, e)
        }
    }

    // ========================================================================
    // Constraint Checking
    // ========================================================================

    private fun checkConstraint(name: String?, value: Any, constraint: Constraint, location: SourceLocation?) {
        if (!runtime.checkConstraints) return

        val satisfied = when (constraint) {
            is ComparisonConstraint -> {
                val threshold = evaluate(constraint.value)
                val cmp = compare(value, threshold)
                when (constraint.operator) {
                    ComparisonOp.GT -> cmp > 0
                    ComparisonOp.LT -> cmp < 0
                    ComparisonOp.GTE -> cmp >= 0
                    ComparisonOp.LTE -> cmp <= 0
                    ComparisonOp.NEQ -> cmp != 0
                }
            }
            is RangeConstraint -> {
                val range = evaluate(constraint.range)
                checkContains(range, value)
            }
            is InConstraint -> {
                val collection = evaluate(constraint.collection)
                checkContains(collection, value)
            }
            is MatchesConstraint -> {
                val pattern = evaluate(constraint.pattern)
                matchesPattern(value, pattern)
            }
        }

        if (!satisfied) {
            val constraintExpr = constraintToString(constraint)
            throw ConstraintError(name, constraintExpr, value, location)
        }
    }

    private fun constraintToString(constraint: Constraint): String {
        return when (constraint) {
            is ComparisonConstraint -> {
                val op = when (constraint.operator) {
                    ComparisonOp.GT -> ">"
                    ComparisonOp.LT -> "<"
                    ComparisonOp.GTE -> ">="
                    ComparisonOp.LTE -> "<="
                    ComparisonOp.NEQ -> "!="
                }
                "$op ..."
            }
            is RangeConstraint -> "range"
            is InConstraint -> "in ..."
            is MatchesConstraint -> "matches ..."
        }
    }

    // ========================================================================
    // Arithmetic Operations (type-preserving)
    // ========================================================================

    private fun add(left: Any?, right: Any?): Any {
        // String concatenation
        if (left is String || right is String) {
            return stringify(left) + stringify(right)
        }
        return numericOp(left, right, "add")
    }

    private fun subtract(left: Any?, right: Any?): Any {
        return numericOp(left, right, "subtract")
    }

    private fun multiply(left: Any?, right: Any?): Any {
        return numericOp(left, right, "multiply")
    }

    private fun divide(left: Any?, right: Any?): Any {
        return numericOp(left, right, "divide")
    }

    private fun modulo(left: Any?, right: Any?): Any {
        return numericOp(left, right, "modulo")
    }

    private fun power(left: Any?, right: Any?): Any {
        val base = toDouble(left)
        val exp = toDouble(right)
        val result = Math.pow(base, exp)

        // Try to preserve integer type if possible
        if (left is Int && right is Int && result == result.toLong().toDouble() && result <= Int.MAX_VALUE) {
            return result.toInt()
        }
        if ((left is Int || left is Long) && right is Int && result == result.toLong().toDouble()) {
            return result.toLong()
        }
        return result
    }

    private fun negate(value: Any?): Any {
        return when (value) {
            is Int -> -value
            is Long -> -value
            is Float -> -value
            is Double -> -value
            is Dec -> value.negate()
            else -> -toDouble(value)
        }
    }

    /**
     * Perform a numeric operation, preserving the "wider" type.
     * Type hierarchy: Int < Long < Float < Double < BigDecimal
     */
    private fun numericOp(left: Any?, right: Any?, op: String): Any {
        // Handle BigDecimal specially
        if (left is Dec || right is Dec) {
            val l = toBigDecimal(left)
            val r = toBigDecimal(right)
            return when (op) {
                "add" -> l.add(r)
                "subtract" -> l.subtract(r)
                "multiply" -> l.multiply(r)
                "divide" -> l.divide(r, 10, RoundingMode.HALF_UP)
                "modulo" -> l.remainder(r)
                else -> throw RuntimeError("Unknown operation: $op")
            }
        }

        val a = toDouble(left)
        val b = toDouble(right)

        // Check division by zero
        if (op == "divide" && b == 0.0) {
            throw DivisionByZeroError()
        }

        val result = when (op) {
            "add" -> a + b
            "subtract" -> a - b
            "multiply" -> a * b
            "divide" -> a / b
            "modulo" -> a % b
            else -> throw RuntimeError("Unknown operation: $op")
        }

        // Preserve type based on operands
        return when {
            left is Double || right is Double -> result
            left is Float || right is Float -> result.toFloat()
            left is Long || right is Long -> {
                if (result == result.toLong().toDouble()) result.toLong() else result
            }
            left is Int && right is Int -> {
                if (result == result.toInt().toDouble() &&
                    result >= Int.MIN_VALUE && result <= Int.MAX_VALUE) {
                    result.toInt()
                } else if (result == result.toLong().toDouble()) {
                    result.toLong()
                } else {
                    result
                }
            }
            else -> result
        }
    }

    private fun compare(left: Any?, right: Any?): Int {
        if (left is String && right is String) return left.compareTo(right)
        if (left is Char && right is Char) return left.compareTo(right)
        return toDouble(left).compareTo(toDouble(right))
    }

    // ========================================================================
    // Type Conversions
    // ========================================================================

    internal fun stringify(value: Any?): String {
        return when (value) {
            null -> "nil"
            is Double -> {
                val text = value.toString()
                if (text.endsWith(".0")) text.substring(0, text.length - 2) else text
            }
            is Float -> {
                val text = value.toString()
                if (text.endsWith(".0")) text.substring(0, text.length - 2) else text
            }
            is Dec -> value.toPlainString()
            is List<*> -> value.joinToString(", ", "[", "]") { stringify(it) }
            is Map<*, *> -> value.entries.joinToString(", ", "[", "]") { "${stringify(it.key)}=${stringify(it.value)}" }
            else -> value.toString()
        }
    }

    private fun toDouble(value: Any?): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
                ?: throw TypeError("Cannot convert '$value' to number")
            else -> throw TypeError("Expected number, got ${value?.let { it::class.simpleName } ?: "nil"}")
        }
    }

    private fun toInt(value: Any?, location: SourceLocation?): Int {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
                ?: throw TypeError("Cannot convert '$value' to Int", location)
            else -> throw TypeError("Expected Int, got ${value?.let { it::class.simpleName } ?: "nil"}", location)
        }
    }

    private fun toLong(value: Any?, location: SourceLocation?): Long {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
                ?: throw TypeError("Cannot convert '$value' to Long", location)
            else -> throw TypeError("Expected Long, got ${value?.let { it::class.simpleName } ?: "nil"}", location)
        }
    }

    private fun toFloat(value: Any?, location: SourceLocation?): Float {
        return when (value) {
            is Float -> value
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull()
                ?: throw TypeError("Cannot convert '$value' to Float", location)
            else -> throw TypeError("Expected Float, got ${value?.let { it::class.simpleName } ?: "nil"}", location)
        }
    }

    private fun toBigDecimal(value: Any?): Dec {
        return when (value) {
            is Dec -> value
            is Number -> Dec(value.toDouble())
            is String -> Dec(value)
            else -> throw TypeError("Cannot convert to BigDecimal")
        }
    }

    internal fun isTruthy(value: Any?): Boolean {
        return when (value) {
            null -> false
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.isNotEmpty()
            is List<*> -> value.isNotEmpty()
            is Map<*, *> -> value.isNotEmpty()
            else -> true
        }
    }

    private fun isEqual(left: Any?, right: Any?): Boolean {
        if (left == null && right == null) return true
        if (left == null || right == null) return false

        // Numeric comparison - compare by value, not type
        if (left is Number && right is Number) {
            return left.toDouble() == right.toDouble()
        }

        return left == right
    }

    // ========================================================================
    // Iteration Support
    // ========================================================================

    private fun toIterable(value: Any?, location: SourceLocation?): Iterable<Any?> {
        return when (value) {
            is Iterable<*> -> value
            is String -> value.toList()
            is Map<*, *> -> value.entries.toList()
            is KSRange -> value.toIterable()
            else -> throw TypeError("Cannot iterate over ${value?.let { it::class.simpleName } ?: "nil"}", location)
        }
    }

    // ========================================================================
    // Built-in Member Access
    // ========================================================================

    private fun getStringMember(str: String, member: String, location: SourceLocation?): Any {
        return when (member) {
            "length", "size" -> str.length
            "isEmpty" -> str.isEmpty()
            "isNotEmpty" -> str.isNotEmpty()
            "uppercase" -> str.uppercase()
            "lowercase" -> str.lowercase()
            "trim" -> str.trim()
            "reversed" -> str.reversed()
            else -> throw MemberNotFoundError(member, "String", location)
        }
    }

    private fun getListMember(list: List<*>, member: String, location: SourceLocation?): Any {
        return when (member) {
            "size", "length" -> list.size
            "isEmpty" -> list.isEmpty()
            "isNotEmpty" -> list.isNotEmpty()
            "first" -> list.firstOrNull() ?: throw IndexOutOfBoundsError(0, 0, location)
            "last" -> list.lastOrNull() ?: throw IndexOutOfBoundsError(0, 0, location)
            "reversed" -> list.reversed()
            else -> throw MemberNotFoundError(member, "List", location)
        }
    }

    private fun getMapMember(map: Map<*, *>, member: String, location: SourceLocation?): Any {
        return when (member) {
            "size" -> map.size
            "isEmpty" -> map.isEmpty()
            "isNotEmpty" -> map.isNotEmpty()
            "keys" -> map.keys.toList()
            "values" -> map.values.toList()
            else -> throw MemberNotFoundError(member, "Map", location)
        }
    }

    private fun getRangeMember(range: KSRange, member: String, location: SourceLocation?): Any? {
        return when (member) {
            "start" -> range.start
            "end" -> range.end
            "startExclusive" -> range.startExclusive
            "endExclusive" -> range.endExclusive
            else -> throw MemberNotFoundError(member, "Range", location)
        }
    }
}

// ============================================================================
// KSRange - Simple Range Implementation
// ============================================================================

/**
 * Runtime representation of a KS range.
 *
 * Supports both Int and Double ranges with inclusive/exclusive bounds.
 */
class KSRange(
    val start: Any?,
    val end: Any?,
    val startExclusive: Boolean,
    val endExclusive: Boolean
) {
    fun contains(value: Any?): Boolean {
        if (value == null) return false
        if (value !is Number) return false

        val v = value.toDouble()

        val startOk = when {
            start == null -> true
            start is Number -> {
                val s = start.toDouble()
                if (startExclusive) v > s else v >= s
            }
            else -> false
        }

        val endOk = when {
            end == null -> true
            end is Number -> {
                val e = end.toDouble()
                if (endExclusive) v < e else v <= e
            }
            else -> false
        }

        return startOk && endOk
    }

    fun toIterable(): Iterable<Any?> {
        // Only works for integer ranges with both bounds
        if (start == null || end == null) {
            throw RuntimeError("Cannot iterate over open-ended range")
        }

        val s = (start as Number).toInt()
        val e = (end as Number).toInt()

        val actualStart = if (startExclusive) s + 1 else s
        val actualEnd = if (endExclusive) e - 1 else e

        return (actualStart..actualEnd).toList()
    }

    override fun toString(): String {
        val startStr = start?.toString() ?: "_"
        val endStr = end?.toString() ?: "_"
        val op = when {
            startExclusive && endExclusive -> "<..<"
            startExclusive -> "<.."
            endExclusive -> "..<"
            else -> ".."
        }
        return "$startStr$op$endStr"
    }
}