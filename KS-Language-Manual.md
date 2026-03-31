# Ki Script (KS) Language Manual

**Version 0.1 — Draft**

KS is a Kotlin-inspired scripting language that runs on the JVM via a tree-walking interpreter. It features delimiter-free control flow, first-class quantities and units of measure, an embedded KD (Ki Data) DSL, and a concise, expressive syntax designed for both interactive exploration (REPL) and scripted automation.

KS is part of the **Ki ecosystem**, which includes Ki.Core-JVM (types, units, quantities, currencies, grids, ranges, coordinates), Ki.KD-JVM (the Ki Data format), and Ki.KS-JVM (this language).

---

## Table of Contents

1. [Variables and Bindings](#1-variables-and-bindings)
2. [Types and Literals](#2-types-and-literals)
3. [Strings and Interpolation](#3-strings-and-interpolation)
4. [Operators](#4-operators)
5. [Control Flow](#5-control-flow)
6. [Functions](#6-functions)
7. [Lambdas and Higher-Order Functions](#7-lambdas-and-higher-order-functions)
8. [Classes](#8-classes)
9. [Traits](#9-traits)
10. [Structs](#10-structs)
11. [Enums](#11-enums)
12. [Collections](#12-collections)
13. [Ranges](#13-ranges)
14. [Type Operations](#14-type-operations)
15. [Quantities and Units of Measure](#15-quantities-and-units-of-measure)
16. [Grid](#16-grid)
17. [Coordinate](#17-coordinate)
18. [Pattern Matching with `when`](#18-pattern-matching-with-when)
19. [Error Handling](#19-error-handling)
20. [Constraints](#20-constraints)
21. [Import System (`use`)](#21-import-system-use)
22. [Type Extensions (`extend`)](#22-type-extensions-extend)
23. [Reflection](#23-reflection)
24. [The `say` Statement](#24-the-say-statement)
25. [Lang Blocks (Embedded DSLs)](#25-lang-blocks-embedded-dsls)
26. [Null Safety](#26-null-safety)
27. [The REPL](#27-the-repl)
28. [Appendix: Operator Precedence](#appendix-operator-precedence)
29. [Appendix: Built-in Methods](#appendix-built-in-methods)

---

## 1. Variables and Bindings

KS has two binding forms: `var` for mutable variables and `let` for immutable bindings.

### `var` — Mutable

```
var name = "Akiko"
var count = 0
var height: Double = 68.0
```

Mutable variables can be reassigned:

```
var x = 10
x = 20
x += 5
```

### `let` — Immutable

```
let age = 42
let pi = 3.14159
let greeting = "Hello, world!"
```

Attempting to reassign a `let` binding produces a runtime error.

### Type Annotations

Type annotations are optional. When provided, they follow the name after a colon:

```
var score: Int = 100
let name: String = "Kai"
var item: String?            // nullable, no initializer
```

### Initializers

Variables can be declared with or without an initializer:

```
var x: Int                   // declared, not yet initialized
var y = 42                   // type inferred as Int
let z: Double = 3.14         // explicit type + initializer
```

---

## 2. Types and Literals

### Numeric Types

KS supports five numeric types, each with its own literal syntax:

```
let i = 42                   // Int
let l = 42L                  // Long
let f = 3.14f                // Float
let d = 3.14                 // Double (default for decimals)
let bd = 3.14BD              // BigDecimal (Dec)
```

Integers also support hex and binary notation:

```
let hex = 0xFF               // 255
let bin = 0b1010             // 10
```

### Boolean

```
let yes = true
let no = false
```

### Nil

`nil` is KS's null value:

```
let nothing = nil
```

### Character

Single characters use single quotes:

```
let letter = 'A'
let digit = '7'
let newline = '\n'
```

### URL

URLs are delimited with angle brackets:

```
let site = <https://kixi.io>
let api = <https://api.example.com/v2/data>
let file = <file:///tmp/data.txt>
```

### Version

Version literals use semantic versioning syntax:

```
let v = 5.0.0
let beta = 1.2.3_beta
let rc = 0.2.0_rc_1
```

---

## 3. Strings and Interpolation

KS provides five string forms to cover different needs.

### Standard Strings

Double-quoted strings support escape sequences and interpolation:

```
let name = "world"
say "Hello, $name!"                    // Hello, world!
say "Sum: ${2 + 3}"                    // Sum: 5
say "Tab\there\nnewline"               // escape sequences
```

### Multiline Strings

Triple-quoted strings preserve line breaks and support interpolation:

```
let poem = """
    Roses are red,
    Violets are blue,
    KS is neat,
    And so are you.
"""
```

Leading indentation is automatically stripped (dedented).

### Verbatim Strings

Prefixed with `@`, verbatim strings disable escape processing:

```
let path = @"C:\Users\kai\docs"        // no escape processing
let regex = @"^\d+\.\d+$"              // raw regex
let raw = @"Hello\nWorld"              // literal \n, not newline
```

### Verbatim Multiline Strings

Combine verbatim and multiline:

```
let block = @"""
    No \escapes here.
    Everything is literal.
"""
```

### Backtick Strings

Raw strings delimited by backticks — no escapes, no interpolation:

```
let cmd = `echo "hello world"`
let pattern = `[A-Za-z_]\w*`
let json = `{"key": "value"}`
```

---

## 4. Operators

### Arithmetic

```
let sum = 10 + 3             // 13
let diff = 10 - 3            // 7
let prod = 10 * 3            // 30
let quot = 10 / 3            // 3 (integer division)
let rem = 10 % 3             // 1
let power = 2 ** 10          // 1024
```

Exponentiation is right-associative: `2**3**2` evaluates as `2**(3**2)` = 512.

### Comparison

```
3 < 5                        // true
5 >= 5                       // true
"abc" == "abc"               // true
42 != 0                      // true
```

### Logical

```
true && false                // false
true || false                // true
!true                        // false
```

Short-circuit evaluation applies: `&&` stops at the first `false`, `||` stops at the first `true`.

### Compound Assignment

```
var x = 10
x += 5                       // 15
x -= 3                       // 12
x *= 2                       // 24
x /= 4                       // 6
x %= 5                       // 1
x **= 3                      // 1
```

### Increment and Decrement

Both prefix and postfix forms are supported:

```
var i = 5
i++                          // postfix: returns 5, i becomes 6
++i                          // prefix: i becomes 7, returns 7
i--                          // postfix: returns 7, i becomes 6
--i                          // prefix: i becomes 5, returns 5
```

### Ternary

```
let status = (score > 90) ? "A" : "B"
let label = (x > 0) ? "positive" : (x < 0) ? "negative" : "zero"
let abs = (n >= 0) ? n : -n
```

### String Concatenation

The `+` operator concatenates strings:

```
"Hello" + " " + "World"     // "Hello World"
"Count: " + 42              // "Count: 42"
"Pi = " + 3.14              // "Pi = 3.14"
```

---

## 5. Control Flow

KS uses delimiter-free syntax for control flow — parentheses around conditions are optional, and the body can be either a `{ block }` or a single statement.

### if / else

`if` is an expression in KS — it returns a value.

```
// Block body
if score >= 90 {
    say "Excellent!"
}

// Single-statement body
if n <= 1 return n

// if/else as an expression
let grade = if score >= 90 { "A" } else if score >= 80 { "B" } else { "C" }

// Chained else-if
if temp > 100 {
    say "Boiling"
} else if temp > 50 {
    say "Hot"
} else {
    say "Cool"
}
```

### for

KS supports two `for` loop forms:

**Traditional form** — explicit loop variable:

```
for i in [1, 2, 3] {
    say i
}

for ch in "Hello" {
    say ch
}

for item in items say item       // single-statement body
```

**Simplified form** — implicit `it`:

```
for [1, 2, 3] say "Number: $it"

for 1..5 {
    say "Count: $it"
}

for Color {                      // enum iteration
    say it
}

for "Hello" say "Char: $it"
```

### while

```
var x = 10
while x > 0 {
    say x
    x--
}

// Single-statement body
var n = 1
while n <= 100 n *= 2
```

### break and continue

```
for i in 1..100 {
    if i % 7 == 0 {
        say "Found: $i"
        break
    }
}

for i in 1..20 {
    if i % 2 == 0 continue
    say "$i is odd"
}
```

---

## 6. Functions

### Declaration

Functions are declared with `fun`. The body can be a block or a single expression after `=`:

```
// Block body
fun add(a: Int, b: Int): Int {
    return a + b
}

// Single-expression body
fun square(n: Int): Int = n * n

// No return type annotation (inferred)
fun greet(name: String) = "Hello, $name!"
```

### Parameters

Parameters can have types, default values, and constraints:

```
fun greet(name: String, greeting: String = "Hello") {
    say "$greeting, $name!"
}

greet("Kai")                     // Hello, Kai!
greet("Kai", "Hey")             // Hey, Kai!

// Untyped parameter (type inferred at call site)
fun echo(value) = say value
```

### Named Arguments

```
fun createUser(name: String, age: Int, active: Bool = true) {
    say "$name, $age, active=$active"
}

createUser(name = "Kai", age = 30)
createUser(age = 25, name = "Yuki", active = false)
```

### Recursion

```
fun factorial(n: Int): Int = if n <= 1 1 else n * factorial(n - 1)

fun fib(n: Int): Int {
    if n <= 1 return n
    return fib(n - 1) + fib(n - 2)
}

say factorial(10)                // 3628800
say fib(10)                      // 55
```

### Infix Functions

Functions declared with the `infix` modifier can be called in infix position. They must take exactly one parameter:

```
infix fun add(other: Int): Int = this.value + other

// Usage: receiver name argument
let result = 5 add 3

// Multiple infix calls are left-associative
// a foo b bar c  parses as  (a.foo(b)).bar(c)
```

For complex right-hand operands, use dot-call syntax: `a.add(b + c)`.

---

## 7. Lambdas and Higher-Order Functions

### Lambda Syntax

Lambdas are anonymous functions enclosed in braces:

```
// Explicit parameter with arrow
let double = { x -> x * 2 }

// Typed parameters
let add = { x: Int, y: Int -> x + y }

// Zero-parameter lambda (explicit arrow)
let greet = { -> "Hello!" }

// Implicit `it` parameter (no arrow)
let square = { it * it }
```

### Trailing Lambda

When a lambda is the last argument to a function call, it can be placed outside the parentheses:

```
[1, 2, 3].map { it * 2 }                    // [2, 4, 6]
[1, 2, 3].filter { it > 1 }                 // [2, 3]
[1, 2, 3].fold(0) { acc, x -> acc + x }     // 6
```

### Higher-Order List Methods

KS lists support a rich set of higher-order functions:

```
let nums = [1, 2, 3, 4, 5]

// map — transform each element
nums.map { it * 10 }                        // [10, 20, 30, 40, 50]

// filter — keep elements matching predicate
nums.filter { it % 2 == 0 }                 // [2, 4]

// forEach — execute side effect for each element
nums.forEach { say "Item: $it" }

// reduce — combine elements left to right
nums.reduce { acc, x -> acc + x }           // 15

// fold — reduce with initial value
nums.fold(100) { acc, x -> acc + x }        // 115

// flatMap — map and flatten
[[1, 2], [3, 4]].flatMap { it }             // [1, 2, 3, 4]

// any, all, none — predicate tests
nums.any { it > 4 }                         // true
nums.all { it > 0 }                         // true
nums.none { it > 10 }                       // true

// take, drop — subsequences
nums.take(3)                                 // [1, 2, 3]
nums.drop(2)                                 // [3, 4, 5]
```

---

## 8. Classes

### Declaration

Classes support primary constructors, super types, and member bodies:

```
class Person(let name: String, var age: Int = 0) {
    fun greet() = say "Hi, I'm $name"
    fun birthday() { age++ }
}

let p = Person("Kai", 30)
p.greet()                        // Hi, I'm Kai
p.birthday()
say p.age                        // 31
```

### Constructor Parameters

Constructor parameters with `let` or `var` automatically become properties:

```
class Point(let x: Double, let y: Double) {
    fun distanceTo(other: Point): Double {
        let dx = x - other.x
        let dy = y - other.y
        return (dx * dx + dy * dy) ** 0.5
    }
}

let a = Point(0.0, 0.0)
let b = Point(3.0, 4.0)
say a.distanceTo(b)              // 5.0
```

Plain parameters (without `var`/`let`) are constructor-only — not accessible as properties:

```
class Wrapper(value: Int) {
    let doubled = value * 2      // value used during construction
}

let w = Wrapper(5)
say w.doubled                    // 10
// w.value would be an error — not a property
```

### Inheritance

Classes can extend one superclass and implement multiple traits:

```
class Dog: Animal {
    fun speak() = say "Woof!"
}

class Person(let name: String, var age: Int): Printable, Comparable {
    fun display() = say "$name ($age)"
}
```

### Static Members

Static members live in a `static` block inside the class:

```
class MathUtils {
    static {
        let PI = 3.14159
        fun square(n: Int): Int = n * n
    }
}

say MathUtils.PI                 // 3.14159
say MathUtils.square(5)          // 25
```

### The `this` Keyword

Inside a class body, `this` refers to the current instance:

```
class Counter(var value: Int = 0) {
    fun increment() { this.value++ }
    fun add(n: Int) { this.value += n }
}
```

---

## 9. Traits

Traits define interfaces with optional default implementations. Unlike classes, traits cannot be directly instantiated.

```
trait Animal {
    fun name(): String               // abstract (no body)
    fun speak() = say "${name()} speaks"   // default implementation
}

trait Swimmer {
    fun swim() = say "Swimming!"
}

class Duck: Animal, Swimmer {
    fun name(): String = "Duck"
    fun speak() = say "Quack!"
}

let d = Duck()
d.speak()                        // Quack!
d.swim()                         // Swimming!
```

### Trait Inheritance

Traits can extend other traits:

```
trait Shape {
    fun area(): Double
}

trait Printable {
    fun display(): String
}

trait PrintableShape: Shape, Printable {
    fun summary() = say "${display()}: area=${area()}"
}
```

---

## 10. Structs

Structs are value types with structural equality and copy-on-assign semantics. They require a primary constructor and can implement traits but cannot have a superclass.

```
struct Point(let x: Double, let y: Double) {
    fun distanceTo(other: Point): Double {
        let dx = x - other.x
        let dy = y - other.y
        return (dx * dx + dy * dy) ** 0.5
    }
}

struct Color(let r: Int, let g: Int, let b: Int): Printable {
    fun brightness() = (r + g + b) / 3
}
```

### Value Equality

Structs use structural equality — two structs are equal if all their fields are equal:

```
let a = Point(1.0, 2.0)
let b = Point(1.0, 2.0)
say a == b                       // true
```

### Copy Semantics

Assignment copies the struct value:

```
var p1 = Point(1.0, 2.0)
var p2 = p1                      // p2 is an independent copy
```

---

## 11. Enums

KS enums support four forms, from simple to constructor-style.

### Simple Enums

```
enum Color { RED GREEN BLUE }

say Color.RED
say Color.BLUE

for Color { say it }             // RED, GREEN, BLUE
```

### Valued Enums

Constants can have assigned values:

```
enum Priority { LOW=1 MEDIUM=5 HIGH=10 }

say Priority.HIGH                // HIGH
```

### Typed Enums

A type annotation on the enum specifies the value type:

```
enum Fruit: Int { Apple=1 Orange=2 Banana=3 }
```

### Constructor Enums

The most powerful form — each constant calls a constructor:

```
enum HttpStatus(code: Int, msg: String) {
    OK(200, "OK")
    NOT_FOUND(404, "Not Found")
    SERVER_ERROR(500, "Internal Server Error")
}

say HttpStatus.OK
say HttpStatus.NOT_FOUND
```

### Enum Members

Enums can also contain methods, properties, and static blocks alongside their constants:

```
enum Direction {
    NORTH SOUTH EAST WEST

    fun isVertical(): Bool = this == Direction.NORTH || this == Direction.SOUTH
}
```

### Dot-Prefixed Enum Constants (DPEC)

When the enum type can be inferred from context, you can use the short `.NAME` syntax:

```
var color: Color = .RED

when color {
    .RED -> say "Hot"
    .GREEN -> say "Go"
    .BLUE -> say "Cool"
}
```

---

## 12. Collections

### Lists

Lists are ordered, indexable collections. Commas are optional (KD-style):

```
let nums = [1, 2, 3, 4, 5]
let words = ["hello" "world"]    // commas optional
let empty = []

say nums[0]                      // 1
say nums.size                    // 5
say nums.first                   // 1
say nums.last                    // 5
```

### Mutable List Operations

```
var list = [1, 2, 3]
list[0] = 10                    // [10, 2, 3]
```

### List Methods

```
let items = [3, 1, 4, 1, 5, 9]

items.sorted()                   // [1, 1, 3, 4, 5, 9]
items.reversed()                 // [9, 5, 1, 4, 1, 3]
items.contains(4)                // true
items.indexOf(1)                 // 1
items.take(3)                    // [3, 1, 4]
items.drop(4)                    // [5, 9]
items.isEmpty                    // false
items.isNotEmpty                 // true
```

### Maps

Maps use `key=value` syntax inside brackets:

```
let ages = [name="Kai" age=30]
let scores = ["math"=95, "science"=87, "english"=92]
let empty = [=]                  // empty map

say ages["name"]                 // Kai
say scores.size                  // 3
say scores.keys                  // ["math", "science", "english"]
say scores.values                // [95, 87, 92]
say scores.isEmpty               // false
```

---

## 13. Ranges

KS has a powerful range system with four operators controlling endpoint inclusivity.

### Basic Ranges

```
let inclusive = 1..10            // 1, 2, 3, ..., 10
let exclusive = 0..<5            // 0, 1, 2, 3, 4
let startExcl = 0<..5           // 1, 2, 3, 4, 5
let bothExcl = 0<..<5           // 1, 2, 3, 4
```

### Open-Ended Ranges

Use `_` to leave one end open:

```
let from5 = 5.._               // 5 to infinity
let upTo10 = _..10              // negative infinity to 10
let upToNot10 = _..<10          // negative infinity to 9
```

### Range Properties and Methods

```
let r = 1..10
say r.start                      // 1
say r.end                        // 10
say r.min                        // 1
say r.max                        // 10
say r.reversed                   // 10..1

r.contains(5)                    // true
r.contains(11)                   // false
r.toList()                       // [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
```

### Range Iteration

```
for i in 1..5 {
    say i                        // 1, 2, 3, 4, 5
}

for i in 0..<3 {
    say i                        // 0, 1, 2
}
```

### Containment Check

```
let valid = 1..100
say 50 in valid                  // true
say 0 !in valid                  // true
```

---

## 14. Type Operations

### Type Check: `is` / `!is`

```
let x = "hello"
say x is String                  // true
say x !is Int                    // true

if x is String {
    say "It's a string"
}
```

### Type Cast: `as`

```
let value: Any = 42
let n = value as Int
say n + 1                        // 43
```

Unit conversion also uses `as`:

```
let dist = 10cm + 4mm
say dist as cm                   // result in centimeters
```

### Containment Check: `in` / `!in`

```
say 5 in [1, 2, 3, 4, 5]        // true
say "x" !in ["a", "b", "c"]     // true
say 50 in 1..100                 // true
```

### Pattern Matching: `matches`

```
let email = "kai@example.com"
say email matches @"^[\w.]+@[\w.]+\.\w+$"   // true

let code = "ABC-123"
say code matches @"^[A-Z]{3}-\d{3}$"        // true

let greeting = "Hello World"
say greeting matches @"^Hello"               // true
```

---

## 15. Quantities and Units of Measure

One of KS's distinctive features is built-in support for quantities with units of measure, powered by Ki.Core.

### Unit Quantities

Numeric literals can carry a unit suffix:

```
let length = 23cm
let area = 51.4m²
let weight = 1000kg
let temp = 25°C
let volume = 97ℓ
```

### Currency Quantities

Currencies use prefix notation with their symbol:

```
let price = $23.53
let euros = €50.25
let yen = ¥10000
let pounds = £75.50
let bitcoin = ₿0.5
let ether = Ξ2.5
```

Or suffix notation with the currency code:

```
let amount = 100USD
```

### Arithmetic with Quantities

Quantities support arithmetic while maintaining unit safety:

```
let total = 10cm + 5cm          // 15cm
let diff = 1kg - 200g           // result in compatible units
let doubled = 50USD * 2         // $100.00
```

### Unit Conversion with `as`

```
let dist = 10cm + 4mm
let result = dist as cm          // convert to centimeters

let temp = 100°C
let fahrenheit = temp as °F      // convert to Fahrenheit
```

### Unit Composition with `⚭`

The combine operator (`⚭`) multiplies quantities and composes their units:

```
let area = 4cm ⚭ 3cm            // 12cm²
```

---

## 16. Grid

Grid is a two-dimensional data structure from Ki.Core. KS provides special syntax for creating grids.

### Data Form

Use `.grid { ... }` with rows separated by newlines or semicolons:

```
let g = .grid {
    1  2  3
    4  5  6
    7  8  9
}

// Typed grid
let typed = .grid<Int> {
    10  20  30
    40  50  60
}

// Inline with semicolons
let inline = .grid { 1 2 3; 4 5 6; 7 8 9 }
```

### Dimension Form

Create grids by specifying width and height:

```
let blank = .grid(3, 3)                   // 3×3, nil-filled
let zeros = .grid<Int>(4, 4)              // 4×4, zero-filled
let ones = .grid<Int>(2, 2, default = 1)  // 2×2, filled with 1
```

### Grid Access

```
let g = .grid { 1 2 3; 4 5 6; 7 8 9 }
say g[0, 0]                      // 1
say g[1, 2]                      // 6
say g.width                      // 3
say g.height                     // 3
say g.size                       // 9
```

---

## 17. Coordinate

Coordinates support two addressing styles via named arguments.

### Standard Notation

```
let origin = .coordinate(x=0, y=0)
let pos = .coordinate(x=4, y=7)
let pos3d = .coordinate(x=0, y=0, z=5)
```

### Sheet Notation

```
let cell = .coordinate(c="A", r=1)
let far = .coordinate(c="AA", r=100)
let deep = .coordinate(c="E", r=8, z=5)
```

---

## 18. Pattern Matching with `when`

`when` is KS's pattern matching expression, inspired by Kotlin. It is an expression — it returns a value.

### Subject-Based When

```
when status {
    200 -> say "OK"
    404 -> say "Not Found"
    500 -> say "Server Error"
    else -> say "Unknown: $status"
}
```

### DPEC Matchers

```
when color {
    .RED -> say "Stop"
    .YELLOW -> say "Caution"
    .GREEN -> say "Go"
}
```

### Multi-Value Matchers

Multiple matchers can be comma-separated on a single branch:

```
when direction {
    .NORTH, .SOUTH -> say "Vertical"
    .EAST, .WEST -> say "Horizontal"
}
```

### Type Matchers

```
when value {
    is String -> say "It's a string: $value"
    is Int -> say "It's an integer: $value"
    is List -> say "It's a list"
    else -> say "Unknown type"
}
```

### Containment Matchers

```
when score {
    in 90..100 -> "A"
    in 80..89 -> "B"
    in 70..79 -> "C"
    else -> "F"
}
```

### Pattern Matchers

```
when text {
    matches @"^\d+$" -> say "All digits"
    matches @"^[A-Z]" -> say "Starts with uppercase"
    else -> say "Other"
}
```

### Condition-Based When (No Subject)

When there is no subject, each branch is a standalone boolean expression:

```
when {
    temp > 100 -> say "Boiling"
    temp > 50 -> say "Hot"
    temp > 20 -> say "Warm"
    else -> say "Cool"
}
```

### When as Expression

```
let grade = when score {
    in 90..100 -> "A"
    in 80..89 -> "B"
    in 70..79 -> "C"
    else -> "F"
}
say grade
```

---

## 19. Error Handling

### try / catch / finally

`try` is an expression in KS — it returns a value.

```
try {
    let result = riskyOperation()
    say result
} catch(e) {
    say.error "Something went wrong: $e"
} finally {
    cleanup()
}
```

### Typed Catch

```
try {
    let data = readFile("config.txt")
} catch(e: IOException) {
    say.error "IO Error: $e"
} catch(e: ParseException) {
    say.error "Parse Error: $e"
}
```

### Wildcard Catch

The `catch(*)` form catches all exceptions:

```
try {
    dangerousCode()
} catch(*) {
    say.error "An error occurred"
}
```

### throw

```
fun divide(a: Int, b: Int): Int {
    if b == 0 throw KSException("Division by zero!")
    return a / b
}

try {
    divide(10, 0)
} catch(e) {
    say.error e
}
```

---

## 20. Constraints

Constraints are runtime-checked guards that can be attached to variable declarations and function parameters. Violations throw a `ConstraintError`.

### Comparison Constraints

```
var score: Int > 0 = 100
let age: Int >= 0 = 25
var temp: Double <= 100.0 = 37.5
let code: Int != -1 = 42
```

### Range Constraints

```
var percentage: Int 0..100 = 75
let probability: Double 0.0..<1.0 = 0.5
var temperature: Double -40.0..60.0 = 22.0
```

### Containment Constraints

```
var level: Int in [1, 2, 3, 4, 5] = 3
let color: String in ["red", "green", "blue"] = "red"
```

### Pattern Constraints

```
var email: String matches @"^[\w.]+@[\w.]+\.\w+$" = "kai@example.com"
let code: String matches @"^[A-Z]{3}-\d{3}$" = "ABC-123"
let name: String matches @"^[A-Za-z ]+$" = "Kai Tanaka"
```

### Constraints on Parameters

```
fun setVolume(level: Int 0..100) {
    say "Volume set to $level"
}

fun gradeStudent(score: Int >= 0) {
    say "Score: $score"
}

fun registerUser(email: String matches @"^[\w.]+@") {
    say "Registered: $email"
}
```

---

## 21. Import System (`use`)

The `use` declaration imports types, functions, and packages.

### Single Import

```
use io.kixi.kd.Tag
```

### Multi-Import

Import multiple items from the same package:

```
use io.kixi.kd.Tag, Annotation, Snip
```

### Wildcard Import

**Flat** — imports direct members:

```
use io.kixi.kd.*
```

**Tree** — imports recursively into subpackages:

```
use io.kixi.**
```

### Aliased Import

```
use io.kixi.kd.Tag as T
use io.kixi.kd.Tag as T, Annotation as Ann
```

### Static Member Import

```
use SomeClass.staticFun
```

---

## 22. Type Extensions (`extend`)

Extensions add methods or traits to existing types without modifying them.

### Method Extension

```
extend String {
    fun isPalindrome(): Bool {
        let reversed = this.reversed()
        return this == reversed
    }
}

say "racecar".isPalindrome()     // true
say "hello".isPalindrome()       // false
```

### Trait Extension

Adopt a trait for an existing type:

```
extend trait Comparable
```

---

## 23. Reflection

KS supports basic reflection via the `::class` operator:

```
let x = 42
say x::class                     // Int

let s = "hello"
say s::class                     // String

let list = [1, 2, 3]
say list::class                  // List
```

---

## 24. The `say` Statement

`say` is KS's primary output statement. It supports variants for different output styles and accepts parenthesized or bare arguments.

### Basic Output

```
say "Hello, world!"
say 42
say [1, 2, 3]
```

### Variants

```
say.error "Something went wrong"     // red output
say.warn "Careful here"              // orange output
say.note "Important detail"          // bold output
```

### Multiple Arguments

```
say "Name:" name "Age:" age
```

### Parenthesized Form

```
say("Hello", "World")
say.note("Important", bold=true)
```

### Expression Arguments

```
say "Sum: " + (a + b)
say "Status: " + status
```

### Blank Line

`say` with no arguments prints a blank line:

```
say
```

---

## 25. Lang Blocks (Embedded DSLs)

The `lang` keyword switches the parser to an embedded DSL mode. Currently, KD (Ki Data) is the supported embedded language.

### KD Block

```
let data = lang KD {
    book "The Hobbit" author="Tolkien" published=1937

    config {
        db host="localhost" port=5432
        cache enabled=true ttl=300
    }

    @Personal
    favorites {
        color "blue"
        number 42
    }
}
```

### KD Features Inside Blocks

KD tags support values, attributes, annotations, namespaces, and children:

```
lang KD {
    // Values and attributes
    person "Kai" age=30 active=true

    // Namespaced tags
    config:database host="localhost" port=5432

    // Annotations
    @Deprecated @Since("2.0")
    oldMethod "doStuff"

    // Nested children
    team "Engineering" {
        member "Alice" role="Lead"
        member "Bob" role="Dev"
        member "Carol" role="QA"
    }
}
```

### Interpolation in KD Blocks

KD blocks support `$var` and `${expr}` interpolation:

```
let name = "Kai"
let age = 30

lang KD {
    user $name age=${age + 1}
}
```

---

## 26. Null Safety

KS provides several operators for handling nullable values.

### Nullable Types

```
var name: String? = "Kai"
name = nil                       // OK — String? accepts nil
```

### Safe Navigation: `?.`

```
let len = name?.length           // nil if name is nil, otherwise the length
let upper = name?.uppercase      // nil if name is nil
```

### Elvis Operator: `?:`

Provides a default value when the left side is nil:

```
let displayName = name ?: "Anonymous"
let length = name?.length ?: 0
let safe = getValue() ?: fallback()
```

### Non-Null Assertion: `!!`

Asserts that a value is not nil, throwing an error if it is:

```
let definitelyNotNil = name!!
let len = name!!.length
```

---

## 27. The REPL

KS includes an interactive REPL (Read-Eval-Print Loop) with the following features:

- **Expression auto-display** — bare expressions print their result
- **Persistent state** — variables, functions, and classes carry across lines
- **Smart multi-line input** — detects incomplete expressions automatically
- **Line editing and history** — arrow keys, Ctrl+A/E, Up/Down recall
- **Colored output** — ANSI colors for `say.error`, `say.warn`, `say.note`

### REPL Commands

```
:help     — show available commands
:quit     — exit the REPL
:reset    — clear all state
:env      — display defined variables
:type     — show the type of an expression
```

### Example Session

```
ks> let x = 42
ks> x * 2
84
ks> fun square(n: Int) = n * n
ks> square(x)
1764
ks> [1, 2, 3].map { it * 10 }
[10, 20, 30]
```

---

## Appendix: Operator Precedence

From lowest to highest precedence:

| Level | Operators | Associativity |
|-------|-----------|---------------|
| 1 | `=` `+=` `-=` `*=` `/=` `%=` `**=` | Right |
| 2 | `? :` (ternary) | Right |
| 3 | `\|\|` | Left |
| 4 | `&&` | Left |
| 5 | `==` `!=` | Left |
| 6 | `<` `>` `<=` `>=` | Left |
| 7 | `in` `!in` `is` `!is` `matches` `as` | Left |
| 8 | infix calls (`a add b`) | Left |
| 9 | `?:` (elvis) | Left |
| 10 | `..` `..<` `<..` `<..<` | Left |
| 11 | `+` `-` | Left |
| 12 | `*` `/` `%` `⚭` | Left |
| 13 | `**` | Right |
| 14 | `-` `!` `++` `--` (prefix) | Right |
| 15 | `.` `?.` `()` `[]` `!!` `++` `--` `::class` | Left |

---

## Appendix: Built-in Methods

### String Methods

| Method / Property | Description |
|---|---|
| `.size`, `.length` | Number of characters |
| `.isEmpty`, `.isNotEmpty` | Emptiness check |
| `.first`, `.last` | First/last character |
| `.uppercase`, `.lowercase` | Case conversion |
| `.trim` | Strip leading/trailing whitespace |
| `.reversed` | Reversed string |
| `.contains(str)` | Substring check |
| `.startsWith(str)` | Prefix check |
| `.endsWith(str)` | Suffix check |
| `.indexOf(str)` | First occurrence index |
| `.substring(start, end)` | Extract substring |
| `.split(delimiter)` | Split into list |
| `.replace(old, new)` | Replace occurrences |
| `.toInt()`, `.toDouble()` | Numeric conversion |

### List Methods

| Method / Property | Description |
|---|---|
| `.size`, `.length` | Number of elements |
| `.isEmpty`, `.isNotEmpty` | Emptiness check |
| `.first`, `.last` | First/last element |
| `.reversed()` | Reversed copy |
| `.sorted()` | Sorted copy |
| `.contains(elem)` | Element check |
| `.indexOf(elem)` | First occurrence index |
| `.take(n)` | First n elements |
| `.drop(n)` | Skip first n elements |
| `.map { }` | Transform elements |
| `.filter { }` | Keep matching elements |
| `.forEach { }` | Execute for each |
| `.reduce { acc, x -> }` | Combine left to right |
| `.fold(init) { acc, x -> }` | Combine with initial value |
| `.flatMap { }` | Map and flatten |
| `.any { }` | True if any match |
| `.all { }` | True if all match |
| `.none { }` | True if none match |

### Map Methods

| Method / Property | Description |
|---|---|
| `.size` | Number of entries |
| `.isEmpty`, `.isNotEmpty` | Emptiness check |
| `.keys` | List of keys |
| `.values` | List of values |

### Range Methods

| Method / Property | Description |
|---|---|
| `.start`, `.end` | Endpoints |
| `.min`, `.max` | Minimum/maximum |
| `.reversed` | Reversed range |
| `.contains(value)` | Containment check |
| `.toList()` | Materialize to list |
| `.count()` | Number of elements |

### Grid Methods

| Method / Property | Description |
|---|---|
| `.width`, `.height` | Dimensions |
| `.size` | Total cell count |
| `.isEmpty`, `.isNotEmpty` | Emptiness check |
| `.toList()` | Flatten to list |
| `[x, y]` | Cell access |

---

*This manual is a living document. As KS evolves, new features and refinements will be added.*