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
import io.kixi.ks.parser.*
import io.kixi.uom.Currency
import io.kixi.uom.Quantity
import io.kixi.uom.Unit as KiUnit
import io.kixi.uom.combineUnits

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.math.BigDecimal as Dec
import java.math.RoundingMode
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * Interpreter operations: arithmetic, type checking, member access, and utilities.
 *
 * This class contains the "leaf" operations that the interpreter delegates to.
 * It handles:
 *
 * - **Arithmetic**: type-preserving add/subtract/multiply/divide/modulo/power,
 *   quantity scalar ops, unit composition (⚭)
 * - **Comparison & equality**: compare, isEqual, isTruthy
 * - **Type conversions**: stringify, toDouble, toInt, toLong, toFloat, toBigDecimal
 * - **Type system**: checkType, castTo, runtimeTypeName, isTypeCompatible
 * - **Constraint & null safety checking**: checkConstraint, checkNullSafety,
 *   checkTypeCompatibility
 * - **Built-in member access**: String, List, Map, Range, Regex, Enum, Quantity,
 *   Version, Grid, Coordinate, Tag (io.kixi.kd.Tag)
 * - **Iteration support**: toIterable, rangeToIterable
 * - **Reflection**: getKSType
 *
 * Follows the same delegation pattern as the Parser's sub-parsers (ExpressionParser,
 * TypeParser, etc.) — holds a reference to the parent [Interpreter] for access to
 * shared state and the core evaluate() method.
 *
 * @param interp Reference to the parent [Interpreter] for state access.
 */
class InterpreterOps(internal val interp: Interpreter) {

    // ========================================================================
    // Arithmetic Operations (type-preserving)
    // ========================================================================

    internal fun add(left: Any?, right: Any?): Any {
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
        // List + element -> new list with element appended
        if (left is List<*>) {
            val result = left.toMutableList()
            result.add(right)
            return result
        }
        return numericOp(left, right, "add")
    }

    internal fun subtract(left: Any?, right: Any?): Any {
        // Quantity - Quantity (with unit conversion)
        if (left is Quantity<*> && right is Quantity<*>) {
            @Suppress("UNCHECKED_CAST")
            return (left as Quantity<KiUnit>) - (right as Quantity<KiUnit>)
        }
        // Quantity - Number (scalar subtraction)
        if (left is Quantity<*> && right is Number) {
            return quantityScalarOp(left, right, "subtract")
        }
        // List - element -> new list without first occurrence of element
        if (left is List<*>) {
            val result = left.toMutableList()
            result.remove(right)
            return result
        }
        return numericOp(left, right, "subtract")
    }

    internal fun multiply(left: Any?, right: Any?): Any {
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

    internal fun divide(left: Any?, right: Any?): Any {
        // Quantity / Number (scalar division)
        if (left is Quantity<*> && right is Number) {
            return quantityScalarOp(left, right, "divide")
        }
        return numericOp(left, right, "divide")
    }

    internal fun modulo(left: Any?, right: Any?): Any {
        // Quantity % Number (scalar modulo)
        if (left is Quantity<*> && right is Number) {
            return quantityScalarOp(left, right, "modulo")
        }
        return numericOp(left, right, "modulo")
    }

    internal fun power(left: Any?, right: Any?): Any {
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

    internal fun negate(value: Any?): Any {
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

    internal fun numericOp(left: Any?, right: Any?, op: String): Any {
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

    // ========================================================================
    // Quantity Scalar Operations
    // ========================================================================

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
    internal fun quantityScalarOp(quantity: Quantity<*>, scalar: Number, op: String): Quantity<*> {
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

    /**
     * Multiply two Number values preserving appropriate types.
     * Used by the combine (\u2695) operator for unit composition.
     */
    internal fun multiplyNumbers(a: Number, b: Number): Number {
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
    // Combine (\u2695) Operator
    // ========================================================================

    /**
     * Evaluate the unit composition operator \u2695.
     *
     * Combines two quantities into a higher-dimensional unit:
     * - Length \u00d7 Length \u2192 Area:   `4cm \u2695 3cm \u2192 12cm\u00b2`
     * - Length \u00d7 Area \u2192 Volume:  `2m \u2695 3m\u00b2 \u2192 6m\u00b3`
     * - Area \u00d7 Length \u2192 Volume:  `3m\u00b2 \u2695 2m \u2192 6m\u00b3`
     *
     * If units match, the combination is direct. If units differ within the
     * same dimension (e.g., m and cm), both are converted to base units first.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun evaluateCombine(left: Any?, right: Any?, location: SourceLocation?): Quantity<*> {
        if (left !is Quantity<*> || right !is Quantity<*>) {
            throw TypeError(
                "Combine operator \u2695 requires quantity operands, got " +
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
                        "(supported: Length\u00d7Length\u2192Area, Length\u00d7Area\u2192Volume)",
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

    // ========================================================================
    // Compound Assignment
    // ========================================================================

    internal fun computeAssignment(op: AssignOp, getCurrent: () -> Any?, newValue: Any?): Any? {
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
    // Comparison & Equality
    // ========================================================================

    internal fun compare(left: Any?, right: Any?): Int {
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

    internal fun isEqual(left: Any?, right: Any?): Boolean {
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
    internal fun copyIfStruct(value: Any?): Any? {
        return if (value is KSStructInstance) value.copy() else value
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

            // ============================================================================
            // 8. UPDATE stringify() for new types
            // ============================================================================

            is JVMClassProxy -> value.toString()    // "class Version"
            is JVMMethodProxy -> value.toString()   // "<jvm method Version.parse>"

            is URL -> value.toString()
            is List<*> -> value.joinToString(", ", "[", "]") { stringify(it) }
            is Map<*, *> -> value.entries.joinToString(", ", "[", "]") { "${stringify(it.key)}=${stringify(it.value)}" }
            is KSEnumConstant -> value.name
            is KSObject -> value.toString()
            is KSStructInstance -> value.toString()
            is KiTag -> value.toString()
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
    internal fun stringifyVersion(v: Version): String {
        var text = "${v.major}.${v.minor}.${v.micro}"
        if (v.qualifier.isNotEmpty()) {
            text += "_${v.qualifier}"
            if (v.qualifierNumber != 0) {
                text += "_${v.qualifierNumber}"
            }
        }
        return text
    }

    internal fun stringifyLocalDateTime(dt: LocalDateTime): String {
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

    internal fun stringifyOffsetDateTime(odt: OffsetDateTime): String {
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

    internal fun toDouble(value: Any?): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
                ?: throw TypeError("Cannot convert '$value' to number")
            is KSEnumConstant -> value.ordinal.toDouble()
            else -> throw TypeError("Expected number, got ${value?.let { it::class.simpleName } ?: "nil"}")
        }
    }

    internal fun toInt(value: Any?, location: SourceLocation?): Int {
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

    internal fun toLong(value: Any?, location: SourceLocation?): Long {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
                ?: throw TypeError("Cannot convert '$value' to Long", location)
            else -> throw TypeError("Expected Long, got ${value?.let { it::class.simpleName } ?: "nil"}", location)
        }
    }

    internal fun toFloat(value: Any?, location: SourceLocation?): Float {
        return when (value) {
            is Float -> value
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull()
                ?: throw TypeError("Cannot convert '$value' to Float", location)
            else -> throw TypeError("Expected Float, got ${value?.let { it::class.simpleName } ?: "nil"}", location)
        }
    }

    internal fun toBigDecimal(value: Any?): Dec {
        return when (value) {
            is Dec -> value
            is Number -> Dec(value.toDouble())
            is String -> Dec(value)
            else -> throw TypeError("Cannot convert to BigDecimal")
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
    internal fun stringifyGrid(grid: Grid<*>): String {
        // Build type annotation string
        // Show type when elementType is set (and not Any -- Any/Any? grids don't display the type)
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

    // ========================================================================
    // Grid Helpers
    // ========================================================================

    /**
     * Resolve a grid type parameter name to a JVM class.
     *
     * Maps KS type names to their native JVM classes since KS uses
     * native types directly (no wrapping).
     */
    internal fun resolveGridElementType(typeRef: TypeRef, location: SourceLocation?): Class<*> {
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
    internal fun isGridValueCompatible(value: Any, expectedType: Class<*>): Boolean {
        if (expectedType == Any::class.java) return true
        if (expectedType == Number::class.java) return value is Number
        return expectedType.isInstance(value)
    }

    /**
     * Map a JVM class back to a KS type name for error messages.
     */
    internal fun gridElementTypeName(type: Class<*>): String = when (type) {
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
     * Returns the default zero-value for known KS primitive grid element types,
     * or null if the type has no built-in default (requiring the user to supply
     * one via `default = ...`).
     *
     *     Int    -> 0          Long   -> 0L         Float  -> 0.0f
     *     Double -> 0.0        Dec    -> BigDecimal.ZERO
     *     String -> ""         Bool   -> false       Char   -> '\u0000'
     */
    internal fun defaultValueForGridType(type: Class<*>): Any? = when (type) {
        Int::class.java, java.lang.Integer::class.java -> 0
        Long::class.java, java.lang.Long::class.java -> 0L
        Float::class.java, java.lang.Float::class.java -> 0.0f
        Double::class.java, java.lang.Double::class.java -> 0.0
        java.math.BigDecimal::class.java -> java.math.BigDecimal.ZERO
        String::class.java -> ""
        Boolean::class.java, java.lang.Boolean::class.java -> false
        Char::class.java, java.lang.Character::class.java -> '\u0000'
        else -> null
    }

    // ========================================================================
    // Reflection
    // ========================================================================

    /**
     * Get the KS type representation of a value.
     */
    internal fun getKSType(value: Any?): KSType {
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

            is JVMClassProxy -> KSType("class ${value.simpleName}")
            is JVMMethodProxy -> KSType("Function")

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
            is NativeTypeConstructor -> KSType("class ${value.typeName}")
            else -> KSType(value::class.simpleName ?: "Unknown")
        }
    }

    // ========================================================================
    // Constraint Checking
    // ========================================================================

    internal fun checkConstraint(name: String?, value: Any, constraint: Constraint, location: SourceLocation?) {
        if (!interp.runtime.checkConstraints) return

        val satisfied = when (constraint) {
            is ComparisonConstraint -> {
                val threshold = interp.evaluate(constraint.value)
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
                val range = interp.evaluate(constraint.range)
                checkContains(range, value)
            }
            is InConstraint -> {
                val collection = interp.evaluate(constraint.collection)
                checkContains(collection, value)
            }
            is MatchesConstraint -> {
                val pattern = interp.evaluate(constraint.pattern)
                matchesPattern(value, pattern)
            }
        }

        if (!satisfied) {
            val constraintExpr = constraintToString(constraint)
            throw ConstraintError(name, constraintExpr, value, location)
        }
    }

    internal fun constraintToString(constraint: Constraint): String {
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
     * @param type   The declared type (null means no annotation -- skip check)
     * @param location Source location for error reporting
     */
    internal fun checkNullSafety(name: String, value: Any?, type: TypeRef?, location: SourceLocation?) {
        if (!interp.runtime.strictNullSafety) return
        if (type == null) return          // no type annotation -- nothing to check
        if (type.nullable) return         // nullable type (String?) -- nil is allowed
        if (value != null) return         // non-null value -- always OK

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
     *     thing = "apple"          // -> TypeError
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
    internal fun checkTypeCompatibility(name: String, value: Any?, type: TypeRef?, location: SourceLocation?) {
        if (type == null) return              // no annotation -- dynamic, no check
        if (value == null) return             // null handled by checkNullSafety
        if (type.name == "Any") return        // Any accepts everything

        val actualType = runtimeTypeName(value)
        if (actualType == null) return        // unknown type -- skip check

        if (!isTypeCompatible(actualType, type.name)) {
            throw TypeError(
                "Cannot assign ${actualType} value to '${name}' of type '${type.name}'",
                location
            )
        }
    }

    // ========================================================================
    // Reserved Member Warnings
    // ========================================================================

    /** Reserved member names that are universal reflection properties. */
    private val RESERVED_MEMBER_NAMES = setOf("type", "typeName", "members")

    /**
     * Emit warnings if a class or struct declares members with reserved names.
     * These names are used for universal reflection properties and will be
     * shadowed -- user code will never be able to access them via dot syntax.
     */
    internal fun warnReservedMemberNames(typeName: String, members: List<Node>, constructorParams: List<ConstructorParam>, location: SourceLocation) {
        val colorEnabled = interp.runtime.colorOutput
        for (param in constructorParams) {
            if (param.name in RESERVED_MEMBER_NAMES && param.binding != null) {
                interp.runtime.errorWriter.println(ANSI.warn(
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
                interp.runtime.errorWriter.println(ANSI.warn(
                    "[${location.line}:${location.column}] Warning: '$typeName' declares member '$memberName' which shadows the built-in .$memberName reflection property",
                    colorEnabled
                ))
            }
        }
    }

    // ========================================================================
    // Type Operations
    // ========================================================================

    internal fun checkType(value: Any?, type: TypeRef): Boolean {
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
                        interp.classes[type.name]?.let { return value.klass.isSubclassOf(it) }
                        // Trait conformance check
                        val trait = interp.traits[type.name]
                        if (trait != null) return value.klass.implementsTrait(trait)
                        false
                    }
                    is KSStructInstance -> {
                        // Direct struct name match
                        if (value.struct.name == type.name) return true
                        // Trait conformance check
                        val trait = interp.traits[type.name]
                        if (trait != null) return value.struct.implementsTrait(trait)
                        false
                    }
                    is KSEnumConstant -> value.enum.name == type.name
                    else -> false
                }
            }
        }
    }

    internal fun castTo(value: Any?, type: TypeRef, location: SourceLocation?): Any? {
        if (value == null) {
            if (type.nullable) return null
            throw CastError(value, type.name, location)
        }

        // Unit conversion: `quantity as unitSymbol` → convertTo
        // e.g. (10cm + 4mm) as cm → 10.4cm
        // IncompatibleUnitsException propagates naturally for mismatched
        // dimensions (e.g. 5cm as kg)
        if (value is Quantity<*>) {
            val targetUnit = KiUnit.getUnit(type.name)
            if (targetUnit != null) {
                @Suppress("UNCHECKED_CAST")
                return (value as Quantity<KiUnit>).convertTo(targetUnit)
            }
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

    internal fun checkContains(container: Any?, value: Any?): Boolean {
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

    internal fun matchesPattern(value: Any?, pattern: Any?): Boolean {
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
    // Type Compatibility
    // ========================================================================

    /**
     * Get the KS type name for a runtime value.
     */
    internal fun runtimeTypeName(value: Any): String? = when (value) {
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
     * Handles exact matches and numeric widening (Int -> Long -> Double, etc.).
     * Class/trait subtype relationships are checked via the runtime class hierarchy.
     */
    internal fun isTypeCompatible(actualType: String, declaredType: String): Boolean {
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
            val actualClass = interp.classes[actualType]
            val declaredClass = interp.classes[declaredType]
            if (actualClass != null && declaredClass != null) {
                if (isSubclassOf(actualClass, declaredClass)) return true
            }
            // Check trait compatibility
            val declaredTrait = interp.traits[declaredType]
            if (declaredTrait != null && actualClass != null) {
                if (actualClass.implementsTrait(declaredTrait)) return true
            }
        } catch (_: Exception) {
            // Type not found -- skip subtype check
        }

        return false
    }

    /**
     * Check if one class is a subclass of another (walks the superclass chain).
     */
    internal fun isSubclassOf(subclass: KSClass, superclass: KSClass): Boolean {
        var current: KSClass? = subclass
        while (current != null) {
            if (current === superclass) return true
            current = current.superclass
        }
        return false
    }

    // ========================================================================
    // Iteration Support
    // ========================================================================

    internal fun toIterable(value: Any?, location: SourceLocation?): Iterable<Any?> {
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
    internal fun rangeToIterable(range: Range<*>, location: SourceLocation?): Iterable<Any?> {
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
    internal fun getStringMember(str: String, member: String, location: SourceLocation?): Any {
        return when (member) {

            // ================================================================
            // Tier 1: KS Properties
            //
            // KS-only additions that don't shadow Kotlin/Java names.
            //   • `size`  — universal countable (delegates to length)
            //   • `rex`   — compile String to Regex (no Kotlin/Java equivalent)
            // ================================================================

            "size" -> str.length
            "rex" -> Regex(str)

            // ================================================================
            // Tier 2: Kotlin Members
            //
            // Properties and functions from kotlin.String, resolved with
            // correct property-vs-method semantics:
            //   • Kotlin properties → return value directly (no parens)
            //   • Zero-arg functions (no property name clash) → return value
            //     directly (property-style access accepted)
            //   • Functions with parameters → return NativeCallable (parens)
            // ================================================================

            // --- Kotlin property ---
            "length" -> str.length

            // --- Zero-arg Kotlin functions (property-style) ---
            "isEmpty" -> str.isEmpty()
            "isNotEmpty" -> str.isNotEmpty()
            "isBlank" -> str.isBlank()
            "isNotBlank" -> str.isNotBlank()
            "first" -> if (str.isNotEmpty()) str.first()
            else throw IndexOutOfBoundsError(0, 0, location)
            "last" -> if (str.isNotEmpty()) str.last()
            else throw IndexOutOfBoundsError(0, 0, location)
            "indices" -> if (str.isEmpty()) {
                Range(0, 0, Range.Bound.ExclusiveEnd)  // 0..<0 = empty
            } else {
                Range(0, str.length - 1, Range.Bound.Inclusive)
            }
            "uppercase" -> str.uppercase()
            "lowercase" -> str.lowercase()
            "trim" -> str.trim()
            "trimStart" -> str.trimStart()
            "trimEnd" -> str.trimEnd()
            "reversed" -> str.reversed()
            "lines" -> str.lines().toMutableList()

            // --- Kotlin/Java functions with parameters (NativeCallable) ---

            "charAt" -> NativeCallable("charAt") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("charAt() requires 1 argument", loc)
                val index = (args[0] as? Number)?.toInt()
                    ?: throw TypeError("charAt() requires an Int argument", loc)
                if (index < 0 || index >= str.length)
                    throw IndexOutOfBoundsError(index, str.length, loc)
                str[index]
            }

            "compareTo" -> NativeCallable("compareTo") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("compareTo() requires 1 argument", loc)
                val other = args[0]?.toString()
                    ?: throw TypeError("compareTo() requires a String argument", loc)
                str.compareTo(other)
            }

            "compareToIgnoreCase" -> NativeCallable("compareToIgnoreCase") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("compareToIgnoreCase() requires 1 argument", loc)
                val other = args[0]?.toString()
                    ?: throw TypeError("compareToIgnoreCase() requires a String argument", loc)
                str.compareTo(other, ignoreCase = true)
            }

            "contains" -> NativeCallable("contains") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("contains() requires 1 argument", loc)
                when (val arg = args[0]) {
                    is Regex -> arg.containsMatchIn(str)
                    else -> str.contains(arg?.toString()
                        ?: throw TypeError("contains() requires a String or Regex argument", loc))
                }
            }

            "endsWith" -> NativeCallable("endsWith") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("endsWith() requires 1 argument", loc)
                val suffix = args[0]?.toString()
                    ?: throw TypeError("endsWith() requires a String argument", loc)
                str.endsWith(suffix)
            }

            "equals" -> NativeCallable("equals") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("equals() requires 1 argument", loc)
                str == args[0]
            }

            "equalsIgnoreCase" -> NativeCallable("equalsIgnoreCase") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("equalsIgnoreCase() requires 1 argument", loc)
                val other = args[0]?.toString()
                    ?: throw TypeError("equalsIgnoreCase() requires a String argument", loc)
                str.equals(other, ignoreCase = true)
            }

            "hashCode" -> NativeCallable("hashCode") { _, _ ->
                str.hashCode()
            }

            "indexOf" -> NativeCallable("indexOf") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("indexOf() requires at least 1 argument", loc)
                val substr = args[0]?.toString()
                    ?: throw TypeError("indexOf() requires a String argument", loc)
                if (args.size >= 2) {
                    val fromIndex = (args[1] as? Number)?.toInt()
                        ?: throw TypeError("indexOf() second argument must be an Int", loc)
                    str.indexOf(substr, fromIndex)
                } else {
                    str.indexOf(substr)
                }
            }

            "lastIndexOf" -> NativeCallable("lastIndexOf") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("lastIndexOf() requires 1 argument", loc)
                val substr = args[0]?.toString()
                    ?: throw TypeError("lastIndexOf() requires a String argument", loc)
                str.lastIndexOf(substr)
            }

            "matches" -> NativeCallable("matches") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("matches() requires 1 argument", loc)
                when (val arg = args[0]) {
                    is Regex -> arg.matches(str)
                    is String -> Regex(arg).matches(str)
                    else -> throw TypeError("matches() requires a Regex or String argument", loc)
                }
            }

            "padEnd" -> NativeCallable("padEnd") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("padEnd() requires at least 1 argument", loc)
                val length = (args[0] as? Number)?.toInt()
                    ?: throw TypeError("padEnd() first argument must be an Int", loc)
                val padChar = if (args.size >= 2) {
                    when (val c = args[1]) {
                        is Char -> c
                        is String -> if (c.length == 1) c[0]
                        else throw TypeError("padEnd() padChar must be a single Char", loc)
                        else -> throw TypeError("padEnd() padChar must be a Char", loc)
                    }
                } else ' '
                str.padEnd(length, padChar)
            }

            "padStart" -> NativeCallable("padStart") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("padStart() requires at least 1 argument", loc)
                val length = (args[0] as? Number)?.toInt()
                    ?: throw TypeError("padStart() first argument must be an Int", loc)
                val padChar = if (args.size >= 2) {
                    when (val c = args[1]) {
                        is Char -> c
                        is String -> if (c.length == 1) c[0]
                        else throw TypeError("padStart() padChar must be a single Char", loc)
                        else -> throw TypeError("padStart() padChar must be a Char", loc)
                    }
                } else ' '
                str.padStart(length, padChar)
            }

            "repeat" -> NativeCallable("repeat") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("repeat() requires 1 argument", loc)
                val n = (args[0] as? Number)?.toInt()
                    ?: throw TypeError("repeat() requires an Int argument", loc)
                if (n < 0) throw RuntimeError("repeat() count must be non-negative, got $n", loc)
                str.repeat(n)
            }

            "replace" -> NativeCallable("replace") { args, loc ->
                if (args.size < 2) throw RuntimeError("replace() requires 2 arguments", loc)
                val replacement = args[1]?.toString()
                    ?: throw TypeError("replace() second argument must be a String", loc)
                when (val first = args[0]) {
                    is Regex -> first.replace(str, replacement)
                    else -> str.replace(
                        first?.toString()
                            ?: throw TypeError("replace() first argument must be a String or Regex", loc),
                        replacement
                    )
                }
            }

            "replaceFirst" -> NativeCallable("replaceFirst") { args, loc ->
                if (args.size < 2) throw RuntimeError("replaceFirst() requires 2 arguments", loc)
                val replacement = args[1]?.toString()
                    ?: throw TypeError("replaceFirst() second argument must be a String", loc)
                when (val first = args[0]) {
                    is Regex -> first.replaceFirst(str, replacement)
                    else -> str.replaceFirst(
                        first?.toString()
                            ?: throw TypeError("replaceFirst() first argument must be a String or Regex", loc),
                        replacement
                    )
                }
            }

            "split" -> NativeCallable("split") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("split() requires at least 1 argument", loc)
                when (val delim = args[0]) {
                    is Regex -> {
                        if (args.size >= 2) {
                            val limit = (args[1] as? Number)?.toInt()
                                ?: throw TypeError("split() limit must be an Int", loc)
                            delim.split(str, limit).toMutableList()
                        } else {
                            delim.split(str).toMutableList()
                        }
                    }
                    else -> {
                        val delimStr = delim?.toString()
                            ?: throw TypeError("split() requires a String or Regex argument", loc)
                        if (args.size >= 2) {
                            val limit = (args[1] as? Number)?.toInt()
                                ?: throw TypeError("split() limit must be an Int", loc)
                            str.split(delimStr, limit = limit).toMutableList()
                        } else {
                            str.split(delimStr).toMutableList()
                        }
                    }
                }
            }

            "startsWith" -> NativeCallable("startsWith") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("startsWith() requires 1 argument", loc)
                val prefix = args[0]?.toString()
                    ?: throw TypeError("startsWith() requires a String argument", loc)
                str.startsWith(prefix)
            }

            "substring" -> NativeCallable("substring") { args, loc ->
                if (args.isEmpty()) throw RuntimeError("substring() requires at least 1 argument", loc)
                val beginIndex = (args[0] as? Number)?.toInt()
                    ?: throw TypeError("substring() first argument must be an Int", loc)
                if (args.size >= 2) {
                    val endIndex = (args[1] as? Number)?.toInt()
                        ?: throw TypeError("substring() second argument must be an Int", loc)
                    str.substring(beginIndex, endIndex)
                } else {
                    str.substring(beginIndex)
                }
            }

            "toDouble" -> NativeCallable("toDouble") { _, loc ->
                str.toDoubleOrNull()
                    ?: throw RuntimeError("Cannot convert '${str}' to Double", loc)
            }

            "toInt" -> NativeCallable("toInt") { _, loc ->
                str.toIntOrNull()
                    ?: throw RuntimeError("Cannot convert '${str}' to Int", loc)
            }

            "toLong" -> NativeCallable("toLong") { _, loc ->
                str.toLongOrNull()
                    ?: throw RuntimeError("Cannot convert '${str}' to Long", loc)
            }

            "toString" -> NativeCallable("toString") { _, _ ->
                str
            }

            // ================================================================
            // Tier 3: Lazy-load JVM Reflection Fallback
            //
            // Any method not in the curated tiers above is resolved via
            // reflection on java.lang.String. This ensures ALL String methods
            // are accessible without explicit registration.
            //
            // Zero-arg methods (no property name clash): auto-invoke,
            //   returning the value directly (property-style).
            // Methods with params: return NativeCallable (parens required).
            //
            // Only available when hostLang=true.
            // ================================================================

            else -> {
                if (!interp.runtime.hostLang) {
                    throw MemberNotFoundError(member, "String", location)
                }
                resolveJvmStringMethod(str, member, location)
                    ?: throw MemberNotFoundError(member, "String", location)
            }
        }
    }

    /**
     * Lazy-load a String method via JVM reflection.
     *
     * Searches [java.lang.String] for public instance methods matching [member].
     *
     * Resolution rules:
     * - If ALL overloads are zero-arg → auto-invoke and return the value
     *   directly (property-style access, consistent with zero-arg Kotlin
     *   functions in the curated set).
     * - If ANY overload has parameters → return a [NativeCallable] with
     *   overload resolution and type coercion at call time.
     * - Returns null if no method is found.
     *
     * Examples of methods resolved here: `codePointAt`, `getBytes`,
     * `toCharArray`, `intern`, `regionMatches`, etc.
     */
    private fun resolveJvmStringMethod(str: String, member: String, location: SourceLocation?): Any? {
        val methods = String::class.java.methods.filter {
            it.name == member &&
                    Modifier.isPublic(it.modifiers) &&
                    !Modifier.isStatic(it.modifiers) &&
                    !it.isSynthetic && !it.isBridge
        }
        if (methods.isEmpty()) return null

        // Zero-arg only: auto-invoke (property-style)
        if (methods.all { it.parameterCount == 0 }) {
            return try {
                methods[0].invoke(str)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw RuntimeError(
                    "Error calling String.$member: ${e.targetException.message}",
                    location, e.targetException
                )
            }
        }

        // Has overloads with params: return NativeCallable
        return NativeCallable(member) { args, loc ->
            val method = methods.find { it.parameterCount == args.size }
                ?: throw RuntimeError(
                    "No overload of String.$member matches ${args.size} argument(s). " +
                            "Available: ${methods.map { "${member}(${it.parameterCount} args)" }.distinct().joinToString(", ")}",
                    loc
                )

            try {
                method.invoke(str, *args.toTypedArray())
            } catch (e: IllegalArgumentException) {
                val coerced = tryCoerceArgs(args, method.parameterTypes)
                if (coerced != null) {
                    try {
                        method.invoke(str, *coerced.toTypedArray())
                    } catch (e2: java.lang.reflect.InvocationTargetException) {
                        throw RuntimeError(
                            "Error calling String.$member: ${e2.targetException.message}",
                            loc, e2.targetException
                        )
                    }
                } else {
                    throw TypeError(
                        "Argument type mismatch calling String.$member: ${e.message}",
                        loc
                    )
                }
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw RuntimeError(
                    "Error calling String.$member: ${e.targetException.message}",
                    loc, e.targetException
                )
            }
        }
    }

    internal fun getListMember(list: List<*>, member: String, location: SourceLocation?): Any {
        return when (member) {
            "size", "length" -> list.size
            "isEmpty" -> list.isEmpty()
            "isNotEmpty" -> list.isNotEmpty()
            "first" -> list.firstOrNull() ?: throw IndexOutOfBoundsError(0, 0, location)
            "last" -> list.lastOrNull() ?: throw IndexOutOfBoundsError(0, 0, location)
            // --- Mutator / copy pairs (both require parens) ---

            "reverse" -> NativeCallable("reverse") { _, loc ->
                if (list is MutableList<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (list as MutableList<Any?>).reverse()
                    null // in-place mutation — returns nil
                } else {
                    throw RuntimeError(
                        "reverse() requires a mutable list. Use reversed() for an immutable copy.",
                        loc ?: location
                    )
                }
            }

            "reversed" -> NativeCallable("reversed") { _, _ ->
                list.reversed()
            }

            "shuffle" -> NativeCallable("shuffle") { _, loc ->
                if (list is MutableList<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (list as MutableList<Any?>).shuffle()
                    null // in-place mutation — returns nil
                } else {
                    throw RuntimeError(
                        "shuffle() requires a mutable list. Use shuffled() for an immutable copy.",
                        loc ?: location
                    )
                }
            }

            "shuffled" -> NativeCallable("shuffled") { _, _ ->
                list.shuffled()
            }

            "sort" -> NativeCallable("sort") { _, loc ->
                if (list is MutableList<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val mutable = list as MutableList<Any?>
                    try {
                        @Suppress("UNCHECKED_CAST")
                        (mutable as MutableList<Comparable<Any>>).sort()
                    } catch (e: ClassCastException) {
                        throw RuntimeError(
                            "sort() requires all elements to be comparable",
                            loc ?: location
                        )
                    }
                    null // in-place mutation — returns nil
                } else {
                    throw RuntimeError(
                        "sort() requires a mutable list. Use sorted() for an immutable copy.",
                        loc ?: location
                    )
                }
            }

            "sorted" -> NativeCallable("sorted") { _, loc ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    (list as List<Comparable<Any>>).sorted()
                } catch (e: ClassCastException) {
                    throw RuntimeError(
                        "sorted() requires all elements to be comparable",
                        loc ?: location
                    )
                }
            }

            else -> throw MemberNotFoundError(member, "List", location)
        }
    }

    internal fun getMapMember(map: Map<*, *>, member: String, location: SourceLocation?): Any {
        return when (member) {
            "size" -> map.size
            "isEmpty" -> map.isEmpty()
            "isNotEmpty" -> map.isNotEmpty()
            "keys" -> map.keys.toList()
            "values" -> map.values.toList()
            else -> throw MemberNotFoundError(member, "Map", location)
        }
    }

    internal fun getRangeMember(range: Range<*>, member: String, location: SourceLocation?): Any? {
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

    internal fun getRegexMember(regex: Regex, member: String, location: SourceLocation?): Any? {
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

    internal fun getMatchResultMember(match: MatchResult, member: String, location: SourceLocation?): Any? {
        return when (member) {
            // Properties
            "value" -> match.value
            "groupValues" -> match.groupValues.toMutableList()
            "groupCount" -> match.groupValues.size - 1  // exclude group 0 (full match)

            else -> throw MemberNotFoundError(member, "MatchResult", location)
        }
    }

    internal fun getEnumConstantMember(constant: KSEnumConstant, member: String, location: SourceLocation?): Any? {
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
     * - `value` -> the numeric value (Int, Long, Float, Double, or Dec)
     * - `unit` -> the unit symbol as a String (e.g., "cm", "kg", "USD")
     */
    internal fun getQuantityMember(quantity: Quantity<*>, member: String, location: SourceLocation?): Any? {
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

    internal fun getVersionMember(version: Version, member: String, location: SourceLocation?): Any? {
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

    // ========================================================================
    // Grid Member Access
    // ========================================================================

    internal fun getGridMember(grid: Grid<*>, member: String, location: SourceLocation?): Any? {
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

    internal fun getCoordinateMember(coord: Coordinate, member: String, location: SourceLocation?): Any? {
        return when (member) {
            "x" -> coord.x
            "y" -> coord.y
            "column" -> coord.column
            "row" -> coord.row
            "hasZ" -> coord.hasZ
            "z" -> coord.z
            "rowLetter" -> coord.rowLetter
            "columnNumber" -> coord.columnNumber
            "toSheetNotation" -> NativeCallable("toSheetNotation") { _, _ -> coord.toSheetNotation() }
            "toPlateNotation" -> NativeCallable("toPlateNotation") { _, _ -> coord.toPlateNotation() }
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
// NativeCallable
// ============================================================================

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