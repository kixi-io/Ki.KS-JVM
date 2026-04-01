# Ki Script (KS) Language Manual

**Version 0.2 — Draft**

KS is a Kotlin and Swift-inspired scripting language that runs on the JVM via a tree-walking interpreter. It features delimiter-free control flow, first-class quantities and units of measure, an embedded KD (Ki Data) DSL, and a concise, expressive syntax designed for both interactive exploration (REPL) and scripted automation.

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
18. [Version](#18-version)
19. [Blob](#19-blob)
20. [Email](#20-email)
21. [GeoPoint](#21-geopoint)
22. [NSID](#22-nsid)
23. [Call and Tag](#23-call-and-tag)
24. [Regex](#24-regex)
25. [Pattern Matching with `when`](#25-pattern-matching-with-when)
26. [Error Handling](#26-error-handling)
27. [Constraints](#27-constraints)
28. [Import System (`use`)](#28-import-system-use)
29. [Type Extensions (`extend`)](#29-type-extensions-extend)
30. [Reflection](#30-reflection)
31. [The `say` Statement](#31-the-say-statement)
32. [Lang Blocks (Embedded DSLs)](#32-lang-blocks-embedded-dsls)
33. [Null Safety](#33-null-safety)
34. [The REPL](#34-the-repl)
35. [Appendix: Operator Precedence](#appendix-operator-precedence)
36. [Appendix: Built-in Methods Reference](#appendix-built-in-methods-reference)

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

### when

`when` is KS's pattern matching construct, inspired by Kotlin. Like `if`, it is an expression — it returns a value.

**Subject-based** — match a value against branches:

```
let httpCode = 404

let result = when httpCode {
    200 -> "OK"
    in 300..399 -> "Redirect"
    404 -> "Not Found"
    in 500..599 -> "Server Error"
    else -> "Unknown"
}

say result                           // Not Found
```

**Condition-based** — no subject, each branch is a boolean expression:

```
var temperature = 85

when {
    temperature > 100 -> say.error "Critical overheating!"
    temperature > 80  -> say.warn "Running hot"
    temperature > 60  -> say "Normal"
    else -> say.note "Cool"
}
```

**Enum matching with DPEC**:

```
enum Status { SUCCESS PARTIAL TIMEOUT FAILURE }

let response = Status.TIMEOUT

let message = when response {
    .SUCCESS, .PARTIAL -> "Received data"
    .TIMEOUT -> "Try again"
    .FAILURE -> "Error occurred"
    else -> "Unhandled"
}

say message                          // Try again
```

Branch bodies can be a single expression (as shown above) or a `{ block }` for multi-line logic. Branches support comma-separated matchers that act as OR — if any matcher hits, that branch executes.

Subject-based `when` also supports `is`/`!is` (type matchers), `in`/`!in` (containment matchers), and `matches` (regex matchers). See [Section 25: Pattern Matching with `when`](#25-pattern-matching-with-when) for the full matcher reference.

If no branch matches and there is no `else`, a `NonExhaustiveWhenError` is thrown at runtime.

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

// takeWhile, dropWhile — conditional subsequences
nums.takeWhile { it < 4 }                   // [1, 2, 3]
nums.dropWhile { it < 3 }                   // [3, 4, 5]
```

### Closures

Lambdas capture variables from their enclosing scope:

```
var factor = 10
let result = [1, 2, 3].map { it * factor }  // [10, 20, 30]

fun multiplier(n: Int): Any {
    return { x: Int -> x * n }
}
let triple = multiplier(3)
say triple(5)                                // 15
```

### Standalone Blocks

A bare `{ ... }` at statement level executes immediately as a scoped block — it is not a lambda:

```
var x = 1
{
    var y = 2
    say x       // 1  (visible from outer scope)
    say y       // 2
}
// y is not visible here
say x           // 1
```

To create a zero-argument lambda (not an immediately-executed block), use the explicit arrow: `{ -> body }`.

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

### Index Assignment

Lists created with `var` support index assignment:

```
var list = [1, 2, 3]
list[0] = 10                    // [10, 2, 3]
```

### List Query Methods

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

### Mutable List Operations

KS lists are mutable by default. In-place mutators require a `var` binding and return `nil`; copy methods return a new list.

**Adding elements:**

```
var list = [1, 2, 3]
list += 4                        // [1, 2, 3, 4]  (append element)
list += [5, 6]                   // [1, 2, 3, 4, 5, 6]  (append list)
```

**Removing elements:**

```
var list = [1, 2, 3, 2, 5]
list -= 2                        // [1, 3, 2, 5]  (removes first occurrence)
```

**In-place mutators** (require `var`, return `nil`):

```
var nums = [3, 1, 4, 1, 5]

nums.sort()                      // [1, 1, 3, 4, 5] in-place
nums.reverse()                   // [5, 4, 3, 1, 1] in-place
nums.shuffle()                   // random order in-place
```

**Copy methods** (work on any list, return new list):

```
let nums = [3, 1, 4, 1, 5]

let sorted = nums.sorted()      // [1, 1, 3, 4, 5] — new list
let reversed = nums.reversed()  // [5, 1, 4, 1, 3] — new list
let shuffled = nums.shuffled()  // random order — new list
```

The mutator/copy convention: `sort()` mutates in-place and returns `nil`; `sorted()` returns a new sorted list. The same pattern applies to `reverse()`/`reversed()` and `shuffle()`/`shuffled()`.

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

### Range Properties

```
let r = 1..10
say r.start                      // 1
say r.end                        // 10
say r.bound                      // ".."
say r.min                        // 1
say r.max                        // 10
say r.reversed                   // true if start > end, else false
say r.isClosed                   // true (both ends bounded)
say r.isOpen                     // false
say r.isOpenStart                // false
say r.isOpenEnd                  // false
say r.startExclusive             // false
say r.endExclusive               // false
```

### Range Methods

```
let r = 1..10

r.contains(5)                    // true
r.contains(11)                   // false
r.toList()                       // [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
r.toList(2)                      // [1, 3, 5, 7, 9] (step by 2)
r.count()                        // 10
r.count(3)                       // 4 (step by 3)

// Set operations
let r2 = 5..15
r.overlaps(r2)                   // true
r.intersect(r2)                  // 5..10
r.clamp(20)                      // 10 (clamp to range bounds)
r.clamp(-5)                      // 1
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
let canadian = 50CAD
let swiss = 200CHF
```

### Arithmetic with Quantities

Quantities support arithmetic while maintaining unit safety:

```
let total = 10cm + 5cm          // 15cm
let diff = 1kg - 200g           // result in compatible units
let doubled = 50USD * 2         // $100.00
```

Incompatible units produce a runtime error:

```
// 10cm + 5kg  → IncompatibleUnitsException
// $100 + €50  → IncompatibleUnitsException
```

### Unit Conversion with `as`

```
let dist = 10cm + 4mm
let result = dist as cm          // convert to centimeters

let temp = 100°C
let fahrenheit = temp as K       // convert to Kelvin
```

### Unit Composition with `⚭`

The combine operator (`⚭`) multiplies quantities and composes their units:

```
let area = 4cm ⚭ 3cm            // 12cm²
let volume = 12cm² ⚭ 5cm        // 60cm³
```

### Quantity Members

```
let dist = 23cm
say dist.value                   // 23
say dist.unit                    // "cm"

let converted = dist.convertTo("mm")  // 230mm
say dist.toSuffixString()        // "23cm:i"
```

### Scientific Notation

KS supports two forms for scientific notation in quantities:

```
// Parentheses style
let earth = 5.5e(8)km           // 5.5 × 10⁸ km
let nano = 5.5e(-7)m            // 5.5 × 10⁻⁷ m

// Letter style (n=negative, p=positive)
let mass = 9.109en31kg           // 9.109 × 10⁻³¹ kg
let avogadro = 6.022ep23mol      // 6.022 × 10²³ mol
```

### Available Units

**Length:** `nm`, `µm`, `mm`, `cm`, `dm`, `m`, `km`

**Mass:** `ng`, `mg`, `cg`, `g`, `kg`

**Area:** `nm²`, `mm²`, `cm²`, `m²`, `km²`

**Volume:** `nm³`, `mm³`, `cm³`, `m³`, `km³`, `mℓ`, `ℓ`

**Temperature:** `K`, `°C` (alias: `dC`)

**Time:** `s`, `min`, `h`, `day`

**Speed:** `kph`, `mps`

**Density:** `kgpm³`

**Other:** `mol`, `A`, `cd`, `pH`

**Fiat Currencies:** `USD` ($), `EUR` (€), `JPY` (¥), `GBP` (£), `CNY`, `AUD`, `CAD`, `CHF`, `HKD`, `SGD`, `INR`, `KRW`

**Cryptocurrencies:** `BTC` (₿), `ETH` (Ξ)

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

### Cell Access

Grids support multiple access styles:

```
let g = .grid { 1 2 3; 4 5 6; 7 8 9 }

// Standard zero-based (x=column, y=row)
say g[0, 0]                      // 1
say g[1, 2]                      // 8

// Plate notation (letter row, one-based column)
say g["A", 1]                    // 1  (row A, column 1)
say g["B", 3]                    // 6  (row B, column 3)

// Sheet notation string
say g["A1"]                      // 1

// Coordinate object
let coord = .coordinate(x=2, y=1)
say g[coord]                     // 6
```

### Cell Assignment

```
var g = .grid<Int>(3, 3)
g[0, 0] = 42
g[1, 1] = 99
g["C", 3] = 7                   // plate notation
```

### Grid Properties

```
let g = .grid { 1 2 3; 4 5 6 }
say g.width                      // 3
say g.height                     // 2
say g.size                       // 6
say g.isEmpty                    // false (true if all cells nil)
say g.isNotEmpty                 // true (true if any cell non-nil)
say g.elementNullable            // false
```

### Grid Methods

**Transformation:**

```
let g = .grid { 1 2 3; 4 5 6 }

let doubled = g.map { it * 2 }  // new grid: 2 4 6; 8 10 12
let transposed = g.transpose()  // 2×3 grid: 1 4; 2 5; 3 6
let cloned = g.copy()           // independent deep copy
```

**Row and column access:**

```
let g = .grid { 1 2 3; 4 5 6; 7 8 9 }

let row = g.getRowCopy(0)        // [1, 2, 3]
let col = g.getColumnCopy(1)     // [2, 5, 8]

g.setRow(0, [10, 20, 30])       // replaces first row
g.setColumn(2, [30, 60, 90])    // replaces third column
```

**Search:**

```
let g = .grid { 1 2 3; 4 5 6; 7 8 9 }

g.find { it > 5 }               // (.coordinate(x=2, y=1), 6) — first match
g.findAll { it % 2 == 0 }       // list of (Coordinate, value) pairs
g.count { it > 3 }              // 6
g.any { it == 5 }               // true
g.all { it > 0 }                // true
g.none { it > 100 }             // true
```

**Mutation:**

```
var g = .grid<Int>(3, 3, default = 0)

g.fill(42)                       // set every cell to 42
g.fillRow(0, 99)                 // fill first row with 99
g.fillColumn(1, 77)              // fill second column with 77
g.clear()                        // set all cells to nil
```

**Subgrid extraction:**

```
let g = .grid {
    1  2  3  4
    5  6  7  8
    9 10 11 12
}

let sub = g.subgrid(1, 0, 2, 2)  // 2×2 from (1,0): 2 3; 6 7
```

**Conversion:**

```
let g = .grid { 1 2 3; 4 5 6 }

g.toList()                       // [1, 2, 3, 4, 5, 6] (flat, row-major)
g.toRowList()                    // [[1, 2, 3], [4, 5, 6]]
```

**Iteration:**

```
let g = .grid { 1 2; 3 4 }

g.forEach { say it }             // 1, 2, 3, 4
g.forEachIndexed { x, y, v -> say "$x,$y = $v" }
// 0,0 = 1
// 1,0 = 2
// 0,1 = 3
// 1,1 = 4
```

---

## 17. Coordinate

Coordinates represent a position in a 2D grid (with optional z for 3D). They support two addressing styles that map to the same underlying (x, y) position.

### Standard Notation

Zero-based programmer-friendly coordinates:

```
let origin = .coordinate(x=0, y=0)
let pos = .coordinate(x=4, y=7)
let pos3d = .coordinate(x=0, y=0, z=5)
```

### Sheet Notation

Spreadsheet-style with letter columns and one-based rows:

```
let cell = .coordinate(c="A", r=1)       // same as x=0, y=0
let far = .coordinate(c="AA", r=100)     // x=26, y=99
let deep = .coordinate(c="E", r=8, z=5)  // x=4, y=7, z=5
```

### Coordinate Properties

```
let c = .coordinate(x=4, y=7)

// Standard notation
say c.x                          // 4
say c.y                          // 7

// Sheet notation
say c.column                     // "E"
say c.row                        // 8 (one-based)

// Plate notation (rows=letters, columns=numbers)
say c.rowLetter                  // "H"
say c.columnNumber               // 5 (one-based)

say c.hasZ                       // false
say c.isOrigin                   // false
```

### Coordinate Methods

```
let c = .coordinate(x=2, y=3)

// Navigation — returns new Coordinate
c.right(1)                       // x=3, y=3
c.left(1)                        // x=1, y=3
c.down(1)                        // x=2, y=4
c.up(1)                          // x=2, y=2
c.offset(dx=1, dy=-2)           // x=3, y=1

// Z-axis
c.withZ(5)                       // x=2, y=3, z=5
c.withoutZ()                     // x=2, y=3 (removes z)

// Formatting
c.toSheetNotation()              // "C4"
c.toPlateNotation()              // "D3"
c.toStandardNotation()           // "2,3"
```

### Parsing Coordinates

Coordinates can be parsed from strings:

```
let a = Coordinate.parse("A1")      // sheet notation → x=0, y=0
let b = Coordinate.parse("AA100")   // x=26, y=99
let c = Coordinate.parse("4,7")     // standard notation → x=4, y=7
```

### Coordinate Ranges

Coordinates support range syntax for rectangular regions:

```
let start = Coordinate.parse("A1")
let end = Coordinate.parse("C3")
let range = start..end

for coord in range {
    say coord.toSheetNotation()  // A1, B1, C1, A2, B2, C2, A3, B3, C3
}
say range.size                   // 9
say range.width                  // 3
say range.height                 // 3
```

---

## 18. Version

The Version type implements [Semantic Versioning 2.0](https://semver.org). Versions use the format `major.minor.micro-qualifier-qualifierNumber`.

### Version Literals

In KS, version literals use underscores to separate qualifiers (since `-` is the minus operator):

```
let v1 = 5.0.0
let v2 = 1.2.3_beta
let v3 = 0.2.0_rc_1
let v4 = 1_000.0.0_alpha       // underscores in digits for readability
```

### Constructing Versions

```
let v = Version(5, 2, 7)
let pre = Version(1, 0, 0, "beta", 3)
let parsed = Version.parse("5.2.7-rc-1")
```

### Version Properties

```
let v = 5.2.7_beta_3
say v.major                      // 5
say v.minor                      // 2
say v.micro                      // 7
say v.qualifier                  // "beta"
say v.qualifierNumber            // 3
say v.hasQualifier               // true
say v.isStable                   // false (has qualifier)
say v.isPreRelease               // true
```

### Version Methods

```
let v = 1.2.3_beta_2

v.toStable()                     // 1.2.3 (strip qualifier)
v.toShortString()                // "1.2.3-beta-2" (omit trailing zeros)
v.incrementMajor()               // 2.0.0
v.incrementMinor()               // 1.3.0
v.incrementMicro()               // 1.2.4
v.incrementQualifierNumber()     // 1.2.3-beta-3
v.withQualifier("rc", 1)        // 1.2.3-rc-1
v.isCompatibleWith(1.5.0)       // true (same major)
```

### Version Comparison

Versions are compared component by component. Pre-release versions sort below their stable equivalents:

```
say 1.0.0 < 2.0.0               // true
say 5.2.0_alpha < 5.2.0         // true (qualifier < stable)
say 1.0.0_alpha < 1.0.0_beta    // true (lexicographic qualifier)
say 1.0.0_rc_1 < 1.0.0_rc_2    // true (qualifier number)
```

### Version Constants

```
Version.EMPTY                    // 0.0.0
Version.MIN                      // 0.0.0-AAA (lowest possible)
Version.MAX                      // MAX_INT.MAX_INT.MAX_INT (highest possible)
```

---

## 19. Blob

Blob represents arbitrary binary data (a Binary Large Object), stored internally as a byte array and represented in Ki literal format using Base64 encoding.

### Creating Blobs

```
// From a string (UTF-8 encoded)
let b = Blob("Hello World!")

// Empty blob
let empty = Blob.empty()

// From a UTF-8 string via static method
let b2 = Blob.of("Hello")

// Parse raw Base64
let b3 = Blob.parse("SGVsbG8gV29ybGQh")

// Parse Ki literal format
let b4 = Blob.parseLiteral(".blob(SGVsbG8gV29ybGQh)")
```

### Blob Properties

```
let b = Blob("Hello World!")
say b.size                       // 12
say b.isEmpty                    // false
say b.isNotEmpty                 // true
```

### Blob Methods

```
let b = Blob("Hello World!")

b.toBase64()                     // "SGVsbG8gV29ybGQh"
b.toBase64UrlSafe()              // URL-safe Base64 encoding
b.decodeToString()               // "Hello World!"
b.get(0)                         // first byte
```

### Blob Literals

In Ki literal format, blobs are written as `.blob(Base64Content)`:

```
.blob(SGVsbG8gV29ybGQh)         // short form
.blob()                          // empty blob
```

### Static Methods

```
Blob.empty()                     // empty Blob
Blob.of("text")                  // from UTF-8 string
Blob.parse("SGVsbG8=")           // from raw Base64
Blob.parseLiteral(".blob(...)")  // from Ki literal
Blob.isLiteral(".blob(abc)")     // true (structural check)
```

---

## 20. Email

The Email type represents a validated email address following RFC 5322.

### Creating Emails

```
let e = Email.of("dan@leuck.org")
let tagged = Email.of("dan+spam@leuck.org")
let parsed = Email.parseLiteral("terada.mika@rakuten.co.jp")
```

### Email Properties

```
let e = Email.of("dan+spam@leuck.org")

say e.address                    // "dan+spam@leuck.org"
say e.localPart                  // "dan+spam"
say e.domain                     // "leuck.org"
say e.tld                        // "org"
say e.hasTag                     // true
say e.tag                        // "spam"
say e.baseLocalPart              // "dan"
```

### Email Methods

```
let e = Email.of("dan+spam@leuck.org")

e.withoutTag()                   // Email("dan@leuck.org")
e.withTag("newsletter")          // Email("dan+newsletter@leuck.org")
e.equalsIgnoreDomainCase(other)  // domain-case-insensitive compare
```

### Static Methods

```
Email.of("user@domain.com")      // validated Email
Email.ofOrNull("bad")            // nil (invalid)
Email.isValid("x@y.com")        // true
Email.isLiteral("x@y.com")      // true (structural check)
```

---

## 21. GeoPoint

GeoPoint represents GPS coordinates (latitude, longitude, optional altitude) on Earth, with high-precision `Dec` storage.

### Creating GeoPoints

```
// Ki literal syntax
let sf = .geo(37.7749, -122.4194)
let tokyo = .geo(35.6762, 139.6503, 40.0)     // with altitude

// Constructor
let point = GeoPoint(37.7749, -122.4194)
let withAlt = GeoPoint(35.6762, 139.6503, 40.0)

// Static methods
let p = GeoPoint.of(37.7749, -122.4194)
let parsed = GeoPoint.parse(".geo(37.7749, -122.4194)")
```

### GeoPoint Properties

```
let sf = .geo(37.7749, -122.4194)

say sf.latitude                  // 37.7749 (Dec)
say sf.longitude                 // -122.4194 (Dec)
say sf.altitude                  // nil (not set)
say sf.lat                       // 37.7749 (Double shorthand)
say sf.lon                       // -122.4194 (Double shorthand)
say sf.alt                       // nil

say sf.hasAltitude               // false
say sf.isOrigin                  // false
say sf.isNorthern                // true
say sf.isSouthern                // false
say sf.isEastern                 // false
say sf.isWestern                 // true
```

### GeoPoint Methods

```
let sf = .geo(37.7749, -122.4194)
let tokyo = .geo(35.6762, 139.6503)

// Distance in kilometers (Haversine formula)
let dist = sf.distanceTo(tokyo)  // ~8,270 km

// Bearing in degrees (0=north, 90=east)
let bearing = sf.bearingTo(tokyo)

// Point at distance and bearing from this point
let dest = sf.destination(100.0, 45.0)   // 100km northeast

// Altitude manipulation
let withAlt = sf.withAltitude(100.0)
let noAlt = withAlt.withoutAltitude()

// Formatting
sf.toCompactString()             // ".geo(37.7749, -122.4194)"
```

### GeoPoint Constants

```
GeoPoint.ORIGIN                  // .geo(0.0, 0.0) — "Null Island"
GeoPoint.NORTH_POLE              // .geo(90.0, 0.0)
GeoPoint.SOUTH_POLE              // .geo(-90.0, 0.0)
```

### Static Methods

```
GeoPoint.of(lat, lon)           // validated GeoPoint
GeoPoint.of(lat, lon, alt)     // with altitude
GeoPoint.parse(".geo(...)")     // from Ki literal
GeoPoint.center([p1, p2, p3])  // geographic centroid
GeoPoint.isLiteral(".geo(...)") // structural check
```

---

## 22. NSID

NSID (Namespaced Identifier) is a name with an optional namespace, used for tag names and attributes in KD.

### Creating NSIDs

```
let simple = NSID("name")
let namespaced = NSID("name", "ns")
let anonymous = NSID()          // NSID.ANONYMOUS
let parsed = NSID.parse("ns:name")
```

### NSID Properties

```
let id = NSID("host", "config")

say id.name                      // "host"
say id.namespace                 // "config"
say id.hasNamespace              // true
say id.isAnonymous               // false
say id.toString()                // "config:host"
```

---

## 23. Call and Tag

### Call

A Call represents a function call as data — a name (NSID) with indexed arguments (values) and named arguments (attributes).

```
let c = Call("greet")
let c2 = Call("add", values = [1, 2, 3])
let c3 = Call("config", attributes = [NSID("debug")=true])
```

**Call properties:**

```
let c = Call("func", values = [1, "hello"], attributes = [NSID("key")="val"])

say c.name                       // "func"
say c.namespace                  // ""
say c.values                     // [1, "hello"]
say c.attributes                 // {NSID(key)="val"}
say c.value                      // 1 (first value, convenience)
say c.valueCount                 // 2
say c.attributeCount             // 1
say c.hasValues()                // true
say c.hasAttributes()            // true
```

### Tag

Tag extends Call with support for annotations and child tags, forming the basis of KD documents:

```
let data = lang KD {
    person "Kai" age=30 {
        hobby "coding"
        hobby "cycling"
    }
}

// Navigate tag tree
let person = data.getChild("person")
say person.value                  // "Kai"
say person["age"]                 // 30

// Search children
let hobbies = person.getChildren("hobby")
say hobbies.size                  // 2

// Recursive search
let found = data.findChild("hobby")

// Property access (config-file style)
let config = lang KD {
    host = "localhost"
    port = 8080
}
say config.getProperty("host")   // "localhost"
say config.hasProperty("port")   // true
```

**Tag-specific members:**

```
tag.children                     // child tag list
tag.annotations                  // annotation list
tag.getChild("name")            // first child by name
tag.getChildren("name")         // all children by name
tag.findChild("name")           // recursive search (first)
tag.findChildren("name")        // recursive search (all)
tag.getChildrenInNamespace("ns")
tag.getDescendants()             // all descendants (recursive)
tag.getProperty("key")           // attribute from self or children
tag.getPropertyOrNull("key")     // nil if not found
tag.hasProperty("key")           // true if attribute exists
tag.getProperties()              // all properties as Map<NSID, Any?>
tag.getPropertiesMap()           // all properties as Map<String, Any?>
tag.getChildrenValues()          // values of children as list of lists
```

---

## 24. Regex

KS provides a Regex type for pattern matching, search, and replacement. Regex literals are typically created from verbatim strings using the `.rex` property on strings.

### Creating Regex

```
let pattern = @"^\d{3}-\d{4}$".rex
let greeting = @"hello|hi|hey".rex
let digits = @"\d+".rex
```

### Regex Properties

```
let r = @"\d+".rex
say r.pattern                    // "\\d+"
```

### Regex Methods

**Matching:**

```
let r = @"^\d+$".rex

r.matches("12345")               // true  — full string match
r.matches("abc")                 // false
r.containsMatchIn("abc 123 def") // true  — partial match
```

**Finding:**

```
let r = @"\d+".rex

let match = r.find("abc 123 def 456")
say match.value                  // "123"
say match.groupValues            // ["123"]

let all = r.findAll("abc 123 def 456")
// List of MatchResult objects
```

**MatchResult properties:**

```
let r = @"(\w+)@(\w+)\.(\w+)".rex
let match = r.find("kai@example.com")

say match.value                  // "kai@example.com"
say match.groupValues            // ["kai@example.com", "kai", "example", "com"]
say match.groupCount             // 3 (excludes group 0)
```

**Replacing:**

```
let r = @"\d+".rex

r.replace("a1b2c3", "X")        // "aXbXcX"
r.replaceFirst("a1b2c3", "X")   // "aXb2c3"
```

**Splitting:**

```
let r = @"[,;\s]+".rex

r.split("a, b; c  d")           // ["a", "b", "c", "d"]
```

### Regex in String Methods

Strings can also use Regex objects directly:

```
let text = "Hello World 123"

text.replace(@"\d+".rex, "NUM")      // "Hello World NUM"
text.split(@"\s+".rex)               // ["Hello", "World", "123"]
text.matches(@"^Hello.*$".rex)       // true
```

---

## 25. Pattern Matching with `when`

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

## 26. Error Handling

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

## 27. Constraints

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

## 28. Import System (`use`)

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

## 29. Type Extensions (`extend`)

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

## 30. Reflection

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

## 31. The `say` Statement

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

## 32. Lang Blocks (Embedded DSLs)

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

## 33. Null Safety

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

## 34. The REPL

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

## Appendix: Built-in Methods Reference

### String Methods

| Method / Property | Description |
|---|---|
| `.size`, `.length` | Number of characters |
| `.isEmpty`, `.isNotEmpty` | Emptiness check |
| `.isBlank`, `.isNotBlank` | Blank check (empty or all whitespace) |
| `.first`, `.last` | First/last character |
| `.uppercase`, `.lowercase` | Case conversion |
| `.trim`, `.trimStart`, `.trimEnd` | Strip whitespace |
| `.reversed` | Reversed string |
| `.lines` | Split into list of lines |
| `.indices` | Range of valid indices |
| `.rex` | Compile to Regex |
| `.contains(str)` | Substring check |
| `.startsWith(str)` | Prefix check |
| `.endsWith(str)` | Suffix check |
| `.indexOf(str)`, `.indexOf(str, from)` | First occurrence index |
| `.lastIndexOf(str)` | Last occurrence index |
| `.substring(start)`, `.substring(start, end)` | Extract substring |
| `.split(delimiter)`, `.split(regex)` | Split into list |
| `.replace(old, new)`, `.replace(regex, new)` | Replace occurrences |
| `.replaceFirst(old, new)` | Replace first occurrence |
| `.matches(regex)` | Full string match |
| `.repeat(n)` | Repeat n times |
| `.padStart(len)`, `.padStart(len, char)` | Pad from left |
| `.padEnd(len)`, `.padEnd(len, char)` | Pad from right |
| `.charAt(i)` | Character at index |
| `.toInt()`, `.toDouble()` | Numeric conversion |

### List Methods

| Method / Property | Description |
|---|---|
| `.size`, `.length` | Number of elements |
| `.isEmpty`, `.isNotEmpty` | Emptiness check |
| `.first`, `.last` | First/last element |
| `.contains(elem)` | Element check |
| `.indexOf(elem)` | First occurrence index |
| `.reversed()` | Reversed copy |
| `.sorted()` | Sorted copy |
| `.shuffled()` | Shuffled copy |
| `.reverse()` | **Mutator**: reverse in-place, returns nil |
| `.sort()` | **Mutator**: sort in-place, returns nil |
| `.shuffle()` | **Mutator**: shuffle in-place, returns nil |
| `.take(n)` | First n elements |
| `.drop(n)` | Skip first n elements |
| `.takeWhile { }` | Take while predicate holds |
| `.dropWhile { }` | Drop while predicate holds |
| `.map { }` | Transform elements |
| `.filter { }` | Keep matching elements |
| `.forEach { }` | Execute for each |
| `.reduce { acc, x -> }` | Combine left to right |
| `.fold(init) { acc, x -> }` | Combine with initial value |
| `.flatMap { }` | Map and flatten |
| `.any { }` | True if any match |
| `.all { }` | True if all match |
| `.none { }` | True if none match |
| `list += elem` | Append element in-place |
| `list += [a, b]` | Append list in-place |
| `list -= elem` | Remove first occurrence in-place |

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
| `.bound` | Bound operator string (`".."`, `"..<"`, etc.) |
| `.min`, `.max` | Minimum/maximum (nil if open) |
| `.reversed` | True if start > end |
| `.isClosed`, `.isOpen` | Boundedness check |
| `.isOpenStart`, `.isOpenEnd` | Individual end checks |
| `.startExclusive`, `.endExclusive` | Exclusivity flags |
| `.contains(value)` | Containment check |
| `.toList()`, `.toList(step)` | Materialize to list |
| `.count()`, `.count(step)` | Number of elements |
| `.overlaps(other)` | Overlap check |
| `.intersect(other)` | Intersection range |
| `.clamp(value)` | Clamp value to range |

### Grid Methods

| Method / Property | Description |
|---|---|
| `.width`, `.height` | Dimensions |
| `.size` | Total cell count |
| `.isEmpty`, `.isNotEmpty` | Emptiness check |
| `.elementNullable` | Whether elements can be nil |
| `[x, y]` / `[x, y] = v` | Cell access / assignment |
| `["A", 1]` | Plate notation access |
| `[coord]` | Coordinate access |
| `.transpose()` | Transposed copy |
| `.copy()` | Deep copy |
| `.map { }` | Transform all cells |
| `.forEach { }` | Iterate cells |
| `.forEachIndexed { x, y, v -> }` | Iterate with coordinates |
| `.find { }` | First matching (Coordinate, value) |
| `.findAll { }` | All matching pairs |
| `.count { }` | Count matching cells |
| `.any { }`, `.all { }`, `.none { }` | Predicate tests |
| `.fill(value)` | Fill all cells |
| `.fillRow(y, value)` | Fill a row |
| `.fillColumn(x, value)` | Fill a column |
| `.clear()` | Set all cells to nil |
| `.getRowCopy(y)` | Copy of row data |
| `.getColumnCopy(x)` | Copy of column data |
| `.setRow(y, values)` | Replace a row |
| `.setColumn(x, values)` | Replace a column |
| `.subgrid(x, y, w, h)` | Extract rectangular region |
| `.toList()` | Flatten to list (row-major) |
| `.toRowList()` | List of row lists |

### Version Methods

| Method / Property | Description |
|---|---|
| `.major`, `.minor`, `.micro` | Version components |
| `.qualifier`, `.qualifierNumber` | Pre-release info |
| `.hasQualifier`, `.isStable`, `.isPreRelease` | Status checks |
| `.toStable()` | Strip qualifier |
| `.toShortString()` | Omit trailing zeros |
| `.incrementMajor()` | Bump major, reset minor/micro |
| `.incrementMinor()` | Bump minor, reset micro |
| `.incrementMicro()` | Bump micro |
| `.incrementQualifierNumber()` | Bump qualifier number |
| `.withQualifier(q)`, `.withQualifier(q, n)` | New qualifier |
| `.isCompatibleWith(other)` | Same major version |

### Quantity Methods

| Method / Property | Description |
|---|---|
| `.value` | Numeric value |
| `.unit` | Unit symbol string |
| `.convertTo("unit")` | Convert to target unit |
| `.toSuffixString()` | Always-suffix format |

### Regex Methods

| Method / Property | Description |
|---|---|
| `.pattern` | Pattern string |
| `.matches(str)` | Full string match |
| `.containsMatchIn(str)` | Partial match |
| `.find(str)` | First MatchResult or nil |
| `.findAll(str)` | All MatchResults |
| `.replace(str, replacement)` | Replace all matches |
| `.replaceFirst(str, replacement)` | Replace first match |
| `.split(str)` | Split by pattern |

### MatchResult Properties

| Property | Description |
|---|---|
| `.value` | Matched string |
| `.groupValues` | List of group captures (index 0 = full match) |
| `.groupCount` | Number of capture groups (excluding group 0) |

### Blob Methods

| Method / Property | Description |
|---|---|
| `.size` | Byte count |
| `.isEmpty`, `.isNotEmpty` | Emptiness check |
| `.toBase64()` | Standard Base64 string |
| `.toBase64UrlSafe()` | URL-safe Base64 string |
| `.decodeToString()` | Decode as UTF-8 |
| `.get(index)` | Byte at index |

### Email Methods

| Method / Property | Description |
|---|---|
| `.address` | Full email string |
| `.localPart`, `.domain`, `.tld` | Components |
| `.hasTag`, `.tag`, `.baseLocalPart` | Plus-addressing |
| `.withoutTag()` | Email without tag |
| `.withTag(tag)` | Email with new tag |
| `.equalsIgnoreDomainCase(other)` | Case-insensitive domain compare |

### GeoPoint Methods

| Method / Property | Description |
|---|---|
| `.latitude`, `.longitude`, `.altitude` | Dec coordinates |
| `.lat`, `.lon`, `.alt` | Double shorthand |
| `.hasAltitude` | Altitude present |
| `.isOrigin`, `.isNorthern`, `.isSouthern` | Position checks |
| `.isEastern`, `.isWestern` | Hemisphere checks |
| `.distanceTo(other)` | Great-circle distance (km) |
| `.bearingTo(other)` | Initial bearing (degrees) |
| `.destination(km, bearing)` | Point at distance/bearing |
| `.withAltitude(m)` | Add altitude |
| `.withoutAltitude()` | Remove altitude |
| `.toCompactString()` | Minimal decimal format |

### NSID Properties

| Property | Description |
|---|---|
| `.name` | Name component |
| `.namespace` | Namespace component |
| `.isAnonymous` | True if both empty |
| `.hasNamespace` | True if namespace set |

### Coordinate Methods

| Method / Property | Description |
|---|---|
| `.x`, `.y`, `.z` | Zero-based indices |
| `.column` | Letter column (sheet) |
| `.row` | One-based row (sheet) |
| `.rowLetter` | Letter row (plate) |
| `.columnNumber` | One-based column (plate) |
| `.hasZ`, `.isOrigin` | Status checks |
| `.right(n)`, `.left(n)`, `.up(n)`, `.down(n)` | Navigation |
| `.offset(dx, dy)` | General offset |
| `.withZ(z)`, `.withoutZ()` | Z manipulation |
| `.toSheetNotation()` | "E8" format |
| `.toPlateNotation()` | Plate "H5" format |
| `.toStandardNotation()` | "4,7" format |

---

*This manual is a living document. As KS evolves, new features and refinements will be added.*
