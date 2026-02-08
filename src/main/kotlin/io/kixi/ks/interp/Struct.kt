package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.parser.*

/**
 * Runtime representation of a KS struct (value type).
 *
 * Unlike classes: no superclass, structural equality, copy-on-assign.
 * Structs can implement traits.
 */
class KSStruct(
    val declaration: StructDecl,
    val traits: List<KSTrait>,
    val closure: Environment
) {
    val name: String get() = declaration.name
    val constructorParams: List<ConstructorParam> get() = declaration.constructorParams
    val location: SourceLocation get() = declaration.location

    private val methods = mutableMapOf<String, KSFunction>()
    private val staticMembers = Environment(closure, "static:$name")

    init {
        for (member in declaration.members) {
            when (member) {
                is FunDecl -> methods[member.name] = KSFunction(member, closure)
                is StaticBlock -> { /* initialized by interpreter */ }
                is VarDecl -> { /* handled during instantiation */ }
                else -> {}
            }
        }
    }

    fun findMethod(name: String): KSFunction? {
        methods[name]?.let { return it }
        for (trait in traits) { trait.findMethod(name)?.let { return it } }
        return null
    }

    fun getStatic(name: String): Any? = when {
        staticMembers.isDefined(name) -> staticMembers.get(name)
        staticMembers.isFunctionDefined(name) -> staticMembers.getFunction(name)
        else -> null
    }

    fun hasStatic(name: String): Boolean =
        staticMembers.isDefined(name) || staticMembers.isFunctionDefined(name)

    fun staticEnvironment(): Environment = staticMembers

    fun getPropertyParams(): List<ConstructorParam> =
        constructorParams.filter { it.binding != null }

    fun getInstanceProperties(): List<VarDecl> =
        declaration.members.filterIsInstance<VarDecl>()

    fun implementsTrait(trait: KSTrait): Boolean {
        if (trait in traits) return true
        return traits.any { it.extendsTrait(trait) }
    }

    override fun toString(): String = "struct $name"
}

/**
 * Runtime instance of a KS struct (value type).
 *
 * Structural equality + copy-on-assign.
 */
class KSStructInstance(val struct: KSStruct) {

    private data class PropertyValue(var value: Any?, val mutable: Boolean)

    private val properties = mutableMapOf<String, PropertyValue>()

    fun initProperty(name: String, value: Any?, mutable: Boolean) {
        properties[name] = PropertyValue(value, mutable)
    }

    fun get(name: String, location: SourceLocation? = null): Any? {
        properties[name]?.let { return it.value }
        struct.findMethod(name)?.let { return StructBoundMethod(this, it) }
        throw MemberNotFoundError(name, struct.name, location)
    }

    fun set(name: String, value: Any?, location: SourceLocation? = null) {
        val prop = properties[name]
            ?: throw MemberNotFoundError(name, struct.name, location)
        if (!prop.mutable) throw ImmutableAssignmentError(name, location)
        prop.value = value
    }

    fun hasProperty(name: String): Boolean = name in properties
    fun isMutable(name: String): Boolean = properties[name]?.mutable == true
    fun propertyNames(): Set<String> = properties.keys.toSet()

    /** Shallow copy \u2014 core of copy-on-assign semantics. */
    fun copy(): KSStructInstance {
        val clone = KSStructInstance(struct)
        for ((name, prop) in properties) {
            clone.properties[name] = PropertyValue(prop.value, prop.mutable)
        }
        return clone
    }

    /** Copy with overrides for named-arg copy(): `p1.copy(x = 10.0)` */
    fun copyWith(overrides: Map<String, Any?>, location: SourceLocation? = null): KSStructInstance {
        val clone = copy()
        for ((name, value) in overrides) {
            val prop = clone.properties[name]
                ?: throw MemberNotFoundError(name, struct.name, location,
                    "Struct '${struct.name}' has no property '$name'")
            clone.properties[name] = PropertyValue(value, prop.mutable)
        }
        return clone
    }

    // --- Structural equality ---

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KSStructInstance) return false
        if (struct !== other.struct) return false
        for ((name, prop) in properties) {
            if (prop.value != other.properties[name]?.value) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var hash = struct.name.hashCode()
        for ((name, prop) in properties.entries.sortedBy { it.key }) {
            hash = 31 * hash + name.hashCode()
            hash = 31 * hash + (prop.value?.hashCode() ?: 0)
        }
        return hash
    }

    override fun toString(): String {
        val props = properties.entries.joinToString(", ") { (k, v) ->
            "$k=${when (v.value) { null -> "nil"; is String -> "\"${v.value}\""; is Char -> "'${v.value}'"; else -> v.value.toString() }}"
        }
        return "${struct.name}($props)"
    }
}

/** Bound method on a struct instance. */
class StructBoundMethod(
    val receiver: KSStructInstance,
    val method: KSFunction
) : Callable {
    override fun call(interpreter: Interpreter, arguments: List<Any?>, location: SourceLocation?): Any? =
        interpreter.callStructMethod(receiver, method, arguments, location)

    override fun toString(): String = "<bound method ${method.name} of ${receiver.struct.name}>"
}

/** Auto-generated copy() callable for struct instances. */
class StructCopyCallable(val receiver: KSStructInstance) : Callable {
    override fun call(interpreter: Interpreter, arguments: List<Any?>, location: SourceLocation?): Any? =
        receiver.copy()

    override fun toString(): String = "<copy of ${receiver.struct.name}>"
}