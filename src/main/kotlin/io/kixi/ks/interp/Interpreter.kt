package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.lexer.*
import io.kixi.ks.parser.*
import io.kixi.Grid

/**
 * KS Interpreter
 *
 * Tree-walking interpreter that executes KS programs by recursively evaluating
 * AST nodes. Uses [Environment] for lexical scoping and [KSFunction] for
 * first-class function support.
 *
 * ## Architecture
 *
 * The interpreter uses a delegation pattern where evaluation logic is split
 * into focused sub-evaluators, each holding a reference back to this core:
 *
 * - [InterpreterOps] — arithmetic, type conversions, constraint checking,
 *   built-in member access (String, List, Map, Range, Regex, etc.), stringify
 * - [ExpressionEvaluator] — literals, string templates, collections, member
 *   access, index access, assignment, binary/unary operators, type operations
 * - [TypeDeclarationEvaluator] — class/trait/struct/enum declarations,
 *   instantiation, extend blocks, lang blocks, reflection, use declarations
 *
 * The core [Interpreter] retains:
 * - All mutable state (environment, registries, recursion depth)
 * - Public API ([execute], [executeProgram], [callFunction], [callMethod])
 * - The central [evaluate] dispatch
 * - Variable and function declarations
 * - Control flow (if, when, for, while, try/catch, block)
 * - Say statement
 *
 * ## Supported Features
 *
 * - Variables: `var`/`let` declarations, assignments (=, +=, -=, etc.)
 * - Literals: Int, Long, Float, Double, Dec, String, Char, Bool, Nil, URL, Quantity
 * - Quantities: unit quantities (23cm, 25°C), currency ($50.25), combine (⚭)
 * - String interpolation: `"Hello $name"`, `"Sum: ${a + b}"`
 * - Operators: arithmetic, comparison, logical, elvis (?:)
 * - Unary: -, !, ++, --, !!
 * - Control flow: if/else, when, try/catch/finally, for, while
 * - Functions: declarations, calls, closures, recursion
 * - Classes: declaration, instantiation, methods, properties, static members
 * - Traits: declaration, implementation, default methods
 * - Enums: declaration, constants, iteration, DPEC matching
 * - Structs: declaration, copy semantics, value equality
 * - Say: `say`, `say.error`, `say.warn`, `say.note`
 * - Lang blocks: `lang KD { ... }` for embedded DSLs
 * - Reflection: `::class`
 *
 * @param runtime Configuration for interpreter behavior (optional)
 */
class Interpreter(internal val runtime: KSRuntime = KSRuntime.DEFAULT) {

    // ========================================================================
    // State
    // ========================================================================

    /** Current execution environment (scope chain). */
    internal var environment = Environment.global()

    /** Recursion depth counter for stack overflow protection. */
    internal var recursionDepth = 0

    /** Registry of defined classes by name. */
    internal val classes = mutableMapOf<String, KSClass>()

    /** Registry of defined traits by name. */
    internal val traits = mutableMapOf<String, KSTrait>()

    /** Registry of defined enums by name. */
    internal val enums = mutableMapOf<String, KSEnum>()

    /** Registry of defined structs by name. */
    internal val structs = mutableMapOf<String, KSStruct>()

    /** Native Ki.Core type registry — lazy-loaded constructors and static methods. */
    internal val nativeTypes = NativeTypeRegistry()

    /** Import registry for `use` statement resolution. */
    internal val importRegistry = ImportRegistry(runtime)

    /** Built-in type sentinels for reflective access (e.g. String.type). */
    internal val builtinTypes = mapOf(
        "String"     to KSBuiltinType("String"),
        "Int"        to KSBuiltinType("Int"),
        "Long"       to KSBuiltinType("Long"),
        "Float"      to KSBuiltinType("Float"),
        "Double"     to KSBuiltinType("Double"),
        "Dec"        to KSBuiltinType("Dec"),
        "Bool"       to KSBuiltinType("Bool"),
        "Char"       to KSBuiltinType("Char"),
        "List"       to KSBuiltinType("List"),
        "Map"        to KSBuiltinType("Map"),
        "Range"      to KSBuiltinType("Range"),
        "Regex"      to KSBuiltinType("Regex"),
        "Quantity"   to KSBuiltinType("Quantity"),
        "Version"    to KSBuiltinType("Version"),
        "Grid"       to KSBuiltinType("Grid"),
        "Coordinate" to KSBuiltinType("Coordinate"),
        "Blob"       to KSBuiltinType("Blob"),
        "NSID"       to KSBuiltinType("NSID"),
        "Call"       to KSBuiltinType("Call"),
        "Tag"        to KSBuiltinType("Tag"),
        "Nil"        to KSBuiltinType("Nil"),
        "Any"        to KSBuiltinType("Any"),
        "Type"       to KSBuiltinType("Type"),
    )

    /** Current enum context for DPEC resolution (set during when subject evaluation). */
    internal var currentEnumContext: KSEnum? = null

    // ========================================================================
    // Sub-evaluators (delegation pattern)
    // ========================================================================

    /** Operations: arithmetic, type conversions, constraints, built-in members, stringify. */
    internal val ops = InterpreterOps(this)

    /** Type declarations: class, trait, struct, enum, extend, lang, reflection, use. */
    internal val typeDecls = TypeDeclarationEvaluator(this)

    /** Expressions: literals, templates, collections, access, assign, operators. */
    internal val expr = ExpressionEvaluator(this)

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
        println("executing: $source")

        try {
            val lexer = Lexer(source)
            println("Lexer initialized")

            val tokens = lexer.tokenize()
            val parser = Parser(tokens)
            val program = parser.parse()

            return executeProgram(program)
        } catch (e: Exception) {
            println("Exception: ${e.message}")
            e.printStackTrace()
            return null
        }
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

    /**
     * Stringify a runtime value for display (delegates to [InterpreterOps]).
     *
     * Public API for REPL and external consumers.
     */
    fun stringify(value: Any?): String = ops.stringify(value)

    /**
     * Reset all import state. Called by REPL on `:reset`.
     */
    fun resetImports() {
        importRegistry.clear()
    }

    // ========================================================================
    // Core Evaluation Dispatch
    // ========================================================================

    /**
     * Evaluate any AST node.
     *
     * This is the central dispatch method that routes to specialized evaluators.
     * Sub-evaluators call back to this method for recursive evaluation.
     */
    internal fun evaluate(node: Node): Any? {
        return when (node) {
            // --- Declarations (core + typeDecls) ---
            is VarDecl -> evaluateVarDecl(node)
            is FunDecl -> evaluateFunDecl(node)
            is ClassDecl -> typeDecls.evaluateClassDecl(node)
            is TraitDecl -> typeDecls.evaluateTraitDecl(node)
            is EnumDecl -> typeDecls.evaluateEnumDecl(node)
            is StructDecl -> typeDecls.evaluateStructDecl(node)
            is UseDecl -> typeDecls.evaluateUseDecl(node)
            is ExtendDecl -> typeDecls.evaluateExtendDecl(node)
            is StaticBlock -> typeDecls.evaluateStaticBlock(node)

            // --- Statements (core) ---
            is SayStmt -> evaluateSay(node)
            is ExprStmt -> evaluate(node.expression)
            is ReturnStmt -> evaluateReturn(node)
            is ForStmt -> evaluateFor(node)
            is WhileStmt -> evaluateWhile(node)
            is BreakStmt -> throw BreakSignal()
            is ContinueStmt -> throw ContinueSignal()
            is ThrowStmt -> evaluateThrow(node)

            // --- Expressions: Literals (expr) ---
            is LiteralExpr -> expr.evaluateLiteral(node)
            is StringTemplateExpr -> expr.evaluateStringTemplate(node)
            is ListExpr -> expr.evaluateList(node)
            is MapExpr -> expr.evaluateMap(node)

            // --- Expressions: Variables & Access (core + expr) ---
            is IdentifierExpr -> lookupIdentifier(node)
            is ThisExpr -> environment.get("this", node.location)
            is MemberAccessExpr -> expr.evaluateMemberAccess(node)
            is IndexExpr -> expr.evaluateIndex(node)
            is DPECExpr -> typeDecls.evaluateDPEC(node)

            // --- Expressions: Operators (expr) ---
            is BinaryExpr -> expr.evaluateBinary(node)
            is UnaryExpr -> expr.evaluateUnary(node)
            is AssignExpr -> expr.evaluateAssign(node)
            is TernaryExpr -> evaluateTernary(node)

            // --- Expressions: Calls (core) ---
            is CallExpr -> evaluateCall(node)

            // --- Expressions: Control Flow (core) ---
            is IfExpr -> evaluateIf(node)
            is WhenExpr -> evaluateWhen(node)
            is TryExpr -> evaluateTry(node)
            is BlockExpr -> evaluateBlock(node)

            // --- Expressions: Type Operations (expr) ---
            is TypeCheckExpr -> expr.evaluateTypeCheck(node)
            is TypeCastExpr -> expr.evaluateTypeCast(node)
            is InCheckExpr -> expr.evaluateInCheck(node)
            is MatchesExpr -> expr.evaluateMatches(node)
            is RangeExpr -> expr.evaluateRange(node)

            // --- Expressions: Ki Literals (expr) ---
            is GridLiteralExpr -> expr.evaluateGridLiteral(node)
            is CoordinateLiteralExpr -> expr.evaluateCoordinateLiteral(node)

            // --- Expressions: Special (typeDecls) ---
            is LangBlockExpr -> typeDecls.evaluateLangBlock(node)
            is ReflectionExpr -> typeDecls.evaluateReflection(node)

            // --- KD Nodes ---
            is KDTagNode -> typeDecls.evaluateKDTag(node)

            is Program -> executeProgram(node)
        }
    }

    // ========================================================================
    // Variable Declarations
    // ========================================================================

    private fun evaluateVarDecl(decl: VarDecl): Any? {
        val value = if (decl.initializer != null) {
            ops.copyIfStruct(evaluate(decl.initializer))
        } else {
            null
        }

        // Check null safety: reject nil for non-nullable types
        ops.checkNullSafety(decl.name, value, decl.typeAnnotation, decl.location)
        ops.checkTypeCompatibility(decl.name, value, decl.typeAnnotation, decl.location)

        // Check constraint if present
        if (decl.constraint != null && value != null) {
            ops.checkConstraint(decl.name, value, decl.constraint, decl.location)
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
                ops.copyIfStruct(arguments[i])
            } else {
                // Use default value (must exist since we passed arity check)
                param.defaultValue?.let { evaluate(it) }
            }

            // Check null safety for non-nullable parameter types
            ops.checkNullSafety(param.name, value, param.type, location)
            ops.checkTypeCompatibility(param.name, value, param.type, location)

            // Check parameter constraint if present
            if (param.constraint != null && value != null) {
                ops.checkConstraint(param.name, value, param.constraint, param.location)
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
                    evaluate(body) // Last expression value is implicitly returned
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
                param.defaultValue?.let { evaluate(it) }
            }

            // Check null safety for non-nullable parameter types
            ops.checkNullSafety(param.name, value, param.type, location)
            ops.checkTypeCompatibility(param.name, value, param.type, location)

            if (param.constraint != null && value != null) {
                ops.checkConstraint(param.name, value, param.constraint, param.location)
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
                evaluate(body)
            } else {
                try {
                    evaluate(body)
                    // Last expression value is implicitly returned
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
     * Call a struct method with proper `this` binding (delegates to [TypeDeclarationEvaluator]).
     *
     * Public API for external callers (Struct.kt).
     */
    fun callStructMethod(
        receiver: KSStructInstance,
        method: KSFunction,
        arguments: List<Any?>,
        location: SourceLocation?
    ): Any? = typeDecls.callStructMethod(receiver, method, arguments, location)

    /**
     * Evaluate a call expression.
     *
     * Handles function calls, class instantiation, struct instantiation,
     * JVM proxy calls, and generic Callable dispatch.
     */
    private fun evaluateCall(expr: CallExpr): Any? {
        val callee = evaluate(expr.callee)
        val location = expr.location

        // Evaluate arguments
        val args = expr.arguments.map { evaluate(it.value) }

        // Handle different callable types
        return when (callee) {
            is KSFunction -> callFunction(callee, args, location)
            is KSClass -> typeDecls.instantiateClass(callee, args, expr.arguments, location)
            is KSStruct -> typeDecls.instantiateStruct(callee, args, expr.arguments, location)
            is KSEnum -> throw RuntimeError("Cannot instantiate enum '${callee.name}' directly", location)
            is StructCopyCallable -> {
                // Auto-generated copy(): no args = plain copy, named args = copy-with-overrides
                if (expr.arguments.isEmpty()) {
                    callee.receiver.copy()
                } else {
                    val namedArgs = mutableMapOf<String, Any?>()
                    for ((index, argNode) in expr.arguments.withIndex()) {
                        if (argNode.name != null) {
                            namedArgs[argNode.name] = args[index]
                        } else {
                            throw RuntimeError(
                                "copy() requires named arguments: copy(x = 10.0)",
                                location
                            )
                        }
                    }
                    callee.receiver.copyWith(namedArgs, location)
                }
            }

            // JVM class — construct via reflection
            is JVMClassProxy -> callee.construct(args, location)

            // JVM method proxy — already implements Callable, but explicit for clarity
            is JVMMethodProxy -> callee.call(this, args, location)

            // Built-in type constructor — e.g. Grid(2, 3), Grid<Int>(2, 3)
            is KSBuiltinType -> {
                when (callee.name) {
                    "Grid" -> {
                        if (args.size != 2) {
                            throw RuntimeError(
                                "Grid() requires 2 arguments (width, height), got ${args.size}",
                                location
                            )
                        }
                        val width = args[0] as? Int
                            ?: throw RuntimeError("Grid width must be Int, got ${args[0]?.let { ops.runtimeTypeName(it) } ?: "null"}", location)
                        val height = args[1] as? Int
                            ?: throw RuntimeError("Grid height must be Int, got ${args[1]?.let { ops.runtimeTypeName(it) } ?: "null"}", location)

                        if (expr.typeArgs.isNotEmpty()) {
                            val typeRef = expr.typeArgs.first()
                            val elementType = ops.resolveGridElementType(typeRef, location)
                            val storedType = if (elementType == Any::class.java) null else elementType
                            val nullable = typeRef.nullable || storedType == null
                            Grid<Any?>(width, height, Array(width * height) { null }, storedType, nullable)
                        } else {
                            Grid.ofNulls<Any?>(width, height)
                        }
                    }
                    else -> throw NotCallableError(callee, location)
                }
            }

            // Generic Callable dispatch
            is Callable -> callee.call(this, args, location)
            else -> {
                // Check if it's a function name
                if (expr.callee is IdentifierExpr) {
                    val name = (expr.callee as IdentifierExpr).name

                    // Check functions first
                    if (environment.isFunctionDefined(name)) {
                        val fn = environment.getFunction(name, location)
                        return callFunction(fn, args, location)
                    }

                    // Check classes
                    classes[name]?.let { cls ->
                        return typeDecls.instantiateClass(cls, args, expr.arguments, location)
                    }

                    // Check structs
                    structs[name]?.let { struct ->
                        return typeDecls.instantiateStruct(struct, args, expr.arguments, location)
                    }
                }

                // Zero-arg property-style members: when a built-in member like
                // `str.isEmpty` is auto-invoked during member access (returning
                // the value directly), calling it with parens `str.isEmpty()`
                // should also work. The callee IS the result — return it.
                if (expr.callee is MemberAccessExpr && args.isEmpty()) {
                    return callee
                }

                throw NotCallableError(callee, location)
            }
        }
    }

    /**
     * Look up an identifier — could be a variable, function, class, enum, or trait.
     */
    private fun lookupIdentifier(expr: IdentifierExpr): Any? {
        val name = expr.name

        // First try as a variable
        if (environment.isDefined(name)) {
            return environment.get(name, expr.location)
        }

        // Then try as a function
        if (environment.isFunctionDefined(name)) {
            return environment.getFunction(name, expr.location)
        }

        // Try as a class
        classes[name]?.let { return it }

        // Try as a struct
        structs[name]?.let { return it }

        // Try as an enum
        enums[name]?.let { return it }

        // Try as a trait
        traits[name]?.let { return it }

        // If in a method context, try implicit 'this' property access
        if (environment.isDefined("this")) {
            val thisVal = environment.get("this")

            val thisObj = thisVal as? KSObject
            if (thisObj != null) {
                if (thisObj.hasProperty(name)) {
                    return thisObj.get(name, expr.location)
                }
                // Also check for methods
                thisObj.klass.findMethod(name)?.let { method ->
                    return BoundMethod(thisObj, method)
                }
            }

            val thisStruct = thisVal as? KSStructInstance
            if (thisStruct != null) {
                if (thisStruct.hasProperty(name)) {
                    return thisStruct.get(name, expr.location)
                }
                thisStruct.struct.findMethod(name)?.let { method ->
                    return StructBoundMethod(thisStruct, method)
                }
            }
        }

        // Try as a built-in type (String, Int, Bool, etc.)
        // Native type constructors take precedence over plain sentinels —
        // they provide both constructor calls and static member access.
        nativeTypes[name]?.let { return it }
        builtinTypes[name]?.let { return it }

        // Try imports (from `use` declarations)
        if (importRegistry.hasImports()) {
            val resolved = importRegistry.resolve(name)
            if (resolved != null) {
                return resolvedImportToValue(resolved, expr.location)
            }
        }

        throw UndefinedNameError(name, NameKind.VARIABLE, expr.location)
    }

    /**
     * Convert a [ResolvedImport] to a runtime value for use in expressions.
     *
     * Maps import types to their runtime representations:
     * - JVM classes -> [JVMClassProxy] (used for construction and member access)
     * - JVM members -> the raw value
     * - JVM callables -> [JVMMethodProxy] (implements [Callable])
     * - KS types -> the existing KS type object (KSClass, KSStruct, etc.)
     * - KS static members -> the raw value
     */
    private fun resolvedImportToValue(resolved: ResolvedImport, location: SourceLocation?): Any? {
        return when (resolved) {
            is ResolvedImport.JVMClass -> resolved.proxy
            is ResolvedImport.JVMMember -> resolved.value
            is ResolvedImport.JVMCallable -> resolved.callable
            is ResolvedImport.KsClass -> resolved.ksClass
            is ResolvedImport.KsStruct -> resolved.ksStruct
            is ResolvedImport.KsTrait -> resolved.ksTrait
            is ResolvedImport.KsEnum -> resolved.ksEnum
            is ResolvedImport.KsStaticMember -> resolved.value
        }
    }

    // ========================================================================
    // Control Flow Statements
    // ========================================================================

    private fun evaluateReturn(stmt: ReturnStmt): Nothing {
        val value = stmt.value?.let { evaluate(it) }
        throw ReturnValue(value)
    }

    private fun evaluateFor(stmt: ForStmt): Any? {
        val iterableValue = evaluate(stmt.iterable)

        // Handle enum iteration: for Color { say it }
        val items: Iterable<Any?> = when (iterableValue) {
            is KSEnum -> iterableValue.constants.values
            else -> ops.toIterable(iterableValue, stmt.iterable.location)
        }

        val loopEnv = environment.child("for")
        val previousEnv = environment
        environment = loopEnv

        var iterations = 0L

        try {
            for (item in items) {
                if (runtime.maxLoopIterations > 0 && iterations >= runtime.maxLoopIterations) {
                    throw RuntimeError(
                        "Maximum loop iterations exceeded (${runtime.maxLoopIterations})",
                        stmt.location
                    )
                }
                iterations++

                val varName = stmt.variable ?: "it"
                if (iterations == 1L) {
                    loopEnv.define(varName, item, mutable = true)
                } else {
                    loopEnv.assign(varName, item)
                }

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

        while (ops.isTruthy(evaluate(stmt.condition))) {
            if (runtime.maxLoopIterations > 0 && iterations >= runtime.maxLoopIterations) {
                throw RuntimeError(
                    "Maximum loop iterations exceeded (${runtime.maxLoopIterations})",
                    stmt.location
                )
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
        throw RuntimeError(ops.stringify(value), stmt.location)
    }

    // ========================================================================
    // Control Flow Expressions
    // ========================================================================

    private fun evaluateIf(expr: IfExpr): Any? {
        val condition = evaluate(expr.condition)
        return if (ops.isTruthy(condition)) {
            evaluate(expr.thenBranch)
        } else {
            expr.elseBranch?.let { evaluate(it) }
        }
    }

    private fun evaluateWhen(expr: WhenExpr): Any? {
        val subject = expr.subject?.let { evaluate(it) }

        // Set enum context if subject is an enum constant
        val previousEnumContext = currentEnumContext
        if (subject is KSEnumConstant) {
            currentEnumContext = subject.enum
        } else if (subject is KSEnum) {
            currentEnumContext = subject
        }

        try {
            for (branch in expr.branches) {
                if (branch.isElse) {
                    return evaluate(branch.body)
                }

                for (matcher in branch.matchers) {
                    val matches = if (subject != null) {
                        matchesWithSubject(subject, matcher)
                    } else {
                        ops.isTruthy(evaluateMatcher(matcher))
                    }

                    if (matches) {
                        return evaluate(branch.body)
                    }
                }
            }

            throw NonExhaustiveWhenError(subject, expr.location)
        } finally {
            currentEnumContext = previousEnumContext
        }
    }

    private fun matchesWithSubject(subject: Any?, matcher: WhenMatcher): Boolean {
        return when (matcher) {
            is ExpressionMatcher -> ops.isEqual(subject, evaluate(matcher.expr))
            is TypeMatcher -> {
                val typeMatches = ops.checkType(subject, matcher.type)
                if (matcher.negated) !typeMatches else typeMatches
            }
            is InMatcher -> {
                val container = evaluate(matcher.expr)
                val contains = ops.checkContains(container, subject)
                if (matcher.negated) !contains else contains
            }
            is PatternMatcher -> {
                val pattern = evaluate(matcher.pattern)
                ops.matchesPattern(subject, pattern)
            }
            is DPECMatcher -> {
                // DPEC matching: .RED matches enum constant RED
                if (subject is KSEnumConstant) {
                    subject.name == matcher.name
                } else {
                    false
                }
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
            throw e
        } finally {
            expr.finallyBlock?.let { evaluateBlock(it) }
        }
    }

    private fun catchMatches(error: RuntimeError, catch: CatchClause): Boolean {
        // For now, all catches match. Type-based matching requires more infrastructure.
        return true
    }

    internal fun evaluateBlock(block: BlockExpr): Any? {
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
        return if (ops.isTruthy(condition)) {
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
            ops.stringify(evaluate(arg.value))
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
}