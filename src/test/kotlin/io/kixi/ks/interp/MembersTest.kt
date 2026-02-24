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
 * Tests for the `.members` universal reflection property.
 *
 * Covers:
 *   - Class members: constructors, properties, methods, static, embedded enums
 *   - Struct members: constructors, properties, methods
 *   - Trait members: abstract methods, default methods
 *   - Enum members: constants, constructors, properties, methods, static
 *   - Extension methods displayed in Extensions section
 *   - Constraints and default values in signatures
 *   - Generic types in signatures
 *   - Return types in method signatures
 *   - .members returns String
 *   - .members via say output
 *   - Error conditions: instances, nil, non-type values
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.MembersTest"
 */
class MembersTest : FunSpec({

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

    /**
     * Execute KS source and expect a runtime error. Returns the error message.
     */
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
    // 1. .members Returns String
    // ====================================================================

    context(".members returns String type") {

        test("class .members returns String") {
            val result = eval("""
                class Dog(let name: String)
                Dog.members
            """.trimIndent())
            result.shouldBeInstanceOf<String>()
        }

        test("struct .members returns String") {
            val result = eval("""
                struct Point(let x: Double, let y: Double)
                Point.members
            """.trimIndent())
            result.shouldBeInstanceOf<String>()
        }

        test("trait .members returns String") {
            val result = eval("""
                trait Printable { fun display(): String }
                Printable.members
            """.trimIndent())
            result.shouldBeInstanceOf<String>()
        }

        test("enum .members returns String") {
            val result = eval("""
                enum Color { Red, Green, Blue }
                Color.members
            """.trimIndent())
            result.shouldBeInstanceOf<String>()
        }
    }

    // ====================================================================
    // 2. Class Members
    // ====================================================================

    context("class .members") {

        test("minimal class with no members") {
            val result = eval("""
                class Empty { }
                Empty.members
            """.trimIndent()) as String
            result shouldContain "class Empty"
            result shouldNotContain "Constructors:"
            result shouldNotContain "Properties:"
            result shouldNotContain "Methods:"
        }

        test("class with constructor params shows Constructors section") {
            val result = eval("""
                class Dog(let name: String, var age: Int = 0)
                Dog.members
            """.trimIndent()) as String
            result shouldContain "class Dog"
            result shouldContain "Constructors:"
            result shouldContain "Dog(name: String, age: Int = 0)"
        }

        test("class with let/var params shows Properties section") {
            val result = eval("""
                class Dog(let name: String, var age: Int = 0)
                Dog.members
            """.trimIndent()) as String
            result shouldContain "Properties:"
            result shouldContain "let name: String"
            result shouldContain "var age: Int"
        }

        test("class constructor-only params not in Properties") {
            val result = eval("""
                class Wrapper(data: String) { }
                Wrapper.members
            """.trimIndent()) as String
            result shouldContain "Constructors:"
            result shouldContain "Wrapper(data: String)"
            // data has no let/var binding, so no Properties section
            result shouldNotContain "Properties:"
        }

        test("class with body VarDecl shows in Properties") {
            val result = eval("""
                class Counter(let name: String) {
                    var count: Int = 0
                }
                Counter.members
            """.trimIndent()) as String
            result shouldContain "let name: String"
            result shouldContain "var count: Int"
        }

        test("class with methods shows Methods section") {
            val result = eval("""
                class Dog(let name: String) {
                    fun bark(): String = "Woof!"
                    fun sit() { }
                }
                Dog.members
            """.trimIndent()) as String
            result shouldContain "Methods:"
            result shouldContain "fun bark(): String"
            result shouldContain "fun sit()"
        }

        test("method with parameters and return type") {
            val result = eval("""
                class Calc {
                    fun add(a: Int, b: Int): Int = a + b
                }
                Calc.members
            """.trimIndent()) as String
            result shouldContain "fun add(a: Int, b: Int): Int"
        }

        test("method with default parameter value") {
            val result = eval("""
                class Greeter {
                    fun greet(name: String = "World"): String = "Hello"
                }
                Greeter.members
            """.trimIndent()) as String
            result shouldContain """fun greet(name: String = "World"): String"""
        }

        test("class with static members") {
            val result = eval("""
                class Config {
                    static {
                        let VERSION: String = "1.0"
                        fun defaults() = Config()
                    }
                }
                Config.members
            """.trimIndent()) as String
            result shouldContain "Static:"
            result shouldContain "let VERSION: String"
            result shouldContain "fun defaults()"
        }

        test("class with embedded enum") {
            val result = eval("""
                class Task(let title: String) {
                    enum Status { Pending, Active, Done }
                }
                Task.members
            """.trimIndent()) as String
            result shouldContain "Enums:"
            result shouldContain "Status { Pending, Active, Done }"
        }

        test("class with constraints on constructor params") {
            val result = eval("""
                class Pixel(let r: Int 0..255, let g: Int 0..255, let b: Int 0..255)
                Pixel.members
            """.trimIndent()) as String
            result shouldContain "Pixel(r: Int 0..255, g: Int 0..255, b: Int 0..255)"
            result shouldContain "let r: Int 0..255"
        }

        test("class with comparison constraint") {
            val result = eval("""
                class Positive(let value: Int > 0)
                Positive.members
            """.trimIndent()) as String
            result shouldContain "Positive(value: Int > 0)"
            result shouldContain "let value: Int > 0"
        }

        test("method with constrained parameter") {
            val result = eval("""
                class Game {
                    fun setLevel(n: Int 1..100) { }
                }
                Game.members
            """.trimIndent()) as String
            result shouldContain "fun setLevel(n: Int 1..100)"
        }

        test("full class with all sections") {
            val result = eval("""
                class Person(let name: String, var age: Int = 0) {
                    var greeting: String = "Hello"
                    fun greet(): String = "Hi"
                    fun celebrate(times: Int = 1) { }
                    enum Role { Admin, User, Guest }
                    static {
                        fun create(name: String) = Person(name, 0)
                    }
                }
                Person.members
            """.trimIndent()) as String
            result shouldContain "class Person"
            result shouldContain "Constructors:"
            result shouldContain "Person(name: String, age: Int = 0)"
            result shouldContain "Properties:"
            result shouldContain "let name: String"
            result shouldContain "var age: Int"
            result shouldContain "var greeting: String"
            result shouldContain "Methods:"
            result shouldContain "fun greet(): String"
            result shouldContain "fun celebrate(times: Int = 1)"
            result shouldContain "Enums:"
            result shouldContain "Role { Admin, User, Guest }"
            result shouldContain "Static:"
            result shouldContain "fun create(name: String)"
        }
    }

    // ====================================================================
    // 3. Struct Members
    // ====================================================================

    context("struct .members") {

        test("minimal struct") {
            val result = eval("""
                struct Point(let x: Double, let y: Double)
                Point.members
            """.trimIndent()) as String
            result shouldContain "struct Point"
            result shouldContain "Constructors:"
            result shouldContain "Point(x: Double, y: Double)"
            result shouldContain "Properties:"
            result shouldContain "let x: Double"
            result shouldContain "let y: Double"
        }

        test("struct with methods") {
            val result = eval("""
                struct Vec2(let x: Double, let y: Double) {
                    fun length(): Double = 0.0
                    fun dot(other: Vec2): Double = 0.0
                }
                Vec2.members
            """.trimIndent()) as String
            result shouldContain "Methods:"
            result shouldContain "fun length(): Double"
            // Note: Vec2 parameter type — formatter shows TypeRef name
            result shouldContain "fun dot(other: Vec2): Double"
        }

        test("struct with constrained params") {
            val result = eval("""
                struct Color(let r: Int 0..255, let g: Int 0..255, let b: Int 0..255) {
                    fun brightness(): Int = 0
                }
                Color.members
            """.trimIndent()) as String
            result shouldContain "struct Color"
            result shouldContain "Color(r: Int 0..255, g: Int 0..255, b: Int 0..255)"
            result shouldContain "let r: Int 0..255"
            result shouldContain "fun brightness(): Int"
        }

        test("struct with mutable property") {
            val result = eval("""
                struct Config(let name: String, var active: Bool = true)
                Config.members
            """.trimIndent()) as String
            result shouldContain "let name: String"
            result shouldContain "var active: Bool"
        }

        test("struct with static members") {
            val result = eval("""
                struct Point(let x: Double, let y: Double) {
                    static {
                        fun origin() = Point(0.0, 0.0)
                    }
                }
                Point.members
            """.trimIndent()) as String
            result shouldContain "Static:"
            result shouldContain "fun origin()"
        }
    }

    // ====================================================================
    // 4. Trait Members
    // ====================================================================

    context("trait .members") {

        test("trait with abstract method") {
            val result = eval("""
                trait Printable {
                    fun display(): String
                }
                Printable.members
            """.trimIndent()) as String
            result shouldContain "trait Printable"
            result shouldContain "Methods:"
            result shouldContain "fun display(): String"
        }

        test("trait with default implementation") {
            val result = eval("""
                trait Labelable {
                    fun label() = "item"
                }
                Labelable.members
            """.trimIndent()) as String
            result shouldContain "Methods:"
            result shouldContain "fun label()"
        }

        test("trait with abstract and default methods") {
            val result = eval("""
                trait Animal {
                    fun name(): String
                    fun speak() = "..."
                }
                Animal.members
            """.trimIndent()) as String
            result shouldContain "fun name(): String"
            result shouldContain "fun speak()"
        }

        test("trait with parameterized method") {
            val result = eval("""
                trait Comparable {
                    fun compareTo(other: Any): Int
                    fun lessThan(other: Any): Bool = false
                }
                Comparable.members
            """.trimIndent()) as String
            result shouldContain "fun compareTo(other: Any): Int"
            result shouldContain "fun lessThan(other: Any): Bool"
        }

        test("empty trait") {
            val result = eval("""
                trait Marker { }
                Marker.members
            """.trimIndent()) as String
            result shouldContain "trait Marker"
            result shouldNotContain "Methods:"
        }
    }

    // ====================================================================
    // 5. Enum Members
    // ====================================================================

    context("enum .members") {

        test("simple enum with constants") {
            val result = eval("""
                enum Color { Red, Green, Blue }
                Color.members
            """.trimIndent()) as String
            result shouldContain "enum Color"
            result shouldContain "Constants:"
            result shouldContain "Red, Green, Blue"
        }

        test("enum with values") {
            val result = eval("""
                enum Priority { Low=1, Medium=2, High=3 }
                Priority.members
            """.trimIndent()) as String
            result shouldContain "Constants:"
            result shouldContain "Low=1, Medium=2, High=3"
        }

        test("enum with constructor params") {
            val result = eval("""
                enum HttpStatus(code: Int, msg: String) {
                    OK(200, "OK"),
                    NotFound(404, "Not Found")
                }
                HttpStatus.members
            """.trimIndent()) as String
            result shouldContain "Constructors:"
            result shouldContain "HttpStatus(code: Int, msg: String)"
            result shouldContain "Constants:"
            result shouldContain "OK(200, \"OK\")"
            result shouldContain "NotFound(404, \"Not Found\")"
        }

        test("enum with methods") {
            val result = eval("""
                enum Direction {
                    North, South, East, West
                    fun opposite(): String = "unknown"
                }
                Direction.members
            """.trimIndent()) as String
            result shouldContain "Constants:"
            result shouldContain "North, South, East, West"
            result shouldContain "Methods:"
            result shouldContain "fun opposite(): String"
        }

        test("enum with static members") {
            val result = eval("""
                enum Season {
                    Spring, Summer, Autumn, Winter
                    static {
                        fun current() = .Summer
                    }
                }
                Season.members
            """.trimIndent()) as String
            result shouldContain "Static:"
            result shouldContain "fun current()"
        }

        test("enum no constructors section for simple enums") {
            val result = eval("""
                enum Color { Red, Green, Blue }
                Color.members
            """.trimIndent()) as String
            result shouldNotContain "Constructors:"
        }
    }

    // ====================================================================
    // 6. Extension Methods
    // ====================================================================

    context("extension methods in .members") {

        test("class extension method shown in Extensions section") {
            val result = eval("""
                class Dog(let name: String)
                extend Dog {
                    fun wag() = "wagging"
                }
                Dog.members
            """.trimIndent()) as String
            result shouldContain "Extensions:"
            result shouldContain "fun wag()"
        }

        test("struct extension method shown in Extensions section") {
            val result = eval("""
                struct Point(let x: Double, let y: Double)
                extend Point {
                    fun magnitude(): Double = 0.0
                }
                Point.members
            """.trimIndent()) as String
            result shouldContain "Extensions:"
            result shouldContain "fun magnitude(): Double"
        }

        test("extension method separate from declared methods") {
            val result = eval("""
                class Cat(let name: String) {
                    fun purr() = "prrr"
                }
                extend Cat {
                    fun hiss() = "hssss"
                }
                Cat.members
            """.trimIndent()) as String
            result shouldContain "Methods:"
            result shouldContain "fun purr()"
            result shouldContain "Extensions:"
            result shouldContain "fun hiss()"
        }
    }

    // ====================================================================
    // 7. Constraints and Defaults
    // ====================================================================

    context("constraints and defaults in .members") {

        test("range constraint in constructor") {
            val result = eval("""
                class Level(let n: Int 1..100)
                Level.members
            """.trimIndent()) as String
            result shouldContain "Level(n: Int 1..100)"
            result shouldContain "let n: Int 1..100"
        }

        test("comparison constraint in constructor") {
            val result = eval("""
                class Account(let balance: Double >= 0.0)
                Account.members
            """.trimIndent()) as String
            result shouldContain "Account(balance: Double >= 0.0)"
        }

        test("exclusive range constraint") {
            val result = eval("""
                class Ratio(let value: Double 0.0..<1.0)
                Ratio.members
            """.trimIndent()) as String
            result shouldContain "Ratio(value: Double 0.0..<1.0)"
        }

        test("integer default value") {
            val result = eval("""
                class Box(let size: Int = 10)
                Box.members
            """.trimIndent()) as String
            result shouldContain "Box(size: Int = 10)"
        }

        test("string default value") {
            val result = eval("""
                class Msg(let text: String = "hello")
                Msg.members
            """.trimIndent()) as String
            result shouldContain """Msg(text: String = "hello")"""
        }

        test("boolean default value") {
            val result = eval("""
                class Toggle(let active: Bool = true)
                Toggle.members
            """.trimIndent()) as String
            result shouldContain "Toggle(active: Bool = true)"
        }

        test("nil default value") {
            val result = eval("""
                class Optional(let value: String? = nil)
                Optional.members
            """.trimIndent()) as String
            result shouldContain "Optional(value: String? = nil)"
        }

        test("DPEC default value") {
            val result = eval("""
                enum Status { Active, Inactive }
                class Item(let status: Status = .Active)
                Item.members
            """.trimIndent()) as String
            result shouldContain "Item(status: Status = .Active)"
        }

        test("method with mixed defaults and constraints") {
            val result = eval("""
                class Engine {
                    fun throttle(power: Int 0..100, smooth: Bool = true): Int = 0
                }
                Engine.members
            """.trimIndent()) as String
            result shouldContain "fun throttle(power: Int 0..100, smooth: Bool = true): Int"
        }
    }

    // ====================================================================
    // 8. Generic Types
    // ====================================================================

    context("generic types in .members") {

        test("method with generic parameter type") {
            val result = eval("""
                class Container {
                    fun items(): List<String> = []
                }
                Container.members
            """.trimIndent()) as String
            result shouldContain "fun items(): List<String>"
        }

        test("constructor with generic type") {
            val result = eval("""
                class Holder(let items: List<Int>)
                Holder.members
            """.trimIndent()) as String
            result shouldContain "Holder(items: List<Int>)"
            result shouldContain "let items: List<Int>"
        }

        test("nested generic types") {
            val result = eval("""
                class Registry {
                    fun lookup(key: String): Map<String, List<Int>> { return [] }
                }
                Registry.members
            """.trimIndent()) as String
            result shouldContain "fun lookup(key: String): Map<String, List<Int>>"
        }

        test("nullable generic type") {
            val result = eval("""
                class Cache(let data: List<String>? = nil)
                Cache.members
            """.trimIndent()) as String
            result shouldContain "Cache(data: List<String>? = nil)"
            result shouldContain "let data: List<String>?"
        }
    }

    // ====================================================================
    // 9. Say Integration
    // ====================================================================

    context(".members with say") {

        test("say class .members") {
            val output = run("""
                class Dog(let name: String) {
                    fun bark() = "Woof!"
                }
                say Dog.members
            """.trimIndent())
            output shouldContain "class Dog"
            output shouldContain "Constructors:"
            output shouldContain "Dog(name: String)"
            output shouldContain "Properties:"
            output shouldContain "let name: String"
            output shouldContain "Methods:"
            output shouldContain "fun bark()"
        }

        test("say enum .members") {
            val output = run("""
                enum Color { Red, Green, Blue }
                say Color.members
            """.trimIndent())
            output shouldContain "enum Color"
            output shouldContain "Red, Green, Blue"
        }

        test("say trait .members") {
            val output = run("""
                trait Hashable {
                    fun hash(): Int
                }
                say Hashable.members
            """.trimIndent())
            output shouldContain "trait Hashable"
            output shouldContain "fun hash(): Int"
        }
    }

    // ====================================================================
    // 10. Section Ordering
    // ====================================================================

    context("section ordering in .members output") {

        test("sections appear in correct order for class") {
            val result = eval("""
                class Widget(let id: Int) {
                    var label: String = "default"
                    fun render() = "widget"
                    enum State { Ready, Busy }
                    static {
                        fun create(id: Int) = Widget(id)
                    }
                }
                Widget.members
            """.trimIndent()) as String

            // Verify ordering: header, constructors, properties, methods, enums, static
            val headerIdx = result.indexOf("class Widget")
            val ctorIdx = result.indexOf("Constructors:")
            val propsIdx = result.indexOf("Properties:")
            val methodsIdx = result.indexOf("Methods:")
            val enumsIdx = result.indexOf("Enums:")
            val staticIdx = result.indexOf("Static:")

            (headerIdx < ctorIdx) shouldBe true
            (ctorIdx < propsIdx) shouldBe true
            (propsIdx < methodsIdx) shouldBe true
            (methodsIdx < enumsIdx) shouldBe true
            (enumsIdx < staticIdx) shouldBe true
        }
    }

    // ====================================================================
    // 11. Error Conditions
    // ====================================================================

    context(".members error conditions") {

        test(".members on class instance throws error") {
            val error = runExpectingError("""
                class Dog(let name: String)
                let d = Dog("Rex")
                d.members
            """.trimIndent())
            error shouldContain ".members is only available on class, struct, trait, and enum definitions"
        }

        test(".members on struct instance throws error") {
            val error = runExpectingError("""
                struct Point(let x: Double, let y: Double)
                let p = Point(1.0, 2.0)
                p.members
            """.trimIndent())
            error shouldContain ".members is only available on class, struct, trait, and enum definitions"
        }

        test(".members on integer throws error") {
            val error = runExpectingError("""
                let x = 42
                x.members
            """.trimIndent())
            error shouldContain ".members is only available on class, struct, trait, and enum definitions"
        }

        test(".members on string throws error") {
            val error = runExpectingError("""
                let s = "hello"
                s.members
            """.trimIndent())
            error shouldContain ".members is only available on class, struct, trait, and enum definitions"
        }

        test(".members on nil throws NullPointerError") {
            val error = runExpectingError("""
                let x: String? = nil
                x.members
            """.trimIndent())
            error shouldContain "nil"
        }

        test("nil-safe .members returns nil") {
            val result = eval("""
                let x: String? = nil
                x?.members
            """.trimIndent())
            result shouldBe null
        }
    }

    // ====================================================================
    // 12. Indentation and Formatting
    // ====================================================================

    context("formatting details") {

        test("properties indented with two spaces") {
            val result = eval("""
                class Dog(let name: String)
                Dog.members
            """.trimIndent()) as String
            result shouldContain "  let name: String"
        }

        test("methods indented with two spaces") {
            val result = eval("""
                class Dog {
                    fun bark() = "Woof"
                }
                Dog.members
            """.trimIndent()) as String
            result shouldContain "  fun bark()"
        }

        test("header is first line") {
            val result = eval("""
                class Dog(let name: String)
                Dog.members
            """.trimIndent()) as String
            result.lines().first() shouldBe "class Dog"
        }

        test("struct header is first line") {
            val result = eval("""
                struct Vec(let x: Double)
                Vec.members
            """.trimIndent()) as String
            result.lines().first() shouldBe "struct Vec"
        }

        test("empty sections are omitted") {
            val result = eval("""
                class Empty { }
                Empty.members
            """.trimIndent()) as String
            // Only the header line
            result.trim() shouldBe "class Empty"
        }

        test("no trailing newline") {
            val result = eval("""
                class Dog(let name: String)
                Dog.members
            """.trimIndent()) as String
            (result == result.trimEnd()) shouldBe true
        }
    }

    // ====================================================================
    // 13. Multiple Methods (Overload Display)
    // ====================================================================

    context("multiple methods displayed") {

        test("multiple methods each on own line") {
            val result = eval("""
                class Math {
                    fun add(a: Int, b: Int): Int = a + b
                    fun sub(a: Int, b: Int): Int = a - b
                    fun mul(a: Int, b: Int): Int = a * b
                }
                Math.members
            """.trimIndent()) as String
            result shouldContain "fun add(a: Int, b: Int): Int"
            result shouldContain "fun sub(a: Int, b: Int): Int"
            result shouldContain "fun mul(a: Int, b: Int): Int"
        }

        test("methods and properties don't mix sections") {
            val result = eval("""
                class Item(let name: String) {
                    var count: Int = 0
                    fun increment() { }
                    fun decrement() { }
                }
                Item.members
            """.trimIndent()) as String

            // Properties section should have properties
            val propsSection = result.substringAfter("Properties:").substringBefore("\n\n")
            propsSection shouldContain "let name"
            propsSection shouldContain "var count"
            propsSection shouldNotContain "fun "
        }
    }

    // ====================================================================
    // 14. Edge Cases
    // ====================================================================

    context("edge cases") {

        test("class with only methods, no constructor") {
            val result = eval("""
                class Util {
                    fun help() = "help"
                }
                Util.members
            """.trimIndent()) as String
            result shouldContain "class Util"
            result shouldNotContain "Constructors:"
            result shouldNotContain "Properties:"
            result shouldContain "Methods:"
            result shouldContain "fun help()"
        }

        test("class with only static, no instance members") {
            val result = eval("""
                class Constants {
                    static {
                        let PI: Double = 3.14159
                    }
                }
                Constants.members
            """.trimIndent()) as String
            result shouldContain "class Constants"
            result shouldNotContain "Constructors:"
            result shouldNotContain "Properties:"
            result shouldNotContain "Methods:"
            result shouldContain "Static:"
            result shouldContain "let PI: Double"
        }

        test("enum with only constants, no methods") {
            val result = eval("""
                enum Suit { Hearts, Diamonds, Clubs, Spades }
                Suit.members
            """.trimIndent()) as String
            result shouldContain "enum Suit"
            result shouldContain "Hearts, Diamonds, Clubs, Spades"
            result shouldNotContain "Methods:"
            result shouldNotContain "Static:"
        }

        test("method with no parameters and no return type") {
            val result = eval("""
                class Noop {
                    fun doNothing() { }
                }
                Noop.members
            """.trimIndent()) as String
            result shouldContain "fun doNothing()"
        }

        test("method with many parameters") {
            val result = eval("""
                class Builder {
                    fun build(name: String, width: Int, height: Int, color: String = "black"): String = "done"
                }
                Builder.members
            """.trimIndent()) as String
            result shouldContain "fun build(name: String, width: Int, height: Int, color: String = \"black\"): String"
        }

        test("struct with no methods just shows constructor and properties") {
            val result = eval("""
                struct Pair(let first: Int, let second: Int)
                Pair.members
            """.trimIndent()) as String
            result shouldContain "struct Pair"
            result shouldContain "Constructors:"
            result shouldContain "Properties:"
            result shouldNotContain "Methods:"
        }
    }

    // ====================================================================
    // 15. .members is a reserved name
    // ====================================================================

    context(".members is reserved") {

        test(".members on class takes precedence over user property") {
            // Even if someone names a property 'members', the universal
            // .members reflection property takes precedence
            val result = eval("""
                class Team(let members: Int = 5)
                Team.members
            """.trimIndent()) as String
            // Should return the reflection output, not the integer 5
            result shouldContain "class Team"
            result shouldContain "Constructors:"
        }
    }
})