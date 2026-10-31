package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.parser.UseDecl
import io.kixi.ks.parser.UseImport
import io.kixi.ks.parser.UseWildcard
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// ============================================================================
// Resolved Import Types
// ============================================================================

/**
 * A successfully resolved import target.
 *
 * Sealed hierarchy covering all import sources:
 * - JVM classes (via reflection when hostLang=true)
 * - JVM members (static/companion/object members)
 * - KS-defined types (class, struct, trait, enum)
 * - KS static members (from static blocks)
 */
sealed class ResolvedImport {
    /** A JVM class, accessible for construction and static member access. */
    data class JVMClass(val proxy: JVMClassProxy) : ResolvedImport()

    /** A JVM member value (static field, companion member, object member). */
    data class JVMMember(val value: Any?) : ResolvedImport()

    /** A JVM callable member (static method, companion method, object method). */
    data class JVMCallable(val callable: JVMMethodProxy) : ResolvedImport()

    /** A KS-defined class. */
    data class KsClass(val ksClass: KSClass) : ResolvedImport()

    /** A KS-defined struct. */
    data class KsStruct(val ksStruct: KSStruct) : ResolvedImport()

    /** A KS-defined trait. */
    data class KsTrait(val ksTrait: KSTrait) : ResolvedImport()

    /** A KS-defined enum. */
    data class KsEnum(val ksEnum: KSEnum) : ResolvedImport()

    /** A KS static member (function or variable from a static block). */
    data class KsStaticMember(val value: Any?) : ResolvedImport()

    /**
     * A declaration of a crate (`use lab.assays.proteinConc`). Its value is read
     * on every use, so an imported crate `var` shows the crate's current state.
     */
    class CrateMember internal constructor(internal val declaration: CrateDeclaration) : ResolvedImport() {
        /** The declaration's current value. */
        val value: Any? get() = declaration.read()

        override fun toString(): String = "CrateMember(${declaration.file.crate.name}.${declaration.name})"
    }

    /**
     * A public top-level name of a script module (`use ./helpers/dilute`). Its
     * value is read on every use, so an imported module `var` shows its current
     * value.
     */
    class ModuleMember internal constructor(internal val module: ScriptModule, internal val name: String) : ResolvedImport() {
        /** The name's current value. */
        val value: Any? get() = module.read(name)

        override fun toString(): String = "ModuleMember(${module.name}.$name)"
    }
}

// ============================================================================
// JVM Class Proxy
// ============================================================================

/**
 * Proxy wrapper around a JVM [Class] for use in the KS runtime.
 *
 * Provides:
 * - Construction via reflected constructors
 * - Static and companion member access
 * - Kotlin `object` singleton access
 * - Integration with KS `.type` and `.typeName` reflection
 *
 * ## Kotlin Object Detection
 *
 * Kotlin `object` declarations compile to a class with a static `INSTANCE` field.
 * When detected, member access is delegated to the singleton instance rather than
 * treated as static method calls.
 *
 * ## Companion Object
 *
 * Kotlin companion objects compile to a nested `Companion` class with an instance
 * stored as a static `Companion` field on the outer class. Static-like methods
 * (e.g. `Version.parse()`) are dispatched through the companion instance.
 *
 * @property clazz The underlying JVM class
 */
class JVMClassProxy(val clazz: Class<*>) {

    /** Simple class name (e.g. "Version", "Tag"). */
    val simpleName: String get() = clazz.simpleName

    /** Fully qualified class name (e.g. "io.kixi.Version"). */
    val qualifiedName: String get() = clazz.name

    /** True if this is a Kotlin `object` (singleton). */
    val isKotlinObject: Boolean by lazy {
        try {
            val field = clazz.getDeclaredField("INSTANCE")
            Modifier.isStatic(field.modifiers) && field.type == clazz
        } catch (e: NoSuchFieldException) {
            false
        }
    }

    /** The Kotlin object singleton instance, or null if not an object. */
    val objectInstance: Any? by lazy {
        if (isKotlinObject) {
            try {
                clazz.getDeclaredField("INSTANCE").get(null)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    /** The Kotlin companion object instance, or null if none. */
    val companionInstance: Any? by lazy {
        try {
            val field = clazz.getDeclaredField("Companion")
            if (Modifier.isStatic(field.modifiers)) {
                field.isAccessible = true
                field.get(null)
            } else null
        } catch (e: NoSuchFieldException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /** The companion class, or null if none. */
    val companionClass: Class<*>? by lazy {
        companionInstance?.javaClass
    }

    /**
     * Construct an instance of this class with the given arguments.
     *
     * Tries to find a constructor matching the argument types. For Kotlin
     * objects, returns the singleton instance (ignoring arguments).
     *
     * @throws ImportError if no matching constructor is found
     */
    fun construct(args: List<Any?>, location: SourceLocation?): Any {
        if (isKotlinObject) {
            return objectInstance
                ?: throw ImportError("Cannot instantiate Kotlin object '${simpleName}'", location)
        }

        val constructors = clazz.constructors

        // Try exact match first
        for (ctor in constructors) {
            if (ctor.parameterCount == args.size) {
                try {
                    ctor.isAccessible = true
                    return ctor.newInstance(*args.toTypedArray())
                } catch (e: IllegalArgumentException) {
                    continue // try next constructor
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw RuntimeError(
                        "Error constructing $simpleName: ${e.targetException.message}",
                        location, e.targetException
                    )
                }
            }
        }

        // Try with type coercion
        for (ctor in constructors) {
            if (ctor.parameterCount == args.size) {
                val coerced = tryCoerceArgs(args, ctor.parameterTypes)
                if (coerced != null) {
                    try {
                        ctor.isAccessible = true
                        return ctor.newInstance(*coerced.toTypedArray())
                    } catch (e: IllegalArgumentException) {
                        continue
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        throw RuntimeError(
                            "Error constructing $simpleName: ${e.targetException.message}",
                            location, e.targetException
                        )
                    }
                }
            }
        }

        val ctorSigs = constructors.joinToString(", ") { c ->
            "(${c.parameterTypes.joinToString(", ") { it.simpleName }})"
        }
        throw ArityError(
            simpleName,
            constructors.firstOrNull()?.parameterCount ?: 0,
            args.size,
            location,
            "Available constructors: $ctorSigs"
        )
    }

    /**
     * Access a member (method, field, companion method) by name.
     *
     * Resolution order:
     * 1. Kotlin object members (if this is an object)
     * 2. Companion object methods
     * 3. Java static methods
     * 4. Java static fields
     * 5. Companion object fields/properties
     *
     * @return The member value/callable, or null if not found
     */
    fun getMember(name: String, location: SourceLocation?): Any? {
        // 1. Kotlin object — dispatch to the singleton
        if (isKotlinObject && objectInstance != null) {
            return getMemberOnInstance(objectInstance!!, name, location)
        }

        // 2. Companion object methods
        if (companionInstance != null) {
            val companionMethod = findMethodOn(companionClass!!, name)
            if (companionMethod != null) {
                return JVMMethodProxy(companionInstance!!, companionMethod, simpleName)
            }

            // Companion properties (via getter)
            val getter = findGetterOn(companionClass!!, name)
            if (getter != null) {
                getter.isAccessible = true
                return getter.invoke(companionInstance)
            }

            // Companion fields
            val companionField = findFieldOn(companionClass!!, name)
            if (companionField != null) {
                companionField.isAccessible = true
                return companionField.get(companionInstance)
            }
        }

        // 3. Java static methods
        val staticMethod = findStaticMethod(name)
        if (staticMethod != null) {
            return JVMMethodProxy(null, staticMethod, simpleName)
        }

        // 4. Java static fields
        val staticField = findStaticField(name)
        if (staticField != null) {
            staticField.isAccessible = true
            return staticField.get(null)
        }

        return null
    }

    /**
     * Check if a member exists.
     */
    fun hasMember(name: String): Boolean {
        if (isKotlinObject) {
            return findMethodOn(clazz, name) != null ||
                    findGetterOn(clazz, name) != null ||
                    findFieldOn(clazz, name) != null
        }
        return (companionInstance != null && (
                findMethodOn(companionClass!!, name) != null ||
                        findGetterOn(companionClass!!, name) != null ||
                        findFieldOn(companionClass!!, name) != null
                )) ||
                findStaticMethod(name) != null ||
                findStaticField(name) != null
    }

    override fun toString(): String = "class $simpleName"

    // --- Private helpers ---

    private fun getMemberOnInstance(instance: Any, name: String, location: SourceLocation?): Any? {
        val instanceClass = instance.javaClass

        // Method
        val method = findMethodOn(instanceClass, name)
        if (method != null) return JVMMethodProxy(instance, method, simpleName)

        // Property getter
        val getter = findGetterOn(instanceClass, name)
        if (getter != null) {
            getter.isAccessible = true
            return getter.invoke(instance)
        }

        // Direct field
        val field = findFieldOn(instanceClass, name)
        if (field != null) {
            field.isAccessible = true
            return field.get(instance)
        }

        return null
    }

    private fun findStaticMethod(name: String): Method? {
        return clazz.declaredMethods.firstOrNull {
            it.name == name && Modifier.isStatic(it.modifiers)
        }
    }

    private fun findStaticField(name: String): Field? {
        return clazz.declaredFields.firstOrNull {
            it.name == name && Modifier.isStatic(it.modifiers)
        }
    }

    private fun findMethodOn(target: Class<*>, name: String): Method? {
        return target.declaredMethods.firstOrNull { it.name == name }
            ?: target.methods.firstOrNull { it.name == name }
    }

    private fun findGetterOn(target: Class<*>, name: String): Method? {
        val getterName = "get${name.replaceFirstChar { it.uppercaseChar() }}"
        return target.declaredMethods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
            ?: target.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
    }

    private fun findFieldOn(target: Class<*>, name: String): Field? {
        return try {
            target.getDeclaredField(name)
        } catch (e: NoSuchFieldException) {
            try { target.getField(name) } catch (e2: NoSuchFieldException) { null }
        }
    }
}

// ============================================================================
// JVM Method Proxy
// ============================================================================

/**
 * A callable wrapper around a JVM method.
 *
 * Handles both static methods (receiver = null) and instance methods
 * (receiver = object/companion instance). Implements [Callable] so it
 * integrates directly into KS function call evaluation.
 *
 * @property receiver The object to invoke the method on (null for static)
 * @property method The JVM method
 * @property ownerName Display name of the owning class (for error messages)
 */
class JVMMethodProxy(
    private val receiver: Any?,
    private val method: Method,
    private val ownerName: String
) : Callable {

    val name: String get() = method.name

    init {
        method.isAccessible = true
    }

    override fun call(interpreter: Interpreter, arguments: List<Any?>, location: SourceLocation?): Any? {
        // The proxy was resolved by name only, so [method] may be the wrong
        // overload for these arguments (e.g. KDS.compile(File) when a String
        // was passed). Select among all same-name overloads of the declaring
        // class, mirroring the instance-member resolution in
        // ExpressionEvaluator.buildJavaCallable: exact type match first,
        // then numeric coercion.
        val candidates = overloads(arguments.size)
        if (candidates.isEmpty()) {
            throw TypeError(
                "No overload of $ownerName.${method.name} takes ${arguments.size} argument(s). " +
                        "Available: ${overloads(null).map { "${method.name}(${it.parameterCount} args)" }
                            .distinct().joinToString(", ")}",
                location
            )
        }

        val exact = candidates.firstOrNull { m ->
            arguments.indices.all { i ->
                val arg = arguments[i]
                if (arg == null) !m.parameterTypes[i].isPrimitive
                else isAssignableWithBoxing(m.parameterTypes[i], arg.javaClass)
            }
        }
        if (exact != null) return invoke(exact, arguments, location)

        for (candidate in candidates) {
            val coerced = tryCoerceArgs(arguments, candidate.parameterTypes)
            if (coerced != null) return invoke(candidate, coerced, location)
        }

        throw TypeError(
            "Argument type mismatch calling $ownerName.${method.name}: no overload accepts (" +
                    arguments.joinToString(", ") { it?.javaClass?.simpleName ?: "nil" } + ")",
            location
        )
    }

    /** Same-name overloads of the declaring class with the same static-ness as [method]. */
    private fun overloads(argCount: Int?): List<Method> {
        val declaring = method.declaringClass
        val static = Modifier.isStatic(method.modifiers)
        return (declaring.declaredMethods.toList() + declaring.methods.toList())
            .filter {
                it.name == method.name && Modifier.isStatic(it.modifiers) == static &&
                        !it.isSynthetic && !it.isBridge && (argCount == null || it.parameterCount == argCount)
            }
            .distinctBy { it.parameterTypes.toList() }
            .let { list -> if (list.any { it == method }) listOf(method) + list.filter { it != method } else list }
    }

    private fun invoke(m: Method, args: List<Any?>, location: SourceLocation?): Any? {
        m.isAccessible = true
        return try {
            m.invoke(receiver, *args.toTypedArray())
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw RuntimeError(
                "Error calling $ownerName.${m.name}: ${e.targetException.message}",
                location, e.targetException
            )
        }
    }

    private fun isAssignableWithBoxing(paramType: Class<*>, argType: Class<*>): Boolean {
        if (paramType.isAssignableFrom(argType)) return true
        val boxed = when (paramType) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            else -> paramType
        }
        return boxed.isAssignableFrom(argType)
    }

    /**
     * Find an overload matching the given argument count.
     *
     * JVM methods can have overloads with different parameter counts.
     * This attempts to find the best match from the declaring class.
     */
    fun findOverload(argCount: Int): JVMMethodProxy? {
        val declaring = method.declaringClass
        val overload = declaring.declaredMethods.firstOrNull {
            it.name == method.name && it.parameterCount == argCount
        } ?: declaring.methods.firstOrNull {
            it.name == method.name && it.parameterCount == argCount
        }

        return if (overload != null) JVMMethodProxy(receiver, overload, ownerName) else null
    }

    override fun toString(): String = "<jvm method $ownerName.${method.name}>"
}

// ============================================================================
// Type Coercion Utilities
// ============================================================================

/**
 * Attempt to coerce KS runtime values to match JVM parameter types.
 *
 * KS uses Int, Long, Double, Dec (BigDecimal), etc. JVM methods may expect
 * specific types. This performs safe widening conversions:
 * - Int → Long, Float, Double
 * - Long → Float, Double
 * - Float → Double
 * - String → String (identity)
 * - Dec → BigDecimal (identity, same type)
 *
 * @return A new list with coerced values, or null if coercion is not possible
 */
internal fun tryCoerceArgs(args: List<Any?>, paramTypes: Array<Class<*>>): List<Any?>? {
    if (args.size != paramTypes.size) return null

    val result = mutableListOf<Any?>()
    for (i in args.indices) {
        val arg = args[i]
        val expected = paramTypes[i]

        if (arg == null) {
            if (expected.isPrimitive) return null // can't pass null to primitive
            result.add(null)
            continue
        }

        if (expected.isAssignableFrom(arg.javaClass)) {
            result.add(arg)
            continue
        }

        // Primitive boxing compatibility
        if (expected.isPrimitive || isBoxedPrimitive(expected)) {
            val coerced = coerceNumeric(arg, expected)
            if (coerced != null) {
                result.add(coerced)
                continue
            }
        }

        return null // can't coerce this argument
    }
    return result
}

private fun isBoxedPrimitive(clazz: Class<*>): Boolean = clazz in setOf(
    java.lang.Integer::class.java, java.lang.Long::class.java,
    java.lang.Float::class.java, java.lang.Double::class.java,
    java.lang.Boolean::class.java, java.lang.Character::class.java,
    java.lang.Byte::class.java, java.lang.Short::class.java
)

private fun coerceNumeric(value: Any, target: Class<*>): Any? {
    if (value !is Number) return null
    return when {
        target == Int::class.java || target == java.lang.Integer::class.java -> value.toInt()
        target == Long::class.java || target == java.lang.Long::class.java -> value.toLong()
        target == Float::class.java || target == java.lang.Float::class.java -> value.toFloat()
        target == Double::class.java || target == java.lang.Double::class.java -> value.toDouble()
        target == Short::class.java || target == java.lang.Short::class.java -> value.toShort()
        target == Byte::class.java || target == java.lang.Byte::class.java -> value.toByte()
        else -> null
    }
}

// ============================================================================
// Import Registry
// ============================================================================

/**
 * Registry for managing imports declared via `use` statements.
 *
 * Handles three import modes:
 *
 * 1. **Direct imports** — resolved eagerly at `use` time:
 *    ```ks
 *    use io.kixi.Version              // class import
 *    use io.kixi.kd.Tag as T          // aliased class import
 *    use io.kixi.kd.Tag, Annotation   // multi-import
 *    use MyClass.staticFun             // static member import
 *    ```
 *
 * 2. **Flat wildcard** — resolved lazily on first access:
 *    ```ks
 *    use io.kixi.kd.*    // all types in io.kixi.kd
 *    ```
 *
 * 3. **Tree wildcard** — resolved lazily, searches subpackages:
 *    ```ks
 *    use io.kixi.**      // all types in io.kixi and subpackages
 *    ```
 *
 * ## Resolution Strategy
 *
 * JVM class resolution (when `hostLang=true`) uses `Class.forName()` with
 * lazy caching for wildcards. KS type resolution checks the interpreter's
 * type registries (classes, structs, traits, enums).
 *
 * ## Thread Safety
 *
 * Not thread-safe. Designed for single-threaded interpreter use.
 *
 * ## Crates and script modules
 *
 * Where today's resolution finds nothing, a `use` path is looked up as a module
 * (see Modules.kt): a crate ([Crate]), a script module (a `.ks` file without a
 * crate line) or a plain folder. The path is read as the longest prefix that is a
 * module, then a member of it: `use lab.assays.proteinConc` (one name, or several
 * with commas, each with an optional `as`), `use lab.assays.*` (a crate's or
 * module's public names), `use lab.**` (the public names of crate `lab`, if there
 * is one, and of every crate below it) and `use lab.assays` (the module itself as
 * a namespace, `assays.proteinConc(...)`). Dotted names are looked up under the
 * crate roots; a relative path (`use ./plates/PlateMap`, `use ../shared/Plate`)
 * from the folder of the file it is written in. Everything that resolved before
 * resolves the same way. A crate file or module has a registry of its own
 * ([owner]), so its imports are visible only in that file.
 *
 * @property runtime The runtime configuration (checked for hostLang flag)
 * @property owner The crate file or script module whose imports this registry
 *     holds, or null for the interpreter's own (script) imports
 */
class ImportRegistry internal constructor(
    private val runtime: KSRuntime,
    internal val owner: FileScope?
) {
    /** A registry for a script's imports. */
    constructor(runtime: KSRuntime) : this(runtime, null)

    /** Where crates are found; set by the interpreter (null: crates are not looked up). */
    internal var crates: CrateRegistry? = null

    /** Direct imports: local name (or alias) → resolved target. */
    private val directImports = mutableMapOf<String, ResolvedImport>()

    /** Flat wildcard package paths for lazy resolution. */
    private val flatWildcards = mutableListOf<String>()

    /** Tree wildcard package paths for lazy resolution. */
    private val treeWildcards = mutableListOf<String>()

    /** Cache for wildcard resolutions (name → result, null means "tried and failed"). */
    private val wildcardCache = mutableMapOf<String, ResolvedImport?>()

    /** Discovered subpackages for tree wildcards (lazily populated). */
    private val discoveredSubpackages = mutableSetOf<String>()

    // ========================================================================
    // Registration
    // ========================================================================

    /**
     * Process a [UseDecl] and register the import(s).
     *
     * For direct imports, resolution is eager: failures throw [ImportError].
     * For wildcard imports, the package path is stored for lazy resolution.
     * Crates (see the class notes) are looked up where today's resolution finds
     * nothing; a crate's names are bound when the `use` runs, and loading the
     * crate may throw [CrateError].
     *
     * @param decl The parsed use declaration
     * @param ksClasses KS class registry (for KS type resolution)
     * @param ksStructs KS struct registry
     * @param ksTraits KS trait registry
     * @param ksEnums KS enum registry
     */
    fun processUseDecl(
        decl: UseDecl,
        ksClasses: Map<String, KSClass>,
        ksStructs: Map<String, KSStruct>,
        ksTraits: Map<String, KSTrait>,
        ksEnums: Map<String, KSEnum>
    ) {
        // `use ./plates/PlateMap`, `use ../shared/Plate`: a path from this file's folder
        if (decl.isRelative) {
            processRelativeUse(decl)
            return
        }

        val packagePath = decl.packagePath.joinToString(".")

        // `use ki.<bag>…`: a type bag (io.kixi.types), in every hostLang mode.
        if (decl.packagePath.isEmpty() && decl.imports.singleOrNull()?.name == "ki") {
            throw ImportError("ki", decl.location, suggestion = "Use ki.<bag>, ki.<bag>.* or ki.<bag>.<name>" + loadedBags())
        }
        if (decl.packagePath.firstOrNull() == "ki") {
            processBagUse(decl)
            return
        }

        when (decl.wildcard) {
            UseWildcard.FLAT -> {
                flatWildcards.add(packagePath)
                crates?.let { crates ->
                    // A crate's public names, else a script module's (a plain folder has none)
                    when (val module = crates.moduleAt(decl.packagePath, decl.location, owner)) {
                        is Module.OfCrate -> bindPublicNames(module)
                        is Module.Script -> bindPublicNames(module)
                        else -> {}
                    }
                }
                if (runtime.debugMode) {
                    runtime.outputWriter.println("[DEBUG] use $packagePath.*")
                }
            }

            UseWildcard.TREE -> {
                treeWildcards.add(packagePath)
                if (runtime.hostLang) {
                    discoverSubpackages(packagePath)
                }
                crates?.let { bindCrates(it.under(packagePath, decl.location, owner), "use $packagePath.**", decl.location) }
                if (runtime.debugMode) {
                    runtime.outputWriter.println("[DEBUG] use $packagePath.**")
                }
            }

            UseWildcard.NONE -> {
                for (import in decl.imports) {
                    val localName = import.alias ?: import.name

                    val resolved = resolveDirectImport(
                        packagePath, import.name,
                        ksClasses, ksStructs, ksTraits, ksEnums,
                        decl.location
                    ) ?: crates?.let { crates ->
                        val shown = if (packagePath.isEmpty()) import.name else "$packagePath.${import.name}"
                        resolveModuleImport(crates, decl.packagePath, import.name, decl.location, shown, relative = false) {
                            crates.moduleAt(it, decl.location, owner)
                        }
                    }

                    if (resolved != null) {
                        directImports[localName] = resolved
                    } else {
                        throw ImportError(
                            if (packagePath.isEmpty()) import.name
                            else "$packagePath.${import.name}",
                            decl.location,
                            suggestion = "Check the fully qualified name and ensure the class is on the classpath"
                        )
                    }

                    if (runtime.debugMode) {
                        val alias = if (import.alias != null) " as ${import.alias}" else ""
                        val fqn = if (packagePath.isEmpty()) import.name
                        else "$packagePath.${import.name}"
                        runtime.outputWriter.println("[DEBUG] use $fqn$alias → $localName")
                    }
                }
            }
        }
    }

    /**
     * `use ki.bio` binds the bag `bio` as an object (`bio.revcomp(s)`, `bio.avogadro`);
     * `use ki.bio.*` binds each of its functions and constants; `use ki.bio.revcomp`
     * (or several, comma-separated) binds those; `as` renames; `use ki.*` binds every
     * bag as an object, activating the bags not referred to yet (Ki type bags design, 13). Bags are curated, so this needs no hostLang. Names are
     * bound when the `use` runs (a bag loaded later needs another `use`), and a name the
     * script defines itself shadows an imported one, as in Kotlin.
     */
    private fun processBagUse(decl: UseDecl) {
        val path = decl.packagePath
        val shown = (path + decl.imports.map { it.name }).joinToString(".") + if (decl.wildcard == UseWildcard.NONE) "" else ".*"
        fun bag(name: String) = io.kixi.types.TypeBags.byName(name) ?: throw ImportError("ki.$name", decl.location,
            suggestion = "No type bag '$name' is available" + loadedBags())
        when {
            decl.wildcard == UseWildcard.TREE ->
                throw ImportError("${path.joinToString(".")}.**", decl.location, suggestion = "Use ki.<bag> or ki.<bag>.*")
            decl.wildcard == UseWildcard.FLAT && path.size == 1 -> {
                io.kixi.types.TypeBags.activateAll()
                for (b in io.kixi.types.TypeBags.all) directImports[b.name] = ResolvedImport.KsStaticMember(BagObject(b))
            }
            decl.wildcard == UseWildcard.FLAT && path.size == 2 ->
                for ((k, v) in bagValues(bag(path[1]))) directImports[k] = ResolvedImport.KsStaticMember(v)
            decl.wildcard == UseWildcard.NONE && path.size == 1 ->
                for (i in decl.imports) directImports[i.alias ?: i.name] = ResolvedImport.KsStaticMember(BagObject(bag(i.name)))
            decl.wildcard == UseWildcard.NONE && path.size == 2 -> {
                val b = bag(path[1])
                for (i in decl.imports) {
                    val v = bagValue(b, i.name) ?: throw ImportError("ki.${b.name}.${i.name}", decl.location,
                        suggestion = "Bag '${b.name}' has no function or constant '${i.name}'" +
                                bagValues(b).keys.sorted().let { if (it.isEmpty()) "" else it.joinToString(prefix = " (it has: ", postfix = ")") })
                    directImports[i.alias ?: i.name] = ResolvedImport.KsStaticMember(v)
                }
            }
            else -> throw ImportError(shown, decl.location, suggestion = "Use ki.<bag>, ki.<bag>.* or ki.<bag>.<name>")
        }
    }

    /**
     * Resolves `use <path>.<name>` against modules, after today's resolution found
     * nothing (for a dotted name; a relative path has no earlier resolution). The
     * path is read as the longest prefix that is a module, then a member:
     *
     * 1. [name] in the module [path] names: a crate's declaration (unless it is
     *    `internal` to another crate or `private` to another file), or a script
     *    module's public top-level name;
     * 2. else the module `<path>.<name>` itself, bound as a namespace (a crate, a
     *    script module, or a plain folder);
     * 3. else an error when [path] is a crate or module that lacks [name].
     *
     * Returns null when neither exists, so the caller reports the import.
     *
     * @param relative True for a relative path, whose empty [path] is the folder it
     *     starts in; a dotted use of one name (`use helpers`) has no container
     * @param locate Finds the module a path names (under the crate roots for a
     *     dotted name, below a folder for a relative path)
     * @param shown The import as messages show it (`lab.assays.x`, `../shared/x`)
     * @throws CrateError when the name is not visible from here, or when a crate's
     *     declaration and a module beside it share the name
     * @throws ImportError when the module [path] exists but lacks [name]
     */
    private fun resolveModuleImport(
        crates: CrateRegistry,
        path: List<String>,
        name: String,
        location: SourceLocation?,
        shown: String,
        relative: Boolean,
        locate: (List<String>) -> Module?
    ): ResolvedImport? {
        val container = if (path.isEmpty() && !relative) null else locate(path)
        when (container) {
            is Module.OfCrate -> {
                val crate = container.crate
                crate.declarations[name]?.let { d ->
                    crates.checkModuleClash(crate, name, location)
                    if (d.visibility == io.kixi.ks.parser.Visibility.INTERNAL && owner?.crate !== crate) {
                        throw CrateError("$name is internal to crate ${crate.name}", location, crate.name,
                            suggestion = "Only files of crate ${crate.name} can use it")
                    }
                    return ResolvedImport.CrateMember(d)
                }
                crate.privates[name]?.let { ds ->
                    crates.checkModuleClash(crate, name, location)
                    ds.firstOrNull { it.file === owner }?.let { return ResolvedImport.CrateMember(it) }
                    throw CrateError("$name is private to file ${ds.first().file.display}", location, crate.name,
                        suggestion = "Only that file can use it")
                }
            }
            is Module.Script -> {
                val module = container.module
                if (name in module.exported) return ResolvedImport.ModuleMember(module, name)
                module.notExported(name, location)?.let { throw it }
            }
            else -> {}
        }
        locate(path + name)?.let { return ResolvedImport.KsStaticMember(crates.namespace(it, owner)) }
        when (container) {
            is Module.OfCrate -> throw ImportError(shown, location,
                suggestion = "Crate ${container.crate.name} has no public declaration '$name'" +
                        container.crate.publicNames.let { if (it.isEmpty()) "" else it.joinToString(prefix = " (it has: ", postfix = ")") })
            is Module.Script -> throw ImportError(shown, location,
                suggestion = "Module ${container.module.name} has no public name '$name'" +
                        container.module.publicNames.let { if (it.isEmpty()) "" else it.joinToString(prefix = " (it has: ", postfix = ")") })
            else -> return null
        }
    }

    /**
     * A relative use (`use ./plates/PlateMap`, `use ../shared/Plate, readPlate`,
     * `use ./plates/` then a star, `use ../` then two stars): resolved from the
     * folder of the file it is written in, `../` going up from there but never
     * above its root. One name binds a member or a module (as a dotted use does); a
     * star binds a crate's or module's public names; two stars the public names of
     * the crates in that folder and below it.
     *
     * @throws CrateError when `../` leaves the root, and as resolving a module may
     * @throws ImportError when nothing is found, or a star names a plain folder
     */
    private fun processRelativeUse(decl: UseDecl) {
        val crates = crates ?: throw ImportError(decl.pathText, decl.location)
        val (base, root) = crates.baseOf(owner)
        val start = crates.startFolder(decl, base, root)
        val locate = { parts: List<String> -> crates.moduleIn(start, root, parts, decl.location, owner) }
        val prefix = if (decl.parents == 0) "./" else "../".repeat(decl.parents ?: 0)
        when (decl.wildcard) {
            UseWildcard.FLAT -> when (val module = locate(decl.packagePath)) {
                is Module.OfCrate -> bindPublicNames(module)
                is Module.Script -> bindPublicNames(module)
                is Module.Folder -> throw ImportError(decl.pathText, decl.location,
                    suggestion = "${module.display} is a plain folder, which has no names of its own: use one of its " +
                            "modules (${prefix}${(decl.packagePath + "name").joinToString("/")}), or make it a crate")
                null -> throw ImportError(decl.pathText, decl.location, suggestion = nothingThere(start, decl.packagePath))
            }
            UseWildcard.TREE -> {
                val folder = decl.packagePath.fold(start) { path, segment -> path.resolve(segment) }
                if (!java.nio.file.Files.isDirectory(folder)) {
                    throw ImportError(decl.pathText, decl.location, suggestion = "There is no folder $folder")
                }
                bindCrates(crates.underFolder(folder, root, decl.location, owner), "use ${decl.pathText}", decl.location)
            }
            UseWildcard.NONE -> for (import in decl.imports) {
                val shown = prefix + (decl.packagePath + import.name).joinToString("/")
                val resolved = resolveModuleImport(crates, decl.packagePath, import.name, decl.location, shown, relative = true, locate)
                    ?: throw ImportError(shown, decl.location, suggestion = nothingThere(start, decl.packagePath + import.name))
                directImports[import.alias ?: import.name] = resolved
                if (runtime.debugMode) {
                    runtime.outputWriter.println("[DEBUG] use $shown${import.alias?.let { " as $it" } ?: ""}")
                }
            }
        }
    }

    /** Why a relative path found nothing: what was looked for, and where. */
    private fun nothingThere(start: java.nio.file.Path, parts: List<String>): String {
        val target = parts.fold(start) { path, segment -> path.resolve(segment) }
        return "There is no crate or folder $target, and no module ${target.resolveSibling(target.fileName.toString() + ".ks")}"
    }

    /** Binds the public names of a crate or script module (`use lab.assays.*`, `use ./helpers/` and a star). */
    private fun bindPublicNames(module: Module) {
        when (module) {
            is Module.OfCrate -> for (d in module.crate.declarations.values) {
                if (d.visibility == io.kixi.ks.parser.Visibility.PUBLIC) directImports[d.name] = ResolvedImport.CrateMember(d)
            }
            is Module.Script -> for (name in module.module.exported.keys) {
                directImports[name] = ResolvedImport.ModuleMember(module.module, name)
            }
            is Module.Folder -> {}
        }
    }

    /**
     * Two stars: binds the public names of [found] crates. One name declared by two
     * crates is an error naming both.
     *
     * @param shown The use as messages show it (`use lab.**`)
     */
    private fun bindCrates(found: List<Crate>, shown: String, location: SourceLocation?) {
        val bound = LinkedHashMap<String, CrateDeclaration>()
        for (crate in found) {
            for (d in crate.declarations.values) {
                if (d.visibility != io.kixi.ks.parser.Visibility.PUBLIC) continue
                bound[d.name]?.let { other ->
                    throw CrateError(
                        "$shown: ${d.name} is declared by both crate ${other.file.crate.name} and crate ${crate.name}",
                        location, crate.name,
                        suggestion = "Use the crates one at a time, or rename one with use ${crate.name}.${d.name} as ..."
                    )
                }
                bound[d.name] = d
            }
        }
        for ((name, d) in bound) directImports[name] = ResolvedImport.CrateMember(d)
    }

    /** The bags a script can use, active or not; naming them creates none. */
    private fun loadedBags(): String {
        val names = io.kixi.types.TypeBags.available
        return if (names.isEmpty()) " (no bags are available)" else " (available: ${names.joinToString()})"
    }

    // ========================================================================
    // Resolution
    // ========================================================================

    /**
     * Resolve a simple name against registered imports.
     *
     * Checked by the interpreter as a fallback when a name isn't found in
     * the local environment, function registry, or KS type registries.
     *
     * @param name The simple name to resolve (e.g. "Tag", "parse", "Version")
     * @return The resolved import, or null if not found
     */
    fun resolve(name: String): ResolvedImport? {
        // 1. Check direct imports (O(1))
        directImports[name]?.let { return it }

        // 2. Check wildcard cache
        if (name in wildcardCache) return wildcardCache[name]

        // 3. Try flat wildcards
        for (pkg in flatWildcards) {
            val resolved = tryResolveClass("$pkg.$name")
            if (resolved != null) {
                wildcardCache[name] = resolved
                return resolved
            }
        }

        // 4. Try tree wildcards (base package + discovered subpackages)
        for (basePkg in treeWildcards) {
            // Try base package first
            val resolved = tryResolveClass("$basePkg.$name")
            if (resolved != null) {
                wildcardCache[name] = resolved
                return resolved
            }
            // Try discovered subpackages
            for (subPkg in discoveredSubpackages) {
                if (subPkg.startsWith(basePkg)) {
                    val sub = tryResolveClass("$subPkg.$name")
                    if (sub != null) {
                        wildcardCache[name] = sub
                        return sub
                    }
                }
            }
        }

        // 5. Not found — cache the miss
        wildcardCache[name] = null
        return null
    }

    /**
     * Check if any imports are registered.
     */
    fun hasImports(): Boolean =
        directImports.isNotEmpty() || flatWildcards.isNotEmpty() || treeWildcards.isNotEmpty()

    /**
     * Clear all imports (used by REPL :reset).
     */
    fun clear() {
        directImports.clear()
        flatWildcards.clear()
        treeWildcards.clear()
        wildcardCache.clear()
        discoveredSubpackages.clear()
    }

    /**
     * Get all directly imported names (for REPL :env display).
     */
    fun directImportNames(): Set<String> = directImports.keys.toSet()

    // ========================================================================
    // Private — Direct Import Resolution
    // ========================================================================

    /**
     * Resolve a direct import: packagePath + name.
     *
     * Resolution strategy:
     * 1. Try as a KS type (class, struct, trait, enum)
     * 2. Try as a JVM class (if hostLang=true)
     * 3. Try package as containing a type, with name as a member
     */
    private fun resolveDirectImport(
        packagePath: String,
        name: String,
        ksClasses: Map<String, KSClass>,
        ksStructs: Map<String, KSStruct>,
        ksTraits: Map<String, KSTrait>,
        ksEnums: Map<String, KSEnum>,
        location: SourceLocation?
    ): ResolvedImport? {
        val fqn = if (packagePath.isEmpty()) name else "$packagePath.$name"

        // 1. Try as a KS type by simple name
        //    (KS types are currently not package-qualified, so check by name)
        ksClasses[name]?.let { return ResolvedImport.KsClass(it) }
        ksStructs[name]?.let { return ResolvedImport.KsStruct(it) }
        ksTraits[name]?.let { return ResolvedImport.KsTrait(it) }
        ksEnums[name]?.let { return ResolvedImport.KsEnum(it) }

        // 2. Try as JVM class (only when hostLang is enabled)
        if (runtime.hostLang) {
            val classImport = tryResolveClass(fqn)
            if (classImport != null) return classImport

            // 3. Try packagePath as a class, name as a member
            //    e.g. use io.kixi.Version.parse → class=io.kixi.Version, member=parse
            if (packagePath.isNotEmpty()) {
                val ownerClass = tryLoadClass(packagePath)
                if (ownerClass != null) {
                    val proxy = JVMClassProxy(ownerClass)
                    return resolveMemberImport(proxy, name, location)
                }
            }

            // 4. Try KS type + member
            //    e.g. use MyClass.staticMethod → KS class with static member
            if (packagePath.isNotEmpty()) {
                val lastDot = packagePath.lastIndexOf('.')
                val possibleTypeName = if (lastDot >= 0) packagePath.substring(lastDot + 1)
                else packagePath

                // Check KS classes for static member
                ksClasses[possibleTypeName]?.let { cls ->
                    if (cls.hasStatic(name)) {
                        return ResolvedImport.KsStaticMember(cls.getStatic(name))
                    }
                }
                ksEnums[possibleTypeName]?.let { enum ->
                    enum.getConstant(name)?.let {
                        return ResolvedImport.KsStaticMember(it)
                    }
                }
            }
        }

        return null
    }

    // ========================================================================
    // Private — JVM Class Resolution
    // ========================================================================

    /**
     * Try to resolve a fully qualified name as a JVM class.
     */
    private fun tryResolveClass(fqn: String): ResolvedImport? {
        if (!runtime.hostLang) return null
        val clazz = tryLoadClass(fqn) ?: return null
        return ResolvedImport.JVMClass(JVMClassProxy(clazz))
    }

    /**
     * Try to load a JVM class by fully qualified name.
     */
    private fun tryLoadClass(fqn: String): Class<*>? {
        return try {
            Class.forName(fqn)
        } catch (e: ClassNotFoundException) {
            null
        } catch (e: NoClassDefFoundError) {
            null
        }
    }

    /**
     * Resolve a member import from a JVM class proxy.
     *
     * Checks methods first (returning a callable), then fields/properties
     * (returning the value).
     */
    private fun resolveMemberImport(
        proxy: JVMClassProxy,
        memberName: String,
        location: SourceLocation?
    ): ResolvedImport? {
        val member = proxy.getMember(memberName, location)
        return when (member) {
            is JVMMethodProxy -> ResolvedImport.JVMCallable(member)
            null -> null
            else -> ResolvedImport.JVMMember(member)
        }
    }

    // ========================================================================
    // Private — Subpackage Discovery (for tree wildcards)
    // ========================================================================

    /**
     * Discover subpackages under the given base package.
     *
     * Uses the ClassLoader's resource scanning to find package directories.
     * This is a best-effort scan — it finds packages available on the classpath
     * but may not discover dynamically loaded packages.
     */
    private fun discoverSubpackages(basePackage: String) {
        val basePath = basePackage.replace('.', '/')
        try {
            val classLoader = Thread.currentThread().contextClassLoader ?: return
            val resources = classLoader.getResources(basePath)

            while (resources.hasMoreElements()) {
                val resource = resources.nextElement()
                if (resource.protocol == "file") {
                    scanDirectory(java.io.File(resource.toURI()), basePackage)
                } else if (resource.protocol == "jar") {
                    scanJar(resource, basePath, basePackage)
                }
            }
        } catch (e: Exception) {
            // Best-effort — if scanning fails, tree wildcards degrade to flat
            if (runtime.debugMode) {
                runtime.outputWriter.println("[DEBUG] Subpackage scan failed for $basePackage: ${e.message}")
            }
        }
    }

    private fun scanDirectory(dir: java.io.File, packageName: String) {
        if (!dir.exists() || !dir.isDirectory) return
        discoveredSubpackages.add(packageName)

        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val subPkg = "$packageName.${file.name}"
                scanDirectory(file, subPkg)
            }
        }
    }

    private fun scanJar(resource: java.net.URL, basePath: String, basePackage: String) {
        try {
            val jarPath = resource.path.substringBefore("!")
            val jarUrl = java.net.URL(jarPath)
            val jarFile = java.util.jar.JarFile(java.io.File(jarUrl.toURI()))

            jarFile.use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryName = entry.name
                    if (entryName.startsWith(basePath) && entry.isDirectory) {
                        val pkg = entryName.trimEnd('/').replace('/', '.')
                        discoveredSubpackages.add(pkg)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore jar scanning errors
        }
    }
}