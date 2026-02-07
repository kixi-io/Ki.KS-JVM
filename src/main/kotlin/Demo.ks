# ════════════════════════════════════════════════════════════════════════════════
#                            KS Language Demo
#                     Showcasing the KS Interpreter v1.0
# ════════════════════════════════════════════════════════════════════════════════

say.note "╔═══════════════════════════════════════════════════════════════════╗"
say.note "║                   KS Interpreter Demo                             ║"
say.note "╚═══════════════════════════════════════════════════════════════════╝"
say ""

# ════════════════════════════════════════════════════════════════════════════════
# PART 1: Functions with Constraints
# ════════════════════════════════════════════════════════════════════════════════

say.note "══════════════════════════════════════════════════════════════════════"
say.note "  Part 1: Functions with Constraints"
say.note "══════════════════════════════════════════════════════════════════════"
say ""


var lemur = "sifaka"
say "I love $lemur"

# --- Comparison Constraints ---

# Age must be positive
fun createPerson(name: String, age: Int > 0): String {
    return "$name is $age years old"
}

say "Creating person with valid age:"
say "  " + createPerson("Alice", 30)
say ""

# Temperature in valid range (Kelvin can't go below absolute zero)
fun formatTemperature(kelvin: Double >= 0.0): String {
    let celsius = kelvin - 273.15
    return "${kelvin}K = ${celsius}°C"
}

say "Temperature conversions:"
say "  " + formatTemperature(373.15)    # Boiling point of water
say "  " + formatTemperature(273.15)    # Freezing point of water
say "  " + formatTemperature(0.0)       # Absolute zero
say ""

# --- Range Constraints ---

# Percentage must be 0-100
fun formatPercent(value: Int in 0..100): String {
    let bars = value / 10
    var bar = ""
    for i in 1..bars { bar = bar + "█" }
    for i in 1..(10 - bars) { bar = bar + "░" }
    return "[$bar] ${value}%"
}

say "Progress bars with range constraint (0..100):"
say "  " + formatPercent(0)
say "  " + formatPercent(25)
say "  " + formatPercent(50)
say "  " + formatPercent(75)
say "  " + formatPercent(100)
say ""

# --- Exclusive Range Constraints ---

# HTTP port (1-65535, exclusive of 0)
fun validatePort(port: Int in 1..65535): String {
    return when {
        port < 1024 -> "Port $port: System/privileged port"
        port < 49152 -> "Port $port: Registered port"
        else -> "Port $port: Dynamic/private port"
    }
}

say "Port validation with range constraint:"
say "  " + validatePort(80)
say "  " + validatePort(443)
say "  " + validatePort(8080)
say "  " + validatePort(50000)
say ""

# --- Constrained Variables ---

say "Constrained variable declarations:"
var score: Int in 0..100 = 85
say "  Initial score: $score"
score = 92
say "  Updated score: $score"
say ""

# ════════════════════════════════════════════════════════════════════════════════
# PART 2: When Expressions
# ════════════════════════════════════════════════════════════════════════════════

say.note "══════════════════════════════════════════════════════════════════════"
say.note "  Part 2: When Expressions"
say.note "══════════════════════════════════════════════════════════════════════"
say ""

# --- Basic When with Subject ---

fun describeNumber(n: Int): String {
    return when n {
        0 -> "zero"
        1 -> "one"
        2 -> "two"
        else -> "many ($n)"
    }
}

say "Basic when expression:"
say "  describeNumber(0) = " + describeNumber(0)
say "  describeNumber(1) = " + describeNumber(1)
say "  describeNumber(2) = " + describeNumber(2)
say "  describeNumber(42) = " + describeNumber(42)
say ""

# --- When with Multiple Matchers ---

fun isWeekend(day: String): String {
    return when day {
        "Saturday", "Sunday" -> "Yes! 🎉 Time to relax"
        "Friday" -> "Almost there! 🙂"
        "Monday" -> "Back to work... 😴"
        else -> "Regular weekday"
    }
}

say "When with multiple matchers:"
say "  Friday: " + isWeekend("Friday")
say "  Saturday: " + isWeekend("Saturday")
say "  Monday: " + isWeekend("Monday")
say "  Wednesday: " + isWeekend("Wednesday")
say ""

# --- When with Range Matching ---

fun gradeScore(score: Int): String {
    return when {
        score >= 90 -> "A - Excellent!"
        score >= 80 -> "B - Good job"
        score >= 70 -> "C - Satisfactory"
        score >= 60 -> "D - Needs improvement"
        else -> "F - Please see instructor"
    }
}

say "When with conditional expressions:"
say "  Score 95: " + gradeScore(95)
say "  Score 82: " + gradeScore(82)
say "  Score 71: " + gradeScore(71)
say "  Score 55: " + gradeScore(55)
say ""

# --- Enum with When (DPEC - Dot Prefixed Enum Constant) ---

enum Color {
    RED, GREEN, BLUE, YELLOW, CYAN, MAGENTA
}

fun colorToHex(c: Color): String {
    return when c {
        .RED -> "#FF0000"
        .GREEN -> "#00FF00"
        .BLUE -> "#0000FF"
        .YELLOW -> "#FFFF00"
        .CYAN -> "#00FFFF"
        .MAGENTA -> "#FF00FF"
    }
}

say "Enum with DPEC (Dot-Prefixed Enum Constant) matching:"
say "  Color.RED    -> " + colorToHex(Color.RED)
say "  Color.GREEN  -> " + colorToHex(Color.GREEN)
say "  Color.BLUE   -> " + colorToHex(Color.BLUE)
say "  Color.YELLOW -> " + colorToHex(Color.YELLOW)
say ""

# --- When Expression as Value ---

let status = "active"
let message = when status {
    "active" -> "System is running"
    "paused" -> "System is paused"
    "stopped" -> "System is stopped"
    else -> "Unknown status"
}
say "When as expression value: $message"
say ""

# --- When with In-Check ---

fun categorizePort(port: Int): String {
    return when {
        port in 1..79 -> "Low numbered service port"
        port in 80..443 -> "Common web port"
        port in 1024..49151 -> "Registered port"
        else -> "Dynamic or reserved port"
    }
}

say "When with in-check:"
say "  Port 22: " + categorizePort(22)
say "  Port 80: " + categorizePort(80)
say "  Port 3306: " + categorizePort(3306)
say ""

# ════════════════════════════════════════════════════════════════════════════════
# PART 3: Embedded KD (KD Data Language)
# ════════════════════════════════════════════════════════════════════════════════

say.note "══════════════════════════════════════════════════════════════════════"
say.note "  Part 3: Embedded KD (lang KD { ... })"
say.note "══════════════════════════════════════════════════════════════════════"
say ""

# --- Simple KD Document ---

var root = lang KD {
    # A configuration document
    app "MyApp" version="2.0.1" {
        server host="localhost" port=8080 {
            ssl enabled=true
            timeout 30
        }

        database {
            connection "postgresql://localhost:5432/mydb"
            pool minSize=5 maxSize=20
        }

        @deprecated
        legacyMode false
    }
}

say "KD Document:"
say root
say ""

# --- KD with Computed Values ---

let appName = "ConfigDemo"
let serverPort = 3000
let maxConnections = 100

var config = lang KD {
    @version(1 0 0)
    configuration name="ConfigDemo" {
        http port=3000
        limits {
            maxConnections 100
            timeout $(30 * 60)
        }
    }
}

var more = 2

// Fix interpolation
var config2 = lang KD {
    @version(1 0 0)
    configuration name=$appName {
        http port=$serverPort
        limits {
            maxConnections $maxConnections
            timeout $(30 * 3 * more)
        }
        Hi {
            Bula "Hello, world!"
        }
    }
}



say "KD with computed values (interpolation):"
say config2
say ""

# --- Accessing KD Tag Properties ---

say "Accessing KD tag properties:"
say "  root.name: " + root.name
say "  root.attributes: " + root.attributes
say "  root.children count: " + root.children.size
say ""

# --- Complex KD Structure ---

var persons = lang KD {
    people {
        @role("admin")
        person "Alice" age=30 active=true

        @role("user")
        person "Bob" age=25 active=true

        @role("guest")
        person "Charlie" age=35 active=false
    }
}

say "Complex KD structure:"
say persons
say ""

# ════════════════════════════════════════════════════════════════════════════════
# PART 4: Combining Features
# ════════════════════════════════════════════════════════════════════════════════

say.note "══════════════════════════════════════════════════════════════════════"
say.note "  Part 4: Combining Features"
say.note "══════════════════════════════════════════════════════════════════════"
say ""

# --- Enums with Parameters ---

enum LogLevel(let severity: Int, let prefix: String) {
    DEBUG(0, "[DEBUG]"),
    INFO(1, "[INFO]"),
    WARN(2, "[WARN]"),
    ERROR(3, "[ERROR]"),
    FATAL(4, "[FATAL]")
}

fun log(level: LogLevel, message: String): String {
    return "${level.prefix} $message"
}

say "Parameterized enum logging:"
say "  " + log(LogLevel.DEBUG, "Starting application")
say "  " + log(LogLevel.INFO, "Server listening on port 8080")
say "  " + log(LogLevel.WARN, "Connection timeout approaching")
say "  " + log(LogLevel.ERROR, "Failed to connect to database")
say ""

# --- HTTP Status Codes with When ---

enum HttpStatus(let code: Int) {
    OK(200),
    CREATED(201),
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    NOT_FOUND(404),
    INTERNAL_ERROR(500)
}

fun describeStatus(status: HttpStatus): String {
    return when status {
        .OK -> "Success - Request completed"
        .CREATED -> "Success - Resource created"
        .BAD_REQUEST -> "Client Error - Invalid request"
        .UNAUTHORIZED -> "Client Error - Authentication required"
        .NOT_FOUND -> "Client Error - Resource not found"
        .INTERNAL_ERROR -> "Server Error - Something went wrong"
    }
}

say "HTTP status descriptions using DPEC:"
for status in [HttpStatus.OK, HttpStatus.NOT_FOUND, HttpStatus.INTERNAL_ERROR] {
    say "  ${status.code}: " + describeStatus(status)
}
say ""

# --- Recursive Function with Constraint ---

fun factorial(n: Int >= 0): Int {
    return when n {
        0, 1 -> 1
        else -> n * factorial(n - 1)
    }
}

say "Factorial with constraint (n >= 0):"
for i in 0..7 {
    say "  $i! = " + factorial(i)
}
say ""

# --- Fibonacci with Memoization Pattern ---

fun fib(n: Int >= 0): Int {
    return when {
        n == 0 -> 0
        n == 1 -> 1
        else -> fib(n - 1) + fib(n - 2)
    }
}

say "Fibonacci sequence:"
var fibStr = ""
for i in 0..12 {
    fibStr = fibStr + fib(i)
    if i < 12 { fibStr = fibStr + ", " }
}
say "  " + fibStr
say ""

# ════════════════════════════════════════════════════════════════════════════════
# PART 5: Classes with Constraints
# ════════════════════════════════════════════════════════════════════════════════

say.note "══════════════════════════════════════════════════════════════════════"
say.note "  Part 5: Classes with Constraints"
say.note "══════════════════════════════════════════════════════════════════════"
say ""

# --- Class with Constrained Constructor Parameters ---

class Temperature(var kelvin: Double >= 0.0) {
    fun toCelsius(): Double = kelvin - 273.15
    fun toFahrenheit(): Double = (kelvin - 273.15) * 9.0 / 5.0 + 32.0

    fun format(): String {
        return "${kelvin}K (${toCelsius()}°C / ${toFahrenheit()}°F)"
    }
}

say "Temperature class with constraint (kelvin >= 0):"
let boiling = Temperature(373.15)
let freezing = Temperature(273.15)
let absolute = Temperature(0.0)

say "  Boiling point:  " + boiling.format()
say "  Freezing point: " + freezing.format()
say "  Absolute zero:  " + absolute.format()
say ""

# --- Class with Constrained Properties ---

class BankAccount(let owner: String, var balance: Double >= 0.0) {
    fun deposit(amount: Double > 0.0) {
        balance = balance + amount
    }

    fun withdraw(amount: Double > 0.0): Bool {
        if amount <= balance {
            balance = balance - amount
            return true
        }
        return false
    }

    fun statement(): String {
        return "Account: $owner | Balance: $${balance}"
    }
}

say "BankAccount with constraints:"
let account = BankAccount("Alice", 1000.0)
say "  Initial: " + account.statement()

account.deposit(500.0)
say "  After deposit $500: " + account.statement()

let success = account.withdraw(300.0)
say "  Withdraw $300 (success=$success): " + account.statement()
say ""

# ════════════════════════════════════════════════════════════════════════════════
# PART 6: Quantities & Units of Measure
# ════════════════════════════════════════════════════════════════════════════════

say.note "══════════════════════════════════════════════════════════════════════"
say.note "  Part 6: Quantities & Units of Measure"
say.note "══════════════════════════════════════════════════════════════════════"
say ""

# --- Basic Quantity Literals ---

say "Basic quantity literals:"
say "  Length:      " + 23cm
say "  Mass:       " + 1000g
say "  Volume:     " + 500mℓ
say "  Time:       " + 30s
say ""

# --- Currency Literals (prefix notation) ---

say "Currency literals (prefix notation):"
say "  Dollars:    " + $23.53
say "  Dollars:    " + $23.53 * 2
say "  Euros:      " + €50.25
say "  Yen:        " + ¥10000
say "  Pounds:     " + £75.50
say "  Bitcoin:    " + ₿0.5
say "  Ether:      " + Ξ2.5
say ""

# --- Quantity Arithmetic (with unit conversion) ---

say "Quantity arithmetic with automatic unit conversion:"
say "  1m + 2cm          = " + (1m + 2cm)            # → 102cm
say "  1000g - 250g      = " + (1000g - 250g)        # → 750g
say "  5kg + 500g        = " + (5kg + 500g)           # → 5500g
say ""

# --- Scalar Arithmetic ---

say "Quantity-scalar arithmetic:"
say "  10cm * 3           = " + 10cm * 3              # → 30cm
say "  1000g / 4          = " + 1000g / 4             # → 250g
say "  -5kg               = " + -5kg                  # → -5kg
say ""

# --- Quantity Comparison ---

say "Quantity comparison:"
say "  1m > 50cm         = " + (1m > 50cm)            # → true
say "  500g == 500g      = " + (500g == 500g)          # → true
say "  100cm <= 1m       = " + (100cm <= 1m)           # → true
say ""

# --- Member Access ---

let distance = 42km
say "Member access on quantities:"
say "  distance           = " + distance
say "  distance.value     = " + distance.value
say "  distance.unit      = " + distance.unit
say ""

# --- Combine Operator (⚭ for unit composition) ---

say "Unit composition with ⚭ (combine operator):"
say "  4cm ⚭ 3cm         = " + (4cm ⚭ 3cm)           # → 12cm² (area)
say "  2m ⚭ 2m           = " + (2m ⚭ 2m)             # → 4m² (area)
say "  3cm ⚭ 3cm ⚭ 3cm  = " + (3cm ⚭ 3cm ⚭ 3cm)   # → 27cm³ (volume)
say ""

# --- Scientific Notation ---

say "Scientific notation in quantities:"
say "  Speed of light:  " + 3e8mps
say "  Nano-distance:   " + 5en7m
say ""

# --- Quantities in Collections ---

let measurements = [10cm, 25cm, 50cm, 100cm]
say "Quantities in a list:"
say "  measurements = " + measurements
say ""

# ════════════════════════════════════════════════════════════════════════════════
# FINALE
# ════════════════════════════════════════════════════════════════════════════════

say ""
say.note "══════════════════════════════════════════════════════════════════════"
say.note "  Demo Complete!"
say.note "══════════════════════════════════════════════════════════════════════"
say ""
say "This demo showcased:"
say "  ✓ Functions with comparison constraints (> 0, >= 0.0)"
say "  ✓ Functions with range constraints (in 0..100, in 1..65535)"
say "  ✓ Constrained variable declarations"
say "  ✓ When expressions with subjects"
say "  ✓ When expressions with multiple matchers"
say "  ✓ When expressions with conditional branches"
say "  ✓ DPEC (Dot-Prefixed Enum Constant) matching"
say "  ✓ Embedded KD documents (lang KD { ... })"
say "  ✓ KD with computed values (interpolation)"
say "  ✓ Parameterized enums"
say "  ✓ Classes with constrained constructor parameters"
say "  ✓ Recursive functions with constraints"
say "  ✓ Quantity literals with units (23cm, 1000g, 500mℓ)"
say "  ✓ Currency literals with prefix notation ($23.53, €50.25)"
say "  ✓ Quantity arithmetic with unit conversion (1m + 2cm)"
say "  ✓ Unit composition with combine operator (4cm ⚭ 3cm)"
say "  ✓ Scientific notation in quantities (3e8mps, 5en7m)"
say ""
say "KS: Where type safety meets expressiveness! 🚀"
