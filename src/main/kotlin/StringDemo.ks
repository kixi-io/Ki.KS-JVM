# ============================================================
# KS Strings — Literal Types and Interpolation
# ============================================================

say.note "--- String ---"
var rich = 1_000_000
say "She is $rich"
say "She is ${rich}"

# --- Standard Strings ---
# Double-quoted. Supports escape sequences and interpolation.

say "Hello, world!"
say "Tabs:\tand\nnewlines"
say "Backslash: \\ Quote: \""
say "Unicode coffee: \u2615"
say ""

# --- Verbatim Strings ---
# Prefixed with @. No escape processing — backslashes are literal.

say @"Regex: ^\d{3}-\d{4}$"
say @"Windows path: C:\Users\name\Documents"
say ""

# --- Multiline Strings ---
# Triple-quoted. KD/Swift-style dedenting based on closing """ position.

let html = """
    <html>
        <body>
            <h1>Hello</h1>
        </body>
    </html>
    """
say html
say ""

# --- Verbatim Multiline Strings ---
# @""" combines multiline dedenting with verbatim (no escape) behavior.

let pattern = @"""
    ^\d{3}
    -\d{4}$
    """
say pattern
say ""

# --- Backtick Strings ---
# Completely raw — no escapes, no interpolation.

say `hello\nworld`
say `This $is literal — no interpolation here`
say ""

# --- String Interpolation: $identifier ---

let name = "Kai"
let age = 28
say "My name is $name and I am $age years old."
say ""

# --- String Interpolation: ${expression} ---

say.note "String Interpolation"

let x = 7
say "The answer is ${x * 6}."

let items = ["apple", "banana", "cherry"]
say "First item: ${items[0]}"
say "Count: ${items.size}"
say ""

var hi = "Hello"
say "$hi World!"
say "${hi*2} World!"

# --- Interpolation in Multiline Strings ---

let title = "Status Report"
let count = 42

let doc = """
    # $title
    Items processed: ${count * 2}
    Status: complete
    """
say doc
say ""

# --- Literal $ ---
# In standard strings, $ followed by a digit is literal (not interpolation).

say "Price: $100"

# In verbatim and backtick strings, $ is always literal.

say @"Cost is $100"
say `Total: $500`
