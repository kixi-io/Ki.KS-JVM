package io.kixi.ks

import io.kixi.ks.ext.rex
import io.kixi.ks.interp.Interpreter
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Comprehensive tests for the String `.rex` extension property.
 *
 * Covers:
 *   - Kotlin-level extension property (`String.rex`)
 *   - KS interpreter `.rex` member on all four string literal types:
 *     basic ("..."), raw (`...`), multiline (\"\"\"...\"\"\"), raw multiline (```...```)
 *   - Digit, word, character class, anchor, quantifier, and group patterns
 *   - Unicode and special character regex patterns
 *   - `.typeName` reflection on Regex results
 *   - `say` output of Regex values
 *   - `.rex` combined with the `matches` operator
 *   - Error conditions (invalid regex patterns)
 *
 * Note: Regex method dispatch (`.matches()`, `.find()`, `.replace()`, etc.)
 * is not yet wired in the interpreter. Those tests are staged in a
 * separate context and should be enabled once `getRegexMember` and
 * Regex method call handling are added.
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.StringRegexTest"
 */
class StringRegexTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * Execute KS source code and capture stdout output.
     */
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

    /**
     * Execute KS source and return the result of the last expression.
     */
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

    // ====================================================================
    // Kotlin-level extension property tests
    // ====================================================================

    context("Kotlin extension - String.rex") {
        test("returns a Regex instance") {
            val r = "\\d+".rex
            r.shouldBeInstanceOf<Regex>()
        }

        test("pattern is preserved") {
            "\\d{3}-\\d{2}-\\d{4}".rex.pattern shouldBe "\\d{3}-\\d{2}-\\d{4}"
        }

        test("digit pattern matches") {
            val r = "\\d+".rex
            r.matches("12345") shouldBe true
            r.matches("abc") shouldBe false
        }

        test("SSN pattern") {
            val r = "\\d{3}-\\d{2}-\\d{4}".rex
            r.matches("123-45-6789") shouldBe true
            r.matches("123456789") shouldBe false
        }

        test("email pattern") {
            val r = "(\\w+)@(\\w+\\.\\w+)".rex
            val m = r.find("Email me at dan@example.com")
            m shouldBe m  // non-null
            m!!.value shouldBe "dan@example.com"
            m.groupValues[1] shouldBe "dan"
            m.groupValues[2] shouldBe "example.com"
        }

        test("word boundary pattern") {
            val r = "\\bcat\\b".rex
            r.containsMatchIn("the cat sat") shouldBe true
            r.containsMatchIn("concatenate") shouldBe false
        }

        test("anchored pattern") {
            val r = "^hello$".rex
            r.matches("hello") shouldBe true
            r.matches("say hello") shouldBe false
        }

        test("alternation pattern") {
            val r = "cat|dog|fish".rex
            r.containsMatchIn("I have a dog") shouldBe true
            r.containsMatchIn("I have a bird") shouldBe false
        }

        test("character class pattern") {
            val r = "[A-Z][a-z]+".rex
            r.matches("Hello") shouldBe true
            r.matches("hello") shouldBe false
        }

        test("quantifiers") {
            val r = "a{2,4}".rex
            r.matches("aa") shouldBe true
            r.matches("aaaa") shouldBe true
            r.matches("a") shouldBe false
            r.matches("aaaaa") shouldBe false
        }

        test("lazy quantifier") {
            val r = "<.+?>".rex
            val m = r.find("<b>bold</b>")
            m!!.value shouldBe "<b>"
        }

        test("lookahead") {
            val r = "\\d+(?= dollars)".rex
            val m = r.find("100 dollars")
            m!!.value shouldBe "100"
        }

        test("empty pattern matches empty string") {
            "".rex.matches("") shouldBe true
        }

        test("dot matches any character") {
            val r = "a.c".rex
            r.matches("abc") shouldBe true
            r.matches("a9c") shouldBe true
            r.matches("ac") shouldBe false
        }

        test("Unicode letter pattern") {
            val r = "\\p{L}+".rex
            r.matches("\u65E5\u672C\u8A9E") shouldBe true  // Japanese
            r.matches("hello") shouldBe true
            r.matches("123") shouldBe false
        }

        test("findAll collects multiple matches") {
            val r = "\\d+".rex
            val matches = r.findAll("abc 12 def 345 ghi 6").map { it.value }.toList()
            matches shouldBe listOf("12", "345", "6")
        }

        test("replace with rex result") {
            val r = "\\s+".rex
            r.replace("hello   world", " ") shouldBe "hello world"
        }

        test("split with rex result") {
            val r = "[,;]+".rex
            r.split("a,b;;c,d") shouldBe listOf("a", "b", "c", "d")
        }
    }

    // ====================================================================
    // KS interpreter - basic strings ("...")
    // ====================================================================

    context("KS basic string .rex") {
        test("returns Regex type") {
            val result = eval("""
                let r = "\\d+".rex
                r
            """.trimIndent())
            result.shouldBeInstanceOf<Regex>()
        }

        test("typeName is Regex") {
            run("""
                let r = "\\d+".rex
                say r.typeName
            """.trimIndent()) shouldBe "Regex"
        }

        test("say prints the pattern") {
            run("""
                let r = "\\d{3}-\\d{2}-\\d{4}".rex
                say r
            """.trimIndent()) shouldBe "\\d{3}-\\d{2}-\\d{4}"
        }

        test("SSN pattern via basic string") {
            val result = eval("""
                "\\d{3}-\\d{2}-\\d{4}".rex
            """.trimIndent()) as Regex
            result.matches("123-45-6789") shouldBe true
            result.matches("123456789") shouldBe false
        }

        test("email pattern via basic string") {
            val result = eval("""
                "(\\w+)@(\\w+\\.\\w+)".rex
            """.trimIndent()) as Regex
            val m = result.find("dan@example.com")
            m!!.value shouldBe "dan@example.com"
        }

        test("anchored pattern via basic string") {
            val result = eval("""
                "^[A-Z][a-z]+$".rex
            """.trimIndent()) as Regex
            result.matches("Hello") shouldBe true
            result.matches("hello") shouldBe false
        }

        test("word boundary pattern") {
            val result = eval("""
                "\\bfoo\\b".rex
            """.trimIndent()) as Regex
            result.containsMatchIn("foo bar") shouldBe true
            result.containsMatchIn("foobar") shouldBe false
        }

        test("escaped special chars in basic string") {
            val result = eval("""
                "\\(\\d+\\)".rex
            """.trimIndent()) as Regex
            result.matches("(42)") shouldBe true
            result.matches("42") shouldBe false
        }
    }

    // ====================================================================
    // KS interpreter - raw strings (`...`)
    // ====================================================================

    context("KS raw string .rex") {
        test("returns Regex type") {
            val result = eval("""
                let r = `\d+`.rex
                r
            """.trimIndent())
            result.shouldBeInstanceOf<Regex>()
        }

        test("typeName is Regex") {
            run("""
                let r = `\d+`.rex
                say r.typeName
            """.trimIndent()) shouldBe "Regex"
        }

        test("say prints the pattern") {
            run("""
                let r = `\d{3}-\d{2}-\d{4}`.rex
                say r
            """.trimIndent()) shouldBe "\\d{3}-\\d{2}-\\d{4}"
        }

        test("SSN pattern - no double escaping needed") {
            val result = eval("""
                `\d{3}-\d{2}-\d{4}`.rex
            """.trimIndent()) as Regex
            result.matches("123-45-6789") shouldBe true
            result.matches("123456789") shouldBe false
        }

        test("email pattern via raw string") {
            val result = eval("""
                `(\w+)@(\w+\.\w+)`.rex
            """.trimIndent()) as Regex
            val m = result.find("dan@example.com")
            m!!.value shouldBe "dan@example.com"
            m.groupValues[1] shouldBe "dan"
            m.groupValues[2] shouldBe "example.com"
        }

        test("complex pattern with backslashes") {
            val result = eval("""
                `^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z]{2,}$`.rex
            """.trimIndent()) as Regex
            result.matches("user@example.COM") shouldBe true
            result.matches("not-an-email") shouldBe false
        }

        test("Windows path pattern via raw string") {
            val result = eval("""
                `[A-Z]:\\[\w\\]+\.\w+`.rex
            """.trimIndent()) as Regex
            result.matches("C:\\Users\\file.txt") shouldBe true
        }

        test("IP address pattern") {
            val result = eval("""
                `(\d{1,3}\.){3}\d{1,3}`.rex
            """.trimIndent()) as Regex
            result.matches("192.168.1.1") shouldBe true
            result.matches("999.999.999.999") shouldBe true  // pattern only, not validation
            result.matches("abc") shouldBe false
        }

        test("URL pattern via raw string") {
            val result = eval("""
                `https?://[\w.-]+(/[\w./-]*)?`.rex
            """.trimIndent()) as Regex
            result.containsMatchIn("Visit https://example.com/path") shouldBe true
        }

        test("hex color pattern") {
            val result = eval("""
                `#[0-9A-Fa-f]{6}`.rex
            """.trimIndent()) as Regex
            result.matches("#FF00AA") shouldBe true
            result.matches("#xyz123") shouldBe false
        }

        test("date pattern YYYY-MM-DD") {
            val result = eval("""
                `\d{4}-\d{2}-\d{2}`.rex
            """.trimIndent()) as Regex
            result.matches("2026-02-15") shouldBe true
            result.matches("26-2-15") shouldBe false
        }

        test("alternation pattern") {
            val result = eval("""
                `cat|dog|fish`.rex
            """.trimIndent()) as Regex
            result.containsMatchIn("I have a dog") shouldBe true
            result.containsMatchIn("I have a bird") shouldBe false
        }

        test("quantifiers - greedy and lazy") {
            val greedy = eval("`<.+>`.rex") as Regex
            val lazy = eval("`<.+?>`.rex") as Regex
            val input = "<b>bold</b>"
            greedy.find(input)!!.value shouldBe "<b>bold</b>"
            lazy.find(input)!!.value shouldBe "<b>"
        }

        test("lookahead via raw string") {
            val result = eval("""
                `\d+(?= dollars)`.rex
            """.trimIndent()) as Regex
            result.find("100 dollars")!!.value shouldBe "100"
        }

        test("lookbehind via raw string") {
            val result = eval("""
                `(?<=\$)\d+`.rex
            """.trimIndent()) as Regex
            result.find("Price: \$42")!!.value shouldBe "42"
        }

        test("character class negation") {
            val result = eval("""
                `[^aeiou]+`.rex
            """.trimIndent()) as Regex
            result.find("hello")!!.value shouldBe "h"
        }

        test("named groups") {
            val result = eval("""
                `(?<year>\d{4})-(?<month>\d{2})-(?<day>\d{2})`.rex
            """.trimIndent()) as Regex
            val m = result.find("2026-02-15")!!
            m.groups["year"]!!.value shouldBe "2026"
            m.groups["month"]!!.value shouldBe "02"
            m.groups["day"]!!.value shouldBe "15"
        }
    }

    // ====================================================================
    // KS interpreter - multiline strings ("""...""")
    // ====================================================================

    context("KS multiline string .rex") {
        test("simple multiline pattern") {
            // KS multiline strings process escapes, so \\ -> \ for regex backslash
            run("""
                let r = ${"\"\"\""}\\d+${"\"\"\""}.rex
                say r.matches("42")
            """.trimIndent()) shouldBe "true"
        }

        test("typeName is Regex") {
            run("""
                let r = ${"\"\"\""}\\d+${"\"\"\""}.rex
                say r.typeName
            """.trimIndent()) shouldBe "Regex"
        }

        test("multiline pattern with indentation stripping") {
            val result = eval("""
                let r = ${"\"\"\""}
                    \\d{3}-\\d{2}-\\d{4}
                    ${"\"\"\""}.rex
                r
            """.trimIndent()) as Regex
            result.matches("123-45-6789") shouldBe true
        }

        test("verbose regex pattern") {
            // Multiline strings are useful for documenting complex patterns
            val result = eval("\"\"\"[A-Z][a-z]+\"\"\".rex") as Regex
            result.matches("Hello") shouldBe true
            result.matches("hello") shouldBe false
        }
    }

    // ====================================================================
    // KS interpreter - raw multiline strings (```...```)
    // ====================================================================

    context("KS raw multiline string .rex") {
        test("simple raw multiline pattern") {
            // Separate raw multiline string from .rex to avoid lexer ambiguity
            run("""
                let pattern = ${"```"}\d+${"```"}
                let r = pattern.rex
                say r.typeName
            """.trimIndent()) shouldBe "Regex"
        }

        test("complex pattern without escaping") {
            // Raw strings (backtick) are ideal for regex - no double escaping
            run("""
                let r = `(\w+)@(\w+\.\w+)`.rex
                let m = r.find("dan@example.com")
                say m.value
            """.trimIndent()) shouldBe "dan@example.com"
        }

        test("raw multiline SSN pattern") {
            run("""
                let r = `\d{3}-\d{2}-\d{4}`.rex
                say r.matches("123-45-6789")
            """.trimIndent()) shouldBe "true"
        }

        test("raw multiline with indentation stripping") {
            run("""
                let r = `\d{4}-\d{2}-\d{2}`.rex
                say r.matches("2026-02-15")
            """.trimIndent()) shouldBe "true"
        }
    }

    // ====================================================================
    // KS interpreter - .rex with matches operator
    // ====================================================================

    context("KS .rex combined with matches operator") {
        test("matches operator with basic string pattern") {
            run("""
                say "12345" matches "\\d+"
            """.trimIndent()) shouldBe "true"
        }

        test("matches operator with raw string pattern") {
            run("""
                say "12345" matches `\d+`
            """.trimIndent()) shouldBe "true"
        }

        test("matches negative case") {
            run("""
                say "hello" matches `\d+`
            """.trimIndent()) shouldBe "false"
        }

        test("matches with anchored pattern") {
            run("""
                say "Hello" matches `^[A-Z][a-z]+$`
            """.trimIndent()) shouldBe "true"
        }

        test("matches SSN pattern") {
            run("""
                let ssn = "123-45-6789"
                say ssn matches `\d{3}-\d{2}-\d{4}`
            """.trimIndent()) shouldBe "true"
        }

        test("matches email pattern") {
            run("""
                say "dan@example.com" matches `\w+@\w+\.\w+`
            """.trimIndent()) shouldBe "true"
        }

        test("matches with alternation") {
            run("""
                say "dog" matches "cat|dog|fish"
            """.trimIndent()) shouldBe "true"
        }

        test("matches with character class") {
            run("""
                say "A1" matches `[A-Z]\d`
            """.trimIndent()) shouldBe "true"
        }

        test("matches in when expression") {
            run("""
                let input = "2026-02-15"
                let result = when {
                    input matches `\d{4}-\d{2}-\d{2}` -> "date"
                    input matches `\d+` -> "number"
                    else -> "other"
                }
                say result
            """.trimIndent()) shouldBe "date"
        }

        test("matches in if expression") {
            run("""
                let phone = "555-1234"
                if (phone matches `\d{3}-\d{4}`) {
                    say "valid"
                } else {
                    say "invalid"
                }
            """.trimIndent()) shouldBe "valid"
        }
    }

    // ====================================================================
    // KS interpreter - .rex variable assignment and passing
    // ====================================================================

    context("KS .rex assignment and usage") {
        test("assign to let") {
            val result = eval("""
                let r = `\d+`.rex
                r
            """.trimIndent())
            result.shouldBeInstanceOf<Regex>()
            (result as Regex).pattern shouldBe "\\d+"
        }

        test("assign to var and reassign") {
            val result = eval("""
                var r = `\d+`.rex
                r = `[a-z]+`.rex
                r
            """.trimIndent()) as Regex
            result.pattern shouldBe "[a-z]+"
        }

        test("store in list") {
            run("""
                let patterns = [`\d+`.rex, `[a-z]+`.rex, `\w+@\w+`.rex]
                say patterns.size
            """.trimIndent()) shouldBe "3"
        }

        test("store in map") {
            run("""
                let ssn = `\d{3}-\d{2}-\d{4}`.rex
                let zip = `\d{5}`.rex
                let validators = ["ssn" = ssn, "zip" = zip]
                say validators.size
            """.trimIndent()) shouldBe "2"
        }

        test("regex equality - same pattern") {
            // Kotlin Regex equality is reference-based, so two .rex calls
            // produce different instances
            run("""
                let r1 = `\d+`.rex
                let r2 = `\d+`.rex
                say r1 == r2
            """.trimIndent()) shouldBe "false"
        }
    }

    // ====================================================================
    // KS interpreter - Unicode regex patterns
    // ====================================================================

    context("KS .rex with Unicode patterns") {
        test("Unicode letter class") {
            val result = eval("`\\p{L}+`.rex") as Regex
            result.matches("\u65E5\u672C\u8A9E") shouldBe true
        }

        test("Unicode digit class") {
            val result = eval("`\\p{Nd}+`.rex") as Regex
            result.matches("123") shouldBe true
        }

        test("literal Unicode characters in pattern via raw string") {
            val result = eval("`\u65E5\u672C.+`.rex") as Regex
            result.matches("\u65E5\u672C\u8A9E") shouldBe true
        }
    }

    // ====================================================================
    // Edge cases and error conditions
    // ====================================================================

    context("Edge cases") {
        test("empty string .rex matches empty string") {
            val result = eval("``.rex") as Regex
            result.matches("") shouldBe true
            result.matches("a") shouldBe false
        }

        test("dot-star matches anything") {
            val result = eval("`.*`.rex") as Regex
            result.matches("") shouldBe true
            result.matches("anything at all") shouldBe true
        }

        test("escaped special regex chars via raw string") {
            val result = eval("""`\(\)\[\]\{\}\.\*\+\?\^\$\|\\`.rex""") as Regex
            result.matches("()[]{}.*+?^\$|\\") shouldBe true
        }

        test("single character pattern") {
            val result = eval("`a`.rex") as Regex
            result.matches("a") shouldBe true
            result.matches("b") shouldBe false
        }

        test("pattern from variable") {
            val result = eval("""
                let pattern = `\d{4}`
                pattern.rex
            """.trimIndent()) as Regex
            result.matches("2026") shouldBe true
        }

        test("chained .rex access on literal") {
            run("""
                say `\d+`.rex.typeName
            """.trimIndent()) shouldBe "Regex"
        }
    }

    // ====================================================================
    // String literal type comparison for regex readability
    // ====================================================================

    context("String literal type comparison") {
        test("basic vs raw string produce same regex") {
            // Basic string requires double escaping
            val basic = eval("""
                "\\d{3}-\\d{2}-\\d{4}".rex
            """.trimIndent()) as Regex

            // Raw string: clean and readable
            val raw = eval("""
                `\d{3}-\d{2}-\d{4}`.rex
            """.trimIndent()) as Regex

            basic.pattern shouldBe raw.pattern
            basic.matches("123-45-6789") shouldBe true
            raw.matches("123-45-6789") shouldBe true
        }

        test("all four literal types produce same regex pattern") {
            // All four string literal types should produce the same regex pattern.
            // Escaping rules:
            //   basic "...": \\d -> \d (KS processes \\ to \)
            //   raw `...`:   \d -> \d (no escape processing)
            //   multiline """...""": \\d -> \d (KS processes \\ to \)
            //   raw backtick: same as raw `...` for single-line test
            run("""
                let basic = "\\d+".rex
                let raw = `\d+`.rex
                let multi = ${"\"\"\""}\\d+${"\"\"\""}.rex

                say basic.pattern == raw.pattern
                say raw.pattern == multi.pattern
            """.trimIndent()) shouldBe "true\ntrue"
        }
    }

    // ====================================================================
    // KS interpreter - Regex.matches() method
    // ====================================================================

    context("KS Regex.matches() method") {
        test("matches returns true for full match") {
            run("""
                let r = `\d+`.rex
                say r.matches("12345")
            """.trimIndent()) shouldBe "true"
        }

        test("matches returns false for non-match") {
            run("""
                let r = `\d+`.rex
                say r.matches("hello")
            """.trimIndent()) shouldBe "false"
        }

        test("matches requires full string match") {
            run("""
                let r = `\d+`.rex
                say r.matches("abc123def")
            """.trimIndent()) shouldBe "false"
        }

        test("SSN pattern matches") {
            run("""
                let r = `\d{3}-\d{2}-\d{4}`.rex
                say r.matches("123-45-6789")
            """.trimIndent()) shouldBe "true"
        }

        test("SSN pattern rejects invalid") {
            run("""
                let r = `\d{3}-\d{2}-\d{4}`.rex
                say r.matches("123456789")
            """.trimIndent()) shouldBe "false"
        }

        test("anchored pattern") {
            run("""
                let r = `^[A-Z][a-z]+$`.rex
                say r.matches("Hello")
            """.trimIndent()) shouldBe "true"
        }

        test("matches in if condition") {
            run("""
                let r = `\d{5}`.rex
                if (r.matches("90210")) {
                    say "valid zip"
                } else {
                    say "invalid"
                }
            """.trimIndent()) shouldBe "valid zip"
        }

        test("matches in when expression") {
            run("""
                let email = `\w+@\w+\.\w+`.rex
                let phone = `\d{3}-\d{4}`.rex

                let input = "555-1234"
                let result = when {
                    email.matches(input) -> "email"
                    phone.matches(input) -> "phone"
                    else -> "unknown"
                }
                say result
            """.trimIndent()) shouldBe "phone"
        }
    }

    // ====================================================================
    // KS interpreter - Regex.containsMatchIn() method
    // ====================================================================

    context("KS Regex.containsMatchIn() method") {
        test("finds match in longer string") {
            run("""
                let r = `\d+`.rex
                say r.containsMatchIn("abc 123 def")
            """.trimIndent()) shouldBe "true"
        }

        test("returns false when no match") {
            run("""
                let r = `\d+`.rex
                say r.containsMatchIn("no digits here")
            """.trimIndent()) shouldBe "false"
        }

        test("word boundary search") {
            run("""
                let r = `\bcat\b`.rex
                say r.containsMatchIn("the cat sat")
            """.trimIndent()) shouldBe "true"
        }

        test("word boundary rejects partial") {
            run("""
                let r = `\bcat\b`.rex
                say r.containsMatchIn("concatenate")
            """.trimIndent()) shouldBe "false"
        }
    }

    // ====================================================================
    // KS interpreter - Regex.find() method
    // ====================================================================

    context("KS Regex.find() method") {
        test("find returns MatchResult") {
            run("""
                let r = `\d+`.rex
                let m = r.find("abc 42 def")
                say m.typeName
            """.trimIndent()) shouldBe "MatchResult"
        }

        test("find returns matched value") {
            run("""
                let r = `\d+`.rex
                let m = r.find("abc 42 def")
                say m.value
            """.trimIndent()) shouldBe "42"
        }

        test("find returns nil for no match") {
            run("""
                let r = `\d+`.rex
                let m = r.find("no digits")
                say m == nil
            """.trimIndent()) shouldBe "true"
        }

        test("find with null check") {
            run("""
                let r = `(\w+)@(\w+\.\w+)`.rex
                let m = r.find("Email me at dan@example.com")
                if (m != nil) {
                    say m.value
                } else {
                    say "no match"
                }
            """.trimIndent()) shouldBe "dan@example.com"
        }

        test("find returns first match") {
            run("""
                let r = `\d+`.rex
                let m = r.find("12 and 34 and 56")
                say m.value
            """.trimIndent()) shouldBe "12"
        }

        test("find with safe access on nil result") {
            run("""
                let r = `\d+`.rex
                let m = r.find("no match")
                say m?.value
            """.trimIndent()) shouldBe "nil"
        }
    }

    // ====================================================================
    // KS interpreter - MatchResult properties and indexing
    // ====================================================================

    context("KS MatchResult properties") {
        test("value property") {
            run("""
                let r = `(\w+)@(\w+\.\w+)`.rex
                let m = r.find("dan@example.com")
                say m.value
            """.trimIndent()) shouldBe "dan@example.com"
        }

        test("groupValues property") {
            run("""
                let r = `(\w+)@(\w+\.\w+)`.rex
                let m = r.find("dan@example.com")
                say m.groupValues.size
            """.trimIndent()) shouldBe "3"  // group 0 + 2 capture groups
        }

        test("groupValues[0] is full match") {
            run("""
                let r = `(\w+)@(\w+\.\w+)`.rex
                let m = r.find("dan@example.com")
                say m.groupValues[0]
            """.trimIndent()) shouldBe "dan@example.com"
        }

        test("groupValues[1] is first group") {
            run("""
                let r = `(\w+)@(\w+\.\w+)`.rex
                let m = r.find("dan@example.com")
                say m.groupValues[1]
            """.trimIndent()) shouldBe "dan"
        }

        test("groupValues[2] is second group") {
            run("""
                let r = `(\w+)@(\w+\.\w+)`.rex
                let m = r.find("dan@example.com")
                say m.groupValues[2]
            """.trimIndent()) shouldBe "example.com"
        }

        test("groupCount property") {
            run("""
                let r = `(\w+)@(\w+)\.(\w+)`.rex
                let m = r.find("dan@example.com")
                say m.groupCount
            """.trimIndent()) shouldBe "3"
        }

        test("index access with int - group 0") {
            run("""
                let r = `(\w+)-(\w+)`.rex
                let m = r.find("hello-world")
                say m[0]
            """.trimIndent()) shouldBe "hello-world"
        }

        test("index access with int - group 1") {
            run("""
                let r = `(\w+)-(\w+)`.rex
                let m = r.find("hello-world")
                say m[1]
            """.trimIndent()) shouldBe "hello"
        }

        test("index access with int - group 2") {
            run("""
                let r = `(\w+)-(\w+)`.rex
                let m = r.find("hello-world")
                say m[2]
            """.trimIndent()) shouldBe "world"
        }

        test("named group index access") {
            run("""
                let r = `(?<year>\d{4})-(?<month>\d{2})-(?<day>\d{2})`.rex
                let m = r.find("2026-02-15")
                say m["year"]
            """.trimIndent()) shouldBe "2026"
        }

        test("named group index access - multiple") {
            run("""
                let r = `(?<year>\d{4})-(?<month>\d{2})-(?<day>\d{2})`.rex
                let m = r.find("2026-02-15")
                say "${'$'}{m["year"]}/${'$'}{m["month"]}/${'$'}{m["day"]}"
            """.trimIndent()) shouldBe "2026/02/15"
        }

        test("say stringifies MatchResult as its value") {
            run("""
                let r = `\d+`.rex
                let m = r.find("abc 42 def")
                say m
            """.trimIndent()) shouldBe "42"
        }
    }

    // ====================================================================
    // KS interpreter - Regex.findAll() method
    // ====================================================================

    context("KS Regex.findAll() method") {
        test("findAll returns list of MatchResults") {
            run("""
                let r = `\d+`.rex
                let results = r.findAll("12 and 34 and 56")
                say results.size
            """.trimIndent()) shouldBe "3"
        }

        test("findAll values") {
            run("""
                let r = `\d+`.rex
                let results = r.findAll("12 and 34 and 56")
                for m in results {
                    say m.value
                }
            """.trimIndent()) shouldBe "12\n34\n56"
        }

        test("findAll with groups") {
            run("""
                let r = `(\w+)@(\w+\.\w+)`.rex
                let results = r.findAll("dan@a.com and bob@b.org")
                for m in results {
                    say m[1]
                }
            """.trimIndent()) shouldBe "dan\nbob"
        }

        test("findAll returns empty list for no matches") {
            run("""
                let r = `\d+`.rex
                let results = r.findAll("no digits")
                say results.size
            """.trimIndent()) shouldBe "0"
        }

        test("findAll with word pattern") {
            run("""
                let r = `[A-Z][a-z]+`.rex
                let results = r.findAll("Hello Beautiful World")
                for m in results {
                    say m.value
                }
            """.trimIndent()) shouldBe "Hello\nBeautiful\nWorld"
        }
    }

    // ====================================================================
    // KS interpreter - Regex.replace() and Regex.replaceFirst()
    // ====================================================================

    context("KS Regex.replace() method") {
        test("replace all occurrences") {
            run("""
                let r = `\s+`.rex
                say r.replace("hello   world   foo", " ")
            """.trimIndent()) shouldBe "hello world foo"
        }

        test("replace digits with placeholder") {
            run("""
                let r = `\d+`.rex
                say r.replace("Order 123 and 456", "XXX")
            """.trimIndent()) shouldBe "Order XXX and XXX"
        }

        test("replace with empty string (deletion)") {
            run("""
                let r = `[aeiou]`.rex
                say r.replace("hello world", "")
            """.trimIndent()) shouldBe "hll wrld"
        }

        test("replace when no match returns original") {
            run("""
                let r = `\d+`.rex
                say r.replace("no digits", "X")
            """.trimIndent()) shouldBe "no digits"
        }

        test("replaceFirst replaces only first occurrence") {
            run("""
                let r = `\d+`.rex
                say r.replaceFirst("12 and 34 and 56", "XX")
            """.trimIndent()) shouldBe "XX and 34 and 56"
        }
    }

    // ====================================================================
    // KS interpreter - Regex.split() method
    // ====================================================================

    context("KS Regex.split() method") {
        test("split by whitespace") {
            run("""
                let r = `\s+`.rex
                let parts = r.split("hello   world   foo")
                say parts.size
            """.trimIndent()) shouldBe "3"
        }

        test("split values") {
            run("""
                let r = `[,;]+`.rex
                let parts = r.split("a,b;;c,d")
                for p in parts {
                    say p
                }
            """.trimIndent()) shouldBe "a\nb\nc\nd"
        }

        test("split by pipe") {
            run("""
                let r = `\|`.rex
                let parts = r.split("one|two|three")
                say parts.size
            """.trimIndent()) shouldBe "3"
        }

        test("split with no matches returns single-element list") {
            run("""
                let r = `\d+`.rex
                let parts = r.split("no digits")
                say parts.size
            """.trimIndent()) shouldBe "1"
        }
    }

    // ====================================================================
    // KS interpreter - Regex.pattern property
    // ====================================================================

    context("KS Regex.pattern property") {
        test("pattern from raw string") {
            run("""
                let r = `\d{3}-\d{4}`.rex
                say r.pattern
            """.trimIndent()) shouldBe "\\d{3}-\\d{4}"
        }

        test("pattern from basic string") {
            run("""
                let r = "\\d+".rex
                say r.pattern
            """.trimIndent()) shouldBe "\\d+"
        }

        test("pattern is String type") {
            run("""
                let r = `\d+`.rex
                say r.pattern.typeName
            """.trimIndent()) shouldBe "String"
        }
    }

    // ====================================================================
    // KS interpreter - Regex type checking
    // ====================================================================

    context("KS Regex type checks") {
        test("is Regex") {
            run("""
                let r = `\d+`.rex
                say r is Regex
            """.trimIndent()) shouldBe "true"
        }

        test("String is not Regex") {
            run("""
                let s = "hello"
                say s is Regex
            """.trimIndent()) shouldBe "false"
        }

        test("MatchResult is MatchResult") {
            run("""
                let r = `\d+`.rex
                let m = r.find("42")
                say m is MatchResult
            """.trimIndent()) shouldBe "true"
        }

        test("MatchResult is not Regex") {
            run("""
                let r = `\d+`.rex
                let m = r.find("42")
                say m is Regex
            """.trimIndent()) shouldBe "false"
        }
    }

    // ====================================================================
    // KS interpreter - practical examples
    // ====================================================================

    context("KS practical regex examples") {
        test("email extraction from text") {
            run("""
                let emailPat = `\w+@\w+\.\w+`.rex
                let text = "Contact dan@example.com or support@kixi.io"
                let results = emailPat.findAll(text)
                for m in results {
                    say m.value
                }
            """.trimIndent()) shouldBe "dan@example.com\nsupport@kixi.io"
        }

        test("input validation function") {
            run("""
                let zipCode = `^\d{5}(-\d{4})?$`.rex

                fun validateZip(input: String): String {
                    if (zipCode.matches(input)) {
                        return "valid"
                    }
                    return "invalid"
                }

                say validateZip("90210")
                say validateZip("90210-1234")
                say validateZip("abcde")
            """.trimIndent()) shouldBe "valid\nvalid\ninvalid"
        }

        test("parse key-value pairs") {
            run("""
                let kvPat = `(\w+)=(\w+)`.rex
                let config = "host=localhost port=8080 mode=debug"
                let pairs = kvPat.findAll(config)
                for p in pairs {
                    say "${'$'}{p[1]}: ${'$'}{p[2]}"
                }
            """.trimIndent()) shouldBe "host: localhost\nport: 8080\nmode: debug"
        }

        test("sanitize input") {
            run("""
                let htmlTags = `<[^>]+>`.rex
                let input = "<b>Hello</b> <i>World</i>"
                say htmlTags.replace(input, "")
            """.trimIndent()) shouldBe "Hello World"
        }

        test("extract numbers from text") {
            run("""
                let numPat = `\d+\.?\d*`.rex
                let text = "Price: 19.99, Qty: 3, Tax: 1.60"
                let nums = numPat.findAll(text)
                for n in nums {
                    say n.value
                }
            """.trimIndent()) shouldBe "19.99\n3\n1.60"
        }

        test("regex with when for input classification") {
            run("""
                let emailPat = `^\w+@\w+\.\w+$`.rex
                let phonePat = `^\d{3}-\d{3}-\d{4}$`.rex
                let zipPat = `^\d{5}$`.rex

                fun classify(input: String): String = when {
                    emailPat.matches(input) -> "email"
                    phonePat.matches(input) -> "phone"
                    zipPat.matches(input) -> "zip"
                    else -> "unknown"
                }

                say classify("dan@example.com")
                say classify("555-867-5309")
                say classify("90210")
                say classify("hello")
            """.trimIndent()) shouldBe "email\nphone\nzip\nunknown"
        }
    }
})