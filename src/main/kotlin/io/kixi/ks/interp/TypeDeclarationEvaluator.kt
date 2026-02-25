package io.kixi.ks.interp

import io.kixi.NSID
import io.kixi.kd.Tag as KdTag
import io.kixi.kd.Annotation as KdAnnotation
import io.kixi.ks.*
import io.kixi.ks.parser.*

/**
 * Type declaration evaluator for the KS interpreter.
 *
 * Handles evaluation of all type-system declarations and related operations:
 *
 * - **Classes**: declaration, static member initialization, instantiation,
 *   superclass body property inheritance
 * - **Traits**: declaration, super-trait resolution
 * - **Structs**: declaration, static members, instantiation, method calls
 * - **Enums**: declaration, constant initialization, DPEC resolution
 * - **Extensions**: `extend` blocks for classes, structs, and traits
 * - **Static blocks**: top-level and nested static member processing
 * - **Lang blocks**: `lang KD { ... }` DSL evaluation
 * - **Reflection**: `x::class` evaluation
 * - **Use declarations**: import delegation to [ImportRegistry]
 * - **Validation**: trait conformance checking for classes and structs
 *
 * Follows the same delegation pattern as the Parser's sub-parsers — holds a
 * reference to the parent [Interpreter] for access to shared state and the
 * core evaluate() method.
 *
 * @param interp Reference to the parent [Interpreter] for state access.
 */
class TypeDeclarationEvaluator(internal val interp: Interpreter) {

    // Convenience accessors for frequently used interpreter state
    private inline val ops get() = interp.ops

    // ========================================================================
    // Class Declarations & Instantiation
    // ========================================================================

    internal fun evaluateClassDecl(decl: ClassDecl): Any? {
        // Resolve superclass if specified
        val superclass: KSClass? = if (decl.superTypes.isNotEmpty()) {
            val superTypeName = decl.superTypes.first().name
            interp.classes[superTypeName] ?: interp.traits[superTypeName]?.let { null }
        } else {
            null
        }

        // Resolve traits
        val implementedTraits = decl.superTypes.mapNotNull { typeRef ->
            interp.traits[typeRef.name]
        }

        // Create class
        val ksClass = KSClass(decl, superclass, implementedTraits, interp.environment)
        interp.classes[decl.name] = ksClass

        // Warn if class declares members that shadow reserved reflection properties
        ops.warnReservedMemberNames(decl.name, decl.members, decl.constructorParams, decl.location)

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
        interp.environment.define(decl.name, ksClass, mutable = false, location = decl.location)

        return null
    }

    private fun initializeStaticMembers(ksClass: KSClass, members: List<Node>) {
        val staticEnv = ksClass.staticEnvironment()
        val previousEnv = interp.environment
        interp.environment = staticEnv

        try {
            for (member in members) {
                when (member) {
                    is StaticBlock -> {
                        for (staticMember in member.members) {
                            when (staticMember) {
                                is VarDecl -> {
                                    val value = staticMember.initializer?.let { interp.evaluate(it) }
                                    // Check null safety for non-nullable static variable types
                                    ops.checkNullSafety(staticMember.name, value, staticMember.typeAnnotation, staticMember.location)
                                    ops.checkTypeCompatibility(staticMember.name, value, staticMember.typeAnnotation, staticMember.location)
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
            interp.environment = previousEnv
        }
    }

    /**
     * Create a new instance of a class.
     */
    internal fun instantiateClass(
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
                param.defaultValue != null -> interp.evaluate(param.defaultValue)
                else -> throw ArityError(ksClass.name, params.size, args.size, location)
            }

            // Check null safety for non-nullable constructor parameter types
            ops.checkNullSafety(param.name, value, param.type, location)
            ops.checkTypeCompatibility(param.name, value, param.type, location)

            // Check constraint
            if (param.constraint != null && value != null) {
                ops.checkConstraint(param.name, value, param.constraint, location)
            }

            // If param has binding (var/let), create a property
            if (param.binding != null) {
                obj.initProperty(param.name, value, param.binding == BindingType.VAR)
            }
        }

        // Initialize instance properties from class body (including superclasses)
        val previousEnv = interp.environment
        val instanceEnv = interp.environment.child("instance:${ksClass.name}")
        instanceEnv.define("this", obj, mutable = false, location = location)
        interp.environment = instanceEnv

        try {
            // Initialize superclass body properties first (walk up chain)
            initSuperclassBodyProperties(ksClass.superclass, obj)

            // Then current class body properties
            for (property in ksClass.getInstanceProperties()) {
                val value = property.initializer?.let { interp.evaluate(it) }
                // Check null safety for non-nullable property types
                ops.checkNullSafety(property.name, value, property.typeAnnotation, property.location)
                ops.checkTypeCompatibility(property.name, value, property.typeAnnotation, property.location)
                if (property.constraint != null && value != null) {
                    ops.checkConstraint(property.name, value, property.constraint, property.location)
                }
                obj.initProperty(property.name, value, property.mutable)
            }
        } finally {
            interp.environment = previousEnv
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
                val value = property.initializer?.let { interp.evaluate(it) }
                // Check null safety for non-nullable property types
                ops.checkNullSafety(property.name, value, property.typeAnnotation, property.location)
                ops.checkTypeCompatibility(property.name, value, property.typeAnnotation, property.location)
                if (property.constraint != null && value != null) {
                    ops.checkConstraint(property.name, value, property.constraint, property.location)
                }
                obj.initProperty(property.name, value, property.mutable)
            }
        }
    }

    // ========================================================================
    // Trait Declarations
    // ========================================================================

    internal fun evaluateTraitDecl(decl: TraitDecl): Any? {
        // Resolve super traits
        val superTraits = decl.superTraits.mapNotNull { typeRef ->
            interp.traits[typeRef.name]
        }

        val ksTrait = KSTrait(decl, superTraits, interp.environment)
        interp.traits[decl.name] = ksTrait

        // Make trait available in environment
        interp.environment.define(decl.name, ksTrait, mutable = false, location = decl.location)

        return null
    }

    // ========================================================================
    // Struct Declarations & Instantiation
    // ========================================================================

    internal fun evaluateStructDecl(decl: StructDecl): Any? {
        // Resolve traits (structs can only implement traits, no superclass)
        val implementedTraits = decl.traits.mapNotNull { typeRef ->
            interp.traits[typeRef.name] ?: run {
                // Check if it's a class -- structs can't extend classes
                if (interp.classes.containsKey(typeRef.name)) {
                    throw RuntimeError(
                        "Struct '${decl.name}' cannot extend class '${typeRef.name}'. Structs can only implement traits.",
                        decl.location
                    )
                }
                null
            }
        }

        val ksStruct = KSStruct(decl, implementedTraits, interp.environment)
        interp.structs[decl.name] = ksStruct

        // Warn if struct declares members that shadow reserved reflection properties
        ops.warnReservedMemberNames(decl.name, decl.members, decl.constructorParams, decl.location)

        // Validate trait conformance: every abstract method must be implemented
        validateStructTraitConformance(ksStruct)

        // Initialize static members
        initializeStructStaticMembers(ksStruct, decl.members)

        // Make struct available in environment
        interp.environment.define(decl.name, ksStruct, mutable = false, location = decl.location)

        return null
    }

    private fun initializeStructStaticMembers(ksStruct: KSStruct, members: List<Node>) {
        val staticEnv = ksStruct.staticEnvironment()
        val previousEnv = interp.environment
        interp.environment = staticEnv

        try {
            for (member in members) {
                when (member) {
                    is StaticBlock -> {
                        for (staticMember in member.members) {
                            when (staticMember) {
                                is VarDecl -> {
                                    val value = staticMember.initializer?.let { interp.evaluate(it) }
                                    // Check null safety for non-nullable static variable types
                                    ops.checkNullSafety(staticMember.name, value, staticMember.typeAnnotation, staticMember.location)
                                    ops.checkTypeCompatibility(staticMember.name, value, staticMember.typeAnnotation, staticMember.location)
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
            interp.environment = previousEnv
        }
    }

    /**
     * Create a new instance of a struct.
     */
    internal fun instantiateStruct(
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
                param.defaultValue != null -> interp.evaluate(param.defaultValue)
                else -> throw ArityError(ksStruct.name, params.size, args.size, location)
            }

            // Check null safety for non-nullable constructor parameter types
            ops.checkNullSafety(param.name, value, param.type, location)
            ops.checkTypeCompatibility(param.name, value, param.type, location)

            // Check constraint
            if (param.constraint != null && value != null) {
                ops.checkConstraint(param.name, value, param.constraint, location)
            }

            // If param has binding (var/let), create a property
            if (param.binding != null) {
                instance.initProperty(param.name, value, param.binding == BindingType.VAR)
            }
        }

        // Initialize instance properties from struct body
        val previousEnv = interp.environment
        val instanceEnv = interp.environment.child("instance:${ksStruct.name}")
        instanceEnv.define("this", instance, mutable = false, location = location)
        interp.environment = instanceEnv

        try {
            for (property in ksStruct.getInstanceProperties()) {
                val value = property.initializer?.let { interp.evaluate(it) }
                // Check null safety for non-nullable property types
                ops.checkNullSafety(property.name, value, property.typeAnnotation, property.location)
                ops.checkTypeCompatibility(property.name, value, property.typeAnnotation, property.location)
                if (property.constraint != null && value != null) {
                    ops.checkConstraint(property.name, value, property.constraint, property.location)
                }
                instance.initProperty(property.name, value, property.mutable)
            }
        } finally {
            interp.environment = previousEnv
        }

        return instance
    }

    /**
     * Call a method on a struct instance with proper `this` binding.
     */
    internal fun callStructMethod(receiver: KSStructInstance, method: KSFunction, arguments: List<Any?>, location: SourceLocation?): Any? {
        if (interp.runtime.maxRecursionDepth > 0 && interp.recursionDepth >= interp.runtime.maxRecursionDepth) {
            throw RuntimeError("Maximum recursion depth exceeded (${interp.runtime.maxRecursionDepth})", location)
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
                ops.copyIfStruct(arguments[i])
            } else {
                param.defaultValue?.let { interp.evaluate(it) }
            }

            // Check null safety for non-nullable parameter types
            ops.checkNullSafety(param.name, value, param.type, location)
            ops.checkTypeCompatibility(param.name, value, param.type, location)

            if (param.constraint != null && value != null) {
                ops.checkConstraint(param.name, value, param.constraint, param.location)
            }

            methodEnv.define(param.name, value, mutable = true, param.type, param.constraint, param.location)
        }

        val previousEnv = interp.environment
        interp.environment = methodEnv
        interp.recursionDepth++

        try {
            val body = method.declaration.body
                ?: throw RuntimeError("Cannot call abstract method '${method.name}'", location)

            return if (method.isSingleExpr) {
                interp.evaluate(body)
            } else {
                try {
                    interp.evaluate(body)
                    // Last expression value is implicitly returned
                } catch (ret: ReturnValue) {
                    ret.value
                }
            }
        } finally {
            interp.recursionDepth--
            interp.environment = previousEnv
        }
    }

    // ========================================================================
    // Trait Conformance Validation
    // ========================================================================

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
                // We need to verify the struct provides a concrete implementation --
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

    // ========================================================================
    // Enum Declarations
    // ========================================================================

    internal fun evaluateEnumDecl(decl: EnumDecl): Any? {
        val ksEnum = KSEnum(decl, interp.environment)
        interp.enums[decl.name] = ksEnum

        // Initialize enum constants
        for ((index, constant) in decl.constants.withIndex()) {
            val enumValue = when {
                // Constructor-style: OK(200, "OK")
                constant.arguments.isNotEmpty() -> {
                    val args = constant.arguments.map { interp.evaluate(it.value) }
                    KSEnumConstant(ksEnum, constant.name, index, args)
                }
                // Value-style: Apple = 1
                constant.value != null -> {
                    val value = interp.evaluate(constant.value)
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
                    val fn = KSFunction(member, interp.environment)
                    ksEnum.staticMembers.defineFunction(member.name, fn, member.location)
                }
                is VarDecl -> {
                    val value = member.initializer?.let { interp.evaluate(it) }
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
                    val previousEnv = interp.environment
                    interp.environment = ksEnum.staticMembers
                    try {
                        for (staticMember in member.members) {
                            interp.evaluate(staticMember)
                        }
                    } finally {
                        interp.environment = previousEnv
                    }
                }
                else -> { /* ignore */ }
            }
        }

        // Make enum available in environment
        interp.environment.define(decl.name, ksEnum, mutable = false, location = decl.location)

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
    internal fun evaluateDPEC(expr: DPECExpr): Any? {
        val constantName = expr.name

        // First, check current enum context (set during when evaluation)
        interp.currentEnumContext?.let { enum ->
            enum.getConstant(constantName)?.let { return it }
        }

        // Search all enums for the constant
        for ((_, enum) in interp.enums) {
            enum.getConstant(constantName)?.let { return it }
        }

        throw UndefinedNameError(constantName, NameKind.ENUM_CONSTANT, expr.location)
    }

    // ========================================================================
    // Use Declarations (Imports)
    // ========================================================================

    /**
     * Evaluate a `use` (import) declaration.
     *
     * Delegates to [ImportRegistry] which handles:
     * - Direct imports (eager resolution)
     * - Flat wildcards (lazy, `.*`)
     * - Tree wildcards (lazy with subpackage scanning, `.**`)
     * - Static member imports (e.g. `use Version.parse`)
     * - KS type imports (class, struct, trait, enum)
     * - JVM class imports (when hostLang=true)
     *
     * @throws ImportError if a direct import cannot be resolved
     */
    internal fun evaluateUseDecl(decl: UseDecl): Any? {
        interp.importRegistry.processUseDecl(
            decl,
            ksClasses = interp.classes,
            ksStructs = interp.structs,
            ksTraits = interp.traits,
            ksEnums = interp.enums
        )
        return null
    }

    // ========================================================================
    // Extend Declarations
    // ========================================================================

    internal fun evaluateExtendDecl(decl: ExtendDecl): Any? {
        val targetName = decl.target.name

        if (decl.isTraitExtension) {
            // extend trait Foo { fun bar() = ... }
            val trait = interp.traits[targetName]
                ?: throw RuntimeError("Cannot extend unknown trait '$targetName'", decl.location)

            for (member in decl.members) {
                when (member) {
                    is FunDecl -> {
                        val fn = KSFunction(member, interp.environment)
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
            val ksClass = interp.classes[targetName]
            val ksStruct = interp.structs[targetName]

            if (ksClass == null && ksStruct == null) {
                throw RuntimeError("Cannot extend unknown type '$targetName'", decl.location)
            }

            for (member in decl.members) {
                when (member) {
                    is FunDecl -> {
                        val fn = KSFunction(member, interp.environment)
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

        if (interp.runtime.debugMode) {
            val kind = if (decl.isTraitExtension) "trait " else ""
            interp.runtime.outputWriter.println("[DEBUG] extend $kind$targetName with ${decl.members.size} members")
        }

        return null
    }

    // ========================================================================
    // Static Blocks
    // ========================================================================

    internal fun evaluateStaticBlock(block: StaticBlock): Any? {
        // Static blocks are processed during class/enum declaration
        // If encountered at top level, evaluate as a regular block
        for (member in block.members) {
            interp.evaluate(member)
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
     * Returns a Tag if there's a single root tag, or a root Tag named "root"
     * wrapping multiple tags as children (matching KD.read() behavior).
     */
    internal fun evaluateLangBlock(expr: LangBlockExpr): Any? {
        return when (expr.language.uppercase()) {
            "KD" -> {
                val tags = expr.body.map { evaluateKDTag(it) }
                if (tags.size == 1) tags[0]
                else {
                    // Multiple root tags → wrap in a "root" Tag (matches KD.read() behavior)
                    val root = KdTag("root")
                    root.children.addAll(tags)
                    root
                }
            }
            else -> throw RuntimeError("Unsupported DSL language: '${expr.language}'", expr.location)
        }
    }

    /**
     * Evaluate a KD tag node into an io.kixi.kd.Tag runtime object.
     */
    internal fun evaluateKDTag(tag: KDTagNode): KdTag {
        // Create the Tag with name and optional namespace
        val kdTag = KdTag(tag.name, tag.namespace ?: "")

        // Evaluate and add values
        for (valueExpr in tag.values) {
            kdTag.values.add(interp.evaluate(valueExpr))
        }

        // Evaluate and add attributes (using NSID keys)
        for (attr in tag.attributes) {
            val nsid = NSID(attr.name, attr.namespace ?: "")
            kdTag.attributes[nsid] = interp.evaluate(attr.value)
        }

        // Evaluate and add annotations
        for (ann in tag.annotations) {
            val kdAnn = KdAnnotation(ann.name)
            for (valueExpr in ann.values) {
                kdAnn.values.add(interp.evaluate(valueExpr))
            }
            for (attr in ann.attributes) {
                val nsid = NSID(attr.name, attr.namespace ?: "")
                kdAnn.attributes[nsid] = interp.evaluate(attr.value)
            }
            kdTag.annotations.add(kdAnn)
        }

        // Recursively evaluate and add children
        for (child in tag.children) {
            kdTag.children.add(evaluateKDTag(child))
        }

        return kdTag
    }

    // ========================================================================
    // Reflection
    // ========================================================================

    /**
     * Evaluate a reflection expression like `x::class`.
     */
    internal fun evaluateReflection(expr: ReflectionExpr): Any? {
        val value = interp.evaluate(expr.expr)

        return when (expr.member) {
            "class" -> ops.getKSType(value)
            else -> throw RuntimeError("Unknown reflection member: '${expr.member}'", expr.location)
        }
    }
}