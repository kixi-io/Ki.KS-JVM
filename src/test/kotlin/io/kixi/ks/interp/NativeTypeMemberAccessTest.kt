package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Tests for native Ki.Core type member access from KS code.
 *
 * Covers constructors, static members, instance properties, instance methods,
 * type checking (`is`), reflection (`.type`, `.typeName`), and superclass
 * member inheritance (Tag inherits Call members).
 *
 * Native types tested:
 *   - Version
 *   - Blob
 *   - NSID
 *   - Call
 *   - Tag (extends Call)
 *   - Grid
 *   - Quantity (including Currency quantities)
 *   - Range
 *   - Coordinate
 *   - Currency
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.NativeTypeMemberAccessTest"
 */
class NativeTypeMemberAccessTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

    fun run(source: String): String {
        val output = StringWriter()
        val error = StringWriter()
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = PrintWriter(output, true),
            errorWriter = PrintWriter(error, true),
            debugMode = false
        )
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = Interpreter(runtime)
        interpreter.executeProgram(program)
        return output.toString().trim()
    }

    fun eval(source: String): Any? {
        val output = StringWriter()
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = PrintWriter(output, true),
            errorWriter = PrintWriter(StringWriter(), true),
            debugMode = false
        )
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = Interpreter(runtime)
        return interpreter.executeProgram(program)
    }

    fun runExpectingError(source: String): String {
        val output = StringWriter()
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = PrintWriter(output, true),
            errorWriter = PrintWriter(StringWriter(), true)
        )
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = Interpreter(runtime)

        try {
            interpreter.executeProgram(program)
            throw AssertionError("Expected an error but execution completed. Output: ${output.toString().trim()}")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            return e.message ?: e.toString()
        }
    }

    // ====================================================================
    // Version
    // ====================================================================

    context("Version - construction") {

        test("construct with major only") {
            run("""
                let v = Version(5)
                say v.major
            """.trimIndent()) shouldBe "5"
        }

        test("construct with major, minor") {
            run("""
                let v = Version(5, 2)
                say v.major
                say v.minor
            """.trimIndent()) shouldBe "5\n2"
        }

        test("construct with major, minor, micro") {
            run("""
                let v = Version(1, 2, 3)
                say v.major
                say v.minor
                say v.micro
            """.trimIndent()) shouldBe "1\n2\n3"
        }

        test("construct with qualifier") {
            run("""
                let v = Version(1, 0, 0, "beta")
                say v.qualifier
            """.trimIndent()) shouldBe "beta"
        }

        test("construct with qualifier and number") {
            run("""
                let v = Version(1, 0, 0, "rc", 2)
                say v.qualifier
                say v.qualifierNumber
            """.trimIndent()) shouldBe "rc\n2"
        }
    }

    context("Version - static members") {

        test("Version.parse") {
            run("""
                let v = Version.parse("5.2.7")
                say v.major
                say v.minor
                say v.micro
            """.trimIndent()) shouldBe "5\n2\n7"
        }

        test("Version.parse with qualifier") {
            run("""
                let v = Version.parse("1.0.0-beta-2")
                say v.qualifier
                say v.qualifierNumber
            """.trimIndent()) shouldBe "beta\n2"
        }

        test("Version.EMPTY") {
            run("""
                let v = Version.EMPTY
                say v.major
                say v.minor
                say v.micro
            """.trimIndent()) shouldBe "0\n0\n0"
        }

        test("Version.MIN") {
            run("""
                let v = Version.MIN
                say v.major
                say v.qualifier
            """.trimIndent()) shouldBe "0\nAAA"
        }

        test("Version.MAX") {
            run("""
                let v = Version.MAX
                say v.major
            """.trimIndent()) shouldBe "${Int.MAX_VALUE}"
        }
    }

    context("Version - instance properties") {

        test("hasQualifier") {
            run("""
                say Version(1, 0, 0, "beta").hasQualifier
                say Version(1, 0, 0).hasQualifier
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("isStable and isPreRelease") {
            run("""
                say Version(1, 0, 0).isStable
                say Version(1, 0, 0).isPreRelease
                say Version(1, 0, 0, "alpha").isStable
                say Version(1, 0, 0, "alpha").isPreRelease
            """.trimIndent()) shouldBe "true\nfalse\nfalse\ntrue"
        }
    }

    context("Version - instance methods") {

        test("toShortString") {
            run("""
                say Version(5, 0, 0).toShortString()
                say Version(5, 2, 0).toShortString()
                say Version(5, 2, 7).toShortString()
            """.trimIndent()) shouldBe "5\n5.2\n5.2.7"
        }

        test("toStable") {
            run("""
                let v = Version(1, 0, 0, "beta", 2).toStable()
                say v.hasQualifier
                say v.major
            """.trimIndent()) shouldBe "false\n1"
        }

        test("incrementMajor") {
            run("""
                let v = Version(1, 2, 3).incrementMajor()
                say v.major
                say v.minor
                say v.micro
            """.trimIndent()) shouldBe "2\n0\n0"
        }

        test("incrementMinor") {
            run("""
                let v = Version(1, 2, 3).incrementMinor()
                say v.major
                say v.minor
                say v.micro
            """.trimIndent()) shouldBe "1\n3\n0"
        }

        test("incrementMicro") {
            run("""
                let v = Version(1, 2, 3).incrementMicro()
                say v.micro
            """.trimIndent()) shouldBe "4"
        }

        test("incrementQualifierNumber") {
            run("""
                let v = Version(1, 0, 0, "beta", 1).incrementQualifierNumber()
                say v.qualifierNumber
            """.trimIndent()) shouldBe "2"
        }

        test("incrementQualifierNumber without qualifier throws") {
            val error = runExpectingError("""
                Version(1, 0, 0).incrementQualifierNumber()
            """.trimIndent())
            error shouldContain "qualifier"
        }

        test("isCompatibleWith") {
            run("""
                say Version(1, 2, 0).isCompatibleWith(Version(1, 5, 0))
                say Version(1, 0, 0).isCompatibleWith(Version(2, 0, 0))
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("withQualifier") {
            run("""
                let v = Version(1, 0, 0).withQualifier("rc", 1)
                say v.qualifier
                say v.qualifierNumber
            """.trimIndent()) shouldBe "rc\n1"
        }

        test("chained method calls on Version") {
            run("""
                let v = Version(1, 0, 0).incrementMajor().withQualifier("alpha")
                say v.major
                say v.qualifier
            """.trimIndent()) shouldBe "2\nalpha"
        }
    }

    // ====================================================================
    // Blob
    // ====================================================================

    context("Blob - construction") {

        test("construct from string") {
            run("""
                let b = Blob("Hello")
                say b.size
            """.trimIndent()) shouldBe "5"
        }

        test("construct empty") {
            run("""
                let b = Blob()
                say b.isEmpty
            """.trimIndent()) shouldBe "true"
        }
    }

    context("Blob - static members") {

        test("Blob.empty") {
            run("""
                let b = Blob.empty()
                say b.isEmpty
                say b.size
            """.trimIndent()) shouldBe "true\n0"
        }

        test("Blob.of from string") {
            run("""
                let b = Blob.of("Hello World!")
                say b.size
                say b.isNotEmpty
            """.trimIndent()) shouldBe "12\ntrue"
        }

        test("Blob.parse from Base64") {
            run("""
                let b = Blob.parse("SGVsbG8=")
                say b.decodeToString()
            """.trimIndent()) shouldBe "Hello"
        }

        test("Blob.isLiteral") {
            run("""
                say Blob.isLiteral(".blob(SGVsbG8=)")
                say Blob.isLiteral("not a blob")
            """.trimIndent()) shouldBe "true\nfalse"
        }
    }

    context("Blob - instance properties") {

        test("size, isEmpty, isNotEmpty") {
            run("""
                let empty = Blob()
                let data = Blob("test")
                say empty.size
                say empty.isEmpty
                say empty.isNotEmpty
                say data.size
                say data.isEmpty
                say data.isNotEmpty
            """.trimIndent()) shouldBe "0\ntrue\nfalse\n4\nfalse\ntrue"
        }
    }

    context("Blob - instance methods") {

        test("toBase64 and decodeToString roundtrip") {
            run("""
                let b = Blob("Hello World!")
                let encoded = b.toBase64()
                say encoded
                let b2 = Blob.parse(encoded)
                say b2.decodeToString()
            """.trimIndent()) shouldBe "SGVsbG8gV29ybGQh\nHello World!"
        }

        test("toBase64UrlSafe") {
            run("""
                let b = Blob("Hello World!")
                let safe = b.toBase64UrlSafe()
                say safe.size > 0
            """.trimIndent()) shouldBe "true"
        }

        test("get individual byte") {
            run("""
                let b = Blob("A")
                say b.get(0)
            """.trimIndent()) shouldBe "65"
        }
    }

    // ====================================================================
    // NSID
    // ====================================================================

    context("NSID - construction") {

        test("construct with name only") {
            run("""
                let id = NSID("myFunc")
                say id.name
                say id.hasNamespace
            """.trimIndent()) shouldBe "myFunc\nfalse"
        }

        test("construct with name and namespace") {
            run("""
                let id = NSID("myFunc", "myNS")
                say id.name
                say id.namespace
            """.trimIndent()) shouldBe "myFunc\nmyNS"
        }

        test("construct anonymous") {
            run("""
                let id = NSID()
                say id.isAnonymous
            """.trimIndent()) shouldBe "true"
        }
    }

    context("NSID - static members") {

        test("NSID.ANONYMOUS") {
            run("""
                let anon = NSID.ANONYMOUS
                say anon.isAnonymous
            """.trimIndent()) shouldBe "true"
        }

        test("NSID.parse") {
            run("""
                let id = NSID.parse("myNS:myName")
                say id.name
                say id.namespace
            """.trimIndent()) shouldBe "myName\nmyNS"
        }

        test("NSID.parse simple name") {
            run("""
                let id = NSID.parse("simpleName")
                say id.name
                say id.hasNamespace
            """.trimIndent()) shouldBe "simpleName\nfalse"
        }
    }

    context("NSID - instance properties") {

        test("isAnonymous and hasNamespace") {
            run("""
                let ns = NSID("func", "pkg")
                say ns.isAnonymous
                say ns.hasNamespace
                let simple = NSID("func")
                say simple.hasNamespace
            """.trimIndent()) shouldBe "false\ntrue\nfalse"
        }
    }

    // ====================================================================
    // Call
    // ====================================================================

    context("Call - construction") {

        test("construct with name") {
            run("""
                let c = Call("myFunc")
                say c.name
            """.trimIndent()) shouldBe "myFunc"
        }

        test("construct with name and namespace") {
            run("""
                let c = Call("myFunc", "myNS")
                say c.name
                say c.namespace
            """.trimIndent()) shouldBe "myFunc\nmyNS"
        }
    }

    context("Call - instance properties") {

        test("name and namespace") {
            run("""
                let c = Call("func", "ns")
                say c.name
                say c.namespace
            """.trimIndent()) shouldBe "func\nns"
        }

        test("nsid property") {
            run("""
                let c = Call("func", "ns")
                let id = c.nsid
                say id.name
                say id.namespace
            """.trimIndent()) shouldBe "func\nns"
        }

        test("value count initially zero") {
            run("""
                let c = Call("func")
                say c.valueCount
                say c.attributeCount
            """.trimIndent()) shouldBe "0\n0"
        }
    }

    context("Call - instance methods") {

        test("withValue and values access") {
            run("""
                let c = Call("add").withValue(1).withValue(2).withValue(3)
                say c.valueCount
                let vals = c.values
                say vals[0]
                say vals[1]
                say vals[2]
            """.trimIndent()) shouldBe "3\n1\n2\n3"
        }

        test("hasValues and hasAttributes") {
            run("""
                let empty = Call("func")
                say empty.hasValues()
                say empty.hasAttributes()
                let withVal = Call("func").withValue(1)
                say withVal.hasValues()
            """.trimIndent()) shouldBe "false\nfalse\ntrue"
        }

        test("hasValue checks bounds") {
            run("""
                let c = Call("func").withValue("hello")
                say c.hasValue(0)
                say c.hasValue(1)
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("getValue") {
            run("""
                let c = Call("func").withValue("hello").withValue(42)
                say c.getValue(0)
                say c.getValue(1)
            """.trimIndent()) shouldBe "hello\n42"
        }

        test("getValueOrDefault") {
            run("""
                let c = Call("func").withValue("hello")
                say c.getValueOrDefault(0, "default")
                say c.getValueOrDefault(5, "default")
            """.trimIndent()) shouldBe "hello\ndefault"
        }

        test("getValueOrNull") {
            run("""
                let c = Call("func").withValue("hello")
                say c.getValueOrNull(0)
                say c.getValueOrNull(5)
            """.trimIndent()) shouldBe "hello\nnil"
        }

        test("setAttribute and getAttribute") {
            run("""
                let c = Call("func")
                c.setAttribute("key", "value")
                say c.getAttribute("key")
                say c.hasAttribute("key")
                say c.hasAttribute("missing")
            """.trimIndent()) shouldBe "value\ntrue\nfalse"
        }

        test("withAttribute") {
            run("""
                let c = Call("func").withAttribute("x", 10)
                say c.getAttribute("x")
                say c.attributeCount
            """.trimIndent()) shouldBe "10\n1"
        }

        test("getAttributeOrDefault") {
            run("""
                let c = Call("func").withAttribute("key", "val")
                say c.getAttributeOrDefault("key", "none")
                say c.getAttributeOrDefault("missing", "none")
            """.trimIndent()) shouldBe "val\nnone"
        }

        test("getAttributeOrNull") {
            run("""
                let c = Call("func").withAttribute("key", "val")
                say c.getAttributeOrNull("key")
                say c.getAttributeOrNull("missing")
            """.trimIndent()) shouldBe "val\nnil"
        }

        test("value convenience property") {
            run("""
                let c = Call("func").withValue("first").withValue("second")
                say c.value
            """.trimIndent()) shouldBe "first"
        }

        test("value is nil when no values") {
            run("""
                let c = Call("func")
                say c.value
            """.trimIndent()) shouldBe "nil"
        }
    }

    // ====================================================================
    // Tag (extends Call)
    // ====================================================================

    context("Tag - construction") {

        test("construct with name") {
            run("""
                let t = Tag("item")
                say t.name
            """.trimIndent()) shouldBe "item"
        }

        test("construct with name and namespace") {
            run("""
                let t = Tag("item", "pkg")
                say t.name
                say t.namespace
            """.trimIndent()) shouldBe "item\npkg"
        }

        test("construct anonymous") {
            run("""
                let t = Tag()
                say t.isAnonymous
            """.trimIndent()) shouldBe "true"
        }
    }

    context("Tag - inherits Call members") {

        test("Tag has name and namespace from Call") {
            run("""
                let t = Tag("func", "ns")
                say t.name
                say t.namespace
            """.trimIndent()) shouldBe "func\nns"
        }

        test("Tag withValue inherited from Call") {
            run("""
                let t = Tag("item").withValue(42).withValue("hello")
                say t.valueCount
                say t.getValue(0)
                say t.getValue(1)
            """.trimIndent()) shouldBe "2\n42\nhello"
        }

        test("Tag setAttribute inherited from Call") {
            run("""
                let t = Tag("config")
                t.setAttribute("debug", true)
                say t.getAttribute("debug")
                say t.hasAttribute("debug")
            """.trimIndent()) shouldBe "true\ntrue"
        }

        test("Tag hasValues and hasAttributes from Call") {
            run("""
                let t = Tag("test")
                say t.hasValues()
                say t.hasAttributes()
                t.withValue(1)
                say t.hasValues()
            """.trimIndent()) shouldBe "false\nfalse\ntrue"
        }

        test("Tag nsid property from Call") {
            run("""
                let t = Tag("item", "pkg")
                let id = t.nsid
                say id.name
                say id.namespace
            """.trimIndent()) shouldBe "item\npkg"
        }
    }

    context("Tag - specific properties") {

        test("isAnonymous") {
            run("""
                say Tag().isAnonymous
                say Tag("named").isAnonymous
            """.trimIndent()) shouldBe "true\nfalse"
        }
    }

    // ====================================================================
    // Grid
    // ====================================================================

    context("Grid - construction") {

        test("construct with width, height") {
            run("""
                let g = Grid(3, 2)
                say g.width
                say g.height
            """.trimIndent()) shouldBe "3\n2"
        }

        test("construct with default value") {
            run("""
                let g = Grid(2, 2, 0)
                say g.size
            """.trimIndent()) shouldBe "4"
        }
    }

    context("Grid - static members") {

        test("Grid.of") {
            run("""
                let g = Grid.of(3, 2, "x")
                say g.width
                say g.height
            """.trimIndent()) shouldBe "3\n2"
        }

        test("Grid.ofNulls") {
            run("""
                let g = Grid.ofNulls(2, 3)
                say g.width
                say g.height
                say g.elementNullable
            """.trimIndent()) shouldBe "2\n3\ntrue"
        }
    }

    context("Grid - instance properties") {

        test("width, height, size") {
            run("""
                let g = Grid(4, 3, 0)
                say g.width
                say g.height
                say g.size
            """.trimIndent()) shouldBe "4\n3\n12"
        }

        test("isEmpty and isNotEmpty") {
            run("""
                let g = Grid(2, 2, 0)
                say g.isEmpty
                say g.isNotEmpty
            """.trimIndent()) shouldBe "false\ntrue"
        }
    }

    context("Grid - instance methods") {

        test("toList") {
            run("""
                let g = Grid(2, 2, 0)
                g[0, 0] = 1
                g[1, 0] = 2
                g[0, 1] = 3
                g[1, 1] = 4
                say g.toList()
            """.trimIndent()) shouldBe "[1, 2, 3, 4]"
        }

        test("copy creates independent grid") {
            run("""
                let g1 = Grid(2, 1, 0)
                g1[0, 0] = 5
                let g2 = g1.copy()
                g2[0, 0] = 99
                say g1[0, 0]
                say g2[0, 0]
            """.trimIndent()) shouldBe "5\n99"
        }

        test("transpose") {
            run("""
                let g = Grid(3, 1, 0)
                g[0, 0] = 1
                g[1, 0] = 2
                g[2, 0] = 3
                let t = g.transpose()
                say t.width
                say t.height
            """.trimIndent()) shouldBe "1\n3"
        }

        test("fill") {
            run("""
                let g = Grid(2, 2, 0)
                g.fill(7)
                say g[0, 0]
                say g[1, 1]
            """.trimIndent()) shouldBe "7\n7"
        }

        test("getRowCopy") {
            run("""
                let g = Grid(3, 2, 0)
                g[0, 0] = 1
                g[1, 0] = 2
                g[2, 0] = 3
                say g.getRowCopy(0)
            """.trimIndent()) shouldBe "[1, 2, 3]"
        }

        test("subgrid") {
            run("""
                let g = Grid(4, 4, 0)
                g[1, 1] = 5
                g[2, 1] = 6
                g[1, 2] = 7
                g[2, 2] = 8
                let sub = g.subgrid(1, 1, 2, 2)
                say sub.width
                say sub.height
                say sub[0, 0]
                say sub[1, 1]
            """.trimIndent()) shouldBe "2\n2\n5\n8"
        }
    }

    // ====================================================================
    // Quantity
    // ====================================================================

    context("Quantity - construction") {

        test("construct from number and unit string") {
            run("""
                let q = Quantity(5, "cm")
                say q.value
                say q.unit
            """.trimIndent()) shouldBe "5\ncm"
        }

        test("construct from parse string") {
            run("""
                let q = Quantity("100kg")
                say q.value
                say q.unit
            """.trimIndent()) shouldBe "100\nkg"
        }
    }

    context("Quantity - static members") {

        test("Quantity.parse") {
            run("""
                let q = Quantity.parse("25m")
                say q.value
                say q.unit
            """.trimIndent()) shouldBe "25\nm"
        }
    }

    context("Quantity - instance properties") {

        test("value and unit") {
            run("""
                let q = Quantity(42, "kg")
                say q.value
                say q.unit
            """.trimIndent()) shouldBe "42\nkg"
        }
    }

    context("Quantity - instance methods") {

        test("toSuffixString") {
            run("""
                let q = Quantity(5, "cm")
                let s = q.toSuffixString()
                say s
            """.trimIndent()) shouldBe "5cm:i"
        }
    }

    // ====================================================================
    // Currency members
    // ====================================================================

    context("Currency - instance properties") {

        test("currency unit properties via quantity") {
            run("""
                let q = Quantity(100, "USD")
                let cu = q.unitObject
                say cu.symbol
                say cu.currencyName
                say cu.isFiat
                say cu.isCrypto
            """.trimIndent()) shouldBe "USD\nUS Dollar\ntrue\nfalse"
        }

        test("currency hasPrefixSymbol") {
            run("""
                let q = Quantity(50, "USD")
                let cu = q.unitObject
                say cu.hasPrefixSymbol
            """.trimIndent()) shouldBe "true"
        }
    }

    // ====================================================================
    // Range
    // ====================================================================

    context("Range - construction") {

        test("construct inclusive range") {
            run("""
                let r = Range(1, 10)
                say r.start
                say r.end
            """.trimIndent()) shouldBe "1\n10"
        }

        test("construct with explicit bound") {
            run("""
                let r = Range(1, 10, "Exclusive")
                say r.bound
            """.trimIndent()) shouldBe "<..<"
        }
    }

    context("Range - static members") {

        test("Range.inclusive") {
            run("""
                let r = Range.inclusive(1, 5)
                say r.start
                say r.end
                say r.bound
            """.trimIndent()) shouldBe "1\n5\n.."
        }

        test("Range.exclusive") {
            run("""
                let r = Range.exclusive(1, 5)
                say r.bound
            """.trimIndent()) shouldBe "<..<"
        }

        test("Range.openStart") {
            run("""
                let r = Range.openStart(10)
                say r.start
                say r.end
                say r.isOpenStart
            """.trimIndent()) shouldBe "nil\n10\ntrue"
        }

        test("Range.openEnd") {
            run("""
                let r = Range.openEnd(0)
                say r.start
                say r.end
                say r.isOpenEnd
            """.trimIndent()) shouldBe "0\nnil\ntrue"
        }
    }

    context("Range - instance properties") {

        test("core properties") {
            run("""
                let r = Range(1, 10)
                say r.start
                say r.end
                say r.min
                say r.max
            """.trimIndent()) shouldBe "1\n10\n1\n10"
        }

        test("openness properties") {
            run("""
                let closed = Range(1, 10)
                say closed.isOpen
                say closed.isClosed
                let open = Range.openEnd(0)
                say open.isOpen
                say open.isOpenEnd
                say open.isOpenStart
            """.trimIndent()) shouldBe "false\ntrue\ntrue\ntrue\nfalse"
        }

        test("exclusivity properties") {
            run("""
                let incl = Range(1, 10, "Inclusive")
                say incl.startExclusive
                say incl.endExclusive
                let exclEnd = Range(1, 10, "ExclusiveEnd")
                say exclEnd.startExclusive
                say exclEnd.endExclusive
            """.trimIndent()) shouldBe "false\nfalse\nfalse\ntrue"
        }

        test("reversed range") {
            run("""
                let r = Range(10, 1)
                say r.reversed
            """.trimIndent()) shouldBe "true"
        }
    }

    context("Range - instance methods") {

        test("contains") {
            run("""
                let r = Range(1, 10)
                say r.contains(5)
                say r.contains(11)
                say r.contains(1)
                say r.contains(10)
            """.trimIndent()) shouldBe "true\nfalse\ntrue\ntrue"
        }

        test("toList") {
            run("""
                let r = Range(1, 5)
                say r.toList()
            """.trimIndent()) shouldBe "[1, 2, 3, 4, 5]"
        }

        test("toList with step") {
            run("""
                let r = Range(0, 10)
                say r.toList(3)
            """.trimIndent()) shouldBe "[0, 3, 6, 9]"
        }

        test("count") {
            run("""
                let r = Range(1, 10)
                say r.count()
            """.trimIndent()) shouldBe "10"
        }

        test("count with step") {
            run("""
                let r = Range(0, 10)
                say r.count(2)
            """.trimIndent()) shouldBe "6"
        }

        test("clamp") {
            run("""
                let r = Range(0, 10)
                say r.clamp(5)
                say r.clamp(-5)
                say r.clamp(15)
            """.trimIndent()) shouldBe "5\n0\n10"
        }

        test("overlaps") {
            run("""
                let r1 = Range(1, 10)
                let r2 = Range(5, 15)
                let r3 = Range(11, 20)
                say r1.overlaps(r2)
                say r1.overlaps(r3)
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("intersect") {
            run("""
                let r1 = Range(1, 10)
                let r2 = Range(5, 15)
                let inter = r1.intersect(r2)
                say inter.start
                say inter.end
            """.trimIndent()) shouldBe "5\n10"
        }
    }

    context("Range - literal syntax integration") {

        test("range literal properties") {
            run("""
                let r = 1..10
                say r.start
                say r.end
                say r.isClosed
            """.trimIndent()) shouldBe "1\n10\ntrue"
        }

        test("exclusive end range literal properties") {
            run("""
                let r = 1..<10
                say r.start
                say r.end
                say r.endExclusive
            """.trimIndent()) shouldBe "1\n10\ntrue"
        }

        test("range literal methods") {
            run("""
                let r = 1..5
                say r.contains(3)
                say r.toList()
            """.trimIndent()) shouldBe "true\n[1, 2, 3, 4, 5]"
        }
    }

    // ====================================================================
    // Coordinate
    // ====================================================================

    context("Coordinate - construction") {

        test("construct standard coordinate") {
            run("""
                let c = Coordinate(3, 5)
                say c.x
                say c.y
            """.trimIndent()) shouldBe "3\n5"
        }

        test("construct with z") {
            run("""
                let c = Coordinate(1, 2, 3)
                say c.x
                say c.y
                say c.z
                say c.hasZ
            """.trimIndent()) shouldBe "1\n2\n3\ntrue"
        }

        test("construct sheet coordinate") {
            run("""
                let c = Coordinate("B", 3)
                say c.column
                say c.row
            """.trimIndent()) shouldBe "B\n3"
        }
    }

    context("Coordinate - static members") {

        test("Coordinate.standard") {
            run("""
                let c = Coordinate.standard(5, 10)
                say c.x
                say c.y
            """.trimIndent()) shouldBe "5\n10"
        }

        test("Coordinate.sheet") {
            run("""
                let c = Coordinate.sheet("A", 1)
                say c.column
                say c.row
            """.trimIndent()) shouldBe "A\n1"
        }

        test("Coordinate.columnToIndex") {
            run("""
                say Coordinate.columnToIndex("A")
                say Coordinate.columnToIndex("B")
                say Coordinate.columnToIndex("Z")
            """.trimIndent()) shouldBe "0\n1\n25"
        }

        test("Coordinate.indexToColumn") {
            run("""
                say Coordinate.indexToColumn(0)
                say Coordinate.indexToColumn(1)
                say Coordinate.indexToColumn(25)
            """.trimIndent()) shouldBe "A\nB\nZ"
        }
    }

    context("Coordinate - instance properties") {

        test("x, y, column, row") {
            run("""
                let c = Coordinate("C", 5)
                say c.column
                say c.row
                say c.x
                say c.y
            """.trimIndent()) shouldBe "C\n5\n2\n4"
        }

        test("hasZ false by default") {
            run("""
                let c = Coordinate(1, 2)
                say c.hasZ
            """.trimIndent()) shouldBe "false"
        }
    }

    context("Coordinate - instance methods") {

        test("toSheetNotation") {
            run("""
                let c = Coordinate("B", 3)
                say c.toSheetNotation()
            """.trimIndent()) shouldBe "B3"
        }

        test("right and left") {
            run("""
                let c = Coordinate(5, 5)
                let r = c.right(3)
                let l = c.left(2)
                say r.x
                say l.x
            """.trimIndent()) shouldBe "8\n3"
        }

        test("up and down") {
            run("""
                let c = Coordinate(5, 5)
                let u = c.up(2)
                let d = c.down(3)
                say u.y
                say d.y
            """.trimIndent()) shouldBe "3\n8"
        }

        test("navigation defaults to step of 1") {
            run("""
                let c = Coordinate(5, 5)
                say c.right().x
                say c.left().x
                say c.up().y
                say c.down().y
            """.trimIndent()) shouldBe "6\n4\n4\n6"
        }
    }

    // ====================================================================
    // Type Checking (is / !is)
    // ====================================================================

    context("Type checking for native types") {

        test("is Version") {
            run("""
                let v = Version(1, 0, 0)
                say v is Version
                say "hello" is Version
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("is Blob") {
            run("""
                let b = Blob("test")
                say b is Blob
                say 42 is Blob
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("is NSID") {
            run("""
                let id = NSID("test")
                say id is NSID
                say "test" is NSID
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("is Call") {
            run("""
                let c = Call("func")
                say c is Call
                say 42 is Call
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("Tag is Call (inheritance)") {
            run("""
                let t = Tag("item")
                say t is Call
            """.trimIndent()) shouldBe "true"
        }

        test("is Range") {
            run("""
                let r = 1..10
                say r is Range
                say 5 is Range
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("is Grid") {
            run("""
                let g = Grid(2, 2, 0)
                say g is Grid
                say [1, 2] is Grid
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("is Coordinate") {
            run("""
                let c = Coordinate(1, 2)
                say c is Coordinate
                say 42 is Coordinate
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("is Quantity") {
            run("""
                let q = Quantity(5, "cm")
                say q is Quantity
                say 5 is Quantity
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("is Currency") {
            run("""
                let q = Quantity(100, "USD")
                let cu = q.unitObject
                say cu is Currency
                say "USD" is Currency
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("!is operator") {
            run("""
                say 42 !is Version
                say Version(1, 0, 0) !is Version
            """.trimIndent()) shouldBe "true\nfalse"
        }
    }

    // ====================================================================
    // Reflection (.type, .typeName)
    // ====================================================================

    context("Reflection for native types") {

        test("Version reflection") {
            run("""
                let v = Version(1, 0, 0)
                say v.typeName
            """.trimIndent()) shouldBe "Version"
        }

        test("Blob reflection") {
            run("""
                let b = Blob("test")
                say b.typeName
            """.trimIndent()) shouldBe "Blob"
        }

        test("NSID reflection") {
            run("""
                let id = NSID("test")
                say id.typeName
            """.trimIndent()) shouldBe "NSID"
        }

        test("Call reflection") {
            run("""
                let c = Call("func")
                say c.typeName
            """.trimIndent()) shouldBe "Call"
        }

        test("Tag reflection") {
            run("""
                let t = Tag("item")
                say t.typeName
            """.trimIndent()) shouldBe "Tag"
        }

        test("Grid reflection") {
            run("""
                let g = Grid(2, 2, 0)
                say g.typeName
            """.trimIndent()) shouldBe "Grid"
        }

        test("Coordinate reflection") {
            run("""
                let c = Coordinate(1, 2)
                say c.typeName
            """.trimIndent()) shouldBe "Coordinate"
        }

        test("Range reflection") {
            run("""
                let r = 1..10
                say r.typeName
            """.trimIndent()) shouldBe "Range"
        }

        test("Quantity reflection") {
            run("""
                let q = Quantity(5, "cm")
                say q.typeName
            """.trimIndent()) shouldBe "Quantity"
        }

        test("Currency reflection") {
            run("""
                let q = Quantity(100, "USD")
                let cu = q.unitObject
                say cu.typeName
            """.trimIndent()) shouldBe "Currency"
        }

        test(".type returns KSType object") {
            run("""
                let v = Version(1, 0, 0)
                say v.type
            """.trimIndent()) shouldBe "Version"
        }

        test("NativeTypeConstructor reflection") {
            run("""
                say Version.type
            """.trimIndent()) shouldBe "class Version"
        }
    }

    // ====================================================================
    // Error Conditions
    // ====================================================================

    context("Native type error conditions") {

        test("member not found on Version") {
            val error = runExpectingError("""
                let v = Version(1, 0, 0)
                say v.nonExistentMember
            """.trimIndent())
            error shouldContain "nonExistentMember"
        }

        test("member not found on Blob") {
            val error = runExpectingError("""
                let b = Blob("test")
                say b.fakeMember
            """.trimIndent())
            error shouldContain "fakeMember"
        }

        test("member not found on NSID") {
            val error = runExpectingError("""
                let id = NSID("test")
                say id.missing
            """.trimIndent())
            error shouldContain "missing"
        }

        test("member not found on Coordinate") {
            val error = runExpectingError("""
                let c = Coordinate(1, 2)
                say c.doesNotExist
            """.trimIndent())
            error shouldContain "doesNotExist"
        }

        test("static not found on Version") {
            val error = runExpectingError("""
                say Version.noSuchStatic
            """.trimIndent())
            error shouldContain "noSuchStatic"
        }

        test("Version constructor with wrong arg count") {
            val error = runExpectingError("""
                Version()
            """.trimIndent())
            error shouldContain "argument"
        }

        test("Blob.parse with non-string argument") {
            val error = runExpectingError("""
                Blob.parse(42)
            """.trimIndent())
            error shouldContain "String"
        }

        test("Range constructor wrong arg count") {
            val error = runExpectingError("""
                Range(1)
            """.trimIndent())
            error shouldContain "expects"
        }

        test("Grid constructor wrong arg count") {
            val error = runExpectingError("""
                Grid(1)
            """.trimIndent())
            error shouldContain "expects"
        }
    }

    // ====================================================================
    // Integration / Cross-cutting
    // ====================================================================

    context("Integration tests") {

        test("store native type in variable and access members") {
            run("""
                var v = Version(1, 0, 0, "beta")
                var next = v.incrementMicro().withQualifier("rc", 1)
                say next.major
                say next.micro
                say next.qualifier
                say next.qualifierNumber
            """.trimIndent()) shouldBe "1\n1\nrc\n1"
        }

        test("native type in collection") {
            run("""
                let versions = [Version(1, 0, 0), Version(2, 0, 0), Version(3, 0, 0)]
                for v in versions {
                    say v.major
                }
            """.trimIndent()) shouldBe "1\n2\n3"
        }

        test("native type as function parameter") {
            run("""
                fun isStable(v): Bool = v.isStable
                say isStable(Version(1, 0, 0))
                say isStable(Version(1, 0, 0, "alpha"))
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("native type as return value") {
            run("""
                fun createVersion(maj: Int, min: Int): Version = Version(maj, min)
                let v = createVersion(5, 3)
                say v.major
                say v.minor
            """.trimIndent()) shouldBe "5\n3"

        }

        test("native type in when expression") {
            run("""
                let v = Version(2, 0, 0)
                let desc = when {
                    v.major >= 3 -> "major"
                    v.major >= 2 -> "stable"
                    else -> "old"
                }
                say desc
            """.trimIndent()) shouldBe "stable"
        }

        test("Grid with Coordinate access") {
            run("""
                let g = Grid(3, 3, 0)
                let c = Coordinate(1, 2)
                g[c.x, c.y] = 42
                say g[c]
                say g[1, 2]
            """.trimIndent()) shouldBe "42\n42"
        }

        test("Range with Version values") {
            run("""
                let r = Range(Version(1, 0, 0), Version(3, 0, 0))
                say r.contains(Version(2, 0, 0))
                say r.contains(Version(4, 0, 0))
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("Blob roundtrip through Base64") {
            run("""
                let original = "Hello, Ki Script!"
                let blob = Blob(original)
                let base64 = blob.toBase64()
                let restored = Blob.parse(base64)
                say restored.decodeToString()
                say blob.size == restored.size
            """.trimIndent()) shouldBe "Hello, Ki Script!\ntrue"
        }

        test("NSID from Call") {
            run("""
                let c = Call("method", "package")
                let id = c.nsid
                say id.name
                say id.namespace
                say id.hasNamespace
            """.trimIndent()) shouldBe "method\npackage\ntrue"
        }

        test("Coordinate navigation chain") {
            run("""
                let start = Coordinate("A", 1)
                let dest = start.right(2).down(3)
                say dest.column
                say dest.row
            """.trimIndent()) shouldBe "C\n4"
        }
    }
})