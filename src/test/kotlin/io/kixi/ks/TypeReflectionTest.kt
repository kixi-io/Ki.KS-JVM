package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Tests for the `.type` and `.typeName` reflection properties.
 *
 * Every KS value supports `.type` (returns a KSType object) and `.typeName`
 * (returns the type name as a String). These are reserved — they cannot be
 * shadowed by user-defined properties.
 *
 * ## Value-level reflection
 *
 *   `42.type`       → Int           (KSType with name "Int")
 *   `42.typeName`   → "Int"         (String)
 *
 * ## Meta-type reflection
 *
 * When `.type` is called on a type itself (a class, struct, trait, enum, or
 * function value), the result uses `keyword name` format:
 *
 *   `Dog.type`       → class Dog     (user-defined class)
 *   `String.type`    → class String  (built-in type)
 *   `Point.type`     → struct Point
 *   `Printable.type` → trait Printable
 *   `Color.type`     → enum Color
 *   `greet.type`     → fun greet
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.TypeReflectionTest"
 */
class TypeReflectionTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * Execute KS source and capture stdout output (from `say`).
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
        val tokens = Lexer(source).tokenize()
        val program = Parser(tokens).parse()
        val interpreter = Interpreter(runtime)
        interpreter.executeProgram(program)
        return output.toString().trim()
    }

    /**
     * Execute KS source and return the raw result of the last expression.
     */
    fun eval(source: String): Any? {
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = PrintWriter(StringWriter(), true),
            errorWriter = PrintWriter(StringWriter(), true),
            debugMode = false
        )
        val tokens = Lexer(source).tokenize()
        val program = Parser(tokens).parse()
        val interpreter = Interpreter(runtime)
        return interpreter.executeProgram(program)
    }

    // ====================================================================
    // Primitive Value .type
    // ====================================================================

    context("primitive value .type") {

        test("Int literal .type") {
            run("say 42.type") shouldBe "Int"
        }

        test("Int literal .typeName") {
            run("say 42.typeName") shouldBe "Int"
        }

        test("negative Int .type") {
            run("say (-7).type") shouldBe "Int"
        }

        test("Long literal .type") {
            run("say 42L.type") shouldBe "Long"
        }

        test("Float literal .type") {
            run("say 3.14f.type") shouldBe "Float"
        }

        test("Double literal .type") {
            run("say 3.14d.type") shouldBe "Double"
        }

        test("Dec literal .type") {
            run("say 3.14BD.type") shouldBe "Dec"
        }

        test("String literal .type") {
            run("""say "hello".type""") shouldBe "String"
        }

        test("Char literal .type") {
            run("say 'A'.type") shouldBe "Char"
        }

        test("Bool true .type") {
            run("say true.type") shouldBe "Bool"
        }

        test("Bool false .type") {
            run("say false.type") shouldBe "Bool"
        }

        test("nil .type") {
            run("say nil.type") shouldBe "Nil"
        }
    }

    // ====================================================================
    // Collection Value .type
    // ====================================================================

    context("collection value .type") {

        test("List literal .type") {
            run("say [1, 2, 3].type") shouldBe "List"
        }

        test("empty List .type") {
            run("say [].type") shouldBe "List"
        }

        test("Map literal .type") {
            run("""say ["a" = 1, "b" = 2].type""") shouldBe "Map"
        }

        test("Range .type") {
            run("say (1..10).type") shouldBe "Range"
        }
    }

    // ====================================================================
    // Variable .type
    // ====================================================================

    context("variable .type") {

        test("var Int .type") {
            run("""
                var x = 42
                say x.type
            """.trimIndent()) shouldBe "Int"
        }

        test("let String .type") {
            run("""
                let name = "Alice"
                say name.type
            """.trimIndent()) shouldBe "String"
        }

        test("let Bool .typeName") {
            run("""
                let flag = true
                say flag.typeName
            """.trimIndent()) shouldBe "Bool"
        }

        test("typed variable .type reflects runtime value") {
            run("""
                var x = 42
                say x.type
            """.trimIndent()) shouldBe "Int"
        }

        test("variable holding a List") {
            run("""
                let items = [10, 20, 30]
                say items.type
            """.trimIndent()) shouldBe "List"
        }
    }

    // ====================================================================
    // Quantity .type (with qualifiedName)
    // ====================================================================

    context("Quantity .type") {

        test("Quantity literal .type includes unit") {
            run("say 5cm.type") shouldBe "Quantity(cm)"
        }

        test("Quantity .typeName is just Quantity") {
            run("say 5cm.typeName") shouldBe "Quantity"
        }

        test("currency Quantity .type") {
            run("say 23.50USD.type") shouldBe "Quantity(USD)"
        }

        test("prefix currency Quantity .type") {
            run("""say ${'$'}23.50.type""") shouldBe "Quantity(USD)"
        }

        test("temperature Quantity .type") {
            run("""say 25°C.type""") shouldBe "Quantity(°C)"
        }
    }

    // ====================================================================
    // Built-in Type Meta-Type .type (class keyword)
    // ====================================================================

    context("built-in type .type") {

        test("String.type") {
            run("say String.type") shouldBe "class String"
        }

        test("String.typeName") {
            run("say String.typeName") shouldBe "class String"
        }

        test("Int.type") {
            run("say Int.type") shouldBe "class Int"
        }

        test("Long.type") {
            run("say Long.type") shouldBe "class Long"
        }

        test("Float.type") {
            run("say Float.type") shouldBe "class Float"
        }

        test("Double.type") {
            run("say Double.type") shouldBe "class Double"
        }

        test("Dec.type") {
            run("say Dec.type") shouldBe "class Dec"
        }

        test("Bool.type") {
            run("say Bool.type") shouldBe "class Bool"
        }

        test("Char.type") {
            run("say Char.type") shouldBe "class Char"
        }

        test("List.type") {
            run("say List.type") shouldBe "class List"
        }

        test("Map.type") {
            run("say Map.type") shouldBe "class Map"
        }

        test("Range.type") {
            run("say Range.type") shouldBe "class Range"
        }

        test("Regex.type") {
            run("say Regex.type") shouldBe "class Regex"
        }

        test("Quantity.type") {
            run("say Quantity.type") shouldBe "class Quantity"
        }

        test("Version.type") {
            run("say Version.type") shouldBe "class Version"
        }

        test("Nil.type") {
            run("say Nil.type") shouldBe "class Nil"
        }

        test("Any.type") {
            run("say Any.type") shouldBe "class Any"
        }
    }

    // ====================================================================
    // User-Defined class .type
    // ====================================================================

    context("class .type") {

        test("class meta-type .type") {
            run("""
                class Dog { }
                say Dog.type
            """.trimIndent()) shouldBe "class Dog"
        }

        test("class meta-type .typeName") {
            run("""
                class Dog { }
                say Dog.typeName
            """.trimIndent()) shouldBe "class Dog"
        }

        test("class instance .type is the class name") {
            run("""
                class Dog { }
                let d = Dog()
                say d.type
            """.trimIndent()) shouldBe "Dog"
        }

        test("class instance .typeName") {
            run("""
                class Dog { }
                let d = Dog()
                say d.typeName
            """.trimIndent()) shouldBe "Dog"
        }

        test("class with constructor - instance .type") {
            run("""
                class Person(name: String, age: Int) { }
                let p = Person("Alice", 30)
                say p.type
            """.trimIndent()) shouldBe "Person"
        }

        test("subclass instance .type is the subclass") {
            run("""
                class Animal { }
                class Dog: Animal { }
                let d = Dog()
                say d.type
            """.trimIndent()) shouldBe "Dog"
        }

        test("class with methods - meta .type vs instance .type") {
            run("""
                class Calculator {
                    fun add(a: Int, b: Int): Int = a + b
                }
                say Calculator.type
                say Calculator().type
            """.trimIndent()) shouldBe "class Calculator\nCalculator"
        }
    }

    // ====================================================================
    // User-Defined struct .type
    // ====================================================================

    context("struct .type") {

        test("struct meta-type .type") {
            run("""
                struct Point(x: Int, y: Int)
                say Point.type
            """.trimIndent()) shouldBe "struct Point"
        }

        test("struct meta-type .typeName") {
            run("""
                struct Point(x: Int, y: Int)
                say Point.typeName
            """.trimIndent()) shouldBe "struct Point"
        }

        test("struct instance .type is the struct name") {
            run("""
                struct Point(x: Int, y: Int)
                let p = Point(3, 4)
                say p.type
            """.trimIndent()) shouldBe "Point"
        }

        test("struct instance .typeName") {
            run("""
                struct Point(x: Int, y: Int)
                let p = Point(3, 4)
                say p.typeName
            """.trimIndent()) shouldBe "Point"
        }

        test("struct with methods - meta vs instance") {
            run("""
                struct Vec2(x: Int, y: Int) {
                    fun sum(): Int = x + y
                }
                say Vec2.type
                say Vec2(3, 4).type
            """.trimIndent()) shouldBe "struct Vec2\nVec2"
        }
    }

    // ====================================================================
    // User-Defined trait .type
    // ====================================================================

    context("trait .type") {

        test("trait meta-type .type") {
            run("""
                trait Printable {
                    fun display(): String
                }
                say Printable.type
            """.trimIndent()) shouldBe "trait Printable"
        }

        test("trait meta-type .typeName") {
            run("""
                trait Printable {
                    fun display(): String
                }
                say Printable.typeName
            """.trimIndent()) shouldBe "trait Printable"
        }

        test("trait with default method - meta .type") {
            run("""
                trait Greetable {
                    fun greet(): String = "Hello!"
                }
                say Greetable.type
            """.trimIndent()) shouldBe "trait Greetable"
        }

        test("instance of class implementing trait has the class type") {
            run("""
                trait Speakable {
                    fun speak(): String
                }
                class Cat: Speakable {
                    fun speak(): String = "Meow"
                }
                let c = Cat()
                say c.type
            """.trimIndent()) shouldBe "Cat"
        }
    }

    // ====================================================================
    // User-Defined enum .type
    // ====================================================================

    context("enum .type") {

        test("enum meta-type .type") {
            run("""
                enum Color { Red, Green, Blue }
                say Color.type
            """.trimIndent()) shouldBe "enum Color"
        }

        test("enum meta-type .typeName") {
            run("""
                enum Color { Red, Green, Blue }
                say Color.typeName
            """.trimIndent()) shouldBe "enum Color"
        }

        test("enum constant .type is the enum name") {
            run("""
                enum Color { Red, Green, Blue }
                say Color.Red.type
            """.trimIndent()) shouldBe "Color"
        }

        test("enum constant .typeName") {
            run("""
                enum Color { Red, Green, Blue }
                say Color.Blue.typeName
            """.trimIndent()) shouldBe "Color"
        }

        test("enum with constructor args - constant .type") {
            run("""
                enum Planet(mass: Double, radius: Double) {
                    Earth(5.97d, 6.37d)
                    Mars(0.642d, 3.39d)
                }
                say Planet.Earth.type
            """.trimIndent()) shouldBe "Planet"
        }

        test("enum constant in variable .type") {
            run("""
                enum Direction { North, South, East, West }
                let dir = Direction.North
                say dir.type
            """.trimIndent()) shouldBe "Direction"
        }

        test("enum meta vs constant .type differ") {
            run("""
                enum Season { Spring, Summer, Autumn, Winter }
                say Season.type
                say Season.Spring.type
            """.trimIndent()) shouldBe "enum Season\nSeason"
        }
    }

    // ====================================================================
    // Function .type
    // ====================================================================

    context("fun .type") {

        test("named function .type") {
            run("""
                fun greet(): String = "hi"
                say greet.type
            """.trimIndent()) shouldBe "fun greet"
        }

        test("named function .typeName") {
            run("""
                fun add(a: Int, b: Int): Int = a + b
                say add.typeName
            """.trimIndent()) shouldBe "fun add"
        }

        test("function with body .type") {
            run("""
                fun factorial(n: Int): Int {
                    if (n <= 1) return 1
                    return n * factorial(n - 1)
                }
                say factorial.type
            """.trimIndent()) shouldBe "fun factorial"
        }

        test("function stored in variable .type") {
            run("""
                fun double(x: Int): Int = x * 2
                let f = double
                say f.type
            """.trimIndent()) shouldBe "fun double"
        }
    }

    // ====================================================================
    // .type Returns KSType Object (eval tests)
    // ====================================================================

    context(".type returns KSType object") {

        test("literal .type returns KSType instance") {
            val result = eval("42.type")
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "Int"
        }

        test("String literal .type returns KSType") {
            val result = eval(""""hello".type""")
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "String"
        }

        test("Quantity .type has qualifiedName") {
            val result = eval("5cm.type")
            result.shouldBeInstanceOf<KSType>()
            val ksType = result as KSType
            ksType.name shouldBe "Quantity"
            ksType.qualifiedName shouldBe "cm"
            ksType.toString() shouldBe "Quantity(cm)"
        }

        test("class meta .type returns KSType with keyword format") {
            val result = eval("""
                class Dog { }
                Dog.type
            """.trimIndent())
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "class Dog"
        }

        test("struct meta .type returns KSType with keyword format") {
            val result = eval("""
                struct Pair(a: Int, b: Int)
                Pair.type
            """.trimIndent())
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "struct Pair"
        }

        test("trait meta .type returns KSType with keyword format") {
            val result = eval("""
                trait Hashable { fun hash(): Int }
                Hashable.type
            """.trimIndent())
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "trait Hashable"
        }

        test("enum meta .type returns KSType with keyword format") {
            val result = eval("""
                enum Suit { Hearts, Diamonds, Clubs, Spades }
                Suit.type
            """.trimIndent())
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "enum Suit"
        }

        test("function .type returns KSType with keyword format") {
            val result = eval("""
                fun noop() { }
                noop.type
            """.trimIndent())
            result.shouldBeInstanceOf<KSType>()
            (result as KSType).name shouldBe "fun noop"
        }
    }

    // ====================================================================
    // .typeName Returns String
    // ====================================================================

    context(".typeName returns String") {

        test("value .typeName is a String") {
            val result = eval("42.typeName")
            result.shouldBeInstanceOf<String>()
            result shouldBe "Int"
        }

        test("class meta .typeName is a String") {
            val result = eval("""
                class Widget { }
                Widget.typeName
            """.trimIndent())
            result.shouldBeInstanceOf<String>()
            result shouldBe "class Widget"
        }
    }

    // ====================================================================
    // ::class Reflection (Legacy Syntax)
    // ====================================================================

    context("::class reflection") {

        test("Int ::class returns same as .type") {
            run("say 42::class") shouldBe "Int"
        }

        test("String ::class") {
            run("""say "test"::class""") shouldBe "String"
        }

        test("Bool ::class") {
            run("say true::class") shouldBe "Bool"
        }

        test("List ::class") {
            run("say [1, 2]::class") shouldBe "List"
        }
    }

    // ====================================================================
    // .type in Expressions
    // ====================================================================

    context(".type used in expressions") {

        test(".type result used with say") {
            run("""
                let t = 42.type
                say t
            """.trimIndent()) shouldBe "Int"
        }

        test(".typeName in string interpolation") {
            run("""
                let x = 3.14BD
                say "x is a ${'$'}{x.typeName}"
            """.trimIndent()) shouldBe "x is a Dec"
        }

        test(".type in string interpolation") {
            run("""
                let items = [1, 2, 3]
                say "items is ${'$'}{items.type}"
            """.trimIndent()) shouldBe "items is List"
        }

        test("say output for all five meta-type categories") {
            run("""
                class Animal { }
                struct Coord(x: Int, y: Int)
                trait Drawable { fun draw(): String }
                enum Shape { Circle, Square }
                fun helper(): Int = 0

                say Animal.type
                say Coord.type
                say Drawable.type
                say Shape.type
                say helper.type
            """.trimIndent()) shouldBe
                    "class Animal\nstruct Coord\ntrait Drawable\nenum Shape\nfun helper"
        }
    }

    // ====================================================================
    // .type Is Reserved (Cannot Be Shadowed)
    // ====================================================================

    context(".type is reserved and cannot be shadowed") {

        test("class property named 'type' is shadowed by reflection") {
            // .type always returns the KS reflection result, even if
            // the class defines a property called "type"
            run("""
                class Widget {
                    var type = "custom"
                }
                let w = Widget()
                say w.type
            """.trimIndent()) shouldBe "Widget"
        }

        test("struct field named 'type' is shadowed by reflection") {
            run("""
                struct Tag(type: String)
                let t = Tag("bold")
                say t.type
            """.trimIndent()) shouldBe "Tag"
        }
    }

    // ====================================================================
    // Comprehensive Category Summary
    // ====================================================================

    context("all five meta-type categories with instances") {

        test("class — meta and instance types") {
            run("""
                class Car(make: String) { }
                say Car.type
                say Car("Toyota").type
            """.trimIndent()) shouldBe "class Car\nCar"
        }

        test("struct — meta and instance types") {
            run("""
                struct RGB(r: Int, g: Int, b: Int)
                say RGB.type
                say RGB(255, 0, 0).type
            """.trimIndent()) shouldBe "struct RGB\nRGB"
        }

        test("trait — meta type (traits cannot be instantiated)") {
            run("""
                trait Serializable {
                    fun serialize(): String
                }
                say Serializable.type
            """.trimIndent()) shouldBe "trait Serializable"
        }

        test("enum — meta and constant types") {
            run("""
                enum Priority { Low, Medium, High }
                say Priority.type
                say Priority.High.type
            """.trimIndent()) shouldBe "enum Priority\nPriority"
        }

        test("fun — function type") {
            run("""
                fun multiply(a: Int, b: Int): Int = a * b
                say multiply.type
            """.trimIndent()) shouldBe "fun multiply"
        }
    }
})