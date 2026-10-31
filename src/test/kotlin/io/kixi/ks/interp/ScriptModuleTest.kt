package io.kixi.ks.interp

import io.kixi.KiException
import io.kixi.ks.CrateError
import io.kixi.ks.ImportError
import io.kixi.ks.KSRuntime
import io.kixi.ks.RuntimeError
import io.kixi.ks.UndefinedNameError
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.lexer.TokenType
import io.kixi.ks.parser.ParseException
import io.kixi.ks.parser.Parser
import io.kixi.ks.parser.UseDecl
import io.kixi.ks.parser.UseWildcard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Relative `use` paths, script modules and `isMain` (KS-Language-Manual.md,
 * section 28): a `use` path that starts with `./` or `../` counts from the folder of
 * the file it is in, a `.ks` file without a crate line is a module that runs once
 * on first use, and `isMain` says whether code is in the script being run.
 *
 * Each test builds a lab project in a fresh temporary crate root:
 *
 *     projects/elisa/run.ks          the script being run (its scriptPath)
 *     projects/elisa/helpers.ks      a script module with an isMain self-check
 *     projects/elisa/plates/         crate projects.elisa.plates
 *     projects/shared/               crate projects.shared
 *     lab/units/                     crate lab.units
 */
class ScriptModuleTest : FunSpec({

    lateinit var root: Path

    beforeTest { root = Files.createTempDirectory("ks-modules") }
    afterTest { root.toFile().deleteRecursively() }

    /** Writes [text] (trimmed of its common indent) to [path] under the crate root. */
    fun write(path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent() + "\n")
    }

    /** An interpreter whose script being run is [script] (below the root), or none. */
    fun interpreter(
        script: String? = null,
        output: StringWriter = StringWriter(),
        roots: List<Path> = listOf(root),
        scriptPath: Path? = script?.let { root.resolve(it) },
        hostLang: Boolean = false
    ) = Interpreter(
        KSRuntime(
            hostLang = hostLang,
            colorOutput = false,
            outputWriter = PrintWriter(output, true),
            errorWriter = PrintWriter(StringWriter(), true),
            crateRoots = roots,
            scriptPath = scriptPath
        )
    )

    /** Runs [code] as the script [at] (below the root) and returns what it said. */
    fun run(code: String, at: String? = "projects/elisa/run.ks"): String {
        val output = StringWriter()
        interpreter(at, output).execute(code.trimIndent())
        return output.toString().trim()
    }

    /** Evaluates [code] as the script [at] and returns its value. */
    fun eval(code: String, at: String? = "projects/elisa/run.ks"): Any? = interpreter(at).execute(code.trimIndent())

    fun parseUse(code: String): UseDecl = Parser(Lexer(code).tokenize()).parse().body.first().shouldBeInstanceOf<UseDecl>()

    fun parseError(code: String): String = shouldThrow<KiException> { Parser(Lexer(code).tokenize()).parse() }.message ?: ""

    /** The lab project of the class notes. */
    fun writeProject() {
        write("projects/elisa/helpers.ks", """
            // Dilution helpers for the ELISA run
            say "loading helpers"

            fun dilute(stock: Double, factor: Int): Double = stock / factor
            fun whereAmI() = isMain
            private fun check() = dilute(10.0, 2) == 5.0
            internal let LIMIT = 100
            var calls = 0
            fun count() { calls += 1 }

            if isMain {
                say "self-check: ${'$'}{check()}"
            }
        """)
        write("projects/elisa/plates/layout.ks", """
            crate projects.elisa.plates

            class PlateMap(let rows: Int, let cols: Int) {
                fun wells() = rows * cols
            }
            fun wellCount() = 96
        """)
        write("projects/shared/plate.ks", """
            crate projects.shared

            class Plate(let id: String)
            fun readPlate(id: String) = Plate(id)
        """)
        write("lab/units/u.ks", "crate lab.units\nlet ML = \"mL\"")
    }

    test("the manual's example runs as documented") {
        writeProject()
        write("projects/elisa/helpers.ks", """
            // projects/elisa/helpers.ks
            fun dilute(stock: Double, factor: Int): Double = stock / factor

            if isMain {
                say "quick check: ${'$'}{dilute(10.0, 4)}"     // only when helpers.ks itself is run
            }
        """)
        val runKs = """
            // projects/elisa/run.ks
            use ./plates/PlateMap               // the crate in plates/, beside this file
            use ../shared/Plate, readPlate      // the crate in shared/, one folder up
            use ./helpers/dilute                // a name from helpers.ks, beside this file
            use lab.units.ML                    // a full name, found from the scripts folder

            let map = PlateMap(8, 12)
            say dilute(2.0, 4)                  // 0.5
        """
        run(runKs) shouldBe "0.5"
        run(Files.readString(root.resolve("projects/elisa/helpers.ks")), at = "projects/elisa/helpers.ks") shouldBe "quick check: 2.5"
    }

    // ========================================================================
    // 1. Relative use with slash paths
    // ========================================================================

    context("1. relative paths") {

        test("parse into a UseDecl that counts the parents") {
            parseUse("use ./plates/PlateMap").let {
                it.parents shouldBe 0
                it.packagePath shouldBe listOf("plates")
                it.imports.map { i -> i.name } shouldBe listOf("PlateMap")
            }
            parseUse("use ../../shared/Plate as P").let {
                it.parents shouldBe 2
                it.imports.single().alias shouldBe "P"
                it.pathText shouldBe "../../shared/Plate"
            }
            parseUse("use ../shared/Plate, readPlate").imports.map { it.name } shouldBe listOf("Plate", "readPlate")
            parseUse("use ./plates/*").wildcard shouldBe UseWildcard.FLAT
            parseUse("use ../**").let {
                it.wildcard shouldBe UseWildcard.TREE
                it.packagePath shouldBe emptyList()
                it.parents shouldBe 1
            }
            parseUse("use lab.units.ML").parents shouldBe null
        }

        test("the path is one token, so a star does not open a comment") {
            val tokens = Lexer("use ./plates/*\nsay 1").tokenize()
            tokens[1].type shouldBe TokenType.USE_PATH
            tokens[1].value shouldBe "./plates/*"
            Parser(tokens).parse().body.size shouldBe 2
            Lexer("use ../shared/Plate, readPlate // note").tokenize()[1].value shouldBe "../shared/Plate"
        }

        test("mixing / and . is a parse error with a clear message") {
            parseError("use ../shared.Plate") shouldContain "mixes / and .: a relative path separates its parts with / (use ../shared/Plate)"
            parseError("use lab/assays") shouldContain "mixes . and /"
        }

        test("other malformed paths are parse errors") {
            parseError("use ..x") shouldContain "A relative use starts with ./ or ../"
            parseError("use ./a/../b") shouldContain "./ and ../ may only start a path"
            parseError("use ./my-helpers") shouldContain "'my-helpers' is not a name"
            parseError("use ./") shouldContain "names no module"
            parseError("use ./a/") shouldContain "empty part"
            // `//` starts a comment, here as anywhere
            parseUse("use ./a//b").pathText shouldBe "./a"
        }

        test("this file's folder, the parent and two parents") {
            writeProject()
            eval("""
                use ./plates/PlateMap
                use ../shared/Plate, readPlate
                use ../../lab/units/ML
                [PlateMap(8, 12).wells(), readPlate("P1").id, Plate("P2") is Plate, ML]
            """) shouldBe listOf(96, "P1", true, "mL")
        }

        test("an alias, a star and two stars") {
            writeProject()
            eval("use ../shared/Plate as P\nP(\"x\").id") shouldBe "x"
            eval("use ./plates/*\nwellCount()") shouldBe 96
            eval("use ../**\n[wellCount(), readPlate(\"y\").id]") shouldBe listOf(96, "y")
        }

        test("a module and a folder as namespaces") {
            writeProject()
            write("projects/elisa/tools/stats.ks", "fun mean(xs: List) = xs.reduce { a, b -> a + b } / xs.size")
            run("use ./helpers\nsay helpers.dilute(9.0, 2)") shouldBe "loading helpers\n4.5"
            eval("use ./tools\ntools.stats.mean([2, 4, 6])") shouldBe 4
            eval("use ./plates\nplates.wellCount()") shouldBe 96
        }

        test("going above the root is an error") {
            writeProject()
            val message = shouldThrow<CrateError> { eval("use ../../../x/y") }.message
            message shouldContain "use ../../../x/y: ../ goes above the scripts folder"
            message shouldContain root.toAbsolutePath().normalize().toString()
        }

        test("a script with no path counts from the first crate root") {
            writeProject()
            write("top.ks", "fun here() = \"top\"")
            eval("use ./top/here\nhere()", at = null) shouldBe "top"
            shouldThrow<CrateError> { eval("use ../x/y", at = null) }.message shouldContain "goes above the scripts folder"
        }

        test("a script outside every root counts from its own folder") {
            val elsewhere = Files.createTempDirectory("ks-elsewhere")
            try {
                Files.writeString(elsewhere.resolve("near.ks"), "fun near() = \"near\"\n")
                val interp = interpreter(scriptPath = elsewhere.resolve("run.ks"))
                interp.execute("use ./near/near\nnear()") shouldBe "near"
                shouldThrow<CrateError> { interp.execute("use ../x/y") }.message shouldContain "goes above the scripts folder $elsewhere"
            } finally {
                elsewhere.toFile().deleteRecursively()
            }
        }

        test("what a path finds nothing for is an import error") {
            writeProject()
            shouldThrow<ImportError> { eval("use ./nothing/here") }.message shouldContain "There is no crate or folder"
            shouldThrow<ImportError> { eval("use ./helpers/nothing") }.message shouldContain "Module projects.elisa.helpers has no public name 'nothing'"
            write("projects/elisa/data/readme.ks", "fun r() = 1")
            Files.createDirectories(root.resolve("projects/elisa/empty"))
            shouldThrow<ImportError> { eval("use ./empty/*") }.message shouldContain "is a plain folder, which has no names of its own"
        }

        test("additive: ranges, division and dotted names are unchanged") {
            writeProject()
            eval("let r = 1..5\nr.toList().size") shouldBe 5
            eval("let a = 10\nlet b = 2\na / b") shouldBe 5
            eval("use lab.units.ML\nML") shouldBe "mL"
            eval("use projects.shared.readPlate\nreadPlate(\"z\").id") shouldBe "z"
            shouldThrow<ImportError> { eval("use ki.nothing") }
            interpreter("projects/elisa/run.ks", hostLang = true).execute("use java.util.ArrayList\nArrayList().size()") shouldBe 0
        }
    }

    // ========================================================================
    // 2. Every file counts from its own folder
    // ========================================================================

    context("2. own folders") {

        test("a crate file counts from its crate's folder") {
            write("lab/units/u.ks", "crate lab.units\nfun mg(x) = x * 1000")
            write("lab/assays/a.ks", "crate lab.assays\nuse ../units/mg\nfun inMg(x) = mg(x)")
            eval("use lab.assays.inMg\ninMg(2)", at = null) shouldBe 2000
        }

        test("a module counts from its own folder") {
            write("tools/stats.ks", "use ./math/square\nfun meanSquare(a, b) = (square(a) + square(b)) / 2")
            write("tools/math.ks", "fun square(x) = x * x")
            eval("use ./tools/stats/meanSquare\nmeanSquare(2, 4)", at = "run.ks") shouldBe 10
        }

        test("dotted names resolve from the crate roots only") {
            write("helpers.ks", "let where = \"root\"")
            write("projects/elisa/helpers.ks", "let where = \"elisa\"")
            eval("use helpers.where\nwhere") shouldBe "root"
            eval("use ./helpers/where\nwhere") shouldBe "elisa"
        }
    }

    // ========================================================================
    // 3. Plain scripts are modules
    // ========================================================================

    context("3. script modules") {

        test("a module runs once, however it is reached") {
            writeProject()
            run("""
                use ./helpers/dilute
                use ./helpers
                use projects.elisa.helpers.dilute as d2
            """) shouldBe "loading helpers"
        }

        test("its public names are its top-level declarations, read live") {
            writeProject()
            run("""
                use ./helpers/dilute, count, calls
                count()
                count()
                say dilute(7.0, 2) calls
            """) shouldBe "loading helpers\n3.5 2"
        }

        test("private and internal names are not exported") {
            writeProject()
            shouldThrow<CrateError> { eval("use ./helpers/check") }.message shouldContain
                    "check is private in module projects.elisa.helpers, so it is not exported"
            shouldThrow<CrateError> { eval("use ./helpers/LIMIT") }.message shouldContain "LIMIT is internal in module"
            shouldThrow<UndefinedNameError> { eval("use ./helpers/*\nLIMIT") }
            shouldThrow<CrateError> { eval("use ./helpers\nhelpers.check()") }.message shouldContain "check is private"
        }

        test("a module sees the host's bindings and its own imports, never the script's names") {
            write("m.ks", "use lab.units.ML\nfun unit() = ML\nfun fromHost() = db + 1\nfun fromScript() = secret")
            write("lab/units/u.ks", "crate lab.units\nlet ML = \"mL\"")
            val interp = interpreter("run.ks")
            interp.define("db", 41)
            interp.execute("use ./m/unit, fromHost\n[unit(), fromHost()]") shouldBe listOf("mL", 42)
            shouldThrow<UndefinedNameError> { interp.execute("ML") }
            shouldThrow<UndefinedNameError> { interp.execute("let secret = 1\nuse ./m/fromScript\nfromScript()") }
        }

        test("modules are reached by dotted names from a root") {
            write("helpers.ks", "fun dilute(x, f) = x / f")
            write("tools/stats.ks", "fun mean(xs: List) = xs.reduce { a, b -> a + b } / xs.size")
            eval("use helpers.dilute\nuse tools.stats.mean\n[dilute(9, 3), mean([1, 2, 3])]", at = null) shouldBe listOf(3, 2)
            eval("use helpers\nhelpers.dilute(8, 2)", at = null) shouldBe 4
            eval("use tools.stats.*\nmean([4, 6])", at = null) shouldBe 5
        }

        test("a crate folder may hold modules") {
            write("lab/assays/conc.ks", "crate lab.assays\nfun conc() = 1")
            write("lab/assays/notes.ks", "fun summary() = \"notes\"")
            eval("use lab.assays.notes\nnotes.summary()", at = null) shouldBe "notes"
            eval("use lab.assays.notes.summary\nsummary()", at = null) shouldBe "notes"
            eval("use lab.assays\nassays.notes.summary()", at = null) shouldBe "notes"
        }

        test("a crate's name and a module beside it may not clash") {
            write("lab/assays/conc.ks", "crate lab.assays\nfun notes() = 1")
            write("lab/assays/notes.ks", "fun summary() = \"notes\"")
            shouldThrow<CrateError> { eval("use lab.assays.notes", at = null) }.message shouldContain
                    "'notes' is both a name in crate lab.assays and the module lab.assays.notes; rename one"
        }

        test("a crate's file is not a module") {
            writeProject()
            shouldThrow<CrateError> { eval("use ./plates/layout") }.message shouldContain
                    "is a file of crate projects.elisa.plates, not a module of its own"
        }

        test("what resolves today resolves first") {
            write("java/util.ks", "fun ArrayList() = \"module\"")
            interpreter("run.ks", hostLang = true).execute("use java.util.ArrayList\nArrayList().size()") shouldBe 0
            write("lab/assays/conc.ks", "crate lab.assays\nfun conc() = \"crate\"")
            write("lab/assays.ks", "fun conc() = \"module\"")
            eval("use lab.assays.conc\nconc()", at = null) shouldBe "crate"
        }

        test("modules that use each other are an error naming the loop") {
            write("a.ks", "use ./b/fb\nfun fa() = 1")
            write("b.ks", "use ./a/fa\nfun fb() = 2")
            shouldThrow<CrateError> { eval("use ./a/fa", at = "run.ks") }.message shouldContain
                    "Modules use each other in a loop: a -> b -> a"
            write("m.ks", "use lab.x.fx\nfun fm() = 1")
            write("lab/x/x.ks", "crate lab.x\nuse m.fm\nfun fx() = 2")
            shouldThrow<CrateError> { eval("use ./m/fm", at = "run.ks") }.message shouldContain
                    "Modules use each other in a loop: m -> lab.x -> m"
        }

        test("module types and enums stay the module's") {
            write("phases.ks", "enum Phase { PREP READ }\nclass Step(let phase: Phase)\nfun isRead(p: Phase) = p == .READ")
            eval("use ./phases/*\n[isRead(.READ), Step(.PREP).phase is Phase]", at = "run.ks") shouldBe listOf(true, true)
            shouldThrow<UndefinedNameError> { eval("use ./phases/isRead\nPhase", at = "run.ks") }
        }

        test("a module that fails to run is not cached") {
            write("bad.ks", "fun f() = 1\nlet x = nothing")
            val interp = interpreter("run.ks")
            shouldThrow<UndefinedNameError> { interp.execute("use ./bad/f") }
            shouldThrow<UndefinedNameError> { interp.execute("use ./bad/f") }
        }
    }

    // ========================================================================
    // 4. isMain
    // ========================================================================

    context("4. isMain") {

        test("true in the script being run and at the top level") {
            eval("isMain") shouldBe true
            Interpreter().execute("isMain") shouldBe true
            eval("fun f() = isMain\nf()") shouldBe true
            // A class's own property of that name is its own
            eval("class Run(let isMain: Bool) { fun main() = isMain }\nRun(false).main()") shouldBe false
        }

        test("false in a module's code, even when the script calls it") {
            writeProject()
            run("use ./helpers/whereAmI\nsay whereAmI() isMain") shouldBe "loading helpers\nfalse true"
        }

        test("a module's self-check runs only when the file itself is run") {
            writeProject()
            run("use ./helpers/dilute") shouldBe "loading helpers"
            val text = Files.readString(root.resolve("projects/elisa/helpers.ks"))
            run(text, at = "projects/elisa/helpers.ks") shouldBe "loading helpers\nself-check: true"
        }

        test("false in crate files") {
            write("lab/a/x.ks", "crate lab.a\nfun main() = isMain")
            eval("use lab.a.main\nmain()", at = null) shouldBe false
        }

        test("cannot be assigned; a name of the script's own hides it, as any built-in (additive)") {
            shouldThrow<RuntimeError> { eval("isMain = false") }.message shouldContain "isMain is built in and cannot be assigned"
            shouldThrow<RuntimeError> { eval("isMain += 1") }.message shouldContain "cannot be assigned"
            shouldThrow<RuntimeError> { eval("isMain++") }.message shouldContain "cannot be assigned"
            // Scripts that already used the name keep working.
            eval("let isMain = 1\nisMain") shouldBe 1
            eval("var isMain = 1\nisMain = 5\nisMain++\nisMain") shouldBe 6
            eval("fun f(isMain) = isMain\nf(7)") shouldBe 7
            eval("fun isMain() = 3\nisMain()") shouldBe 3
            interpreter().apply { define("isMain", false) }.execute("isMain") shouldBe false
        }
    }
})
