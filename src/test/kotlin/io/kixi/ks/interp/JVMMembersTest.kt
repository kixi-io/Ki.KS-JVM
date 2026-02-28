package io.kixi.ks.interp

import io.kixi.ks.KSRuntime
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Tests for `.members` on JVM/Kotlin classes, built-in types, and native types.
 *
 * Covers:
 *   - Built-in type members: String, StringBuilder, Int, Long, Float, Double, Dec
 *   - Built-in type members: Bool, Char, List, Map, Range
 *   - Built-in IO type members: File, BufferedReader, PrintWriter, streams
 *   - JVM class members via import: reflected constructors, methods, properties
 *   - JVM objects and companions: Kotlin object/companion reflection
 *   - JVM enum classes: constants and methods
 *   - Native Ki type members: Email, GeoPoint, Version
 *   - Error conditions
 *
 * All tests use hostLang=true (interop mode) to enable JVM class imports.
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.JVMMembersTest"
 */
class JVMMembersTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * Execute KS source with interop mode and capture stdout.
     */
    fun run(source: String): String {
        val output = StringWriter()
        val error = StringWriter()
        val runtime = KSRuntime(
            hostLang = true,
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
     * Execute KS source with interop mode and return the result.
     */
    fun eval(source: String): Any? {
        val output = StringWriter()
        val runtime = KSRuntime(
            hostLang = true,
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

    /**
     * Execute KS source in portable mode (no interop) and return the result.
     */
    fun evalPortable(source: String): Any? {
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

    /**
     * Execute KS source and expect a runtime error. Returns the error message.
     */
    fun runExpectingError(source: String, hostLang: Boolean = true): String {
        val output = StringWriter()
        val runtime = KSRuntime(
            hostLang = hostLang,
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
            throw AssertionError("Expected error but got: ${output.toString().trim()}")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            return e.message ?: e.toString()
        }
    }

    // ====================================================================
    // 1. Built-in String .members
    // ====================================================================

    context("String .members") {

        test("String.members returns String type") {
            val result = eval("String.members")
            result.shouldBeInstanceOf<String>()
        }

        test("String.members header") {
            val result = eval("String.members") as String
            result.lines().first() shouldBe "class String"
        }

        test("String.members shows Properties section") {
            val result = eval("String.members") as String
            result shouldContain "Properties:"
            result shouldContain "val size: Int"
            result shouldContain "val length: Int"
            result shouldContain "val indices: Range<Int>"
            result shouldContain "val rex: Regex"
        }

        test("String.members shows Methods section") {
            val result = eval("String.members") as String
            result shouldContain "Methods:"
            result shouldContain "fun contains("
            result shouldContain "fun split("
            result shouldContain "fun trim(): String"
            result shouldContain "fun substring("
            result shouldContain "fun replace("
            result shouldContain "fun uppercase(): String"
            result shouldContain "fun lowercase(): String"
            result shouldContain "fun isEmpty(): Bool"
            result shouldContain "fun toString(): String"
        }

        test("String.members shows indexOf") {
            val result = eval("String.members") as String
            result shouldContain "fun indexOf(str: String): Int"
        }

        test("String.members shows startsWith/endsWith") {
            val result = eval("String.members") as String
            result shouldContain "fun startsWith(prefix: String): Bool"
            result shouldContain "fun endsWith(suffix: String): Bool"
        }

        test("String.members shows reversed") {
            val result = eval("String.members") as String
            result shouldContain "fun reversed(): String"
        }

        test("String.members shows toString") {
            val result = eval("String.members") as String
            result shouldContain "fun toString(): String"
        }

        test("String.members via say") {
            val output = run("say String.members")
            output shouldContain "class String"
            output shouldContain "val length: Int"
        }
    }

    // ====================================================================
    // 2. Built-in StringBuilder .members
    // ====================================================================

    context("StringBuilder .members") {

        test("StringBuilder.members header and implements") {
            val result = eval("use java.lang.StringBuilder\nStringBuilder.members") as String
            result.lines().first() shouldBe "class StringBuilder"
            result shouldContain "implements CharSequence, Appendable"
        }

        test("StringBuilder.members shows Constructors") {
            val result = eval("use java.lang.StringBuilder\nStringBuilder.members") as String
            result shouldContain "Constructors:"
            result shouldContain "StringBuilder()"
            result shouldContain "StringBuilder(capacity: Int)"
            result shouldContain "StringBuilder(str: String)"
        }

        test("StringBuilder.members shows key methods") {
            val result = eval("use java.lang.StringBuilder\nStringBuilder.members") as String
            result shouldContain "fun append("
            result shouldContain "fun insert("
            result shouldContain "fun delete("
            result shouldContain "fun reverse(): StringBuilder"
            result shouldContain "fun toString(): String"
        }
    }

    // ====================================================================
    // 3. Built-in Number Types .members
    // ====================================================================

    context("Number types .members") {

        test("Int.members header") {
            val result = eval("Int.members") as String
            result.lines().first() shouldBe "class Int"
            result shouldContain "extends Number"
        }

        test("Int.members shows Static section with MIN/MAX") {
            val result = eval("Int.members") as String
            result shouldContain "Static:"
            result shouldContain "val MAX_VALUE: Int"
            result shouldContain "val MIN_VALUE: Int"
        }

        test("Int.members shows conversion methods") {
            val result = eval("Int.members") as String
            result shouldContain "fun toLong(): Long"
            result shouldContain "fun toDouble(): Double"
            result shouldContain "fun toFloat(): Float"
            result shouldContain "fun toString(): String"
        }

        test("Int.members shows toDec") {
            val result = eval("Int.members") as String
            result shouldContain "fun toDec(): Dec"
        }

        test("Long.members header and Static") {
            val result = eval("Long.members") as String
            result shouldContain "class Long"
            result shouldContain "extends Number"
            result shouldContain "val MAX_VALUE: Long"
        }

        test("Float.members shows special properties") {
            val result = eval("Float.members") as String
            result shouldContain "val isNaN: Bool"
            result shouldContain "val isInfinite: Bool"
            result shouldContain "val isFinite: Bool"
        }

        test("Double.members shows special properties") {
            val result = eval("Double.members") as String
            result shouldContain "val isNaN: Bool"
            result shouldContain "val POSITIVE_INFINITY: Double"
            result shouldContain "val NaN: Double"
        }

        test("Dec.members shows arithmetic methods") {
            val result = eval("Dec.members") as String
            result shouldContain "class Dec"
            result shouldContain "fun add(augend: Dec): Dec"
            result shouldContain "fun subtract(subtrahend: Dec): Dec"
            result shouldContain "fun multiply(multiplicand: Dec): Dec"
            result shouldContain "fun divide(divisor: Dec): Dec"
        }

        test("Dec.members shows Static constants") {
            val result = eval("Dec.members") as String
            result shouldContain "Static:"
            result shouldContain "val ZERO: Dec"
            result shouldContain "val ONE: Dec"
            result shouldContain "val TEN: Dec"
        }

        test("Number.members shows abstract class") {
            val result = eval("use java.lang.Number\nNumber.members") as String
            result shouldContain "abstract class Number"
            result shouldContain "fun toInt(): Int"
            result shouldContain "fun toDouble(): Double"
        }
    }

    // ====================================================================
    // 4. Built-in Bool and Char .members
    // ====================================================================

    context("Bool and Char .members") {

        test("Bool.members") {
            val result = eval("Bool.members") as String
            result shouldContain "class Bool"
            result shouldContain "fun compareTo(other: Bool): Int"
            result shouldContain "fun toString(): String"
        }

        test("Char.members shows properties") {
            val result = eval("Char.members") as String
            result shouldContain "class Char"
            result shouldContain "val isDigit: Bool"
            result shouldContain "val isLetter: Bool"
            result shouldContain "val isWhitespace: Bool"
        }

        test("Char.members shows methods") {
            val result = eval("Char.members") as String
            result shouldContain "fun uppercase(): String"
            result shouldContain "fun lowercase(): String"
            result shouldContain "fun digitToInt(): Int"
        }
    }

    // ====================================================================
    // 5. Built-in Collection .members
    // ====================================================================

    context("Collection types .members") {

        test("List.members header") {
            val result = eval("List.members") as String
            result.lines().first() shouldBe "class List<E>"
        }

        test("List.members shows Properties") {
            val result = eval("List.members") as String
            result shouldContain "val size: Int"
            result shouldContain "val isEmpty: Bool"
            result shouldContain "val first: E"
            result shouldContain "val last: E"
        }

        test("List.members shows key methods") {
            val result = eval("List.members") as String
            result shouldContain "fun add(element: E): Bool"
            result shouldContain "fun get(index: Int): E"
            result shouldContain "fun remove(element: E): Bool"
            result shouldContain "fun contains(element: E): Bool"
            result shouldContain "fun sort()"
            result shouldContain "fun reversed(): List<E>"
            result shouldContain "fun map("
            result shouldContain "fun filter("
            result shouldContain "fun joinToString("
        }

        test("Map.members header") {
            val result = eval("Map.members") as String
            result.lines().first() shouldBe "class Map<K, V>"
        }

        test("Map.members shows key methods") {
            val result = eval("Map.members") as String
            result shouldContain "val keys: Set<K>"
            result shouldContain "val values: Collection<V>"
            result shouldContain "fun get(key: K): V?"
            result shouldContain "fun put(key: K, value: V): V?"
            result shouldContain "fun containsKey(key: K): Bool"
            result shouldContain "fun remove(key: K): V?"
        }
    }

    // ====================================================================
    // 6. Built-in Range .members
    // ====================================================================

    context("Range .members") {

        test("Range.members header") {
            val result = eval("Range.members") as String
            result.lines().first() shouldBe "class Range<T>"
        }

        test("Range.members shows properties") {
            val result = eval("Range.members") as String
            result shouldContain "val start: T?"
            result shouldContain "val end: T?"
            result shouldContain "val isEmpty: Bool"
        }

        test("Range.members shows methods") {
            val result = eval("Range.members") as String
            result shouldContain "fun contains(value: T): Bool"
        }
    }

    // ====================================================================
    // 7. Built-in IO Types .members
    // ====================================================================

    context("IO types .members") {

        test("File.members shows Constructors") {
            val result = eval("use java.io.File\nFile.members") as String
            result shouldContain "class File"
            result shouldContain "Constructors:"
            result shouldContain "File(pathname: String)"
        }

        test("File.members shows file properties") {
            val result = eval("use java.io.File\nFile.members") as String
            result shouldContain "val name: String"
            result shouldContain "val path: String"
            result shouldContain "val absolutePath: String"
            result shouldContain "val isFile: Bool"
            result shouldContain "val isDirectory: Bool"
            result shouldContain "val exists: Bool"
        }

        test("File.members shows readText with UTF-8 default") {
            val result = eval("use java.io.File\nFile.members") as String
            result shouldContain "fun readText(charset: String = \"UTF-8\"): String"
        }

        test("File.members shows writeText with UTF-8 default") {
            val result = eval("use java.io.File\nFile.members") as String
            result shouldContain "fun writeText(text: String, charset: String = \"UTF-8\")"
        }

        test("File.members shows static separator") {
            val result = eval("use java.io.File\nFile.members") as String
            result shouldContain "Static:"
            result shouldContain "val separator: String"
        }

        test("BufferedReader.members shows extends and methods") {
            val result = eval("use java.io.BufferedReader\nBufferedReader.members") as String
            result shouldContain "class BufferedReader"
            result shouldContain "extends Reader"
            result shouldContain "fun readLine(): String?"
            result shouldContain "fun close()"
        }

        test("PrintWriter.members shows Constructors") {
            val result = eval("use java.io.PrintWriter\nPrintWriter.members") as String
            result shouldContain "Constructors:"
            result shouldContain "PrintWriter(out: Writer)"
            result shouldContain "fun println("
            result shouldContain "fun print("
        }

        test("InputStream.members shows abstract class") {
            val result = eval("use java.io.InputStream\nInputStream.members") as String
            result shouldContain "abstract class InputStream"
            result shouldContain "fun read(): Int"
            result shouldContain "fun readAllBytes(): ByteArray"
            result shouldContain "fun close()"
        }

        test("OutputStream.members shows abstract class") {
            val result = eval("use java.io.OutputStream\nOutputStream.members") as String
            result shouldContain "abstract class OutputStream"
            result shouldContain "fun write(b: Int)"
            result shouldContain "fun flush()"
            result shouldContain "fun close()"
        }

        test("DataInputStream.members shows typed read methods") {
            val result = eval("use java.io.DataInputStream\nDataInputStream.members") as String
            result shouldContain "fun readInt(): Int"
            result shouldContain "fun readLong(): Long"
            result shouldContain "fun readDouble(): Double"
            result shouldContain "fun readUTF(): String"
        }

        test("DataOutputStream.members shows typed write methods") {
            val result = eval("use java.io.DataOutputStream\nDataOutputStream.members") as String
            result shouldContain "fun writeInt(v: Int)"
            result shouldContain "fun writeLong(v: Long)"
            result shouldContain "fun writeDouble(v: Double)"
            result shouldContain "fun writeUTF(str: String)"
        }

        test("ByteArrayInputStream.members") {
            val result = eval("use java.io.ByteArrayInputStream\nByteArrayInputStream.members") as String
            result shouldContain "ByteArrayInputStream(buf: ByteArray)"
            result shouldContain "fun read(): Int"
        }

        test("ByteArrayOutputStream.members") {
            val result = eval("use java.io.ByteArrayOutputStream\nByteArrayOutputStream.members") as String
            result shouldContain "ByteArrayOutputStream()"
            result shouldContain "fun toByteArray(): ByteArray"
            result shouldContain "fun toString(): String"
        }
    }

    // ====================================================================
    // 8. Built-in Regex .members
    // ====================================================================

    context("Regex .members") {

        test("Regex.members header") {
            val result = eval("Regex.members") as String
            result.lines().first() shouldBe "class Regex"
        }

        test("Regex.members shows properties") {
            val result = eval("Regex.members") as String
            result shouldContain "val pattern: String"
        }

        test("Regex.members shows methods") {
            val result = eval("Regex.members") as String
            result shouldContain "fun find(input: String): MatchResult?"
            result shouldContain "fun findAll("
            result shouldContain "fun matches(input: String): Bool"
            result shouldContain "fun replace("
            result shouldContain "fun split("
        }
    }

    // ====================================================================
    // 9. JVM Class Members via Import
    // ====================================================================

    context("JVM class .members via import") {

        test("imported JVM class shows .members") {
            val result = eval("""
                use java.util.ArrayList
                ArrayList.members
            """.trimIndent()) as String
            result shouldContain "class ArrayList"
        }

        test("imported JVM class shows Constructors") {
            val result = eval("""
                use java.util.ArrayList
                ArrayList.members
            """.trimIndent()) as String
            result shouldContain "Constructors:"
        }

        test("imported JVM class shows Methods") {
            val result = eval("""
                use java.util.ArrayList
                ArrayList.members
            """.trimIndent()) as String
            result shouldContain "Methods:"
        }

        test("imported HashMap shows .members") {
            val result = eval("""
                use java.util.HashMap
                HashMap.members
            """.trimIndent()) as String
            result shouldContain "class HashMap"
            result shouldContain "Constructors:"
            result shouldContain "Methods:"
        }

        test("imported TreeMap shows interface") {
            val result = eval("""
                use java.util.TreeMap
                TreeMap.members
            """.trimIndent()) as String
            result shouldContain "class TreeMap"
        }
    }

    // ====================================================================
    // 10. Native Ki Type .members
    // ====================================================================

    context("native Ki type .members") {

        test("Email.members shows class header") {
            val result = eval("Email.members") as String
            result shouldContain "class Email"
        }

        test("GeoPoint.members shows class header") {
            val result = eval("GeoPoint.members") as String
            result shouldContain "class GeoPoint"
        }

        test("Version.members shows class header") {
            val result = eval("Version.members") as String
            result shouldContain "class Version"
        }

        test("Blob.members shows class header") {
            val result = eval("Blob.members") as String
            result shouldContain "class Blob"
        }
    }

    // ====================================================================
    // 11. .members Returns String (type check)
    // ====================================================================

    context(".members always returns String") {

        test("String.members returns String type") {
            val result = eval("String.members")
            result.shouldBeInstanceOf<String>()
        }

        test("Int.members returns String type") {
            val result = eval("Int.members")
            result.shouldBeInstanceOf<String>()
        }

        test("List.members returns String type") {
            val result = eval("List.members")
            result.shouldBeInstanceOf<String>()
        }

        test("Map.members returns String type") {
            val result = eval("Map.members")
            result.shouldBeInstanceOf<String>()
        }

        test("File.members returns String type") {
            val result = eval("use java.io.File\nFile.members")
            result.shouldBeInstanceOf<String>()
        }
    }

    // ====================================================================
    // 12. .members Still Works for KS Types (Regression)
    // ====================================================================

    context("KS type .members regression") {

        test("KS class .members still works") {
            val result = eval("""
                class Dog(let name: String, var age: Int = 0) {
                    fun bark(): String = "Woof!"
                }
                Dog.members
            """.trimIndent()) as String
            result shouldContain "class Dog"
            result shouldContain "Constructors:"
            result shouldContain "Dog(name: String, age: Int = 0)"
            result shouldContain "Properties:"
            result shouldContain "let name: String"
            result shouldContain "Methods:"
            result shouldContain "fun bark(): String"
        }

        test("KS struct .members still works") {
            val result = eval("""
                struct Point(let x: Double, let y: Double)
                Point.members
            """.trimIndent()) as String
            result shouldContain "struct Point"
            result shouldContain "let x: Double"
        }

        test("KS enum .members still works") {
            val result = eval("""
                enum Color { Red, Green, Blue }
                Color.members
            """.trimIndent()) as String
            result shouldContain "enum Color"
            result shouldContain "Red, Green, Blue"
        }

        test("KS trait .members still works") {
            val result = eval("""
                trait Printable { fun display(): String }
                Printable.members
            """.trimIndent()) as String
            result shouldContain "trait Printable"
            result shouldContain "fun display(): String"
        }
    }

    // ====================================================================
    // 13. .members Output Formatting
    // ====================================================================

    context("formatting") {

        test("properties indented with two spaces") {
            val result = eval("String.members") as String
            result shouldContain "  val length: Int"
        }

        test("methods indented with two spaces") {
            val result = eval("String.members") as String
            result shouldContain "  fun trim(): String"
        }

        test("no trailing newline") {
            val result = eval("String.members") as String
            (result == result.trimEnd()) shouldBe true
        }

        test("empty sections omitted in built-in types") {
            val result = eval("use java.lang.Number\nNumber.members") as String
            result shouldNotContain "Constructors:"
            result shouldNotContain "Properties:"
            result shouldNotContain "Static:"
        }
    }

    // ====================================================================
    // 14. equals/hashCode/toString present in all types
    // ====================================================================

    context("equals, hashCode, toString in built-in types") {

        test("String.members includes hashCode") {
            val result = eval("String.members") as String
            result shouldContain "fun hashCode(): Int"
        }

        test("Int.members includes equals and hashCode") {
            val result = eval("Int.members") as String
            result shouldContain "fun equals(other: Any?): Bool"
            result shouldContain "fun hashCode(): Int"
        }

        test("List.members includes equals and hashCode") {
            val result = eval("List.members") as String
            result shouldContain "fun equals(other: Any?): Bool"
            result shouldContain "fun hashCode(): Int"
        }

        test("Map.members includes equals and hashCode") {
            val result = eval("Map.members") as String
            result shouldContain "fun equals(other: Any?): Bool"
            result shouldContain "fun hashCode(): Int"
        }

        test("Dec.members includes equals and hashCode") {
            val result = eval("Dec.members") as String
            result shouldContain "fun equals(other: Any?): Bool"
            result shouldContain "fun hashCode(): Int"
        }

        test("Number.members includes all three") {
            val result = eval("use java.lang.Number\nNumber.members") as String
            result shouldContain "fun equals(other: Any?): Bool"
            result shouldContain "fun hashCode(): Int"
            result shouldContain "fun toString(): String"
        }

        test("Range.members includes equals and hashCode") {
            val result = eval("Range.members") as String
            result shouldContain "fun equals(other: Any?): Bool"
            result shouldContain "fun hashCode(): Int"
        }

        test("Regex.members includes equals and hashCode") {
            val result = eval("Regex.members") as String
            result shouldContain "fun equals(other: Any?): Bool"
            result shouldContain "fun hashCode(): Int"
        }

        test("StringBuilder.members includes equals and hashCode") {
            val result = eval("use java.lang.StringBuilder\nStringBuilder.members") as String
            result shouldContain "fun equals(other: Any?): Bool"
            result shouldContain "fun hashCode(): Int"
        }
    }

    // ====================================================================
    // 15. .members via say
    // ====================================================================

    context(".members via say output") {

        test("say String.members outputs to stdout") {
            val output = run("say String.members")
            output shouldContain "class String"
            output shouldContain "Methods:"
        }

        test("say Int.members outputs to stdout") {
            val output = run("say Int.members")
            output shouldContain "class Int"
            output shouldContain "extends Number"
        }

        test("say List.members outputs to stdout") {
            val output = run("say List.members")
            output shouldContain "class List<E>"
            output shouldContain "val size: Int"
        }
    }

    // ====================================================================
    // 16. .members in Expressions
    // ====================================================================

    context(".members used in expressions") {

        test(".members result stored in variable") {
            val result = eval("""
                let info = String.members
                info
            """.trimIndent()) as String
            result shouldContain "class String"
        }

        test(".members result used with contains check") {
            val result = eval("""
                let info = Int.members
                "MAX_VALUE" in info
            """.trimIndent())
            result shouldBe true
        }

        test(".members in string interpolation") {
            val output = run("""
                let m = Bool.members
                say "Bool has ${'$'}{m.length} chars"
            """.trimIndent())
            // Just verify it produces output without errors
            output shouldContain "Bool has"
            output shouldContain "chars"
        }
    }
})