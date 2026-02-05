package io.kixi.ks.interp

import io.kixi.ks.SourceLocation
import io.kixi.ks.parser.Constraint
import io.kixi.ks.parser.TypeRef
import io.kixi.ks.*

/**
 * Scoped environment for variable bindings.
 *
 * Manages:
 * - Variable storage with values
 * - Mutability tracking (var vs let)
 * - Type metadata for future type checking
 * - Constraint metadata for runtime validation
 * - Lexical scoping via parent chain
 *
 * The Environment is the core data structure for the Interpreter's name resolution.
 * Each scope (function body, block, loop body, etc.) gets its own Environment that
 * links to its enclosing scope.
 *
 * ## Design Notes
 *
 * Values are stored as raw Kotlin types (Int, String, List, etc.) — no wrapping.
 * Type metadata and constraints are stored separately from values for efficiency.
 * This allows `var x = 5` to literally store a Kotlin Int, with KS-specific
 * behavior (constraints, type checking) handled through the metadata layer.
 *
 * ## Usage
 *
 * ```kotlin
 * val global = Environment()
 * global.define("PI", 3.14159, mutable = false)
 *
 * val local = global.child()
 * local.define("x", 10, mutable = true)
 * local.assign("x", 20)  // OK
 *
 * local.get("PI")  // 3.14159 (from parent)
 * local.assign("PI", 3.0)  // throws ImmutableAssignmentError
 * ```
 *
 * @param parent The enclosing scope, or null for the global scope.
 * @param name Optional name for debugging (e.g., "global", "function:foo", "block")
 */
class Environment(
    private val parent: Environment? = null,
    val name: String = "scope"
) {
    /**
     * Metadata for a variable binding.
     */
    private data class Binding(
        var value: Any?,
        val mutable: Boolean,
        val type: TypeRef?,
        val constraint: Constraint?,
        val definedAt: SourceLocation?
    )

    /** Variable bindings in this scope. */
    private val bindings = mutableMapOf<String, Binding>()

    /** Function definitions in this scope (separate namespace for clarity). */
    private val functions = mutableMapOf<String, KSFunction>()

    // ========================================================================
    // Variable Operations
    // ========================================================================

    /**
     * Define a new variable in this scope.
     *
     * @param name Variable name
     * @param value Initial value (can be null for uninitialized nullable vars)
     * @param mutable True for `var`, false for `let`
     * @param type Optional type annotation
     * @param constraint Optional constraint
     * @param location Source location for error reporting
     * @throws RedefinitionError if the name is already defined in this scope
     */
    fun define(
        name: String,
        value: Any?,
        mutable: Boolean,
        type: TypeRef? = null,
        constraint: Constraint? = null,
        location: SourceLocation? = null
    ) {
        if (name in bindings) {
            throw RedefinitionError(name, location)
        }
        bindings[name] = Binding(value, mutable, type, constraint, location)
    }

    /**
     * Get the value of a variable.
     *
     * Searches this scope and all ancestor scopes.
     *
     * @param name Variable name
     * @param location Source location for error reporting
     * @return The variable's current value
     * @throws UndefinedNameError if the variable is not found
     */
    fun get(name: String, location: SourceLocation? = null): Any? {
        val binding = resolve(name)
        if (binding != null) return binding.value
        throw UndefinedNameError(name, NameKind.VARIABLE, location)
    }

    /**
     * Check if a variable is defined (in this scope or any ancestor).
     */
    fun isDefined(name: String): Boolean = resolve(name) != null

    /**
     * Check if a variable is mutable.
     *
     * @throws UndefinedNameError if the variable is not found
     */
    fun isMutable(name: String, location: SourceLocation? = null): Boolean {
        val binding = resolve(name)
            ?: throw UndefinedNameError(name, NameKind.VARIABLE, location)
        return binding.mutable
    }

    /**
     * Assign a new value to an existing variable.
     *
     * Searches this scope and all ancestor scopes. The assignment happens
     * in the scope where the variable was defined.
     *
     * @param name Variable name
     * @param value New value
     * @param location Source location for error reporting
     * @throws UndefinedNameError if the variable is not found
     * @throws ImmutableAssignmentError if the variable is immutable (let)
     */
    fun assign(name: String, value: Any?, location: SourceLocation? = null) {
        val scope = findScope(name)
        if (scope == null) {
            throw UndefinedNameError(name, NameKind.VARIABLE, location)
        }

        val binding = scope.bindings[name]!!
        if (!binding.mutable) {
            throw ImmutableAssignmentError(name, location)
        }

        binding.value = value
    }

    /**
     * Get the type annotation for a variable (if any).
     */
    fun getType(name: String): TypeRef? = resolve(name)?.type

    /**
     * Get the constraint for a variable (if any).
     */
    fun getConstraint(name: String): Constraint? = resolve(name)?.constraint

    // ========================================================================
    // Function Operations
    // ========================================================================

    /**
     * Define a function in this scope.
     *
     * Functions have their own namespace, so a function and variable can share
     * the same name (though this is discouraged for clarity).
     */
    fun defineFunction(name: String, function: KSFunction, location: SourceLocation? = null) {
        if (name in functions) {
            throw RedefinitionError(name, location)
        }
        functions[name] = function
    }

    /**
     * Get a function by name.
     *
     * Searches this scope and all ancestor scopes.
     */
    fun getFunction(name: String, location: SourceLocation? = null): KSFunction {
        var current: Environment? = this
        while (current != null) {
            current.functions[name]?.let { return it }
            current = current.parent
        }
        throw UndefinedNameError(name, NameKind.FUNCTION, location)
    }

    /**
     * Check if a function is defined.
     */
    fun isFunctionDefined(name: String): Boolean {
        var current: Environment? = this
        while (current != null) {
            if (name in current.functions) return true
            current = current.parent
        }
        return false
    }

    // ========================================================================
    // Scope Operations
    // ========================================================================

    /**
     * Create a child scope.
     *
     * Used when entering a new block, function body, loop, etc.
     */
    fun child(scopeName: String = "block"): Environment = Environment(this, scopeName)

    /**
     * Get the parent scope (for debugging/introspection).
     */
    fun parent(): Environment? = parent

    /**
     * Get the depth of this scope (0 for global).
     */
    fun depth(): Int {
        var d = 0
        var current = parent
        while (current != null) {
            d++
            current = current.parent
        }
        return d
    }

    /**
     * Get the global scope (root of the parent chain).
     */
    fun global(): Environment {
        var current: Environment = this
        while (current.parent != null) {
            current = current.parent!!
        }
        return current
    }

    /**
     * Get all variable names defined in this scope (not ancestors).
     */
    fun localNames(): Set<String> = bindings.keys.toSet()

    /**
     * Get all variable names visible from this scope (including ancestors).
     */
    fun allNames(): Set<String> {
        val names = mutableSetOf<String>()
        var current: Environment? = this
        while (current != null) {
            names.addAll(current.bindings.keys)
            current = current.parent
        }
        return names
    }

    // ========================================================================
    // Iteration Support
    // ========================================================================

    /**
     * Define the implicit `it` variable for simplified for-loops.
     *
     * `for [1, 2, 3] say it` — the `it` variable is bound to each element.
     * This is a convenience method that defines `it` as mutable (it changes
     * each iteration) but should be treated as read-only by user code.
     */
    fun defineIt(value: Any?, location: SourceLocation? = null) {
        // Allow redefining `it` in the same scope (for loop iterations)
        bindings["it"] = Binding(value, mutable = true, type = null, constraint = null, location)
    }

    /**
     * Update the implicit `it` variable.
     */
    fun updateIt(value: Any?) {
        bindings["it"]?.let { it.value = value }
    }

    // ========================================================================
    // Debugging
    // ========================================================================

    /**
     * Create a string representation of this environment for debugging.
     */
    fun dump(): String {
        val sb = StringBuilder()
        sb.appendLine("Environment: $name (depth=${depth()})")

        if (bindings.isEmpty()) {
            sb.appendLine("  (no variables)")
        } else {
            for ((name, binding) in bindings) {
                val mutability = if (binding.mutable) "var" else "let"
                val typeStr = binding.type?.let { ": ${it.name}" } ?: ""
                val constraintStr = binding.constraint?.let { " [constrained]" } ?: ""
                sb.appendLine("  $mutability $name$typeStr$constraintStr = ${binding.value}")
            }
        }

        if (functions.isNotEmpty()) {
            sb.appendLine("  Functions:")
            for ((name, _) in functions) {
                sb.appendLine("    fun $name(...)")
            }
        }

        return sb.toString()
    }

    override fun toString(): String = "Environment($name, depth=${depth()}, vars=${bindings.size})"

    // ========================================================================
    // Private Helpers
    // ========================================================================

    /**
     * Resolve a variable name to its binding, searching up the scope chain.
     */
    private fun resolve(name: String): Binding? {
        var current: Environment? = this
        while (current != null) {
            current.bindings[name]?.let { return it }
            current = current.parent
        }
        return null
    }

    /**
     * Find the scope that contains a variable definition.
     */
    private fun findScope(name: String): Environment? {
        var current: Environment? = this
        while (current != null) {
            if (name in current.bindings) return current
            current = current.parent
        }
        return null
    }

    companion object {
        /**
         * Create a global environment with standard library bindings.
         *
         * This is the typical entry point for creating a new execution context.
         */
        fun global(): Environment = Environment(null, "global")
    }
}