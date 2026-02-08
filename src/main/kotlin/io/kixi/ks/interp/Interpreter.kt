package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.lexer.*
import io.kixi.ks.parser.*
import io.kixi.uom.Currency
import io.kixi.uom.Quantity
import io.kixi.uom.Unit as KiUnit
import io.kixi.uom.combineUnits

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
 * - Say: `say`, `say.error`, `say.warn`, `say.note`
 * - Lang blocks: `lang KD { ... }` for embedded DSLs
 * - Reflection: `::class`
 *
 * @param runtime Configuration for interpreter behavior (optional)
 */
class Interpreter(private val runtime: KSRuntime = KSRuntime.DEFAULT) {

    /** Current execution environment (scope chain). */
    internal var environment = Environment.global()

    /** Recursion depth counter for stack overflow protection. */
    private var recursionDepth = 0

    /** Registry of defined classes by name. */
    private val classes = mutableMapOf<String, KSClass>()

    /** Registry of defined traits by name. */
    private val traits = mutableMapOf<String, KSTrait>()

    /** Registry of defined enums by name. */
    private val enums = mutableMapOf<String, KSEnum>()

    /** Registry of defined structs by name. */
    private val structs = mutableMapOf<String, KSStruct>()

    /** Current enum context for DPEC resolution (set during when subject evaluation). */
    private var currentEnumContext: KSEnum? = null

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
            is ClassDecl -> evaluateClassDecl(node)
            is TraitDecl -> evaluateTraitDecl(node)
            is EnumDecl -> evaluateEnumDecl(node)
            is StructDecl -> evaluateStructDecl(node)
            is UseDecl -> evaluateUseDecl(node)
            is ExtendDecl -> evaluateExtendDecl(node)
            is StaticBlock -> evaluateStaticBlock(node)

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
            is LiteralExpr -> evaluateLiteral(node)
            is StringTemplateExpr -> evaluateStringTemplate(node)
            is ListExpr -> evaluateList(node)
            is MapExpr -> evaluateMap(node)

            // --- Expressions: Variables & Access ---
            is IdentifierExpr -> lookupIdentifier(node)
            is ThisExpr -> environment.get("this", node.location)
            is MemberAccessExpr -> evaluateMemberAccess(node)
            is IndexExpr -> evaluateIndex(node)
            is DPECExpr -> evaluateDPEC(node)

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

            // --- Expressions: Special ---
            is LangBlockExpr -> evaluateLangBlock(node)
            is ReflectionExpr -> evaluateReflection(node)

            // --- KD Nodes ---
            is KDTagNode -> evaluateKDTag(node)

            is Program -> executeProgram(node)
        }
    }

    // ========================================================================
    // Variable Declarations
    // ========================================================================

    private fun evaluateVarDecl(decl: VarDecl): Any? {
        val value = if (decl.initializer != null) {
            copyIfStruct(evaluate(decl.initializer))
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
                copyIfStruct(arguments[i])
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
                evaluate(body)
            } else {
                try {
                    evaluate(body)
                    null
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
            is KSClass -> instantiateClass(callee, args, expr.arguments, location)
            is KSStruct -> instantiateStruct(callee, args, expr.arguments, location)
            is KSEnum -> throw RuntimeError("Cannot instantiate enum '${callee.name}' directly", location)
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
                        return instantiateClass(cls, args, expr.arguments, location)
                    }

                    // Check structs
                    structs[name]?.let { struct ->
                        return instantiateStruct(struct, args, expr.arguments, location)
                    }
                }
                throw NotCallableError(callee, location)
            }
        }
    }

    /**
     * Look up an identifier - could be a variable, function, class, enum, or trait.
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

        throw UndefinedNameError(name, NameKind.VARIABLE, expr.location)
    }
    // ========================================================================
    // Class Declarations & Instantiation
    // ========================================================================

    private fun evaluateClassDecl(decl: ClassDecl): Any? {
        // Resolve superclass if specified
        val superclass: KSClass? = if (decl.superTypes.isNotEmpty()) {
            val superTypeName = decl.superTypes.first().name
            classes[superTypeName] ?: traits[superTypeName]?.let { null }
        } else {
            null
        }

        // Resolve traits
        val implementedTraits = decl.superTypes.mapNotNull { typeRef ->
            traits[typeRef.name]
        }

        // Create class
        val ksClass = KSClass(decl, superclass, implementedTraits, environment)
        classes[decl.name] = ksClass

        // Initialize static members
        initializeStaticMembers(ksClass, decl.members)

        // Make class available in environment
        environment.define(decl.name, ksClass, mutable = false, location = decl.location)

        return null
    }

    private fun initializeStaticMembers(ksClass: KSClass, members: List<Node>) {
        val staticEnv = ksClass.staticEnvironment()
        val previousEnv = environment
        environment = staticEnv

        try {
            for (member in members) {
                when (member) {
                    is StaticBlock -> {
                        for (staticMember in member.members) {
                            when (staticMember) {
                                is VarDecl -> {
                                    val value = staticMember.initializer?.let { evaluate(it) }
                                    staticEnv.define(
                                        staticMember.name,
                                        value,
                                        staticMember.mutable,
                                        staticMember.typeAnnotation,
                                        staticMember.constraint,
                                        staticMember.location
                                    )
                                }
                                is FunDecl -> {
                                    val fn = KSFunction(staticMember, staticEnv)
                                    staticEnv.defineFunction(staticMember.name, fn, staticMember.location)
                                }
                                else -> { /* ignore */ }
                            }
                        }
                    }
                    else -> { /* instance members handled during instantiation */ }
                }
            }
        } finally {
            environment = previousEnv
        }
    }

    /**
     * Create a new instance of a class.
     */
    private fun instantiateClass(
        ksClass: KSClass,
        args: List<Any?>,
        argNodes: List<Argument>,
        location: SourceLocation?
    ): KSObject {
        val obj = KSObject(ksClass)

        // Bind constructor parameters to properties
        val params = ksClass.constructorParams

        // Build argument map for named arguments
        val namedArgs = mutableMapOf<String, Any?>()
        val positionalArgs = mutableListOf<Any?>()

        for ((index, argNode) in argNodes.withIndex()) {
            if (argNode.name != null) {
                namedArgs[argNode.name] = args[index]
            } else {
                positionalArgs.add(args[index])
            }
        }

        // Assign values to constructor parameters
        var positionalIndex = 0
        for (param in params) {
            val value = when {
                namedArgs.containsKey(param.name) -> namedArgs[param.name]
                positionalIndex < positionalArgs.size -> positionalArgs[positionalIndex++]
                param.defaultValue != null -> evaluate(param.defaultValue)
                else -> throw ArityError(ksClass.name, params.size, args.size, location)
            }

            // Check constraint
            if (param.constraint != null && value != null) {
                checkConstraint(param.name, value, param.constraint, location)
            }

            // If param has binding (var/let), create a property
            if (param.binding != null) {
                obj.initProperty(param.name, value, param.binding == BindingType.VAR)
            }
        }

        // Initialize instance properties from class body
        val previousEnv = environment
        val instanceEnv = environment.child("instance:${ksClass.name}")
        instanceEnv.define("this", obj, mutable = false, location = location)
        environment = instanceEnv

        try {
            for (property in ksClass.getInstanceProperties()) {
                val value = property.initializer?.let { evaluate(it) }
                if (property.constraint != null && value != null) {
                    checkConstraint(property.name, value, property.constraint, property.location)
                }
                obj.initProperty(property.name, value, property.mutable)
            }
        } finally {
            environment = previousEnv
        }

        return obj
    }

    // ========================================================================
    // Trait Declarations
    // ========================================================================

    private fun evaluateTraitDecl(decl: TraitDecl): Any? {
        // Resolve super traits
        val superTraits = decl.superTraits.mapNotNull { typeRef ->
            traits[typeRef.name]
        }

        val ksTrait = KSTrait(decl, superTraits, environment)
        traits[decl.name] = ksTrait

        // Make trait available in environment
        environment.define(decl.name, ksTrait, mutable = false, location = decl.location)

        return null
    }

    // ========================================================================
    // Struct Declarations & Instantiation
    // ========================================================================

    private fun evaluateStructDecl(decl: StructDecl): Any? {
        // Resolve traits (structs can only implement traits, no superclass)
        val implementedTraits = decl.traits.mapNotNull { typeRef ->
            traits[typeRef.name] ?: run {
                // Check if it's a class — structs can't extend classes
                if (classes.containsKey(typeRef.name)) {
                    throw RuntimeError(
                        "Struct '${decl.name}' cannot extend class '${typeRef.name}'. Structs can only implement traits.",
                        decl.location
                    )
                }
                null
            }
        }

        val ksStruct = KSStruct(decl, implementedTraits, environment)
        structs[decl.name] = ksStruct

        // Initialize static members
        initializeStructStaticMembers(ksStruct, decl.members)

        // Make struct available in environment
        environment.define(decl.name, ksStruct, mutable = false, location = decl.location)

        return null
    }

    private fun initializeStructStaticMembers(ksStruct: KSStruct, members: List<Node>) {
        val staticEnv = ksStruct.staticEnvironment()
        val previousEnv = environment
        environment = staticEnv

        try {
            for (member in members) {
                when (member) {
                    is StaticBlock -> {
                        for (staticMember in member.members) {
                            when (staticMember) {
                                is VarDecl -> {
                                    val value = staticMember.initializer?.let { evaluate(it) }
                                    staticEnv.define(
                                        staticMember.name, value, staticMember.mutable,
                                        staticMember.typeAnnotation, staticMember.constraint,
                                        staticMember.location
                                    )
                                }
                                is FunDecl -> {
                                    val fn = KSFunction(staticMember, staticEnv)
                                    staticEnv.defineFunction(staticMember.name, fn, staticMember.location)
                                }
                                else -> {}
                            }
                        }
                    }
                    else -> {}
                }
            }
        } finally {
            environment = previousEnv
        }
    }

    /**
     * Create a new instance of a struct.
     */
    private fun instantiateStruct(
        ksStruct: KSStruct,
        args: List<Any?>,
        argNodes: List<Argument>,
        location: SourceLocation?
    ): KSStructInstance {
        val instance = KSStructInstance(ksStruct)

        val params = ksStruct.constructorParams

        // Build argument map for named arguments
        val namedArgs = mutableMapOf<String, Any?>()
        val positionalArgs = mutableListOf<Any?>()

        for ((index, argNode) in argNodes.withIndex()) {
            if (argNode.name != null) {
                namedArgs[argNode.name] = args[index]
            } else {
                positionalArgs.add(args[index])
            }
        }

        // Assign values to constructor parameters
        var positionalIndex = 0
        for (param in params) {
            val value = when {
                namedArgs.containsKey(param.name) -> namedArgs[param.name]
                positionalIndex < positionalArgs.size -> positionalArgs[positionalIndex++]
                param.defaultValue != null -> evaluate(param.defaultValue)
                else -> throw ArityError(ksStruct.name, params.size, args.size, location)
            }

            // Check constraint
            if (param.constraint != null && value != null) {
                checkConstraint(param.name, value, param.constraint, location)
            }

            // If param has binding (var/let), create a property
            if (param.binding != null) {
                instance.initProperty(param.name, value, param.binding == BindingType.VAR)
            }
        }

        // Initialize instance properties from struct body
        val previousEnv = environment
        val instanceEnv = environment.child("instance:${ksStruct.name}")
        instanceEnv.define("this", instance, mutable = false, location = location)
        environment = instanceEnv

        try {
            for (property in ksStruct.getInstanceProperties()) {
                val value = property.initializer?.let { evaluate(it) }
                if (property.constraint != null && value != null) {
                    checkConstraint(property.name, value, property.constraint, property.location)
                }
                instance.initProperty(property.name, value, property.mutable)
            }
        } finally {
            environment = previousEnv
        }

        return instance
    }

    /**
     * Call a method on a struct instance with proper `this` binding.
     */
    fun callStructMethod(receiver: KSStructInstance, method: KSFunction, arguments: List<Any?>, location: SourceLocation?): Any? {
        if (runtime.maxRecursionDepth > 0 && recursionDepth >= runtime.maxRecursionDepth) {
            throw RuntimeError("Maximum recursion depth exceeded (${runtime.maxRecursionDepth})", location)
        }

        val params = method.params
        if (arguments.size < method.requiredArity) {
            throw ArityError(method.name, method.requiredArity, arguments.size, location)
        }
        if (arguments.size > method.totalArity) {
            throw ArityError(method.name, method.totalArity, arguments.size, location)
        }

        val methodEnv = method.closure.child("method:${method.name}")
        methodEnv.define("this", receiver, mutable = false, location = location)

        for (i in params.indices) {
            val param = params[i]
            val value = if (i < arguments.size) {
                copyIfStruct(arguments[i])
            } else {
                param.defaultValue?.let { evaluate(it) }
            }

            if (param.constraint != null && value != null) {
                checkConstraint(param.name, value, param.constraint, param.location)
            }

            methodEnv.define(param.name, value, mutable = true, param.type, param.constraint, param.location)
        }

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
                    null
                } catch (ret: ReturnValue) {
                    ret.value
                }
            }
        } finally {
            recursionDepth--
            environment = previousEnv
        }
    }

    // ========================================================================
    // Enum Declarations
    // ========================================================================

    private fun evaluateEnumDecl(decl: EnumDecl): Any? {
        val ksEnum = KSEnum(decl, environment)
        enums[decl.name] = ksEnum

        // Initialize enum constants
        for ((index, constant) in decl.constants.withIndex()) {
            val enumValue = when {
                // Constructor-style: OK(200, "OK")
                constant.arguments.isNotEmpty() -> {
                    val args = constant.arguments.map { evaluate(it.value) }
                    KSEnumConstant(ksEnum, constant.name, index, args)
                }
                // Value-style: Apple = 1
                constant.value != null -> {
                    val value = evaluate(constant.value)
                    KSEnumConstant(ksEnum, constant.name, index, listOf(value))
                }
                // Simple: RED
                else -> {
                    KSEnumConstant(ksEnum, constant.name, index, emptyList())
                }
            }
            ksEnum.addConstant(constant.name, enumValue)
        }

        // Initialize static members
        for (member in decl.members) {
            when (member) {
                is FunDecl -> {
                    val fn = KSFunction(member, environment)
                    ksEnum.staticMembers.defineFunction(member.name, fn, member.location)
                }
                is VarDecl -> {
                    val value = member.initializer?.let { evaluate(it) }
                    ksEnum.staticMembers.define(
                        member.name,
                        value,
                        member.mutable,
                        member.typeAnnotation,
                        member.constraint,
                        member.location
                    )
                }
                is StaticBlock -> {
                    val previousEnv = environment
                    environment = ksEnum.staticMembers
                    try {
                        for (staticMember in member.members) {
                            evaluate(staticMember)
                        }
                    } finally {
                        environment = previousEnv
                    }
                }
                else -> { /* ignore */ }
            }
        }

        // Make enum available in environment
        environment.define(decl.name, ksEnum, mutable = false, location = decl.location)

        return null
    }

    // ========================================================================
    // DPEC - Dot-Prefixed Enum Constant
    // ========================================================================

    /**
     * Evaluate a DPEC expression like `.RED` or `.SUCCESS`.
     *
     * Resolution order:
     * 1. Current enum context (from when expression subject)
     * 2. Infer from assignment target type annotation
     * 3. Search all enums for a matching constant
     */
    private fun evaluateDPEC(expr: DPECExpr): Any? {
        val constantName = expr.name

        // First, check current enum context (set during when evaluation)
        currentEnumContext?.let { enum ->
            enum.getConstant(constantName)?.let { return it }
        }

        // Search all enums for the constant
        for ((_, enum) in enums) {
            enum.getConstant(constantName)?.let { return it }
        }

        throw UndefinedNameError(constantName, NameKind.ENUM_CONSTANT, expr.location)
    }

    // ========================================================================
    // Use (Import) Declarations
    // ========================================================================

    private fun evaluateUseDecl(decl: UseDecl): Any? {
        // For now, use declarations are informational only.
        // Full module system would require a module loader.
        // In portable mode (hostLang = false), we only have KS standard library.

        if (runtime.debugMode) {
            val path = decl.path.joinToString(".")
            val suffix = if (decl.wildcard) ".*" else ""
            val alias = decl.alias?.let { " as $it" } ?: ""
            runtime.outputWriter.println("[DEBUG] use $path$suffix$alias")
        }

        return null
    }

    // ========================================================================
    // Extend Declarations
    // ========================================================================

    private fun evaluateExtendDecl(decl: ExtendDecl): Any? {
        // Type extensions add methods to existing types.
        // For now, we store extension methods separately.
        // Full implementation would integrate with method resolution.

        if (runtime.debugMode) {
            val target = decl.target.name
            val kind = if (decl.isTraitExtension) "trait " else ""
            runtime.outputWriter.println("[DEBUG] extend $kind$target with ${decl.members.size} members")
        }

        return null
    }

    // ========================================================================
    // Static Blocks
    // ========================================================================

    private fun evaluateStaticBlock(block: StaticBlock): Any? {
        // Static blocks are processed during class/enum declaration
        // If encountered at top level, evaluate as a regular block
        for (member in block.members) {
            evaluate(member)
        }
        return null
    }

    // ========================================================================
    // Lang Block (DSL)
    // ========================================================================

    /**
     * Evaluate a lang block expression.
     *
     * Currently only KD is supported:
     *
     *     lang KD {
     *         book "The Hobbit" author="Tolkien"
     *         book "Dune" author="Herbert"
     *     }
     *
     * Returns a KDTag if there's a single root tag, or a KDDocument for multiple root tags.
     */
    private fun evaluateLangBlock(expr: LangBlockExpr): Any? {
        return when (expr.language.uppercase()) {
            "KD" -> {
                val tags = expr.body.map { evaluateKDTag(it) }
                if (tags.size == 1) tags[0] else KDDocument(tags)
            }
            else -> throw RuntimeError("Unsupported DSL language: '${expr.language}'", expr.location)
        }
    }

    /**
     * Evaluate a KD tag into a KDTag runtime object.
     */
    private fun evaluateKDTag(tag: KDTagNode): KDTag {
        // Evaluate values
        val values = tag.values.map { evaluate(it) }

        // Evaluate attributes
        val attributes = tag.attributes.associate { attr ->
            val key = if (attr.namespace != null) "${attr.namespace}:${attr.name}" else attr.name
            key to evaluate(attr.value)
        }

        // Evaluate annotations
        val annotations = tag.annotations.map { ann ->
            KDAnnotation(
                ann.name,
                ann.values.map { evaluate(it) },
                ann.attributes.associate { attr ->
                    val key = if (attr.namespace != null) "${attr.namespace}:${attr.name}" else attr.name
                    key to evaluate(attr.value)
                }
            )
        }

        // Recursively evaluate children
        val children = tag.children.map { evaluateKDTag(it) }

        return KDTag(
            name = tag.name,
            namespace = tag.namespace,
            values = values,
            attributes = attributes,
            annotations = annotations,
            children = children
        )
    }

    // ========================================================================
    // Reflection
    // ========================================================================

    /**
     * Evaluate a reflection expression like `x::class`.
     */
    private fun evaluateReflection(expr: ReflectionExpr): Any? {
        val value = evaluate(expr.expr)

        return when (expr.member) {
            "class" -> getKSType(value)
            else -> throw RuntimeError("Unknown reflection member: '${expr.member}'", expr.location)
        }
    }

    /**
     * Get the KS type representation of a value.
     */
    private fun getKSType(value: Any?): KSType {
        return when (value) {
            null -> KSType("Nil")
            is Int -> KSType("Int")
            is Long -> KSType("Long")
            is Float -> KSType("Float")
            is Double -> KSType("Double")
            is Dec -> KSType("Dec")
            is Quantity<*> -> KSType("Quantity", value.unit.symbol)
            is String -> KSType("String")
            is Char -> KSType("Char")
            is Boolean -> KSType("Bool")
            is List<*> -> KSType("List")
            is Map<*, *> -> KSType("Map")
            is KSObject -> KSType(value.klass.name)
            is KSStructInstance -> KSType(value.struct.name)
            is KSEnumConstant -> KSType(value.enum.name)
            is KSClass -> KSType("Class", value.name)
            is KSStruct -> KSType("Struct", value.name)
            is KSTrait -> KSType("Trait", value.name)
            is KSEnum -> KSType("Enum", value.name)
            is KSFunction -> KSType("Function", value.name)
            is KSRange -> KSType("Range")
            is KDTag -> KSType("KDTag")
            is KDDocument -> KSType("KDDocument")
            else -> KSType(value::class.simpleName ?: "Unknown")
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
            else -> toIterable(iterableValue, stmt.iterable.location)
        }

        val loopEnv = environment.child("for")
        val previousEnv = environment
        environment = loopEnv

        var iterations = 0L

        try {
            for (item in items) {
                if (runtime.maxLoopIterations > 0 && iterations >= runtime.maxLoopIterations) {
                    throw RuntimeError("Maximum loop iterations exceeded (${runtime.maxLoopIterations})", stmt.location)
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

        while (isTruthy(evaluate(stmt.condition))) {
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
                        isTruthy(evaluateMatcher(matcher))
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
    // Literal Evaluation
    // ========================================================================

    /**
     * Evaluate a literal expression.
     *
     * Most literals are stored directly in the AST node. Quantity and currency
     * quantity literals store raw text that must be parsed into [Quantity] objects
     * at evaluation time using Ki.Core's parsing infrastructure.
     */
    private fun evaluateLiteral(expr: LiteralExpr): Any? {
        return when (expr.kind) {
            LiteralKind.QUANTITY -> parseQuantityLiteral(expr.value as String, expr.location)
            LiteralKind.CURRENCY_QUANTITY -> parseCurrencyQuantityLiteral(expr.value as String, expr.location)
            else -> expr.value
        }
    }

    /**
     * Parse a quantity literal from raw text.
     *
     * Examples: `23cm`, `51.4m³`, `1000kg`, `25°C`, `97ℓ`, `100USD`, `5.5e(-7)m`
     *
     * Delegates to [Quantity.parse] which handles all forms including scientific
     * notation and type specifiers.
     */
    private fun parseQuantityLiteral(text: String, location: SourceLocation?): Quantity<*> {
        return try {
            Quantity.parse(text)
        } catch (e: Exception) {
            throw RuntimeError("Invalid quantity literal '$text': ${e.message}", location)
        }
    }

    /**
     * Parse a currency quantity literal from prefix notation.
     *
     * Converts prefix notation (`$23.53`) to suffix notation (`23.53USD`) and
     * delegates to [Quantity.parse].
     *
     * Examples: `$23.53` → `23.53USD`, `€50.25:d` → `50.25EUR:d`, `₿0.5` → `0.5BTC`
     */
    private fun parseCurrencyQuantityLiteral(text: String, location: SourceLocation?): Quantity<*> {
        val prefixChar = text[0]
        val currency = Currency.fromPrefix(prefixChar)
            ?: throw RuntimeError("Unknown currency prefix: '$prefixChar'", location)

        // Convert prefix notation to suffix notation for Quantity.parse
        val numericPart = text.substring(1) // e.g., "23.53" or "23.53:d"
        val colonIdx = numericPart.indexOf(':')
        val suffixForm = if (colonIdx >= 0) {
            // Insert currency symbol before type specifier: "23.53:d" → "23.53USD:d"
            numericPart.substring(0, colonIdx) + currency.symbol + numericPart.substring(colonIdx)
        } else {
            // Append currency symbol: "23.53" → "23.53USD"
            numericPart + currency.symbol
        }

        return try {
            Quantity.parse(suffixForm)
        } catch (e: Exception) {
            throw RuntimeError("Invalid currency quantity '$text': ${e.message}", location)
        }
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

        return when (obj) {
            is KSObject -> obj.get(expr.member, expr.location)
            is KSStructInstance -> {
                // Check for auto-generated copy() method
                if (expr.member == "copy" && obj.struct.findMethod("copy") == null) {
                    return StructCopyCallable(obj)
                }
                obj.get(expr.member, expr.location)
            }
            is KSClass -> obj.getStatic(expr.member)
                ?: throw MemberNotFoundError(expr.member, obj.name, expr.location)
            is KSStruct -> obj.getStatic(expr.member)
                ?: throw MemberNotFoundError(expr.member, obj.name, expr.location)
            is KSEnum -> {
                // Could be a constant or static member
                obj.getConstant(expr.member)
                    ?: obj.staticMembers.get(expr.member)
                    ?: throw MemberNotFoundError(expr.member, obj.name, expr.location)
            }
            is KSEnumConstant -> getEnumConstantMember(obj, expr.member, expr.location)
            is String -> getStringMember(obj, expr.member, expr.location)
            is List<*> -> getListMember(obj, expr.member, expr.location)
            is Map<*, *> -> getMapMember(obj, expr.member, expr.location)
            is KSRange -> getRangeMember(obj, expr.member, expr.location)
            is Quantity<*> -> getQuantityMember(obj, expr.member, expr.location)
            is KDTag -> getKDTagMember(obj, expr.member, expr.location)
            is KDDocument -> getKDDocumentMember(obj, expr.member, expr.location)
            else -> throw MemberNotFoundError(expr.member, obj::class.simpleName ?: "Unknown", expr.location)
        }
    }

    private fun evaluateIndex(expr: IndexExpr): Any? {
        val obj = evaluate(expr.obj)
        val indices = expr.indices.map { evaluate(it) }

        // Multi-index: always dispatch to get() method on KSObject
        if (indices.size > 1) {
            return when (obj) {
                is KSObject -> {
                    val getMethod = obj.klass.findMethod("get")
                        ?: throw RuntimeError(
                            "Class '${obj.klass.name}' does not define a 'get' method for multi-index access",
                            expr.location
                        )
                    callMethod(obj, getMethod, indices, expr.location)
                }
                is KSStructInstance -> {
                    val getMethod = obj.struct.findMethod("get")
                        ?: throw RuntimeError(
                            "Struct '${obj.struct.name}' does not define a 'get' method for multi-index access",
                            expr.location
                        )
                    callStructMethod(obj, getMethod, indices, expr.location)
                }
                null -> throw NullPointerError("Cannot index into nil", expr.location)
                else -> throw TypeError(
                    "Multi-index access requires a class with a 'get' method, got ${obj::class.simpleName}",
                    expr.location
                )
            }
        }

        // Single index
        val index = indices[0]

        return when (obj) {
            is List<*> -> {
                val i = toInt(index, expr.indices[0].location)
                if (i < 0 || i >= obj.size) {
                    throw IndexOutOfBoundsError(i, obj.size, expr.location)
                }
                obj[i]
            }
            is Map<*, *> -> obj[index]
            is String -> {
                val i = toInt(index, expr.indices[0].location)
                if (i < 0 || i >= obj.length) {
                    throw IndexOutOfBoundsError(i, obj.length, expr.location)
                }
                obj[i]
            }
            is KSObject -> {
                // Desugar obj[index] -> obj.get(index)
                val getMethod = obj.klass.findMethod("get")
                    ?: throw RuntimeError(
                        "Class '${obj.klass.name}' does not define a 'get' method for index access",
                        expr.location
                    )
                callMethod(obj, getMethod, listOf(index), expr.location)
            }
            is KSStructInstance -> {
                val getMethod = obj.struct.findMethod("get")
                    ?: throw RuntimeError(
                        "Struct '${obj.struct.name}' does not define a 'get' method for index access",
                        expr.location
                    )
                callStructMethod(obj, getMethod, listOf(index), expr.location)
            }
            is KDTag -> {
                when (index) {
                    is Int -> obj.children.getOrNull(index)
                    is String -> obj.attributes[index]
                    else -> throw TypeError("KDTag index must be Int or String", expr.location)
                }
            }
            is KDDocument -> {
                when (index) {
                    is Int -> obj.tags.getOrNull(index)
                    is String -> obj.tags.firstOrNull { it.name == index }
                    else -> throw TypeError("KDDocument index must be Int or String", expr.location)
                }
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
                // Check if this is a variable in scope
                if (environment.isDefined(target.name)) {
                    val newValue = copyIfStruct(computeAssignment(
                        expr.operator,
                        { environment.get(target.name, target.location) },
                        value
                    ))

                    val constraint = environment.getConstraint(target.name)
                    if (constraint != null && newValue != null) {
                        checkConstraint(target.name, newValue, constraint, expr.location)
                    }

                    environment.assign(target.name, newValue, expr.location)
                    return newValue
                }

                // If not in scope but we're in a method, try implicit 'this' property
                if (environment.isDefined("this")) {
                    val thisVal = environment.get("this")

                    val thisObj = thisVal as? KSObject
                    if (thisObj != null && thisObj.hasProperty(target.name)) {
                        val newValue = computeAssignment(
                            expr.operator,
                            { thisObj.get(target.name, target.location) },
                            value
                        )
                        thisObj.set(target.name, newValue, expr.location)
                        return newValue
                    }

                    val thisStruct = thisVal as? KSStructInstance
                    if (thisStruct != null && thisStruct.hasProperty(target.name)) {
                        val newValue = computeAssignment(
                            expr.operator,
                            { thisStruct.get(target.name, target.location) },
                            value
                        )
                        thisStruct.set(target.name, newValue, expr.location)
                        return newValue
                    }
                }

                // Not found anywhere
                throw UndefinedNameError(target.name, NameKind.VARIABLE, expr.location)
            }
            is IndexExpr -> {
                val obj = evaluate(target.obj)
                val indices = target.indices.map { evaluate(it) }

                // KSObject: dispatch to set() method (single or multi-index)
                if (obj is KSObject) {
                    val getMethod = obj.klass.findMethod("get")
                    val setMethod = obj.klass.findMethod("set")
                        ?: throw RuntimeError(
                            "Class '${obj.klass.name}' does not define a 'set' method for index assignment",
                            target.location
                        )
                    val newValue = computeAssignment(expr.operator, {
                        if (getMethod != null) callMethod(obj, getMethod, indices, target.location)
                        else throw RuntimeError(
                            "Class '${obj.klass.name}' does not define a 'get' method (needed for compound assignment)",
                            target.location
                        )
                    }, value)
                    callMethod(obj, setMethod, indices + newValue, target.location)
                    return newValue
                }

                // KSStructInstance: dispatch to set() method (single or multi-index)
                if (obj is KSStructInstance) {
                    val getMethod = obj.struct.findMethod("get")
                    val setMethod = obj.struct.findMethod("set")
                        ?: throw RuntimeError(
                            "Struct '${obj.struct.name}' does not define a 'set' method for index assignment",
                            target.location
                        )
                    val newValue = computeAssignment(expr.operator, {
                        if (getMethod != null) callStructMethod(obj, getMethod, indices, target.location)
                        else throw RuntimeError(
                            "Struct '${obj.struct.name}' does not define a 'get' method (needed for compound assignment)",
                            target.location
                        )
                    }, value)
                    callStructMethod(obj, setMethod, indices + newValue, target.location)
                    return newValue
                }

                // Multi-index on non-KSObject/KSStructInstance is an error
                if (indices.size > 1) {
                    throw TypeError(
                        "Multi-index assignment requires a class with a 'set' method, got ${obj?.javaClass?.simpleName}",
                        target.location
                    )
                }

                // Single index on built-in types
                val index = indices[0]

                when (obj) {
                    is MutableList<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val list = obj as MutableList<Any?>
                        val i = toInt(index, target.indices[0].location)
                        if (i < 0 || i >= list.size) {
                            throw IndexOutOfBoundsError(i, list.size, target.location)
                        }
                        val newValue = computeAssignment(expr.operator, { list[i] }, value)
                        list[i] = newValue
                        return newValue
                    }
                    is MutableMap<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val map = obj as MutableMap<Any?, Any?>
                        val newValue = computeAssignment(expr.operator, { map[index] }, value)
                        map[index] = newValue
                        return newValue
                    }
                    is List<*> -> {
                        // For immutable lists, we need to create a new list
                        throw RuntimeError("Cannot modify immutable list. Use a mutable list.", target.location)
                    }
                    is Map<*, *> -> {
                        throw RuntimeError("Cannot modify immutable map. Use a mutable map.", target.location)
                    }
                    null -> throw NullPointerError("Cannot index-assign into nil", target.location)
                    else -> throw TypeError("Cannot index-assign into ${obj::class.simpleName}", target.location)
                }
            }
            is MemberAccessExpr -> {
                val obj = evaluate(target.obj)

                if (obj == null) {
                    throw NullPointerError("Cannot assign to member '${target.member}' on nil", expr.location)
                }

                when (obj) {
                    is KSObject -> {
                        val newValue = computeAssignment(
                            expr.operator,
                            { obj.get(target.member, target.location) },
                            value
                        )
                        obj.set(target.member, newValue, expr.location)
                        return newValue
                    }
                    is KSStructInstance -> {
                        val newValue = copyIfStruct(computeAssignment(
                            expr.operator,
                            { obj.get(target.member, target.location) },
                            value
                        ))
                        obj.set(target.member, newValue, expr.location)
                        return newValue
                    }
                    is MutableMap<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val map = obj as MutableMap<Any?, Any?>
                        val newValue = computeAssignment(
                            expr.operator,
                            { map[target.member] },
                            value
                        )
                        map[target.member] = newValue
                        return newValue
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

    private fun computeAssignment(op: AssignOp, getCurrent: () -> Any?, newValue: Any?): Any? {
        return when (op) {
            AssignOp.ASSIGN -> newValue
            AssignOp.PLUS_ASSIGN -> add(getCurrent(), newValue)
            AssignOp.MINUS_ASSIGN -> subtract(getCurrent(), newValue)
            AssignOp.STAR_ASSIGN -> multiply(getCurrent(), newValue)
            AssignOp.SLASH_ASSIGN -> divide(getCurrent(), newValue)
            AssignOp.MODULO_ASSIGN -> modulo(getCurrent(), newValue)
            AssignOp.POWER_ASSIGN -> power(getCurrent(), newValue)
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
            BinaryOp.ADD -> add(left, right)
            BinaryOp.SUBTRACT -> subtract(left, right)
            BinaryOp.MULTIPLY -> multiply(left, right)
            BinaryOp.DIVIDE -> divide(left, right)
            BinaryOp.MODULO -> modulo(left, right)
            BinaryOp.POWER -> power(left, right)

            BinaryOp.EQUAL -> isEqual(left, right)
            BinaryOp.NOT_EQUAL -> !isEqual(left, right)
            BinaryOp.LESS -> compare(left, right) < 0
            BinaryOp.GREATER -> compare(left, right) > 0
            BinaryOp.LESS_EQUAL -> compare(left, right) <= 0
            BinaryOp.GREATER_EQUAL -> compare(left, right) >= 0

            BinaryOp.AND -> isTruthy(left) && isTruthy(right)
            BinaryOp.OR -> isTruthy(left) || isTruthy(right)

            BinaryOp.ELVIS -> left ?: right

            BinaryOp.COMBINE -> evaluateCombine(left, right, expr.location)
        }
    }

    // ========================================================================
    // Combine (⚭) Operator
    // ========================================================================

    /**
     * Evaluate the unit composition operator ⚭.
     *
     * Combines two quantities into a higher-dimensional unit:
     * - Length × Length → Area:   `4cm ⚭ 3cm → 12cm²`
     * - Length × Area → Volume:  `2m ⚭ 3m² → 6m³`
     * - Area × Length → Volume:  `3m² ⚭ 2m → 6m³`
     *
     * If units match, the combination is direct. If units differ within the
     * same dimension (e.g., m and cm), both are converted to base units first.
     */
    @Suppress("UNCHECKED_CAST")
    private fun evaluateCombine(left: Any?, right: Any?, location: SourceLocation?): Quantity<*> {
        if (left !is Quantity<*> || right !is Quantity<*>) {
            throw TypeError(
                "Combine operator ⚭ requires quantity operands, got " +
                        "${left?.let { it::class.simpleName } ?: "nil"} and " +
                        "${right?.let { it::class.simpleName } ?: "nil"}",
                location
            )
        }

        val lUnit = left.unit
        val rUnit = right.unit

        // Try to combine units directly
        val resultUnit = combineUnits(lUnit, rUnit)
            ?: throw RuntimeError(
                "Cannot combine units '${lUnit.symbol}' and '${rUnit.symbol}' " +
                        "(supported: Length×Length→Area, Length×Area→Volume)",
                location
            )

        // If units are the same, multiply values directly
        val resultValue = if (lUnit == rUnit) {
            multiplyNumbers(left.value, right.value)
        } else {
            // Convert both to base units before multiplying
            val lBase = (left as Quantity<KiUnit>).convertTo(lUnit.baseUnit as KiUnit)
            val rBase = (right as Quantity<KiUnit>).convertTo(rUnit.baseUnit as KiUnit)
            multiplyNumbers(lBase.value, rBase.value)
        }

        return Quantity(resultValue, resultUnit)
    }

    /**
     * Multiply two Number values preserving appropriate types.
     * Used by [evaluateCombine] for unit composition.
     */
    private fun multiplyNumbers(a: Number, b: Number): Number {
        return when {
            a is Dec || b is Dec -> toBigDecimal(a).multiply(toBigDecimal(b))
            a is Double || b is Double -> a.toDouble() * b.toDouble()
            a is Float || b is Float -> a.toFloat() * b.toFloat()
            a is Long || b is Long -> a.toLong() * b.toLong()
            else -> {
                val result = a.toLong() * b.toLong()
                if (result in Int.MIN_VALUE..Int.MAX_VALUE) result.toInt() else result
            }
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
                evaluateIncDec(expr, true)
            }
            UnaryOp.DECREMENT -> {
                evaluateIncDec(expr, false)
            }
            UnaryOp.NON_NULL -> {
                val operand = evaluate(expr.operand)
                operand ?: throw NullAssertionError(expr.location)
            }
        }
    }

    private fun evaluateIncDec(expr: UnaryExpr, isIncrement: Boolean): Any? {
        when (val operand = expr.operand) {
            is IdentifierExpr -> {
                val name = operand.name
                val current = environment.get(name, expr.location)
                val newVal = if (isIncrement) add(current, 1) else subtract(current, 1)
                environment.assign(name, newVal, expr.location)
                return if (expr.prefix) newVal else current
            }
            is MemberAccessExpr -> {
                val obj = evaluate(operand.obj)
                if (obj is KSObject) {
                    val current = obj.get(operand.member, operand.location)
                    val newVal = if (isIncrement) add(current, 1) else subtract(current, 1)
                    obj.set(operand.member, newVal, operand.location)
                    return if (expr.prefix) newVal else current
                }
                if (obj is KSStructInstance) {
                    val current = obj.get(operand.member, operand.location)
                    val newVal = if (isIncrement) add(current, 1) else subtract(current, 1)
                    obj.set(operand.member, newVal, operand.location)
                    return if (expr.prefix) newVal else current
                }
                throw RuntimeError("Cannot increment/decrement member of ${obj?.javaClass?.simpleName}", expr.location)
            }
            is IndexExpr -> {
                val obj = evaluate(operand.obj)
                val indices = operand.indices.map { evaluate(it) }

                // KSObject: dispatch to get/set methods
                if (obj is KSObject) {
                    val getMethod = obj.klass.findMethod("get")
                        ?: throw RuntimeError(
                            "Class '${obj.klass.name}' does not define a 'get' method for index access",
                            expr.location
                        )
                    val setMethod = obj.klass.findMethod("set")
                        ?: throw RuntimeError(
                            "Class '${obj.klass.name}' does not define a 'set' method for index assignment",
                            expr.location
                        )
                    val current = callMethod(obj, getMethod, indices, operand.location)
                    val newVal = if (isIncrement) add(current, 1) else subtract(current, 1)
                    callMethod(obj, setMethod, indices + newVal, operand.location)
                    return if (expr.prefix) newVal else current
                }

                // KSStructInstance: dispatch to get/set methods
                if (obj is KSStructInstance) {
                    val getMethod = obj.struct.findMethod("get")
                        ?: throw RuntimeError(
                            "Struct '${obj.struct.name}' does not define a 'get' method for index access",
                            expr.location
                        )
                    val setMethod = obj.struct.findMethod("set")
                        ?: throw RuntimeError(
                            "Struct '${obj.struct.name}' does not define a 'set' method for index assignment",
                            expr.location
                        )
                    val current = callStructMethod(obj, getMethod, indices, operand.location)
                    val newVal = if (isIncrement) add(current, 1) else subtract(current, 1)
                    callStructMethod(obj, setMethod, indices + newVal, operand.location)
                    return if (expr.prefix) newVal else current
                }

                // Built-in: single index on MutableList
                if (indices.size == 1 && obj is MutableList<*>) {
                    val index = indices[0]
                    @Suppress("UNCHECKED_CAST")
                    val list = obj as MutableList<Any?>
                    val i = toInt(index, operand.indices[0].location)
                    val current = list[i]
                    val newVal = if (isIncrement) add(current, 1) else subtract(current, 1)
                    list[i] = newVal
                    return if (expr.prefix) newVal else current
                }

                throw RuntimeError("Cannot increment/decrement index of ${obj?.javaClass?.simpleName}", expr.location)
            }
            else -> {
                // For non-assignable operands, just compute the result
                val current = evaluate(operand)
                return if (isIncrement) add(current, 1) else subtract(current, 1)
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
            "Quantity" -> value is Quantity<*>
            "String" -> value is String
            "Char" -> value is Char
            "Bool" -> value is Boolean
            "List" -> value is List<*>
            "Map" -> value is Map<*, *>
            "Any" -> true
            else -> {
                // Check user-defined types
                when (value) {
                    is KSObject -> value.klass.name == type.name || value.klass.isSubclassOf(classes[type.name] ?: return false)
                    is KSStructInstance -> value.struct.name == type.name
                    is KSEnumConstant -> value.enum.name == type.name
                    else -> false
                }
            }
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
            else -> {
                // For user types, just check and return
                if (checkType(value, type)) value
                else throw CastError(value, type.name, location)
            }
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
            is KSEnum -> {
                // Check if value is a constant of this enum
                when (value) {
                    is KSEnumConstant -> value.enum == container
                    is String -> container.getConstant(value) != null
                    else -> false
                }
            }
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
        // Quantity + Quantity (with unit conversion)
        if (left is Quantity<*> && right is Quantity<*>) {
            @Suppress("UNCHECKED_CAST")
            return (left as Quantity<KiUnit>) + (right as Quantity<KiUnit>)
        }
        // Quantity + Number (scalar addition)
        if (left is Quantity<*> && right is Number) {
            return quantityScalarOp(left, right, "add")
        }
        if (left is String || right is String) {
            return stringify(left) + stringify(right)
        }
        if (left is List<*> && right is List<*>) {
            return left + right
        }
        return numericOp(left, right, "add")
    }

    private fun subtract(left: Any?, right: Any?): Any {
        // Quantity - Quantity (with unit conversion)
        if (left is Quantity<*> && right is Quantity<*>) {
            @Suppress("UNCHECKED_CAST")
            return (left as Quantity<KiUnit>) - (right as Quantity<KiUnit>)
        }
        // Quantity - Number (scalar subtraction)
        if (left is Quantity<*> && right is Number) {
            return quantityScalarOp(left, right, "subtract")
        }
        return numericOp(left, right, "subtract")
    }

    private fun multiply(left: Any?, right: Any?): Any {
        if (left is String && right is Int) {
            return left.repeat(right)
        }
        if (left is Int && right is String) {
            return right.repeat(left)
        }
        // Quantity * Number (scalar multiplication)
        if (left is Quantity<*> && right is Number) {
            return quantityScalarOp(left, right, "multiply")
        }
        // Number * Quantity (commutative)
        if (left is Number && right is Quantity<*>) {
            return quantityScalarOp(right, left, "multiply")
        }
        return numericOp(left, right, "multiply")
    }

    private fun divide(left: Any?, right: Any?): Any {
        // Quantity / Number (scalar division)
        if (left is Quantity<*> && right is Number) {
            return quantityScalarOp(left, right, "divide")
        }
        return numericOp(left, right, "divide")
    }

    private fun modulo(left: Any?, right: Any?): Any {
        // Quantity % Number (scalar modulo)
        if (left is Quantity<*> && right is Number) {
            return quantityScalarOp(left, right, "modulo")
        }
        return numericOp(left, right, "modulo")
    }

    private fun power(left: Any?, right: Any?): Any {
        val base = toDouble(left)
        val exp = toDouble(right)
        val result = Math.pow(base, exp)

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
            is Quantity<*> -> -value
            is Int -> -value
            is Long -> -value
            is Float -> -value
            is Double -> -value
            is Dec -> value.negate()
            else -> -toDouble(value)
        }
    }

    private fun numericOp(left: Any?, right: Any?, op: String): Any {
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

        // Integer division should truncate (like Kotlin, Java, Python //)
        if (op == "divide") {
            if (left is Int && right is Int) {
                if (right == 0) throw DivisionByZeroError()
                return left / right  // Kotlin Int / Int truncates toward zero
            }
            if (left is Long || right is Long) {
                val a = (left as Number).toLong()
                val b = (right as Number).toLong()
                if (b == 0L) throw DivisionByZeroError()
                return a / b
            }
        }

        val a = toDouble(left)
        val b = toDouble(right)

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

    /**
     * Perform a scalar arithmetic operation on a quantity.
     *
     * Delegates to the Quantity class operator overloads which handle
     * type-preserving arithmetic (Int value stays Int, etc.).
     *
     * @param quantity The quantity operand
     * @param scalar The numeric scalar operand
     * @param op The operation name: "add", "subtract", "multiply", "divide"
     */
    private fun quantityScalarOp(quantity: Quantity<*>, scalar: Number, op: String): Quantity<*> {
        return when (scalar) {
            is Int -> when (op) {
                "add" -> quantity + scalar
                "subtract" -> quantity - scalar
                "multiply" -> quantity * scalar
                "divide" -> quantity / scalar
                "modulo" -> quantity % scalar
                else -> throw RuntimeError("Unknown operation: $op")
            }
            is Long -> when (op) {
                "add" -> quantity + scalar
                "subtract" -> quantity - scalar
                "multiply" -> quantity * scalar
                "divide" -> quantity / scalar
                "modulo" -> quantity % scalar
                else -> throw RuntimeError("Unknown operation: $op")
            }
            is Float -> when (op) {
                "add" -> quantity + scalar
                "subtract" -> quantity - scalar
                "multiply" -> quantity * scalar
                "divide" -> quantity / scalar
                "modulo" -> quantity % scalar
                else -> throw RuntimeError("Unknown operation: $op")
            }
            is Double -> when (op) {
                "add" -> quantity + scalar
                "subtract" -> quantity - scalar
                "multiply" -> quantity * scalar
                "divide" -> quantity / scalar
                "modulo" -> quantity % scalar
                else -> throw RuntimeError("Unknown operation: $op")
            }
            is Dec -> when (op) {
                "add" -> quantity + scalar
                "subtract" -> quantity - scalar
                "multiply" -> quantity * scalar
                "divide" -> quantity / scalar
                "modulo" -> quantity % scalar
                else -> throw RuntimeError("Unknown operation: $op")
            }
            else -> throw TypeError("Unsupported scalar type for quantity operation: ${scalar::class.simpleName}")
        }
    }

    private fun compare(left: Any?, right: Any?): Int {
        if (left is String && right is String) return left.compareTo(right)
        if (left is Char && right is Char) return left.compareTo(right)
        if (left is KSEnumConstant && right is KSEnumConstant) {
            return left.ordinal.compareTo(right.ordinal)
        }
        // Quantity comparison (with unit conversion)
        if (left is Quantity<*> && right is Quantity<*>) {
            @Suppress("UNCHECKED_CAST")
            return (left as Quantity<KiUnit>).compareTo(right as Quantity<KiUnit>)
        }
        return toDouble(left).compareTo(toDouble(right))
    }

    // ========================================================================
    // Type Conversions
    // ========================================================================

    internal fun stringify(value: Any?): String {
        return when (value) {
            null -> "nil"
            is Quantity<*> -> value.toString()
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
            is KSEnumConstant -> value.name
            is KSObject -> value.toString()
            is KSStructInstance -> value.toString()
            is KDTag -> value.toString()
            is KDDocument -> value.toString()
            is KSType -> value.toString()
            else -> value.toString()
        }
    }

    private fun toDouble(value: Any?): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
                ?: throw TypeError("Cannot convert '$value' to number")
            is KSEnumConstant -> value.ordinal.toDouble()
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
            is KSEnumConstant -> value.ordinal
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
            is Quantity<*> -> true  // Quantities are always truthy
            is String -> value.isNotEmpty()
            is List<*> -> value.isNotEmpty()
            is Map<*, *> -> value.isNotEmpty()
            else -> true
        }
    }

    /**
     * If [value] is a struct instance, return a copy. Otherwise return as-is.
     * This is the single interception point for copy-on-assign semantics.
     */
    private fun copyIfStruct(value: Any?): Any? {
        return if (value is KSStructInstance) value.copy() else value
    }

    private fun isEqual(left: Any?, right: Any?): Boolean {
        if (left == null && right == null) return true
        if (left == null || right == null) return false

        if (left is Number && right is Number) {
            return left.toDouble() == right.toDouble()
        }

        // Quantity equality (with unit conversion)
        if (left is Quantity<*> && right is Quantity<*>) {
            @Suppress("UNCHECKED_CAST")
            return (left as Quantity<KiUnit>).compareTo(right as Quantity<KiUnit>) == 0
        }

        if (left is KSEnumConstant && right is KSEnumConstant) {
            return left.enum == right.enum && left.name == right.name
        }

        // Struct instances use structural equality (via KSStructInstance.equals)
        if (left is KSStructInstance && right is KSStructInstance) {
            return left == right
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
            is KSEnum -> value.constants.values
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
            "first" -> if (str.isNotEmpty()) str.first() else throw IndexOutOfBoundsError(0, 0, location)
            "last" -> if (str.isNotEmpty()) str.last() else throw IndexOutOfBoundsError(0, 0, location)
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

    private fun getEnumConstantMember(constant: KSEnumConstant, member: String, location: SourceLocation?): Any? {
        return when (member) {
            "name" -> constant.name
            "ordinal" -> constant.ordinal
            else -> {
                // Try to get from constructor args by parameter name
                val params = constant.enum.declaration.constructorParams
                for ((index, param) in params.withIndex()) {
                    if (param.name == member && index < constant.args.size) {
                        return constant.args[index]
                    }
                }
                throw MemberNotFoundError(member, constant.enum.name, location)
            }
        }
    }

    /**
     * Access members on a Quantity value.
     *
     * Supported members:
     * - `value` → the numeric value (Int, Long, Float, Double, or Dec)
     * - `unit` → the unit symbol as a String (e.g., "cm", "kg", "USD")
     */
    private fun getQuantityMember(quantity: Quantity<*>, member: String, location: SourceLocation?): Any? {
        return when (member) {
            "value" -> quantity.value
            "unit" -> quantity.unit.symbol
            else -> throw MemberNotFoundError(member, "Quantity", location)
        }
    }

    private fun getKDTagMember(tag: KDTag, member: String, location: SourceLocation?): Any? {
        return when (member) {
            "name" -> tag.name
            "namespace" -> tag.namespace
            "values" -> tag.values
            "attributes" -> tag.attributes
            "annotations" -> tag.annotations
            "children" -> tag.children
            else -> {
                // Try attribute lookup
                tag.attributes[member] ?: throw MemberNotFoundError(member, "KDTag", location)
            }
        }
    }

    private fun getKDDocumentMember(doc: KDDocument, member: String, location: SourceLocation?): Any? {
        return when (member) {
            "tags" -> doc.tags
            "size" -> doc.tags.size
            else -> {
                // Try finding a root tag by name
                doc.tags.firstOrNull { it.name == member }
                    ?: throw MemberNotFoundError(member, "KDDocument", location)
            }
        }
    }
}

// ============================================================================
// KSRange - Simple Range Implementation
// ============================================================================

/**
 * Runtime representation of a KS range.
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

// ============================================================================
// KSEnum - Enum Runtime Representation
// ============================================================================

/**
 * Runtime representation of a KS enum.
 */
class KSEnum(
    val declaration: EnumDecl,
    val closure: Environment
) {
    val name: String get() = declaration.name
    val constants = mutableMapOf<String, KSEnumConstant>()
    val staticMembers = closure.child("static:$name")

    fun addConstant(name: String, constant: KSEnumConstant) {
        constants[name] = constant
    }

    fun getConstant(name: String): KSEnumConstant? = constants[name]

    override fun toString(): String = "enum $name"
}

/**
 * Runtime representation of an enum constant.
 */
class KSEnumConstant(
    val enum: KSEnum,
    val name: String,
    val ordinal: Int,
    val args: List<Any?>
) {
    override fun toString(): String = "$name"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KSEnumConstant) return false
        return enum == other.enum && name == other.name
    }

    override fun hashCode(): Int = 31 * enum.hashCode() + name.hashCode()
}

// ============================================================================
// KSType - Type Representation for Reflection
// ============================================================================

/**
 * Runtime type representation for `::class` reflection.
 */
class KSType(
    val name: String,
    val qualifiedName: String? = null
) {
    override fun toString(): String = qualifiedName?.let { "$name($it)" } ?: name

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KSType) return false
        return name == other.name && qualifiedName == other.qualifiedName
    }

    override fun hashCode(): Int = 31 * name.hashCode() + (qualifiedName?.hashCode() ?: 0)
}

// ============================================================================
// KDTag - KD Tag Runtime Representation
// ============================================================================

/**
 * Runtime representation of a KD tag.
 *
 * Created from `lang KD { ... }` blocks.
 */
class KDTag(
    val name: String,
    val namespace: String?,
    val values: List<Any?>,
    val attributes: Map<String, Any?>,
    val annotations: List<KDAnnotation>,
    val children: List<KDTag>
) {
    override fun toString(): String {
        val sb = StringBuilder()

        // Annotations
        for (ann in annotations) {
            sb.append("@${ann.name}")
            if (ann.values.isNotEmpty() || ann.attributes.isNotEmpty()) {
                sb.append("(")
                sb.append(ann.values.joinToString(" "))
                if (ann.attributes.isNotEmpty()) {
                    if (ann.values.isNotEmpty()) sb.append(" ")
                    sb.append(ann.attributes.entries.joinToString(" ") { "${it.key}=${formatValue(it.value)}" })
                }
                sb.append(")")
            }
            sb.append(" ")
        }

        // Namespace and name
        if (namespace != null) {
            sb.append("$namespace:")
        }
        sb.append(name)

        // Values
        if (values.isNotEmpty()) {
            sb.append(" ")
            sb.append(values.joinToString(" ") { formatValue(it) })
        }

        // Attributes
        if (attributes.isNotEmpty()) {
            sb.append(" ")
            sb.append(attributes.entries.joinToString(" ") { "${it.key}=${formatValue(it.value)}" })
        }

        // Children
        if (children.isNotEmpty()) {
            sb.append(" {\n")
            for (child in children) {
                sb.append("    ")
                sb.append(child.toString().replace("\n", "\n    "))
                sb.append("\n")
            }
            sb.append("}")
        }

        return sb.toString()
    }

    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "nil"
            is String -> "\"$value\""
            is Char -> "'$value'"
            else -> value.toString()
        }
    }
}

/**
 * Runtime representation of a KD annotation.
 */
class KDAnnotation(
    val name: String,
    val values: List<Any?>,
    val attributes: Map<String, Any?>
)

/**
 * Runtime representation of a KD document with multiple root tags.
 *
 * Created from `lang KD { ... }` blocks that contain more than one
 * top-level tag. Provides indexed and named access to root tags.
 */
class KDDocument(val tags: List<KDTag>) {
    override fun toString(): String {
        return tags.joinToString("\n") { it.toString() }
    }
}