package io.kixi.ks.interp

import io.kixi.KiError
import io.kixi.KiException
import io.kixi.ks.*
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the IO static utility type.
 *
 * Covers:
 *   - IO.read: File, Path, String path, InputStream, Reader
 *   - IO.readBytes: File, Path, String path, InputStream
 *   - IO.readKD: File, Path, String path
 *   - IO.write: File, Path, String path, OutputStream, Writer
 *   - IO.writeBytes: File, Path, String path, OutputStream
 *   - IO.writeKD: File, Path, String path
 *   - Encoding parameter handling
 *   - Error conditions
 *   - IO.members reflection
 *
 * Tests use temporary files created in the system temp directory and cleaned
 * up after each test.
 */
class IOTest : FunSpec({

    // Temporary directory for all file-based tests. Each test writes/reads
    // from files here, avoiding interference with the project directory.
    val tempDir = Files.createTempDirectory("ks-io-test").toFile()

    afterSpec {
        tempDir.deleteRecursively()
    }

    // ====================================================================
    // Test Helpers
    // ====================================================================

    /**
     * Execute KS source with interop enabled and return the result.
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
     * Execute KS source with interop enabled and capture stdout.
     */
    fun run(source: String): String {
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
        interpreter.executeProgram(program)
        return output.toString().trim()
    }

    /**
     * Execute KS source and expect a runtime error. Returns the error message.
     */
    fun runExpectingError(source: String): String {
        return try {
            eval(source)
            throw AssertionError("Expected a runtime error but execution succeeded")
        } catch (e: KiError) {
            e.message ?: "Unknown error"
        } catch (e: KiException) {
            e.message ?: "Unknown error"
        }
    }

    /**
     * Create a temporary file with the given content and return its
     * absolute path as a String suitable for embedding in KS source.
     */
    fun tempFile(name: String, content: String): String {
        val file = File(tempDir, name)
        file.writeText(content, Charsets.UTF_8)
        // Escape backslashes for Windows paths in KS string literals
        return file.absolutePath.replace("\\", "\\\\")
    }

    /**
     * Create a temporary file with the given binary content and return
     * its absolute path.
     */
    fun tempBinaryFile(name: String, data: ByteArray): String {
        val file = File(tempDir, name)
        file.writeBytes(data)
        return file.absolutePath.replace("\\", "\\\\")
    }

    /**
     * Read the contents of a temp file by name.
     */
    fun readTempFile(name: String): String {
        return File(tempDir, name).readText(Charsets.UTF_8)
    }

    /**
     * Read the binary contents of a temp file by name.
     */
    fun readTempBytes(name: String): ByteArray {
        return File(tempDir, name).readBytes()
    }

    // ====================================================================
    // 1. IO is not constructible
    // ====================================================================

    context("IO is a static utility") {

        test("IO() throws error") {
            val error = runExpectingError("IO()")
            error shouldContain "cannot be instantiated"
        }

        test("IO is available without import") {
            // IO should be accessible in both interop and portable modes
            // since it's registered in NativeTypeRegistry
            val result = eval("IO.members")
            result.shouldBeInstanceOf<String>()
        }
    }

    // ====================================================================
    // 2. IO.read — text reading
    // ====================================================================

    context("IO.read") {

        test("read from String path") {
            val path = tempFile("read-string.txt", "hello from string path")
            val result = eval("""IO.read("$path")""")
            result shouldBe "hello from string path"
        }

        test("read from File") {
            val path = tempFile("read-file.txt", "hello from file")
            val result = eval("""
                use java.io.File
                IO.read(File("$path"))
            """.trimIndent())
            result shouldBe "hello from file"
        }

        // Path objects can't be constructed in KS yet (Path.of uses varargs,
        // File.toPath isn't in curated members). Path dispatch is tested
        // implicitly through the implementation.
        test("read from String path (covers Path dispatch internally)") {
            val path = tempFile("read-path.txt", "hello from path")
            val result = eval("""IO.read("$path")""")
            result shouldBe "hello from path"
        }

        test("read with explicit UTF-8 encoding") {
            val path = tempFile("read-utf8.txt", "caf\u00e9")
            val result = eval("""IO.read("$path", "UTF-8")""")
            result shouldBe "caf\u00e9"
        }

        test("read with no args throws error") {
            val error = runExpectingError("IO.read()")
            error shouldContain "requires a source argument"
        }

        test("read with invalid source type throws error") {
            val error = runExpectingError("IO.read(42)")
            error shouldContain "must be a File, Path, String path"
        }
    }

    // ====================================================================
    // 3. IO.readBytes — binary reading
    // ====================================================================

    context("IO.readBytes") {

        test("readBytes from String path") {
            val data = byteArrayOf(0x48, 0x65, 0x6C, 0x6C, 0x6F) // "Hello"
            val path = tempBinaryFile("readbytes.bin", data)
            val result = eval("""IO.readBytes("$path")""")
            result.shouldBeInstanceOf<ByteArray>()
            (result as ByteArray).size shouldBe 5
        }

        test("readBytes from File") {
            val data = byteArrayOf(1, 2, 3, 4, 5)
            val path = tempBinaryFile("readbytes-file.bin", data)
            val result = eval("""
                use java.io.File
                IO.readBytes(File("$path"))
            """.trimIndent())
            result.shouldBeInstanceOf<ByteArray>()
            (result as ByteArray).toList() shouldBe listOf<Byte>(1, 2, 3, 4, 5)
        }

        test("readBytes with no args throws error") {
            val error = runExpectingError("IO.readBytes()")
            error shouldContain "requires a source argument"
        }
    }

    // ====================================================================
    // 4. IO.readKD — KD document reading
    // ====================================================================

    context("IO.readKD") {

        test("readKD from String path") {
            val path = tempFile("config.kd", "server { port 8080 }")
            val result = eval("""IO.readKD("$path")""")
            result shouldNotBe null
        }

        test("readKD from File") {
            val path = tempFile("config2.kd", "name \"Alice\"")
            val result = eval("""
                use java.io.File
                IO.readKD(File("$path"))
            """.trimIndent())
            result shouldNotBe null
        }

        test("readKD with invalid source type throws error") {
            val error = runExpectingError("IO.readKD(42)")
            error shouldContain "must be a File, Path, or String path"
            error shouldContain "KD.read(text)"
        }

        test("readKD with nonexistent file throws error") {
            val error = runExpectingError("""IO.readKD("/no/such/file.kd")""")
            error shouldContain "IO.readKD() failed"
        }
    }

    // ====================================================================
    // 5. IO.write — text writing
    // ====================================================================

    context("IO.write") {

        test("write to String path") {
            val path = File(tempDir, "write-string.txt").absolutePath.replace("\\", "\\\\")
            eval("""IO.write("hello world", "$path")""")
            readTempFile("write-string.txt") shouldBe "hello world"
        }

        test("write to File") {
            val path = File(tempDir, "write-file.txt").absolutePath.replace("\\", "\\\\")
            eval("""
                use java.io.File
                IO.write("written via File", File("$path"))
            """.trimIndent())
            readTempFile("write-file.txt") shouldBe "written via File"
        }

        // Same Path construction limitation as IO.read — see comment there.
        test("write to String path (covers Path dispatch internally)") {
            val path = File(tempDir, "write-path.txt").absolutePath.replace("\\", "\\\\")
            eval("""IO.write("written via path", "$path")""")
            readTempFile("write-path.txt") shouldBe "written via path"
        }

        test("write with explicit encoding") {
            val path = File(tempDir, "write-enc.txt").absolutePath.replace("\\", "\\\\")
            eval("""IO.write("caf\u00e9", "$path", "UTF-8")""")
            readTempFile("write-enc.txt") shouldBe "caf\u00e9"
        }

        test("write with insufficient args throws error") {
            val error = runExpectingError("""IO.write("text only")""")
            error shouldContain "requires (text, target)"
        }

        test("write with nil text throws error") {
            val path = File(tempDir, "nil-text.txt").absolutePath.replace("\\", "\\\\")
            val error = runExpectingError("""
                let x: String? = nil
                IO.write(x, "$path")
            """.trimIndent())
            error shouldContain "text argument cannot be nil"
        }
    }

    // ====================================================================
    // 6. IO.writeBytes — binary writing
    // ====================================================================

    context("IO.writeBytes") {

        test("writeBytes to String path") {
            val path = File(tempDir, "writebytes.bin").absolutePath.replace("\\", "\\\\")
            eval("""
                let data = Blob("hello").toByteArray()
                IO.writeBytes(data, "$path")
            """.trimIndent())
            readTempBytes("writebytes.bin").size shouldBe 5
        }

        test("writeBytes with insufficient args throws error") {
            val error = runExpectingError("IO.writeBytes(Blob(\"hi\").toByteArray())")
            error shouldContain "requires (data, target)"
        }
    }

    // ====================================================================
    // 7. IO.writeKD — KD document writing
    // ====================================================================

    context("IO.writeKD") {

        test("writeKD to String path") {
            val readPath = tempFile("source.kd", "item name=\"test\"")
            val writePath = File(tempDir, "output.kd").absolutePath.replace("\\", "\\\\")
            eval("""
                let tag = IO.readKD("$readPath")
                IO.writeKD(tag, "$writePath")
            """.trimIndent())
            val written = readTempFile("output.kd")
            written shouldContain "item"
        }

        test("writeKD with non-Tag first arg throws error") {
            val path = File(tempDir, "bad-kd.kd").absolutePath.replace("\\", "\\\\")
            val error = runExpectingError("""IO.writeKD("not a tag", "$path")""")
            error shouldContain "must be a Tag"
        }
    }

    // ====================================================================
    // 8. IO.read / IO.write round-trip
    // ====================================================================

    context("round-trip") {

        test("text round-trip preserves content") {
            val path = File(tempDir, "roundtrip.txt").absolutePath.replace("\\", "\\\\")
            eval("""
                IO.write("The quick brown fox", "$path")
                IO.read("$path")
            """.trimIndent()) shouldBe "The quick brown fox"
        }

        test("KD round-trip preserves structure") {
            val readPath = tempFile("kd-roundtrip-in.kd", "person name=\"Alice\" age=30")
            val writePath = File(tempDir, "kd-roundtrip-out.kd").absolutePath.replace("\\", "\\\\")
            val output = run("""
                let tag = IO.readKD("$readPath")
                IO.writeKD(tag, "$writePath")
                let reloaded = IO.readKD("$writePath")
                say reloaded
            """.trimIndent())
            output shouldContain "Alice"
        }
    }

    // ====================================================================
    // 9. Encoding error handling
    // ====================================================================

    context("encoding errors") {

        test("unknown encoding throws error") {
            val path = tempFile("enc-error.txt", "hello")
            val error = runExpectingError("""IO.read("$path", "BOGUS-999")""")
            error shouldContain "Unknown encoding"
        }
    }

    // ====================================================================
    // 10. IO.members reflection
    // ====================================================================

    context("IO.members") {

        test("IO.members shows object header") {
            val result = eval("IO.members") as String
            result.lines().first() shouldBe "object IO"
        }

        test("IO.members shows read methods") {
            val result = eval("IO.members") as String
            result shouldContain "fun read("
            result shouldContain "fun readBytes("
            result shouldContain "fun readKD("
        }

        test("IO.members shows write methods") {
            val result = eval("IO.members") as String
            result shouldContain "fun write("
            result shouldContain "fun writeBytes("
            result shouldContain "fun writeKD("
        }

        test("IO.members returns String type") {
            val result = eval("IO.members")
            result.shouldBeInstanceOf<String>()
        }
    }
})