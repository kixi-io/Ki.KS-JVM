package io.kixi.ks.parser

import io.kixi.ks.SourceLocation
import io.kixi.ks.lexer.Lexer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.assertions.throwables.shouldThrow
import java.math.BigDecimal

/**
 * Comprehensive Kotest tests for the KS Parser.
 *
 * Tests cover all language constructs:
 *   - Literals and string interpolation
 *   - Operators (arithmetic, comparison, logical, assignment, range, null-safety)
 *   - Declarations (var, let, fun, class, trait, enum, use, extend, static)
 *   - Statements (say, for, while, return, throw, break, continue)
 *   - Expressions (if, when, try, calls, member access, index access, lists, maps)
 *   - When expression with all matcher types
 *   - KD lang blocks for DSL support
 *   - Type references and constraints
 *
 * Run with: ./gradlew test
 */
class ParserTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

    /** Parse source code and return the Program AST */
    fun parse(source: String): Program {
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        return parser.parse()
    }

    /** Parse and return the first body item */
    fun parseFirst(source: String): Node = parse(source).body.first()

    /** Parse and return the first item as an expression (unwrapping ExprStmt) */
    fun parseExpr(source: String): Expr {
        val first = parseFirst(source)
        return when (first) {
            is ExprStmt -> first.expression
            is Expr -> first
            else -> throw AssertionError("Expected expression, got ${first::class.simpleName}")
        }
    }

    // ====================================================================
    // Literal Expressions
    // ====================================================================

    context("Literal expressions") {

        context("Integer literals") {
            test("simple integer") {
                val expr = parseExpr("42") as LiteralExpr
                expr.value shouldBe 42
                expr.kind shouldBe LiteralKind.INT
            }

            test("zero") {
                val expr = parseExpr("0") as LiteralExpr
                expr.value shouldBe 0
                expr.kind shouldBe LiteralKind.INT
            }

            test("negative integer") {
                val expr = parseExpr("-42") as UnaryExpr
                expr.operator shouldBe UnaryOp.NEGATE
                val operand = expr.operand as LiteralExpr
                operand.value shouldBe 42
            }

            test("integer with underscores") {
                val expr = parseExpr("1_000_000") as LiteralExpr
                expr.value shouldBe 1000000
            }

            test("hex literal") {
                val expr = parseExpr("0xFF") as LiteralExpr
                expr.value shouldBe 255
            }

            test("binary literal") {
                val expr = parseExpr("0b1010") as LiteralExpr
                expr.value shouldBe 10
            }
        }

        context("Long literals") {
            test("explicit long") {
                val expr = parseExpr("123L") as LiteralExpr
                expr.value shouldBe 123L
                expr.kind shouldBe LiteralKind.LONG
            }
        }

        context("Float literals") {
            test("float with f suffix") {
                val expr = parseExpr("3.14f") as LiteralExpr
                expr.value shouldBe 3.14f
                expr.kind shouldBe LiteralKind.FLOAT
            }
        }

        context("Double literals") {
            test("implicit double") {
                val expr = parseExpr("3.14") as LiteralExpr
                expr.value shouldBe 3.14
                expr.kind shouldBe LiteralKind.DOUBLE
            }

            test("explicit double with d suffix") {
                val expr = parseExpr("2.718d") as LiteralExpr
                expr.value shouldBe 2.718
                expr.kind shouldBe LiteralKind.DOUBLE
            }
        }

        context("BigDecimal literals") {
            test("dec with BD suffix") {
                val expr = parseExpr("123.45BD") as LiteralExpr
                expr.value shouldBe BigDecimal("123.45")
                expr.kind shouldBe LiteralKind.DEC
            }
        }

        context("String literals") {
            test("simple string") {
                val expr = parseExpr(""""hello"""") as LiteralExpr
                expr.value shouldBe "hello"
                expr.kind shouldBe LiteralKind.STRING
            }

            test("empty string") {
                val expr = parseExpr("\"\"") as LiteralExpr
                expr.value shouldBe ""
            }

            test("string with escapes") {
                val expr = parseExpr(""""tab\there"""") as LiteralExpr
                expr.value shouldBe "tab\there"
            }
        }

        context("Verbatim strings") {
            test("verbatim string preserves backslashes") {
                val expr = parseExpr("""@"C:\path\file"""") as LiteralExpr
                expr.value shouldBe """C:\path\file"""
                expr.kind shouldBe LiteralKind.VERBATIM_STRING
            }
        }

        context("Multiline strings") {
            test("multiline string with dedenting") {
                val src = "\"\"\"\n    line1\n    line2\n    \"\"\""
                val expr = parseExpr(src) as LiteralExpr
                expr.value shouldBe "line1\nline2"
                expr.kind shouldBe LiteralKind.MULTILINE_STRING
            }
        }

        context("Backtick strings") {
            test("backtick string") {
                val expr = parseExpr("""`raw content`""") as LiteralExpr
                expr.value shouldBe "raw content"
                expr.kind shouldBe LiteralKind.BACKTICK_STRING
            }
        }

        context("Char literals") {
            test("simple char") {
                val expr = parseExpr("'A'") as LiteralExpr
                expr.value shouldBe 'A'
                expr.kind shouldBe LiteralKind.CHAR
            }

            test("escaped char") {
                val expr = parseExpr("'\\n'") as LiteralExpr
                expr.value shouldBe '\n'
            }
        }

        context("Boolean literals") {
            test("true") {
                val expr = parseExpr("true") as LiteralExpr
                expr.value shouldBe true
                expr.kind shouldBe LiteralKind.BOOL
            }

            test("false") {
                val expr = parseExpr("false") as LiteralExpr
                expr.value shouldBe false
            }
        }

        context("Nil literal") {
            test("nil") {
                val expr = parseExpr("nil") as LiteralExpr
                expr.value shouldBe null
                expr.kind shouldBe LiteralKind.NIL
            }
        }

        context("URL literals") {
            test("https URL") {
                val expr = parseExpr("<https://www.example.com>") as LiteralExpr
                expr.value shouldBe "https://www.example.com"
                expr.kind shouldBe LiteralKind.URL
            }
        }
    }

    // ====================================================================
    // String Interpolation
    // ====================================================================

    context("String interpolation") {
        test("simple variable interpolation") {
            val expr = parseExpr("\"Hello \$name\"") as StringTemplateExpr
            expr.parts shouldHaveSize 2
            (expr.parts[0] as LiteralPart).text shouldBe "Hello "
            val exprPart = expr.parts[1] as ExpressionPart
            (exprPart.expr as IdentifierExpr).name shouldBe "name"
        }

        test("expression interpolation") {
            val expr = parseExpr("\"Sum is \${a + b}\"") as StringTemplateExpr
            expr.parts shouldHaveSize 2
            val exprPart = expr.parts[1] as ExpressionPart
            exprPart.expr.shouldBeInstanceOf<BinaryExpr>()
        }

        test("multiple interpolations") {
            val expr = parseExpr("\"\$x plus \$y equals \${x + y}\"") as StringTemplateExpr
            expr.parts shouldHaveSize 5
        }

        test("no interpolation becomes plain literal") {
            val expr = parseExpr("\"no interpolation here\"") as LiteralExpr
            expr.value shouldBe "no interpolation here"
        }
    }

    // ====================================================================
    // Identifiers and This
    // ====================================================================

    context("Identifiers") {
        test("simple identifier") {
            val expr = parseExpr("myVariable") as IdentifierExpr
            expr.name shouldBe "myVariable"
        }

        test("identifier starting with underscore") {
            val expr = parseExpr("_private") as IdentifierExpr
            expr.name shouldBe "_private"
        }

        test("this expression") {
            val expr = parseExpr("this") as ThisExpr
            expr.shouldNotBeNull()
        }
    }

    // ====================================================================
    // Arithmetic Operators
    // ====================================================================

    context("Arithmetic operators") {
        test("addition") {
            val expr = parseExpr("1 + 2") as BinaryExpr
            expr.operator shouldBe BinaryOp.ADD
            (expr.left as LiteralExpr).value shouldBe 1
            (expr.right as LiteralExpr).value shouldBe 2
        }

        test("subtraction") {
            val expr = parseExpr("5 - 3") as BinaryExpr
            expr.operator shouldBe BinaryOp.SUBTRACT
        }

        test("multiplication") {
            val expr = parseExpr("4 * 5") as BinaryExpr
            expr.operator shouldBe BinaryOp.MULTIPLY
        }

        test("division") {
            val expr = parseExpr("10 / 2") as BinaryExpr
            expr.operator shouldBe BinaryOp.DIVIDE
        }

        test("modulo") {
            val expr = parseExpr("7 % 3") as BinaryExpr
            expr.operator shouldBe BinaryOp.MODULO
        }

        test("exponentiation") {
            val expr = parseExpr("2 ** 3") as BinaryExpr
            expr.operator shouldBe BinaryOp.POWER
        }

        test("exponentiation is right-associative") {
            // 2**3**2 should be 2**(3**2) = 2**9 = 512
            val expr = parseExpr("2 ** 3 ** 2") as BinaryExpr
            expr.operator shouldBe BinaryOp.POWER
            (expr.left as LiteralExpr).value shouldBe 2
            val right = expr.right as BinaryExpr
            right.operator shouldBe BinaryOp.POWER
        }

        test("operator precedence: multiplication before addition") {
            val expr = parseExpr("1 + 2 * 3") as BinaryExpr
            expr.operator shouldBe BinaryOp.ADD
            (expr.left as LiteralExpr).value shouldBe 1
            val right = expr.right as BinaryExpr
            right.operator shouldBe BinaryOp.MULTIPLY
        }

        test("parentheses override precedence") {
            val expr = parseExpr("(1 + 2) * 3") as BinaryExpr
            expr.operator shouldBe BinaryOp.MULTIPLY
            val left = expr.left as BinaryExpr
            left.operator shouldBe BinaryOp.ADD
        }
    }

    // ====================================================================
    // Comparison Operators
    // ====================================================================

    context("Comparison operators") {
        test("equal") {
            val expr = parseExpr("a == b") as BinaryExpr
            expr.operator shouldBe BinaryOp.EQUAL
        }

        test("not equal") {
            val expr = parseExpr("a != b") as BinaryExpr
            expr.operator shouldBe BinaryOp.NOT_EQUAL
        }

        test("less than") {
            val expr = parseExpr("a < b") as BinaryExpr
            expr.operator shouldBe BinaryOp.LESS
        }

        test("greater than") {
            val expr = parseExpr("a > b") as BinaryExpr
            expr.operator shouldBe BinaryOp.GREATER
        }

        test("less or equal") {
            val expr = parseExpr("a <= b") as BinaryExpr
            expr.operator shouldBe BinaryOp.LESS_EQUAL
        }

        test("greater or equal") {
            val expr = parseExpr("a >= b") as BinaryExpr
            expr.operator shouldBe BinaryOp.GREATER_EQUAL
        }
    }

    // ====================================================================
    // Logical Operators
    // ====================================================================

    context("Logical operators") {
        test("and") {
            val expr = parseExpr("a && b") as BinaryExpr
            expr.operator shouldBe BinaryOp.AND
        }

        test("or") {
            val expr = parseExpr("a || b") as BinaryExpr
            expr.operator shouldBe BinaryOp.OR
        }

        test("not") {
            val expr = parseExpr("!flag") as UnaryExpr
            expr.operator shouldBe UnaryOp.NOT
            expr.prefix shouldBe true
        }

        test("logical precedence: and before or") {
            val expr = parseExpr("a || b && c") as BinaryExpr
            expr.operator shouldBe BinaryOp.OR
            val right = expr.right as BinaryExpr
            right.operator shouldBe BinaryOp.AND
        }
    }

    // ====================================================================
    // Assignment Operators
    // ====================================================================

    context("Assignment operators") {
        test("simple assignment") {
            val expr = parseExpr("x = 5") as AssignExpr
            expr.operator shouldBe AssignOp.ASSIGN
            (expr.target as IdentifierExpr).name shouldBe "x"
            (expr.value as LiteralExpr).value shouldBe 5
        }

        test("plus-assign") {
            val expr = parseExpr("x += 1") as AssignExpr
            expr.operator shouldBe AssignOp.PLUS_ASSIGN
        }

        test("minus-assign") {
            val expr = parseExpr("x -= 1") as AssignExpr
            expr.operator shouldBe AssignOp.MINUS_ASSIGN
        }

        test("star-assign") {
            val expr = parseExpr("x *= 2") as AssignExpr
            expr.operator shouldBe AssignOp.STAR_ASSIGN
        }

        test("slash-assign") {
            val expr = parseExpr("x /= 2") as AssignExpr
            expr.operator shouldBe AssignOp.SLASH_ASSIGN
        }

        test("modulo-assign") {
            val expr = parseExpr("x %= 3") as AssignExpr
            expr.operator shouldBe AssignOp.MODULO_ASSIGN
        }

        test("power-assign") {
            val expr = parseExpr("x **= 2") as AssignExpr
            expr.operator shouldBe AssignOp.POWER_ASSIGN
        }

        test("assignment to member access") {
            val expr = parseExpr("obj.field = 10") as AssignExpr
            expr.target.shouldBeInstanceOf<MemberAccessExpr>()
        }

        test("assignment to index") {
            val expr = parseExpr("arr[0] = 10") as AssignExpr
            expr.target.shouldBeInstanceOf<IndexExpr>()
        }
    }

    // ====================================================================
    // Increment / Decrement Operators
    // ====================================================================

    context("Increment and decrement") {
        test("prefix increment") {
            val expr = parseExpr("++x") as UnaryExpr
            expr.operator shouldBe UnaryOp.INCREMENT
            expr.prefix shouldBe true
        }

        test("postfix increment") {
            val expr = parseExpr("x++") as UnaryExpr
            expr.operator shouldBe UnaryOp.INCREMENT
            expr.prefix shouldBe false
        }

        test("prefix decrement") {
            val expr = parseExpr("--x") as UnaryExpr
            expr.operator shouldBe UnaryOp.DECREMENT
            expr.prefix shouldBe true
        }

        test("postfix decrement") {
            val expr = parseExpr("x--") as UnaryExpr
            expr.operator shouldBe UnaryOp.DECREMENT
            expr.prefix shouldBe false
        }
    }

    // ====================================================================
    // Range Operators
    // ====================================================================

    context("Range operators") {
        test("inclusive range") {
            val expr = parseExpr("1..10") as RangeExpr
            (expr.start as LiteralExpr).value shouldBe 1
            (expr.end as LiteralExpr).value shouldBe 10
            expr.startExclusive shouldBe false
            expr.endExclusive shouldBe false
        }

        test("exclusive end (..<)") {
            val expr = parseExpr("0..<5") as RangeExpr
            expr.startExclusive shouldBe false
            expr.endExclusive shouldBe true
        }

        test("exclusive start (<..)") {
            val expr = parseExpr("0<..5") as RangeExpr
            expr.startExclusive shouldBe true
            expr.endExclusive shouldBe false
        }

        test("exclusive both (<..<)") {
            val expr = parseExpr("0<..<5") as RangeExpr
            expr.startExclusive shouldBe true
            expr.endExclusive shouldBe true
        }

        test("open left range (_..x)") {
            val expr = parseExpr("_..100") as RangeExpr
            expr.start.shouldBeNull()
            (expr.end as LiteralExpr).value shouldBe 100
        }

        test("open right range (x.._)") {
            val expr = parseExpr("0.._") as RangeExpr
            (expr.start as LiteralExpr).value shouldBe 0
            expr.end.shouldBeNull()
        }
    }

    // ====================================================================
    // Null Safety Operators
    // ====================================================================

    context("Null safety operators") {
        test("safe call (?.)") {
            val expr = parseExpr("obj?.field") as MemberAccessExpr
            expr.safe shouldBe true
            expr.member shouldBe "field"
        }

        test("safe call chain") {
            val expr = parseExpr("a?.b?.c") as MemberAccessExpr
            expr.safe shouldBe true
            expr.member shouldBe "c"
            val inner = expr.obj as MemberAccessExpr
            inner.safe shouldBe true
        }

        test("elvis operator") {
            val expr = parseExpr("name ?: \"default\"") as BinaryExpr
            expr.operator shouldBe BinaryOp.ELVIS
        }

        test("non-null assertion") {
            val expr = parseExpr("value!!") as UnaryExpr
            expr.operator shouldBe UnaryOp.NON_NULL
            expr.prefix shouldBe false
        }
    }

    // ====================================================================
    // Ternary Operator
    // ====================================================================

    context("Ternary operator") {
        test("simple ternary") {
            val expr = parseExpr("x > 0 ? 1 : -1") as TernaryExpr
            val cond = expr.condition as BinaryExpr
            cond.operator shouldBe BinaryOp.GREATER
            (expr.thenBranch as LiteralExpr).value shouldBe 1
        }

        test("nested ternary") {
            val expr = parseExpr("a ? b ? 1 : 2 : 3") as TernaryExpr
            expr.thenBranch.shouldBeInstanceOf<TernaryExpr>()
        }
    }

    // ====================================================================
    // Type Check and Cast
    // ====================================================================

    context("Type checks and casts") {
        test("is check") {
            val expr = parseExpr("x is String") as TypeCheckExpr
            (expr.expr as IdentifierExpr).name shouldBe "x"
            expr.type.name shouldBe "String"
            expr.negated shouldBe false
        }

        test("!is check") {
            val expr = parseExpr("x !is Int") as TypeCheckExpr
            expr.negated shouldBe true
        }

        test("as cast") {
            val expr = parseExpr("x as String") as TypeCastExpr
            expr.type.name shouldBe "String"
        }
    }

    // ====================================================================
    // In Check
    // ====================================================================

    context("In check") {
        test("in check") {
            val expr = parseExpr("x in list") as InCheckExpr
            expr.negated shouldBe false
        }

        test("!in check") {
            val expr = parseExpr("x !in range") as InCheckExpr
            expr.negated shouldBe true
        }
    }

    // ====================================================================
    // Matches Expression
    // ====================================================================

    context("Matches expression") {
        test("matches with regex") {
            val expr = parseExpr("""text matches @"[A-Z]+"""") as MatchesExpr
            (expr.expr as IdentifierExpr).name shouldBe "text"
            expr.pattern.shouldBeInstanceOf<LiteralExpr>()
        }
    }

    // ====================================================================
    // Call Expressions
    // ====================================================================

    context("Call expressions") {
        test("simple function call") {
            val expr = parseExpr("foo()") as CallExpr
            (expr.callee as IdentifierExpr).name shouldBe "foo"
            expr.arguments shouldHaveSize 0
        }

        test("function call with positional arguments") {
            val expr = parseExpr("add(1, 2)") as CallExpr
            expr.arguments shouldHaveSize 2
            expr.arguments[0].name.shouldBeNull()
            expr.arguments[1].name.shouldBeNull()
        }

        test("function call with named arguments") {
            val expr = parseExpr("create(name = \"Ada\", age = 42)") as CallExpr
            expr.arguments shouldHaveSize 2
            expr.arguments[0].name shouldBe "name"
            expr.arguments[1].name shouldBe "age"
        }

        test("function call with mixed arguments") {
            val expr = parseExpr("build(1, color = \"red\")") as CallExpr
            expr.arguments shouldHaveSize 2
            expr.arguments[0].name.shouldBeNull()
            expr.arguments[1].name shouldBe "color"
        }

        test("method call") {
            val expr = parseExpr("obj.method()") as CallExpr
            val callee = expr.callee as MemberAccessExpr
            callee.member shouldBe "method"
        }

        test("chained method calls") {
            val expr = parseExpr("obj.first().second()") as CallExpr
            val callee = expr.callee as MemberAccessExpr
            callee.member shouldBe "second"
        }
    }

    // ====================================================================
    // Member Access
    // ====================================================================

    context("Member access") {
        test("property access") {
            val expr = parseExpr("obj.property") as MemberAccessExpr
            (expr.obj as IdentifierExpr).name shouldBe "obj"
            expr.member shouldBe "property"
            expr.safe shouldBe false
        }

        test("chained property access") {
            val expr = parseExpr("a.b.c") as MemberAccessExpr
            expr.member shouldBe "c"
            val ab = expr.obj as MemberAccessExpr
            ab.member shouldBe "b"
        }
    }

    // ====================================================================
    // Index Access
    // ====================================================================

    context("Index access") {
        test("list index") {
            val expr = parseExpr("list[0]") as IndexExpr
            (expr.obj as IdentifierExpr).name shouldBe "list"
            (expr.index as LiteralExpr).value shouldBe 0
        }

        test("map index with string key") {
            val expr = parseExpr("map[\"key\"]") as IndexExpr
            (expr.index as LiteralExpr).value shouldBe "key"
        }

        test("chained index access") {
            val expr = parseExpr("matrix[0][1]") as IndexExpr
            expr.obj.shouldBeInstanceOf<IndexExpr>()
        }
    }

    // ====================================================================
    // List Literals
    // ====================================================================

    context("List literals") {
        test("empty list") {
            val expr = parseExpr("[]") as ListExpr
            expr.elements shouldHaveSize 0
        }

        test("list with commas") {
            val expr = parseExpr("[1, 2, 3]") as ListExpr
            expr.elements shouldHaveSize 3
        }

        test("list without commas (KD style)") {
            val expr = parseExpr("[1 2 3]") as ListExpr
            expr.elements shouldHaveSize 3
        }

        test("nested list") {
            val expr = parseExpr("[[1, 2], [3, 4]]") as ListExpr
            expr.elements shouldHaveSize 2
            expr.elements[0].shouldBeInstanceOf<ListExpr>()
        }
    }

    // ====================================================================
    // Map Literals
    // ====================================================================

    context("Map literals") {
        test("empty map") {
            val expr = parseExpr("[=]") as MapExpr
            expr.entries shouldHaveSize 0
        }

        test("map with entries") {
            val expr = parseExpr("[name = \"Ada\", age = 42]") as MapExpr
            expr.entries shouldHaveSize 2
            val first = expr.entries[0]
            (first.key as IdentifierExpr).name shouldBe "name"
            (first.value as LiteralExpr).value shouldBe "Ada"
        }

        test("map without commas (KD style)") {
            val expr = parseExpr("[a = 1 b = 2]") as MapExpr
            expr.entries shouldHaveSize 2
        }
    }

    // ====================================================================
    // DPEC (Dot-Prefixed Enum Constant)
    // ====================================================================

    context("DPEC expressions") {
        test("simple DPEC") {
            val expr = parseExpr(".SUCCESS") as DPECExpr
            expr.name shouldBe "SUCCESS"
        }
    }

    // ====================================================================
    // Reflection
    // ====================================================================

    context("Reflection expressions") {
        test("::class") {
            val expr = parseExpr("x::class") as ReflectionExpr
            (expr.expr as IdentifierExpr).name shouldBe "x"
            expr.member shouldBe "class"
        }
    }

    // ====================================================================
    // If Expression
    // ====================================================================

    context("If expressions") {
        test("simple if") {
            val expr = parseExpr("if x > 0 { 1 }") as IfExpr
            expr.condition.shouldBeInstanceOf<BinaryExpr>()
            expr.thenBranch.shouldBeInstanceOf<BlockExpr>()
            expr.elseBranch.shouldBeNull()
        }

        test("if-else") {
            val expr = parseExpr("if x > 0 { 1 } else { -1 }") as IfExpr
            expr.elseBranch.shouldNotBeNull()
        }

        test("else-if chain") {
            val expr = parseExpr("if x > 0 { 1 } else if x < 0 { -1 } else { 0 }") as IfExpr
            expr.elseBranch.shouldBeInstanceOf<IfExpr>()
        }

        test("if with single statement body") {
            val expr = parseExpr("if x > 0 return 1") as IfExpr
            expr.thenBranch.shouldBeInstanceOf<ReturnStmt>()
        }
    }

    // ====================================================================
    // When Expression (Complex Cases)
    // ====================================================================

    context("When expressions") {

        context("Basic when syntax") {
            test("when with subject") {
                val expr = parseExpr("""
                    when x {
                        1 -> "one"
                        2 -> "two"
                        else -> "other"
                    }
                """.trimIndent()) as WhenExpr
                expr.subject.shouldNotBeNull()
                expr.branches shouldHaveSize 3
                expr.branches.last().isElse shouldBe true
            }

            test("when without subject (condition style)") {
                val expr = parseExpr("""
                    when {
                        x > 0 -> "positive"
                        x < 0 -> "negative"
                        else -> "zero"
                    }
                """.trimIndent()) as WhenExpr
                expr.subject.shouldBeNull()
            }
        }

        context("When matchers") {
            test("expression matcher") {
                val expr = parseExpr("""
                    when x {
                        42 -> "answer"
                        else -> "unknown"
                    }
                """.trimIndent()) as WhenExpr
                val matcher = expr.branches[0].matchers[0]
                matcher.shouldBeInstanceOf<ExpressionMatcher>()
            }

            test("DPEC matcher") {
                val expr = parseExpr("""
                    when status {
                        .SUCCESS -> "ok"
                        .ERROR -> "fail"
                        else -> "unknown"
                    }
                """.trimIndent()) as WhenExpr
                val matcher = expr.branches[0].matchers[0]
                matcher.shouldBeInstanceOf<DPECMatcher>()
                (matcher as DPECMatcher).name shouldBe "SUCCESS"
            }

            test("type matcher (is)") {
                val expr = parseExpr("""
                    when obj {
                        is String -> "string"
                        is Int -> "int"
                        else -> "other"
                    }
                """.trimIndent()) as WhenExpr
                val matcher = expr.branches[0].matchers[0]
                matcher.shouldBeInstanceOf<TypeMatcher>()
                (matcher as TypeMatcher).negated shouldBe false
            }

            test("negated type matcher (!is)") {
                val expr = parseExpr("""
                    when obj {
                        !is String -> "not string"
                        else -> "string"
                    }
                """.trimIndent()) as WhenExpr
                val matcher = expr.branches[0].matchers[0] as TypeMatcher
                matcher.negated shouldBe true
            }

            test("in matcher") {
                val expr = parseExpr("""
                    when score {
                        in 90..100 -> "A"
                        in 80..<90 -> "B"
                        else -> "F"
                    }
                """.trimIndent()) as WhenExpr
                val matcher = expr.branches[0].matchers[0]
                matcher.shouldBeInstanceOf<InMatcher>()
            }

            test("negated in matcher (!in)") {
                val expr = parseExpr("""
                    when x {
                        !in badList -> "good"
                        else -> "bad"
                    }
                """.trimIndent()) as WhenExpr
                val matcher = expr.branches[0].matchers[0] as InMatcher
                matcher.negated shouldBe true
            }

            test("pattern matcher (matches)") {
                val expr = parseExpr("""
                    when text {
                        matches @"[A-Z]+" -> "uppercase"
                        else -> "other"
                    }
                """.trimIndent()) as WhenExpr
                val matcher = expr.branches[0].matchers[0]
                matcher.shouldBeInstanceOf<PatternMatcher>()
            }

            test("multiple matchers per branch (comma-separated)") {
                val expr = parseExpr("""
                    when x {
                        1, 2, 3 -> "small"
                        else -> "large"
                    }
                """.trimIndent()) as WhenExpr
                expr.branches[0].matchers shouldHaveSize 3
            }

            test("mixed matcher types in branch") {
                val expr = parseExpr("""
                    when obj {
                        .SUCCESS, .WARNING, is String -> "ok-ish"
                        else -> "error"
                    }
                """.trimIndent()) as WhenExpr
                val matchers = expr.branches[0].matchers
                matchers shouldHaveSize 3
                matchers[0].shouldBeInstanceOf<DPECMatcher>()
                matchers[1].shouldBeInstanceOf<DPECMatcher>()
                matchers[2].shouldBeInstanceOf<TypeMatcher>()
            }
        }

        context("When branch bodies") {
            test("block body") {
                val expr = parseExpr("""
                    when x {
                        1 -> {
                            let y = 2
                            y
                        }
                        else -> 0
                    }
                """.trimIndent()) as WhenExpr
                expr.branches[0].body.shouldBeInstanceOf<BlockExpr>()
            }

            test("expression body") {
                val expr = parseExpr("""
                    when x {
                        1 -> "one"
                        else -> "other"
                    }
                """.trimIndent()) as WhenExpr
                // The body should be a simple expression (wrapped by parser)
            }
        }
    }

    // ====================================================================
    // Try Expression
    // ====================================================================

    context("Try expressions") {
        test("try-catch") {
            val expr = parseExpr("""
                try {
                    risky()
                } catch(e: Exception) {
                    handleError()
                }
            """.trimIndent()) as TryExpr
            expr.catches shouldHaveSize 1
            expr.catches[0].name shouldBe "e"
            expr.catches[0].type?.name shouldBe "Exception"
            expr.finallyBlock.shouldBeNull()
        }

        test("try-catch with untyped catch") {
            val expr = parseExpr("""
                try { foo() } catch(e) { bar() }
            """.trimIndent()) as TryExpr
            expr.catches[0].name shouldBe "e"
            expr.catches[0].type.shouldBeNull()
        }

        test("try-catch-all (wildcard)") {
            val expr = parseExpr("""
                try { foo() } catch(*) { fallback() }
            """.trimIndent()) as TryExpr
            expr.catches[0].isCatchAll shouldBe true
            expr.catches[0].name.shouldBeNull()
        }

        test("try-catch-finally") {
            val expr = parseExpr("""
                try { foo() } catch(e) { bar() } finally { cleanup() }
            """.trimIndent()) as TryExpr
            expr.finallyBlock.shouldNotBeNull()
        }

        test("multiple catch clauses") {
            val expr = parseExpr("""
                try {
                    risky()
                } catch(e: IOException) {
                    handleIO()
                } catch(e: Exception) {
                    handleGeneral()
                }
            """.trimIndent()) as TryExpr
            expr.catches shouldHaveSize 2
        }
    }

    // ====================================================================
    // Block Expression
    // ====================================================================

    context("Block expressions") {
        test("block with multiple statements") {
            val expr = parseExpr("""
                {
                    let x = 1
                    let y = 2
                    x + y
                }
            """.trimIndent()) as BlockExpr
            expr.statements shouldHaveSize 3
        }
    }

    // ====================================================================
    // Variable Declarations
    // ====================================================================

    context("Variable declarations") {
        test("var with inferred type") {
            val decl = parseFirst("var name = \"Akiko\"") as VarDecl
            decl.name shouldBe "name"
            decl.mutable shouldBe true
            decl.typeAnnotation.shouldBeNull()
            (decl.initializer as LiteralExpr).value shouldBe "Akiko"
        }

        test("let (immutable)") {
            val decl = parseFirst("let age = 42") as VarDecl
            decl.mutable shouldBe false
        }

        test("var with explicit type") {
            val decl = parseFirst("var height: Double = 68.0") as VarDecl
            decl.typeAnnotation?.name shouldBe "Double"
        }

        test("var with nullable type") {
            val decl = parseFirst("var item: String?") as VarDecl
            decl.typeAnnotation?.nullable shouldBe true
            decl.initializer.shouldBeNull()
        }

        test("var with comparison constraint") {
            val decl = parseFirst("let score: Int > 0 = 100") as VarDecl
            decl.constraint.shouldBeInstanceOf<ComparisonConstraint>()
            (decl.constraint as ComparisonConstraint).operator shouldBe ComparisonOp.GT
        }

        test("var with range constraint") {
            val decl = parseFirst("let percent: Int 0..100 = 50") as VarDecl
            decl.constraint.shouldBeInstanceOf<RangeConstraint>()
        }

        test("var with in constraint") {
            val decl = parseFirst("let color: String in [\"red\", \"green\", \"blue\"] = \"red\"") as VarDecl
            decl.constraint.shouldBeInstanceOf<InConstraint>()
        }

        test("var with matches constraint") {
            val decl = parseFirst("""let code: String matches @"[A-Z]{3}" = "ABC"""") as VarDecl
            decl.constraint.shouldBeInstanceOf<MatchesConstraint>()
        }
    }

    // ====================================================================
    // Function Declarations
    // ====================================================================

    context("Function declarations") {
        test("function with block body") {
            val decl = parseFirst("""
                fun add(a: Int, b: Int): Int {
                    return a + b
                }
            """.trimIndent()) as FunDecl
            decl.name shouldBe "add"
            decl.params shouldHaveSize 2
            decl.returnType?.name shouldBe "Int"
            decl.isSingleExpr shouldBe false
            decl.body.shouldBeInstanceOf<BlockExpr>()
        }

        test("function with single-expression body") {
            val decl = parseFirst("fun greet(name: String) = \"Hello\"") as FunDecl
            decl.isSingleExpr shouldBe true
            decl.body.shouldBeInstanceOf<LiteralExpr>()
        }

        test("function with untyped parameter") {
            val decl = parseFirst("fun dangerous(level) { }") as FunDecl
            decl.params[0].type.shouldBeNull()
        }

        test("abstract function (no body)") {
            val decl = parseFirst("fun abstractMethod(): String") as FunDecl
            decl.body.shouldBeNull()
        }

        test("function with default parameter value") {
            val decl = parseFirst("fun greet(name: String = \"World\") { }") as FunDecl
            decl.params[0].defaultValue.shouldNotBeNull()
        }

        test("function with constrained parameter") {
            val decl = parseFirst("fun setVolume(level: Int in 0..100) { }") as FunDecl
            decl.params[0].constraint.shouldNotBeNull()
        }
    }

    // ====================================================================
    // Class Declarations
    // ====================================================================

    context("Class declarations") {
        test("simple class") {
            val decl = parseFirst("class Dog { }") as ClassDecl
            decl.name shouldBe "Dog"
            decl.constructorParams shouldHaveSize 0
            decl.superTypes shouldHaveSize 0
        }

        test("class with super type") {
            val decl = parseFirst("class Dog: Animal { }") as ClassDecl
            decl.superTypes shouldHaveSize 1
            decl.superTypes[0].name shouldBe "Animal"
        }

        test("class with primary constructor") {
            val decl = parseFirst("class Person(let name: String, var age: Int) { }") as ClassDecl
            decl.constructorParams shouldHaveSize 2
            decl.constructorParams[0].binding shouldBe BindingType.LET
            decl.constructorParams[0].name shouldBe "name"
            decl.constructorParams[1].binding shouldBe BindingType.VAR
        }

        test("class with constructor parameter without binding") {
            val decl = parseFirst("class Point(x: Double, y: Double) { }") as ClassDecl
            decl.constructorParams[0].binding.shouldBeNull()
        }

        test("class with constructor parameter default value") {
            val decl = parseFirst("class Widget(var size: Int = 10) { }") as ClassDecl
            decl.constructorParams[0].defaultValue.shouldNotBeNull()
        }

        test("class with multiple super types") {
            val decl = parseFirst("class Widget: Drawable, Clickable { }") as ClassDecl
            decl.superTypes shouldHaveSize 2
        }

        test("class with members") {
            val decl = parseFirst("""
                class Counter {
                    var count = 0
                    fun increment() { count++ }
                }
            """.trimIndent()) as ClassDecl
            decl.members shouldHaveSize 2
        }
    }

    // ====================================================================
    // Trait Declarations
    // ====================================================================

    context("Trait declarations") {
        test("simple trait") {
            val decl = parseFirst("trait Drawable { }") as TraitDecl
            decl.name shouldBe "Drawable"
        }

        test("trait with abstract method") {
            val decl = parseFirst("""
                trait Shape {
                    fun area(): Double
                }
            """.trimIndent()) as TraitDecl
            val method = decl.members[0] as FunDecl
            method.body.shouldBeNull()
        }

        test("trait with default method") {
            val decl = parseFirst("""
                trait Greeter {
                    fun greet() = say "Hello"
                }
            """.trimIndent()) as TraitDecl
            val method = decl.members[0] as FunDecl
            method.body.shouldNotBeNull()
        }

        test("trait with super trait") {
            val decl = parseFirst("trait Square: Rectangle { }") as TraitDecl
            decl.superTraits shouldHaveSize 1
        }
    }

    // ====================================================================
    // Enum Declarations
    // ====================================================================

    context("Enum declarations") {
        test("simple enum") {
            val decl = parseFirst("enum Color { RED GREEN BLUE }") as EnumDecl
            decl.name shouldBe "Color"
            decl.constants shouldHaveSize 3
            decl.constants[0].name shouldBe "RED"
        }

        test("enum with values") {
            val decl = parseFirst("enum Veggie { Olive=5 Broccoli=10 }") as EnumDecl
            (decl.constants[0].value as LiteralExpr).value shouldBe 5
        }

        test("enum with value type") {
            val decl = parseFirst("enum Fruit: Int { Apple=1 Orange=2 }") as EnumDecl
            decl.valueType?.name shouldBe "Int"
        }

        test("enum with constructor") {
            val decl = parseFirst("""
                enum HttpStatus(code: Int, msg: String) {
                    OK(200, "OK")
                    NOT_FOUND(404, "Not Found")
                }
            """.trimIndent()) as EnumDecl
            decl.constructorParams shouldHaveSize 2
            decl.constants[0].arguments shouldHaveSize 2
        }

        test("enum with methods") {
            val decl = parseFirst("""
                enum Color {
                    RED GREEN BLUE
                    fun toRgb(): Int = 0
                }
            """.trimIndent()) as EnumDecl
            decl.members shouldHaveSize 1
        }
    }

    // ====================================================================
    // Use (Import) Declarations
    // ====================================================================

    context("Use declarations") {
        test("simple use") {
            val decl = parseFirst("use io.kixi.kd.Tag") as UseDecl
            decl.path shouldBe listOf("io", "kixi", "kd", "Tag")
            decl.wildcard shouldBe false
            decl.alias.shouldBeNull()
        }

        test("wildcard use") {
            val decl = parseFirst("use io.kixi.kd.*") as UseDecl
            decl.wildcard shouldBe true
        }

        test("use with alias") {
            val decl = parseFirst("use collections.OrderedMap as OMap") as UseDecl
            decl.alias shouldBe "OMap"
        }
    }

    // ====================================================================
    // Extend Declarations
    // ====================================================================

    context("Extend declarations") {
        test("extend type") {
            val decl = parseFirst("extend String { fun isPalindrome(): Bool = true }") as ExtendDecl
            decl.target.name shouldBe "String"
            decl.isTraitExtension shouldBe false
        }

        test("extend trait") {
            val decl = parseFirst("extend trait Comparable") as ExtendDecl
            decl.isTraitExtension shouldBe true
        }
    }

    // ====================================================================
    // Static Block
    // ====================================================================

    context("Static blocks") {
        test("static block in class") {
            val decl = parseFirst("""
                class Counter {
                    static {
                        let DEFAULT = 0
                    }
                }
            """.trimIndent()) as ClassDecl
            decl.members.any { it is StaticBlock } shouldBe true
        }
    }

    // ====================================================================
    // Say Statement
    // ====================================================================

    context("Say statements") {
        test("simple say") {
            val stmt = parseFirst("say \"hello\"") as SayStmt
            stmt.variant.shouldBeNull()
            stmt.arguments shouldHaveSize 1
        }

        test("say without parentheses") {
            val stmt = parseFirst("say \"hello world\"") as SayStmt
            val value = stmt.arguments[0].value as LiteralExpr
            value.value shouldBe "hello world"
        }

        test("say with parentheses") {
            val stmt = parseFirst("say(\"hello\")") as SayStmt
            stmt.arguments shouldHaveSize 1
        }

        test("say.error variant") {
            val stmt = parseFirst("say.error \"oops\"") as SayStmt
            stmt.variant shouldBe "error"
        }

        test("say.warn variant") {
            val stmt = parseFirst("say.warn \"caution\"") as SayStmt
            stmt.variant shouldBe "warn"
        }

        test("say.note variant") {
            val stmt = parseFirst("say.note \"note\"") as SayStmt
            stmt.variant shouldBe "note"
        }

        test("say.info with parentheses and named args") {
            val stmt = parseFirst("say.info(\"note\", bold = true)") as SayStmt
            stmt.variant shouldBe "note"
            stmt.arguments shouldHaveSize 2
            stmt.arguments[1].name shouldBe "bold"
        }

        test("say with expression") {
            val stmt = parseFirst("say a + b") as SayStmt
            stmt.arguments[0].value.shouldBeInstanceOf<BinaryExpr>()
        }

        test("say with no arguments") {
            val stmt = parseFirst("say") as SayStmt
            stmt.arguments shouldHaveSize 0
        }
    }

    // ====================================================================
    // For Statement
    // ====================================================================

    context("For statements") {
        test("traditional for with variable") {
            val stmt = parseFirst("for i in list { say i }") as ForStmt
            stmt.variable shouldBe "i"
            (stmt.iterable as IdentifierExpr).name shouldBe "list"
        }

        test("simplified for (implicit it)") {
            val stmt = parseFirst("for list { say it }") as ForStmt
            stmt.variable.shouldBeNull()
        }

        test("for with single statement body") {
            val stmt = parseFirst("for i in list say i") as ForStmt
            stmt.body.shouldBeInstanceOf<SayStmt>()
        }

        test("for over range") {
            val stmt = parseFirst("for i in 1..10 { say i }") as ForStmt
            stmt.iterable.shouldBeInstanceOf<RangeExpr>()
        }

        test("for over enum (simplified)") {
            val stmt = parseFirst("for Color { say it }") as ForStmt
            stmt.variable.shouldBeNull()
            (stmt.iterable as IdentifierExpr).name shouldBe "Color"
        }
    }

    // ====================================================================
    // While Statement
    // ====================================================================

    context("While statements") {
        test("while with block body") {
            val stmt = parseFirst("while x > 0 { x-- }") as WhileStmt
            stmt.condition.shouldBeInstanceOf<BinaryExpr>()
            stmt.body.shouldBeInstanceOf<BlockExpr>()
        }

        test("while with single statement body") {
            val stmt = parseFirst("while running doWork()") as WhileStmt
            stmt.body.shouldBeInstanceOf<ExprStmt>()
        }
    }

    // ====================================================================
    // Return Statement
    // ====================================================================

    context("Return statements") {
        test("return with value") {
            val stmt = parseFirst("return 42") as ReturnStmt
            (stmt.value as LiteralExpr).value shouldBe 42
        }

        test("bare return") {
            val stmt = parseFirst("return") as ReturnStmt
            stmt.value.shouldBeNull()
        }
    }

    // ====================================================================
    // Throw Statement
    // ====================================================================

    context("Throw statements") {
        test("throw with call") {
            val stmt = parseFirst("throw Exception(\"error\")") as ThrowStmt
            stmt.expression.shouldBeInstanceOf<CallExpr>()
        }

        test("throw with variable") {
            val stmt = parseFirst("throw error") as ThrowStmt
            (stmt.expression as IdentifierExpr).name shouldBe "error"
        }
    }

    // ====================================================================
    // Break and Continue
    // ====================================================================

    context("Break and continue") {
        test("break") {
            val stmt = parseFirst("break") as BreakStmt
            stmt.shouldNotBeNull()
        }

        test("continue") {
            val stmt = parseFirst("continue") as ContinueStmt
            stmt.shouldNotBeNull()
        }
    }

    // ====================================================================
    // Lang Block (KD DSL)
    // ====================================================================

    context("Lang blocks") {
        test("simple KD block") {
            val expr = parseExpr("""
                lang KD {
                    book "The Hobbit"
                }
            """.trimIndent()) as LangBlockExpr
            expr.language shouldBe "KD"
            expr.body shouldHaveSize 1
            expr.body[0].name shouldBe "book"
            expr.body[0].values shouldHaveSize 1
        }

        test("KD tag with attributes") {
            val expr = parseExpr("""
                lang KD {
                    book "The Hobbit" author="Tolkien" year=1937
                }
            """.trimIndent()) as LangBlockExpr
            val tag = expr.body[0]
            tag.attributes shouldHaveSize 2
            tag.attributes[0].name shouldBe "author"
            tag.attributes[1].name shouldBe "year"
        }

        test("KD tag with namespace") {
            val expr = parseExpr("""
                lang KD {
                    config:db host="localhost" port=5432
                }
            """.trimIndent()) as LangBlockExpr
            val tag = expr.body[0]
            tag.namespace shouldBe "config"
            tag.name shouldBe "db"
        }

        test("KD tag with children") {
            val expr = parseExpr("""
                lang KD {
                    library {
                        book "Book 1"
                        book "Book 2"
                    }
                }
            """.trimIndent()) as LangBlockExpr
            val parent = expr.body[0]
            parent.name shouldBe "library"
            parent.children shouldHaveSize 2
        }

        test("KD tag with annotation") {
            val expr = parseExpr("""
                lang KD {
                    @Important
                    task "Do something"
                }
            """.trimIndent()) as LangBlockExpr
            val tag = expr.body[0]
            tag.annotations shouldHaveSize 1
            tag.annotations[0].name shouldBe "Important"
        }

        test("KD annotation with values and attributes") {
            val expr = parseExpr("""
                lang KD {
                    @Test(true enabled=false)
                    testCase "example"
                }
            """.trimIndent()) as LangBlockExpr
            val annotation = expr.body[0].annotations[0]
            annotation.values shouldHaveSize 1
            annotation.attributes shouldHaveSize 1
        }

        test("multiple top-level KD tags") {
            val expr = parseExpr("""
                lang KD {
                    tag1 "value1"
                    tag2 "value2"
                    tag3 "value3"
                }
            """.trimIndent()) as LangBlockExpr
            expr.body shouldHaveSize 3
        }
    }

    // ====================================================================
    // Type References
    // ====================================================================

    context("Type references") {
        test("simple type") {
            val decl = parseFirst("var x: Int = 0") as VarDecl
            decl.typeAnnotation?.name shouldBe "Int"
            decl.typeAnnotation?.nullable shouldBe false
        }

        test("nullable type") {
            val decl = parseFirst("var x: String? = nil") as VarDecl
            decl.typeAnnotation?.nullable shouldBe true
        }

        test("generic type") {
            val decl = parseFirst("var items: List<Int> = []") as VarDecl
            decl.typeAnnotation?.name shouldBe "List"
            decl.typeAnnotation?.typeArgs?.size shouldBe 1
            decl.typeAnnotation?.typeArgs?.get(0)?.name shouldBe "Int"
        }

        test("map type") {
            val decl = parseFirst("var dict: Map<String, Int> = [=]") as VarDecl
            decl.typeAnnotation?.name shouldBe "Map"
            decl.typeAnnotation?.typeArgs?.size shouldBe 2
        }

        test("list shorthand [Int]") {
            val decl = parseFirst("var items: [Int] = []") as VarDecl
            decl.typeAnnotation?.name shouldBe "List"
            decl.typeAnnotation?.typeArgs?.get(0)?.name shouldBe "Int"
        }

        test("map shorthand [String:Int]") {
            val decl = parseFirst("var dict: [String:Int] = [=]") as VarDecl
            decl.typeAnnotation?.name shouldBe "Map"
            decl.typeAnnotation?.typeArgs?.size shouldBe 2
        }

        test("nullable generic type") {
            val decl = parseFirst("var items: List<Int>? = nil") as VarDecl
            decl.typeAnnotation?.nullable shouldBe true
        }

        test("nested generic type") {
            val decl = parseFirst("var matrix: List<List<Int>> = []") as VarDecl
            val outer = decl.typeAnnotation
            outer?.name shouldBe "List"
            val inner = outer?.typeArgs?.get(0)
            inner?.name shouldBe "List"
        }
    }

    // ====================================================================
    // Error Cases
    // ====================================================================

    context("Parse errors") {
        test("unclosed brace throws error") {
            shouldThrow<ParseError> {
                parse("if x > 0 {")
            }
        }

        test("unclosed parenthesis throws error") {
            shouldThrow<ParseError> {
                parse("foo(1, 2")
            }
        }

        test("missing expression after operator throws error") {
            shouldThrow<ParseError> {
                parse("x +")
            }
        }

        test("invalid assignment target throws error") {
            shouldThrow<ParseError> {
                parse("1 + 2 = 3")
            }
        }
    }

    // ====================================================================
    // Integration: Complex Programs
    // ====================================================================

    context("Integration: complex programs") {
        test("fibonacci function") {
            val program = parse("""
                fun fib(n: Int): Int {
                    if n <= 1 return n
                    return fib(n - 1) + fib(n - 2)
                }
            """.trimIndent())
            program.body shouldHaveSize 1
            val fn = program.body[0] as FunDecl
            fn.name shouldBe "fib"
        }

        test("class with methods and properties") {
            val program = parse("""
                class Counter(var count: Int = 0) {
                    fun increment() {
                        count++
                    }
                    
                    fun decrement() {
                        count--
                    }
                    
                    fun reset() {
                        count = 0
                    }
                }
            """.trimIndent())
            val cls = program.body[0] as ClassDecl
            cls.constructorParams shouldHaveSize 1
            cls.members shouldHaveSize 3
        }

        test("enum with when expression") {
            val program = parse("""
                enum Status { SUCCESS ERROR PENDING }
                
                let result = when status {
                    .SUCCESS -> "done"
                    .ERROR -> "failed"
                    .PENDING -> "waiting"
                    else -> "unknown"
                }
            """.trimIndent())
            program.body shouldHaveSize 2
        }

        test("KD configuration block") {
            val program = parse("""
                let config = lang KD {
                    database {
                        host "localhost"
                        port 5432
                        credentials {
                            user "admin"
                            pass @"secret"
                        }
                    }
                    
                    server {
                        port 8080
                        ssl enabled=true cert="server.pem"
                    }
                }
            """.trimIndent())
            program.body shouldHaveSize 1
            val decl = program.body[0] as VarDecl
            decl.initializer.shouldBeInstanceOf<LangBlockExpr>()
        }

        test("try-catch in function") {
            val program = parse("""
                fun safeDiv(a: Int, b: Int): Int {
                    try {
                        return a / b
                    } catch(e: DivisionByZero) {
                        say.error "Division by zero"
                        return 0
                    } finally {
                        say "Division attempted"
                    }
                }
            """.trimIndent())
            val fn = program.body[0] as FunDecl
            fn.body.shouldBeInstanceOf<BlockExpr>()
        }

        test("nested when expressions") {
            val program = parse("""
                let result = when category {
                    .FOOD -> when item {
                        .FRUIT -> "healthy"
                        .CANDY -> "sweet"
                        else -> "edible"
                    }
                    .DRINK -> "liquid"
                    else -> "unknown"
                }
            """.trimIndent())
            program.body shouldHaveSize 1
        }
    }
})