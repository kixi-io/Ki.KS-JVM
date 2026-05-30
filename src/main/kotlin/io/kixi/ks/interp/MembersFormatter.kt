package io.kixi.ks.interp

import io.kixi.ks.KSFunction
import io.kixi.ks.parser.*

/**
 * Formats `.members` output for KS types (class, struct, trait, enum).
 *
 * Produces a human-readable summary of a type's API surface including
 * constructors, properties, methods (with overloads), constraints, defaults,
 * embedded enums, static members, and extension methods.
 *
 * ## Output Format
 *
 * ```
 * class Person
 *
 * Constructors:
 *   Person(name: String, age: Int = 0)
 *
 * Properties:
 *   let name: String
 *   var age: Int
 *
 * Methods:
 *   fun greet(): String
 *   fun celebrate(times: Int = 1)
 *
 * Extensions:
 *   fun toJSON(): String
 *
 * Enums:
 *   Status { Active, Inactive }
 *
 * Static:
 *   let MAX_AGE: Int
 *   fun default(): Person
 * ```
 *
 * Each constructor, method, and property occupies one line.
 * Embedded enums are listed in a natural readable format.
 */
object MembersFormatter {

    // ====================================================================
    // Public API — one method per type kind
    // ====================================================================

    /**
     * Format `.members` for a class.
     */
    fun formatClass(cls: KSClass): String {
        val sb = StringBuilder()
        sb.appendLine("class ${cls.name}")

        // Constructors
        if (cls.constructorParams.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Constructors:")
            sb.appendLine("  ${cls.name}(${formatConstructorParams(cls.constructorParams)})")
        }

        // Properties: from constructor bindings + body VarDecls
        val props = collectProperties(cls.constructorParams, cls.declaration.members)
        if (props.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Properties:")
            for (line in props) sb.appendLine("  $line")
        }

        // Methods: declared (may include overloads)
        val declaredMethods = cls.declaration.members.filterIsInstance<FunDecl>()
        if (declaredMethods.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Methods:")
            for (fn in declaredMethods) sb.appendLine("  ${formatFunSignature(fn)}")
        }

        // Extension methods
        val extensionNames = cls.extensionMethodNames()
        if (extensionNames.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Extensions:")
            for (name in extensionNames) {
                val fn = cls.getExtensionMethod(name)
                if (fn != null) {
                    sb.appendLine("  ${formatFunSignature(fn.declaration)}")
                }
            }
        }

        // Embedded enums
        val embeddedEnums = cls.declaration.members.filterIsInstance<EnumDecl>()
        if (embeddedEnums.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Enums:")
            for (enumDecl in embeddedEnums) {
                sb.appendLine("  ${formatEmbeddedEnum(enumDecl)}")
            }
        }

        // Static members
        val staticLines = collectStaticMembers(cls.declaration.members)
        if (staticLines.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Static:")
            for (line in staticLines) sb.appendLine("  $line")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Format `.members` for a struct.
     */
    fun formatStruct(struct: KSStruct): String {
        val sb = StringBuilder()
        sb.appendLine("struct ${struct.name}")

        // Constructors
        if (struct.constructorParams.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Constructors:")
            sb.appendLine("  ${struct.name}(${formatConstructorParams(struct.constructorParams)})")
        }

        // Properties
        val props = collectProperties(struct.constructorParams, struct.declaration.members)
        if (props.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Properties:")
            for (line in props) sb.appendLine("  $line")
        }

        // Methods
        val declaredMethods = struct.declaration.members.filterIsInstance<FunDecl>()
        if (declaredMethods.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Methods:")
            for (fn in declaredMethods) sb.appendLine("  ${formatFunSignature(fn)}")
        }

        // Extension methods
        val extensionNames = struct.extensionMethodNames()
        if (extensionNames.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Extensions:")
            for (name in extensionNames) {
                val fn = struct.getExtensionMethod(name)
                if (fn != null) {
                    sb.appendLine("  ${formatFunSignature(fn.declaration)}")
                }
            }
        }

        // Embedded enums
        val embeddedEnums = struct.declaration.members.filterIsInstance<EnumDecl>()
        if (embeddedEnums.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Enums:")
            for (enumDecl in embeddedEnums) {
                sb.appendLine("  ${formatEmbeddedEnum(enumDecl)}")
            }
        }

        // Static members
        val staticLines = collectStaticMembers(struct.declaration.members)
        if (staticLines.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Static:")
            for (line in staticLines) sb.appendLine("  $line")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Format `.members` for a trait.
     */
    fun formatTrait(trait: KSTrait): String {
        val sb = StringBuilder()
        sb.appendLine("trait ${trait.name}")

        // Traits have methods only (abstract + default)
        val methods = trait.declaration.members.filterIsInstance<FunDecl>()
        if (methods.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Methods:")
            for (fn in methods) sb.appendLine("  ${formatFunSignature(fn)}")
        }

        // Extension methods on trait
        val extensionNames = trait.extensionMethodNames()
        if (extensionNames.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Extensions:")
            for (name in extensionNames) {
                val fn = trait.getExtensionMethod(name)
                if (fn != null) {
                    sb.appendLine("  ${formatFunSignature(fn.declaration)}")
                }
            }
        }

        return sb.toString().trimEnd()
    }

    /**
     * Format `.members` for an enum.
     */
    fun formatEnum(enum: KSEnum): String {
        val sb = StringBuilder()
        sb.appendLine("enum ${enum.name}")

        // Constructor (if enum has constructor params)
        if (enum.declaration.constructorParams.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Constructors:")
            sb.appendLine("  ${enum.name}(${formatConstructorParams(enum.declaration.constructorParams)})")
        }

        // Constants
        if (enum.declaration.constants.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Constants:")
            sb.appendLine("  ${formatEnumConstants(enum.declaration)}")
        }

        // Properties from constructor params
        val props = collectProperties(enum.declaration.constructorParams, enum.declaration.members)
        if (props.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Properties:")
            for (line in props) sb.appendLine("  $line")
        }

        // Methods (non-static, non-var)
        val methods = enum.declaration.members.filterIsInstance<FunDecl>()
        if (methods.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Methods:")
            for (fn in methods) sb.appendLine("  ${formatFunSignature(fn)}")
        }

        // Static members
        val staticLines = collectStaticMembers(enum.declaration.members)
        if (staticLines.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Static:")
            for (line in staticLines) sb.appendLine("  $line")
        }

        return sb.toString().trimEnd()
    }

    // ====================================================================
    // Constructor Parameters
    // ====================================================================

    /**
     * Format constructor parameter list: `name: String, age: Int = 0`
     *
     * Constructor params in .members show the full constructor signature
     * WITHOUT let/var binding — those are shown in Properties instead.
     */
    private fun formatConstructorParams(params: List<ConstructorParam>): String {
        return params.joinToString(", ") { formatConstructorParam(it) }
    }

    /**
     * Format a single constructor parameter.
     *
     *     name: String
     *     age: Int = 0
     *     r: Int 0..255
     *     level: Int > 0
     */
    private fun formatConstructorParam(param: ConstructorParam): String {
        val sb = StringBuilder()
        sb.append(param.name)
        if (param.type != null) {
            sb.append(": ")
            sb.append(formatTypeRef(param.type))
        }
        if (param.constraint != null) {
            sb.append(" ")
            sb.append(formatConstraint(param.constraint))
        }
        if (param.defaultValue != null) {
            sb.append(" = ")
            sb.append(formatExpr(param.defaultValue))
        }
        return sb.toString()
    }

    // ====================================================================
    // Properties
    // ====================================================================

    /**
     * Collect property lines from constructor bindings and body VarDecls.
     *
     * Returns lines like:
     *   `let name: String`
     *   `var age: Int`
     *   `var greeting: String`
     */
    private fun collectProperties(
        constructorParams: List<ConstructorParam>,
        members: List<Node>
    ): List<String> {
        val lines = mutableListOf<String>()

        // Properties from constructor params with let/var binding
        for (param in constructorParams) {
            if (param.binding != null) {
                val binding = if (param.binding == BindingType.LET) "let" else "var"
                val sb = StringBuilder("$binding ${param.name}")
                if (param.type != null) {
                    sb.append(": ")
                    sb.append(formatTypeRef(param.type))
                }
                if (param.constraint != null) {
                    sb.append(" ")
                    sb.append(formatConstraint(param.constraint))
                }
                lines.add(sb.toString())
            }
        }

        // Instance properties from body
        for (member in members) {
            if (member is VarDecl) {
                val binding = if (member.mutable) "var" else "let"
                val sb = StringBuilder("$binding ${member.name}")
                if (member.typeAnnotation != null) {
                    sb.append(": ")
                    sb.append(formatTypeRef(member.typeAnnotation))
                }
                if (member.constraint != null) {
                    sb.append(" ")
                    sb.append(formatConstraint(member.constraint))
                }
                lines.add(sb.toString())
            }
        }

        return lines
    }

    // ====================================================================
    // Methods
    // ====================================================================

    /**
     * Format a function signature line.
     *
     *     fun greet(): String
     *     fun add(a: Int, b: Int): Int
     *     fun setLevel(n: Int > 0)
     *     fun process(data: List<String>, verbose: Bool = false): Int
     *     infix fun dot(other: Vec): Int
     */
    internal fun formatFunSignature(fn: FunDecl): String {
        val prefix = if (fn.isInfix) "infix fun" else "fun"
        val sb = StringBuilder("$prefix ${fn.name}(")
        sb.append(fn.params.joinToString(", ") { formatParameter(it) })
        sb.append(")")
        if (fn.returnType != null) {
            sb.append(": ")
            sb.append(formatTypeRef(fn.returnType))
        }
        return sb.toString()
    }

    /**
     * Format a function parameter.
     *
     *     a: Int
     *     name: String = "Anonymous"
     *     n: Int > 0
     *     level: Int 1..10
     *     items: List<String>
     */
    private fun formatParameter(param: Parameter): String {
        val sb = StringBuilder(param.name)
        if (param.type != null) {
            sb.append(": ")
            sb.append(formatTypeRef(param.type))
        }
        if (param.constraint != null) {
            sb.append(" ")
            sb.append(formatConstraint(param.constraint))
        }
        if (param.defaultValue != null) {
            sb.append(" = ")
            sb.append(formatExpr(param.defaultValue))
        }
        return sb.toString()
    }

    // ====================================================================
    // Enums (embedded and top-level constants)
    // ====================================================================

    /**
     * Format an embedded enum for display in the Enums section.
     *
     *     Status { Active, Inactive, Pending }
     *     Priority { Low=1, Medium=2, High=3 }
     */
    private fun formatEmbeddedEnum(decl: EnumDecl): String {
        val constants = formatEnumConstants(decl)
        return "${decl.name} { $constants }"
    }

    /**
     * Format enum constants for display.
     *
     * Simple:       Red, Green, Blue
     * With values:  Low=1, Medium=2, High=3
     * Constructor:  OK(200, "OK"), NotFound(404, "Not Found")
     */
    private fun formatEnumConstants(decl: EnumDecl): String {
        return decl.constants.joinToString(", ") { constant ->
            when {
                // Constructor-style: OK(200, "OK")
                constant.arguments.isNotEmpty() -> {
                    val args = constant.arguments.joinToString(", ") { arg ->
                        val prefix = if (arg.name != null) "${arg.name} = " else ""
                        "$prefix${formatExpr(arg.value)}"
                    }
                    "${constant.name}($args)"
                }
                // Value-style: Apple=5
                constant.value != null -> {
                    "${constant.name}=${formatExpr(constant.value)}"
                }
                // Simple: Red
                else -> constant.name
            }
        }
    }

    // ====================================================================
    // Static Members
    // ====================================================================

    /**
     * Collect static member lines from StaticBlock declarations.
     */
    private fun collectStaticMembers(members: List<Node>): List<String> {
        val lines = mutableListOf<String>()
        for (member in members) {
            if (member is StaticBlock) {
                for (staticMember in member.members) {
                    when (staticMember) {
                        is FunDecl -> lines.add(formatFunSignature(staticMember))
                        is VarDecl -> {
                            val binding = if (staticMember.mutable) "var" else "let"
                            val sb = StringBuilder("$binding ${staticMember.name}")
                            if (staticMember.typeAnnotation != null) {
                                sb.append(": ")
                                sb.append(formatTypeRef(staticMember.typeAnnotation))
                            }
                            if (staticMember.constraint != null) {
                                sb.append(" ")
                                sb.append(formatConstraint(staticMember.constraint))
                            }
                            lines.add(sb.toString())
                        }
                        else -> { /* ignore */ }
                    }
                }
            }
        }
        return lines
    }

    // ====================================================================
    // Type Reference Formatting
    // ====================================================================

    /**
     * Format a TypeRef to its source representation.
     *
     *     Int, String?, List<Int>, Map<String, Any?>, [Int], [String:Int]
     */
    fun formatTypeRef(ref: TypeRef): String {
        val sb = StringBuilder()
        sb.append(ref.name)
        if (ref.typeArgs.isNotEmpty()) {
            sb.append("<")
            sb.append(ref.typeArgs.joinToString(", ") { formatTypeRef(it) })
            sb.append(">")
        }
        if (ref.nullable) sb.append("?")
        return sb.toString()
    }

    // ====================================================================
    // Constraint Formatting
    // ====================================================================

    /**
     * Format a constraint to its source representation.
     *
     *     > 0, >= 1, < 100, != -1
     *     1..100, 0.0..<1.0
     *     in [1, 2, 3]
     *     matches "[A-Z]+"
     */
    private fun formatConstraint(constraint: Constraint): String {
        return when (constraint) {
            is ComparisonConstraint -> {
                val op = when (constraint.operator) {
                    ComparisonOp.GT -> ">"
                    ComparisonOp.LT -> "<"
                    ComparisonOp.GTE -> ">="
                    ComparisonOp.LTE -> "<="
                    ComparisonOp.NEQ -> "!="
                }
                "$op ${formatExpr(constraint.value)}"
            }
            is RangeConstraint -> formatExpr(constraint.range)
            is InConstraint -> "in ${formatExpr(constraint.collection)}"
            is MatchesConstraint -> "matches ${formatExpr(constraint.pattern)}"
        }
    }

    // ====================================================================
    // Expression Formatting (for defaults & constraint values)
    // ====================================================================

    /**
     * Format an expression to its approximate source representation.
     *
     * Handles common literal cases directly. Complex expressions fall back
     * to `...` since we can't perfectly reconstruct all source forms.
     */
    fun formatExpr(expr: Expr): String {
        return when (expr) {
            is LiteralExpr -> formatLiteral(expr)
            is IdentifierExpr -> expr.name
            is DPECExpr -> ".${expr.name}"
            is UnaryExpr -> {
                if (expr.prefix) {
                    val op = when (expr.operator) {
                        UnaryOp.NEGATE -> "-"
                        UnaryOp.NOT -> "!"
                        else -> return "..."
                    }
                    "$op${formatExpr(expr.operand)}"
                } else {
                    val op = when (expr.operator) {
                        UnaryOp.NON_NULL -> "!!"
                        else -> return "..."
                    }
                    "${formatExpr(expr.operand)}$op"
                }
            }
            is BinaryExpr -> {
                val op = when (expr.operator) {
                    BinaryOp.ADD -> "+"
                    BinaryOp.SUBTRACT -> "-"
                    BinaryOp.MULTIPLY -> "*"
                    BinaryOp.DIVIDE -> "/"
                    BinaryOp.MODULO -> "%"
                    BinaryOp.POWER -> "**"
                    BinaryOp.EQUAL -> "=="
                    BinaryOp.NOT_EQUAL -> "!="
                    BinaryOp.LESS -> "<"
                    BinaryOp.GREATER -> ">"
                    BinaryOp.LESS_EQUAL -> "<="
                    BinaryOp.GREATER_EQUAL -> ">="
                    BinaryOp.AND -> "&&"
                    BinaryOp.OR -> "||"
                    BinaryOp.ELVIS -> "?:"
                    BinaryOp.COMBINE -> "\u26AD"
                }
                "${formatExpr(expr.left)} $op ${formatExpr(expr.right)}"
            }
            is RangeExpr -> {
                val startStr = expr.start?.let { formatExpr(it) } ?: "_"
                val endStr = expr.end?.let { formatExpr(it) } ?: "_"
                val op = when {
                    expr.startExclusive && expr.endExclusive -> "<..<"
                    expr.startExclusive -> "<.."
                    expr.endExclusive -> "..<"
                    else -> ".."
                }
                "$startStr$op$endStr"
            }
            is ListExpr -> {
                val elements = expr.elements.joinToString(", ") { formatExpr(it) }
                "[$elements]"
            }
            is MapExpr -> {
                val entries = expr.entries.joinToString(", ") { entry ->
                    "${formatExpr(entry.key)}=${formatExpr(entry.value)}"
                }
                "[$entries]"
            }
            is CallExpr -> {
                // Format simple constructor/function calls in defaults
                val callee = formatExpr(expr.callee)
                val args = expr.arguments.joinToString(", ") { arg ->
                    val prefix = if (arg.name != null) "${arg.name} = " else ""
                    "$prefix${formatExpr(arg.value)}"
                }
                "$callee($args)"
            }
            is MemberAccessExpr -> {
                val sep = if (expr.safe) "?." else "."
                "${formatExpr(expr.obj)}$sep${expr.member}"
            }
            is StringTemplateExpr -> "..."  // interpolated strings are complex
            is ThisExpr -> "this"
            else -> "..."
        }
    }

    /**
     * Format a literal expression to its source representation.
     */
    private fun formatLiteral(expr: LiteralExpr): String {
        return when (expr.kind) {
            LiteralKind.NIL -> "nil"
            LiteralKind.BOOL -> expr.value.toString()
            LiteralKind.INT, LiteralKind.LONG -> expr.value.toString()
            LiteralKind.FLOAT -> "${expr.value}f"
            LiteralKind.DOUBLE, LiteralKind.DEC -> expr.value.toString()
            LiteralKind.STRING -> "\"${expr.value}\""
            LiteralKind.CHAR -> "'${expr.value}'"
            LiteralKind.URL -> "<${expr.value}>"
            LiteralKind.QUANTITY, LiteralKind.CURRENCY_QUANTITY -> expr.value.toString()
            LiteralKind.VERSION -> expr.value.toString()
            LiteralKind.VERBATIM_STRING -> "@\"${expr.value}\""
            LiteralKind.MULTILINE_STRING, LiteralKind.VERBATIM_MULTILINE -> "..."
            LiteralKind.BACKTICK_STRING -> "`${expr.value}`"
        }
    }
}