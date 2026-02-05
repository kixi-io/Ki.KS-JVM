package io.kixi.ks.interp

import io.kixi.ks.lexer.*
import io.kixi.ks.parser.*

import java.math.BigDecimal as Dec

/**
 * KS Interpreter - supports say, variables, numbers, and expressions.
 */
class Interpreter {

    // ANSI escape codes
    private object Ansi {
        const val RESET = "\u001B[0m"
        const val BOLD = "\u001B[1m"
        const val RED = "\u001B[31m"
        const val BRIGHT_YELLOW = "\u001B[93m" // Bright/orange-ish
    }

    // Environment for variable bindings
    private class Environment(private val parent: Environment? = null) {
        private val values = mutableMapOf<String, Any?>()
        private val mutable = mutableSetOf<String>()

        fun define(name: String, value: Any?, isMutable: Boolean) {
            values[name] = value
            if (isMutable) mutable.add(name)
        }

        fun get(name: String): Any? {
            if (name in values) return values[name]
            if (parent != null) return parent.get(name)
            throw RuntimeException("Undefined variable '$name'")
        }

        fun assign(name: String, value: Any?) {
            when {
                name in values -> {
                    if (name !in mutable) {
                        throw RuntimeException("Cannot reassign immutable variable '$name' (use 'var' instead of 'let')")
                    }
                    values[name] = value
                }
                parent != null -> parent.assign(name, value)
                else -> throw RuntimeException("Undefined variable '$name'")
            }
        }

        fun child(): Environment = Environment(this)
    }

    private var environment = Environment()

    fun execute(source: String): Any? {
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()

        var result: Any? = null
        for (node in program.body) {
            result = evaluate(node)
        }
        return result
    }

    private fun evaluate(node: Node): Any? {
        return when (node) {
            // Declarations
            is VarDecl -> evaluateVarDecl(node)

            // Statements
            is SayStmt -> evaluateSay(node)
            is ExprStmt -> evaluate(node.expression)

            // Expressions - Literals
            is LiteralExpr -> node.value

            // String templates (interpolation)
            is StringTemplateExpr -> evaluateStringTemplate(node)

            // Identifiers (variable references)
            is IdentifierExpr -> environment.get(node.name)

            // Binary expressions
            is BinaryExpr -> evaluateBinary(node)

            // Unary expressions
            is UnaryExpr -> evaluateUnary(node)

            // Assignment expressions
            is AssignExpr -> evaluateAssign(node)

            // Grouping via block
            is BlockExpr -> {
                var result: Any? = null
                for (stmt in node.statements) {
                    result = evaluate(stmt)
                }
                result
            }

            else -> throw RuntimeException("Unsupported node type: ${node::class.simpleName}")
        }
    }

    // --- Declarations ---

    private fun evaluateVarDecl(decl: VarDecl): Any? {
        val value = if (decl.initializer != null) evaluate(decl.initializer) else null
        environment.define(decl.name, value, decl.mutable)
        return null
    }

    // --- Say Statement ---

    private fun evaluateSay(stmt: SayStmt): Any? {
        val output = stmt.arguments.map { arg ->
            stringify(evaluate(arg.value))
        }.joinToString(" ")

        when (stmt.variant) {
            "error" -> println("${Ansi.RED}$output${Ansi.RESET}")
            "warn"  -> println("${Ansi.BRIGHT_YELLOW}$output${Ansi.RESET}")
            "note"  -> println("${Ansi.BOLD}$output${Ansi.RESET}")
            else    -> println(output)
        }
        return null
    }

    // --- String Template ---

    private fun evaluateStringTemplate(template: StringTemplateExpr): String {
        val sb = StringBuilder()
        for (part in template.parts) {
            when (part) {
                is LiteralPart -> sb.append(part.text)
                is ExpressionPart -> sb.append(stringify(evaluate(part.expr)))
            }
        }
        return sb.toString()
    }

    // --- Assignment ---

    private fun evaluateAssign(expr: AssignExpr): Any? {
        val value = evaluate(expr.value)

        when (val target = expr.target) {
            is IdentifierExpr -> {
                when (expr.operator) {
                    AssignOp.ASSIGN -> environment.assign(target.name, value)
                    AssignOp.PLUS_ASSIGN -> {
                        val current = environment.get(target.name)
                        environment.assign(target.name, add(current, value))
                    }
                    AssignOp.MINUS_ASSIGN -> {
                        val current = environment.get(target.name)
                        environment.assign(target.name, subtract(current, value))
                    }
                    AssignOp.STAR_ASSIGN -> {
                        val current = environment.get(target.name)
                        environment.assign(target.name, multiply(current, value))
                    }
                    AssignOp.SLASH_ASSIGN -> {
                        val current = environment.get(target.name)
                        environment.assign(target.name, divide(current, value))
                    }
                    AssignOp.MODULO_ASSIGN -> {
                        val current = environment.get(target.name)
                        environment.assign(target.name, modulo(current, value))
                    }
                    AssignOp.POWER_ASSIGN -> {
                        val current = environment.get(target.name)
                        environment.assign(target.name, power(current, value))
                    }
                }
                return environment.get(target.name)
            }
            else -> throw RuntimeException("Invalid assignment target")
        }
    }

    // --- Binary Expressions ---

    private fun evaluateBinary(expr: BinaryExpr): Any? {
        // Short-circuit for logical operators
        if (expr.operator == BinaryOp.AND) {
            val left = evaluate(expr.left)
            if (!isTruthy(left)) return false
            return isTruthy(evaluate(expr.right))
        }
        if (expr.operator == BinaryOp.OR) {
            val left = evaluate(expr.left)
            if (isTruthy(left)) return true
            return isTruthy(evaluate(expr.right))
        }

        val left = evaluate(expr.left)
        val right = evaluate(expr.right)

        return when (expr.operator) {
            // Arithmetic
            BinaryOp.ADD -> add(left, right)
            BinaryOp.SUBTRACT -> subtract(left, right)
            BinaryOp.MULTIPLY -> multiply(left, right)
            BinaryOp.DIVIDE -> divide(left, right)
            BinaryOp.MODULO -> modulo(left, right)
            BinaryOp.POWER -> power(left, right)

            // Comparison
            BinaryOp.EQUAL -> isEqual(left, right)
            BinaryOp.NOT_EQUAL -> !isEqual(left, right)
            BinaryOp.LESS -> compare(left, right) < 0
            BinaryOp.GREATER -> compare(left, right) > 0
            BinaryOp.LESS_EQUAL -> compare(left, right) <= 0
            BinaryOp.GREATER_EQUAL -> compare(left, right) >= 0

            // Logical (already handled above for short-circuit)
            BinaryOp.AND -> isTruthy(left) && isTruthy(right)
            BinaryOp.OR -> isTruthy(left) || isTruthy(right)

            // Elvis
            BinaryOp.ELVIS -> left ?: right
        }
    }

    // --- Unary Expressions ---

    private fun evaluateUnary(expr: UnaryExpr): Any? {
        val operand = evaluate(expr.operand)

        return when (expr.operator) {
            UnaryOp.NEGATE -> negate(operand)
            UnaryOp.NOT -> !isTruthy(operand)
            UnaryOp.INCREMENT -> {
                if (expr.operand is IdentifierExpr) {
                    val name = (expr.operand as IdentifierExpr).name
                    val current = environment.get(name)
                    val newVal = add(current, 1)
                    environment.assign(name, newVal)
                    if (expr.prefix) newVal else current
                } else {
                    add(operand, 1)
                }
            }
            UnaryOp.DECREMENT -> {
                if (expr.operand is IdentifierExpr) {
                    val name = (expr.operand as IdentifierExpr).name
                    val current = environment.get(name)
                    val newVal = subtract(current, 1)
                    environment.assign(name, newVal)
                    if (expr.prefix) newVal else current
                } else {
                    subtract(operand, 1)
                }
            }
            UnaryOp.NON_NULL -> operand ?: throw RuntimeException("Null assertion failed (!!) on null value")
        }
    }

    // --- Arithmetic Operations (type-preserving) ---

    private fun add(left: Any?, right: Any?): Any {
        // String concatenation
        if (left is String || right is String) {
            return stringify(left) + stringify(right)
        }
        return numericOp(left, right, "add")
    }

    private fun subtract(left: Any?, right: Any?): Any {
        return numericOp(left, right, "subtract")
    }

    private fun multiply(left: Any?, right: Any?): Any {
        return numericOp(left, right, "multiply")
    }

    private fun divide(left: Any?, right: Any?): Any {
        return numericOp(left, right, "divide")
    }

    private fun modulo(left: Any?, right: Any?): Any {
        return numericOp(left, right, "modulo")
    }

    private fun power(left: Any?, right: Any?): Any {
        val base = toDouble(left)
        val exp = toDouble(right)
        val result = Math.pow(base, exp)

        // Try to preserve integer type if possible
        if (left is Int && right is Int && result == result.toLong().toDouble() && result <= Int.MAX_VALUE) {
            return result.toInt()
        }
        if ((left is Int || left is Long) && right is Int && result == result.toLong().toDouble()) {
            return result.toLong()
        }
        return result
    }

    private fun negate(value: Any?): Any {
        return when (value) {
            is Int -> -value
            is Long -> -value
            is Float -> -value
            is Double -> -value
            is Dec -> value.negate()
            else -> -toDouble(value)
        }
    }

    /**
     * Perform a numeric operation, preserving the "wider" type.
     * Type hierarchy: Int < Long < Float < Double < BigDecimal
     */
    private fun numericOp(left: Any?, right: Any?, op: String): Any {
        // Handle BigDecimal specially
        if (left is Dec || right is Dec) {
            val l = toBigDecimal(left)
            val r = toBigDecimal(right)
            return when (op) {
                "add" -> l.add(r)
                "subtract" -> l.subtract(r)
                "multiply" -> l.multiply(r)
                "divide" -> l.divide(r, 10, Dec.ROUND_HALF_UP)
                "modulo" -> l.remainder(r)
                else -> throw RuntimeException("Unknown operation: $op")
            }
        }

        val a = toDouble(left)
        val b = toDouble(right)
        val result = when (op) {
            "add" -> a + b
            "subtract" -> a - b
            "multiply" -> a * b
            "divide" -> a / b
            "modulo" -> a % b
            else -> throw RuntimeException("Unknown operation: $op")
        }

        // Preserve type based on operands
        return when {
            left is Double || right is Double -> result
            left is Float || right is Float -> result.toFloat()
            left is Long || right is Long -> {
                if (result == result.toLong().toDouble()) result.toLong() else result
            }
            left is Int && right is Int -> {
                if (result == result.toInt().toDouble() &&
                    result >= Int.MIN_VALUE && result <= Int.MAX_VALUE) {
                    result.toInt()
                } else if (result == result.toLong().toDouble()) {
                    result.toLong()
                } else {
                    result
                }
            }
            else -> result
        }
    }

    private fun compare(left: Any?, right: Any?): Int {
        if (left is String && right is String) return left.compareTo(right)
        return toDouble(left).compareTo(toDouble(right))
    }

    // --- Type Conversions ---

    private fun stringify(value: Any?): String {
        return when (value) {
            null -> "nil"
            is Double -> {
                val text = value.toString()
                if (text.endsWith(".0")) text.substring(0, text.length - 2) else text
            }
            is Float -> {
                val text = value.toString()
                if (text.endsWith(".0")) text.substring(0, text.length - 2) else text
            }
            is Dec -> value.toPlainString()
            else -> value.toString()
        }
    }

    private fun toDouble(value: Any?): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: throw RuntimeException("Cannot convert '$value' to number")
            else -> throw RuntimeException("Expected number, got ${value?.let { it::class.simpleName } ?: "nil"}")
        }
    }

    private fun toBigDecimal(value: Any?): Dec {
        return when (value) {
            is Dec -> value
            is Number -> Dec(value.toDouble())
            is String -> Dec(value)
            else -> throw RuntimeException("Cannot convert to BigDecimal")
        }
    }

    private fun isTruthy(value: Any?): Boolean {
        return when (value) {
            null -> false
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.isNotEmpty()
            else -> true
        }
    }

    private fun isEqual(left: Any?, right: Any?): Boolean {
        if (left == null && right == null) return true
        if (left == null || right == null) return false

        // Numeric comparison - compare by value, not type
        if (left is Number && right is Number) {
            return left.toDouble() == right.toDouble()
        }

        return left == right
    }
}