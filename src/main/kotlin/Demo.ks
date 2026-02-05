# ============================================
# KS Demo - Testing the Interpreter
# ============================================

# --- Say Statement Variants ---
say "Aloha world!"
say.note "This is a note (bold)"
say.warn "This is a warning (orange)"
say.error "This is an error (red)"

# --- Variables ---
var name = "KS"
let version = 1.0
say "Language: " + name + " v" + version

# Reassignment (var is mutable)
var count = 0
count = 42
say "Count: " + count

# --- Number Types ---
say "--- Number Types ---"

# Int
let i = 42
say "Int: " + i

# Long
let l = 9_000_000_000L
say "Long: " + l

# Float
let f = 3.14f
say "Float: " + f

# Double
let d = 2.71828
say "Double: " + d

# BigDecimal (Dec)
let bd = 123.456BD
        say "Dec: " + bd

# Hex and Binary
let hex = 0xFF
let bin = 0b1010
say "Hex 0xFF = " + hex
say "Binary 0b1010 = " + bin

# --- Arithmetic ---
        say "--- Arithmetic ---"
say "2 + 3 = " + (2 + 3)
say "10 - 4 = " + (10 - 4)
say "6 * 7 = " + (6 * 7)
say "20 / 4 = " + (20 / 4)
say "17 % 5 = " + (17 % 5)
say "2 ** 10 = " + (2 ** 10)

# Negative numbers
        say "-5 + 3 = " + (-5 + 3)

# --- Comparison ---
        say "--- Comparison ---"
say "5 > 3: " + (5 > 3)
say "5 < 3: " + (5 < 3)
say "5 == 5: " + (5 == 5)
say "5 != 3: " + (5 != 3)
say "5 >= 5: " + (5 >= 5)
say "5 <= 4: " + (5 <= 4)

# --- Logical ---
        say "--- Logical ---"
say "true && true: " + (true && true)
say "true && false: " + (true && false)
say "false || true: " + (false || true)
say "!false: " + (!false)

# --- String Operations ---
say "--- Strings ---"
let greeting = "Hello"
let target = "World"
say greeting + ", " + target + "!"

# --- Done ---
        say ""
say.note "Demo complete!"
