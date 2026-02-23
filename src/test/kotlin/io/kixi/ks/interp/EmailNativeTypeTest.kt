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
 * Tests for Email native type member access from KS code.
 *
 * Covers constructors, static members, instance properties, instance methods,
 * type checking (`is`), reflection (`.type`, `.typeName`), and error conditions.
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.EmailNativeTypeTest"
 */
class EmailNativeTypeTest : FunSpec({

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
    // Construction
    // ====================================================================

    context("Email - construction") {

        test("construct with full address") {
            run("""
                let e = Email("dan@leuck.org")
                say e.address
            """.trimIndent()) shouldBe "dan@leuck.org"
        }

        test("construct with local part and domain") {
            run("""
                let e = Email("dan", "leuck.org")
                say e.address
            """.trimIndent()) shouldBe "dan@leuck.org"
        }

        test("construct with plus-addressed tag") {
            run("""
                let e = Email("dan+spam@leuck.org")
                say e.address
            """.trimIndent()) shouldBe "dan+spam@leuck.org"
        }

        test("invalid email throws") {
            val error = runExpectingError("""
                Email("not-an-email")
            """.trimIndent())
            error shouldContain "@"
        }

        test("empty email throws") {
            val error = runExpectingError("""
                Email("")
            """.trimIndent())
            error shouldContain "empty"
        }

        test("wrong arg count throws") {
            val error = runExpectingError("""
                Email()
            """.trimIndent())
            error shouldContain "expects"
        }
    }

    // ====================================================================
    // Static Members
    // ====================================================================

    context("Email - static members") {

        test("Email.of with address") {
            run("""
                let e = Email.of("dan@leuck.org")
                say e.address
            """.trimIndent()) shouldBe "dan@leuck.org"
        }

        test("Email.of with local part and domain") {
            run("""
                let e = Email.of("dan", "leuck.org")
                say e.address
            """.trimIndent()) shouldBe "dan@leuck.org"
        }

        test("Email.ofOrNull valid") {
            run("""
                let e = Email.ofOrNull("dan@leuck.org")
                say e.address
            """.trimIndent()) shouldBe "dan@leuck.org"
        }

        test("Email.ofOrNull invalid returns nil") {
            run("""
                let e = Email.ofOrNull("invalid")
                say e
            """.trimIndent()) shouldBe "nil"
        }

        test("Email.isValid true") {
            run("""
                say Email.isValid("dan@leuck.org")
            """.trimIndent()) shouldBe "true"
        }

        test("Email.isValid false") {
            run("""
                say Email.isValid("not-an-email")
            """.trimIndent()) shouldBe "false"
        }

        test("Email.isLiteral true") {
            run("""
                say Email.isLiteral("user@example.com")
            """.trimIndent()) shouldBe "true"
        }

        test("Email.isLiteral false") {
            run("""
                say Email.isLiteral("not-email")
            """.trimIndent()) shouldBe "false"
        }

        test("Email.parseLiteral") {
            run("""
                let e = Email.parseLiteral("dan@leuck.org")
                say e.address
            """.trimIndent()) shouldBe "dan@leuck.org"
        }
    }

    // ====================================================================
    // Instance Properties
    // ====================================================================

    context("Email - instance properties") {

        test("localPart") {
            run("""
                say Email("dan@leuck.org").localPart
            """.trimIndent()) shouldBe "dan"
        }

        test("domain") {
            run("""
                say Email("dan@leuck.org").domain
            """.trimIndent()) shouldBe "leuck.org"
        }

        test("tld") {
            run("""
                say Email("dan@leuck.org").tld
            """.trimIndent()) shouldBe "org"
        }

        test("tld for multi-part domain") {
            run("""
                say Email("user@example.co.jp").tld
            """.trimIndent()) shouldBe "jp"
        }

        test("hasTag true") {
            run("""
                say Email("dan+spam@leuck.org").hasTag
            """.trimIndent()) shouldBe "true"
        }

        test("hasTag false") {
            run("""
                say Email("dan@leuck.org").hasTag
            """.trimIndent()) shouldBe "false"
        }

        test("tag when present") {
            run("""
                say Email("dan+spam@leuck.org").tag
            """.trimIndent()) shouldBe "spam"
        }

        test("tag when absent is nil") {
            run("""
                say Email("dan@leuck.org").tag
            """.trimIndent()) shouldBe "nil"
        }

        test("baseLocalPart with tag") {
            run("""
                say Email("dan+spam@leuck.org").baseLocalPart
            """.trimIndent()) shouldBe "dan"
        }

        test("baseLocalPart without tag") {
            run("""
                say Email("dan@leuck.org").baseLocalPart
            """.trimIndent()) shouldBe "dan"
        }

        test("address roundtrip") {
            run("""
                let e = Email("dan+newsletter@leuck.org")
                say e.address
                say e.localPart
                say e.domain
                say e.tag
                say e.baseLocalPart
            """.trimIndent()) shouldBe "dan+newsletter@leuck.org\ndan+newsletter\nleuck.org\nnewsletter\ndan"
        }
    }

    // ====================================================================
    // Instance Methods
    // ====================================================================

    context("Email - instance methods") {

        test("withoutTag removes tag") {
            run("""
                let tagged = Email("dan+spam@leuck.org")
                let e = tagged.withoutTag()
                say e.address
            """.trimIndent()) shouldBe "dan@leuck.org"
        }

        test("withoutTag on untagged is no-op") {
            run("""
                let orig = Email("dan@leuck.org")
                let e = orig.withoutTag()
                say e.address
            """.trimIndent()) shouldBe "dan@leuck.org"
        }

        test("withTag adds tag") {
            run("""
                let orig = Email("dan@leuck.org")
                let e = orig.withTag("newsletter")
                say e.address
            """.trimIndent()) shouldBe "dan+newsletter@leuck.org"
        }

        test("withTag replaces existing tag") {
            run("""
                let orig = Email("dan+spam@leuck.org")
                let e = orig.withTag("newsletter")
                say e.address
            """.trimIndent()) shouldBe "dan+newsletter@leuck.org"
        }

        test("chained withTag and withoutTag") {
            run("""
                let orig = Email("dan@leuck.org")
                let tagged = orig.withTag("promo")
                let e = tagged.withoutTag()
                say e.address
            """.trimIndent()) shouldBe "dan@leuck.org"
        }

        test("equalsIgnoreDomainCase true") {
            run("""
                let a = Email("dan@leuck.org")
                let b = Email("dan@LEUCK.ORG")
                say a.equalsIgnoreDomainCase(b)
            """.trimIndent()) shouldBe "true"
        }

        test("equalsIgnoreDomainCase false different local") {
            run("""
                let a = Email("dan@leuck.org")
                let b = Email("Dan@leuck.org")
                say a.equalsIgnoreDomainCase(b)
            """.trimIndent()) shouldBe "false"
        }
    }

    // ====================================================================
    // Type Checking and Reflection
    // ====================================================================

    context("Email - type checking") {

        test("is Email") {
            run("""
                let e = Email("dan@leuck.org")
                say e is Email
            """.trimIndent()) shouldBe "true"
        }

        test("string is not Email") {
            run("""
                say "dan@leuck.org" is Email
            """.trimIndent()) shouldBe "false"
        }

        test("!is Email") {
            run("""
                say 42 !is Email
            """.trimIndent()) shouldBe "true"
        }
    }

    context("Email - reflection") {

        test("typeName") {
            run("""
                say Email("dan@leuck.org").typeName
            """.trimIndent()) shouldBe "Email"
        }

        test("type") {
            run("""
                say Email("dan@leuck.org").type
            """.trimIndent()) shouldBe "Email"
        }

        test("constructor type") {
            run("""
                say Email.type
            """.trimIndent()) shouldBe "class Email"
        }
    }

    // ====================================================================
    // Error Conditions
    // ====================================================================

    context("Email - error conditions") {

        test("member not found") {
            val error = runExpectingError("""
                Email("dan@leuck.org").nonExistent
            """.trimIndent())
            error shouldContain "nonExistent"
        }

        test("static not found") {
            val error = runExpectingError("""
                Email.noSuchStatic
            """.trimIndent())
            error shouldContain "noSuchStatic"
        }

        test("withTag requires string argument") {
            val error = runExpectingError("""
                let e = Email("dan@leuck.org")
                let wt = e.withTag
                wt(42)
            """.trimIndent())
            error shouldContain "String"
        }

        test("equalsIgnoreDomainCase requires Email argument") {
            val error = runExpectingError("""
                let e = Email("dan@leuck.org")
                let eq = e.equalsIgnoreDomainCase
                eq("dan@leuck.org")
            """.trimIndent())
            error shouldContain "Email"
        }
    }

    // ====================================================================
    // Integration
    // ====================================================================

    context("Email - integration") {

        test("store in variable and access members") {
            run("""
                var e = Email("user+promo@example.com")
                say e.localPart
                say e.domain
                say e.tag
                say e.baseLocalPart
                say e.tld
            """.trimIndent()) shouldBe "user+promo\nexample.com\npromo\nuser\ncom"
        }

        test("Email in collection") {
            run("""
                let emails = [
                    Email("alice@example.com"),
                    Email("bob@example.com"),
                    Email("carol@example.com")
                ]
                for e in emails {
                    say e.localPart
                }
            """.trimIndent()) shouldBe "alice\nbob\ncarol"
        }

        test("Email as function parameter") {
            run("""
                fun getDomain(e: Email): String = e.domain
                say getDomain(Email("dan@leuck.org"))
            """.trimIndent()) shouldBe "leuck.org"
        }

        test("Email as return value") {
            run("""
                fun tagEmail(addr: String, tag: String): Email {
                    let e = Email(addr)
                    return e.withTag(tag)
                }
                let result = tagEmail("dan@leuck.org", "work")
                say result.address
            """.trimIndent()) shouldBe "dan+work@leuck.org"
        }

        test("Email in when expression") {
            run("""
                let e = Email("admin@example.com")
                let role = when {
                    e.localPart == "admin" -> "administrator"
                    e.localPart == "info" -> "info"
                    else -> "user"
                }
                say role
            """.trimIndent()) shouldBe "administrator"
        }

        test("Email stringify in say") {
            run("""
                let e = Email("dan@leuck.org")
                say e
            """.trimIndent()) shouldBe "dan@leuck.org"
        }

        test("filter emails by domain") {
            run("""
                let emails = [
                    Email("a@foo.com"),
                    Email("b@bar.com"),
                    Email("c@foo.com"),
                    Email("d@baz.com")
                ]
                var count = 0
                for e in emails {
                    if e.domain == "foo.com" { count = count + 1 }
                }
                say count
            """.trimIndent()) shouldBe "2"
        }

        test("strip tags from list of emails") {
            run("""
                let tagged = [
                    Email("alice+promo@example.com"),
                    Email("bob+spam@example.com")
                ]
                for tagged say it.withoutTag().address
                
            """.trimIndent()) shouldBe "alice@example.com\nbob@example.com"
        }
    }
})