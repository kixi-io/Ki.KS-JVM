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
 * Comprehensive tests for KS inheritance and trait system.
 *
 * Covers:
 *   - Trait declaration (abstract methods, default methods)
 *   - Trait inheritance (trait extends trait)
 *   - Class single inheritance (method override, property inheritance)
 *   - Class trait implementation (conformance, method dispatch)
 *   - Struct trait implementation (conformance validation, method dispatch)
 *   - `is` / `!is` type checking for class hierarchy and traits
 *   - Type extensions (`extend`)
 *   - Multiple trait implementation
 *   - Edge cases (diamond traits, override priority, etc.)
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.InheritanceAndTraitsTest"
 */
class InheritanceAndTraitsTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * Execute KS source code and capture output.
     * Returns the captured stdout as a string.
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
     * Execute KS source and expect a RuntimeError.
     * Returns the error message.
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
            throw AssertionError("Expected an error to be thrown, but execution completed successfully. Output: ${output.toString().trim()}")
        } catch (e: AssertionError) {
            throw e // re-throw our own assertion
        } catch (e: Exception) {
            return e.message ?: e.toString()
        }
    }

    // ====================================================================
    // 1. Basic Trait Declaration
    // ====================================================================

    context("Trait declaration") {

        test("trait with abstract method") {
            // Trait can be declared with abstract methods (no body)
            val result = run("""
                trait Greeter {
                    fun greet(): String
                }

                class Hello: Greeter {
                    fun greet(): String = "Hello!"
                }

                let h = Hello()
                say h.greet()
            """.trimIndent())
            result shouldBe "Hello!"
        }

        test("trait with default method") {
            val result = run("""
                trait Greeter {
                    fun greet(): String = "Hello from default!"
                }

                class Hello: Greeter { }

                let h = Hello()
                say h.greet()
            """.trimIndent())
            result shouldBe "Hello from default!"
        }

        test("class overrides trait default method") {
            val result = run("""
                trait Greeter {
                    fun greet(): String = "Hello from default!"
                }

                class CustomGreeter: Greeter {
                    fun greet(): String = "Custom hello!"
                }

                let g = CustomGreeter()
                say g.greet()
            """.trimIndent())
            result shouldBe "Custom hello!"
        }
    }

    // ====================================================================
    // 2. Trait Inheritance (trait extends trait)
    // ====================================================================

    context("Trait inheritance") {

        test("trait extends another trait - inherits default methods") {
            val result = run("""
                trait Named {
                    fun name(): String = "Unknown"
                }

                trait Describable: Named {
                    fun describe(): String = "I am Named"
                }

                class Thing: Describable { }

                let t = Thing()
                say t.name()
                say t.describe()
            """.trimIndent())
            result shouldBe "Unknown\nI am Named"
        }

        test("trait extends trait - abstract method propagation") {
            // If a super-trait has an abstract method, the sub-trait
            // inherits that requirement unless it provides a default.
            val result = run("""
                trait Named {
                    fun name(): String
                }

                trait Describable: Named {
                    fun describe(): String = "described"
                }

                class Thing: Describable {
                    fun name(): String = "MyThing"
                }

                let t = Thing()
                say t.name()
                say t.describe()
            """.trimIndent())
            result shouldBe "MyThing\ndescribed"
        }

        test("super-trait method accessible through sub-trait") {
            val result = run("""
                trait A {
                    fun fromA(): String = "A"
                }

                trait B: A {
                    fun fromB(): String = "B"
                }

                trait C: B {
                    fun fromC(): String = "C"
                }

                class Impl: C { }

                let x = Impl()
                say x.fromA()
                say x.fromB()
                say x.fromC()
            """.trimIndent())
            result shouldBe "A\nB\nC"
        }
    }

    // ====================================================================
    // 3. Class Implementing Traits
    // ====================================================================

    context("Class trait implementation") {

        test("class implements trait with abstract method") {
            val result = run("""
                trait Animal {
                    fun speak(): String
                }

                class Dog: Animal {
                    fun speak(): String = "Woof!"
                }

                let d = Dog()
                say d.speak()
            """.trimIndent())
            result shouldBe "Woof!"
        }

        test("class implements multiple traits") {
            val result = run("""
                trait Swimmer {
                    fun swim(): String = "swimming"
                }

                trait Runner {
                    fun run(): String = "running"
                }

                class Triathlete: Swimmer, Runner { }

                let t = Triathlete()
                say t.swim()
                say t.run()
            """.trimIndent())
            result shouldBe "swimming\nrunning"
        }

        test("class method takes priority over trait default") {
            val result = run("""
                trait Sayer {
                    fun speak(): String = "default"
                }

                class Loud: Sayer {
                    fun speak(): String = "LOUD!"
                }

                let s = Loud()
                say s.speak()
            """.trimIndent())
            result shouldBe "LOUD!"
        }

        // Diagnostic test — calls validateClassTraitConformance via reflection
        test("class missing all abstract trait methods should error") {
            val msg = runExpectingError("""
                trait Animal {
                    fun speak(): String
                }
                class Silent: Animal { }
            """.trimIndent())
            msg shouldContain "does not implement"
            msg shouldContain "speak"
        }

        test("class missing abstract trait method should error") {
            val msg = runExpectingError("""
                trait Animal {
                    fun speak(): String
                }

                class Silent: Animal { }
            """.trimIndent())
            msg shouldContain "does not implement"
        }

        test("superclass method satisfies trait abstract requirement") {
            // Dog inherits speak() from Animal, which satisfies Vocal's requirement
            val result = run("""
                trait Vocal {
                    fun speak(): String
                }

                class Animal {
                    fun speak(): String = "..."
                }

                class Dog: Animal, Vocal { }

                let d = Dog()
                say d.speak()
            """.trimIndent())
            result shouldBe "..."
        }

        test("class implementing trait with multiple abstract methods - partial impl errors") {
            val msg = runExpectingError("""
                trait Vehicle {
                    fun speed(): Int
                    fun fuel(): String
                }

                class Car: Vehicle {
                    fun speed(): Int = 100
                }
            """.trimIndent())
            msg shouldContain "does not implement"
            msg shouldContain "fuel"
        }
    }

    // ====================================================================
    // 4. Struct Implementing Traits
    // ====================================================================

    context("Struct trait implementation") {

        test("struct implements trait with abstract method") {
            val result = run("""
                trait Printable {
                    fun display(): String
                }

                struct Point(let x: Double, let y: Double): Printable {
                    fun display(): String = "point"
                }

                let p = Point(1.0, 2.0)
                say p.display()
            """.trimIndent())
            result shouldBe "point"
        }

        test("struct uses trait default method") {
            val result = run("""
                trait HasOrigin {
                    fun origin(): String = "0,0"
                }

                struct Vec(let x: Double, let y: Double): HasOrigin { }

                let v = Vec(3.0, 4.0)
                say v.origin()
            """.trimIndent())
            result shouldBe "0,0"
        }

        test("struct overrides trait default method") {
            val result = run("""
                trait HasOrigin {
                    fun origin(): String = "0,0"
                }

                struct Vec(let x: Double, let y: Double): HasOrigin {
                    fun origin(): String = "custom origin"
                }

                let v = Vec(3.0, 4.0)
                say v.origin()
            """.trimIndent())
            result shouldBe "custom origin"
        }

        test("struct missing abstract trait method errors at declaration") {
            val msg = runExpectingError("""
                trait Measurable {
                    fun measure(): Int
                }

                struct Box(let w: Int, let h: Int): Measurable { }
            """.trimIndent())
            msg shouldContain "does not implement"
            msg shouldContain "measure"
        }

        test("struct implements multiple traits") {
            val result = run("""
                trait HasArea {
                    fun area(): String = "no area"
                }

                trait HasPerimeter {
                    fun perimeter(): String = "no perimeter"
                }

                struct Rect(let w: Int, let h: Int): HasArea, HasPerimeter { }

                let r = Rect(3, 4)
                say r.area()
                say r.perimeter()
            """.trimIndent())
            result shouldBe "no area\nno perimeter"
        }

        test("struct cannot extend a class") {
            val msg = runExpectingError("""
                class Base { }

                struct Bad(let x: Int): Base { }
            """.trimIndent())
            msg shouldContain "cannot extend class"
        }
    }

    // ====================================================================
    // 5. Class Single Inheritance
    // ====================================================================

    context("Class inheritance") {

        test("subclass inherits methods from superclass") {
            val result = run("""
                class Animal {
                    fun speak(): String = "..."
                }

                class Dog: Animal { }

                let d = Dog()
                say d.speak()
            """.trimIndent())
            result shouldBe "..."
        }

        test("subclass overrides superclass method") {
            val result = run("""
                class Animal {
                    fun speak(): String = "..."
                }

                class Dog: Animal {
                    fun speak(): String = "Woof!"
                }

                let d = Dog()
                say d.speak()
            """.trimIndent())
            result shouldBe "Woof!"
        }

        test("subclass with own constructor params and methods") {
            val result = run("""
                class Vehicle {
                    fun kind(): String = "vehicle"
                }

                class Car(let brand: String): Vehicle {
                    fun describe(): String = brand
                }

                let c = Car("Toyota")
                say c.kind()
                say c.describe()
                say c.brand
            """.trimIndent())
            result shouldBe "vehicle\nToyota\nToyota"
        }

        test("multi-level inheritance - method lookup") {
            val result = run("""
                class A {
                    fun fromA(): String = "A"
                }

                class B: A {
                    fun fromB(): String = "B"
                }

                class C: B {
                    fun fromC(): String = "C"
                }

                let c = C()
                say c.fromA()
                say c.fromB()
                say c.fromC()
            """.trimIndent())
            result shouldBe "A\nB\nC"
        }

        test("method override in middle of chain") {
            val result = run("""
                class A {
                    fun greet(): String = "Hello from A"
                }

                class B: A {
                    fun greet(): String = "Hello from B"
                }

                class C: B { }

                let c = C()
                say c.greet()
            """.trimIndent())
            result shouldBe "Hello from B"
        }

        test("subclass inherits superclass body properties") {
            val result = run("""
                class Animal {
                    var sound = "..."
                    let category = "animal"
                }

                class Dog: Animal {
                    fun describe(): String = sound
                }

                let d = Dog()
                say d.sound
                say d.category
                say d.describe()
            """.trimIndent())
            result shouldBe "...\nanimal\n..."
        }

        test("subclass can mutate inherited mutable body property") {
            val result = run("""
                class Base {
                    var counter = 0
                }

                class Child: Base { }

                let c = Child()
                say c.counter
                c.counter = 42
                say c.counter
            """.trimIndent())
            result shouldBe "0\n42"
        }

        test("subclass body property overrides superclass body property") {
            val result = run("""
                class Animal {
                    var sound = "..."
                }

                class Dog: Animal {
                    var sound = "Woof"
                }

                let d = Dog()
                say d.sound
            """.trimIndent())
            result shouldBe "Woof"
        }

        test("multi-level body property inheritance") {
            val result = run("""
                class A {
                    var fromA = "A-prop"
                }

                class B: A {
                    var fromB = "B-prop"
                }

                class C: B {
                    var fromC = "C-prop"
                }

                let c = C()
                say c.fromA
                say c.fromB
                say c.fromC
            """.trimIndent())
            result shouldBe "A-prop\nB-prop\nC-prop"
        }

        test("inherited immutable body property cannot be assigned") {
            val msg = runExpectingError("""
                class Base {
                    let locked = "frozen"
                }

                class Child: Base { }

                let c = Child()
                c.locked = "changed"
            """.trimIndent())
            msg shouldContain "locked"
        }
    }

    // ====================================================================
    // 6. Class with Superclass + Traits
    // ====================================================================

    context("Class with superclass and traits") {

        test("class inherits from superclass and implements trait") {
            val result = run("""
                trait Speakable {
                    fun speak(): String = "..."
                }

                class Animal {
                    fun kind(): String = "animal"
                }

                class Dog: Animal, Speakable {
                    fun speak(): String = "Woof!"
                }

                let d = Dog()
                say d.kind()
                say d.speak()
            """.trimIndent())
            result shouldBe "animal\nWoof!"
        }

        test("class method overrides both superclass and trait") {
            val result = run("""
                trait Describable {
                    fun describe(): String = "trait default"
                }

                class Base {
                    fun describe(): String = "base"
                }

                class Child: Base, Describable {
                    fun describe(): String = "child"
                }

                let c = Child()
                say c.describe()
            """.trimIndent())
            result shouldBe "child"
        }
    }

    // ====================================================================
    // 7. `is` / `!is` Type Checking
    // ====================================================================

    context("Type checking with is/!is") {

        test("is checks direct class") {
            val result = run("""
                class Dog { }

                let d = Dog()
                say d is Dog
            """.trimIndent())
            result shouldBe "true"
        }

        test("is checks superclass") {
            val result = run("""
                class Animal { }
                class Dog: Animal { }

                let d = Dog()
                say d is Animal
                say d is Dog
            """.trimIndent())
            result shouldBe "true\ntrue"
        }

        test("is checks multi-level superclass") {
            val result = run("""
                class A { }
                class B: A { }
                class C: B { }

                let c = C()
                say c is A
                say c is B
                say c is C
            """.trimIndent())
            result shouldBe "true\ntrue\ntrue"
        }

        test("is checks trait conformance on class") {
            val result = run("""
                trait Flyable {
                    fun fly(): String = "flying"
                }

                class Bird: Flyable { }

                let b = Bird()
                say b is Flyable
            """.trimIndent())
            result shouldBe "true"
        }

        test("is checks trait conformance on struct") {
            val result = run("""
                trait Measurable {
                    fun size(): Int = 0
                }

                struct Box(let w: Int, let h: Int): Measurable { }

                let b = Box(3, 4)
                say b is Measurable
            """.trimIndent())
            result shouldBe "true"
        }

        test("is returns false for unrelated types") {
            val result = run("""
                class Cat { }
                class Dog { }

                let d = Dog()
                say d is Cat
            """.trimIndent())
            result shouldBe "false"
        }

        test("!is negation works") {
            val result = run("""
                class Cat { }
                class Dog { }

                let d = Dog()
                say d !is Cat
                say d !is Dog
            """.trimIndent())
            result shouldBe "true\nfalse"
        }

        test("is checks inherited trait conformance") {
            // If Dog: Animal, and Animal implements Breathable,
            // then Dog instances should also be Breathable
            val result = run("""
                trait Breathable {
                    fun breathe(): String = "breathing"
                }

                class Animal: Breathable { }
                class Dog: Animal { }

                let d = Dog()
                say d is Breathable
                say d is Animal
            """.trimIndent())
            result shouldBe "true\ntrue"
        }

        test("is checks super-trait via sub-trait") {
            val result = run("""
                trait A {
                    fun a(): String = "a"
                }

                trait B: A {
                    fun b(): String = "b"
                }

                class Impl: B { }

                let x = Impl()
                say x is A
                say x is B
            """.trimIndent())
            result shouldBe "true\ntrue"
        }
    }

    // ====================================================================
    // 8. when Expression with Type Matching
    // ====================================================================

    context("when expression with type matching") {

        test("when with is matcher dispatches on class type") {
            val result = run("""
                class Animal { }
                class Dog: Animal { }
                class Cat: Animal { }

                let pet = Dog()
                let result = when pet {
                    is Dog -> "dog"
                    is Cat -> "cat"
                    else -> "unknown"
                }
                say result
            """.trimIndent())
            result shouldBe "dog"
        }

        test("when with is matcher checks superclass") {
            val result = run("""
                class Animal { }
                class Dog: Animal { }

                let pet = Dog()
                let result = when pet {
                    is Animal -> "animal"
                    else -> "unknown"
                }
                say result
            """.trimIndent())
            result shouldBe "animal"
        }
    }

    // ====================================================================
    // 9. Type Extensions
    // ====================================================================

    context("Type extensions") {

        test("extend class with new method") {
            val result = run("""
                class Dog {
                    fun speak(): String = "Woof"
                }

                extend Dog {
                    fun shout(): String = "WOOF!"
                }

                let d = Dog()
                say d.speak()
                say d.shout()
            """.trimIndent())
            result shouldBe "Woof\nWOOF!"
        }

        test("extend struct with new method") {
            val result = run("""
                struct Point(let x: Double, let y: Double) { }

                extend Point {
                    fun label(): String = "point"
                }

                let p = Point(1.0, 2.0)
                say p.label()
            """.trimIndent())
            result shouldBe "point"
        }

        test("extend trait with new default method") {
            val result = run("""
                trait Speakable {
                    fun speak(): String
                }

                extend trait Speakable {
                    fun shout(): String = "LOUD"
                }

                class Dog: Speakable {
                    fun speak(): String = "woof"
                }

                let d = Dog()
                say d.speak()
                say d.shout()
            """.trimIndent())
            result shouldBe "woof\nLOUD"
        }

        test("extension method overrides existing") {
            val result = run("""
                class Thing {
                    fun name(): String = "original"
                }

                extend Thing {
                    fun name(): String = "extended"
                }

                let t = Thing()
                say t.name()
            """.trimIndent())
            result shouldBe "extended"
        }
    }

    // ====================================================================
    // 10. Static Members
    // ====================================================================

    context("Static members in class hierarchy") {

        test("class static method") {
            val result = run("""
                class Counter {
                    static {
                        fun zero(): Int = 0
                    }
                }

                say Counter.zero()
            """.trimIndent())
            result shouldBe "0"
        }

        test("class static variable") {
            val result = run("""
                class Config {
                    static {
                        let version = "1.0"
                    }
                }

                say Config.version
            """.trimIndent())
            result shouldBe "1.0"
        }
    }

    // ====================================================================
    // 11. Struct Value Semantics with Traits
    // ====================================================================

    context("Struct value semantics with traits") {

        test("struct copy preserves trait method access") {
            val result = run("""
                trait Labelable {
                    fun label(): String = "labeled"
                }

                struct Item(let name: String): Labelable { }

                let a = Item("widget")
                let b = a.copy()
                say b.label()
                say b.name
            """.trimIndent())
            result shouldBe "labeled\nwidget"
        }

        test("struct equality unaffected by trait methods") {
            val result = run("""
                trait T {
                    fun info(): String = "info"
                }

                struct Pair(let x: Int, let y: Int): T { }

                let a = Pair(1, 2)
                let b = Pair(1, 2)
                let c = Pair(1, 3)
                say a == b
                say a == c
            """.trimIndent())
            result shouldBe "true\nfalse"
        }
    }

    // ====================================================================
    // 12. Edge Cases
    // ====================================================================

    context("Edge cases") {

        test("trait with no methods") {
            val result = run("""
                trait Marker { }

                class Tagged: Marker { }

                let t = Tagged()
                say t is Marker
            """.trimIndent())
            result shouldBe "true"
        }

        test("deeply nested trait hierarchy") {
            val result = run("""
                trait L1 { fun l1(): String = "L1" }
                trait L2: L1 { fun l2(): String = "L2" }
                trait L3: L2 { fun l3(): String = "L3" }
                trait L4: L3 { fun l4(): String = "L4" }

                class Deep: L4 { }

                let d = Deep()
                say d.l1()
                say d.l2()
                say d.l3()
                say d.l4()
                say d is L1
                say d is L4
            """.trimIndent())
            result shouldBe "L1\nL2\nL3\nL4\ntrue\ntrue"
        }

        test("class own method hides superclass and trait method of same name") {
            val result = run("""
                trait T {
                    fun name(): String = "trait"
                }

                class Base {
                    fun name(): String = "base"
                }

                class Child: Base, T {
                    fun name(): String = "child"
                }

                let c = Child()
                say c.name()
            """.trimIndent())
            result shouldBe "child"
        }

        test("implicit this accesses inherited method") {
            val result = run("""
                class Base {
                    fun baseInfo(): String = "base"
                }

                class Child: Base {
                    fun combined(): String = baseInfo()
                }

                let c = Child()
                say c.combined()
            """.trimIndent())
            result shouldBe "base"
        }

        test("bound method from trait default") {
            val result = run("""
                trait Greetable {
                    fun greet(): String = "hi"
                }

                class Person: Greetable { }

                let p = Person()
                let g = p.greet
                say g()
            """.trimIndent())
            result shouldBe "hi"
        }
    }

    // ====================================================================
    // 13. Struct with Trait and Methods Accessing Properties
    // ====================================================================

    context("Struct methods accessing properties via this") {

        test("struct own method accesses constructor property") {
            val result = run("""
                struct Point(let x: Double, let y: Double) {
                    fun sum(): Double = this.x + this.y
                }

                let p = Point(3.0, 4.0)
                say p.sum()
            """.trimIndent())
            result shouldBe "7"
        }
    }
})