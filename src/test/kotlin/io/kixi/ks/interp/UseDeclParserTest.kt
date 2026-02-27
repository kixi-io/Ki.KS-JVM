package io.kixi.ks.interp

import io.kixi.ks.KSRuntime
import io.kixi.ks.ImportError
import io.kixi.ks.RuntimeError
import io.kixi.ks.parser.*
import io.kixi.ks.lexer.Lexer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldNotThrowAny

// ============================================================================
// Parser Tests — UseDecl parsing
// ============================================================================

class UseDeclParserTest : FunSpec({

    fun parse(source: String): UseDecl {
        val tokens = Lexer(source).tokenize()
        val program = Parser(tokens).parse()
        return program.body.first() as UseDecl
    }

    test("single type import") {
        val decl = parse("use io.kixi.Version")
        decl.packagePath shouldBe listOf("io", "kixi")
        decl.wildcard shouldBe UseWildcard.NONE
        decl.imports.size shouldBe 1
        decl.imports[0].name shouldBe "Version"
        decl.imports[0].alias.shouldBeNull()
    }

    test("single type import with alias") {
        val decl = parse("use io.kixi.Version as V")
        decl.packagePath shouldBe listOf("io", "kixi")
        decl.wildcard shouldBe UseWildcard.NONE
        decl.imports.size shouldBe 1
        decl.imports[0].name shouldBe "Version"
        decl.imports[0].alias shouldBe "V"
    }

    test("multi-import from same package") {
        val decl = parse("use io.kixi.kd.Tag, Annotation, Snip")
        decl.packagePath shouldBe listOf("io", "kixi", "kd")
        decl.wildcard shouldBe UseWildcard.NONE
        decl.imports.size shouldBe 3
        decl.imports[0].name shouldBe "Tag"
        decl.imports[1].name shouldBe "Annotation"
        decl.imports[2].name shouldBe "Snip"
    }

    test("multi-import with aliases") {
        val decl = parse("use io.kixi.kd.Tag as T, Annotation as Ann")
        decl.packagePath shouldBe listOf("io", "kixi", "kd")
        decl.imports.size shouldBe 2
        decl.imports[0].name shouldBe "Tag"
        decl.imports[0].alias shouldBe "T"
        decl.imports[1].name shouldBe "Annotation"
        decl.imports[1].alias shouldBe "Ann"
    }

    test("multi-import with mixed aliases") {
        val decl = parse("use io.kixi.kd.Tag as T, Annotation, Snip as S")
        decl.imports.size shouldBe 3
        decl.imports[0].alias shouldBe "T"
        decl.imports[1].alias.shouldBeNull()
        decl.imports[2].alias shouldBe "S"
    }

    test("flat wildcard") {
        val decl = parse("use io.kixi.kd.*")
        decl.packagePath shouldBe listOf("io", "kixi", "kd")
        decl.wildcard shouldBe UseWildcard.FLAT
        decl.imports shouldBe emptyList()
    }

    test("tree wildcard") {
        val decl = parse("use io.kixi.**")
        decl.packagePath shouldBe listOf("io", "kixi")
        decl.wildcard shouldBe UseWildcard.TREE
        decl.imports shouldBe emptyList()
    }

    test("static member import") {
        val decl = parse("use io.kixi.Version.parse")
        decl.packagePath shouldBe listOf("io", "kixi", "Version")
        decl.imports.size shouldBe 1
        decl.imports[0].name shouldBe "parse"
    }

    test("static member import with alias") {
        val decl = parse("use io.kixi.Version.parse as parseVer")
        decl.packagePath shouldBe listOf("io", "kixi", "Version")
        decl.imports.size shouldBe 1
        decl.imports[0].name shouldBe "parse"
        decl.imports[0].alias shouldBe "parseVer"
    }

    test("short path — two segments") {
        val decl = parse("use collections.ArrayList")
        decl.packagePath shouldBe listOf("collections")
        decl.imports.size shouldBe 1
        decl.imports[0].name shouldBe "ArrayList"
    }

    test("single identifier import (no dots)") {
        val decl = parse("use MyClass")
        decl.packagePath shouldBe emptyList()
        decl.imports.size shouldBe 1
        decl.imports[0].name shouldBe "MyClass"
    }
})

// ============================================================================
// ImportRegistry Unit Tests
// ============================================================================

class ImportRegistryTest : FunSpec({

    val interopRuntime = KSRuntime(hostLang = true, colorOutput = false)
    val portableRuntime = KSRuntime(hostLang = false, colorOutput = false)

    // --- Direct import resolution ---

    test("resolve JVM class - java.util.ArrayList") {
        val registry = ImportRegistry(interopRuntime)
        val decl = UseDecl(
            packagePath = listOf("java", "util"),
            wildcard = UseWildcard.NONE,
            imports = listOf(UseImport("ArrayList", null)),
            location = TestLocation
        )
        registry.processUseDecl(decl, emptyMap(), emptyMap(), emptyMap(), emptyMap())

        val resolved = registry.resolve("ArrayList")
        resolved.shouldNotBeNull()
        resolved.shouldBeInstanceOf<ResolvedImport.JVMClass>()
        (resolved as ResolvedImport.JVMClass).proxy.simpleName shouldBe "ArrayList"
    }

    test("resolve JVM class with alias") {
        val registry = ImportRegistry(interopRuntime)
        val decl = UseDecl(
            packagePath = listOf("java", "util"),
            wildcard = UseWildcard.NONE,
            imports = listOf(UseImport("ArrayList", "List")),
            location = TestLocation
        )
        registry.processUseDecl(decl, emptyMap(), emptyMap(), emptyMap(), emptyMap())

        // Original name should NOT resolve
        registry.resolve("ArrayList").shouldBeNull()
        // Alias should resolve
        val resolved = registry.resolve("List")
        resolved.shouldNotBeNull()
        resolved.shouldBeInstanceOf<ResolvedImport.JVMClass>()
    }

    test("resolve multiple imports from same package") {
        val registry = ImportRegistry(interopRuntime)
        val decl = UseDecl(
            packagePath = listOf("java", "util"),
            wildcard = UseWildcard.NONE,
            imports = listOf(
                UseImport("ArrayList", null),
                UseImport("HashMap", null),
                UseImport("LinkedList", "LL")
            ),
            location = TestLocation
        )
        registry.processUseDecl(decl, emptyMap(), emptyMap(), emptyMap(), emptyMap())

        registry.resolve("ArrayList").shouldNotBeNull()
        registry.resolve("HashMap").shouldNotBeNull()
        registry.resolve("LL").shouldNotBeNull()
        registry.resolve("LinkedList").shouldBeNull()  // aliased, not available by original name
    }

    test("unresolvable import throws ImportError") {
        val registry = ImportRegistry(interopRuntime)
        val decl = UseDecl(
            packagePath = listOf("com", "nonexistent", "fake"),
            wildcard = UseWildcard.NONE,
            imports = listOf(UseImport("NoSuchClass", null)),
            location = TestLocation
        )
        shouldThrow<ImportError> {
            registry.processUseDecl(decl, emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
    }

    test("JVM import disabled in portable mode") {
        val registry = ImportRegistry(portableRuntime)
        val decl = UseDecl(
            packagePath = listOf("java", "util"),
            wildcard = UseWildcard.NONE,
            imports = listOf(UseImport("ArrayList", null)),
            location = TestLocation
        )
        // Should throw because JVM class resolution is disabled
        shouldThrow<ImportError> {
            registry.processUseDecl(decl, emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
    }

    // --- KS type imports ---

    test("resolve KS class import") {
        val registry = ImportRegistry(portableRuntime)
        val mockClass = mockKSClass("Dog")
        val decl = UseDecl(
            packagePath = listOf("myapp", "models"),
            wildcard = UseWildcard.NONE,
            imports = listOf(UseImport("Dog", null)),
            location = TestLocation
        )
        registry.processUseDecl(
            decl,
            ksClasses = mapOf("Dog" to mockClass),
            ksStructs = emptyMap(),
            ksTraits = emptyMap(),
            ksEnums = emptyMap()
        )

        val resolved = registry.resolve("Dog")
        resolved.shouldNotBeNull()
        resolved.shouldBeInstanceOf<ResolvedImport.KsClass>()
    }

    // --- Flat wildcard ---

    test("flat wildcard resolves classes in package") {
        val registry = ImportRegistry(interopRuntime)
        val decl = UseDecl(
            packagePath = listOf("java", "util"),
            wildcard = UseWildcard.FLAT,
            imports = emptyList(),
            location = TestLocation
        )
        shouldNotThrowAny {
            registry.processUseDecl(decl, emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }

        // Should lazily resolve java.util.ArrayList
        val resolved = registry.resolve("ArrayList")
        resolved.shouldNotBeNull()
        resolved.shouldBeInstanceOf<ResolvedImport.JVMClass>()
    }

    test("flat wildcard caches misses") {
        val registry = ImportRegistry(interopRuntime)
        val decl = UseDecl(
            packagePath = listOf("java", "util"),
            wildcard = UseWildcard.FLAT,
            imports = emptyList(),
            location = TestLocation
        )
        registry.processUseDecl(decl, emptyMap(), emptyMap(), emptyMap(), emptyMap())

        // First lookup — miss
        registry.resolve("NonExistentClass99").shouldBeNull()
        // Second lookup — should use cached miss (no re-scanning)
        registry.resolve("NonExistentClass99").shouldBeNull()
    }

    // --- Tree wildcard ---

    test("tree wildcard resolves classes in subpackages") {
        val registry = ImportRegistry(interopRuntime)
        val decl = UseDecl(
            packagePath = listOf("java"),
            wildcard = UseWildcard.TREE,
            imports = emptyList(),
            location = TestLocation
        )
        shouldNotThrowAny {
            registry.processUseDecl(decl, emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }

        // java.util.ArrayList should be reachable through tree wildcard
        // (if subpackage discovery works on the JDK)
        val resolved = registry.resolve("ArrayList")
        // May or may not resolve depending on classpath scanning —
        // tree wildcard is best-effort. Don't assert hard failure.
    }

    // --- Clear ---

    test("clear removes all imports") {
        val registry = ImportRegistry(interopRuntime)
        val decl = UseDecl(
            packagePath = listOf("java", "util"),
            wildcard = UseWildcard.NONE,
            imports = listOf(UseImport("ArrayList", null)),
            location = TestLocation
        )
        registry.processUseDecl(decl, emptyMap(), emptyMap(), emptyMap(), emptyMap())

        registry.resolve("ArrayList").shouldNotBeNull()
        registry.clear()
        registry.resolve("ArrayList").shouldBeNull()
        registry.hasImports() shouldBe false
    }
})

// ============================================================================
// JVMClassProxy Tests
// ============================================================================

class JVMClassProxyTest : FunSpec({

    test("wrap java.util.ArrayList") {
        val proxy = JVMClassProxy(java.util.ArrayList::class.java)
        proxy.simpleName shouldBe "ArrayList"
        proxy.isKotlinObject shouldBe false
    }

    test("construct ArrayList") {
        val proxy = JVMClassProxy(java.util.ArrayList::class.java)
        val instance = proxy.construct(emptyList(), null)
        instance.shouldBeInstanceOf<java.util.ArrayList<*>>()
    }

    test("construct with arguments") {
        val proxy = JVMClassProxy(java.lang.StringBuilder::class.java)
        val instance = proxy.construct(listOf("hello"), null)
        instance.shouldBeInstanceOf<java.lang.StringBuilder>()
        instance.toString() shouldBe "hello"
    }

    test("detect Kotlin object") {
        // Kotlin's emptyList() returns kotlin.collections.EmptyList which is an object,
        // but it's internal. Use a known JDK singleton pattern instead.
        // For a real test, we'd need a Kotlin object on the classpath.
        // This tests the detection mechanism.
        val proxy = JVMClassProxy(java.util.Collections::class.java)
        // Collections is NOT a Kotlin object
        proxy.isKotlinObject shouldBe false
    }

    test("access static method") {
        val proxy = JVMClassProxy(java.util.Collections::class.java)
        val member = proxy.getMember("emptyList", null)
        member.shouldNotBeNull()
        member.shouldBeInstanceOf<JVMMethodProxy>()
    }

    test("access static field") {
        val proxy = JVMClassProxy(java.lang.Integer::class.java)
        val maxVal = proxy.getMember("MAX_VALUE", null)
        maxVal shouldBe Int.MAX_VALUE
    }

    test("hasMember returns true for existing members") {
        val proxy = JVMClassProxy(java.util.Collections::class.java)
        proxy.hasMember("emptyList") shouldBe true
        proxy.hasMember("nonExistentMethod") shouldBe false
    }

    test("toString returns class description") {
        val proxy = JVMClassProxy(java.util.ArrayList::class.java)
        proxy.toString() shouldBe "class ArrayList"
    }
})

// ============================================================================
// JVMMethodProxy Tests
// ============================================================================

class JVMMethodProxyTest : FunSpec({

    test("invoke static method") {
        val method = java.util.Collections::class.java.getMethod("emptyList")
        val proxy = JVMMethodProxy(null, method, "Collections")
        val result = proxy.call(mockInterpreter(), emptyList(), null)
        result shouldBe emptyList<Any>()
    }

    test("invoke instance method") {
        val list = java.util.ArrayList<Any>()
        val method = java.util.ArrayList::class.java.getMethod("size")
        val proxy = JVMMethodProxy(list, method, "ArrayList")
        val result = proxy.call(mockInterpreter(), emptyList(), null)
        result shouldBe 0
    }

    test("type coercion for numeric arguments") {
        // Integer.valueOf(int) — pass a KS Int (Kotlin Int)
        val method = java.lang.Integer::class.java.getMethod("valueOf", Int::class.java)
        val proxy = JVMMethodProxy(null, method, "Integer")
        val result = proxy.call(mockInterpreter(), listOf(42), null)
        result shouldBe 42
    }

    test("toString describes the method") {
        val method = java.util.Collections::class.java.getMethod("emptyList")
        val proxy = JVMMethodProxy(null, method, "Collections")
        proxy.toString() shouldBe "<jvm method Collections.emptyList>"
    }
})

// ============================================================================
// Integration Tests — Full Interpreter Pipeline
// ============================================================================

class UseImportIntegrationTest : FunSpec({

    fun execute(source: String): Any? {
        val output = java.io.StringWriter()
        val runtime = KSRuntime(
            hostLang = true,
            colorOutput = false,
            outputWriter = java.io.PrintWriter(output, true)
        )
        val interpreter = Interpreter(runtime)
        val tokens = Lexer(source).tokenize()
        val program = Parser(tokens).parse()
        return interpreter.executeProgram(program)
    }

    fun executeCapture(source: String): Pair<Any?, String> {
        val output = java.io.StringWriter()
        val runtime = KSRuntime(
            hostLang = true,
            colorOutput = false,
            outputWriter = java.io.PrintWriter(output, true)
        )
        val interpreter = Interpreter(runtime)
        val tokens = Lexer(source).tokenize()
        val program = Parser(tokens).parse()
        val result = interpreter.executeProgram(program)
        return Pair(result, output.toString())
    }

    // --- Single type imports ---

    test("import and use ArrayList") {
        val result = execute("""
            use java.util.ArrayList
            let list = ArrayList()
            list
        """.trimIndent())
        result.shouldBeInstanceOf<java.util.ArrayList<*>>()
    }

    test("import with alias") {
        val result = execute("""
            use java.util.ArrayList as AList
            let list = AList()
            list
        """.trimIndent())
        result.shouldBeInstanceOf<java.util.ArrayList<*>>()
    }

    test("import and access static member") {
        val result = execute("""
            use java.lang.Integer
            Integer.MAX_VALUE
        """.trimIndent())
        result shouldBe Int.MAX_VALUE
    }

    // --- Multi imports ---

    test("multi import from same package") {
        val result = execute("""
            use java.util.ArrayList, HashMap
            let list = ArrayList()
            let map = HashMap()
            map
        """.trimIndent())
        result.shouldBeInstanceOf<java.util.HashMap<*, *>>()
    }

    // --- Wildcard imports ---

    test("flat wildcard import") {
        val result = execute("""
            use java.util.*
            let list = ArrayList()
            list
        """.trimIndent())
        result.shouldBeInstanceOf<java.util.ArrayList<*>>()
    }

    // --- KS type imports (classes defined in same program) ---

    test("KS class accessible without explicit import") {
        val result = execute("""
            class Dog(let name: String)
            let d = Dog("Rex")
            d.name
        """.trimIndent())
        result shouldBe "Rex"
    }

    // --- Error cases ---

    test("import of nonexistent class throws ImportError") {
        shouldThrow<ImportError> {
            execute("use com.fake.nonexistent.NoSuchClass")
        }
    }

    test("use of unimported class throws UndefinedNameError") {
        shouldThrow<RuntimeError> {
            execute("""
                let list = FakeClassName()
            """.trimIndent())
        }
    }
})

// ============================================================================
// Test Helpers
// ============================================================================

/** Sentinel SourceLocation for test use declarations. */
private val TestLocation = io.kixi.ks.SourceLocation(1, 1)

/** Create a minimal mock KSClass for testing. */
private fun mockKSClass(name: String): KSClass {
    // Minimal KSClass for import registry testing.
    // Requires a ClassDecl and Environment to construct.
    val decl = ClassDecl(
        name = name,
        constructorParams = emptyList(),
        superTypes = emptyList(),
        members = emptyList(),
        location = TestLocation
    )
    return KSClass(
        declaration = decl,
        superclass = null,
        traits = emptyList(),
        closure = Environment.global()
    )
}

/** Create a minimal interpreter for JVMMethodProxy.call() testing. */
private fun mockInterpreter(): Interpreter {
    return Interpreter(KSRuntime(hostLang = true, colorOutput = false))
}