package io.kixi.ks.interp

import io.kixi.ks.SourceLocation
import io.kixi.ks.parser.*
import io.kixi.ks.KSFunction

/**
 * Runtime representation of a KS class.
 *
 * KSClass holds the class declaration along with its resolved superclass
 * and implemented traits. It serves as a factory for creating [KSObject]
 * instances and provides method/property lookup.
 *
 * ## Class Structure
 *
 * ```ks
 * class Person(let name: String, var age: Int = 0): Printable {
 *     fun greet() = say "Hello, I'm $name"
 *
 *     static {
 *         fun create(name: String) = Person(name, 0)
 *     }
 * }
 * ```
 *
 * - Constructor parameters with `let`/`var` become instance properties
 * - Constructor parameters without binding are constructor-only
 * - Methods are stored in the class and looked up on instances
 * - Static members are stored separately
 *
 * @property declaration The AST node for the class
 * @property superclass Optional superclass (single inheritance)
 * @property traits Implemented traits
 * @property closure Environment where the class was defined
 */
class KSClass(
    val declaration: ClassDecl,
    val superclass: KSClass?,
    val traits: List<KSTrait>,
    val closure: Environment
) {
    /** Class name. */
    val name: String get() = declaration.name

    /** Constructor parameters (may include property bindings). */
    val constructorParams: List<ConstructorParam> get() = declaration.constructorParams

    /** Instance methods defined in this class. */
    private val methods = mutableMapOf<String, KSFunction>()

    /** Extension methods added via `extend` blocks (tracked separately). */
    private val extensionMethods = mutableMapOf<String, KSFunction>()

    /** Static members (variables and functions). */
    private val staticMembers = Environment(closure, "static:$name")

    /** Source location. */
    val location: SourceLocation get() = declaration.location

    init {
        // Extract methods from class body
        for (member in declaration.members) {
            when (member) {
                is FunDecl -> {
                    val fn = KSFunction(member, closure)
                    methods[member.name] = fn
                }
                is StaticBlock -> {
                    // Process static block members
                    for (staticMember in member.members) {
                        when (staticMember) {
                            is FunDecl -> {
                                // Static functions are initialized by the interpreter
                                // in initializeStaticMembers(). Do NOT pre-define here
                                // — that causes RedefinitionError.
                            }
                            is VarDecl -> {
                                // Static variables are initialized by the interpreter
                                // in initializeStaticMembers() where initializers can
                                // be evaluated. Do NOT pre-define here — that causes
                                // RedefinitionError when the interpreter calls define().
                            }
                            else -> { /* ignore other members in static block */ }
                        }
                    }
                }
                is VarDecl -> {
                    // Instance property with initializer (not from constructor)
                    // These are handled during instantiation
                }
                else -> { /* ignore other members for now */ }
            }
        }
    }

    /**
     * Look up a method by name, checking this class and superclasses.
     */
    fun findMethod(name: String): KSFunction? {
        methods[name]?.let { return it }
        superclass?.findMethod(name)?.let { return it }

        // Check traits for default implementations
        for (trait in traits) {
            trait.findMethod(name)?.let { return it }
        }

        return null
    }

    /**
     * Add an extension method to this class.
     */
    fun addMethod(name: String, fn: KSFunction) {
        methods[name] = fn
        extensionMethods[name] = fn
    }

    /**
     * Get the set of extension method names.
     */
    fun extensionMethodNames(): Set<String> = extensionMethods.keys.toSet()

    /**
     * Get an extension method by name.
     */
    fun getExtensionMethod(name: String): KSFunction? = extensionMethods[name]

    /**
     * Get a static member (variable or function).
     */
    fun getStatic(name: String): Any? {
        return if (staticMembers.isDefined(name)) {
            staticMembers.get(name)
        } else if (staticMembers.isFunctionDefined(name)) {
            staticMembers.getFunction(name)
        } else {
            superclass?.getStatic(name)
        }
    }

    /**
     * Check if a static member exists.
     */
    fun hasStatic(name: String): Boolean {
        return staticMembers.isDefined(name) ||
                staticMembers.isFunctionDefined(name) ||
                superclass?.hasStatic(name) == true
    }

    /**
     * Get the static environment for initialization.
     */
    fun staticEnvironment(): Environment = staticMembers

    /**
     * Get property declarations from constructor params that have bindings.
     */
    fun getPropertyParams(): List<ConstructorParam> {
        return constructorParams.filter { it.binding != null }
    }

    /**
     * Get instance property declarations from class body.
     */
    fun getInstanceProperties(): List<VarDecl> {
        return declaration.members.filterIsInstance<VarDecl>()
    }

    /**
     * Check if this class is a subclass of another.
     */
    fun isSubclassOf(other: KSClass): Boolean {
        if (this === other) return true
        return superclass?.isSubclassOf(other) == true
    }

    /**
     * Check if this class implements a trait.
     */
    fun implementsTrait(trait: KSTrait): Boolean {
        if (trait in traits) return true
        for (t in traits) {
            if (t.extendsTrait(trait)) return true
        }
        return superclass?.implementsTrait(trait) == true
    }

    override fun toString(): String = "class $name"
}

/**
 * Runtime representation of a KS trait.
 *
 * Traits define abstract methods and can provide default implementations.
 * Unlike classes, traits cannot be instantiated directly.
 *
 * ```ks
 * trait Animal {
 *     fun name(): String                    // abstract
 *     fun speak() = say "${name()} speaks"  // default impl
 * }
 * ```
 */
class KSTrait(
    val declaration: TraitDecl,
    val superTraits: List<KSTrait>,
    val closure: Environment
) {
    /** Trait name. */
    val name: String get() = declaration.name

    /** Methods (abstract and default implementations). */
    private val methods = mutableMapOf<String, KSFunction>()

    /** Extension methods added via `extend` blocks. */
    private val extensionMethods = mutableMapOf<String, KSFunction>()

    /** Abstract method names (no body). */
    private val abstractMethods = mutableSetOf<String>()

    /** Source location. */
    val location: SourceLocation get() = declaration.location

    init {
        for (member in declaration.members) {
            when (member) {
                is FunDecl -> {
                    val fn = KSFunction(member, closure)
                    methods[member.name] = fn
                    if (member.body == null) {
                        abstractMethods.add(member.name)
                    }
                }
                else -> { /* traits only have methods */ }
            }
        }
    }

    /**
     * Look up a method by name.
     */
    fun findMethod(name: String): KSFunction? {
        methods[name]?.let { return it }
        for (superTrait in superTraits) {
            superTrait.findMethod(name)?.let { return it }
        }
        return null
    }

    /**
     * Add an extension method (default implementation) to this trait.
     */
    fun addMethod(name: String, fn: KSFunction) {
        methods[name] = fn
        extensionMethods[name] = fn
    }

    /**
     * Get the set of extension method names.
     */
    fun extensionMethodNames(): Set<String> = extensionMethods.keys.toSet()

    /**
     * Get an extension method by name.
     */
    fun getExtensionMethod(name: String): KSFunction? = extensionMethods[name]

    /**
     * Check if a method is abstract (no default implementation).
     */
    fun isAbstract(methodName: String): Boolean = methodName in abstractMethods

    /**
     * Get all abstract method names that must be implemented.
     */
    fun abstractMethodNames(): Set<String> {
        val result = abstractMethods.toMutableSet()
        for (superTrait in superTraits) {
            result.addAll(superTrait.abstractMethodNames())
        }
        return result
    }

    /**
     * Check if this trait extends another.
     */
    fun extendsTrait(other: KSTrait): Boolean {
        if (this === other) return true
        for (superTrait in superTraits) {
            if (superTrait.extendsTrait(other)) return true
        }
        return false
    }

    override fun toString(): String = "trait $name"
}