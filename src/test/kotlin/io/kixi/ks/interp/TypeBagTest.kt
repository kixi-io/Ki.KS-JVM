package io.kixi.ks.interp

import io.kixi.ks.KSRuntime
import io.kixi.ks.RuntimeError
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.lexer.TokenType
import io.kixi.ks.parser.Parser
import io.kixi.ks.tb.Seq
import io.kixi.ks.tb.withSampleBags
import io.kixi.types.ExtLiteral
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.PrintWriter
import java.io.StringWriter

/** Type bags in KS (Ki type bags design 3.2): literals, members, types and `use ki.<bag>`. */
class TypeBagTest : FunSpec({

    fun interpreter(out: StringWriter, hostLang: Boolean) = Interpreter(KSRuntime(
        hostLang = hostLang, colorOutput = false,
        outputWriter = PrintWriter(out, true), errorWriter = PrintWriter(StringWriter(), true),
    ))

    fun run(source: String, hostLang: Boolean = false, bind: Map<String, Any?> = emptyMap()): String {
        val out = StringWriter()
        val i = interpreter(out, hostLang)
        for ((k, v) in bind) i.define(k, v)
        i.executeProgram(Parser(Lexer(source).tokenize()).parse())
        return out.toString().trim().replace("\r\n", "\n")
    }

    fun error(source: String, hostLang: Boolean = false, bind: Map<String, Any?> = emptyMap()): String =
        shouldThrow<RuntimeError> { run(source, hostLang, bind) }.let { it.message + " " + (it.suggestion ?: "") }

    context("literals") {

        test("a bag literal reads through the bag where an operand can start") {
            withSampleBags {
                run("say .seq(\"ACGT\").length") shouldBe "4"
                run("let s = .sq(\"GGAT\")\nsay s.gc") shouldBe "0.5"
                run("say [.seq(\"A\"), .shape(circle)]") shouldBe "[Seq(A), ${io.kixi.ks.tb.Shape("circle")}]"
                run("say \"len \${.seq(\"ACG\").length}\"") shouldBe "len 3"
                run("fun f(x) = x.length\nsay f(.seq(\"AC\"))") shouldBe "2"
                // A newline ends the operand: the second line is a statement starting with a literal.
                run("say 1\n.seq(\"AC\").length") shouldBe "1"
            }
        }

        test("after an operand, .name( is member access, whatever bags are loaded") {
            withSampleBags {
                run("class Holder {\n    fun seq(s) = \"called \" + s\n}\nlet h = Holder()\nsay h.seq(\"A\")\nsay Holder().seq(\"B\")") shouldBe
                        "called A\ncalled B"
            }
        }

        test("the lexer does not ask which bags are loaded; evaluation reports a missing bag") {
            val tokens = Lexer("let s = .seq(\"A\")").tokenize()
            tokens.map { it.type } shouldBe listOf(TokenType.LET, TokenType.IDENTIFIER, TokenType.EQUAL, TokenType.EXT_LITERAL, TokenType.EOF)
            tokens[3].literal shouldBe ".seq(\"A\")"
            Lexer(".SUCCESS").tokenize()[0].type shouldBe TokenType.DOT       // an enum constant (DPEC) is unchanged
            Lexer(".grid(1)").tokenize()[0].type shouldBe TokenType.DOT
            Lexer("say.note(\"x\")").tokenize().map { it.type }.take(3) shouldBe listOf(TokenType.SAY, TokenType.DOT, TokenType.IDENTIFIER)
            Lexer("say .seq(\"x\")").tokenize().map { it.type }.take(2) shouldBe listOf(TokenType.SAY, TokenType.EXT_LITERAL)
            error("say .seq(\"A\")") shouldContain "Unknown literal .seq(…)"
            error("say .seq(\"A\")") shouldContain "Is the bag that defines it on the classpath?"
            error("say .id(\"01J9A0X5K2M7N3P8Q1R6T4V9AB\")") shouldContain "a KD literal that KS does not read"
            withSampleBags { error("say .seq(\"AX\")") shouldContain "Invalid .seq literal" }
        }

        test("literals after keywords, arrows, brackets and commas; an unclosed literal points at its dot") {
            withSampleBags {
                run("fun f() { return .seq(\"AC\") }\nsay f().length") shouldBe "2"
                run("let g = { -> .seq(\"G\") }\nsay g()") shouldBe "Seq(G)"
                run("say [.seq(\"A\"), .seq(\"C\")].size") shouldBe "2"
                run("say (.seq(\"ACG\")).length") shouldBe "3"
                run("let ok = false\nlet s = if (ok) { .seq(\"A\") } else { .seq(\"C\") }\nsay s") shouldBe "Seq(C)"
                run("say .seq(\"A\") in [.seq(\"A\")]") shouldBe "true"
            }
            val e = shouldThrow<io.kixi.ks.lexer.LexerException> { Lexer("let a = 1\nlet s = .seq(\"A\"").tokenize() }
            e.message shouldContain "The .seq literal must close on its line"
            e.location!!.line shouldBe 2
            e.location!!.column shouldBe 9
        }

        test("a literal in a when branch matches by value") {
            withSampleBags {
                run("let s = .seq(\"A\")\nlet r = when s {\n    .seq(\"C\") -> \"C\"\n    .seq(\"A\") -> \"A\"\n    else -> \"other\"\n}\nsay r") shouldBe "A"
            }
        }
    }

    context("members") {

        test("properties, methods, nil members and extension members, with hostLang off") {
            withSampleBags {
                run("let s = .seq(\"ACGT\")\nsay s.slice(1, 3)\nsay s.reverse()\nsay s.contains(\"CG\")\nsay s.isPalindrome") shouldBe
                        "Seq(CG)\nSeq(TGCA)\ntrue\ntrue"
                run("say .seq(\"\").gc") shouldBe "nil"
                run("say .seq(\"AC\").length()") shouldBe "2"                 // a property called with () is its value, as in KS
                error("say .seq(\"AC\").slice(1)") shouldContain "slice(...) takes 2 arguments, not 1"
                error("say .seq(\"AC\").bases") shouldContain "Type 'Seq' has no member 'bases'"
            }
        }

        test("with hostLang on, a name the bag does not declare falls back to reflection") {
            withSampleBags { run("say .seq(\"AC\").bases", hostLang = true) shouldBe "AC" }
        }

        test("in on a literal read without its bag is an error, as in KQ") {
            error("say \"A\" in x", bind = mapOf("x" to ExtLiteral(".seq(\"A\")"))) shouldContain "read without its type bag"
        }

        test("a literal read without its bag has no members, and says why, with hostLang on or off") {
            error("say x.length", bind = mapOf("x" to ExtLiteral(".seq(\"A\")"))) shouldContain "was read without its type bag"
            error("say x.length", hostLang = true, bind = mapOf("x" to ExtLiteral(".seq(\"A\")"))) shouldContain "was read without its type bag"
        }

        test("a bag value that is also KSMembers keeps the members it exposes itself") {
            withSampleBags {
                val v = object : io.kixi.ks.tb.Shape("circle"), io.kixi.ks.KSMembers {
                    override fun ksMember(name: String): Any? = if (name == "sides") 0 else null
                }
                run("say v.kind\nsay v.sides", bind = mapOf("v" to v)) shouldBe "circle\n0"
            }
        }

        test("in asks a bag value's contains") {
            withSampleBags {
                run("say \"CG\" in .seq(\"ACGT\")\nsay .seq(\"TT\") in .seq(\"ACGT\")\nsay 3 in .shape(circle)") shouldBe "true\nfalse\nfalse"
            }
        }
    }

    context("order") {

        test("bag values order by their sort keys; a type that does not order says so") {
            withSampleBags {
                run("say .seq(\"A\") < .seq(\"C\")\nsay .seq(\"GA\") >= .seq(\"T\")") shouldBe "true\nfalse"
                run("say [.seq(\"T\"), .seq(\"A\"), .seq(\"GA\")].sorted()") shouldBe "[Seq(A), Seq(GA), Seq(T)]"
                run("var xs = [.seq(\"T\"), .seq(\"A\")]\nxs.sort()\nsay xs") shouldBe "[Seq(A), Seq(T)]"
                error("say .shape(circle) < .shape(square)") shouldContain "Shape values: the type does not order"
                error("say .seq(\"A\") < \"A\"") shouldContain "Cannot compare Seq with String"
                run("say 1 < 2") shouldBe "true"
            }
        }
    }

    context("types") {

        test("is, typed declarations and typeName use the bag's type name") {
            withSampleBags {
                run("let s = .seq(\"A\")\nsay s is Seq\nsay \"A\" is Seq\nsay s.typeName\nlet t: Seq = s\nsay t") shouldBe
                        "true\nfalse\nSeq\nSeq(A)"
                error("let t: Seq = \"A\"") shouldContain "Cannot assign String value to 't' of type 'Seq'"
                run("let p = .shape(circle)\nsay p is Shape\nsay p is Seq") shouldBe "true\nfalse"
                // A subclass of a bag type is that type.
                run("say x is Shape\nsay x.kind\nsay x.typeName", bind = mapOf("x" to object : io.kixi.ks.tb.Shape("square") {})) shouldBe "true\nsquare\nShape"
                run("let s = .seq(\"A\") as Seq\nsay s") shouldBe "Seq(A)"
                error("let s = \"A\" as Seq") shouldContain "Seq"
                run("use ki.tb\nsay tb.typeName\nsay tb.revcomp.typeName") shouldBe "bag tb\nfun revcomp"
                error("use ki.tb\nlet n: Int = tb.revcomp") shouldContain "Function"
                run("say x is ExtLiteral\nsay x.typeName", bind = mapOf("x" to ExtLiteral(".seq(\"A\")"))) shouldBe "true\nExtLiteral"
            }
        }
    }

    context("use ki.<bag>") {

        test("the bag as an object, every name, one name, and aliases") {
            withSampleBags {
                run("use ki.tb\nsay tb.revcomp(.seq(\"AACG\"))\nsay tb.answer") shouldBe "Seq(CGTT)\n42"
                run("use ki.tb as b\nsay b.answer") shouldBe "42"
                run("use ki.tb.*\nsay revcomp(.seq(\"AACG\"))\nsay answer\nsay concat(.seq(\"A\"), .seq(\"C\"), .seq(\"G\"))") shouldBe
                        "Seq(CGTT)\n42\nSeq(ACG)"
                run("use ki.tb.revcomp\nsay revcomp(.seq(\"A\"))") shouldBe "Seq(T)"
                run("use ki.tb.revcomp as rc, answer\nsay rc(.seq(\"C\"))\nsay answer") shouldBe "Seq(G)\n42"
                run("use ki.*\nsay tb.answer\nsay tbx") shouldBe "42\n<bag tbx>"
            }
        }

        test("errors name what exists") {
            withSampleBags {
                error("use ki.nope") shouldContain "No type bag 'nope' is loaded (loaded: "
                error("use ki.tb.nope") shouldContain "Bag 'tb' has no function or constant 'nope' (it has: answer, concat, revcomp)"
                error("use ki.**") shouldContain "Use ki.<bag> or ki.<bag>.*"
                error("use ki") shouldContain "Use ki.<bag>, ki.<bag>.* or ki.<bag>.<name>"
                error("use ki.tb.revcomp.x") shouldContain "Use ki.<bag>, ki.<bag>.* or ki.<bag>.<name>"
                error("use ki.tb.*\nsay revcomp()") shouldContain "revcomp(...) takes 1 argument, not 0"
                error("use ki.tb.revcomp\nsay answer") shouldContain "answer"
            }
            error("use ki.tb") shouldContain "(no bags are loaded)"
        }

        test("bag functions report their own failures as KS errors") {
            withSampleBags { error("use ki.tb.*\nsay revcomp(3)") shouldContain "revcomp failed" }
        }
    }

    test("a value KS gets from a bag is the bag's value") {
        withSampleBags {
            val out = StringWriter()
            val i = interpreter(out, hostLang = false)
            i.executeProgram(Parser(Lexer("let s = .seq(\"GA\")").tokenize()).parse())
            i.environment.get("s", null) shouldBe Seq("GA")
        }
    }
})
