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
        // Try with original arguments
        return try {
            method.invoke(receiver, *arguments.toTypedArray())
        } catch (e: IllegalArgumentException) {
            // Try with type coercion
            val coerced = tryCoerceArgs(arguments, method.parameterTypes)
            if (coerced != null) {
                try {
                    method.invoke(receiver, *coerced.toTypedArray())
                } catch (e2: java.lang.reflect.InvocationTargetException) {
                    throw RuntimeError(
                        "Error calling $ownerName.${method.name}: ${e2.targetException.message}",
                        location, e2.targetException
                    )
                }
            } else {
                throw TypeError(
                    "Argument type mismatch calling $ownerName.${method.name}: ${e.message}",
                    location
                )
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw RuntimeError(
                "Error calling $ownerName.${method.name}: ${e.targetException.message}",
                location, e.targetException
            )
        }
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
 * @property runtime The runtime configuration (checked for hostLang flag)
 */
class ImportRegistry(private val runtime: KSRuntime) {

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
     * For direct imports, resolution is eager — failures throw [ImportError].
     * For wildcard imports, the package path is stored for lazy resolution.
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
        val packagePath = decl.packagePath.joinToString(".")

        when (decl.wildcard) {
            UseWildcard.FLAT -> {
                flatWildcards.add(packagePath)
                if (runtime.debugMode) {
                    runtime.outputWriter.println("[DEBUG] use $packagePath.*")
                }
            }

            UseWildcard.TREE -> {
                treeWildcards.add(packagePath)
                if (runtime.hostLang) {
                    discoverSubpackages(packagePath)
                }
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
                    )

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