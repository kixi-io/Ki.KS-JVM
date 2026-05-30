package io.kixi.ks.completion

/**
 * Public, read-only type/member query surface for editor tooling
 * (code completion, hover, the future LSP / IntelliJ plugin).
 *
 * A [TypeModel] is produced by `KS.buildTypeModel(source)` — a
 * side-effect-free pass that lexes, parses, and evaluates **only** the
 * type declarations in a buffer (classes, structs, traits, enums) plus
 * its `use` imports, without executing any statement bodies or static
 * initialisers. See `KS.buildTypeModel` for the construction contract.
 *
 * This interface is the entire boundary LabNexus (and any other
 * consumer) sees. The runtime objects behind a [ResolvedType]
 * (`KSClass`, `JVMClassProxy`, etc.) are intentionally **not** exposed:
 * a consumer resolves a name to an opaque [ResolvedType], then asks for
 * its [members]. Nothing in `io.kixi.ks.interp` leaks across the module
 * boundary.
 *
 * ## Contract
 *
 * - Every method is pure and may be called freely (e.g. on each
 *   keystroke). Implementations never throw on malformed input; a
 *   partial or empty model is returned instead.
 * - Results are stable for a given source string. Consumers may
 *   memoise a [TypeModel] keyed on buffer text.
 *
 * ## Slice 1 scope
 *
 * Member enumeration covers instance methods and properties with
 * inheritance flattened (KS superclass chain + traits; JVM
 * superclass/interface chain). Statics, companion members, and enum
 * constants are enumerated for type-name / object receivers. Chained
 * receivers and full expression type inference are out of scope and
 * handled consumer-side or in later slices.
 */
interface TypeModel {

    /**
     * Resolve a type *name* to an opaque [ResolvedType], or `null` if no
     * type with that name is visible.
     *
     * Resolution order:
     * 1. In-buffer KS declarations — class, struct, trait, enum.
     * 2. `use` imports — JVM classes and KS types brought into scope.
     * 3. Native Ki types.
     * 4. Builtin sentinels — `String`, `Int`, `Double`, `Bool`, etc.
     *
     * @param name The simple type name as written in source (e.g. `Person`,
     *             `String`). Generic arguments and nullability markers must
     *             be stripped by the caller before resolution.
     */
    fun resolveType(name: String): ResolvedType?

    /**
     * Enumerate the members of [type], with inheritance already
     * flattened.
     *
     * For instance receivers this returns instance methods and
     * properties (own + inherited). For type-name / object receivers it
     * returns statics, companion members, and — for enums — constants.
     * The caller filters by [MemberKind] as appropriate for the trigger
     * context.
     *
     * Within a KS inheritance chain the nearest declaration of a given
     * name wins. JVM overloads (same name, different parameters) are
     * returned as distinct items, each with its own signature.
     */
    fun members(type: ResolvedType): List<MemberItem>

    /**
     * Callables visible at top level for prefix completion: top-level
     * function declarations in the buffer plus free / imported
     * functions brought in via `use`.
     *
     * In-scope locals and parameters are **not** included here — those
     * depend on the caret position and are resolved consumer-side via a
     * scope-at-caret walk.
     */
    fun topLevelCallables(): List<MemberItem>

    /**
     * Names of all types visible for prefix completion — in-buffer KS
     * classes, structs, traits, and enums, plus imported and native
     * type names.
     */
    fun typeNames(): List<String>
}

/**
 * An opaque handle to a type resolved by a [TypeModel].
 *
 * Carries only the public-safe descriptors a consumer needs for display
 * and filtering ([name], [kind]); the underlying runtime representation
 * is held by an internal implementation and is never exposed. Obtain one
 * via [TypeModel.resolveType] and pass it back to [TypeModel.members].
 */
sealed interface ResolvedType {

    /** The simple type name (e.g. `Person`, `String`). */
    val name: String

    /** The category of type, for display and for choosing display affordances. */
    val kind: TypeKind
}

/**
 * The category of a [ResolvedType]. Distinguishes KS-native kinds from
 * JVM-interop kinds and the builtin/native sentinels.
 */
enum class TypeKind {
    CLASS,
    STRUCT,
    TRAIT,
    ENUM,
    OBJECT,
    INTERFACE,
    BUILTIN,
    NATIVE
}

/**
 * A single enumerated member of a type, or a top-level callable.
 *
 * The [signature] is a fully rendered, display-ready string matching the
 * form used by the language's `.members` output — e.g.
 * `greet(name: String): String` for a method, `age: Int` for a property,
 * `Red` for an enum constant. Consumers render [name] and [signature]
 * directly and use [kind] to drive iconography and accept-time behaviour
 * (parameter parens for callables, none for properties).
 *
 * @property name          The bare member name used for prefix filtering
 *                         and as the base insert text (e.g. `greet`).
 * @property kind          The member category.
 * @property signature     Display-ready signature string.
 * @property declaringType The type that declares this member, for the
 *                         dimmed right-hand "from X" affordance. `null`
 *                         for top-level / free functions.
 * @property origin        Where the member came from — KS source, JVM
 *                         reflection, a builtin table, or a native Ki type.
 */
data class MemberItem(
    val name: String,
    val kind: MemberKind,
    val signature: String,
    val declaringType: String?,
    val origin: MemberOrigin
)

/**
 * The category of a [MemberItem].
 *
 * [FUNCTION] denotes a free / top-level function (no receiver type),
 * distinct from [METHOD], which is a member of a type.
 */
enum class MemberKind {
    METHOD,
    FUNCTION,
    PROPERTY,
    STATIC_METHOD,
    STATIC_PROPERTY,
    ENUM_CONSTANT
}

/**
 * The provenance of a [MemberItem], so consumers can distinguish
 * KS-defined members from JVM-interop and builtin members if they wish
 * to style or order them differently.
 */
enum class MemberOrigin {
    KS,
    JVM,
    BUILTIN,
    NATIVE
}