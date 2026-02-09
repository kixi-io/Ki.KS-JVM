// ============================================================================
// ExtendTest.ks — Extension method tests
//
// Tests:
//   1. Extension methods on structs
//   2. Extension methods on classes
//   3. Extension methods on traits (default impl additions)
//   4. Extension method accessing `this`
//   5. Class trait conformance type check (bonus fix)
// ============================================================================

// --- Setup ------------------------------------------------------------------

struct Point(let x: Double, let y: Double)

class Person(let name: String, var age: Int)

trait Describable {
    fun label(): String
}

// --- 1. Extend struct with methods ------------------------------------------

extend Point {
    fun distanceTo(other: Point): Double {
        let dx = this.x - other.x
        let dy = this.y - other.y
        return (dx * dx + dy * dy) ** 0.5
    }

    fun translate(dx: Double, dy: Double): Point {
        return Point(this.x + dx, this.y + dy)
    }
}

let a = Point(0.0, 0.0)
let b = Point(3.0, 4.0)
say a.distanceTo(b)    // 5.0

let c = a.translate(1.0, 2.0)
say c                  // Point(x=1.0, y=2.0)

// --- 2. Extend class with methods ------------------------------------------

extend Person {
    fun greet(): String = "Hi, I'm $name and I'm $age years old."
}

let p = Person("Alice", 30)
say p.greet()   // Hi, I'm Alice and I'm 30 years old.

// --- 3. Extend trait with default implementation ----------------------------

extend trait Describable {
    fun shortLabel(): String = "(" + label() + ")"
}

struct Color(let r: Int, let g: Int, let b: Int): Describable {
    fun label(): String = "rgb($r, $g, $b)"
}

let red = Color(255, 0, 0)
say red.label()         // rgb(255, 0, 0)
say red.shortLabel()    // (rgb(255, 0, 0))

// --- 4. Extension method accessing struct properties via `this` -------------

struct Rect(let w: Double, let h: Double)

extend Rect {
    fun area(): Double = this.w * this.h
    fun perimeter(): Double = 2.0 * (this.w + this.h)
    fun isSquare(): Bool = this.w == this.h
}

let r = Rect(3.0, 4.0)
say r.area()        // 12.0
say r.perimeter()   // 14.0
say r.isSquare()    // false

let sq = Rect(5.0, 5.0)
say sq.isSquare()   // true

// --- 5. Class trait conformance type check (bonus fix) ----------------------

trait Greetable {
    fun greetMessage(): String
}

class Robot(let id: String): Greetable {
    fun greetMessage(): String = "I am robot $id"
}

let bot = Robot("R2")
say bot is Robot       // true
say bot is Greetable   // true  (was broken — same fix as struct trait check)
