package io.kixi.ks.interp

import io.kixi.Blob
import io.kixi.Call
import io.kixi.Coordinate
import io.kixi.Email
import io.kixi.GeoPoint
import io.kixi.Grid
import io.kixi.NSID
import io.kixi.Range
import io.kixi.Version
import io.kixi.kd.Tag as KiTag
import io.kixi.ks.*
import io.kixi.ks.parser.*
import io.kixi.uom.Currency
import io.kixi.uom.Quantity

import java.lang.reflect.Modifier
import java.net.URL
import kotlin.reflect.full.IllegalCallableAccessException
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * Expression evaluator for the KS interpreter.
 *
 * Handles evaluation of all expression and expression-like AST nodes:
 *
 * - **Literals**: scalar literals, quantity/currency parsing, version parsing, URLs
 * - **String templates**: interpolation evaluation
 * - **Collections**: list, map, range construction
 * - **Grid & Coordinate literals**: `.grid(...)`, `.coordinate(...)`
 * - **Member access**: property access, safe navigation, universal reflection
 *   (.type, .typeName, .members), built-in type dispatch
 * - **Index access**: single/multi-index on lists, maps, strings, grids, objects
 * - **Assignment**: simple and compound (+=, -=, etc.) to variables, properties,
 *   index targets
 * - **Binary expressions**: arithmetic, comparison, logical, elvis, combine
 * - **Unary expressions**: negate, not, increment/decrement, non-null assert
 * - **Type operations**: is/!is, as, in/!in, matches
 *
 * Follows the same delegation pattern as the Parser's sub-parsers -- holds a
 * reference to the parent [Interpreter] for access to shared state and the
 * core evaluate() method.
 *
 * @param interp Reference to the parent [Interpreter] for state access.
 */
class ExpressionEvaluator(internal val interp: Interpreter) {

    // Convenience accessors for frequently used delegates
    private inline val ops get() = interp.ops
    private inline val types get() = interp.typeDecls

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
    internal fun evaluateLiteral(expr: LiteralExpr): Any? {
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
     * Examples: `23cm`, `51.4m\u00b3`, `1000kg`, `25\u00b0C`, `97\u2113`, `100USD`, `5.5e(-7)m`
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
     * Examples: `$23.53` -> `23.53USD`, `\u20ac50.25:d` -> `50.25EUR:d`, `\u20bf0.5` -> `0.5BTC`
     */
    private fun parseCurrencyQuantityLiteral(text: String, location: SourceLocation?): Quantity<*> {
        val prefixChar = text[0]
        val currency = Currency.fromPrefix(prefixChar)
            ?: throw RuntimeError("Unknown currency prefix: '$prefixChar'", location)

        // Convert prefix notation to suffix notation for Quantity.parse
        val numericPart = text.substring(1) // e.g., "23.53" or "23.53:d"
        val colonIdx = numericPart.indexOf(':')
        val suffixForm = if (colonIdx >= 0) {
            // Insert currency symbol before type specifier: "23.53:d" -> "23.53USD:d"
            numericPart.substring(0, colonIdx) + currency.symbol + numericPart.substring(colonIdx)
        } else {
            // Append currency symbol: "23.53" -> "23.53USD"
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
     *   "5.0.0"         -> "5.0.0"         -> Version(5, 0, 0)
     *   "5.2.7"         -> "5.2.7"         -> Version(5, 2, 7)
     *   "0.2.0_beta"    -> "0.2.0-beta"    -> Version(0, 2, 0, "beta")
     *   "0.2.0_beta_1"  -> "0.2.0-beta-1"  -> Version(0, 2, 0, "beta", 1)
     *   "1_000.0.0_rc"  -> "1000.0.0-rc"   -> Version(1000, 0, 0, "rc")
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
            // No qualifier -- just remove digit-separator underscores
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

    internal fun evaluateStringTemplate(template: StringTemplateExpr): String {
        val sb = StringBuilder()
        for (part in template.parts) {
            when (part) {
                is LiteralPart -> sb.append(part.text)
                is ExpressionPart -> sb.append(ops.stringify(interp.evaluate(part.expr)))
            }
        }
        return sb.toString()
    }

    // ========================================================================
    // Collections
    // ========================================================================

    internal fun evaluateList(expr: ListExpr): MutableList<Any?> {
        return expr.elements.map { interp.evaluate(it) }.toMutableList()
    }

    internal fun evaluateMap(expr: MapExpr): MutableMap<Any?, Any?> {
        val result = mutableMapOf<Any?, Any?>()
        for (entry in expr.entries) {
            val key = interp.evaluate(entry.key)
            val value = interp.evaluate(entry.value)
            result[key] = value
        }
        return result
    }

    internal fun evaluateRange(expr: RangeExpr): Any {
        val start = expr.start?.let { interp.evaluate(it) }
        val end = expr.end?.let { interp.evaluate(it) }
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
     *     .grid(1 2 3; 4 5 6)             -> Grid<Any?>(3, 2)
     *     .grid<Int>(10 20 30; 40 50 60)  -> Grid<Any?>(3, 2) with Int validation
     */
    internal fun evaluateGridLiteral(expr: GridLiteralExpr): Any {
        // Resolve element type from type parameter if specified
        val elementType: Class<*>? = expr.typeParam?.let { ops.resolveGridElementType(it, expr.location) }
        val typeParamNullable = expr.typeParam?.nullable ?: false

        // --- Dimension form: .grid(w, h) or .grid<Int>(w, h, default = val) ---
        if (expr.arguments.isNotEmpty()) {
            return evaluateGridDimensionForm(expr, elementType, typeParamNullable)
        }

        // --- Empty data form: .grid {} or .grid<Int> {} ---
        if (expr.rows.isEmpty()) {
            // For Any / Any? typed grids, store elementType as null so the type isn't displayed
            val storedType = if (elementType == Any::class.java) null else elementType
            val nullable = if (storedType == null && elementType == null) true else typeParamNullable
            return Grid<Any?>(0, 0, emptyArray(), storedType, nullable)
        }

        // --- Data form: .grid { 1 2; 3 4 } or .grid<Int> { 1 2; 3 4 } ---

        // Evaluate all values
        val evaluatedRows = expr.rows.map { row ->
            row.map { interp.evaluate(it) }
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
                    if (value != null && !ops.isGridValueCompatible(value, elementType)) {
                        val actualType = ops.runtimeTypeName(value) ?: value.javaClass.simpleName
                        val expectedType = ops.gridElementTypeName(elementType)
                        throw RuntimeError(
                            "Grid<$expectedType> cannot contain value of type $actualType at [$x, $y]",
                            expr.location
                        )
                    }
                    if (value == null && !typeParamNullable) {
                        val expectedType = ops.gridElementTypeName(elementType)
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
     * Evaluate the dimension form of a .grid literal:
     *
     *     .grid(2, 3)                       // untyped, nil-filled
     *     .grid<Int>(2, 3)                  // typed, zero-filled
     *     .grid<Int>(2, 3, default = 1)     // typed, explicit default
     *
     * Mirrors the logic in [Interpreter.evaluateGridConstruction] so that
     * `.grid<Int>(2, 3)` and `Grid<Int>(2, 3)` produce identical results.
     */
    private fun evaluateGridDimensionForm(
        expr: GridLiteralExpr,
        elementType: Class<*>?,
        typeParamNullable: Boolean
    ): Grid<Any?> {
        val location = expr.location

        // Evaluate and classify arguments
        val positionalArgs = mutableListOf<Any?>()
        var explicitDefault: Any? = null
        var hasExplicitDefault = false

        for (argNode in expr.arguments) {
            val value = interp.evaluate(argNode.value)
            if (argNode.name == "default") {
                explicitDefault = value
                hasExplicitDefault = true
            } else if (argNode.name != null) {
                throw RuntimeError(
                    ".grid() unknown named argument '${argNode.name}' (only 'default' is supported)",
                    location
                )
            } else {
                positionalArgs.add(value)
            }
        }

        // Third positional arg is treated as the default value
        if (positionalArgs.size == 3 && !hasExplicitDefault) {
            explicitDefault = positionalArgs.removeAt(2)
            hasExplicitDefault = true
        }

        if (positionalArgs.size != 2) {
            throw RuntimeError(
                ".grid() expects 2 positional arguments (width, height), got ${positionalArgs.size}",
                location
            )
        }
        val width = positionalArgs[0] as? Int
            ?: throw RuntimeError(".grid width must be Int, got ${positionalArgs[0]?.let { ops.runtimeTypeName(it) } ?: "nil"}", location)
        val height = positionalArgs[1] as? Int
            ?: throw RuntimeError(".grid height must be Int, got ${positionalArgs[1]?.let { ops.runtimeTypeName(it) } ?: "nil"}", location)

        // --- Untyped: .grid(w, h) ---
        if (elementType == null) {
            return if (hasExplicitDefault) {
                Grid<Any?>(width, height, Array(width * height) { explicitDefault }, explicitDefault?.javaClass, explicitDefault == null)
            } else {
                Grid.ofNulls<Any?>(width, height)
            }
        }

        // --- Typed grid ---
        val storedType = if (elementType == Any::class.java) null else elementType
        val nullable = typeParamNullable || storedType == null

        if (nullable) {
            return Grid<Any?>(width, height, Array(width * height) { null }, storedType, true)
        }

        // Non-nullable: determine default
        val defaultValue = if (hasExplicitDefault) {
            if (explicitDefault == null) {
                val typeName = ops.gridElementTypeName(elementType)
                throw RuntimeError(
                    ".grid<$typeName> cannot use nil as default (use .grid<$typeName?> for nullable)",
                    location
                )
            }
            if (!ops.isGridValueCompatible(explicitDefault, elementType)) {
                val actualType = ops.runtimeTypeName(explicitDefault) ?: explicitDefault.javaClass.simpleName
                val expectedType = ops.gridElementTypeName(elementType)
                throw RuntimeError(
                    ".grid<$expectedType> default value has incompatible type $actualType",
                    location
                )
            }
            explicitDefault
        } else {
            ops.defaultValueForGridType(elementType)
                ?: throw RuntimeError(
                    ".grid<${ops.gridElementTypeName(elementType)}> has no built-in default value; " +
                            "specify one with: .grid<${ops.gridElementTypeName(elementType)}>(width, height, default = value)",
                    location
                )
        }
        return Grid<Any?>(width, height, Array(width * height) { defaultValue }, storedType, false)
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
    internal fun evaluateCoordinateLiteral(expr: CoordinateLiteralExpr): Any {
        val argMap = mutableMapOf<String, Any?>()
        for (arg in expr.arguments) {
            val name = arg.name ?: throw RuntimeError(
                "Coordinate arguments must be named (e.g., x=0, y=0 or c=\"A\", r=1)",
                expr.location
            )
            argMap[name] = interp.evaluate(arg.value)
        }

        val z = argMap["z"]?.let { ops.toInt(it, expr.location) }

        return when {
            // Standard notation: x, y (, z)
            "x" in argMap && "y" in argMap -> {
                val x = ops.toInt(argMap["x"]!!, expr.location)
                val y = ops.toInt(argMap["y"]!!, expr.location)
                if (z != null) Coordinate.standard(x, y, z)
                else Coordinate.standard(x, y)
            }

            // Sheet notation: c, r (, z)
            "c" in argMap && "r" in argMap -> {
                val c = argMap["c"]?.toString()
                    ?: throw RuntimeError("Coordinate column 'c' cannot be nil", expr.location)
                val r = ops.toInt(argMap["r"]!!, expr.location)
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
    // Grid Index Resolution

    /**
     * Resolves grid indices for both access styles:
     * - (Int, Int): standard zero-based (column, row)
     * - (String, Int): plate notation (row letter, one-based column)
     *
     * @return Pair of (x, y) as zero-based indices
     */
    private fun resolveGridIndices(
        first: Any?, second: Any?, grid: Grid<*>, location: SourceLocation?
    ): Pair<Int, Int> {
        val x: Int
        val y: Int

        if (first is String) {
            // Plate notation: grid["B", 3] → row letter, one-based column
            y = Coordinate.columnToIndex(first)
            val col = ops.toInt(second!!, location)
            if (col < 1) {
                throw RuntimeError(
                    "Grid plate notation column must be >= 1, got $col",
                    location
                )
            }
            x = col - 1
        } else {
            // Standard: grid[2, 1] → zero-based column, row
            x = ops.toInt(first!!, location)
            y = ops.toInt(second!!, location)
        }

        if (x < 0 || x >= grid.width || y < 0 || y >= grid.height) {
            throw RuntimeError(
                "Grid index [$x, $y] out of bounds for ${grid.width}\u00d7${grid.height} grid",
                location
            )
        }

        return Pair(x, y)
    }

    // Member & Index Access
    // ========================================================================

    internal fun evaluateMemberAccess(expr: MemberAccessExpr): Any? {
        val obj = interp.evaluate(expr.obj)

        if (expr.safe && obj == null) {
            return null
        }

        // Universal reflection properties: .type and .typeName
        // These are reserved -- always resolved here, never shadowed by
        // user-defined properties. Must be checked BEFORE the nil guard
        // so that `nil.type` returns KSType("Nil") rather than throwing.
        if (expr.member == "type") return ops.getKSType(obj)
        if (expr.member == "typeName") return ops.getKSType(obj).name

        // Universal reflection: .members -- available on type definitions
        // (class, struct, trait, enum, JVM classes, native types, built-in types).
        // Returns a String describing the type's API surface: constructors,
        // properties, methods, extensions, enums, static members.
        if (expr.member == "members") {
            return when (obj) {
                // KS-defined types
                is KSClass -> MembersFormatter.formatClass(obj)
                is KSStruct -> MembersFormatter.formatStruct(obj)
                is KSTrait -> MembersFormatter.formatTrait(obj)
                is KSEnum -> MembersFormatter.formatEnum(obj)

                // JVM class proxy (from `use` imports)
                is JVMClassProxy -> JVMMembersFormatter.formatClass(obj)

                // Native Ki types (Email, GeoPoint, Version, etc.)
                is NativeTypeConstructor -> JVMMembersFormatter.formatNativeType(obj)

                // Built-in types (String, Int, List, Map, etc.)
                is KSBuiltinType -> {
                    JVMMembersFormatter.formatBuiltinType(obj.name)
                        ?: throw RuntimeError(
                            ".members not available for built-in type '${obj.name}'",
                            expr.location
                        )
                }

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
            // KS-defined types — resolved via KS runtime, no reflection backstop
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
            is KSEnumConstant -> ops.getEnumConstantMember(obj, expr.member, expr.location)
            // JVM class proxy -- access static/companion members
            is JVMClassProxy -> {
                obj.getMember(expr.member, expr.location)
                    ?: throw MemberNotFoundError(expr.member, obj.simpleName, expr.location)
            }

            // JVM method proxy -- shouldn't normally be member-accessed, but handle gracefully
            is JVMMethodProxy -> throw MemberNotFoundError(
                expr.member, "JVM method ${obj.name}", expr.location
            )

            // Built-in, native, and any JVM types — curated dispatch with
            // universal JVM reflection backstop (hostLang=true only)
            else -> resolveWithFallback(obj, expr.member, expr.location)
        }
    }

    // ========================================================================
    // Universal JVM Reflection Backstop
    // ========================================================================

    /**
     * Sentinel value used to distinguish "curated method not found" from
     * "curated method returned null" (a legitimate property value).
     */
    private object CuratedNotFound

    /**
     * Try curated type-specific dispatch first, then fall back to universal
     * JVM reflection if the curated method throws [MemberNotFoundError].
     *
     * This is the bridge between the hand-crafted `getXxxMember` methods
     * (which provide KS overrides, performance, and custom error messages)
     * and the universal reflective resolver (which ensures every public
     * JVM member is accessible without manual registration).
     *
     * The curated methods remain the **fast path** — `grid.width` still
     * hits the `when` match in `getGridMember` and returns immediately.
     * Reflection only fires for members the curated method doesn't know
     * about, or for types that have no curated method at all.
     */
    private fun resolveWithFallback(
        obj: Any, member: String, location: SourceLocation?
    ): Any? {
        // Phase 1: Curated type-specific dispatch
        val result: Any? = try {
            when (obj) {
                is String -> ops.getStringMember(obj, member, location)
                is List<*> -> ops.getListMember(obj, member, location)
                is Map<*, *> -> ops.getMapMember(obj, member, location)
                is Range<*> -> ops.getRangeMember(obj, member, location)
                is Quantity<*> -> ops.getQuantityMember(obj, member, location)
                is Version -> ops.getVersionMember(obj, member, location)
                is Grid<*> -> ops.getGridMember(obj, member, location)
                is Coordinate -> ops.getCoordinateMember(obj, member, location)
                is Currency -> interp.nativeTypes.getCurrencyMember(obj, member, location)
                is Email -> interp.nativeTypes.getEmailMember(obj, member, location)
                is GeoPoint -> interp.nativeTypes.getGeoPointMember(obj, member, location)
                is Regex -> ops.getRegexMember(obj, member, location)
                is MatchResult -> ops.getMatchResultMember(obj, member, location)
                is Blob -> interp.nativeTypes.getBlobMember(obj, member, location)
                is NSID -> interp.nativeTypes.getNSIDMember(obj, member, location)
                is KiTag -> interp.nativeTypes.getTagMember(obj, member, location)
                is Call -> interp.nativeTypes.getCallMember(obj, member, location)
                else -> CuratedNotFound // No curated method for this type
            }
        } catch (e: MemberNotFoundError) {
            CuratedNotFound // Curated method couldn't resolve this member
        }

        if (result !== CuratedNotFound) return result

        // Phase 2: Universal JVM reflection backstop
        if (!interp.runtime.hostLang) {
            val typeName = ops.runtimeTypeName(obj) ?: obj::class.simpleName ?: "Unknown"
            throw MemberNotFoundError(member, typeName, location)
        }
        return resolveObjectMember(obj, member, location)
    }

    /**
     * Universal JVM reflection member resolver.
     *
     * Resolves properties and methods on **any** JVM object using a two-tier
     * approach:
     *
     * 1. **Kotlin reflection** (`memberProperties`) — identifies Kotlin
     *    properties correctly (distinguishes `val width` from `fun width()`).
     *    Returns the property value directly for property-style access.
     *
     * 2. **Java reflection** — resolves methods with proper overload
     *    selection and numeric type coercion via [tryCoerceArgs].
     *    Zero-arg methods auto-invoke for property-style access.
     *    Methods with parameters return a [NativeCallable].
     *
     * 3. **Java getter convention** — `getFoo()` is accessible as `.foo`.
     *    Catches properties exposed through JavaBean-style getters on
     *    classes where Kotlin reflection might not apply.
     *
     * This is the method that makes `version.major`, `email.domain`,
     * `grid.rows`, etc. "just work" without manual registration in the
     * curated `getXxxMember` methods.
     *
     * @throws MemberNotFoundError if no member is found via either tier
     */
    private fun resolveObjectMember(
        obj: Any, member: String, location: SourceLocation?
    ): Any? {
        val kClass = obj::class
        val typeName = ops.runtimeTypeName(obj) ?: kClass.simpleName ?: "Unknown"

        // ---- Tier 1: Kotlin reflection — property detection ----
        // Kotlin reflection correctly classifies `val width: Int` as a
        // property vs `fun width(): Int` as a function. This matters for
        // KS semantics: properties are accessed without parens, functions
        // return NativeCallable (parens required — unless zero-arg).
        val kProp = try {
            kClass.memberProperties.firstOrNull { it.name == member }
        } catch (_: Exception) {
            // Kotlin reflection can fail on some JVM classes (e.g.,
            // synthetic classes, certain Java generics). Fall through.
            null
        }

        if (kProp != null) {
            // Try Kotlin reflection first — handles public vals/vars correctly.
            // On JPMS-sealed modules (java.lang, java.time), isAccessible
            // throws InaccessibleObjectException. In that case we skip to
            // the Java reflection tiers which use Class.getMethod (public API).
            var jpmsBlocked = false
            try {
                return try {
                    kProp.getter.call(obj)
                } catch (_: IllegalAccessException) {
                    // Kotlin-generated classes sometimes need this
                    kProp.getter.isAccessible = true
                    kProp.getter.call(obj)
                } catch (_: IllegalCallableAccessException) {
                    kProp.getter.isAccessible = true
                    kProp.getter.call(obj)
                }
            } catch (_: java.lang.reflect.InaccessibleObjectException) {
                // JPMS sealed module — fall through to Java reflection tiers
                jpmsBlocked = true
            } catch (e: Exception) {
                throw RuntimeError(
                    "Error accessing $typeName.$member: ${e.message}",
                    location, e
                )
            }
            // If we reach here, Kotlin reflection was blocked by JPMS.
            // Fall through to Tier 2/3 below.
        }

        // ---- Tier 2: Java reflection — methods ----
        val jMethods = obj.javaClass.methods.filter {
            it.name == member &&
                    Modifier.isPublic(it.modifiers) &&
                    !Modifier.isStatic(it.modifiers) &&
                    !it.isSynthetic && !it.isBridge
        }

        if (jMethods.isNotEmpty()) {
            // All overloads are zero-arg: auto-invoke (property-style access)
            if (jMethods.all { it.parameterCount == 0 }) {
                return try {
                    jMethods[0].invoke(obj)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw RuntimeError(
                        "Error calling $typeName.$member(): ${e.targetException.message}",
                        location, e.targetException
                    )
                }
            }
            // Has parameters: return NativeCallable with overload resolution
            return buildJavaCallable(obj, jMethods, member, typeName)
        }

        // ---- Tier 2b: Direct method lookup ----
        // Class.getMethod() uses the JDK's own virtual method resolution, which
        // reliably finds public methods even when they are declared in a
        // package-private superclass (e.g., AbstractStringBuilder.length()).
        // The getMethods().filter approach above can miss these on some JVM
        // versions due to JPMS visibility rules.
        val directMethod = try {
            obj.javaClass.getMethod(member)
        } catch (_: NoSuchMethodException) { null }

        if (directMethod != null && !Modifier.isStatic(directMethod.modifiers)) {
            return try {
                directMethod.invoke(obj)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw RuntimeError(
                    "Error calling $typeName.$member(): ${e.targetException.message}",
                    location, e.targetException
                )
            }
        }

        // ---- Tier 3: Java getter convention — getFoo() for ".foo" ----
        val getterName = "get${member.replaceFirstChar { it.uppercaseChar() }}"
        val getter = obj.javaClass.methods.firstOrNull {
            it.name == getterName &&
                    it.parameterCount == 0 &&
                    Modifier.isPublic(it.modifiers) &&
                    !Modifier.isStatic(it.modifiers)
        }
        if (getter != null) {
            return try {
                getter.invoke(obj)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw RuntimeError(
                    "Error accessing $typeName.$member: ${e.targetException.message}",
                    location, e.targetException
                )
            }
        }

        throw MemberNotFoundError(member, typeName, location)
    }

    /**
     * Build a [NativeCallable] that dispatches to a set of Java method
     * overloads with type-compatible selection and numeric type coercion.
     *
     * ## Overload Resolution
     *
     * When multiple methods match the arity (e.g. `StringBuilder.append`
     * has 13+ one-arg overloads), resolution proceeds as:
     *
     * 1. **Exact type match** — all args are assignable to param types
     * 2. **Numeric coercion** — tries [tryCoerceArgs] on each candidate
     * 3. **Object fallback** — picks overload accepting `Object` param
     * 4. **Error** — no compatible overload found
     */
    private fun buildJavaCallable(
        obj: Any,
        methods: List<java.lang.reflect.Method>,
        member: String,
        typeName: String
    ): NativeCallable {
        return NativeCallable(member) { args, loc ->
            // All overloads matching the argument count
            val candidates = methods.filter { it.parameterCount == args.size }
            if (candidates.isEmpty()) {
                throw RuntimeError(
                    "No overload of $typeName.$member matches ${args.size} argument(s). " +
                            "Available: ${methods.map { "$member(${it.parameterCount} args)" }
                                .distinct().joinToString(", ")}",
                    loc
                )
            }

            // 1. Exact type-compatible match (with autoboxing)
            val exact = candidates.find { m ->
                args.indices.all { i ->
                    val arg = args[i]
                    if (arg == null) !m.parameterTypes[i].isPrimitive
                    else isAssignableWithBoxing(m.parameterTypes[i], arg.javaClass)
                }
            }
            if (exact != null) {
                return@NativeCallable try {
                    exact.invoke(obj, *args.toTypedArray())
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw RuntimeError(
                        "Error calling $typeName.$member: ${e.targetException.message}",
                        loc, e.targetException
                    )
                }
            }

            // 2. Try each candidate with numeric coercion
            for (candidate in candidates) {
                val coerced = tryCoerceArgs(args, candidate.parameterTypes)
                if (coerced != null) {
                    return@NativeCallable try {
                        candidate.invoke(obj, *coerced.toTypedArray())
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        throw RuntimeError(
                            "Error calling $typeName.$member: ${e.targetException.message}",
                            loc, e.targetException
                        )
                    }
                }
            }

            // 3. Fallback: overload accepting Object
            val objectOverload = candidates.find { m ->
                m.parameterTypes.all { it == Any::class.java || it == Object::class.java }
            }
            if (objectOverload != null) {
                return@NativeCallable try {
                    objectOverload.invoke(obj, *args.toTypedArray())
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw RuntimeError(
                        "Error calling $typeName.$member: ${e.targetException.message}",
                        loc, e.targetException
                    )
                }
            }

            // 4. No compatible overload
            throw TypeError(
                "No overload of $typeName.$member is compatible with the given arguments: " +
                        args.joinToString(", ") { it?.let { v -> v::class.simpleName ?: "?" } ?: "nil" },
                loc
            )
        }
    }

    /**
     * Check if [argType] is assignable to [paramType], handling Java's
     * primitive↔boxed type mismatch.
     *
     * Java reflection treats `int.class` and `Integer.class` as unrelated —
     * `int.class.isAssignableFrom(Integer.class)` returns `false`. This helper
     * normalizes primitives to their boxed counterparts before checking, so
     * `append(42)` correctly matches `append(int)`.
     */
    private fun isAssignableWithBoxing(paramType: Class<*>, argType: Class<*>): Boolean {
        if (paramType.isAssignableFrom(argType)) return true
        // Normalize primitive → boxed for comparison
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

    internal fun evaluateIndex(expr: IndexExpr): Any? {
        val obj = interp.evaluate(expr.obj)
        val indices = expr.indices.map { interp.evaluate(it) }

        // Multi-index: dispatch to get() method or built-in Grid access
        if (indices.size > 1) {
            return when (obj) {
                is Grid<*> -> {
                    if (indices.size != 2) {
                        throw RuntimeError(
                            "Grid access requires exactly 2 indices, got ${indices.size}",
                            expr.location
                        )
                    }
                    val (x, y) = resolveGridIndices(indices[0], indices[1], obj, expr.location)
                    obj[x, y]
                }
                is KSObject -> {
                    val getMethod = obj.klass.findMethod("get")
                        ?: throw RuntimeError(
                            "Class '${obj.klass.name}' does not define a 'get' method for multi-index access",
                            expr.location
                        )
                    interp.callMethod(obj, getMethod, indices, expr.location)
                }
                is KSStructInstance -> {
                    val getMethod = obj.struct.findMethod("get")
                        ?: throw RuntimeError(
                            "Struct '${obj.struct.name}' does not define a 'get' method for multi-index access",
                            expr.location
                        )
                    types.callStructMethod(obj, getMethod, indices, expr.location)
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
                val i = ops.toInt(index, expr.indices[0].location)
                if (i < 0 || i >= obj.size) {
                    throw IndexOutOfBoundsError(i, obj.size, expr.location)
                }
                obj[i]
            }
            is Map<*, *> -> obj[index]
            is String -> {
                val i = ops.toInt(index, expr.indices[0].location)
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
                        "Grid single-index access requires a Coordinate or sheet notation String, got ${index?.let { ops.runtimeTypeName(it) ?: it.javaClass.simpleName } ?: "nil"}",
                        expr.location
                    )
                }
            }
            is Blob -> {
                val i = ops.toInt(index, expr.indices[0].location)
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
                interp.callMethod(obj, getMethod, listOf(index), expr.location)
            }
            is KSStructInstance -> {
                val getMethod = obj.struct.findMethod("get")
                    ?: throw RuntimeError(
                        "Struct '${obj.struct.name}' does not define a 'get' method for index access",
                        expr.location
                    )
                types.callStructMethod(obj, getMethod, listOf(index), expr.location)
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

    internal fun evaluateAssign(expr: AssignExpr): Any? {
        val value = interp.evaluate(expr.value)

        when (val target = expr.target) {
            is IdentifierExpr -> {
                // Check if this is a variable in scope
                if (interp.environment.isDefined(target.name)) {
                    val newValue = ops.copyIfStruct(ops.computeAssignment(
                        expr.operator,
                        { interp.environment.get(target.name, target.location) },
                        value
                    ))

                    // Check null safety: reject nil for non-nullable typed variables
                    ops.checkNullSafety(target.name, newValue, interp.environment.getType(target.name), expr.location)
                    ops.checkTypeCompatibility(target.name, newValue, interp.environment.getType(target.name), expr.location)

                    val constraint = interp.environment.getConstraint(target.name)
                    if (constraint != null && newValue != null) {
                        ops.checkConstraint(target.name, newValue, constraint, expr.location)
                    }

                    interp.environment.assign(target.name, newValue, expr.location)
                    return newValue
                }

                // If not in scope but we're in a method, try implicit 'this' property
                if (interp.environment.isDefined("this")) {
                    val thisVal = interp.environment.get("this")

                    val thisObj = thisVal as? KSObject
                    if (thisObj != null && thisObj.hasProperty(target.name)) {
                        val newValue = ops.computeAssignment(
                            expr.operator,
                            { thisObj.get(target.name, target.location) },
                            value
                        )
                        thisObj.set(target.name, newValue, expr.location)
                        return newValue
                    }

                    val thisStruct = thisVal as? KSStructInstance
                    if (thisStruct != null && thisStruct.hasProperty(target.name)) {
                        val newValue = ops.computeAssignment(
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
                val obj = interp.evaluate(target.obj)
                val indices = target.indices.map { interp.evaluate(it) }

                // KSObject: dispatch to set() method (single or multi-index)
                if (obj is KSObject) {
                    val getMethod = obj.klass.findMethod("get")
                    val setMethod = obj.klass.findMethod("set")
                        ?: throw RuntimeError(
                            "Class '${obj.klass.name}' does not define a 'set' method for index assignment",
                            target.location
                        )
                    val newValue = ops.computeAssignment(expr.operator, {
                        if (getMethod != null) interp.callMethod(obj, getMethod, indices, target.location)
                        else throw RuntimeError(
                            "Class '${obj.klass.name}' does not define a 'get' method (needed for compound assignment)",
                            target.location
                        )
                    }, value)
                    interp.callMethod(obj, setMethod, indices + newValue, target.location)
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
                    val newValue = ops.computeAssignment(expr.operator, {
                        if (getMethod != null) types.callStructMethod(obj, getMethod, indices, target.location)
                        else throw RuntimeError(
                            "Struct '${obj.struct.name}' does not define a 'get' method (needed for compound assignment)",
                            target.location
                        )
                    }, value)
                    types.callStructMethod(obj, setMethod, indices + newValue, target.location)
                    return newValue
                }

                // Multi-index on non-KSObject/KSStructInstance is an error
                // (except for Grid which supports [x, y] = value)
                if (obj is Grid<*>) {
                    if (indices.size != 2) {
                        throw RuntimeError(
                            "Grid assignment requires exactly 2 indices, got ${indices.size}",
                            target.location
                        )
                    }
                    val (x, y) = resolveGridIndices(indices[0], indices[1], obj, target.location)
                    @Suppress("UNCHECKED_CAST")
                    val grid = obj as Grid<Any?>
                    val newValue = ops.computeAssignment(expr.operator, { grid[x, y] }, value)
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
                        val i = ops.toInt(index, target.indices[0].location)
                        if (i < 0 || i >= list.size) {
                            throw IndexOutOfBoundsError(i, list.size, target.location)
                        }
                        val newValue = ops.computeAssignment(expr.operator, { list[i] }, value)
                        list[i] = newValue
                        return newValue
                    }
                    is MutableMap<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val map = obj as MutableMap<Any?, Any?>
                        val newValue = ops.computeAssignment(expr.operator, { map[index] }, value)
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
                            val newValue = ops.computeAssignment(expr.operator, { grid[x, y] }, value)
                            grid[x, y] = newValue
                            return newValue
                        } else {
                            throw TypeError(
                                "Grid single-index assignment requires a Coordinate, got ${index?.let { ops.runtimeTypeName(it) ?: it.javaClass.simpleName } ?: "nil"}",
                                target.location
                            )
                        }
                    }
                    null -> throw NullPointerError("Cannot index-assign into nil", target.location)
                    else -> throw TypeError("Cannot index-assign into ${obj::class.simpleName}", target.location)
                }
            }
            is MemberAccessExpr -> {
                val obj = interp.evaluate(target.obj)

                if (obj == null) {
                    throw NullPointerError("Cannot assign to member '${target.member}' on nil", expr.location)
                }

                when (obj) {
                    is KSObject -> {
                        val newValue = ops.computeAssignment(
                            expr.operator,
                            { obj.get(target.member, target.location) },
                            value
                        )
                        obj.set(target.member, newValue, expr.location)
                        return newValue
                    }
                    is KSStructInstance -> {
                        val newValue = ops.copyIfStruct(ops.computeAssignment(
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
                        val newValue = ops.computeAssignment(
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

    // ========================================================================
    // Binary Expressions
    // ========================================================================

    internal fun evaluateBinary(expr: BinaryExpr): Any? {
        // Short-circuit for logical operators
        if (expr.operator == BinaryOp.AND) {
            val left = interp.evaluate(expr.left)
            if (!ops.isTruthy(left)) return false
            return ops.isTruthy(interp.evaluate(expr.right))
        }
        if (expr.operator == BinaryOp.OR) {
            val left = interp.evaluate(expr.left)
            if (ops.isTruthy(left)) return true
            return ops.isTruthy(interp.evaluate(expr.right))
        }

        val left = interp.evaluate(expr.left)
        val right = interp.evaluate(expr.right)

        return when (expr.operator) {
            BinaryOp.ADD -> ops.add(left, right)
            BinaryOp.SUBTRACT -> ops.subtract(left, right)
            BinaryOp.MULTIPLY -> ops.multiply(left, right)
            BinaryOp.DIVIDE -> ops.divide(left, right)
            BinaryOp.MODULO -> ops.modulo(left, right)
            BinaryOp.POWER -> ops.power(left, right)

            BinaryOp.EQUAL -> ops.isEqual(left, right)
            BinaryOp.NOT_EQUAL -> !ops.isEqual(left, right)
            BinaryOp.LESS -> ops.compare(left, right) < 0
            BinaryOp.GREATER -> ops.compare(left, right) > 0
            BinaryOp.LESS_EQUAL -> ops.compare(left, right) <= 0
            BinaryOp.GREATER_EQUAL -> ops.compare(left, right) >= 0

            BinaryOp.AND -> ops.isTruthy(left) && ops.isTruthy(right)
            BinaryOp.OR -> ops.isTruthy(left) || ops.isTruthy(right)

            BinaryOp.ELVIS -> left ?: right

            BinaryOp.COMBINE -> ops.evaluateCombine(left, right, expr.location)
        }
    }

    // ========================================================================
    // Unary Expressions
    // ========================================================================

    internal fun evaluateUnary(expr: UnaryExpr): Any? {
        return when (expr.operator) {
            UnaryOp.NEGATE -> {
                val operand = interp.evaluate(expr.operand)
                ops.negate(operand)
            }
            UnaryOp.NOT -> {
                val operand = interp.evaluate(expr.operand)
                !ops.isTruthy(operand)
            }
            UnaryOp.INCREMENT -> {
                evaluateIncDec(expr, true)
            }
            UnaryOp.DECREMENT -> {
                evaluateIncDec(expr, false)
            }
            UnaryOp.NON_NULL -> {
                val operand = interp.evaluate(expr.operand)
                operand ?: throw NullAssertionError(expr.location)
            }
        }
    }

    private fun evaluateIncDec(expr: UnaryExpr, isIncrement: Boolean): Any? {
        when (val operand = expr.operand) {
            is IdentifierExpr -> {
                val name = operand.name
                val current = interp.environment.get(name, expr.location)
                val newVal = if (isIncrement) ops.add(current, 1) else ops.subtract(current, 1)
                interp.environment.assign(name, newVal, expr.location)
                return if (expr.prefix) newVal else current
            }
            is MemberAccessExpr -> {
                val obj = interp.evaluate(operand.obj)
                if (obj is KSObject) {
                    val current = obj.get(operand.member, operand.location)
                    val newVal = if (isIncrement) ops.add(current, 1) else ops.subtract(current, 1)
                    obj.set(operand.member, newVal, operand.location)
                    return if (expr.prefix) newVal else current
                }
                if (obj is KSStructInstance) {
                    val current = obj.get(operand.member, operand.location)
                    val newVal = if (isIncrement) ops.add(current, 1) else ops.subtract(current, 1)
                    obj.set(operand.member, newVal, operand.location)
                    return if (expr.prefix) newVal else current
                }
                throw RuntimeError("Cannot increment/decrement member of ${obj?.javaClass?.simpleName}", expr.location)
            }
            is IndexExpr -> {
                val obj = interp.evaluate(operand.obj)
                val indices = operand.indices.map { interp.evaluate(it) }

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
                    val current = interp.callMethod(obj, getMethod, indices, operand.location)
                    val newVal = if (isIncrement) ops.add(current, 1) else ops.subtract(current, 1)
                    interp.callMethod(obj, setMethod, indices + newVal, operand.location)
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
                    val current = types.callStructMethod(obj, getMethod, indices, operand.location)
                    val newVal = if (isIncrement) ops.add(current, 1) else ops.subtract(current, 1)
                    types.callStructMethod(obj, setMethod, indices + newVal, operand.location)
                    return if (expr.prefix) newVal else current
                }

                // Built-in: single index on MutableList
                if (indices.size == 1 && obj is MutableList<*>) {
                    val index = indices[0]
                    @Suppress("UNCHECKED_CAST")
                    val list = obj as MutableList<Any?>
                    val i = ops.toInt(index, operand.indices[0].location)
                    val current = list[i]
                    val newVal = if (isIncrement) ops.add(current, 1) else ops.subtract(current, 1)
                    list[i] = newVal
                    return if (expr.prefix) newVal else current
                }

                throw RuntimeError("Cannot increment/decrement index of ${obj?.javaClass?.simpleName}", expr.location)
            }
            else -> {
                // For non-assignable operands, just compute the result
                val current = interp.evaluate(operand)
                return if (isIncrement) ops.add(current, 1) else ops.subtract(current, 1)
            }
        }
    }

    // ========================================================================
    // Type Operations
    // ========================================================================

    internal fun evaluateTypeCheck(expr: TypeCheckExpr): Boolean {
        val value = interp.evaluate(expr.expr)
        val matches = ops.checkType(value, expr.type)
        return if (expr.negated) !matches else matches
    }

    internal fun evaluateTypeCast(expr: TypeCastExpr): Any? {
        val value = interp.evaluate(expr.expr)
        return ops.castTo(value, expr.type, expr.location)
    }

    internal fun evaluateInCheck(expr: InCheckExpr): Boolean {
        val value = interp.evaluate(expr.expr)
        val container = interp.evaluate(expr.container)
        val contains = ops.checkContains(container, value)
        return if (expr.negated) !contains else contains
    }

    internal fun evaluateMatches(expr: MatchesExpr): Boolean {
        val value = interp.evaluate(expr.expr)
        val pattern = interp.evaluate(expr.pattern)
        return ops.matchesPattern(value, pattern)
    }
}