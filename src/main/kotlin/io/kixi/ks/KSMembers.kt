package io.kixi.ks

/**
 * A host (Kotlin) object that exposes a chosen set of members to KS
 * **without reflection**, so they resolve in every [KSRuntime.hostLang]
 * mode — including the portable mode a host uses for untrusted code.
 *
 * ## Why this exists
 *
 * With `hostLang = true`, member access on any JVM object falls through
 * to the universal reflection backstop, which resolves every public
 * member of every reachable object. That is the right tool for
 * scripting against the host, and the wrong one for a document whose
 * expressions evaluate on load: `"x".getClass()` reaches `Class`, whose
 * `forName` reaches everything else. A host that wants `molecule.mass`
 * in such a document but not `Runtime.getRuntime()` needs the object to
 * say what it exposes. This interface is that statement.
 *
 * ## Contract
 *
 * The expression evaluator's curated member dispatch tests `is KSMembers`
 * before giving up on a type, and calls [ksMember]. A non-null result is
 * the member's value; `null` means *no such member*, and the evaluator
 * raises `MemberNotFoundError` naming the object's class. An object
 * therefore cannot expose a member whose value is legitimately `nil`
 * through this seam — return a sentinel or a function instead.
 *
 * Returning a [Callable] (typically a [NativeFunction]) makes the member
 * a method: `m.scaled(2)` resolves `scaled` to the callable and the call
 * evaluator invokes it with the arguments.
 *
 * Keep [ksMember] cheap — it runs on every access — and side-effect-free.
 *
 * ```kotlin
 * class Molecule(...) : KSMembers {
 *     override fun ksMember(name: String): Any? = when (name) {
 *         "formula" -> formula
 *         "mass" -> molarMass
 *         "atoms" -> atoms
 *         else -> null
 *     }
 * }
 * ```
 */
interface KSMembers {

    /** The value of member [name], or `null` when there is no such member. */
    fun ksMember(name: String): Any?
}