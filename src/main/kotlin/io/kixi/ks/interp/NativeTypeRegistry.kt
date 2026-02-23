package io.kixi.ks.interp

import io.kixi.*
import io.kixi.kd.Tag
import io.kixi.ks.Callable
import io.kixi.ks.MemberNotFoundError
import io.kixi.ks.SourceLocation
import io.kixi.uom.Currency
import io.kixi.ks.RuntimeError
import io.kixi.uom.Quantity
import io.kixi.uom.Unit as KiUnit
import java.math.BigDecimal as Dec

/**
 * Registry for native Ki.Core and Ki.KD types exposed to the KS language.
 *
 * Types are lazy-loaded: constructors and static methods are only created
 * when the type name is first referenced in KS code. This avoids startup
 * overhead for types the program never uses.
 *
 * ## Supported Types
 *
 * | Type        | Constructor Examples                          |
 * |-------------|-----------------------------------------------|
 * | Blob        | `Blob("text")`, `Blob.empty()`, `Blob.parse(b64)` |
 * | NSID        | `NSID("name")`, `NSID("name", "ns")`         |
 * | Call        | `Call("func")`, `Call("func", "ns")`          |
 * | Tag         | `Tag("item")`, `Tag("item", "ns")`            |
 * | Grid        | `Grid(w, h, default)`, `Grid.ofNulls(w, h)`  |
 * | Version     | `Version(1, 2, 3)`, `Version.parse("1.2.3")`  |
 * | Quantity    | `Quantity.parse("5cm")`, `Quantity(5, "cm")`  |
 * | Range       | `Range(1, 10)`, `Range.inclusive(1, 10)`      |
 * | Coordinate  | `Coordinate(x, y)`, `Coordinate.sheet("A", 1)` |
 *
 * ## Architecture
 *
 * Each type is represented by a [NativeTypeConstructor] that serves dual duty:
 * 1. **Callable** — `Blob("hello")` invokes the constructor
 * 2. **Static container** — `Blob.empty()` accesses static methods
 *
 * Instance member access (e.g., `blob.size`) is handled separately via
 * dedicated `get*Member()` methods called from the interpreter's member
 * access dispatch.
 */
class NativeTypeRegistry {

    /** Lazily populated cache of type constructors. */
    private val constructors = mutableMapOf<String, NativeTypeConstructor?>()

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Look up a native type by name. Returns null if the name is not a
     * registered native type. Results are cached after first lookup.
     */
    operator fun get(name: String): NativeTypeConstructor? {
        // Return cached (including cached nulls via containsKey check)
        if (constructors.containsKey(name)) return constructors[name]

        val ctor = createConstructor(name)
        constructors[name] = ctor
        return ctor
    }

    /**
     * Check whether a runtime value is an instance of a registered native type.
     */
    fun isNativeType(value: Any?, typeName: String): Boolean = when (typeName) {
        "Blob" -> value is Blob
        "NSID" -> value is NSID
        "Call" -> value is Call
        "Tag" -> value is Tag
        "Email" -> value is Email
        "GeoPoint" -> value is GeoPoint
        else -> false
    }

    // ========================================================================
    // Constructor Factory
    // ========================================================================

    private fun createConstructor(name: String): NativeTypeConstructor? = when (name) {
        "Blob" -> blobType()
        "NSID" -> nsidType()
        "Call" -> callType()
        "Tag" -> tagType()
        "Grid" -> gridType()
        "Version" -> versionType()
        "Quantity" -> quantityType()
        "Range" -> rangeType()
        "Coordinate" -> coordinateType()
        "Email" -> emailType()
        "GeoPoint" -> geoPointType()
        else -> null
    }

    // ========================================================================
    // Blob
    // ========================================================================

    private fun blobType() = NativeTypeConstructor("Blob",
        construct = { args, loc ->
            when {
                args.isEmpty() -> Blob.empty()
                args.size == 1 && args[0] is String ->
                    Blob.of(args[0] as String)
                args.size == 1 && args[0] is ByteArray ->
                    Blob.of(args[0] as ByteArray)
                else -> throw RuntimeError(
                    "Blob() expects a String or ByteArray argument", loc
                )
            }
        },
        statics = mapOf(
            "empty" to NativeCallable("empty") { _, _ -> Blob.empty() },
            "of" to NativeCallable("of") { args, loc ->
                when {
                    args.isEmpty() -> throw RuntimeError("Blob.of() requires an argument", loc)
                    args[0] is String -> Blob.of(args[0] as String)
                    args[0] is ByteArray -> Blob.of(args[0] as ByteArray)
                    else -> throw RuntimeError(
                        "Blob.of() expects a String or ByteArray argument", loc
                    )
                }
            },
            "parse" to NativeCallable("parse") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("Blob.parse() requires a Base64 String argument", loc)
                Blob.parse(args[0] as String)
            },
            "parseLiteral" to NativeCallable("parseLiteral") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("Blob.parseLiteral() requires a String argument", loc)
                Blob.parseLiteral(args[0] as String)
            },
            "isLiteral" to NativeCallable("isLiteral") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("Blob.isLiteral() requires a String argument", loc)
                Blob.isLiteral(args[0] as String)
            }
        )
    )

    // ========================================================================
    // NSID
    // ========================================================================

    private fun nsidType() = NativeTypeConstructor("NSID",
        construct = { args, loc ->
            when (args.size) {
                0 -> NSID.ANONYMOUS
                1 -> {
                    val name = args[0]?.toString()
                        ?: throw RuntimeError("NSID name cannot be nil", loc)
                    NSID(name)
                }
                2 -> {
                    val name = args[0]?.toString()
                        ?: throw RuntimeError("NSID name cannot be nil", loc)
                    val ns = args[1]?.toString() ?: ""
                    NSID(name, ns)
                }
                else -> throw RuntimeError(
                    "NSID() expects (name) or (name, namespace)", loc
                )
            }
        },
        statics = mapOf(
            "ANONYMOUS" to NSID.ANONYMOUS,
            "parse" to NativeCallable("parse") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("NSID.parse() requires a String argument", loc)
                NSID.parse(args[0] as String)
            }
        )
    )

    // ========================================================================
    // Call
    // ========================================================================

    private fun callType() = NativeTypeConstructor("Call",
        construct = { args, loc ->
            when (args.size) {
                0 -> throw RuntimeError("Call() requires at least a name argument", loc)
                1 -> {
                    val name = args[0]?.toString()
                        ?: throw RuntimeError("Call name cannot be nil", loc)
                    Call(name)
                }
                2 -> {
                    val name = args[0]?.toString()
                        ?: throw RuntimeError("Call name cannot be nil", loc)
                    val ns = args[1]?.toString() ?: ""
                    Call(name, ns)
                }
                else -> throw RuntimeError(
                    "Call() expects (name) or (name, namespace)", loc
                )
            }
        },
        statics = emptyMap()
    )

    // ========================================================================
    // Tag
    // ========================================================================

    private fun tagType() = NativeTypeConstructor("Tag",
        construct = { args, loc ->
            when (args.size) {
                0 -> Tag("")  // anonymous tag
                1 -> {
                    val name = args[0]?.toString()
                        ?: throw RuntimeError("Tag name cannot be nil", loc)
                    Tag(name)
                }
                2 -> {
                    val name = args[0]?.toString()
                        ?: throw RuntimeError("Tag name cannot be nil", loc)
                    val ns = args[1]?.toString() ?: ""
                    Tag(name, ns)
                }
                else -> throw RuntimeError(
                    "Tag() expects (), (name), or (name, namespace)", loc
                )
            }
        },
        statics = emptyMap()
    )

    // ========================================================================
    // Grid
    // ========================================================================

    private fun gridType() = NativeTypeConstructor("Grid",
        construct = { args, loc ->
            when (args.size) {
                2 -> {
                    val w = toIntArg(args[0], "width", loc)
                    val h = toIntArg(args[1], "height", loc)
                    Grid.ofNulls<Any?>(w, h)
                }
                3 -> {
                    val w = toIntArg(args[0], "width", loc)
                    val h = toIntArg(args[1], "height", loc)
                    Grid.of(w, h, args[2])
                }
                else -> throw RuntimeError(
                    "Grid() expects (width, height) or (width, height, defaultValue)", loc
                )
            }
        },
        statics = mapOf(
            "of" to NativeCallable("of") { args, loc ->
                if (args.size < 3)
                    throw RuntimeError("Grid.of(width, height, default) requires 3 arguments", loc)
                val w = toIntArg(args[0], "width", loc)
                val h = toIntArg(args[1], "height", loc)
                Grid.of(w, h, args[2])
            },
            "ofNulls" to NativeCallable("ofNulls") { args, loc ->
                if (args.size < 2)
                    throw RuntimeError("Grid.ofNulls(width, height) requires 2 arguments", loc)
                val w = toIntArg(args[0], "width", loc)
                val h = toIntArg(args[1], "height", loc)
                Grid.ofNulls<Any?>(w, h)
            },
            "fromRows" to NativeCallable("fromRows") { args, loc ->
                if (args.isEmpty() || args[0] !is List<*>)
                    throw RuntimeError("Grid.fromRows() requires a List of Lists", loc)
                @Suppress("UNCHECKED_CAST")
                val rows = args[0] as List<List<Any?>>
                Grid.fromRows(rows)
            }
        )
    )

    // ========================================================================
    // Version
    // ========================================================================

    private fun versionType() = NativeTypeConstructor("Version",
        construct = { args, loc ->
            when (args.size) {
                1 -> Version(toIntArg(args[0], "major", loc))
                2 -> Version(
                    toIntArg(args[0], "major", loc),
                    toIntArg(args[1], "minor", loc)
                )
                3 -> Version(
                    toIntArg(args[0], "major", loc),
                    toIntArg(args[1], "minor", loc),
                    toIntArg(args[2], "micro", loc)
                )
                4 -> Version(
                    toIntArg(args[0], "major", loc),
                    toIntArg(args[1], "minor", loc),
                    toIntArg(args[2], "micro", loc),
                    args[3]?.toString() ?: ""
                )
                5 -> Version(
                    toIntArg(args[0], "major", loc),
                    toIntArg(args[1], "minor", loc),
                    toIntArg(args[2], "micro", loc),
                    args[3]?.toString() ?: "",
                    toIntArg(args[4], "qualifierNumber", loc)
                )
                else -> throw RuntimeError(
                    "Version() expects 1\u20135 arguments: (major, minor?, micro?, qualifier?, qualifierNumber?)",
                    loc
                )
            }
        },
        statics = mapOf(
            "parse" to NativeCallable("parse") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("Version.parse() requires a String argument", loc)
                Version.parse(args[0] as String)
            },
            "EMPTY" to Version.EMPTY,
            "MIN" to Version.MIN,
            "MAX" to Version.MAX
        )
    )

    // ========================================================================
    // Quantity
    // ========================================================================

    private fun quantityType() = NativeTypeConstructor("Quantity",
        construct = { args, loc ->
            when {
                args.size == 1 && args[0] is String ->
                    Quantity.parse(args[0] as String)
                args.size == 2 -> {
                    val value = args[0] as? Number
                        ?: throw RuntimeError(
                            "Quantity() first argument must be a Number", loc
                        )
                    val unitStr = args[1]?.toString()
                        ?: throw RuntimeError(
                            "Quantity() second argument must be a unit String", loc
                        )
                    val unit = KiUnit.parseOrNull(unitStr)
                        ?: throw RuntimeError(
                            "Unknown unit symbol: '$unitStr'", loc
                        )
                    Quantity<KiUnit>(value, unit)
                }
                else -> throw RuntimeError(
                    "Quantity() expects (text) or (number, unitSymbol)", loc
                )
            }
        },
        statics = mapOf(
            "parse" to NativeCallable("parse") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("Quantity.parse() requires a String argument", loc)
                Quantity.parse(args[0] as String)
            }
        )
    )

    // ========================================================================
    // Range
    // ========================================================================

    @Suppress("UNCHECKED_CAST")
    private fun rangeType() = NativeTypeConstructor("Range",
        construct = { args, loc ->
            when (args.size) {
                2 -> Range(args[0], args[1], Range.Bound.Inclusive)
                3 -> {
                    val boundName = args[2]?.toString()
                        ?: throw RuntimeError("Range bound cannot be nil", loc)
                    val bound = try {
                        Range.Bound.valueOf(boundName)
                    } catch (_: IllegalArgumentException) {
                        throw RuntimeError(
                            "Invalid Range bound: '$boundName'. " +
                                    "Use Inclusive, Exclusive, ExclusiveStart, or ExclusiveEnd",
                            loc
                        )
                    }
                    Range(args[0], args[1], bound)
                }
                else -> throw RuntimeError(
                    "Range() expects (start, end) or (start, end, bound)", loc
                )
            }
        },
        statics = mapOf(
            "inclusive" to NativeCallable("inclusive") { args, loc ->
                if (args.size < 2)
                    throw RuntimeError("Range.inclusive() requires 2 arguments", loc)
                Range.inclusive(args[0], args[1])
            },
            "exclusive" to NativeCallable("exclusive") { args, loc ->
                if (args.size < 2)
                    throw RuntimeError("Range.exclusive() requires 2 arguments", loc)
                Range.exclusive(args[0], args[1])
            },
            "openStart" to NativeCallable("openStart") { args, loc ->
                if (args.isEmpty())
                    throw RuntimeError("Range.openStart() requires an end value", loc)
                Range.openStart(args[0])
            },
            "openEnd" to NativeCallable("openEnd") { args, loc ->
                if (args.isEmpty())
                    throw RuntimeError("Range.openEnd() requires a start value", loc)
                Range.openEnd(args[0])
            },
            "parse" to NativeCallable("parse") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("Range.parse() requires a String argument", loc)
                Range.parse(args[0] as String)
            }
        )
    )

    // ========================================================================
    // Coordinate
    // ========================================================================

    private fun coordinateType() = NativeTypeConstructor("Coordinate",
        construct = { args, loc ->
            when {
                args.size == 2 && args[0] is Number && args[1] is Number -> {
                    val x = toIntArg(args[0], "x", loc)
                    val y = toIntArg(args[1], "y", loc)
                    Coordinate.standard(x, y)
                }
                args.size == 3 && args[0] is Number && args[1] is Number && args[2] is Number -> {
                    val x = toIntArg(args[0], "x", loc)
                    val y = toIntArg(args[1], "y", loc)
                    val z = toIntArg(args[2], "z", loc)
                    Coordinate.standard(x, y, z)
                }
                args.size == 2 && args[0] is String && args[1] is Number -> {
                    val c = args[0] as String
                    val r = toIntArg(args[1], "row", loc)
                    Coordinate.sheet(c, r)
                }
                args.size == 3 && args[0] is String && args[1] is Number && args[2] is Number -> {
                    val c = args[0] as String
                    val r = toIntArg(args[1], "row", loc)
                    val z = toIntArg(args[2], "z", loc)
                    Coordinate.sheet(c, r, z)
                }
                args.size == 1 && args[0] is String -> {
                    Coordinate.parse(args[0] as String)
                }
                else -> throw RuntimeError(
                    "Coordinate() expects (x, y), (x, y, z), (col, row), (col, row, z), or (\"A1\")",
                    loc
                )
            }
        },
        statics = mapOf(
            "standard" to NativeCallable("standard") { args, loc ->
                if (args.size < 2)
                    throw RuntimeError("Coordinate.standard() requires at least 2 arguments", loc)
                val x = toIntArg(args[0], "x", loc)
                val y = toIntArg(args[1], "y", loc)
                if (args.size >= 3) Coordinate.standard(x, y, toIntArg(args[2], "z", loc))
                else Coordinate.standard(x, y)
            },
            "sheet" to NativeCallable("sheet") { args, loc ->
                if (args.size < 2)
                    throw RuntimeError("Coordinate.sheet() requires at least 2 arguments", loc)
                val c = args[0]?.toString()
                    ?: throw RuntimeError("Coordinate.sheet() column cannot be nil", loc)
                val r = toIntArg(args[1], "row", loc)
                if (args.size >= 3) Coordinate.sheet(c, r, toIntArg(args[2], "z", loc))
                else Coordinate.sheet(c, r)
            },
            "parse" to NativeCallable("parse") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("Coordinate.parse() requires a String argument", loc)
                Coordinate.parse(args[0] as String)
            },
            "columnToIndex" to NativeCallable("columnToIndex") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("Coordinate.columnToIndex() requires a String argument", loc)
                Coordinate.columnToIndex(args[0] as String)
            },
            "indexToColumn" to NativeCallable("indexToColumn") { args, loc ->
                if (args.isEmpty())
                    throw RuntimeError("Coordinate.indexToColumn() requires an Int argument", loc)
                Coordinate.indexToColumn(toIntArg(args[0], "index", loc))
            }
        )
    )

    // ========================================================================
    // Email
    // ========================================================================

    private fun emailType() = NativeTypeConstructor("Email",
        construct = { args, loc ->
            when {
                args.size == 1 && args[0] is String -> Email.of(args[0] as String)
                args.size == 2 && args[0] is String && args[1] is String ->
                    Email.of(args[0] as String, args[1] as String)
                else -> throw RuntimeError(
                    "Email() expects (address) or (localPart, domain)", loc
                )
            }
        },
        statics = mapOf(
            "of" to NativeCallable("of") { args, loc ->
                when {
                    args.size == 1 && args[0] is String -> Email.of(args[0] as String)
                    args.size == 2 && args[0] is String && args[1] is String ->
                        Email.of(args[0] as String, args[1] as String)
                    else -> throw RuntimeError(
                        "Email.of() expects (address) or (localPart, domain)", loc
                    )
                }
            },
            "ofOrNull" to NativeCallable("ofOrNull") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("Email.ofOrNull() requires a String argument", loc)
                Email.ofOrNull(args[0] as String)
            },
            "isValid" to NativeCallable("isValid") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("Email.isValid() requires a String argument", loc)
                Email.isValid(args[0] as String)
            },
            "isLiteral" to NativeCallable("isLiteral") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("Email.isLiteral() requires a String argument", loc)
                Email.isLiteral(args[0] as String)
            },
            "parseLiteral" to NativeCallable("parseLiteral") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("Email.parseLiteral() requires a String argument", loc)
                Email.parseLiteral(args[0] as String)
            }
        )
    )

    // ========================================================================
    // GeoPoint
    // ========================================================================

    private fun geoPointType() = NativeTypeConstructor("GeoPoint",
        construct = { args, loc ->
            when {
                args.size == 2 -> {
                    val lat = toDoubleArg(args[0], "latitude", loc)
                    val lon = toDoubleArg(args[1], "longitude", loc)
                    GeoPoint.of(lat, lon)
                }
                args.size == 3 -> {
                    val lat = toDoubleArg(args[0], "latitude", loc)
                    val lon = toDoubleArg(args[1], "longitude", loc)
                    val alt = toDoubleArg(args[2], "altitude", loc)
                    GeoPoint.of(lat, lon, alt)
                }
                else -> throw RuntimeError(
                    "GeoPoint() expects (latitude, longitude) or (latitude, longitude, altitude)", loc
                )
            }
        },
        statics = mapOf(
            "of" to NativeCallable("of") { args, loc ->
                when (args.size) {
                    2 -> GeoPoint.of(
                        toDoubleArg(args[0], "latitude", loc),
                        toDoubleArg(args[1], "longitude", loc)
                    )
                    3 -> GeoPoint.of(
                        toDoubleArg(args[0], "latitude", loc),
                        toDoubleArg(args[1], "longitude", loc),
                        toDoubleArg(args[2], "altitude", loc)
                    )
                    else -> throw RuntimeError(
                        "GeoPoint.of() expects 2 or 3 arguments", loc
                    )
                }
            },
            "parse" to NativeCallable("parse") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("GeoPoint.parse() requires a String argument", loc)
                GeoPoint.parse(args[0] as String)
            },
            "isLiteral" to NativeCallable("isLiteral") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("GeoPoint.isLiteral() requires a String argument", loc)
                GeoPoint.isLiteral(args[0] as String)
            },
            "center" to NativeCallable("center") { args, loc ->
                if (args.isEmpty() || args[0] !is List<*>)
                    throw RuntimeError("GeoPoint.center() requires a List of GeoPoints", loc)
                @Suppress("UNCHECKED_CAST")
                val points = (args[0] as List<*>).map {
                    it as? GeoPoint ?: throw RuntimeError(
                        "GeoPoint.center() list must contain only GeoPoints", loc
                    )
                }
                GeoPoint.center(points)
            },
            "ORIGIN" to GeoPoint.ORIGIN,
            "NORTH_POLE" to GeoPoint.NORTH_POLE,
            "SOUTH_POLE" to GeoPoint.SOUTH_POLE,
            "DEFAULT_PRECISION" to GeoPoint.DEFAULT_PRECISION
        )
    )

    // ========================================================================
    // Instance Member Access
    // ========================================================================

    /**
     * Access members on a [Blob] instance.
     *
     * Properties: `size`, `isEmpty`, `isNotEmpty`
     * Methods: `toByteArray()`, `toBase64()`, `toBase64UrlSafe()`,
     *          `decodeToString()`, `get(index)`
     */
    fun getBlobMember(blob: Blob, member: String, location: SourceLocation?): Any? {
        return when (member) {
            // Properties
            "size" -> blob.size
            "isEmpty" -> blob.isEmpty()
            "isNotEmpty" -> blob.isNotEmpty()

            // Methods
            "toByteArray" -> NativeCallable("toByteArray") { _, _ -> blob.toByteArray() }
            "toBase64" -> NativeCallable("toBase64") { _, _ -> blob.toBase64() }
            "toBase64UrlSafe" -> NativeCallable("toBase64UrlSafe") { _, _ ->
                blob.toBase64UrlSafe()
            }
            "decodeToString" -> NativeCallable("decodeToString") { _, _ ->
                blob.decodeToString()
            }
            "get" -> NativeCallable("get") { args, loc ->
                if (args.isEmpty())
                    throw RuntimeError("Blob.get() requires an index argument", loc)
                val index = toIntArg(args[0], "index", loc)
                blob[index]
            }

            else -> throw MemberNotFoundError(member, "Blob", location)
        }
    }

    /**
     * Access members on an [NSID] instance.
     *
     * Properties: `name`, `namespace`, `isAnonymous`, `hasNamespace`
     */
    fun getNSIDMember(nsid: NSID, member: String, location: SourceLocation?): Any? {
        return when (member) {
            "name" -> nsid.name
            "namespace" -> nsid.namespace
            "isAnonymous" -> nsid.isAnonymous
            "hasNamespace" -> nsid.hasNamespace
            else -> throw MemberNotFoundError(member, "NSID", location)
        }
    }

    /**
     * Access members on a [Call] instance.
     *
     * Properties: `nsid`, `name`, `namespace`, `values`, `attributes`,
     *             `value`, `valueCount`, `attributeCount`
     * Methods: `hasValues()`, `hasAttributes()`, `hasValue(i)`,
     *          `hasAttribute(name)`, `withValue(v)`, `withAttribute(name, value)`,
     *          `getAttribute(name)`, `setAttribute(name, value)`,
     *          `getValueOrDefault(i, default)`, `getValueOrNull(i)`
     */
    fun getCallMember(call: Call, member: String, location: SourceLocation?): Any? {
        return when (member) {
            // Properties
            "nsid" -> call.nsid
            "name" -> call.name
            "namespace" -> call.namespace
            "values" -> call.values.toMutableList()
            "attributes" -> {
                // Convert Map<NSID, Any?> to Map<String, Any?> for KS ease-of-use
                val map = mutableMapOf<String, Any?>()
                for ((k, v) in call.attributes) {
                    map[k.toString()] = v
                }
                map
            }
            "value" -> call.value
            "valueCount" -> call.valueCount
            "attributeCount" -> call.attributeCount
            "hasValues" -> NativeCallable("hasValues") { _, _ -> call.hasValues() }
            "hasAttributes" -> NativeCallable("hasAttributes") { _, _ -> call.hasAttributes() }

            // Value methods
            "hasValue" -> NativeCallable("hasValue") { args, loc ->
                if (args.isEmpty())
                    throw RuntimeError("hasValue() requires an index argument", loc)
                call.hasValue(toIntArg(args[0], "index", loc))
            }
            "getValue" -> NativeCallable("getValue") { args, loc ->
                if (args.isEmpty())
                    throw RuntimeError("getValue() requires an index argument", loc)
                call[toIntArg(args[0], "index", loc)]
            }
            "getValueOrDefault" -> NativeCallable("getValueOrDefault") { args, loc ->
                if (args.size < 2)
                    throw RuntimeError("getValueOrDefault() requires (index, default)", loc)
                call.getValueOrDefault(toIntArg(args[0], "index", loc), args[1])
            }
            "getValueOrNull" -> NativeCallable("getValueOrNull") { args, loc ->
                if (args.isEmpty())
                    throw RuntimeError("getValueOrNull() requires an index argument", loc)
                call.getValueOrNull<Any?>(toIntArg(args[0], "index", loc))
            }
            "withValue" -> NativeCallable("withValue") { args, loc ->
                if (args.isEmpty())
                    throw RuntimeError("withValue() requires a value argument", loc)
                call.withValue(args[0])
            }
            "withValues" -> NativeCallable("withValues") { args, _ ->
                call.withValues(*args.toTypedArray())
            }

            // Attribute methods
            "getAttribute" -> NativeCallable("getAttribute") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("getAttribute() requires a String name", loc)
                val name = args[0] as String
                val ns = if (args.size > 1) args[1]?.toString() ?: "" else ""
                call.getAttribute<Any?>(name, ns)
            }
            "setAttribute" -> NativeCallable("setAttribute") { args, loc ->
                if (args.size < 2 || args[0] !is String)
                    throw RuntimeError("setAttribute() requires (name, value)", loc)
                call.setAttribute(args[0] as String, "", args[1])
                call  // return the call for chaining
            }
            "hasAttribute" -> NativeCallable("hasAttribute") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("hasAttribute() requires a String name", loc)
                val name = args[0] as String
                val ns = if (args.size > 1) args[1]?.toString() ?: "" else ""
                call.hasAttribute(name, ns)
            }
            "withAttribute" -> NativeCallable("withAttribute") { args, loc ->
                if (args.size < 2 || args[0] !is String)
                    throw RuntimeError("withAttribute() requires (name, value)", loc)
                call.withAttribute(args[0] as String, "", args[1])
            }
            "getAttributeOrDefault" -> NativeCallable("getAttributeOrDefault") { args, loc ->
                if (args.size < 2 || args[0] !is String)
                    throw RuntimeError("getAttributeOrDefault() requires (name, default)", loc)
                call.getAttributeOrDefault(args[0] as String, args[1])
            }
            "getAttributeOrNull" -> NativeCallable("getAttributeOrNull") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("getAttributeOrNull() requires a String name", loc)
                call.getAttributeOrNull<Any?>(args[0] as String)
            }

            else -> throw MemberNotFoundError(member, "Call", location)
        }
    }

    /**
     * Access members on a [Tag] instance.
     *
     * Inherits all [Call] members plus:
     * Properties: `annotations`, `children`, `isAnonymous`
     * Methods: `getChild(name)`, `findChild(name)`, `getChildrenInNamespace(ns)`,
     *          `getDescendants()`, `getProperty(key)`, `getPropertyOrNull(key)`,
     *          `hasProperty(key)`, `getProperties()`, `getPropertiesMap()`,
     *          `getChildrenValues()`
     */
    fun getTagMember(tag: Tag, member: String, location: SourceLocation?): Any? {
        return when (member) {
            // Tag-specific properties
            "annotations" -> tag.annotations.toMutableList()
            "children" -> tag.children.toMutableList()
            "isAnonymous" -> tag.isAnonymous()

            // Tag-specific methods
            "getChild" -> NativeCallable("getChild") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("getChild() requires a String name", loc)
                val name = args[0] as String
                tag.children.firstOrNull { it.name == name }
            }
            "getChildrenInNamespace" -> NativeCallable("getChildrenInNamespace") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("getChildrenInNamespace() requires a namespace String", loc)
                tag.getChildrenInNamespace(args[0] as String).toMutableList()
            }
            "getDescendants" -> NativeCallable("getDescendants") { _, _ ->
                tag.getDescendants().toMutableList()
            }
            "getProperty" -> NativeCallable("getProperty") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("getProperty() requires a String key", loc)
                val key = args[0] as String
                val ns = if (args.size > 1) args[1]?.toString() ?: "" else ""
                tag.getProperty(key, ns)
            }
            "getPropertyOrNull" -> NativeCallable("getPropertyOrNull") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("getPropertyOrNull() requires a String key", loc)
                val key = args[0] as String
                val ns = if (args.size > 1) args[1]?.toString() ?: "" else ""
                tag.getPropertyOrNull(key, ns)
            }
            "hasProperty" -> NativeCallable("hasProperty") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("hasProperty() requires a String key", loc)
                val key = args[0] as String
                val ns = if (args.size > 1) args[1]?.toString() ?: "" else ""
                tag.hasProperty(key, ns)
            }
            "getProperties" -> NativeCallable("getProperties") { _, _ ->
                // Convert Map<NSID, Any?> to Map<String, Any?> for KS ease-of-use
                val map = mutableMapOf<String, Any?>()
                for ((k, v) in tag.getProperties()) {
                    map[k.toString()] = v
                }
                map
            }
            "getPropertiesMap" -> NativeCallable("getPropertiesMap") { _, _ ->
                tag.getPropertiesMap().toMutableMap()
            }
            "getChildrenValues" -> NativeCallable("getChildrenValues") { _, _ ->
                tag.getChildrenValues<Any?>().map { it.toMutableList() }.toMutableList()
            }

            // Fall through to Call members for inherited functionality
            else -> getCallMember(tag, member, location)
        }
    }

    /**
     * Access members on a [Currency] instance.
     *
     * Properties: `symbol`, `prefixSymbol`, `currencyName`, `hasPrefixSymbol`,
     *             `isCrypto`, `isFiat`, `currencySuffix`, `currencyString`,
     *             `baseUnit`, `unicode`
     */
    fun getCurrencyMember(currency: Currency, member: String, location: SourceLocation?): Any? {
        return when (member) {
            "symbol" -> currency.symbol
            "prefixSymbol" -> currency.prefixSymbol?.toString()
            "currencyName" -> currency.currencyName
            "hasPrefixSymbol" -> currency.hasPrefixSymbol
            "isCrypto" -> currency.isCrypto
            "isFiat" -> currency.isFiat
            "currencySuffix" -> currency.currencySuffix
            "currencyString" -> currency.currencyString
            "baseUnit" -> currency.baseUnit
            "unicode" -> currency.unicode
            else -> throw MemberNotFoundError(member, "Currency", location)
        }
    }

    // ========================================================================
    // Email Members
    // ========================================================================

    /**
     * Access members on an [Email] instance.
     *
     * Properties: `address`, `localPart`, `domain`, `tld`, `hasTag`, `tag`,
     *             `baseLocalPart`
     * Methods: `withoutTag()`, `withTag(tag)`, `equalsIgnoreDomainCase(other)`
     */
    fun getEmailMember(email: Email, member: String, location: SourceLocation?): Any? {
        return when (member) {
            // Properties
            "address" -> email.address
            "localPart" -> email.localPart
            "domain" -> email.domain
            "tld" -> email.tld
            "hasTag" -> email.hasTag
            "tag" -> email.tag
            "baseLocalPart" -> email.baseLocalPart

            // Methods
            "withoutTag" -> NativeCallable("withoutTag") { _, _ -> email.withoutTag() }
            "withTag" -> NativeCallable("withTag") { args, loc ->
                if (args.isEmpty() || args[0] !is String)
                    throw RuntimeError("Email.withTag() requires a String argument", loc)
                email.withTag(args[0] as String)
            }
            "equalsIgnoreDomainCase" -> NativeCallable("equalsIgnoreDomainCase") { args, loc ->
                if (args.isEmpty() || args[0] !is Email)
                    throw RuntimeError("Email.equalsIgnoreDomainCase() requires an Email argument", loc)
                email.equalsIgnoreDomainCase(args[0] as Email)
            }

            else -> throw MemberNotFoundError(member, "Email", location)
        }
    }

    // ========================================================================
    // GeoPoint Members
    // ========================================================================

    /**
     * Access members on a [GeoPoint] instance.
     *
     * Properties: `latitude`, `longitude`, `altitude`, `lat`, `lon`, `alt`,
     *             `hasAltitude`, `isOrigin`, `isNorthern`, `isSouthern`,
     *             `isEastern`, `isWestern`
     * Methods: `distanceTo(other)`, `bearingTo(other)`,
     *          `destination(distanceKm, bearing)`, `withAltitude(meters)`,
     *          `withoutAltitude()`, `toDecimalDegrees()`, `toDMS()`,
     *          `toCompactString()`
     */
    fun getGeoPointMember(geoPoint: GeoPoint, member: String, location: SourceLocation?): Any? {
        return when (member) {
            // Properties
            "latitude" -> geoPoint.latitude
            "longitude" -> geoPoint.longitude
            "altitude" -> geoPoint.altitude
            "lat" -> geoPoint.lat
            "lon" -> geoPoint.lon
            "alt" -> geoPoint.alt
            "hasAltitude" -> geoPoint.hasAltitude
            "isOrigin" -> geoPoint.isOrigin
            "isNorthern" -> geoPoint.isNorthern
            "isSouthern" -> geoPoint.isSouthern
            "isEastern" -> geoPoint.isEastern
            "isWestern" -> geoPoint.isWestern

            // Methods
            "distanceTo" -> NativeCallable("distanceTo") { args, loc ->
                if (args.isEmpty() || args[0] !is GeoPoint)
                    throw RuntimeError("GeoPoint.distanceTo() requires a GeoPoint argument", loc)
                geoPoint.distanceTo(args[0] as GeoPoint)
            }
            "bearingTo" -> NativeCallable("bearingTo") { args, loc ->
                if (args.isEmpty() || args[0] !is GeoPoint)
                    throw RuntimeError("GeoPoint.bearingTo() requires a GeoPoint argument", loc)
                geoPoint.bearingTo(args[0] as GeoPoint)
            }
            "destination" -> NativeCallable("destination") { args, loc ->
                if (args.size < 2)
                    throw RuntimeError("GeoPoint.destination() requires (distanceKm, bearing)", loc)
                geoPoint.destination(
                    toDoubleArg(args[0], "distanceKm", loc),
                    toDoubleArg(args[1], "bearing", loc)
                )
            }
            "withAltitude" -> NativeCallable("withAltitude") { args, loc ->
                if (args.isEmpty())
                    throw RuntimeError("GeoPoint.withAltitude() requires a number argument", loc)
                geoPoint.withAltitude(toDoubleArg(args[0], "altitude", loc))
            }
            "withoutAltitude" -> NativeCallable("withoutAltitude") { _, _ ->
                geoPoint.withoutAltitude()
            }
            "toDecimalDegrees" -> NativeCallable("toDecimalDegrees") { _, _ ->
                geoPoint.toDecimalDegrees()
            }
            "toDMS" -> NativeCallable("toDMS") { _, _ ->
                geoPoint.toDMS()
            }
            "toCompactString" -> NativeCallable("toCompactString") { _, _ ->
                geoPoint.toCompactString()
            }

            else -> throw MemberNotFoundError(member, "GeoPoint", location)
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    companion object {
        /**
         * Safely convert a value to Int for use in constructors.
         */
        fun toIntArg(value: Any?, paramName: String, location: SourceLocation?): Int {
            return when (value) {
                is Int -> value
                is Long -> value.toInt()
                is Number -> value.toInt()
                else -> throw RuntimeError(
                    "'$paramName' must be a number, got ${value?.let { it::class.simpleName } ?: "nil"}",
                    location
                )
            }
        }

        /**
         * Safely convert a value to Double for use in constructors.
         */
        fun toDoubleArg(value: Any?, paramName: String, location: SourceLocation?): Double {
            return when (value) {
                is Double -> value
                is Float -> value.toDouble()
                is Int -> value.toDouble()
                is Long -> value.toDouble()
                is Dec -> value.toDouble()
                is Number -> value.toDouble()
                else -> throw RuntimeError(
                    "'$paramName' must be a number, got ${value?.let { it::class.simpleName } ?: "nil"}",
                    location
                )
            }
        }
    }
}

// ============================================================================
// NativeTypeConstructor
// ============================================================================

/**
 * A native Ki type exposed to the KS language.
 *
 * Acts as both:
 * 1. A **constructor** — `Blob("hello")` invokes [call]
 * 2. A **static member container** — `Blob.empty()` resolves via [getStatic]
 *
 * When referenced as an identifier (e.g., just `Blob`), it appears as a type
 * similar to a [KSClass]. It can be used in `is` checks and reflection.
 *
 * @property typeName The KS type name (e.g., "Blob", "NSID")
 * @property construct The constructor lambda
 * @property statics Map of static members (methods and constants)
 */
class NativeTypeConstructor(
    val typeName: String,
    private val construct: (List<Any?>, SourceLocation?) -> Any?,
    private val statics: Map<String, Any?> = emptyMap()
) : Callable {

    override fun call(
        interpreter: Interpreter,
        arguments: List<Any?>,
        location: SourceLocation?
    ): Any? {
        return construct(arguments, location)
    }

    /**
     * Look up a static member by name.
     *
     * @return The static member value, or null if not found
     */
    fun getStatic(name: String): Any? = statics[name]

    /**
     * Check if a static member exists.
     */
    fun hasStatic(name: String): Boolean = name in statics

    override fun toString(): String = "class $typeName"
}