package io.kixi.ks.interp

import io.kixi.ks.CrateError
import io.kixi.ks.ImportError
import io.kixi.ks.KSRuntime
import io.kixi.ks.NativeFunction
import io.kixi.ks.RuntimeError
import io.kixi.ks.UndefinedNameError
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.CrateDecl
import io.kixi.ks.parser.FunDecl
import io.kixi.ks.parser.ParseException
import io.kixi.ks.parser.Parser
import io.kixi.ks.parser.VarDecl
import io.kixi.ks.parser.Visibility
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Crates: namespaces made of KS files (KS-Language-Manual.md, section 28). Each test
 * builds its crates in a fresh temporary crate root. The contexts follow the
 * numbered rules of the crates design: the crate line, where crates live, `use`,
 * declarations only, one namespace with imports per file, order independence,
 * visibility, crate-info.ks, cratePath and loading.
 */
class CrateTest : FunSpec({

    lateinit var root: Path

    beforeTest { root = Files.createTempDirectory("ks-crates") }
    afterTest { root.toFile().deleteRecursively() }

    /** Writes [text] (trimmed of its common indent) to [path] under the crate root. */
    fun write(path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent() + "\n")
    }

    /** The path [path] as error messages show it (the root is absolute in these tests). */
    fun shown(path: String): String = Path.of(path).toString()

    fun interpreter(output: StringWriter = StringWriter(), roots: List<Path> = listOf(root)) = Interpreter(
        KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = PrintWriter(output, true),
            errorWriter = PrintWriter(StringWriter(), true),
            crateRoots = roots
        )
    )

    /** Runs [script] in a fresh interpreter and returns what it said. */
    fun run(script: String, interp: Interpreter? = null): String {
        val output = StringWriter()
        (interp ?: interpreter(output)).execute(script.trimIndent())
        return output.toString().trim()
    }

    /** Evaluates [script] in a fresh interpreter and returns its value. */
    fun eval(script: String): Any? = interpreter().execute(script.trimIndent())

    /** The lab example of the manual: crate lab.assays with proteinConc. */
    fun writeAssays() {
        write("lab/assays/conc.ks", """
            crate lab.assays

            fun proteinConc(absorbance: Int): Int = absorbance * SLOPE + offset()
        """)
        write("lab/assays/bradford.ks", """
            // Bradford assay
            crate lab.assays

            internal let SLOPE = 10
            private fun offset2() = 0

            class Bradford(let slope: Int) {
                fun conc(absorbance: Int): Int = absorbance * slope
            }

            internal fun offset(): Int = OFFSET + offset2()
            private let OFFSET = 2
        """)
    }

    test("the manual's example runs as documented") {
        write("lab/assays/conc.ks", """
            // lab/assays/conc.ks
            crate lab.assays

            /** Protein concentration (mg/mL) from a Bradford absorbance at 595 nm. */
            fun proteinConc(absorbance: Double): Double = (absorbance - BLANK) * SLOPE

            internal let SLOPE = 2.0
            private let BLANK = 0.25
        """)
        write("lab/assays/crate-info.ks", """
            /** Protein assays: Bradford and BCA, with their standard curves. */
            crate lab.assays
        """)
        run("use lab.assays.proteinConc\nsay proteinConc(0.875)") shouldBe "1.25"
        run("use lab.assays\nsay assays.proteinConc(0.875)") shouldBe "1.25"
        shouldThrow<CrateError> { eval("use lab.assays.SLOPE") }.message shouldContain "SLOPE is internal to crate lab.assays"
        interpreter().crate("lab.assays")!!.description shouldBe "Protein assays: Bradford and BCA, with their standard curves."
    }

    // ========================================================================
    // 1. The crate line
    // ========================================================================

    context("1. the crate line") {

        test("parses as the first declaration, after comments") {
            val program = Parser(Lexer("# note\n// more\n/* block */\ncrate lab.assays\nfun f() = 1").tokenize()).parse()
            val line = program.body.first().shouldBeInstanceOf<CrateDecl>()
            line.path shouldContainExactly listOf("lab", "assays")
            line.name shouldBe "lab.assays"
        }

        test("a file whose crate line follows comments loads") {
            write("lab/a/x.ks", "/** doc */\n// note\ncrate lab.a\nfun one() = 1")
            eval("use lab.a.one\none()") shouldBe 1
        }

        test("crate stays an ordinary identifier") {
            eval("let crate = 5\ncrate + 1") shouldBe 6
            eval("fun crate(n) = n * 2\ncrate(4)") shouldBe 8
            eval("let crate = [1, 2, 3]\ncrate.size") shouldBe 3
        }

        test("a crate line anywhere but first is an error") {
            shouldThrow<ParseException> { Parser(Lexer("fun f() = 1\ncrate lab.assays").tokenize()).parse() }
                .message shouldContain "The crate line must be the first declaration"
            shouldThrow<ParseException> { Parser(Lexer("fun f() {\n crate lab.assays\n}").tokenize()).parse() }
                .message shouldContain "The crate line must be the first declaration"
        }

        test("a second crate line in a crate file is an error naming the file") {
            write("lab/a/x.ks", "crate lab.a\nfun one() = 1\ncrate lab.a")
            shouldThrow<CrateError> { eval("use lab.a.one") }.message.let {
                it shouldContain shown("lab/a/x.ks")
                it shouldContain "The crate line must be the first declaration"
            }
        }

        test("CrateLine reads the same shape as the parser") {
            CrateLine.detect("crate lab.assays")?.name shouldBe "lab.assays"
            CrateLine.detect("\n\n// c\ncrate lab.assays // trailing\n")?.line shouldBe 4
            CrateLine.detect("/* a\n /* nested */\n*/ crate x.y;")?.name shouldBe "x.y"
            CrateLine.detect("crate.size").shouldBeNull()
            CrateLine.detect("crate = 5").shouldBeNull()
            CrateLine.detect("crates lab").shouldBeNull()
            CrateLine.detect("crate add other").shouldBeNull()
            CrateLine.detect("say 1\ncrate lab").shouldBeNull()
            CrateLine.detect("crate if.x").shouldBeNull()
        }
    }

    // ========================================================================
    // 2. Where crates live
    // ========================================================================

    context("2. crate roots and folders") {

        test("the default crate root is the current directory") {
            KSRuntime().crateRoots shouldBe listOf(Path.of(""))
            KSRuntime().withCrateRoots(root).crateRoots shouldBe listOf(root)
        }

        test("crate a.b.c is the files in <root>/a/b/c") {
            writeAssays()
            run("use lab.assays.proteinConc\nsay proteinConc(3)") shouldBe "32"
        }

        test("roots are searched in order") {
            val second = Files.createTempDirectory("ks-crates-2")
            try {
                Files.createDirectories(second.resolve("lab/units"))
                Files.writeString(second.resolve("lab/units/u.ks"), "crate lab.units\nlet where = \"second\"\n")
                Files.createDirectories(root.resolve("lab/units"))
                Files.writeString(root.resolve("lab/units/plain.ks"), "say \"an ordinary script\"\n")
                val output = StringWriter()
                run("use lab.units.where\nsay where", interpreter(output, listOf(root, second)))
                output.toString().trim() shouldBe "second"
            } finally {
                second.toFile().deleteRecursively()
            }
        }

        test("files without a crate line are ordinary scripts and ignored") {
            writeAssays()
            write("lab/assays/try-it.ks", "say \"not part of the crate\"\nfun stray() = 1")
            run("use lab.assays.proteinConc\nsay proteinConc(1)") shouldBe "12"
            shouldThrow<ImportError> { eval("use lab.assays.stray") }
        }

        test("a file whose crate line names another crate is an error") {
            writeAssays()
            write("lab/assays/units.ks", "crate lab.units\nfun mg() = 1")
            val message = shouldThrow<CrateError> { eval("use lab.assays.proteinConc") }.message
            message shouldContain shown("lab/assays/units.ks")
            message shouldContain "says crate lab.units"
            message shouldContain "folder makes it crate lab.assays"
        }

        test("a missing crate fails as an unresolved import did") {
            shouldThrow<ImportError> { eval("use lab.nothing.here") }.message shouldContain "Cannot resolve import: 'lab.nothing.here'"
        }
    }

    // ========================================================================
    // 3. use
    // ========================================================================

    context("3. use") {

        test("one declaration") {
            writeAssays()
            eval("use lab.assays.proteinConc\nproteinConc(2)") shouldBe 22
        }

        test("an alias, usable as a type") {
            writeAssays()
            eval("""
                use lab.assays.Bradford as B
                let b: B = B(3)
                [b.conc(2), b is B, b is Bradford]
            """) shouldBe listOf(6, true, true)
        }

        test("several names") {
            writeAssays()
            eval("use lab.assays.proteinConc, Bradford\nproteinConc(1) + Bradford(2).conc(5)") shouldBe 22
        }

        test("a crate's public names with .*") {
            writeAssays()
            eval("use lab.assays.*\nproteinConc(1) + Bradford(1).conc(1)") shouldBe 13
            shouldThrow<UndefinedNameError> { eval("use lab.assays.*\nSLOPE") }
            shouldThrow<UndefinedNameError> { eval("use lab.assays.*\noffset()") }
        }

        test("a crate and every crate below it with .**") {
            write("lab/top.ks", "crate lab\nfun top() = 1")
            write("lab/assays/a.ks", "crate lab.assays\nfun conc() = 10\ninternal fun hidden() = 0")
            write("lab/units/u.ks", "crate lab.units\nfun mg() = 100")
            write("lab/notes/readme.ks", "say \"no crate here\"")
            eval("use lab.**\ntop() + conc() + mg()") shouldBe 111
            shouldThrow<UndefinedNameError> { eval("use lab.**\nhidden()") }
        }

        test(".** with one name in two crates is an error naming both") {
            write("lab/assays/a.ks", "crate lab.assays\nfun conc() = 10")
            write("lab/units/u.ks", "crate lab.units\nfun conc() = 100")
            val message = shouldThrow<CrateError> { eval("use lab.**") }.message
            message shouldContain "conc is declared by both crate lab.assays and crate lab.units"
        }

        test("a crate as a namespace") {
            writeAssays()
            eval("use lab.assays\nassays.proteinConc(1) + assays.Bradford(2).conc(2)") shouldBe 16
            eval("use lab.assays as lab\nlab.proteinConc(0)") shouldBe 2
            shouldThrow<CrateError> { eval("use lab.assays\nassays.SLOPE") }.message shouldContain "SLOPE is internal to crate lab.assays"
            shouldThrow<RuntimeError> { eval("use lab.assays\nassays.nothing") }.message shouldContain "nothing"
        }

        test("a name the crate lacks is an import error listing its public names") {
            writeAssays()
            val error = shouldThrow<ImportError> { eval("use lab.assays.nothing") }
            error.message shouldContain "lab.assays.nothing"
            error.message shouldContain "it has: Bradford, proteinConc"
        }

        test("what resolved before resolves the same way") {
            writeAssays()
            // A script's own type of the same name still wins, as before crates.
            eval("class Bradford { fun conc(a) = -1 }\nuse lab.assays.Bradford\nBradford().conc(5)") shouldBe -1
            // Type bags are unchanged.
            shouldThrow<ImportError> { eval("use ki.nothing") }
        }
    }

    // ========================================================================
    // 4. Declarations only
    // ========================================================================

    context("4. crate files hold declarations only") {

        test("a statement is an error naming the file and line") {
            write("lab/a/x.ks", "crate lab.a\nfun one() = 1\n\nsay \"hello\"")
            val error = shouldThrow<CrateError> { eval("use lab.a.one") }
            error.message shouldContain shown("lab/a/x.ks")
            error.message shouldContain "a crate file holds declarations only"
            error.message shouldContain "not say"
            error.location?.line shouldBe 4
        }

        test("an expression, an if and a loop are errors too") {
            for ((body, what) in listOf("1 + 2" to "an expression", "if true { }" to "an if", "for [1] { }" to "a for loop")) {
                write("lab/b/x.ks", "crate lab.b\n$body")
                val crates = CrateRegistry(interpreter())
                shouldThrow<CrateError> { crates.find("lab.b", null, null) }.message shouldContain "not $what"
            }
        }

        test("every kind of declaration is allowed") {
            write("lab/all/x.ks", """
                crate lab.all
                use lab.other.base
                fun f() = base()
                infix fun plus(n: Int) = n
                class C(let v: Int)
                struct P(let x: Int)
                trait T { fun t(): Int }
                enum E { A B }
                extend C { fun doubled() = v * 2 }
                let k = 1
                var s = 2
            """)
            write("lab/other/o.ks", "crate lab.other\nfun base() = 40")
            eval("use lab.all.*\nf() + C(1).doubled() + k + s + P(3).x") shouldBe 48
        }
    }

    // ========================================================================
    // 5. One namespace per crate, imports per file
    // ========================================================================

    context("5. one namespace, imports per file") {

        test("every file sees every declaration of its crate") {
            write("lab/a/one.ks", "crate lab.a\nfun one() = two() - 1")
            write("lab/a/two.ks", "crate lab.a\nfun two() = 2")
            eval("use lab.a.one\none()") shouldBe 1
        }

        test("a use is visible only in its own file and never exported") {
            write("lab/units/u.ks", "crate lab.units\nfun mg(x) = x * 1000")
            write("lab/a/one.ks", "crate lab.a\nuse lab.units.mg\nfun inMg(x) = mg(x)")
            write("lab/a/two.ks", "crate lab.a\nfun alsoMg(x) = mg(x)")
            eval("use lab.a.inMg\ninMg(2)") shouldBe 2000
            shouldThrow<UndefinedNameError> { eval("use lab.a.alsoMg\nalsoMg(2)") }
            shouldThrow<ImportError> { eval("use lab.a.mg") }
        }

        test("crate code sees host bindings but not the script's declarations") {
            write("lab/a/x.ks", "crate lab.a\nfun fromHost() = db + 1\nfun fromScript() = secret")
            val interp = interpreter()
            interp.define("db", 41)
            interp.execute("use lab.a.fromHost\nfromHost()") shouldBe 42
            shouldThrow<UndefinedNameError> { interp.execute("let secret = 1\nuse lab.a.fromScript\nfromScript()") }
        }

        test("crate types stay out of scripts and do not clash across crates") {
            write("lab/a/x.ks", "crate lab.a\nclass Sample { fun kind() = \"a\" }\nfun make() = Sample()")
            write("lab/b/x.ks", "crate lab.b\nclass Sample { fun kind() = \"b\" }\nfun make() = Sample()")
            eval("""
                use lab.a.make as makeA
                use lab.b.make as makeB
                [makeA().kind(), makeB().kind()]
            """) shouldBe listOf("a", "b")
            shouldThrow<UndefinedNameError> { eval("use lab.a.make\nmake()\nSample()") }
        }

        test("a crate's defaults and property initializers see the crate") {
            write("lab/a/x.ks", """
                crate lab.a
                internal let FACTOR = 3
                fun scale(x, by = FACTOR) = x * by
                class Plate(let wells: Int) { let total = wells * FACTOR }
                struct Well(let row: Int) { let scaled = row * FACTOR }
            """)
            eval("use lab.a.*\n[scale(2), Plate(4).total, Well(5).scaled]") shouldBe listOf(6, 12, 15)
        }

        test("types cross crates and scripts through use") {
            write("lab/base/b.ks", "crate lab.base\nclass Base { fun label() = \"base\" }")
            write("lab/derived/d.ks", "crate lab.derived\nuse lab.base.Base\nclass Derived: Base { fun kind() = label() + \"+derived\" }")
            eval("""
                use lab.derived.Derived
                use lab.base.Base
                class Mine: Base
                extend Base { fun shout() = label() + "!" }
                [Derived().kind(), Derived() is Base, Mine().label(), Mine() is Base, Derived().shout()]
            """) shouldBe listOf("base+derived", true, "base", true, "base!")
        }

        test("a crate var is crate state, read live through use") {
            write("lab/a/x.ks", "crate lab.a\nvar count = 0\nfun bump() { count += 1 }")
            run("use lab.a.count, bump\nbump()\nbump()\nsay count") shouldBe "2"
        }

        test("crate enums work with dot constants") {
            write("lab/colors/c.ks", "crate lab.colors\nenum Color { RED GREEN }\nfun isRed(c: Color) = c == .RED")
            eval("use lab.colors.*\nlet c: Color = .GREEN\n[isRed(.RED), isRed(c), c is Color]") shouldBe listOf(true, false, true)
        }

        test("errors in crate code carry the file in their location") {
            write("lab/a/x.ks", "crate lab.a\nfun boom() = nothing()")
            val error = shouldThrow<RuntimeError> { eval("use lab.a.boom\nboom()") }
            error.location?.file shouldBe root.resolve("lab/a/x.ks").toString()
        }
    }

    // ========================================================================
    // 6. Order independence
    // ========================================================================

    context("6. order independence") {

        test("a let may use a let of a later file") {
            write("lab/a/a.ks", "crate lab.a\nlet total = BASE * 2")
            write("lab/a/b.ks", "crate lab.a\nlet BASE = 21")
            eval("use lab.a.total\ntotal") shouldBe 42
        }

        test("an initializer may call a function that reads a let of a third file") {
            write("lab/a/a.ks", "crate lab.a\nlet answer = compute()")
            write("lab/a/b.ks", "crate lab.a\nfun compute() = SEED + 1")
            write("lab/a/c.ks", "crate lab.a\nlet SEED = 41")
            eval("use lab.a.answer\nanswer") shouldBe 42
        }

        test("a class may extend and implement types of later files") {
            write("lab/shapes/a.ks", "crate lab.shapes\nclass Square(let s: Int): Base, Shape { fun area() = s * s }")
            write("lab/shapes/m.ks", "crate lab.shapes\nfun areaOf(s: Shape) = s.area()")
            write("lab/shapes/y.ks", "crate lab.shapes\nclass Base { fun label() = \"base\" }")
            write("lab/shapes/z.ks", "crate lab.shapes\ntrait Shape { fun area(): Int }")
            eval("""
                use lab.shapes.*
                let sq = Square(3)
                [areaOf(sq), sq is Shape, sq is Base, sq.label()]
            """) shouldBe listOf(9, true, true, "base")
        }

        test("files load in name order whatever order they are written in") {
            write("lab/a/z.ks", "crate lab.a\nvar log = []\nlet z = note(\"z\")")
            write("lab/a/a.ks", "crate lab.a\nfun note(s) { log += s\n return s }\nlet a = note(\"a\")")
            interpreter().crate("lab.a")!!.files.map { it.fileName.toString() } shouldBe listOf("a.ks", "z.ks")
            eval("use lab.a.log\nlog") shouldBe listOf("a", "z")
        }

        test("an initialization cycle is an error naming the names") {
            write("lab/cyc/a.ks", "crate lab.cyc\nlet a = b + 1")
            write("lab/cyc/b.ks", "crate lab.cyc\nlet b = a + 1")
            shouldThrow<CrateError> { eval("use lab.cyc.a") }.message shouldContain "Initialization cycle in crate lab.cyc: a -> b -> a"
        }

        test("a cycle of supertypes is an error naming the names") {
            write("lab/cyc/a.ks", "crate lab.cyc\nclass A: B")
            write("lab/cyc/b.ks", "crate lab.cyc\nclass B: A")
            shouldThrow<CrateError> { eval("use lab.cyc.A") }.message shouldContain "Initialization cycle in crate lab.cyc: A -> B -> A"
        }
    }

    // ========================================================================
    // 7. Visibility
    // ========================================================================

    context("7. visibility") {

        test("the modifiers parse onto top-level declarations") {
            val program = Parser(Lexer("private fun f() = 1\ninternal let x = 2\nfun g() = 3").tokenize()).parse()
            (program.body[0] as FunDecl).visibility shouldBe Visibility.PRIVATE
            (program.body[1] as VarDecl).visibility shouldBe Visibility.INTERNAL
            (program.body[2] as FunDecl).visibility shouldBe Visibility.PUBLIC
        }

        test("internal and private names are visible inside the crate") {
            writeAssays()
            eval("use lab.assays.proteinConc\nproteinConc(0)") shouldBe 2
        }

        test("use of an internal name from outside the crate is an error") {
            writeAssays()
            shouldThrow<CrateError> { eval("use lab.assays.SLOPE") }.message shouldContain "SLOPE is internal to crate lab.assays"
        }

        test("use of a private name from outside is an error naming its file") {
            writeAssays()
            shouldThrow<CrateError> { eval("use lab.assays.offset2") }.message shouldContain
                    "offset2 is private to file ${root.resolve("lab/assays/bradford.ks")}"
        }

        test("a private name used from another file of the crate is an error") {
            write("lab/a/one.ks", "crate lab.a\nprivate fun helper() = 1")
            write("lab/a/two.ks", "crate lab.a\nfun useIt() = helper()")
            shouldThrow<CrateError> { eval("use lab.a.useIt\nuseIt()") }.message shouldContain
                    "helper is private to file ${root.resolve("lab/a/one.ks")}"
            write("lab/b/one.ks", "crate lab.b\nprivate fun helper() = 1")
            write("lab/b/two.ks", "crate lab.b\nuse lab.b.helper")
            shouldThrow<CrateError> { eval("use lab.b.*") }.message shouldContain "helper is private to file"
        }

        test("files may each have a private declaration of one name") {
            write("lab/a/one.ks", "crate lab.a\nprivate let tag = \"one\"\nfun first() = tag")
            write("lab/a/two.ks", "crate lab.a\nprivate let tag = \"two\"\nfun second() = tag")
            eval("use lab.a.*\nfirst() + second()") shouldBe "onetwo"
        }

        test("a name declared twice in a crate is an error") {
            write("lab/a/one.ks", "crate lab.a\nfun dup() = 1")
            write("lab/a/two.ks", "crate lab.a\nprivate fun dup() = 2")
            shouldThrow<CrateError> { eval("use lab.a.*") }.message shouldContain "dup is declared twice in crate lab.a"
        }

        test("in an ordinary script the modifiers have no effect") {
            eval("private fun f() = 1\ninternal let x = 2\nf() + x") shouldBe 3
        }

        test("private and internal stay ordinary identifiers") {
            eval("let private = 1\nlet internal = 2\nprivate + internal") shouldBe 3
        }

        test("the modifiers apply to top-level declarations only") {
            shouldThrow<ParseException> { Parser(Lexer("class C {\n private fun f() = 1\n}").tokenize()).parse() }
                .message shouldContain "top-level declarations only"
            shouldThrow<ParseException> { Parser(Lexer("private use a.b").tokenize()).parse() }
        }
    }

    // ========================================================================
    // 8. crate-info.ks
    // ========================================================================

    context("8. crate-info.ks") {

        test("its doc comment is the crate's description") {
            writeAssays()
            write("lab/assays/crate-info.ks", """
                /**
                 * Protein assays for the plate reader.
                 *
                 * Bradford and BCA.
                 */
                crate lab.assays
            """)
            val crate = interpreter().crate("lab.assays").shouldNotBeNull()
            crate.description shouldBe "Protein assays for the plate reader.\n\nBradford and BCA."
            crate.files.map { it.fileName.toString() } shouldBe listOf("bradford.ks", "conc.ks")
            crate.publicNames shouldBe listOf("Bradford", "proteinConc")
        }

        test("a folder with only crate-info.ks is an empty crate") {
            write("lab/empty/crate-info.ks", "crate lab.empty\n/** Nothing yet. */")
            val crate = interpreter().crate("lab.empty").shouldNotBeNull()
            crate.description shouldBe "Nothing yet."
            crate.publicNames.shouldBeEmpty()
            eval("use lab.empty.*\n1") shouldBe 1
        }

        test("anything else in crate-info.ks is an error") {
            write("lab/a/crate-info.ks", "/** A. */\ncrate lab.a\nfun f() = 1")
            shouldThrow<CrateError> { eval("use lab.a.*") }.message shouldContain
                    "crate-info.ks holds only the crate line and a doc comment"
        }

        test("crate-info.ks without a crate line is an error") {
            write("lab/a/crate-info.ks", "/** A. */")
            shouldThrow<CrateError> { eval("use lab.a.*") }.message shouldContain "must start with the crate line"
        }
    }

    // ========================================================================
    // 9. cratePath
    // ========================================================================

    context("9. cratePath") {

        test("gives the path of a file shipped beside the crate") {
            write("lab/assays/bsa-standards.csv", "ug,abs\n0,0.0\n")
            write("lab/assays/std.ks", "crate lab.assays\nfun standards() = IO.read(cratePath(\"bsa-standards.csv\"))")
            eval("use lab.assays.standards\nstandards()") shouldBe "ug,abs\n0,0.0\n"
            eval("use lab.assays\nassays.standards().size") shouldBe 13
        }

        test("is visible only in crate files") {
            shouldThrow<UndefinedNameError> { eval("cratePath(\"x\")") }
        }

        test("returns an absolute path String") {
            write("lab/a/x.ks", "crate lab.a\nfun where() = cratePath(\"data.kd\")")
            eval("use lab.a.where\nwhere()") shouldBe root.resolve("lab/a/data.kd").toAbsolutePath().normalize().toString()
        }
    }

    // ========================================================================
    // 10. Loading
    // ========================================================================

    context("10. loading") {

        test("a crate loads once per interpreter") {
            write("lab/once/x.ks", "crate lab.once\nfun note() {\n say \"loading\"\n return 1\n}\nlet mark = note()")
            run("use lab.once.mark\nuse lab.once.*\nuse lab.once\nsay mark") shouldBe "loading\n1"
        }

        test("a fresh interpreter reads the files again") {
            write("lab/a/x.ks", "crate lab.a\nlet v = 1")
            eval("use lab.a.v\nv") shouldBe 1
            write("lab/a/x.ks", "crate lab.a\nlet v = 2")
            eval("use lab.a.v\nv") shouldBe 2
        }

        test("crates that use each other are an error naming the loop") {
            write("lab/assays/a.ks", "crate lab.assays\nuse lab.units.mg\nfun conc() = mg()")
            write("lab/units/u.ks", "crate lab.units\nuse lab.assays.conc\nfun mg() = 1")
            shouldThrow<CrateError> { eval("use lab.assays.conc") }.message shouldContain
                    "Crates use each other: lab.assays -> lab.units -> lab.assays"
        }

        test("a crate's files may use their own crate") {
            write("lab/a/one.ks", "crate lab.a\nuse lab.a.two\nfun one() = two() - 1")
            write("lab/a/two.ks", "crate lab.a\nfun two() = 2")
            eval("use lab.a.one\none()") shouldBe 1
        }

        test("running a crate file as a script is an error") {
            shouldThrow<CrateError> { eval("crate lab.assays\nfun f() = 1") }.message.let {
                it shouldContain "belongs to crate lab.assays"
                it shouldContain "holds declarations"
            }
        }

        test("hosts read loaded crates") {
            writeAssays()
            val interp = interpreter()
            interp.crate("lab.nothing").shouldBeNull()
            interp.execute("use lab.assays.proteinConc")
            interp.loadedCrates.map { it.name } shouldBe listOf("lab.assays")
            interp.crate("lab.assays")!!.visibilityOf("SLOPE") shouldBe Visibility.INTERNAL
            interp.crate("lab.assays")!!.visibilityOf("offset2") shouldBe Visibility.PRIVATE
        }

        test("a host function bound with define reaches crate code") {
            write("lab/a/x.ks", "crate lab.a\nfun twice(x) = double(x) * 2")
            val interp = interpreter()
            interp.define("double", NativeFunction("double", 1) { (it[0] as Int) * 2 })
            interp.execute("use lab.a.twice\ntwice(5)") shouldBe 20
        }
    }
    context("11. types of one name in two crates") {
        test("stay apart in is checks and typed lets") {
            write("lab/a/plates.ks", "crate lab.a\n\nclass Plate(let wells: Int)\n\nfun makePlate() = Plate(96)")
            write("lab/b/plates.ks", "crate lab.b\n\nclass Plate(let wells: Int)")
            run("""
                use lab.a.Plate as APlate
                use lab.b.Plate as BPlate
                let p = APlate(96)
                say p is APlate
                say p is BPlate
            """) shouldBe "true\nfalse"
            // Plate here is lab.b's; a plate lab.a made is not one, though both are called Plate.
            run("use lab.a.makePlate\nuse lab.b.Plate\nsay makePlate() is Plate\nsay Plate(24) is Plate") shouldBe "false\ntrue"
            shouldThrow<RuntimeError> {
                run("use lab.a.makePlate\nuse lab.b.Plate\nlet q: Plate = makePlate()")
            }.message shouldContain "Cannot assign"
        }

        test("a user type named like a built-in one is checked as itself") {
            // Before, `is Tag` answered as KD's Tag for a user class called Tag.
            run("class Tag(let n: Int)\nlet t = Tag(1)\nsay t is Tag\nlet u: Tag = t\nsay u.n") shouldBe "true\n1"
        }
    }
})
