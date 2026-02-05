package io.kixi.ks.interp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.assertions.throwables.shouldThrow
import io.kixi.ks.SourceLocation
import io.kixi.ks.parser.*
import io.kixi.ks.*

/**
 * Tests for Environment — scoped variable bindings.
 */
class EnvironmentTest : FunSpec({

    // ========================================================================
    // Basic Variable Operations
    // ========================================================================

    context("variable definition and retrieval") {

        test("define and get a variable") {
            val env = Environment.global()
            env.define("x", 42, mutable = true)

            env.get("x") shouldBe 42
        }

        test("define immutable variable") {
            val env = Environment.global()
            env.define("PI", 3.14159, mutable = false)

            env.get("PI") shouldBe 3.14159
            env.isMutable("PI") shouldBe false
        }

        test("define mutable variable") {
            val env = Environment.global()
            env.define("count", 0, mutable = true)

            env.isMutable("count") shouldBe true
        }

        test("define variable with null value") {
            val env = Environment.global()
            env.define("nullable", null, mutable = true)

            env.get("nullable") shouldBe null
        }

        test("isDefined returns true for defined variable") {
            val env = Environment.global()
            env.define("x", 10, mutable = true)

            env.isDefined("x") shouldBe true
        }

        test("isDefined returns false for undefined variable") {
            val env = Environment.global()

            env.isDefined("undefined") shouldBe false
        }

        test("get undefined variable throws error") {
            val env = Environment.global()

            val error = shouldThrow<UndefinedNameError> {
                env.get("undefined")
            }
            error.name shouldBe "undefined"
        }

        test("redefinition in same scope throws error") {
            val env = Environment.global()
            env.define("x", 1, mutable = true)

            val error = shouldThrow<RedefinitionError> {
                env.define("x", 2, mutable = true)
            }
            error.name shouldBe "x"
        }
    }

    // ========================================================================
    // Variable Assignment
    // ========================================================================

    context("variable assignment") {

        test("assign to mutable variable") {
            val env = Environment.global()
            env.define("x", 10, mutable = true)

            env.assign("x", 20)

            env.get("x") shouldBe 20
        }

        test("assign to immutable variable throws error") {
            val env = Environment.global()
            env.define("x", 10, mutable = false)

            val error = shouldThrow<ImmutableAssignmentError> {
                env.assign("x", 20)
            }
            error.name shouldBe "x"
        }

        test("assign to undefined variable throws error") {
            val env = Environment.global()

            val error = shouldThrow<UndefinedNameError> {
                env.assign("undefined", 42)
            }
            error.name shouldBe "undefined"
        }

        test("multiple assignments") {
            val env = Environment.global()
            env.define("counter", 0, mutable = true)

            env.assign("counter", 1)
            env.assign("counter", 2)
            env.assign("counter", 3)

            env.get("counter") shouldBe 3
        }
    }

    // ========================================================================
    // Scoping
    // ========================================================================

    context("lexical scoping") {

        test("child scope can access parent variables") {
            val parent = Environment.global()
            parent.define("x", 10, mutable = true)

            val child = parent.child()

            child.get("x") shouldBe 10
        }

        test("child scope can shadow parent variables") {
            val parent = Environment.global()
            parent.define("x", 10, mutable = true)

            val child = parent.child()
            child.define("x", 20, mutable = true)

            child.get("x") shouldBe 20
            parent.get("x") shouldBe 10  // parent unchanged
        }

        test("assign in child affects parent scope variable") {
            val parent = Environment.global()
            parent.define("x", 10, mutable = true)

            val child = parent.child()
            child.assign("x", 20)

            parent.get("x") shouldBe 20  // parent updated
            child.get("x") shouldBe 20
        }

        test("assign in child with shadowed variable affects child only") {
            val parent = Environment.global()
            parent.define("x", 10, mutable = true)

            val child = parent.child()
            child.define("x", 20, mutable = true)  // shadow
            child.assign("x", 30)

            child.get("x") shouldBe 30
            parent.get("x") shouldBe 10  // parent unchanged
        }

        test("child variables not visible in parent") {
            val parent = Environment.global()
            val child = parent.child()
            child.define("childOnly", 42, mutable = true)

            child.get("childOnly") shouldBe 42
            shouldThrow<UndefinedNameError> {
                parent.get("childOnly")
            }
        }

        test("deeply nested scopes") {
            val global = Environment.global()
            global.define("a", 1, mutable = true)

            val level1 = global.child("level1")
            level1.define("b", 2, mutable = true)

            val level2 = level1.child("level2")
            level2.define("c", 3, mutable = true)

            val level3 = level2.child("level3")

            // level3 can see all variables
            level3.get("a") shouldBe 1
            level3.get("b") shouldBe 2
            level3.get("c") shouldBe 3

            // depth tracking
            level3.depth() shouldBe 3
            global.depth() shouldBe 0
        }

        test("global() returns root scope") {
            val global = Environment.global()
            val child = global.child()
            val grandchild = child.child()

            grandchild.global() shouldBe global
        }
    }

    // ========================================================================
    // Type and Constraint Metadata
    // ========================================================================

    context("type and constraint metadata") {

        test("define with type annotation") {
            val env = Environment.global()
            val intType = TypeRef("Int")
            env.define("x", 42, mutable = true, type = intType)

            env.getType("x") shouldBe intType
        }

        test("define with constraint") {
            val env = Environment.global()
            val constraint = ComparisonConstraint(
                ComparisonOp.GT,
                LiteralExpr(0, LiteralKind.INT, SourceLocation()),
                SourceLocation()
            )
            env.define("age", 25, mutable = true, constraint = constraint)

            env.getConstraint("age") shouldBe constraint
        }

        test("type is null when not specified") {
            val env = Environment.global()
            env.define("x", 42, mutable = true)

            env.getType("x") shouldBe null
        }

        test("constraint is null when not specified") {
            val env = Environment.global()
            env.define("x", 42, mutable = true)

            env.getConstraint("x") shouldBe null
        }
    }

    // ========================================================================
    // Iteration Support
    // ========================================================================

    context("implicit 'it' variable") {

        test("defineIt creates 'it' variable") {
            val env = Environment.global()
            env.defineIt(1)

            env.get("it") shouldBe 1
        }

        test("updateIt modifies 'it' variable") {
            val env = Environment.global()
            env.defineIt(1)
            env.updateIt(2)

            env.get("it") shouldBe 2
        }

        test("defineIt can redefine in same scope") {
            val env = Environment.global()
            env.defineIt(1)
            env.defineIt(2)  // Should not throw

            env.get("it") shouldBe 2
        }

        test("for loop simulation with 'it'") {
            val env = Environment.global()
            val results = mutableListOf<Int>()

            val loopScope = env.child("for-loop")
            for (i in listOf(1, 2, 3)) {
                loopScope.defineIt(i)
                results.add(loopScope.get("it") as Int)
            }

            results shouldBe listOf(1, 2, 3)
        }
    }

    // ========================================================================
    // Function Operations
    // ========================================================================

    context("function definitions") {

        test("define and get function") {
            val env = Environment.global()
            val funDecl = FunDecl(
                name = "greet",
                params = emptyList(),
                returnType = null,
                body = null,
                isSingleExpr = false,
                location = SourceLocation()
            )
            val function = KSFunction(funDecl, env)

            env.defineFunction("greet", function)

            env.getFunction("greet") shouldBe function
        }

        test("function not found throws error") {
            val env = Environment.global()

            val error = shouldThrow<UndefinedNameError> {
                env.getFunction("undefined")
            }
            error.kind shouldBe NameKind.FUNCTION
        }

        test("isFunctionDefined") {
            val env = Environment.global()
            val funDecl = FunDecl("foo", emptyList(), null, null, false, SourceLocation())
            env.defineFunction("foo", KSFunction(funDecl, env))

            env.isFunctionDefined("foo") shouldBe true
            env.isFunctionDefined("bar") shouldBe false
        }

        test("child scope can access parent functions") {
            val parent = Environment.global()
            val funDecl = FunDecl("parentFun", emptyList(), null, null, false, SourceLocation())
            parent.defineFunction("parentFun", KSFunction(funDecl, parent))

            val child = parent.child()

            child.getFunction("parentFun") shouldNotBe null
        }

        test("function and variable can share name (separate namespaces)") {
            val env = Environment.global()
            env.define("foo", 42, mutable = true)

            val funDecl = FunDecl("foo", emptyList(), null, null, false, SourceLocation())
            env.defineFunction("foo", KSFunction(funDecl, env))

            env.get("foo") shouldBe 42
            env.getFunction("foo") shouldNotBe null
        }
    }

    // ========================================================================
    // Introspection
    // ========================================================================

    context("introspection") {

        test("localNames returns names in current scope only") {
            val parent = Environment.global()
            parent.define("a", 1, mutable = true)

            val child = parent.child()
            child.define("b", 2, mutable = true)
            child.define("c", 3, mutable = true)

            child.localNames() shouldContainAll setOf("b", "c")
            child.localNames().contains("a") shouldBe false
        }

        test("allNames returns names from all scopes") {
            val parent = Environment.global()
            parent.define("a", 1, mutable = true)

            val child = parent.child()
            child.define("b", 2, mutable = true)

            child.allNames() shouldContainAll setOf("a", "b")
        }

        test("depth tracking") {
            val global = Environment.global()
            val child1 = global.child()
            val child2 = child1.child()

            global.depth() shouldBe 0
            child1.depth() shouldBe 1
            child2.depth() shouldBe 2
        }

        test("dump produces readable output") {
            val env = Environment.global()
            env.define("x", 42, mutable = true)
            env.define("name", "Alice", mutable = false)

            val dump = env.dump()

            dump.contains("x") shouldBe true
            dump.contains("42") shouldBe true
            dump.contains("name") shouldBe true
            dump.contains("var") shouldBe true
            dump.contains("let") shouldBe true
        }

        test("toString is informative") {
            val env = Environment(null, "test-scope")
            env.define("a", 1, mutable = true)
            env.define("b", 2, mutable = true)

            val str = env.toString()
            str.contains("test-scope") shouldBe true
            str.contains("vars=2") shouldBe true
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    context("edge cases") {

        test("assign preserves type and constraint metadata") {
            val env = Environment.global()
            val intType = TypeRef("Int")
            val constraint = ComparisonConstraint(
                ComparisonOp.GT,
                LiteralExpr(0, LiteralKind.INT, SourceLocation()),
                SourceLocation()
            )
            env.define("x", 10, mutable = true, type = intType, constraint = constraint)

            env.assign("x", 20)

            env.get("x") shouldBe 20
            env.getType("x") shouldBe intType
            env.getConstraint("x") shouldBe constraint
        }

        test("empty environment") {
            val env = Environment.global()

            env.localNames().size shouldBe 0
            env.isDefined("anything") shouldBe false
        }

        test("parent accessor") {
            val parent = Environment.global()
            val child = parent.child()

            child.parent() shouldBe parent
            parent.parent() shouldBe null
        }
    }
})