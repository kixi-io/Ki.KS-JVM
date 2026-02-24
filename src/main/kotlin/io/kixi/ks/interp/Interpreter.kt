package io.kixi.ks.interp

import io.kixi.Range
import io.kixi.Version
import io.kixi.Grid
import io.kixi.Coordinate
import io.kixi.Blob
import io.kixi.NSID
import io.kixi.Call
import io.kixi.Email
import io.kixi.GeoPoint
import io.kixi.kd.Tag as KiTag
import io.kixi.ks.*
import io.kixi.ks.ext.toList as rangeToList
import io.kixi.ks.ext.asSequence as rangeAsSequence
import io.kixi.ks.ext.count as rangeCount
import io.kixi.ks.lexer.*
import io.kixi.ks.parser.*
import io.kixi.uom.Currency
import io.kixi.uom.Quantity
import io.kixi.uom.Unit as KiUnit
import io.kixi.uom.combineUnits

import java.math.BigDecimal as Dec
import java.math.RoundingMode
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

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

    /** Native Ki.Core type registry \u2014 lazy-loaded constructors and static methods. */
    private val nativeTypes = NativeTypeRegistry()

    /** Built-in type sentinels for reflective access (e.g. String.type). */
    private val builtinTypes = mapOf(
        "String"   to KSBuiltinType("String"),
        "Int"      to KSBuiltinType("Int"),
        "Long"     to KSBuiltinType("Long"),
        "Float"    to KSBuiltinType("Float"),
        "Double"   to KSBuiltinType("Double"),
        "Dec"      to KSBuiltinType("Dec"),
        "Bool"     to KSBuiltinType("Bool"),
        "Char"     to KSBuiltinType("Char"),
        "List"     to KSBuiltinType("List"),
        "Map"      to KSBuiltinType("Map"),
        "Range"    to KSBuiltinType("Range"),
        "Regex"    to KSBuiltinType("Regex"),
        "Quantity" to KSBuiltinType("Quantity"),
        "Version"  to KSBuiltinType("Version"),
        "Grid"     to KSBuiltinType("Grid"),
        "Coordinate" to KSBuiltinType("Coordinate"),
        "Blob"     to KSBuiltinType("Blob"),
        "NSID"     to KSBuiltinType("NSID"),
        "Call"     to KSBuiltinType("Call"),
        "Tag"      to KSBuiltinType("Tag"),
        "Nil"      to KSBuiltinType("Nil"),
        "Any"      to KSBuiltinType("Any"),
        "Type"     to KSBuiltinType("Type"),
    )

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
     * This is the io.kixi.ks.main dispatch method that routes to specialized evaluators.
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

            // --- Expressions: Ki Literals ---
            is GridLiteralExpr -> evaluateGridLiteral(node)
            is CoordinateLiteralExpr -> evaluateCoordinateLiteral(node)

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

        // Check null safety: reject nil for non-nullable types
        checkNullSafety(decl.name, value, decl.typeAnnotation, decl.location)
        checkTypeCompatibility(decl.name, value, decl.typeAnnotation, decl.location)

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

            // Check null safety for non-nullable parameter types
            checkNullSafety(param.name, value, param.type, location)
            checkTypeCompatibility(param.name, value, param.type, location)

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
            checkNullSafety(param.name, value, param.type, location)
            checkTypeCompatibility(param.name, value, param.type, location)

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

        // Try as a built-in type (String, Int, Bool, etc.)
        // Native type constructors take precedence over plain sentinels —
        // they provide both constructor calls and static member access.
        nativeTypes[name]?.let { return it }
        builtinTypes[name]?.let { return it }

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

        // Warn if class declares members that shadow reserved reflection properties
        warnReservedMemberNames(decl.name, decl.members, decl.constructorParams, decl.location)

        // Validate trait conformance
        val missingMethods = mutableListOf<String>()
        for (trait in ksClass.traits) {
            for (methodName in trait.abstractMethodNames()) {
                val found = ksClass.findMethod(methodName)
                if (found == null || found.declaration.body == null) {
                    missingMethods.add("${trait.name}.${methodName}")
                }
            }
        }
        if (missingMethods.isNotEmpty()) {
            throw RuntimeError(
                "Class '${ksClass.name}' does not implement required trait methods: ${missingMethods.joinToString(", ")}",
                ksClass.location
            )
        }

        // Validate trait conformance: every abstract method must be implemented
        // (by the class's own methods or inherited from superclass)
        validateClassTraitConformance(ksClass)

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
                                    // Check null safety for non-nullable static variable types
                                    checkNullSafety(staticMember.name, value, staticMember.typeAnnotation, staticMember.location)
                                    checkTypeCompatibility(staticMember.name, value, staticMember.typeAnnotation, staticMember.location)
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

            // Check null safety for non-nullable constructor parameter types
            checkNullSafety(param.name, value, param.type, location)
            checkTypeCompatibility(param.name, value, param.type, location)

            // Check constraint
            if (param.constraint != null && value != null) {
                checkConstraint(param.name, value, param.constraint, location)
            }

            // If param has binding (var/let), create a property
            if (param.binding != null) {
                obj.initProperty(param.name, value, param.binding == BindingType.VAR)
            }
        }

        // Initialize instance properties from class body (including superclasses)
        val previousEnv = environment
        val instanceEnv = environment.child("instance:${ksClass.name}")
        instanceEnv.define("this", obj, mutable = false, location = location)
        environment = instanceEnv

        try {
            // Initialize superclass body properties first (walk up chain)
            initSuperclassBodyProperties(ksClass.superclass, obj)

            // Then current class body properties
            for (property in ksClass.getInstanceProperties()) {
                val value = property.initializer?.let { evaluate(it) }
                // Check null safety for non-nullable property types
                checkNullSafety(property.name, value, property.typeAnnotation, property.location)
                checkTypeCompatibility(property.name, value, property.typeAnnotation, property.location)
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

    /**
     * Recursively initialize body-declared instance properties from superclasses.
     *
     * Walks the superclass chain from the root ancestor downward, initializing
     * each superclass's body-declared `var`/`let` properties on the instance.
     * This ensures that a subclass inherits its parent's instance properties.
     *
     * Properties are only initialized if they haven't already been set (this
     * prevents a grandparent property from being overwritten by repeated init).
     *
     * Note: Constructor parameters with `var`/`let` binding are handled separately
     * during constructor argument processing. This method only handles properties
     * declared in the class body (e.g., `var greeting = "Hello"`).
     *
     * @param superclass The superclass to process, or null to stop recursion
     * @param obj The instance being constructed
     */
    private fun initSuperclassBodyProperties(superclass: KSClass?, obj: KSObject) {
        if (superclass == null) return

        // Process grandparent first (so properties initialize top-down)
        initSuperclassBodyProperties(superclass.superclass, obj)

        // Initialize this superclass's body-declared properties
        for (property in superclass.getInstanceProperties()) {
            if (!obj.hasProperty(property.name)) {
                val value = property.initializer?.let { evaluate(it) }
                // Check null safety for non-nullable property types
                checkNullSafety(property.name, value, property.typeAnnotation, property.location)
                checkTypeCompatibility(property.name, value, property.typeAnnotation, property.location)
                if (property.constraint != null && value != null) {
                    checkConstraint(property.name, value, property.constraint, property.location)
                }
                obj.initProperty(property.name, value, property.mutable)
            }
        }
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

        // Warn if struct declares members that shadow reserved reflection properties
        warnReservedMemberNames(decl.name, decl.members, decl.constructorParams, decl.location)

        // Validate trait conformance: every abstract method must be implemented
        validateStructTraitConformance(ksStruct)

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
                                    // Check null safety for non-nullable static variable types
                                    checkNullSafety(staticMember.name, value, staticMember.typeAnnotation, staticMember.location)
                                    checkTypeCompatibility(staticMember.name, value, staticMember.typeAnnotation, staticMember.location)
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
     * Validate that a class implements all abstract methods required by its traits.
     *
     * Checks the class's own methods and methods inherited from the superclass chain.
     * A concrete method (one with a body) from ANY point in the superclass chain
     * satisfies the trait requirement. Default implementations in the trait itself
     * also satisfy the requirement.
     *
     * Mirrors [validateStructTraitConformance] for classes.
     */
    private fun validateClassTraitConformance(ksClass: KSClass) {
        val missingMethods = mutableListOf<String>()

        for (trait in ksClass.traits) {
            for (methodName in trait.abstractMethodNames()) {
                val found = ksClass.findMethod(methodName)
                if (found == null || found.declaration.body == null) {
                    missingMethods.add("${trait.name}.${methodName}")
                }
            }
        }

        if (missingMethods.isNotEmpty()) {
            throw RuntimeError(
                "Class '${ksClass.name}' does not implement required trait methods: ${missingMethods.joinToString(", ")}",
                ksClass.location
            )
        }
    }

    /**
     * Validate that a struct implements all abstract methods required by its traits.
     *
     * An abstract method is one with no body in the trait. If a trait provides a
     * default implementation (body present), the struct is not required to override it.
     */
    private fun validateStructTraitConformance(ksStruct: KSStruct) {
        val missingMethods = mutableListOf<String>()

        for (trait in ksStruct.traits) {
            for (methodName in trait.abstractMethodNames()) {
                // findMethod checks struct's own methods first, then falls back to traits.
                // We need to verify the struct provides a concrete implementation —
                // not just that the trait's own abstract declaration is reachable.
                val found = ksStruct.findMethod(methodName)
                if (found == null || found.declaration.body == null) {
                    missingMethods.add("${trait.name}.${methodName}")
                }
            }
        }

        if (missingMethods.isNotEmpty()) {
            throw RuntimeError(
                "Struct '${ksStruct.name}' does not implement required trait methods: ${missingMethods.joinToString(", ")}",
                ksStruct.location
            )
        }
    }

    /**
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

            // Check null safety for non-nullable constructor parameter types
            checkNullSafety(param.name, value, param.type, location)
            checkTypeCompatibility(param.name, value, param.type, location)

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
                // Check null safety for non-nullable property types
                checkNullSafety(property.name, value, property.typeAnnotation, property.location)
                checkTypeCompatibility(property.name, value, property.typeAnnotation, property.location)
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

            // Check null safety for non-nullable parameter types
            checkNullSafety(param.name, value, param.type, location)
            checkTypeCompatibility(param.name, value, param.type, location)

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
        val targetName = decl.target.name

        if (decl.isTraitExtension) {
            // extend trait Foo { fun bar() = ... }
            val trait = traits[targetName]
                ?: throw RuntimeError("Cannot extend unknown trait '$targetName'", decl.location)

            for (member in decl.members) {
                when (member) {
                    is FunDecl -> {
                        val fn = KSFunction(member, environment)
                        trait.addMethod(member.name, fn)
                    }
                    else -> throw RuntimeError(
                        "Trait extensions can only contain methods",
                        decl.location
                    )
                }
            }
        } else {
            // extend Point { fun distance() = ... }
            // Resolve target: class, struct, or trait
            val ksClass = classes[targetName]
            val ksStruct = structs[targetName]

            if (ksClass == null && ksStruct == null) {
                throw RuntimeError("Cannot extend unknown type '$targetName'", decl.location)
            }

            for (member in decl.members) {
                when (member) {
                    is FunDecl -> {
                        val fn = KSFunction(member, environment)
                        if (ksClass != null) {
                            ksClass.addMethod(member.name, fn)
                        } else {
                            ksStruct!!.addMethod(member.name, fn)
                        }
                    }
                    is VarDecl -> throw RuntimeError(
                        "Extension properties are not yet supported. Use extension methods instead.",
                        member.location
                    )
                    else -> throw RuntimeError(
                        "Unsupported member in extension block",
                        decl.location
                    )
                }
            }
        }

        if (runtime.debugMode) {
            val kind = if (decl.isTraitExtension) "trait " else ""
            runtime.outputWriter.println("[DEBUG] extend $kind$targetName with ${decl.members.size} members")
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
            is Version -> KSType("Version")
            is List<*> -> KSType("List")
            is Map<*, *> -> KSType("Map")
            is KSObject -> KSType(value.klass.name)
            is KSStructInstance -> KSType(value.struct.name)
            is KSEnumConstant -> KSType(value.enum.name)
            is KSClass -> KSType("class ${value.name}")
            is KSStruct -> KSType("struct ${value.name}")
            is KSTrait -> KSType("trait ${value.name}")
            is KSEnum -> KSType("enum ${value.name}")
            is KSBuiltinType -> KSType("${value.kind} ${value.name}")
            is KSFunction -> KSType("fun ${value.name}")
            is NativeCallable -> KSType("fun ${value.name}")
            is BoundMethod -> KSType("fun ${value.method.name}")
            is StructBoundMethod -> KSType("fun ${value.method.name}")
            is KSFunctionCallable -> KSType("fun ${value.function.name}")
            is Range<*> -> KSType("Range")
            is Grid<*> -> KSType("Grid")
            is Coordinate -> KSType("Coordinate")
            is Blob -> KSType("Blob")
            is NSID -> KSType("NSID")
            is KiTag -> KSType("Tag")
            is Call -> KSType("Call")
            is Currency -> KSType("Currency")
            is Email -> KSType("Email")
            is GeoPoint -> KSType("GeoPoint")
            is Regex -> KSType("Regex")
            is MatchResult -> KSType("MatchResult")
            is KDTag -> KSType("KDTag")
            is KDDocument -> KSType("KDDocument")
            is NativeTypeConstructor -> KSType("class ${value.typeName}")
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
            LiteralKind.VERSION -> parseVersionLiteral(expr.value as String, expr.location)
            LiteralKind.URL -> {
                val text = expr.value as String
                try { URL(text) } catch (e: Exception) {
                    throw RuntimeError("Invalid URL literal '$text': ${e.message}", expr.location)
                }
            }
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

    /**
     * Parse a KS version literal into a [Version] object.
     *
     * KS uses underscores for qualifier separation (to avoid ambiguity with the
     * minus operator), while [Version.parse] uses dashes internally.
     *
     * Conversion examples:
     *   "5.0.0"         → "5.0.0"         → Version(5, 0, 0)
     *   "5.2.7"         → "5.2.7"         → Version(5, 2, 7)
     *   "0.2.0_beta"    → "0.2.0-beta"    → Version(0, 2, 0, "beta")
     *   "0.2.0_beta_1"  → "0.2.0-beta-1"  → Version(0, 2, 0, "beta", 1)
     *   "1_000.0.0_rc"  → "1000.0.0-rc"   → Version(1000, 0, 0, "rc")
     */
    private fun parseVersionLiteral(text: String, location: SourceLocation?): Version {
        // Find qualifier boundary: first '_' followed by a letter
        var qualStart = -1
        for (i in text.indices) {
            if (text[i] == '_' && i + 1 < text.length && text[i + 1].isLetter()) {
                qualStart = i
                break
            }
        }

        val versionText = if (qualStart >= 0) {
            // Numeric part: remove digit-separator underscores
            val numericPart = text.substring(0, qualStart).replace("_", "")
            // Qualifier part: convert underscores to dashes for Version.parse
            val qualifierPart = text.substring(qualStart + 1).replace("_", "-")
            "$numericPart-$qualifierPart"
        } else {
            // No qualifier — just remove digit-separator underscores
            text.replace("_", "")
        }

        return try {
            Version.parse(versionText)
        } catch (e: Exception) {
            throw RuntimeError("Invalid version literal '$text': ${e.message}", location)
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

    private fun evaluateList(expr: ListExpr): MutableList<Any?> {
        return expr.elements.map { evaluate(it) }.toMutableList()
    }

    private fun evaluateMap(expr: MapExpr): MutableMap<Any?, Any?> {
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
        val bound = when {
            expr.startExclusive && expr.endExclusive -> Range.Bound.Exclusive
            expr.startExclusive -> Range.Bound.ExclusiveStart
            expr.endExclusive -> Range.Bound.ExclusiveEnd
            else -> Range.Bound.Inclusive
        }
        return Range(start, end, bound)
    }

    // ========================================================================
    // Grid & Coordinate Literals
    // ========================================================================

    /**
     * Evaluate a grid literal expression.
     *
     * Creates a [Grid] from the parsed rows of values. All rows must have
     * the same number of values; otherwise a RuntimeError is thrown.
     *
     * Untyped grids use `Grid.ofNulls<Any?>` and can contain mixed types.
     * Typed grids validate that all non-null values conform to the type parameter.
     *
     *     .grid(1 2 3; 4 5 6)             → Grid<Any?>(3, 2)
     *     .grid<Int>(10 20 30; 40 50 60)  → Grid<Any?>(3, 2) with Int validation
     */
    private fun evaluateGridLiteral(expr: GridLiteralExpr): Any {
        // Resolve element type from type parameter if specified
        val elementType: Class<*>? = expr.typeParam?.let { resolveGridElementType(it, expr.location) }
        val typeParamNullable = expr.typeParam?.nullable ?: false

        // --- Empty grid ---
        if (expr.rows.isEmpty()) {
            // For Any / Any? typed grids, store elementType as null so the type isn't displayed
            val storedType = if (elementType == Any::class.java) null else elementType
            val nullable = if (storedType == null && elementType == null) true else typeParamNullable
            return Grid<Any?>(0, 0, emptyArray(), storedType, nullable)
        }

        // --- Non-empty grid ---

        // Evaluate all values
        val evaluatedRows = expr.rows.map { row ->
            row.map { evaluate(it) }
        }

        // Validate all rows have the same width
        val width = evaluatedRows[0].size
        for ((i, row) in evaluatedRows.withIndex()) {
            if (row.size != width) {
                throw RuntimeError(
                    "Grid row ${i + 1} has ${row.size} values, expected $width " +
                            "(all rows must have the same number of values)",
                    expr.location
                )
            }
        }

        val height = evaluatedRows.size

        // Populate the flat data array
        val data = Array<Any?>(width * height) { null }
        var hasNull = false

        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = evaluatedRows[y][x]
                data[y * width + x] = value
                if (value == null) hasNull = true
            }
        }

        // Type validation for typed grids (skip for Any)
        if (elementType != null && elementType != Any::class.java) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val value = data[y * width + x]
                    if (value != null && !isGridValueCompatible(value, elementType)) {
                        val actualType = runtimeTypeName(value) ?: value.javaClass.simpleName
                        val expectedType = gridElementTypeName(elementType)
                        throw RuntimeError(
                            "Grid<$expectedType> cannot contain value of type $actualType at [$x, $y]",
                            expr.location
                        )
                    }
                    if (value == null && !typeParamNullable) {
                        val expectedType = gridElementTypeName(elementType)
                        throw RuntimeError(
                            "Grid<$expectedType> cannot contain nil values (use Grid<$expectedType?> for nullable)",
                            expr.location
                        )
                    }
                }
            }
        }

        // For Any / Any? typed grids, store elementType as null so the type isn't displayed
        val storedType = if (elementType == Any::class.java) null else elementType
        val nullable = hasNull || typeParamNullable

        return Grid<Any?>(width, height, data, storedType, nullable)
    }

    /**
     * Resolve a grid type parameter name to a JVM class.
     *
     * Maps KS type names to their native JVM classes since KS uses
     * native types directly (no wrapping).
     */
    private fun resolveGridElementType(typeRef: TypeRef, location: SourceLocation?): Class<*> {
        return when (typeRef.name) {
            "Int" -> Int::class.javaObjectType
            "Long" -> Long::class.javaObjectType
            "Float" -> Float::class.javaObjectType
            "Double" -> Double::class.javaObjectType
            "Dec" -> java.math.BigDecimal::class.java
            "String" -> String::class.java
            "Bool" -> Boolean::class.javaObjectType
            "Char" -> Char::class.javaObjectType
            "Number" -> Number::class.java
            "Any" -> Any::class.java
            else -> throw RuntimeError(
                "Unknown grid element type '${typeRef.name}'",
                location,
                suggestion = "Supported types: Int, Long, Float, Double, Dec, String, Bool, Char, Number, Any"
            )
        }
    }

    /**
     * Check if a runtime value is compatible with the expected grid element type.
     */
    private fun isGridValueCompatible(value: Any, expectedType: Class<*>): Boolean {
        if (expectedType == Any::class.java) return true
        if (expectedType == Number::class.java) return value is Number
        return expectedType.isInstance(value)
    }

    /**
     * Map a JVM class back to a KS type name for error messages.
     */
    private fun gridElementTypeName(type: Class<*>): String = when (type) {
        Int::class.java, java.lang.Integer::class.java -> "Int"
        Long::class.java, java.lang.Long::class.java -> "Long"
        Float::class.java, java.lang.Float::class.java -> "Float"
        Double::class.java, java.lang.Double::class.java -> "Double"
        java.math.BigDecimal::class.java -> "Dec"
        String::class.java -> "String"
        Boolean::class.java, java.lang.Boolean::class.java -> "Bool"
        Char::class.java, java.lang.Character::class.java -> "Char"
        Number::class.java -> "Number"
        Any::class.java -> "Any"
        else -> type.simpleName
    }

    /**
     * Evaluate a coordinate literal expression.
     *
     * Supports two addressing styles:
     *
     * Standard notation (zero-based):
     *     .coordinate(x=0, y=0)
     *     .coordinate(x=4, y=7)
     *     .coordinate(x=0, y=0, z=5)
     *
     * Sheet notation (letter column, one-based row):
     *     .coordinate(c="A", r=1)
     *     .coordinate(c="E", r=8)
     *     .coordinate(c="AA", r=100, z=5)
     *
     * The notation is detected by checking whether the arguments contain
     * "x"/"y" (standard) or "c"/"r" (sheet).
     */
    private fun evaluateCoordinateLiteral(expr: CoordinateLiteralExpr): Any {
        val argMap = mutableMapOf<String, Any?>()
        for (arg in expr.arguments) {
            val name = arg.name ?: throw RuntimeError(
                "Coordinate arguments must be named (e.g., x=0, y=0 or c=\"A\", r=1)",
                expr.location
            )
            argMap[name] = evaluate(arg.value)
        }

        val z = argMap["z"]?.let { toInt(it, expr.location) }

        return when {
            // Standard notation: x, y (, z)
            "x" in argMap && "y" in argMap -> {
                val x = toInt(argMap["x"]!!, expr.location)
                val y = toInt(argMap["y"]!!, expr.location)
                if (z != null) Coordinate.standard(x, y, z)
                else Coordinate.standard(x, y)
            }

            // Sheet notation: c, r (, z)
            "c" in argMap && "r" in argMap -> {
                val c = argMap["c"]?.toString()
                    ?: throw RuntimeError("Coordinate column 'c' cannot be nil", expr.location)
                val r = toInt(argMap["r"]!!, expr.location)
                if (z != null) Coordinate.sheet(c, r, z)
                else Coordinate.sheet(c, r)
            }

            else -> throw RuntimeError(
                "Coordinate requires either (x=, y=) for standard or (c=, r=) for sheet notation",
                expr.location
            )
        }
    }

    // ========================================================================
    // Member & Index Access
    // ========================================================================

    private fun evaluateMemberAccess(expr: MemberAccessExpr): Any? {
        val obj = evaluate(expr.obj)

        if (expr.safe && obj == null) {
            return null
        }

        // Universal reflection properties: .type and .typeName
        // These are reserved — always resolved here, never shadowed by
        // user-defined properties. Must be checked BEFORE the nil guard
        // so that `nil.type` returns KSType("Nil") rather than throwing.
        if (expr.member == "type") return getKSType(obj)
        if (expr.member == "typeName") return getKSType(obj).name

        // Universal reflection: .members — available on type definitions only
        // (class, struct, trait, enum). Returns a String describing the type's
        // API surface: constructors, properties, methods, extensions, enums, static.
        if (expr.member == "members") {
            return when (obj) {
                is KSClass -> MembersFormatter.formatClass(obj)
                is KSStruct -> MembersFormatter.formatStruct(obj)
                is KSTrait -> MembersFormatter.formatTrait(obj)
                is KSEnum -> MembersFormatter.formatEnum(obj)
                null -> throw NullPointerError("Cannot access .members on nil", expr.location)
                else -> throw RuntimeError(
                    ".members is only available on class, struct, trait, and enum definitions",
                    expr.location
                )
            }
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
            is KSBuiltinType -> throw MemberNotFoundError(expr.member, obj.name, expr.location)
            is NativeTypeConstructor -> obj.getStatic(expr.member)
                ?: throw MemberNotFoundError(expr.member, obj.typeName, expr.location)
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
            is Range<*> -> getRangeMember(obj, expr.member, expr.location)
            is Quantity<*> -> getQuantityMember(obj, expr.member, expr.location)
            is Version -> getVersionMember(obj, expr.member, expr.location)
            is Grid<*> -> getGridMember(obj, expr.member, expr.location)
            is Coordinate -> getCoordinateMember(obj, expr.member, expr.location)
            is Currency -> nativeTypes.getCurrencyMember(obj, expr.member, expr.location)
            is Email -> nativeTypes.getEmailMember(obj, expr.member, expr.location)
            is GeoPoint -> nativeTypes.getGeoPointMember(obj, expr.member, expr.location)
            is KDTag -> getKDTagMember(obj, expr.member, expr.location)
            is KDDocument -> getKDDocumentMember(obj, expr.member, expr.location)
            is Regex -> getRegexMember(obj, expr.member, expr.location)
            is MatchResult -> getMatchResultMember(obj, expr.member, expr.location)
            is Blob -> nativeTypes.getBlobMember(obj, expr.member, expr.location)
            is NSID -> nativeTypes.getNSIDMember(obj, expr.member, expr.location)
            is KiTag -> nativeTypes.getTagMember(obj, expr.member, expr.location)
            is Call -> nativeTypes.getCallMember(obj, expr.member, expr.location)
            else -> throw MemberNotFoundError(expr.member, obj::class.simpleName ?: "Unknown", expr.location)
        }
    }

    private fun evaluateIndex(expr: IndexExpr): Any? {
        val obj = evaluate(expr.obj)
        val indices = expr.indices.map { evaluate(it) }

        // Multi-index: dispatch to get() method or built-in Grid access
        if (indices.size > 1) {
            return when (obj) {
                is Grid<*> -> {
                    if (indices.size != 2) {
                        throw RuntimeError(
                            "Grid access requires exactly 2 indices (x, y), got ${indices.size}",
                            expr.location
                        )
                    }
                    val x = toInt(indices[0]!!, expr.location)
                    val y = toInt(indices[1]!!, expr.location)
                    if (x < 0 || x >= obj.width || y < 0 || y >= obj.height) {
                        throw RuntimeError(
                            "Grid index [$x, $y] out of bounds for ${obj.width}\u00d7${obj.height} grid",
                            expr.location
                        )
                    }
                    obj[x, y]
                }
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
            is Grid<*> -> {
                // Grid single-index access with Coordinate or sheet notation string
                when (index) {
                    is Coordinate -> {
                        val x = index.x
                        val y = index.y
                        if (x < 0 || x >= obj.width || y < 0 || y >= obj.height) {
                            throw RuntimeError(
                                "Grid index [$x, $y] out of bounds for ${obj.width}\u00d7${obj.height} grid",
                                expr.location
                            )
                        }
                        obj[x, y]
                    }
                    is String -> {
                        // Sheet notation: grid["A1"], grid["B3"]
                        try {
                            obj[index]
                        } catch (e: Exception) {
                            throw RuntimeError("Invalid grid reference '$index': ${e.message}", expr.location)
                        }
                    }
                    else -> throw TypeError(
                        "Grid single-index access requires a Coordinate or sheet notation String, got ${index?.let { runtimeTypeName(it) ?: it.javaClass.simpleName } ?: "nil"}",
                        expr.location
                    )
                }
            }
            is Blob -> {
                val i = toInt(index, expr.indices[0].location)
                if (i < 0 || i >= obj.size) {
                    throw IndexOutOfBoundsError(i, obj.size, expr.location)
                }
                obj[i].toInt()  // return as Int for KS convenience
            }
            is Call -> {
                // Call[int] -> values[int], Call[string] -> attributes[string]
                when (index) {
                    is Int -> {
                        if (!obj.hasValue(index)) {
                            throw IndexOutOfBoundsError(index, obj.valueCount, expr.location)
                        }
                        obj[index]
                    }
                    is String -> obj[index]
                    else -> throw TypeError("Call index must be Int or String", expr.location)
                }
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
            is MatchResult -> {
                when (index) {
                    is Int -> {
                        if (index < 0 || index >= obj.groupValues.size) {
                            throw IndexOutOfBoundsError(index, obj.groupValues.size, expr.location)
                        }
                        obj.groupValues[index]
                    }
                    is String -> obj.groups[index]?.value
                    else -> throw TypeError("MatchResult index must be Int or String", expr.location)
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

                    // Check null safety: reject nil for non-nullable typed variables
                    checkNullSafety(target.name, newValue, environment.getType(target.name), expr.location)
                    checkTypeCompatibility(target.name, newValue, environment.getType(target.name), expr.location)

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
                // (except for Grid which supports [x, y] = value)
                if (obj is Grid<*>) {
                    if (indices.size != 2) {
                        throw RuntimeError(
                            "Grid assignment requires exactly 2 indices (x, y), got ${indices.size}",
                            target.location
                        )
                    }
                    val x = toInt(indices[0]!!, target.location)
                    val y = toInt(indices[1]!!, target.location)
                    if (x < 0 || x >= obj.width || y < 0 || y >= obj.height) {
                        throw RuntimeError(
                            "Grid index [$x, $y] out of bounds for ${obj.width}\u00d7${obj.height} grid",
                            target.location
                        )
                    }
                    @Suppress("UNCHECKED_CAST")
                    val grid = obj as Grid<Any?>
                    val newValue = computeAssignment(expr.operator, { grid[x, y] }, value)
                    grid[x, y] = newValue
                    return newValue
                }

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
                    is Grid<*> -> {
                        // Grid single-index assignment with Coordinate: grid[coord] = value
                        if (index is Coordinate) {
                            val x = index.x
                            val y = index.y
                            if (x < 0 || x >= obj.width || y < 0 || y >= obj.height) {
                                throw RuntimeError(
                                    "Grid index [$x, $y] out of bounds for ${obj.width}\u00d7${obj.height} grid",
                                    target.location
                                )
                            }
                            @Suppress("UNCHECKED_CAST")
                            val grid = obj as Grid<Any?>
                            val newValue = computeAssignment(expr.operator, { grid[x, y] }, value)
                            grid[x, y] = newValue
                            return newValue
                        } else {
                            throw TypeError(
                                "Grid single-index assignment requires a Coordinate, got ${index?.let { runtimeTypeName(it) ?: it.javaClass.simpleName } ?: "nil"}",
                                target.location
                            )
                        }
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
            AssignOp.PLUS_ASSIGN -> {
                val current = getCurrent()
                if (current is MutableList<*>) {
                    // In-place mutation: list += element or list += otherList
                    @Suppress("UNCHECKED_CAST")
                    val list = current as MutableList<Any?>
                    if (newValue is List<*>) list.addAll(newValue) else list.add(newValue)
                    current
                } else {
                    add(current, newValue)
                }
            }
            AssignOp.MINUS_ASSIGN -> {
                val current = getCurrent()
                if (current is MutableList<*>) {
                    // In-place mutation: list -= element (remove first occurrence)
                    @Suppress("UNCHECKED_CAST")
                    (current as MutableList<Any?>).remove(newValue)
                    current
                } else {
                    subtract(current, newValue)
                }
            }
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
            "Range" -> value is Range<*>
            "Grid" -> value is Grid<*>
            "Coordinate" -> value is Coordinate
            "Blob" -> value is Blob
            "NSID" -> value is NSID
            "Call" -> value is Call  // includes Tag (Tag extends Call)
            "Tag" -> value is KiTag
            "Currency" -> value is Currency
            "Email" -> value is Email
            "GeoPoint" -> value is GeoPoint
            "Version" -> value is Version
            "Regex" -> value is Regex
            "MatchResult" -> value is MatchResult
            "Any" -> true
            else -> {
                // Check user-defined types
                when (value) {
                    is KSObject -> {
                        // Direct class name match or subclass
                        if (value.klass.name == type.name) return true
                        classes[type.name]?.let { return value.klass.isSubclassOf(it) }
                        // Trait conformance check
                        val trait = traits[type.name]
                        if (trait != null) return value.klass.implementsTrait(trait)
                        false
                    }
                    is KSStructInstance -> {
                        // Direct struct name match
                        if (value.struct.name == type.name) return true
                        // Trait conformance check
                        val trait = traits[type.name]
                        if (trait != null) return value.struct.implementsTrait(trait)
                        false
                    }
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
            is Range<*> -> {
                @Suppress("UNCHECKED_CAST")
                (container as Range<Any>).contains(value as Any)
            }
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
    // Null Safety Checking
    // ========================================================================

    /**
     * Enforce null safety: reject nil values for non-nullable types.
     *
     * Called during variable declarations, assignments, parameter binding,
     * and constructor parameter binding. If the value is null and the type
     * annotation is non-nullable (e.g. `String` rather than `String?`),
     * a TypeError is thrown.
     *
     * Respects [KSRuntime.strictNullSafety]. When disabled, null assignments
     * to non-nullable types silently succeed (useful for migration/testing).
     *
     * @param name   The variable/parameter name (for error messages)
     * @param value  The value being assigned
     * @param type   The declared type (null means no annotation — skip check)
     * @param location Source location for error reporting
     */
    private fun checkNullSafety(name: String, value: Any?, type: TypeRef?, location: SourceLocation?) {
        if (!runtime.strictNullSafety) return
        if (type == null) return          // no type annotation — nothing to check
        if (type.nullable) return         // nullable type (String?) — nil is allowed
        if (value != null) return         // non-null value — always OK

        throw TypeError(
            "Nil cannot be assigned to non-nullable type '${type.name}'. " +
                    "Use '${type.name}?' to allow nil for '$name'",
            location
        )
    }

    /**
     * Check that a value is compatible with a declared type annotation.
     *
     * Enforces type safety for explicitly typed variables and parameters:
     *
     *     var thing: Int = 4
     *     thing = "apple"          // → TypeError
     *     thing = 42               // OK
     *
     * Skips the check when:
     *   - No type annotation (dynamic/untyped variable)
     *   - Value is null (handled separately by [checkNullSafety])
     *   - Type is `Any` or `Any?` (accepts all values)
     *
     * @param name Variable/parameter name for error messages
     * @param value The value being assigned
     * @param type The declared type annotation (null = no annotation)
     * @param location Source location for error reporting
     * @throws TypeError if value is incompatible with the declared type
     */
    private fun checkTypeCompatibility(name: String, value: Any?, type: TypeRef?, location: SourceLocation?) {
        if (type == null) return              // no annotation — dynamic, no check
        if (value == null) return             // null handled by checkNullSafety
        if (type.name == "Any") return        // Any accepts everything

        val actualType = runtimeTypeName(value)
        if (actualType == null) return        // unknown type — skip check

        if (!isTypeCompatible(actualType, type.name)) {
            throw TypeError(
                "Cannot assign ${actualType} value to '${name}' of type '${type.name}'",
                location
            )
        }
    }

    /**
     * Reserved member names that are universal reflection properties.
     * User-defined properties/methods with these names will be shadowed.
     */
    private val RESERVED_MEMBER_NAMES = setOf("type", "typeName", "members")

    /**
     * Emit warnings if a class or struct declares members with reserved names.
     * These names are used for universal reflection properties and will be
     * shadowed — user code will never be able to access them via dot syntax.
     */
    private fun warnReservedMemberNames(typeName: String, members: List<Node>, constructorParams: List<ConstructorParam>, location: SourceLocation) {
        val colorEnabled = runtime.colorOutput
        for (param in constructorParams) {
            if (param.name in RESERVED_MEMBER_NAMES && param.binding != null) {
                runtime.errorWriter.println(ANSI.warn(
                    "[${location.line}:${location.column}] Warning: '$typeName' declares property '${param.name}' which shadows the built-in .${param.name} reflection property",
                    colorEnabled
                ))
            }
        }
        for (member in members) {
            val memberName = when (member) {
                is FunDecl -> member.name
                is VarDecl -> member.name
                else -> null
            }
            if (memberName != null && memberName in RESERVED_MEMBER_NAMES) {
                runtime.errorWriter.println(ANSI.warn(
                    "[${location.line}:${location.column}] Warning: '$typeName' declares member '$memberName' which shadows the built-in .$memberName reflection property",
                    colorEnabled
                ))
            }
        }
    }

    /**
     * Get the KS type name for a runtime value.
     */
    private fun runtimeTypeName(value: Any): String? = when (value) {
        is Int -> "Int"
        is Long -> "Long"
        is Float -> "Float"
        is Double -> "Double"
        is java.math.BigDecimal -> "Dec"
        is String -> "String"
        is Char -> "Char"
        is Boolean -> "Bool"
        is List<*> -> "List"
        is Map<*, *> -> "Map"
        is KSObject -> value.klass.name
        is KSStructInstance -> value.struct.name
        is KSEnumConstant -> value.enum.name
        is io.kixi.uom.Quantity<*> -> "Quantity"
        is Version -> "Version"
        is Range<*> -> "Range"
        is Grid<*> -> "Grid"
        is Coordinate -> "Coordinate"
        is Blob -> "Blob"
        is NSID -> "NSID"
        is KiTag -> "Tag"
        is Call -> "Call"
        is Currency -> "Currency"
        is Email -> "Email"
        is GeoPoint -> "GeoPoint"
        is Regex -> "Regex"
        is MatchResult -> "MatchResult"
        is KSFunction -> "Function"
        is NativeCallable -> "Function"
        is NativeTypeConstructor -> "Type"
        else -> null
    }

    /**
     * Check if a runtime type is compatible with a declared type name.
     *
     * Handles exact matches and numeric widening (Int → Long → Double, etc.).
     * Class/trait subtype relationships are checked via the runtime class hierarchy.
     */
    private fun isTypeCompatible(actualType: String, declaredType: String): Boolean {
        // Exact match
        if (actualType == declaredType) return true

        // Numeric widening: Int can be assigned to Long, Double, Dec, etc.
        val numericCompatibility = mapOf(
            "Int" to setOf("Long", "Float", "Double", "Dec"),
            "Long" to setOf("Float", "Double", "Dec"),
            "Float" to setOf("Double", "Dec"),
            "Double" to setOf("Dec")
        )
        if (numericCompatibility[actualType]?.contains(declaredType) == true) return true

        // Class/trait subtype check: is the actual type a subclass/implementor of the declared type?
        try {
            val actualClass = classes[actualType]
            val declaredClass = classes[declaredType]
            if (actualClass != null && declaredClass != null) {
                if (isSubclassOf(actualClass, declaredClass)) return true
            }
            // Check trait compatibility
            val declaredTrait = traits[declaredType]
            if (declaredTrait != null && actualClass != null) {
                if (actualClass.implementsTrait(declaredTrait)) return true
            }
        } catch (_: Exception) {
            // Type not found — skip subtype check
        }

        return false
    }

    /**
     * Check if one class is a subclass of another (walks the superclass chain).
     */
    private fun isSubclassOf(subclass: KSClass, superclass: KSClass): Boolean {
        var current: KSClass? = subclass
        while (current != null) {
            if (current === superclass) return true
            current = current.superclass
        }
        return false
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
            val result = left.toMutableList()
            result.addAll(right)
            return result
        }
        // List + element → new list with element appended
        if (left is List<*>) {
            val result = left.toMutableList()
            result.add(right)
            return result
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
        // List - element → new list without first occurrence of element
        if (left is List<*>) {
            val result = left.toMutableList()
            result.remove(right)
            return result
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
            is Version -> stringifyVersion(value)
            is LocalDate -> "${value.year}/${value.monthValue}/${value.dayOfMonth}"
            is LocalDateTime -> stringifyLocalDateTime(value)
            is OffsetDateTime -> stringifyOffsetDateTime(value)
            is URL -> value.toString()
            is List<*> -> value.joinToString(", ", "[", "]") { stringify(it) }
            is Map<*, *> -> value.entries.joinToString(", ", "[", "]") { "${stringify(it.key)}=${stringify(it.value)}" }
            is KSEnumConstant -> value.name
            is KSObject -> value.toString()
            is KSStructInstance -> value.toString()
            is KDTag -> value.toString()
            is KDDocument -> value.toString()
            is Grid<*> -> stringifyGrid(value)
            is Coordinate -> value.toString()
            is KSType -> value.toString()
            is Regex -> value.pattern
            is MatchResult -> value.value
            else -> value.toString()
        }
    }

    /**
     * Format a Version using KS literal syntax (underscores for qualifier separation).
     *
     * Examples: "5.0.0", "0.2.0_beta", "0.2.0_beta_1"
     */
    private fun stringifyVersion(v: Version): String {
        var text = "${v.major}.${v.minor}.${v.micro}"
        if (v.qualifier.isNotEmpty()) {
            text += "_${v.qualifier}"
            if (v.qualifierNumber != 0) {
                text += "_${v.qualifierNumber}"
            }
        }
        return text
    }

    private fun stringifyLocalDateTime(dt: LocalDateTime): String {
        val sb = StringBuilder()
        sb.append("${dt.year}/${dt.monthValue}/${dt.dayOfMonth}")
        sb.append("@${dt.hour}:${dt.minute.toString().padStart(2, '0')}")
        if (dt.second != 0 || dt.nano != 0) {
            sb.append(":${dt.second.toString().padStart(2, '0')}")
            if (dt.nano != 0) {
                val frac = dt.nano.toString().padStart(9, '0').trimEnd('0')
                sb.append(".$frac")
            }
        }
        return sb.toString()
    }

    private fun stringifyOffsetDateTime(odt: OffsetDateTime): String {
        val base = stringifyLocalDateTime(odt.toLocalDateTime())
        val offset = odt.offset
        return when {
            offset == java.time.ZoneOffset.UTC -> "$base-Z"
            offset.totalSeconds % 3600 == 0 -> {
                val hours = offset.totalSeconds / 3600
                "$base${if (hours >= 0) "+" else ""}$hours"
            }
            else -> {
                val hours = offset.totalSeconds / 3600
                val minutes = Math.abs(offset.totalSeconds % 3600) / 60
                "$base${if (hours >= 0) "+" else ""}$hours:${minutes.toString().padStart(2, '0')}"
            }
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
            is Range<*> -> rangeToIterable(value, location)
            is KSEnum -> value.constants.values
            else -> throw TypeError("Cannot iterate over ${value?.let { it::class.simpleName } ?: "nil"}", location)
        }
    }

    /**
     * Convert an io.kixi.Range to an iterable sequence for for-loops.
     *
     * Supports Int, Long, and Char ranges. Supports forward and reversed
     * ranges, respecting exclusivity bounds. Delegates to RangeExt.toList().
     */
    private fun rangeToIterable(range: Range<*>, location: SourceLocation?): Iterable<Any?> {
        try {
            return range.rangeToList()
        } catch (e: IllegalArgumentException) {
            throw RuntimeError(e.message ?: "Cannot iterate over range", location)
        }
    }

    // ========================================================================
    // Built-in Member Access
    // ========================================================================

    /**
     * Member access on String values.
     *
     * ## `.rex` -- regex creation
     *
     * The `.rex` property converts a string to a `Regex`. The idiomatic
     * pattern pairs raw strings with `.rex`, since raw strings pass
     * through backslashes literally -- no double-escaping needed:
     *
     *     let r = `\d{3}-\d{2}-\d{4}`.rex   // raw string -- backslashes literal
     *     let r = "\\d{3}-\\d{2}-\\d{4}".rex // basic string -- \\ escape for \
     *
     * Basic strings process escapes strictly, so `"\d"` is a compile error
     * (unknown escape), not a silent bug. The error message guides developers
     * toward raw strings, making the correct pattern discoverable.
     *
     * All four KS string types work with `.rex`:
     *   - basic:          `"\\d+".rex`                (requires double escaping)
     *   - raw:            `` `\d+`.rex ``             (idiomatic for regex)
     *   - multiline:      `"""\\d+""".rex`            (requires double escaping)
     *   - raw multiline:  triple-backtick + `.rex`    (no escaping needed)
     */
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
            "rex" -> Regex(str)
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

    private fun getRangeMember(range: Range<*>, member: String, location: SourceLocation?): Any? {
        return when (member) {
            // Core properties
            "start" -> range.start
            "end" -> range.end
            "bound" -> range.bound.operator

            // Computed properties
            "min" -> range.min
            "max" -> range.max
            "reversed" -> range.reversed

            // Openness properties
            "isOpen" -> range.isOpen
            "isClosed" -> range.isClosed
            "isOpenStart" -> range.isOpenStart
            "isOpenEnd" -> range.isOpenEnd

            // Convenience booleans derived from bound
            "startExclusive" -> range.bound == Range.Bound.ExclusiveStart || range.bound == Range.Bound.Exclusive
            "endExclusive" -> range.bound == Range.Bound.ExclusiveEnd || range.bound == Range.Bound.Exclusive

            // Methods
            "contains" -> NativeCallable("contains") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("contains() requires 1 argument", loc)
                val element = args[0] ?: return@NativeCallable false
                @Suppress("UNCHECKED_CAST")
                (range as Range<Any>).contains(element)
            }
            "overlaps" -> NativeCallable("overlaps") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("overlaps() requires 1 argument", loc)
                val other = args[0] as? Range<*>
                    ?: throw TypeError("overlaps() requires a Range argument", loc)
                @Suppress("UNCHECKED_CAST")
                (range as Range<Any>).overlaps(other as Range<Any>)
            }
            "clamp" -> NativeCallable("clamp") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("clamp() requires 1 argument", loc)
                @Suppress("UNCHECKED_CAST")
                (range as Range<Any>).clamp(args[0] as Any)
            }
            "intersect" -> NativeCallable("intersect") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("intersect() requires 1 argument", loc)
                val other = args[0] as? Range<*>
                    ?: throw TypeError("intersect() requires a Range argument", loc)
                @Suppress("UNCHECKED_CAST")
                (range as Range<Any>).intersect(other as Range<Any>)
            }
            "toList" -> NativeCallable("toList") { args, loc ->
                val step = if (args.isNotEmpty()) {
                    (args[0] as? Number)?.toInt()
                        ?: throw TypeError("toList() step must be an Int", loc)
                } else 1
                try {
                    range.rangeToList(step)
                } catch (e: IllegalArgumentException) {
                    throw RuntimeError(e.message ?: "Cannot convert range to list", loc)
                }
            }
            "toSequence" -> NativeCallable("toSequence") { args, loc ->
                val step = if (args.isNotEmpty()) {
                    (args[0] as? Number)?.toInt()
                        ?: throw TypeError("toSequence() step must be an Int", loc)
                } else 1
                try {
                    range.rangeAsSequence(step).toList()
                } catch (e: IllegalArgumentException) {
                    throw RuntimeError(e.message ?: "Cannot convert range to sequence", loc)
                }
            }
            "count" -> NativeCallable("count") { args, loc ->
                val step = if (args.isNotEmpty()) {
                    (args[0] as? Number)?.toInt()
                        ?: throw TypeError("count() step must be an Int", loc)
                } else 1
                try {
                    range.rangeCount(step)
                } catch (e: IllegalArgumentException) {
                    throw RuntimeError(e.message ?: "Cannot count range", loc)
                }
            }

            else -> throw MemberNotFoundError(member, "Range", location)
        }
    }

    private fun getRegexMember(regex: Regex, member: String, location: SourceLocation?): Any? {
        return when (member) {
            // Properties
            "pattern" -> regex.pattern

            // Methods
            "matches" -> NativeCallable("matches") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("matches() requires 1 argument", loc)
                val input = args[0]?.toString()
                    ?: throw TypeError("matches() requires a String argument", loc)
                regex.matches(input)
            }
            "containsMatchIn" -> NativeCallable("containsMatchIn") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("containsMatchIn() requires 1 argument", loc)
                val input = args[0]?.toString()
                    ?: throw TypeError("containsMatchIn() requires a String argument", loc)
                regex.containsMatchIn(input)
            }
            "find" -> NativeCallable("find") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("find() requires 1 argument", loc)
                val input = args[0]?.toString()
                    ?: throw TypeError("find() requires a String argument", loc)
                // Returns MatchResult or nil
                regex.find(input)
            }
            "findAll" -> NativeCallable("findAll") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("findAll() requires 1 argument", loc)
                val input = args[0]?.toString()
                    ?: throw TypeError("findAll() requires a String argument", loc)
                regex.findAll(input).toList()
            }
            "replace" -> NativeCallable("replace") { args, loc ->
                if (args.size < 2) throw RuntimeError("replace() requires 2 arguments", loc)
                val input = args[0]?.toString()
                    ?: throw TypeError("replace() first argument must be a String", loc)
                val replacement = args[1]?.toString()
                    ?: throw TypeError("replace() second argument must be a String", loc)
                regex.replace(input, replacement)
            }
            "replaceFirst" -> NativeCallable("replaceFirst") { args, loc ->
                if (args.size < 2) throw RuntimeError("replaceFirst() requires 2 arguments", loc)
                val input = args[0]?.toString()
                    ?: throw TypeError("replaceFirst() first argument must be a String", loc)
                val replacement = args[1]?.toString()
                    ?: throw TypeError("replaceFirst() second argument must be a String", loc)
                regex.replaceFirst(input, replacement)
            }
            "split" -> NativeCallable("split") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("split() requires 1 argument", loc)
                val input = args[0]?.toString()
                    ?: throw TypeError("split() requires a String argument", loc)
                regex.split(input).toMutableList()
            }

            else -> throw MemberNotFoundError(member, "Regex", location)
        }
    }

    private fun getMatchResultMember(match: MatchResult, member: String, location: SourceLocation?): Any? {
        return when (member) {
            // Properties
            "value" -> match.value
            "groupValues" -> match.groupValues.toMutableList()
            "groupCount" -> match.groupValues.size - 1  // exclude group 0 (full match)

            else -> throw MemberNotFoundError(member, "MatchResult", location)
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
            // Properties
            "value" -> quantity.value
            "unit" -> quantity.unit.symbol
            "unitObject" -> quantity.unit  // raw Unit object for advanced use
            "unitUnicode" -> quantity.unit.unicode

            // Methods
            "convertTo" -> NativeCallable("convertTo") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("convertTo() requires 1 argument (unit symbol)", loc)
                val targetSymbol = args[0] as? String
                    ?: throw TypeError("convertTo() expects a unit symbol String", loc)
                val targetUnit = try {
                    KiUnit.parse(targetSymbol)
                } catch (e: Exception) {
                    throw RuntimeError("Unknown unit: '$targetSymbol'", loc)
                }
                try {
                    @Suppress("UNCHECKED_CAST")
                    (quantity as Quantity<KiUnit>).convertTo(targetUnit)
                } catch (e: Exception) {
                    throw RuntimeError("Conversion failed: ${e.message}", loc)
                }
            }
            "toSuffixString" -> NativeCallable("toSuffixString") { _, _ ->
                quantity.toSuffixString()
            }

            else -> throw MemberNotFoundError(member, "Quantity", location)
        }
    }

    private fun getVersionMember(version: Version, member: String, location: SourceLocation?): Any? {
        return when (member) {
            // Properties
            "major" -> version.major
            "minor" -> version.minor
            "micro" -> version.micro
            "qualifier" -> version.qualifier
            "qualifierNumber" -> version.qualifierNumber
            "hasQualifier" -> version.hasQualifier
            "isStable" -> version.isStable
            "isPreRelease" -> version.isPreRelease

            // Methods
            "toStable" -> NativeCallable("toStable") { _, _ -> version.toStable() }
            "toShortString" -> NativeCallable("toShortString") { _, _ -> version.toShortString() }
            "incrementMajor" -> NativeCallable("incrementMajor") { _, _ -> version.incrementMajor() }
            "incrementMinor" -> NativeCallable("incrementMinor") { _, _ -> version.incrementMinor() }
            "incrementMicro" -> NativeCallable("incrementMicro") { _, _ -> version.incrementMicro() }
            "incrementQualifierNumber" -> NativeCallable("incrementQualifierNumber") { _, loc ->
                try {
                    version.incrementQualifierNumber()
                } catch (e: Exception) {
                    throw RuntimeError(e.message ?: "Cannot increment qualifier number", loc)
                }
            }
            "isCompatibleWith" -> NativeCallable("isCompatibleWith") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("isCompatibleWith() requires 1 argument", loc)
                val other = args[0] as? Version
                    ?: throw TypeError("isCompatibleWith() expects a Version argument", loc)
                version.isCompatibleWith(other)
            }
            "withQualifier" -> NativeCallable("withQualifier") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("withQualifier() requires 1-2 arguments", loc)
                val qual = args[0] as? String
                    ?: throw TypeError("withQualifier() first argument must be a String", loc)
                val qualNum = if (args.size > 1) {
                    (args[1] as? Number)?.toInt()
                        ?: throw TypeError("withQualifier() second argument must be an Int", loc)
                } else 0
                version.withQualifier(qual, qualNum)
            }

            else -> throw MemberNotFoundError(member, "Version", location)
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

    // ========================================================================
    // Grid Member Access
    // ========================================================================

    private fun getGridMember(grid: Grid<*>, member: String, location: SourceLocation?): Any? {
        return when (member) {
            // Properties
            "width" -> grid.width
            "height" -> grid.height
            "size" -> grid.width * grid.height
            "isEmpty" -> grid.isEmpty
            "isNotEmpty" -> grid.isNotEmpty
            "data" -> grid.data.toList()
            "elementNullable" -> grid.elementNullable

            // Methods
            "transpose" -> NativeCallable("transpose") { _, _ -> grid.transpose() }
            "copy" -> NativeCallable("copy") { _, _ -> grid.copy() }
            "fill" -> NativeCallable("fill") { args, _ ->
                @Suppress("UNCHECKED_CAST")
                (grid as Grid<Any?>).fill(args.firstOrNull())
                null
            }
            "clear" -> NativeCallable("clear") { _, _ ->
                @Suppress("UNCHECKED_CAST")
                (grid as Grid<Any?>).clear()
                null
            }
            "toList" -> NativeCallable("toList") { _, _ -> grid.toList() }
            "toRowList" -> NativeCallable("toRowList") { _, _ -> grid.toRowList() }
            "getRowCopy" -> NativeCallable("getRowCopy") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("getRowCopy() requires 1 argument", loc)
                val y = toInt(args[0]!!, loc)
                grid.getRowCopy(y)
            }
            "getColumnCopy" -> NativeCallable("getColumnCopy") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("getColumnCopy() requires 1 argument", loc)
                when (val arg = args[0]) {
                    is Int -> grid.getColumnCopy(arg)
                    is String -> grid.getColumnCopy(arg)
                    else -> throw TypeError("getColumnCopy() expects Int or String", loc)
                }
            }
            "setRow" -> NativeCallable("setRow") { args, loc ->
                if (args.size < 2) throw RuntimeError("setRow() requires 2 arguments (y, values)", loc)
                val y = toInt(args[0]!!, loc)
                val values = args[1] as? List<*>
                    ?: throw TypeError("setRow() second argument must be a List", loc)
                @Suppress("UNCHECKED_CAST")
                (grid as Grid<Any?>).setRow(y, values)
                null
            }
            "setColumn" -> NativeCallable("setColumn") { args, loc ->
                if (args.size < 2) throw RuntimeError("setColumn() requires 2 arguments (x, values)", loc)
                val values = args[1] as? List<*>
                    ?: throw TypeError("setColumn() second argument must be a List", loc)
                @Suppress("UNCHECKED_CAST")
                when (val col = args[0]) {
                    is Int -> (grid as Grid<Any?>).setColumn(col, values)
                    is String -> (grid as Grid<Any?>).setColumn(col, values)
                    else -> throw TypeError("setColumn() first argument must be Int or String", loc)
                }
                null
            }
            "fillRow" -> NativeCallable("fillRow") { args, loc ->
                if (args.size < 2) throw RuntimeError("fillRow() requires 2 arguments (y, value)", loc)
                val y = toInt(args[0]!!, loc)
                @Suppress("UNCHECKED_CAST")
                (grid as Grid<Any?>).fillRow(y, args[1])
                null
            }
            "fillColumn" -> NativeCallable("fillColumn") { args, loc ->
                if (args.size < 2) throw RuntimeError("fillColumn() requires 2 arguments (x, value)", loc)
                @Suppress("UNCHECKED_CAST")
                when (val col = args[0]) {
                    is Int -> (grid as Grid<Any?>).fillColumn(col, args[1])
                    is String -> (grid as Grid<Any?>).fillColumn(col, args[1])
                    else -> throw TypeError("fillColumn() first argument must be Int or String", loc)
                }
                null
            }
            "subgrid" -> NativeCallable("subgrid") { args, loc ->
                if (args.size != 4) throw RuntimeError("subgrid() requires 4 arguments (startX, startY, width, height)", loc)
                val sx = toInt(args[0]!!, loc)
                val sy = toInt(args[1]!!, loc)
                val w = toInt(args[2]!!, loc)
                val h = toInt(args[3]!!, loc)
                grid.subgrid(sx, sy, w, h)
            }

            else -> throw MemberNotFoundError(member, "Grid", location)
        }
    }

    // ========================================================================
    // Coordinate Member Access
    // ========================================================================

    private fun getCoordinateMember(coord: Coordinate, member: String, location: SourceLocation?): Any? {
        return when (member) {
            "x" -> coord.x
            "y" -> coord.y
            "column" -> coord.column
            "row" -> coord.row
            "hasZ" -> coord.hasZ
            "z" -> coord.z
            "toSheetNotation" -> NativeCallable("toSheetNotation") { _, _ -> coord.toSheetNotation() }
            "toStandardNotation" -> NativeCallable("toStandardNotation") { _, _ -> coord.toStandardNotation() }
            "right" -> NativeCallable("right") { args, loc ->
                val n = if (args.isNotEmpty()) toInt(args[0]!!, loc) else 1
                coord.right(n)
            }
            "left" -> NativeCallable("left") { args, loc ->
                val n = if (args.isNotEmpty()) toInt(args[0]!!, loc) else 1
                coord.left(n)
            }
            "up" -> NativeCallable("up") { args, loc ->
                val n = if (args.isNotEmpty()) toInt(args[0]!!, loc) else 1
                coord.up(n)
            }
            "down" -> NativeCallable("down") { args, loc ->
                val n = if (args.isNotEmpty()) toInt(args[0]!!, loc) else 1
                coord.down(n)
            }
            else -> throw MemberNotFoundError(member, "Coordinate", location)
        }
    }

    // ========================================================================
    // Grid Stringify
    // ========================================================================

    /**
     * Format a Grid as a readable string in Ki literal format.
     *
     *     .grid(
     *         1    2    3
     *         4    5    6
     *     )
     */
    private fun stringifyGrid(grid: Grid<*>): String {
        // Build type annotation string
        // Show type when elementType is set (and not Any — Any/Any? grids don't display the type)
        val typeStr = if (grid.elementType != null && grid.elementType != Any::class.java) {
            val typeName = gridElementTypeName(grid.elementType!!)
            val nullSuffix = if (grid.elementNullable) "?" else ""
            "<$typeName$nullSuffix>"
        } else {
            ""
        }

        // Empty grid: .grid() or .grid<Int>()
        if (grid.width == 0 || grid.height == 0) {
            return ".grid$typeStr()"
        }

        // Non-empty grid: .grid { ... } or .grid<Int> { ... }
        val cellStrings = Array(grid.height) { y ->
            Array(grid.width) { x ->
                stringify(grid[x, y])
            }
        }

        // Calculate max width for each column for alignment
        val colWidths = IntArray(grid.width) { x ->
            (0 until grid.height).maxOf { y -> cellStrings[y][x].length }
        }

        val sb = StringBuilder(".grid$typeStr {\n")
        for (y in 0 until grid.height) {
            sb.append("    ")
            for (x in 0 until grid.width) {
                if (x > 0) sb.append("  ")
                sb.append(cellStrings[y][x].padStart(colWidths[x]))
            }
            sb.append("\n")
        }
        sb.append("}")
        return sb.toString()
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

/**
 * Sentinel object representing a built-in KS type (String, Int, Bool, etc.).
 *
 * Built-in types are not defined in the user environment the way classes,
 * structs, and enums are. This class allows them to be resolved as
 * first-class values so that expressions like `String.type` work.
 *
 * @property name The KS type name (e.g. "String", "Int", "Bool")
 * @property kind The type kind keyword (e.g. "class")
 */
class KSBuiltinType(val name: String, val kind: String = "class") {
    override fun toString(): String = "$kind $name"
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
            is LocalDate -> "${value.year}/${value.monthValue}/${value.dayOfMonth}"
            is LocalDateTime -> formatLocalDateTime(value)
            is OffsetDateTime -> formatOffsetDateTime(value)
            is URL -> value.toString()
            else -> value.toString()
        }
    }

    private fun formatLocalDateTime(dt: LocalDateTime): String {
        val sb = StringBuilder()
        sb.append("${dt.year}/${dt.monthValue}/${dt.dayOfMonth}")
        sb.append("@${dt.hour}:${dt.minute.toString().padStart(2, '0')}")
        if (dt.second != 0 || dt.nano != 0) {
            sb.append(":${dt.second.toString().padStart(2, '0')}")
            if (dt.nano != 0) {
                val frac = dt.nano.toString().padStart(9, '0').trimEnd('0')
                sb.append(".$frac")
            }
        }
        return sb.toString()
    }

    private fun formatOffsetDateTime(odt: OffsetDateTime): String {
        val base = formatLocalDateTime(odt.toLocalDateTime())
        val offset = odt.offset
        return when {
            offset == java.time.ZoneOffset.UTC -> "$base-Z"
            offset.totalSeconds % 3600 == 0 -> {
                val hours = offset.totalSeconds / 3600
                "$base${if (hours >= 0) "+" else ""}$hours"
            }
            else -> {
                val hours = offset.totalSeconds / 3600
                val minutes = Math.abs(offset.totalSeconds % 3600) / 60
                "$base${if (hours >= 0) "+" else ""}$hours:${minutes.toString().padStart(2, '0')}"
            }
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

/**
 * A callable backed by a Kotlin lambda, used for built-in methods on
 * primitive types (Range.contains, Range.clamp, etc.).
 *
 * @param name Display name for error messages
 * @param body The lambda to execute when called
 */
class NativeCallable(
    val name: String,
    private val body: (List<Any?>, SourceLocation?) -> Any?
) : Callable {
    override fun call(interpreter: Interpreter, arguments: List<Any?>, location: SourceLocation?): Any? {
        return body(arguments, location)
    }

    override fun toString(): String = "<native method $name>"
}