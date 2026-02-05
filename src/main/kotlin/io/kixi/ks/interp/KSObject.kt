package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.parser.BindingType

/**
 * Runtime representation of a KS class instance.
 *
 * KSObject holds the instance's property values and provides access to
 * methods through its class. Each instance has its own property storage
 * but shares method definitions with other instances of the same class.
 *
 * ## Property Storage
 *
 * Properties come from two sources:
 * 1. Constructor parameters with `let`/`var` binding
 * 2. Instance property declarations in the class body
 *
 * ```ks
 * class Person(let name: String, var age: Int = 0) {
 *     var greeting = "Hello"  // instance property
 * }
 *
 * let p = Person("Alice", 30)
 * p.name      // from constructor (immutable)
 * p.age       // from constructor (mutable)
 * p.greeting  // from class body (mutable)
 * ```
 *
 * @property klass The class this is an instance of
 */
class KSObject(val klass: KSClass) {

    /** Instance property storage. */
    private val properties = mutableMapOf<String, PropertyValue>()

    /**
     * Property value with mutability tracking.
     */
    private data class PropertyValue(
        var value: Any?,
        val mutable: Boolean
    )

    /**
     * Initialize a property.
     *
     * Called during construction to set up properties from constructor
     * parameters and class body declarations.
     */
    fun initProperty(name: String, value: Any?, mutable: Boolean) {
        properties[name] = PropertyValue(value, mutable)
    }

    /**
     * Get a property value.
     *
     * @throws MemberNotFoundError if the property doesn't exist
     */
    fun get(name: String, location: SourceLocation? = null): Any? {
        properties[name]?.let { return it.value }

        // Check for method (return bound method)
        klass.findMethod(name)?.let { method ->
            return BoundMethod(this, method)
        }

        throw MemberNotFoundError(name, klass.name, location)
    }

    /**
     * Set a property value.
     *
     * @throws MemberNotFoundError if the property doesn't exist
     * @throws ImmutableAssignmentError if the property is immutable
     */
    fun set(name: String, value: Any?, location: SourceLocation? = null) {
        val prop = properties[name]
            ?: throw MemberNotFoundError(name, klass.name, location)

        if (!prop.mutable) {
            throw ImmutableAssignmentError(name, location)
        }

        prop.value = value
    }

    /**
     * Check if a property exists.
     */
    fun hasProperty(name: String): Boolean = name in properties

    /**
     * Check if a property is mutable.
     */
    fun isMutable(name: String): Boolean = properties[name]?.mutable == true

    /**
     * Get all property names.
     */
    fun propertyNames(): Set<String> = properties.keys.toSet()

    /**
     * Check if this object is an instance of a class (including superclasses).
     */
    fun isInstanceOf(cls: KSClass): Boolean = klass.isSubclassOf(cls)

    /**
     * Check if this object implements a trait.
     */
    fun implementsTrait(trait: KSTrait): Boolean = klass.implementsTrait(trait)

    override fun toString(): String {
        val props = properties.entries.joinToString(", ") { (k, v) ->
            "$k=${formatValue(v.value)}"
        }
        return "${klass.name}($props)"
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
 * A method bound to a specific instance.
 *
 * When you access a method on an object (`obj.method`), you get a BoundMethod
 * that remembers both the method and the receiver. When called, it automatically
 * provides `this` binding.
 *
 * ```ks
 * class Counter(var count: Int = 0) {
 *     fun increment() { count = count + 1 }
 * }
 *
 * let c = Counter()
 * let inc = c.increment  // BoundMethod
 * inc()                  // calls with c as 'this'
 * ```
 */
class BoundMethod(
    val receiver: KSObject,
    val method: KSFunction
) : Callable {

    override fun call(interpreter: Interpreter, arguments: List<Any?>, location: SourceLocation?): Any? {
        return interpreter.callMethod(receiver, method, arguments, location)
    }

    override fun toString(): String = "<bound method ${method.name} of ${receiver.klass.name}>"
}