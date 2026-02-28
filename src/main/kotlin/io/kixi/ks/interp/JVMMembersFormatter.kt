package io.kixi.ks.interp

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Parameter
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.lang.reflect.GenericArrayType

/**
 * Formats `.members` output for JVM/Kotlin classes accessed through the import system.
 *
 * Uses Java reflection (with Kotlin-aware heuristics) to produce a human-readable
 * summary of a JVM class's API surface, matching the style of [MembersFormatter]
 * for KS-defined types.
 *
 * ## Output Format
 *
 * ```
 * class StringBuilder
 *   extends Object
 *   implements CharSequence, Appendable, Serializable
 *
 * Constructors:
 *   StringBuilder()
 *   StringBuilder(capacity: Int)
 *   StringBuilder(str: String)
 *
 * Properties:
 *   val length: Int
 *
 * Methods:
 *   fun append(s: String): StringBuilder
 *   fun insert(offset: Int, s: String): StringBuilder
 *   fun delete(start: Int, end: Int): StringBuilder
 *   fun reverse(): StringBuilder
 *   fun toString(): String
 *
 * Static:
 *   ...
 * ```
 *
 * ## Type Name Mapping
 *
 * JVM types are mapped to KS-friendly names where possible:
 * - `java.lang.String` → `String`
 * - `int` / `java.lang.Integer` → `Int`
 * - `java.math.BigDecimal` → `Dec`
 * - `java.util.List` → `List`
 * - etc.
 *
 * ## Filtering
 *
 * Synthetic methods, bridge methods, and internal Kotlin artifacts are excluded.
 * `Object` methods (`wait`, `notify`, `getClass`) are excluded, but commonly
 * overridden methods like `toString`, `equals`, `hashCode` are included.
 *
 * @see MembersFormatter for KS-defined type formatting
 */
object JVMMembersFormatter {

    // ====================================================================
    // Public API
    // ====================================================================

    /**
     * Format `.members` for a JVM class accessed through a [JVMClassProxy].
     *
     * Produces a complete API surface description including constructors,
     * properties, methods, static members, and type hierarchy.
     */
    fun formatClass(proxy: JVMClassProxy): String {
        val clazz = proxy.clazz

        // Prefer curated members when available (e.g., StringBuilder, File, IO types
        // imported via `use` arrive as JVMClassProxy but have curated API surfaces)
        val ksName = mapTypeName(clazz)
        BUILTIN_MEMBERS[ksName]?.let { return it }

        val sb = StringBuilder()

        // Header: class/interface/enum/object keyword + name
        sb.appendLine(formatHeader(clazz, proxy))

        // Type hierarchy: superclass and interfaces
        val superclass = clazz.superclass
        if (superclass != null && superclass != Any::class.java && superclass != Object::class.java) {
            sb.appendLine("  extends ${mapTypeName(superclass)}")
        }

        val interfaces = clazz.interfaces
            .filter { !isInternalInterface(it) }
            .map { mapTypeName(it) }
        if (interfaces.isNotEmpty()) {
            sb.appendLine("  implements ${interfaces.joinToString(", ")}")
        }

        // Constructors (skip for interfaces, enums, objects)
        if (!clazz.isInterface && !proxy.isKotlinObject) {
            val ctors = getPublicConstructors(clazz)
            if (ctors.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Constructors:")
                for (ctor in ctors) {
                    sb.appendLine("  ${formatConstructor(clazz, ctor)}")
                }
            }
        }

        // Enum constants
        if (clazz.isEnum) {
            val constants = clazz.enumConstants
            if (constants != null && constants.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Constants:")
                val names = constants.map { (it as Enum<*>).name }
                sb.appendLine("  ${names.joinToString(", ")}")
            }
        }

        // Properties (public fields + Kotlin property getters)
        val properties = collectProperties(clazz, proxy)
        if (properties.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Properties:")
            for (prop in properties) sb.appendLine("  $prop")
        }

        // Instance methods
        val methods = collectInstanceMethods(clazz, proxy)
        if (methods.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Methods:")
            for (method in methods) sb.appendLine("  $method")
        }

        // Static methods + companion methods
        val statics = collectStaticMembers(clazz, proxy)
        if (statics.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Static:")
            for (line in statics) sb.appendLine("  $line")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Format `.members` for a native Ki type (Email, GeoPoint, Version, etc.)
     * accessed through a [NativeTypeConstructor].
     *
     * Since native types are hand-registered wrappers, we format them using
     * the information available in their static maps and the underlying
     * Kotlin class (if identifiable).
     */
    fun formatNativeType(ctor: NativeTypeConstructor): String {
        // Prefer curated members when available (e.g., Range, Grid, Version
        // are registered as both NativeTypeConstructor and in BUILTIN_MEMBERS)
        BUILTIN_MEMBERS[ctor.typeName]?.let { return it }

        val sb = StringBuilder()
        sb.appendLine("class ${ctor.typeName}")

        // Try to find the underlying Kotlin class for richer reflection
        val underlyingClass = NATIVE_TYPE_CLASSES[ctor.typeName]

        if (underlyingClass != null) {
            val proxy = JVMClassProxy(underlyingClass)

            // Properties from the actual class
            val properties = collectProperties(underlyingClass, proxy)
            if (properties.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Properties:")
                for (prop in properties) sb.appendLine("  $prop")
            }

            // Instance methods from the actual class
            val methods = collectInstanceMethods(underlyingClass, proxy)
            if (methods.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Methods:")
                for (method in methods) sb.appendLine("  $method")
            }

            // Static from companion + statics map
            val statics = collectStaticMembers(underlyingClass, proxy)
            if (statics.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Static:")
                for (line in statics) sb.appendLine("  $line")
            }
        }

        return sb.toString().trimEnd()
    }

    /**
     * Format `.members` for a KS built-in type (String, Int, List, Map, etc.)
     *
     * Built-in types use curated member lists to show the most useful API
     * surface from the KS perspective, rather than raw JVM reflection.
     */
    fun formatBuiltinType(typeName: String): String? {
        return BUILTIN_MEMBERS[typeName]
    }

    // ====================================================================
    // Header Formatting
    // ====================================================================

    private fun formatHeader(clazz: Class<*>, proxy: JVMClassProxy): String {
        val keyword = when {
            proxy.isKotlinObject -> "object"
            clazz.isEnum -> "enum"
            clazz.isInterface -> "interface"
            Modifier.isAbstract(clazz.modifiers) -> "abstract class"
            else -> "class"
        }
        return "$keyword ${clazz.simpleName}"
    }

    // ====================================================================
    // Constructors
    // ====================================================================

    private fun getPublicConstructors(clazz: Class<*>): List<Constructor<*>> {
        return clazz.constructors
            .filter { Modifier.isPublic(it.modifiers) }
            .filter { !isSyntheticConstructor(it) }
            .sortedBy { it.parameterCount }
    }

    private fun isSyntheticConstructor(ctor: Constructor<*>): Boolean {
        if (ctor.isSynthetic) return true
        // Kotlin generates constructors with DefaultConstructorMarker parameter
        val params = ctor.parameterTypes
        return params.isNotEmpty() &&
                params.last().name.contains("DefaultConstructorMarker")
    }

    private fun formatConstructor(clazz: Class<*>, ctor: Constructor<*>): String {
        val params = formatParameters(ctor.parameters, ctor.genericParameterTypes)
        return "${clazz.simpleName}($params)"
    }

    // ====================================================================
    // Properties (Kotlin-aware)
    // ====================================================================

    /**
     * Collect properties from a JVM class.
     *
     * Detection strategy:
     * 1. Kotlin properties: look for getXxx()/isXxx() with matching field
     * 2. Public fields (non-static, non-synthetic)
     *
     * Returns formatted property lines like `val length: Int` or `var count: Int`.
     */
    private fun collectProperties(clazz: Class<*>, proxy: JVMClassProxy): List<String> {
        val props = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // Kotlin properties: find getter methods that correspond to properties
        val allMethods = getAllPublicMethods(clazz)
        for (method in allMethods) {
            if (method.parameterCount != 0) continue
            if (Modifier.isStatic(method.modifiers)) continue

            val propName = extractPropertyName(method) ?: continue
            if (propName in seen) continue
            if (propName in EXCLUDED_PROPERTIES) continue

            val returnType = mapGenericType(method.genericReturnType)
            if (returnType == "Void" || returnType == "Unit") continue

            // Check if there's a setter (var vs val)
            val setterName = "set${propName.replaceFirstChar { it.uppercaseChar() }}"
            val hasSetter = allMethods.any {
                it.name == setterName && it.parameterCount == 1 && !Modifier.isStatic(it.modifiers)
            }

            val binding = if (hasSetter) "var" else "val"
            props.add("$binding $propName: $returnType")
            seen.add(propName)
        }

        // Public instance fields not already covered by getters
        for (field in clazz.fields) {
            if (Modifier.isStatic(field.modifiers)) continue
            if (field.isSynthetic) continue
            val name = field.name
            if (name in seen) continue
            if (name in EXCLUDED_PROPERTIES) continue
            if (name.startsWith("$")) continue // Kotlin internal

            val binding = if (Modifier.isFinal(field.modifiers)) "val" else "var"
            val typeName = mapGenericType(field.genericType)
            props.add("$binding $name: $typeName")
            seen.add(name)
        }

        return props
    }

    /**
     * Extract a property name from a getter method.
     *
     * - `getLength()` → `length`
     * - `isEmpty()` → `isEmpty`
     * - `size()` (no "get" prefix, for collections) → null (handled as method)
     */
    private fun extractPropertyName(method: Method): String? {
        val name = method.name
        return when {
            name.startsWith("get") && name.length > 3 && name[3].isUpperCase() ->
                name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
            name.startsWith("is") && name.length > 2 && name[2].isUpperCase() &&
                    (method.returnType == Boolean::class.java || method.returnType == java.lang.Boolean::class.java) ->
                name  // keep "isEmpty", "isBlank" etc. as-is
            else -> null
        }
    }

    // ====================================================================
    // Instance Methods
    // ====================================================================

    /**
     * Collect instance methods for display.
     *
     * Filters out:
     * - Static methods
     * - Synthetic/bridge methods
     * - Property getters/setters (already shown in Properties)
     * - Object base methods (wait, notify, getClass, finalize)
     * - Internal Kotlin artifacts
     *
     * Includes commonly overridden methods: toString, equals, hashCode, compareTo
     */
    private fun collectInstanceMethods(clazz: Class<*>, proxy: JVMClassProxy): List<String> {
        val methods = mutableListOf<String>()
        val seen = mutableSetOf<String>()  // "name(paramCount)" dedup key

        // Collect property names so we can skip their getters/setters
        val propertyGetters = mutableSetOf<String>()
        val propertySetters = mutableSetOf<String>()
        for (m in getAllPublicMethods(clazz)) {
            if (m.parameterCount == 0 && !Modifier.isStatic(m.modifiers)) {
                val propName = extractPropertyName(m)
                if (propName != null) {
                    propertyGetters.add(m.name)
                    propertySetters.add("set${propName.replaceFirstChar { it.uppercaseChar() }}")
                }
            }
        }

        for (method in getAllPublicMethods(clazz)) {
            if (Modifier.isStatic(method.modifiers)) continue
            if (method.isSynthetic || method.isBridge) continue
            if (method.name in EXCLUDED_METHODS) continue
            if (method.name in propertyGetters) continue
            if (method.name in propertySetters && method.parameterCount == 1) continue
            if (method.name.startsWith("$")) continue  // Kotlin internal
            if (method.name.contains("$")) continue     // Synthetic/internal

            val key = "${method.name}(${method.parameterCount})"
            if (key in seen) continue
            seen.add(key)

            methods.add(formatMethod(method))
        }

        return methods.sorted()
    }

    // ====================================================================
    // Static Members
    // ====================================================================

    /**
     * Collect static members: static methods, static fields, and companion members.
     */
    private fun collectStaticMembers(clazz: Class<*>, proxy: JVMClassProxy): List<String> {
        val statics = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // Java static fields (non-synthetic, non-internal)
        for (field in clazz.declaredFields) {
            if (!Modifier.isStatic(field.modifiers)) continue
            if (!Modifier.isPublic(field.modifiers)) continue
            if (field.isSynthetic) continue
            if (field.name.startsWith("$")) continue
            if (field.name == "INSTANCE") continue  // Kotlin object
            if (field.name == "Companion") continue  // Kotlin companion

            val binding = if (Modifier.isFinal(field.modifiers)) "val" else "var"
            val typeName = mapGenericType(field.genericType)
            statics.add("$binding ${field.name}: $typeName")
            seen.add(field.name)
        }

        // Java static methods (non-synthetic)
        for (method in clazz.declaredMethods) {
            if (!Modifier.isStatic(method.modifiers)) continue
            if (!Modifier.isPublic(method.modifiers)) continue
            if (method.isSynthetic || method.isBridge) continue
            if (method.name.contains("$")) continue

            val key = "${method.name}(${method.parameterCount})"
            if (key in seen) continue
            seen.add(key)

            statics.add(formatMethod(method))
        }

        // Companion object methods (Kotlin)
        if (proxy.companionInstance != null && proxy.companionClass != null) {
            for (method in proxy.companionClass!!.declaredMethods) {
                if (!Modifier.isPublic(method.modifiers)) continue
                if (method.isSynthetic || method.isBridge) continue
                if (method.name.contains("$")) continue
                // Skip property accessors on companion
                if (method.name.startsWith("get") && method.parameterCount == 0) {
                    val propName = method.name.removePrefix("get")
                        .replaceFirstChar { it.lowercaseChar() }
                    if (propName !in seen) {
                        val typeName = mapGenericType(method.genericReturnType)
                        if (typeName != "Void" && typeName != "Unit") {
                            statics.add("val $propName: $typeName")
                            seen.add(propName)
                        }
                    }
                    continue
                }
                if (method.name.startsWith("set") && method.parameterCount == 1) continue

                val key = "${method.name}(${method.parameterCount})"
                if (key in seen) continue
                seen.add(key)

                statics.add(formatMethod(method))
            }
        }

        return statics
    }

    // ====================================================================
    // Method Formatting
    // ====================================================================

    private fun formatMethod(method: Method): String {
        val params = formatParameters(method.parameters, method.genericParameterTypes)
        val returnType = mapGenericType(method.genericReturnType)

        val sb = StringBuilder("fun ${method.name}($params)")
        if (returnType != "Void" && returnType != "Unit") {
            sb.append(": $returnType")
        }
        return sb.toString()
    }

    // ====================================================================
    // Parameter Formatting
    // ====================================================================

    private fun formatParameters(
        params: Array<Parameter>,
        genericTypes: Array<java.lang.reflect.Type>
    ): String {
        return params.indices.joinToString(", ") { i ->
            val param = params[i]
            val genericType = if (i < genericTypes.size) genericTypes[i] else param.type
            val name = if (param.isNamePresent) param.name else "p${i}"
            val typeName = mapGenericType(genericType)
            "$name: $typeName"
        }
    }

    // ====================================================================
    // Type Name Mapping
    // ====================================================================

    /**
     * Map a Java generic type to a KS-friendly type name.
     */
    fun mapGenericType(type: java.lang.reflect.Type): String {
        return when (type) {
            is Class<*> -> mapTypeName(type)
            is ParameterizedType -> {
                val rawName = mapTypeName(type.rawType as Class<*>)
                val args = type.actualTypeArguments.joinToString(", ") { mapGenericType(it) }
                "$rawName<$args>"
            }
            is TypeVariable<*> -> type.name
            is WildcardType -> {
                val upper = type.upperBounds
                val lower = type.lowerBounds
                when {
                    lower.isNotEmpty() -> "in ${mapGenericType(lower[0])}"
                    upper.isNotEmpty() && upper[0] != Any::class.java -> "out ${mapGenericType(upper[0])}"
                    else -> "Any?"
                }
            }
            is GenericArrayType -> "Array<${mapGenericType(type.genericComponentType)}>"
            else -> type.typeName
        }
    }

    /**
     * Map a JVM class to its KS-friendly type name.
     *
     * Resolution order:
     * 1. Check [DIVERGENT_NAMES] for cases where KS name differs from simple name
     * 2. For primitives, check divergent map (covers int→Int, boolean→Bool, etc.)
     * 3. For arrays, recurse on component type
     * 4. Fall back to [Class.getSimpleName] — works for the vast majority of
     *    types where KS name == Java simple name (String, StringBuilder, List,
     *    HashMap, File, BufferedReader, Duration, etc.)
     */
    fun mapTypeName(clazz: Class<*>): String {
        // 1. Check divergent names (small map: ~20 entries)
        DIVERGENT_NAMES[clazz.name]?.let { return it }

        // 2. Primitives are covered by divergent map above, but guard just in case
        if (clazz.isPrimitive) {
            return DIVERGENT_NAMES[clazz.name] ?: clazz.name
        }

        // 3. Arrays
        if (clazz.isArray) {
            val componentType = mapTypeName(clazz.componentType)
            return "Array<$componentType>"
        }

        // 4. Simple name — handles everything else
        return clazz.simpleName
    }

    // ====================================================================
    // Helper Methods
    // ====================================================================

    /**
     * Get all public methods including inherited, excluding Object noise.
     */
    private fun getAllPublicMethods(clazz: Class<*>): List<Method> {
        return clazz.methods
            .filter { Modifier.isPublic(it.modifiers) }
            .filter { !it.isSynthetic && !it.isBridge }
    }

    /**
     * Check if an interface is Kotlin/JVM internal and should be hidden.
     */
    private fun isInternalInterface(iface: Class<*>): Boolean {
        val name = iface.name
        return name.startsWith("kotlin.jvm.internal.") ||
                name == "kotlin.jvm.internal.markers.KMappedMarker" ||
                name.startsWith("kotlin.jvm.internal.markers.")
    }

    // ====================================================================
    // Curated Built-in Type Members
    // ====================================================================

    /**
     * Pre-built `.members` output for KS built-in types.
     *
     * These are curated rather than reflected, to present the most useful
     * API surface from the KS perspective. Inherited JVM noise is excluded,
     * and types use KS naming conventions.
     */
    private val BUILTIN_MEMBERS = mapOf(
        // === String ===
        "String" to """
class String

Properties:
  val size: Int
  val length: Int
  val indices: Range<Int>
  val rex: Regex

Methods:
  fun charAt(index: Int): Char
  fun compareTo(other: String): Int
  fun compareToIgnoreCase(other: String): Int
  fun contains(s: String): Bool
  fun contains(regex: Regex): Bool
  fun endsWith(suffix: String): Bool
  fun equals(other: Any?): Bool
  fun equalsIgnoreCase(other: String): Bool
  fun first(): Char
  fun hashCode(): Int
  fun indexOf(str: String): Int
  fun indexOf(str: String, fromIndex: Int): Int
  fun isBlank(): Bool
  fun isEmpty(): Bool
  fun isNotBlank(): Bool
  fun isNotEmpty(): Bool
  fun last(): Char
  fun lastIndexOf(str: String): Int
  fun lines(): List<String>
  fun lowercase(): String
  fun matches(regex: Regex): Bool
  fun matches(pattern: String): Bool
  fun padEnd(length: Int, padChar: Char = ' '): String
  fun padStart(length: Int, padChar: Char = ' '): String
  fun repeat(n: Int): String
  fun replace(old: String, new: String): String
  fun replace(regex: Regex, replacement: String): String
  fun replaceFirst(old: String, new: String): String
  fun replaceFirst(regex: Regex, replacement: String): String
  fun reversed(): String
  fun split(delimiter: String): List<String>
  fun split(regex: Regex): List<String>
  fun split(delimiter: String, limit: Int): List<String>
  fun startsWith(prefix: String): Bool
  fun substring(beginIndex: Int): String
  fun substring(beginIndex: Int, endIndex: Int): String
  fun toDouble(): Double
  fun toInt(): Int
  fun toLong(): Long
  fun toString(): String
  fun trim(): String
  fun trimEnd(): String
  fun trimStart(): String
  fun uppercase(): String

Note: Additional java.lang.String methods (e.g. codePointAt,
  getBytes, toCharArray) are available via JVM reflection
  when hostLang=true.
        """.trimIndent(),

        // === StringBuilder ===
        "StringBuilder" to """
class StringBuilder
  implements CharSequence, Appendable

Constructors:
  StringBuilder()
  StringBuilder(capacity: Int)
  StringBuilder(str: String)

Properties:
  val length: Int
  val isEmpty: Bool
  val isNotEmpty: Bool

Methods:
  fun append(s: Any?): StringBuilder
  fun append(s: String): StringBuilder
  fun append(c: Char): StringBuilder
  fun append(i: Int): StringBuilder
  fun append(l: Long): StringBuilder
  fun append(d: Double): StringBuilder
  fun appendLine(): StringBuilder
  fun appendLine(s: String): StringBuilder
  fun charAt(index: Int): Char
  fun clear(): StringBuilder
  fun equals(other: Any?): Bool
  fun hashCode(): Int
  fun delete(start: Int, end: Int): StringBuilder
  fun deleteCharAt(index: Int): StringBuilder
  fun indexOf(str: String): Int
  fun insert(offset: Int, s: String): StringBuilder
  fun insert(offset: Int, c: Char): StringBuilder
  fun replace(start: Int, end: Int, str: String): StringBuilder
  fun reverse(): StringBuilder
  fun setCharAt(index: Int, c: Char)
  fun substring(start: Int): String
  fun substring(start: Int, end: Int): String
  fun toString(): String
        """.trimIndent(),

        // === Int ===
        "Int" to """
class Int
  extends Number

Properties:
  val absoluteValue: Int

Methods:
  fun coerceAtLeast(minimum: Int): Int
  fun coerceAtMost(maximum: Int): Int
  fun coerceIn(min: Int, max: Int): Int
  fun compareTo(other: Int): Int
  fun downTo(to: Int): IntRange
  fun equals(other: Any?): Bool
  fun hashCode(): Int
  fun rangeTo(to: Int): IntRange
  fun rangeUntil(to: Int): IntRange
  fun toByte(): Byte
  fun toChar(): Char
  fun toDec(): Dec
  fun toDouble(): Double
  fun toFloat(): Float
  fun toInt(): Int
  fun toLong(): Long
  fun toShort(): Short
  fun toString(): String

Static:
  val MAX_VALUE: Int
  val MIN_VALUE: Int
  val SIZE_BITS: Int
  val SIZE_BYTES: Int
        """.trimIndent(),

        // === Long ===
        "Long" to """
class Long
  extends Number

Properties:
  val absoluteValue: Long

Methods:
  fun coerceAtLeast(minimum: Long): Long
  fun coerceAtMost(maximum: Long): Long
  fun coerceIn(min: Long, max: Long): Long
  fun compareTo(other: Long): Int
  fun downTo(to: Long): LongRange
  fun equals(other: Any?): Bool
  fun hashCode(): Int
  fun rangeTo(to: Long): LongRange
  fun rangeUntil(to: Long): LongRange
  fun toByte(): Byte
  fun toChar(): Char
  fun toDec(): Dec
  fun toDouble(): Double
  fun toFloat(): Float
  fun toInt(): Int
  fun toLong(): Long
  fun toShort(): Short
  fun toString(): String

Static:
  val MAX_VALUE: Long
  val MIN_VALUE: Long
  val SIZE_BITS: Int
  val SIZE_BYTES: Int
        """.trimIndent(),

        // === Float ===
        "Float" to """
class Float
  extends Number

Properties:
  val absoluteValue: Float
  val isFinite: Bool
  val isInfinite: Bool
  val isNaN: Bool

Methods:
  fun coerceAtLeast(minimum: Float): Float
  fun coerceAtMost(maximum: Float): Float
  fun coerceIn(min: Float, max: Float): Float
  fun compareTo(other: Float): Int
  fun equals(other: Any?): Bool
  fun hashCode(): Int
  fun toByte(): Byte
  fun toDec(): Dec
  fun toDouble(): Double
  fun toFloat(): Float
  fun toInt(): Int
  fun toLong(): Long
  fun toShort(): Short
  fun toString(): String

Static:
  val MAX_VALUE: Float
  val MIN_VALUE: Float
  val NaN: Float
  val NEGATIVE_INFINITY: Float
  val POSITIVE_INFINITY: Float
  val SIZE_BITS: Int
  val SIZE_BYTES: Int
        """.trimIndent(),

        // === Double ===
        "Double" to """
class Double
  extends Number

Properties:
  val absoluteValue: Double
  val isFinite: Bool
  val isInfinite: Bool
  val isNaN: Bool

Methods:
  fun coerceAtLeast(minimum: Double): Double
  fun coerceAtMost(maximum: Double): Double
  fun coerceIn(min: Double, max: Double): Double
  fun compareTo(other: Double): Int
  fun equals(other: Any?): Bool
  fun hashCode(): Int
  fun toByte(): Byte
  fun toDec(): Dec
  fun toDouble(): Double
  fun toFloat(): Float
  fun toInt(): Int
  fun toLong(): Long
  fun toShort(): Short
  fun toString(): String

Static:
  val MAX_VALUE: Double
  val MIN_VALUE: Double
  val NaN: Double
  val NEGATIVE_INFINITY: Double
  val POSITIVE_INFINITY: Double
  val SIZE_BITS: Int
  val SIZE_BYTES: Int
        """.trimIndent(),

        // === Dec (BigDecimal) ===
        "Dec" to """
class Dec
  extends Number

Methods:
  fun abs(): Dec
  fun add(augend: Dec): Dec
  fun compareTo(other: Dec): Int
  fun divide(divisor: Dec): Dec
  fun divide(divisor: Dec, scale: Int, roundingMode: RoundingMode): Dec
  fun divideAndRemainder(divisor: Dec): Array<Dec>
  fun equals(other: Any?): Bool
  fun hashCode(): Int
  fun max(other: Dec): Dec
  fun min(other: Dec): Dec
  fun movePointLeft(n: Int): Dec
  fun movePointRight(n: Int): Dec
  fun multiply(multiplicand: Dec): Dec
  fun negate(): Dec
  fun plus(): Dec
  fun pow(n: Int): Dec
  fun remainder(divisor: Dec): Dec
  fun round(mc: MathContext): Dec
  fun scale(): Int
  fun precision(): Int
  fun setScale(newScale: Int): Dec
  fun setScale(newScale: Int, roundingMode: RoundingMode): Dec
  fun signum(): Int
  fun stripTrailingZeros(): Dec
  fun subtract(subtrahend: Dec): Dec
  fun toBigInteger(): BigInteger
  fun toDouble(): Double
  fun toFloat(): Float
  fun toInt(): Int
  fun toLong(): Long
  fun toPlainString(): String
  fun toString(): String
  fun unscaledValue(): BigInteger

Static:
  val ZERO: Dec
  val ONE: Dec
  val TEN: Dec
  fun valueOf(d: Double): Dec
  fun valueOf(unscaledVal: Long, scale: Int): Dec
        """.trimIndent(),

        // === Number ===
        "Number" to """
abstract class Number

Methods:
  fun toByte(): Byte
  fun toDouble(): Double
  fun toFloat(): Float
  fun toInt(): Int
  fun toLong(): Long
  fun toShort(): Short
  fun equals(other: Any?): Bool
  fun hashCode(): Int
  fun toString(): String
        """.trimIndent(),

        // === Bool ===
        "Bool" to """
class Bool

Methods:
  fun compareTo(other: Bool): Int
  fun equals(other: Any?): Bool
  fun hashCode(): Int
  fun toString(): String

Static:
  val TRUE: Bool
  val FALSE: Bool
        """.trimIndent(),

        // === Char ===
        "Char" to """
class Char

Properties:
  val isDigit: Bool
  val isLetter: Bool
  val isLetterOrDigit: Bool
  val isLowerCase: Bool
  val isUpperCase: Bool
  val isWhitespace: Bool

Methods:
  fun compareTo(other: Char): Int
  fun digitToInt(): Int
  fun equals(other: Any?): Bool
  fun hashCode(): Int
  fun lowercase(): String
  fun lowercaseChar(): Char
  fun plus(other: Int): Char
  fun minus(other: Char): Int
  fun rangeTo(other: Char): CharRange
  fun titlecase(): String
  fun titlecaseChar(): Char
  fun toInt(): Int
  fun toString(): String
  fun uppercase(): String
  fun uppercaseChar(): Char

Static:
  val MAX_VALUE: Char
  val MIN_VALUE: Char
  val SIZE_BITS: Int
  val SIZE_BYTES: Int
        """.trimIndent(),

        // === List ===
        "List" to """
class List<E>

Properties:
  val size: Int
  val isEmpty: Bool
  val isNotEmpty: Bool
  val indices: IntRange
  val first: E
  val last: E
  val lastIndex: Int

Methods:
  fun add(element: E): Bool
  fun add(index: Int, element: E)
  fun addAll(elements: List<E>): Bool
  fun clear()
  fun contains(element: E): Bool
  fun containsAll(elements: List<E>): Bool
  fun distinct(): List<E>
  fun drop(n: Int): List<E>
  fun equals(other: Any?): Bool
  fun dropLast(n: Int): List<E>
  fun filter(predicate: (E) -> Bool): List<E>
  fun find(predicate: (E) -> Bool): E?
  fun first(): E
  fun flatMap(transform: (E) -> List<R>): List<R>
  fun flatten(): List<Any?>
  fun forEach(action: (E) -> Unit)
  fun get(index: Int): E
  fun hashCode(): Int
  fun indexOf(element: E): Int
  fun joinToString(separator: String = ", "): String
  fun last(): E
  fun lastIndexOf(element: E): Int
  fun map(transform: (E) -> R): List<R>
  fun remove(element: E): Bool
  fun removeAt(index: Int): E
  fun reversed(): List<E>
  fun set(index: Int, element: E): E
  fun shuffled(): List<E>
  fun slice(indices: IntRange): List<E>
  fun sort()
  fun sorted(): List<E>
  fun sortedDescending(): List<E>
  fun subList(fromIndex: Int, toIndex: Int): List<E>
  fun take(n: Int): List<E>
  fun takeLast(n: Int): List<E>
  fun toList(): List<E>
  fun toMutableList(): List<E>
  fun toSet(): Set<E>
  fun toString(): String
  fun zip(other: List<R>): List<Pair<E, R>>
        """.trimIndent(),

        // === Map ===
        "Map" to """
class Map<K, V>

Properties:
  val size: Int
  val isEmpty: Bool
  val isNotEmpty: Bool
  val keys: Set<K>
  val values: Collection<V>
  val entries: Set<Entry<K, V>>

Methods:
  fun clear()
  fun containsKey(key: K): Bool
  fun containsValue(value: V): Bool
  fun equals(other: Any?): Bool
  fun filter(predicate: (Entry<K, V>) -> Bool): Map<K, V>
  fun forEach(action: (K, V) -> Unit)
  fun get(key: K): V?
  fun getOrDefault(key: K, defaultValue: V): V
  fun getOrElse(key: K, defaultValue: () -> V): V
  fun hashCode(): Int
  fun keys(): Set<K>
  fun map(transform: (Entry<K, V>) -> R): List<R>
  fun mapKeys(transform: (Entry<K, V>) -> K2): Map<K2, V>
  fun mapValues(transform: (Entry<K, V>) -> V2): Map<K, V2>
  fun plus(other: Map<K, V>): Map<K, V>
  fun put(key: K, value: V): V?
  fun putAll(from: Map<K, V>)
  fun putIfAbsent(key: K, value: V): V?
  fun remove(key: K): V?
  fun replace(key: K, value: V): V?
  fun toList(): List<Pair<K, V>>
  fun toMap(): Map<K, V>
  fun toMutableMap(): Map<K, V>
  fun toString(): String
  fun values(): Collection<V>
        """.trimIndent(),

        // === Range ===
        "Range" to """
class Range<T>

Properties:
  val start: T?
  val end: T?
  val isClosed: Bool
  val isOpen: Bool
  val isOpenStart: Bool
  val isOpenEnd: Bool
  val isExclusive: Bool
  val isEmpty: Bool

Methods:
  fun contains(value: T): Bool
  fun equals(other: Any?): Bool
  fun hashCode(): Int
  fun overlaps(other: Range<T>): Bool
  fun toString(): String
        """.trimIndent(),

        // === IO: File ===
        "File" to """
class File
  implements Comparable<File>

Constructors:
  File(pathname: String)
  File(parent: String, child: String)
  File(parent: File, child: String)

Properties:
  val name: String
  val path: String
  val absolutePath: String
  val canonicalPath: String
  val parent: String?
  val parentFile: File?
  val isAbsolute: Bool
  val isDirectory: Bool
  val isFile: Bool
  val isHidden: Bool
  val exists: Bool
  val canRead: Bool
  val canWrite: Bool
  val canExecute: Bool
  val length: Long
  val lastModified: Long
  val freeSpace: Long
  val totalSpace: Long
  val usableSpace: Long

Methods:
  fun compareTo(other: File): Int
  fun createNewFile(): Bool
  fun delete(): Bool
  fun deleteOnExit()
  fun list(): Array<String>?
  fun listFiles(): Array<File>?
  fun mkdir(): Bool
  fun mkdirs(): Bool
  fun readText(charset: String = "UTF-8"): String
  fun readLines(charset: String = "UTF-8"): List<String>
  fun readBytes(): ByteArray
  fun renameTo(dest: File): Bool
  fun setLastModified(time: Long): Bool
  fun setReadOnly(): Bool
  fun setWritable(writable: Bool): Bool
  fun setReadable(readable: Bool): Bool
  fun setExecutable(executable: Bool): Bool
  fun toPath(): Path
  fun toURI(): URI
  fun toString(): String
  fun writeText(text: String, charset: String = "UTF-8")
  fun writeBytes(data: ByteArray)
  fun appendText(text: String, charset: String = "UTF-8")

Static:
  val separator: String
  val separatorChar: Char
  val pathSeparator: String
  val pathSeparatorChar: Char
  fun createTempFile(prefix: String, suffix: String?): File
  fun listRoots(): Array<File>
        """.trimIndent(),

        // === IO: BufferedReader ===
        "BufferedReader" to """
class BufferedReader
  extends Reader
  implements Closeable, AutoCloseable

Constructors:
  BufferedReader(reader: Reader)
  BufferedReader(reader: Reader, bufferSize: Int)

Methods:
  fun close()
  fun lines(): Stream<String>
  fun mark(readAheadLimit: Int)
  fun markSupported(): Bool
  fun read(): Int
  fun read(cbuf: Array<Char>, off: Int, len: Int): Int
  fun readLine(): String?
  fun readLines(): List<String>
  fun ready(): Bool
  fun reset()
  fun skip(n: Long): Long
        """.trimIndent(),

        // === IO: BufferedWriter ===
        "BufferedWriter" to """
class BufferedWriter
  extends Writer
  implements Closeable, AutoCloseable, Flushable

Constructors:
  BufferedWriter(writer: Writer)
  BufferedWriter(writer: Writer, bufferSize: Int)

Methods:
  fun close()
  fun flush()
  fun newLine()
  fun write(c: Int)
  fun write(str: String)
  fun write(str: String, off: Int, len: Int)
  fun write(cbuf: Array<Char>, off: Int, len: Int)
        """.trimIndent(),

        // === IO: PrintWriter ===
        "PrintWriter" to """
class PrintWriter
  extends Writer
  implements Closeable, AutoCloseable, Flushable

Constructors:
  PrintWriter(out: Writer)
  PrintWriter(out: Writer, autoFlush: Bool)
  PrintWriter(out: OutputStream)
  PrintWriter(out: OutputStream, autoFlush: Bool)
  PrintWriter(fileName: String)
  PrintWriter(file: File)

Methods:
  fun append(c: Char): PrintWriter
  fun append(csq: CharSequence): PrintWriter
  fun checkError(): Bool
  fun close()
  fun flush()
  fun format(format: String, vararg args: Any?): PrintWriter
  fun print(s: Any?)
  fun print(s: String)
  fun print(b: Bool)
  fun print(i: Int)
  fun print(l: Long)
  fun print(d: Double)
  fun print(c: Char)
  fun printf(format: String, vararg args: Any?): PrintWriter
  fun println()
  fun println(s: Any?)
  fun println(s: String)
  fun println(b: Bool)
  fun println(i: Int)
  fun println(l: Long)
  fun println(d: Double)
  fun write(s: String)
  fun write(c: Int)
  fun write(cbuf: Array<Char>)
        """.trimIndent(),

        // === IO: InputStream (abstract base) ===
        "InputStream" to """
abstract class InputStream
  implements Closeable, AutoCloseable

Methods:
  fun available(): Int
  fun close()
  fun mark(readLimit: Int)
  fun markSupported(): Bool
  fun read(): Int
  fun read(b: ByteArray): Int
  fun read(b: ByteArray, off: Int, len: Int): Int
  fun readAllBytes(): ByteArray
  fun readNBytes(len: Int): ByteArray
  fun readNBytes(b: ByteArray, off: Int, len: Int): Int
  fun reset()
  fun skip(n: Long): Long
  fun transferTo(out: OutputStream): Long

Static:
  fun nullInputStream(): InputStream
        """.trimIndent(),

        // === IO: OutputStream (abstract base) ===
        "OutputStream" to """
abstract class OutputStream
  implements Closeable, AutoCloseable, Flushable

Methods:
  fun close()
  fun flush()
  fun write(b: Int)
  fun write(b: ByteArray)
  fun write(b: ByteArray, off: Int, len: Int)

Static:
  fun nullOutputStream(): OutputStream
        """.trimIndent(),

        // === IO: FileInputStream ===
        "FileInputStream" to """
class FileInputStream
  extends InputStream
  implements Closeable, AutoCloseable

Constructors:
  FileInputStream(name: String)
  FileInputStream(file: File)

Methods:
  fun available(): Int
  fun close()
  fun read(): Int
  fun read(b: ByteArray): Int
  fun read(b: ByteArray, off: Int, len: Int): Int
  fun skip(n: Long): Long
        """.trimIndent(),

        // === IO: FileOutputStream ===
        "FileOutputStream" to """
class FileOutputStream
  extends OutputStream
  implements Closeable, AutoCloseable, Flushable

Constructors:
  FileOutputStream(name: String)
  FileOutputStream(name: String, append: Bool)
  FileOutputStream(file: File)
  FileOutputStream(file: File, append: Bool)

Methods:
  fun close()
  fun flush()
  fun write(b: Int)
  fun write(b: ByteArray)
  fun write(b: ByteArray, off: Int, len: Int)
        """.trimIndent(),

        // === IO: InputStreamReader (bridges bytes to chars with charset) ===
        "InputStreamReader" to """
class InputStreamReader
  extends Reader
  implements Closeable, AutoCloseable

Constructors:
  InputStreamReader(in: InputStream)
  InputStreamReader(in: InputStream, charsetName: String)

Properties:
  val encoding: String

Methods:
  fun close()
  fun read(): Int
  fun read(cbuf: Array<Char>, off: Int, len: Int): Int
  fun ready(): Bool
        """.trimIndent(),

        // === IO: OutputStreamWriter (bridges chars to bytes with charset) ===
        "OutputStreamWriter" to """
class OutputStreamWriter
  extends Writer
  implements Closeable, AutoCloseable, Flushable

Constructors:
  OutputStreamWriter(out: OutputStream)
  OutputStreamWriter(out: OutputStream, charsetName: String)

Properties:
  val encoding: String

Methods:
  fun close()
  fun flush()
  fun write(c: Int)
  fun write(str: String, off: Int, len: Int)
  fun write(cbuf: Array<Char>, off: Int, len: Int)
        """.trimIndent(),

        // === IO: FileReader (convenience: File -> BufferedReader) ===
        "FileReader" to """
class FileReader
  extends InputStreamReader
  implements Closeable, AutoCloseable

Constructors:
  FileReader(fileName: String)
  FileReader(file: File)

Methods:
  fun close()
  fun read(): Int
  fun read(cbuf: Array<Char>, off: Int, len: Int): Int
  fun ready(): Bool
        """.trimIndent(),

        // === IO: FileWriter (convenience: File -> BufferedWriter) ===
        "FileWriter" to """
class FileWriter
  extends OutputStreamWriter
  implements Closeable, AutoCloseable, Flushable

Constructors:
  FileWriter(fileName: String)
  FileWriter(fileName: String, append: Bool)
  FileWriter(file: File)
  FileWriter(file: File, append: Bool)

Methods:
  fun close()
  fun flush()
  fun write(c: Int)
  fun write(str: String, off: Int, len: Int)
  fun write(cbuf: Array<Char>, off: Int, len: Int)
        """.trimIndent(),

        // === IO: ByteArrayInputStream ===
        "ByteArrayInputStream" to """
class ByteArrayInputStream
  extends InputStream
  implements Closeable, AutoCloseable

Constructors:
  ByteArrayInputStream(buf: ByteArray)
  ByteArrayInputStream(buf: ByteArray, offset: Int, length: Int)

Methods:
  fun available(): Int
  fun close()
  fun mark(readLimit: Int)
  fun markSupported(): Bool
  fun read(): Int
  fun read(b: ByteArray, off: Int, len: Int): Int
  fun reset()
  fun skip(n: Long): Long
        """.trimIndent(),

        // === IO: ByteArrayOutputStream ===
        "ByteArrayOutputStream" to """
class ByteArrayOutputStream
  extends OutputStream
  implements Closeable, AutoCloseable, Flushable

Constructors:
  ByteArrayOutputStream()
  ByteArrayOutputStream(size: Int)

Methods:
  fun close()
  fun flush()
  fun reset()
  fun size(): Int
  fun toByteArray(): ByteArray
  fun toString(): String
  fun toString(charsetName: String): String
  fun write(b: Int)
  fun write(b: ByteArray, off: Int, len: Int)
  fun writeTo(out: OutputStream)
        """.trimIndent(),

        // === IO: DataInputStream ===
        "DataInputStream" to """
class DataInputStream
  extends InputStream
  implements Closeable, AutoCloseable, DataInput

Constructors:
  DataInputStream(in: InputStream)

Methods:
  fun close()
  fun read(): Int
  fun read(b: ByteArray, off: Int, len: Int): Int
  fun readBoolean(): Bool
  fun readByte(): Byte
  fun readChar(): Char
  fun readDouble(): Double
  fun readFloat(): Float
  fun readFully(b: ByteArray)
  fun readFully(b: ByteArray, off: Int, len: Int)
  fun readInt(): Int
  fun readLong(): Long
  fun readShort(): Short
  fun readUTF(): String
  fun readUnsignedByte(): Int
  fun readUnsignedShort(): Int
  fun skipBytes(n: Int): Int
        """.trimIndent(),

        // === IO: DataOutputStream ===
        "DataOutputStream" to """
class DataOutputStream
  extends OutputStream
  implements Closeable, AutoCloseable, Flushable, DataOutput

Constructors:
  DataOutputStream(out: OutputStream)

Properties:
  val size: Int

Methods:
  fun close()
  fun flush()
  fun write(b: Int)
  fun write(b: ByteArray, off: Int, len: Int)
  fun writeBoolean(v: Bool)
  fun writeByte(v: Int)
  fun writeBytes(s: String)
  fun writeChar(v: Int)
  fun writeChars(s: String)
  fun writeDouble(v: Double)
  fun writeFloat(v: Float)
  fun writeInt(v: Int)
  fun writeLong(v: Long)
  fun writeShort(v: Int)
  fun writeUTF(str: String)
        """.trimIndent(),

        // === IO: BufferedInputStream ===
        "BufferedInputStream" to """
class BufferedInputStream
  extends InputStream
  implements Closeable, AutoCloseable

Constructors:
  BufferedInputStream(in: InputStream)
  BufferedInputStream(in: InputStream, size: Int)

Methods:
  fun available(): Int
  fun close()
  fun mark(readLimit: Int)
  fun markSupported(): Bool
  fun read(): Int
  fun read(b: ByteArray, off: Int, len: Int): Int
  fun reset()
  fun skip(n: Long): Long
        """.trimIndent(),

        // === IO: BufferedOutputStream ===
        "BufferedOutputStream" to """
class BufferedOutputStream
  extends OutputStream
  implements Closeable, AutoCloseable, Flushable

Constructors:
  BufferedOutputStream(out: OutputStream)
  BufferedOutputStream(out: OutputStream, size: Int)

Methods:
  fun close()
  fun flush()
  fun write(b: Int)
  fun write(b: ByteArray, off: Int, len: Int)
        """.trimIndent(),

        // === Regex ===
        "Regex" to """
class Regex

Properties:
  val pattern: String
  val options: Set<RegexOption>

Methods:
  fun containsMatchIn(input: String): Bool
  fun equals(other: Any?): Bool
  fun find(input: String): MatchResult?
  fun find(input: String, startIndex: Int): MatchResult?
  fun findAll(input: String): Sequence<MatchResult>
  fun findAll(input: String, startIndex: Int): Sequence<MatchResult>
  fun hashCode(): Int
  fun matchEntire(input: String): MatchResult?
  fun matches(input: String): Bool
  fun replace(input: String, replacement: String): String
  fun replaceFirst(input: String, replacement: String): String
  fun split(input: String): List<String>
  fun split(input: String, limit: Int): List<String>
  fun toString(): String
  fun toPattern(): Pattern
        """.trimIndent(),

        // === IO ===
        "IO" to """
object IO

Static:
  fun read(file: File, encoding: String = "UTF-8"): String
  fun read(path: Path, encoding: String = "UTF-8"): String
  fun read(location: String, encoding: String = "UTF-8"): String
  fun read(input: InputStream, encoding: String = "UTF-8"): String
  fun read(reader: Reader, encoding: String = "UTF-8"): String
  fun readBytes(file: File): ByteArray
  fun readBytes(path: Path): ByteArray
  fun readBytes(location: String): ByteArray
  fun readBytes(input: InputStream): ByteArray
  fun readKD(file: File): Tag
  fun readKD(path: Path): Tag
  fun readKD(location: String): Tag
  fun write(text: String, file: File, encoding: String = "UTF-8")
  fun write(text: String, path: Path, encoding: String = "UTF-8")
  fun write(text: String, location: String, encoding: String = "UTF-8")
  fun write(text: String, output: OutputStream, encoding: String = "UTF-8")
  fun write(text: String, writer: Writer, encoding: String = "UTF-8")
  fun writeBytes(data: ByteArray, file: File)
  fun writeBytes(data: ByteArray, path: Path)
  fun writeBytes(data: ByteArray, location: String)
  fun writeBytes(data: ByteArray, output: OutputStream)
  fun writeKD(tag: Tag, file: File)
  fun writeKD(tag: Tag, path: Path)
  fun writeKD(tag: Tag, location: String)
        """.trimIndent()
    )

    // ====================================================================
    // Constants & Mappings
    // ====================================================================

    /**
     * Divergent name map — only entries where the KS name differs from
     * the Java simple class name. Everything else uses [Class.getSimpleName].
     *
     * This keeps the map small and avoids loading unnecessary state.
     */
    private val DIVERGENT_NAMES = mapOf(
        // Wrappers whose KS name differs from Java simple name
        "java.lang.Integer"   to "Int",
        "java.lang.Boolean"   to "Bool",
        "java.lang.Character" to "Char",
        "java.lang.Object"    to "Any",
        "java.lang.Void"      to "Void",
        "java.math.BigDecimal" to "Dec",

        // Ki Date mapping
        "java.time.LocalDate" to "Date",

        // Primitives (boxed name form used by Class.getName())
        "int"     to "Int",
        "long"    to "Long",
        "float"   to "Float",
        "double"  to "Double",
        "boolean" to "Bool",
        "char"    to "Char",
        "byte"    to "Byte",
        "short"   to "Short",
        "void"    to "Void"
    )

    /** Map of native Ki type names to their underlying JVM classes. */
    private val NATIVE_TYPE_CLASSES = mapOf<String, Class<*>>(
        "Email" to io.kixi.Email::class.java,
        "GeoPoint" to io.kixi.GeoPoint::class.java,
        "Version" to io.kixi.Version::class.java,
        "Blob" to io.kixi.Blob::class.java,
        "NSID" to io.kixi.NSID::class.java,
        "Call" to io.kixi.Call::class.java,
        "Grid" to io.kixi.Grid::class.java,
        "Coordinate" to io.kixi.Coordinate::class.java,
        "Range" to io.kixi.Range::class.java,
        "Quantity" to io.kixi.uom.Quantity::class.java,
        "Currency" to io.kixi.uom.Currency::class.java,
        "Tag" to io.kixi.kd.Tag::class.java
    )

    /**
     * Object methods to exclude from instance method listing.
     *
     * Note: equals, hashCode, and toString are intentionally NOT excluded —
     * they are essential for comparison, hashing, serialization, and debugging.
     */
    private val EXCLUDED_METHODS = setOf(
        "wait", "notify", "notifyAll", "getClass", "finalize", "clone",
        // Kotlin internals
        "access\$getLength\$p", "component1", "component2", "component3",
        "component4", "component5"
    )

    /** Properties to exclude from listing. */
    private val EXCLUDED_PROPERTIES = setOf(
        "class"  // getClass() → "class" property is noise
    )
}