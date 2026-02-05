package io.kixi.ks.parser

import io.kixi.ks.SourceLocation

/*
 * ============================================================================
 * KS Language â€” Abstract Syntax Tree
 * ============================================================================
 *
 * Sealed hierarchy enabling exhaustive pattern matching in Kotlin `when`.
 * Every node carries a [SourceLocation] for error messages and future
 * IntelliJ IDE plugin integration.
 *
 * Design decisions:
 *   - `if`, `when`, `try` are EXPRESSIONS (return values, like Kotlin)
 *   - `for`, `while` are STATEMENTS (do not return values)
 *   - `say` is a STATEMENT with special syntax (keyword, optional dot-variant)
 *   - Constraints are SEPARATE from types (runtime guards, not refinement types)
 *   - KD nodes are embedded directly for `lang KD { ... }` blocks
 *   - `this` is handled by the parser checking identifier name (no lexer keyword)
 *   - DPEC (Dot-Prefixed Enum Constant) e.g. `.SUCCESS` has its own node
 *   - String interpolation is parsed into StringTemplateExpr with parts
 *
 * Organization:
 *   1. Base Node interface
 *   2. Program (top-level container)
 *   3. Declarations (var, fun, class, trait, enum, use, static, extend)
 *   4. Statements (expr-stmt, return, break, continue, throw, say, for, while)
 *   5. Expressions (literals, operators, control flow, access, etc.)
 *   6. Supporting types (Parameter, TypeRef, Constraint, WhenBranch, etc.)
 *   7. KD-specific nodes (for lang blocks)
 */

// ============================================================================
// 1. Base
// ============================================================================

/** Root of the AST hierarchy. Every node has a source location. */
sealed interface Node {
    val location: SourceLocation
}

// ============================================================================
// 2. Program
// ============================================================================

/**
 * A KS source file: a sequence of declarations, statements, and expressions.
 * Top-level expressions are wrapped in [ExprStmt] by the parser.
 */
data class Program(
    val body: List<Node>,
    override val location: SourceLocation = SourceLocation()
) : Node

// ============================================================================
// 3. Declarations
// ============================================================================

sealed interface Decl : Node

/**
 * Variable declaration.
 *
 *     var name = "Akiko"           // mutable, inferred type
 *     let age = 42                 // immutable, inferred type
 *     var height: Double = 68.0    // explicit type
 *     let score: Int > 0 = 100     // with constraint
 *     var item: String?            // nullable, no initializer
 */
data class VarDecl(
    val name: String,
    val mutable: Boolean,           // var = true, let = false
    val typeAnnotation: TypeRef?,
    val constraint: Constraint?,
    val initializer: Expr?,
    override val location: SourceLocation
) : Decl

/**
 * Function declaration.
 *
 *     fun add(a: Int, b: Int): Int { return a + b }  // block body
 *     fun greet(name: String) = "Hello $name"         // single-expression
 *     fun dangerous(level) { ... }                    // untyped param
 *     fun name(): String                              // abstract (body = null)
 */
data class FunDecl(
    val name: String,
    val params: List<Parameter>,
    val returnType: TypeRef?,
    val body: Node?,                // null for abstract trait methods
    val isSingleExpr: Boolean,      // true for `= expr` form
    override val location: SourceLocation
) : Decl

/**
 * Class declaration with optional primary constructor and trait list.
 *
 *     class Dog: Animal { ... }
 *     class Person(let name: String, var age: Int = 0) { ... }
 *     class Point(x: Double, y: Double)
 */
data class ClassDecl(
    val name: String,
    val constructorParams: List<ConstructorParam>,
    val superTypes: List<TypeRef>,
    val members: List<Node>,
    override val location: SourceLocation
) : Decl

/**
 * Trait declaration.
 *
 *     trait Animal {
 *         fun name(): String                        // abstract
 *         fun speak() = say("${name()} speaks")     // default impl
 *     }
 */
data class TraitDecl(
    val name: String,
    val superTraits: List<TypeRef>,
    val members: List<Node>,
    override val location: SourceLocation
) : Decl

/**
 * Enum declaration. Supports multiple forms:
 *
 *     enum Color { RED GREEN BLUE }                              // simple
 *     enum Veggie { Olive=5 Broccoli=10 }                       // with values
 *     enum Fruit: Int { Apple=1 Orange=2 }                      // typed values
 *     enum HttpStatus(code: Int, msg: String) { OK(200, "OK") } // constructor
 */
data class EnumDecl(
    val name: String,
    val valueType: TypeRef?,                 // e.g. Int in `enum Fruit: Int`
    val constructorParams: List<ConstructorParam>,  // e.g. (code: Int, msg: String)
    val constants: List<EnumConstant>,
    val members: List<Node>,                 // methods, properties, static blocks
    override val location: SourceLocation
) : Decl

/**
 * Import declaration.
 *
 *     use io.kixi.kd.Tag
 *     use io.kixi.kd.*
 *     use collections.OrderedMap as OMap
 */
data class UseDecl(
    val path: List<String>,
    val wildcard: Boolean,          // true for .*
    val alias: String?,             // after `as`
    override val location: SourceLocation
) : Decl

/**
 * Static block inside a class or enum.
 *
 *     static {
 *         let PI = 3.14
 *         fun default(): Person = Person("Anonymous", 0)
 *     }
 */
data class StaticBlock(
    val members: List<Node>,
    override val location: SourceLocation
) : Decl

/**
 * Type extension (future-proofed â€” syntax TBD).
 *
 *     extend trait Comparable
 *     extend String { fun isPalindrome(): Bool = ... }
 */
data class ExtendDecl(
    val target: TypeRef,
    val isTraitExtension: Boolean,
    val members: List<Node>,
    override val location: SourceLocation
) : Decl

// ============================================================================
// 4. Statements
// ============================================================================

sealed interface Stmt : Node

/** Expression used as a statement. */
data class ExprStmt(
    val expression: Expr,
    override val location: SourceLocation
) : Stmt

/** `return expr` or bare `return` */
data class ReturnStmt(
    val value: Expr?,
    override val location: SourceLocation
) : Stmt

/** `break` */
data class BreakStmt(
    override val location: SourceLocation
) : Stmt

/** `continue` */
data class ContinueStmt(
    override val location: SourceLocation
) : Stmt

/** `throw expression` */
data class ThrowStmt(
    val expression: Expr,
    override val location: SourceLocation
) : Stmt

/**
 * Say statement with optional format variant.
 *
 *     say "hello"                             // standard
 *     say.error "oops"                        // error (red)
 *     say.warn "caution"                      // warning (orange)
 *     say.note("note", bold=true)             // note (bold)
 *
 * Variant is "error" | "warn" | "note" | null.
 * Parentheses are optional for say arguments.
 */
data class SayStmt(
    val variant: String?,
    val arguments: List<Argument>,
    override val location: SourceLocation
) : Stmt

/**
 * For loop â€” two forms:
 *
 * Traditional:  `for i in list { ... }`   â†’ variable = "i"
 * Simplified:   `for list { ... }`        â†’ variable = null (uses implicit `it`)
 *               `for list say it`         â†’ single-statement body
 *               `for Color { say it }`    â†’ enum iteration
 */
data class ForStmt(
    val variable: String?,          // null for simplified (implicit `it`)
    val iterable: Expr,
    val body: Node,                 // BlockExpr or single Stmt
    override val location: SourceLocation
) : Stmt

/**
 * While loop.
 *
 *     while x > 0 { x-- }
 *     while running doWork()
 */
data class WhileStmt(
    val condition: Expr,
    val body: Node,
    override val location: SourceLocation
) : Stmt

// ============================================================================
// 5. Expressions
// ============================================================================

sealed interface Expr : Node

/**
 * Literal value: `42`, `3.14`, `"hello"`, `'A'`, `true`, `nil`,
 * `<https://...>`, verbatim/multiline/backtick strings.
 *
 * The [value] holds the Kotlin representation (Int, Double, String, etc.).
 * The [kind] disambiguates string variants and numeric types.
 */
data class LiteralExpr(
    val value: Any?,
    val kind: LiteralKind,
    override val location: SourceLocation
) : Expr

enum class LiteralKind {
    INT, LONG, FLOAT, DOUBLE, DEC,
    STRING, CHAR, BOOL, NIL, URL,
    VERBATIM_STRING, MULTILINE_STRING, VERBATIM_MULTILINE, BACKTICK_STRING
}

/**
 * String with interpolation: `"Hello $name, age ${age + 1}"`
 *
 * The parser splits the raw string into alternating literal text segments
 * and embedded expression segments. Verbatim and backtick strings are never
 * templates â€” they always become plain [LiteralExpr].
 */
data class StringTemplateExpr(
    val parts: List<StringPart>,
    val kind: StringTemplateKind,
    override val location: SourceLocation
) : Expr

enum class StringTemplateKind { STANDARD, MULTILINE }

sealed interface StringPart
data class LiteralPart(val text: String) : StringPart
data class ExpressionPart(val expr: Expr) : StringPart

/** Variable or type name reference: `x`, `String`, `MyClass` */
data class IdentifierExpr(
    val name: String,
    override val location: SourceLocation
) : Expr

/** `this` reference inside a class or trait body. */
data class ThisExpr(
    override val location: SourceLocation
) : Expr

/**
 * Prefix or postfix unary operation.
 *
 *     -x       NEGATE (prefix)
 *     !flag    NOT (prefix)
 *     i++      INCREMENT (postfix)
 *     i--      DECREMENT (postfix)
 *     x!!      NON_NULL (postfix)
 *     ++i      INCREMENT (prefix)
 *     --i      DECREMENT (prefix)
 */
data class UnaryExpr(
    val operator: UnaryOp,
    val operand: Expr,
    val prefix: Boolean,
    override val location: SourceLocation
) : Expr

enum class UnaryOp {
    NEGATE,         // -
    NOT,            // !
    INCREMENT,      // ++
    DECREMENT,      // --
    NON_NULL        // !! (postfix only)
}

/**
 * Binary operation.
 *
 * Range operators (.., ..<, <.., <..<) are handled by [RangeExpr] instead,
 * since ranges have additional semantics (open ends, exclusivity flags).
 */
data class BinaryExpr(
    val left: Expr,
    val operator: BinaryOp,
    val right: Expr,
    override val location: SourceLocation
) : Expr

enum class BinaryOp {
    // Arithmetic
    ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, POWER,
    // Comparison
    EQUAL, NOT_EQUAL, LESS, GREATER, LESS_EQUAL, GREATER_EQUAL,
    // Logical
    AND, OR,
    // Null coalescing
    ELVIS
}

/**
 * Assignment expression.
 *
 *     x = 5         ASSIGN
 *     x += 1        PLUS_ASSIGN
 *     arr[0] -= 2   MINUS_ASSIGN
 *     obj.f *= 3    STAR_ASSIGN
 *     x **= 2       POWER_ASSIGN
 *
 * Target must be [IdentifierExpr], [MemberAccessExpr], or [IndexExpr].
 */
data class AssignExpr(
    val target: Expr,
    val operator: AssignOp,
    val value: Expr,
    override val location: SourceLocation
) : Expr

enum class AssignOp {
    ASSIGN,
    PLUS_ASSIGN, MINUS_ASSIGN,
    STAR_ASSIGN, SLASH_ASSIGN, MODULO_ASSIGN,
    POWER_ASSIGN
}

/**
 * Ternary expression: `(condition) ? thenExpr : elseExpr`
 */
data class TernaryExpr(
    val condition: Expr,
    val thenBranch: Expr,
    val elseBranch: Expr,
    override val location: SourceLocation
) : Expr

/**
 * Function or method call.
 *
 *     foo(1, 2)
 *     obj.method(x, name = "Ada")
 *     Person("Alex", 42)
 */
data class CallExpr(
    val callee: Expr,
    val arguments: List<Argument>,
    override val location: SourceLocation
) : Expr

/**
 * Member access: `obj.field` or safe navigation `obj?.field`
 */
data class MemberAccessExpr(
    val obj: Expr,
    val member: String,
    val safe: Boolean,              // true for ?.
    override val location: SourceLocation
) : Expr

/**
 * Index access: `list[0]`, `map["key"]`
 */
data class IndexExpr(
    val obj: Expr,
    val index: Expr,
    override val location: SourceLocation
) : Expr

/**
 * List literal: `[1, 2, 3]` or `[1 2 3]` (commas optional per KD spec).
 */
data class ListExpr(
    val elements: List<Expr>,
    override val location: SourceLocation
) : Expr

/**
 * Map literal: `[name="Rufus", color="rust"]` or `['a'=1 'b'=2]`
 *
 * Disambiguation from lists: if the first element is followed by `=`,
 * the parser treats the entire `[...]` as a map.
 */
data class MapExpr(
    val entries: List<MapEntry>,
    override val location: SourceLocation
) : Expr

data class MapEntry(val key: Expr, val value: Expr)

/**
 * Range expression with open-end and exclusivity support.
 *
 *     1..10       inclusive, both ends present
 *     0..<5       exclusive right
 *     _..100      open left (start = null)
 *     a<..<b      exclusive both
 *     6.._        open right (end = null)
 *
 * Open ends are represented by null start/end. The parser converts
 * the `_` wildcard token into null here.
 */
data class RangeExpr(
    val start: Expr?,               // null for open-left (_..x)
    val end: Expr?,                 // null for open-right (x.._)
    val startExclusive: Boolean,    // true for <.. and <..<
    val endExclusive: Boolean,      // true for ..< and <..<
    override val location: SourceLocation
) : Expr

/**
 * If expression â€” returns a value (like Kotlin).
 *
 *     if condition { thenBlock }
 *     if condition { thenBlock } else { elseBlock }
 *     if n <= 1 return n                          // single-statement
 */
data class IfExpr(
    val condition: Expr,
    val thenBranch: Node,           // BlockExpr or single Stmt/Expr
    val elseBranch: Node?,          // null when no else
    override val location: SourceLocation
) : Expr

/**
 * When expression â€” Kotlin-style exhaustive branching.
 *
 *     when subject { matchers -> body; else -> body }
 *     when { condition -> body }                      // no subject
 */
data class WhenExpr(
    val subject: Expr?,             // null for condition-style when
    val branches: List<WhenBranch>,
    override val location: SourceLocation
) : Expr

/**
 * Try expression â€” returns the value of the body or catch block.
 *
 *     try { expr } catch(e: Type) { handler } finally { cleanup }
 *     try { expr } catch(*) { handler }
 */
data class TryExpr(
    val body: BlockExpr,
    val catches: List<CatchClause>,
    val finallyBlock: BlockExpr?,
    override val location: SourceLocation
) : Expr

/**
 * Block: `{ stmt1; stmt2; lastExpr }`
 * The value of a block is its last expression (if any).
 */
data class BlockExpr(
    val statements: List<Node>,
    override val location: SourceLocation
) : Expr

/**
 * Type check: `x is String`, `x !is Int`
 */
data class TypeCheckExpr(
    val expr: Expr,
    val type: TypeRef,
    val negated: Boolean,           // true for !is
    override val location: SourceLocation
) : Expr

/**
 * Type cast: `x as String`
 */
data class TypeCastExpr(
    val expr: Expr,
    val type: TypeRef,
    override val location: SourceLocation
) : Expr

/**
 * Containment check: `x in list`, `5 !in range`
 */
data class InCheckExpr(
    val expr: Expr,
    val container: Expr,
    val negated: Boolean,           // true for !in
    override val location: SourceLocation
) : Expr

/**
 * Pattern matching: `text matches @"regex"`
 */
data class MatchesExpr(
    val expr: Expr,
    val pattern: Expr,
    override val location: SourceLocation
) : Expr

/**
 * DSL block: `lang KD { ... }`
 *
 * The body is parsed as KD tag structure by the parser's built-in KD mode.
 * Extensible to future DSLs by language name.
 */
data class LangBlockExpr(
    val language: String,
    val body: List<KDTagNode>,
    override val location: SourceLocation
) : Expr

/**
 * Dot-Prefixed Enum Constant (DPEC): `.SUCCESS`, `.Shrub`
 *
 * The enum type is inferred from context (the when-subject's type,
 * the parameter type, or the variable's declared type).
 */
data class DPECExpr(
    val name: String,
    override val location: SourceLocation
) : Expr

/**
 * Reflection access: `x::class`
 *
 * Currently only `::class` is supported. The [member] field allows
 * future extensions (::type, ::name, etc.).
 */
data class ReflectionExpr(
    val expr: Expr,
    val member: String,
    override val location: SourceLocation
) : Expr

// ============================================================================
// 6. Supporting Types
// ============================================================================

// --- Parameters & Arguments ---

/**
 * Function or method parameter.
 *
 *     name: String                    typed
 *     level                           untyped (inferred)
 *     pretty: Bool = true             typed with default
 *     n: Int in 1..100                typed with constraint
 */
data class Parameter(
    val name: String,
    val type: TypeRef?,
    val defaultValue: Expr?,
    val constraint: Constraint?,
    val location: SourceLocation
)

/**
 * Primary constructor parameter â€” may declare a property with var/let.
 *
 *     let name: String                immutable property
 *     var age: Int = 0                mutable property with default
 *     x: Double                       constructor-only param (no binding)
 *     let category = .Shrub           inferred type with DPEC default
 */
data class ConstructorParam(
    val binding: BindingType?,      // VAR, LET, or null (plain param)
    val name: String,
    val type: TypeRef?,
    val defaultValue: Expr?,
    val constraint: Constraint?,
    val location: SourceLocation
)

enum class BindingType { VAR, LET }

/**
 * Call argument â€” positional or named.
 *
 *     42                   positional (name = null)
 *     color = "purple"     named
 *     bold = true          named
 */
data class Argument(
    val name: String?,
    val value: Expr,
    val location: SourceLocation
)

// --- Enums ---

/**
 * Enum constant declaration.
 *
 *     Apple                           simple (no value, no args)
 *     Olive = 5                       with assigned value
 *     OK(200, "OK")                   with constructor args
 */
data class EnumConstant(
    val name: String,
    val arguments: List<Argument>,  // for constructor-style
    val value: Expr?,               // for = value style
    val location: SourceLocation
)

// --- Type References ---

/**
 * Type reference with optional generics and nullability.
 *
 *     Int                  simple
 *     String?              nullable
 *     List<Int>            generic
 *     Map<String, Any?>    multi-param generic
 *     [Int]                list shorthand â†’ List<Int>
 *     [String:Int]         map shorthand  â†’ Map<String, Int>
 *     (Int, Int) -> Int    function type (future)
 */
data class TypeRef(
    val name: String,
    val typeArgs: List<TypeRef> = emptyList(),
    val nullable: Boolean = false,
    val location: SourceLocation = SourceLocation()
) {
    companion object {
        /** `[Int]` â†’ `List<Int>` */
        fun listOf(element: TypeRef, loc: SourceLocation) =
            TypeRef("List", listOf(element), false, loc)

        /** `[String:Int]` â†’ `Map<String, Int>` */
        fun mapOf(key: TypeRef, value: TypeRef, loc: SourceLocation) =
            TypeRef("Map", listOf(key, value), false, loc)
    }
}

// --- When Branches & Matchers ---

/**
 * A branch in a when expression: matchers â†’ body.
 *
 *     in 90..100 -> say "A"
 *     .SUCCESS, .WARNING -> "Good or warning"
 *     else -> "fallback"
 */
data class WhenBranch(
    val matchers: List<WhenMatcher>,    // comma-separated; empty for else
    val body: Node,
    val isElse: Boolean,
    val location: SourceLocation
)

/** Individual matcher in a when branch's comma-separated list. */
sealed interface WhenMatcher {
    val location: SourceLocation
}

/** Value match: `42`, `"hello"`, `Status.SUCCESS` */
data class ExpressionMatcher(
    val expr: Expr,
    override val location: SourceLocation
) : WhenMatcher

/** Type match: `is String`, `is Int` */
data class TypeMatcher(
    val type: TypeRef,
    val negated: Boolean,
    override val location: SourceLocation
) : WhenMatcher

/** Containment match: `in 1..10`, `in validCodes` */
data class InMatcher(
    val expr: Expr,
    val negated: Boolean,
    override val location: SourceLocation
) : WhenMatcher

/** Regex match: `matches @"pattern"` */
data class PatternMatcher(
    val pattern: Expr,
    override val location: SourceLocation
) : WhenMatcher

/** DPEC match: `.SUCCESS`, `.ERROR` */
data class DPECMatcher(
    val name: String,
    override val location: SourceLocation
) : WhenMatcher

// --- Catch Clauses ---

/**
 * Catch clause in a try expression.
 *
 *     catch(e) { ... }                 untyped
 *     catch(e: IOException) { ... }    typed
 *     catch(*) { ... }                 catch-all wildcard
 */
data class CatchClause(
    val name: String?,              // null for catch(*)
    val type: TypeRef?,             // null for untyped or catch(*)
    val isCatchAll: Boolean,        // true for catch(*)
    val body: BlockExpr,
    val location: SourceLocation
)

// --- Constraints ---

/**
 * Runtime-checked guard on a declaration or parameter.
 * Compile-time verification where statically provable.
 * Violations throw ConstraintError.
 *
 *     Int > 25              comparison
 *     Int 1..100            range
 *     Int in [1, 2, 3]      containment
 *     String matches "[A-Z]+"   regex
 */
sealed interface Constraint {
    val location: SourceLocation
}

/** `> 25`, `<= 100`, `== 0`, `!= -1` */
data class ComparisonConstraint(
    val operator: ComparisonOp,
    val value: Expr,
    override val location: SourceLocation
) : Constraint

enum class ComparisonOp { GT, LT, GTE, LTE, NEQ }

/** `1..100`, `0.0..<1.0` â€” a range expression as constraint */
data class RangeConstraint(
    val range: Expr,                // should be a RangeExpr
    override val location: SourceLocation
) : Constraint

/** `in [1, 2, 3]`, `in validSet` */
data class InConstraint(
    val collection: Expr,
    override val location: SourceLocation
) : Constraint

/** `matches "[A-Z]+"`, `matches @"^\d+$"` */
data class MatchesConstraint(
    val pattern: Expr,
    override val location: SourceLocation
) : Constraint

// ============================================================================
// 7. KD-Specific Nodes (for `lang` blocks)
// ============================================================================
//
// When the parser encounters `lang KD { ... }`, it switches to KD parsing
// mode. KD tags are parsed from the same token stream (the KS lexer already
// tokenizes KD content correctly).
//
// KD tag structure:
//   annotation(s)
//   namespace:name value(s) attribute(s) { children }

/**
 * A KD tag within a `lang KD { ... }` block.
 *
 *     book "The Hobbit" author="Tolkien" published=1937/9/21
 *     things { child1; child2 }
 *     @Personal favorite_books { ... }
 */
data class KDTagNode(
    val name: String,
    val namespace: String?,
    val values: List<Expr>,                 // positional values
    val attributes: List<KDAttribute>,      // name=value pairs
    val annotations: List<KDAnnotationNode>,
    val children: List<KDTagNode>,
    override val location: SourceLocation
) : Node

/** KD attribute: `author="Tolkien"`, `ns:key=value` */
data class KDAttribute(
    val name: String,
    val namespace: String?,
    val value: Expr,
    val location: SourceLocation
)

/** KD annotation: `@Test`, `@Test(true log="output.txt")` */
data class KDAnnotationNode(
    val name: String,
    val values: List<Expr>,
    val attributes: List<KDAttribute>,
    val location: SourceLocation
)