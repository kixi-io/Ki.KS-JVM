# ─────────────────────────────────────────────────────────────────────────────
#  KDS Example 4: Diagnostics, error kinds, and validation options
# ─────────────────────────────────────────────────────────────────────────────
#
#  Three different things can go wrong, and KDS keeps them apart:
#    1. The KD text does not parse            -> KDParseException
#    2. The KD parses but is not a valid schema -> KDSchemaException
#    3. The schema is fine but the data breaks its rules
#                                              -> ValidationResult with issues
#
#  Only the third case is "normal" and returns a result object. The other two
#  are thrown. In KS a thrown JVM exception arrives in catch(e) as a message
#  string, prefixed with the call that failed.
#
#  The second half shows ValidationOptions and how to turn a result into a
#  compact report with a KS function.

use io.kixi.kd.KD
use io.kixi.kd.schema.KDS, ValidationOptions

say.note "── KDS Example 4: Diagnostics ──"
say

# ── 1. Three kinds of failure ───────────────────────────────────────────────

say "1. KD that does not parse:"
try {
    KD.readDocument("experiment { title \"unterminated")
} catch(e) {
    say "   " + e
}
say

say "2. Valid KD that is not a valid schema (note the schema path):"
let brokenSchemas = [
    `schema S kds=1 { tag a { value type=Int pattern="x" } }`,
    `schema S kds=1 { tag a { value type=Int min=5 max=1 } }`,
    `schema S kds=1 { tag a occurs=-1 }`,
    `schema S { tag a }`
]
for text in brokenSchemas {
    try {
        KDS.compile(text)
    } catch(e) {
        say "   " + e
    }
}
say

say "3. Data that breaks a valid schema returns a result instead of throwing:"
let schema = KDS.compile(@"""
schema Experiment kds=1 {
    tag experiment occurs=1.._ {
        attribute id type=String required=true pattern=@"EXP-\d+"
        attribute replicates type=Int min=1 max=12
        children {
            tag sample occurs=1.._ {
                value type=String minLength=1
                value type=Double min=0.0 required=false
            }
        }
    }
}
""")

let data = """
experiment id="EXP-1" replicates=3 {
    sample "wt" 1.0
    sample "ko" 0.4
}
experiment id="exp-two" replicates=0 {
    sample "" -1.0
    sample "mut" 0.9 "extra"
}
experiment id="EXP-3"
experiment id="EXP-4" replicates=2 {
    sample "wt" 1.1
    control "buffer"
}
"""
let result = schema.validate(data)
say "   valid=" + result.isValid + "  issues=" + result.issues.size + "  truncated=" + result.truncated
say

# ── 2. Reading issues ───────────────────────────────────────────────────────

# documentPath uses zero-based same-name sibling indices, so /experiment[1]
# is the second experiment tag. schemaPath points at the rule that failed.
# expected and actual are present for bound, count, and occurrence issues.
say "Issues with expected/actual where KDS provides them:"
for issue in result.issues {
    var line = "   " + issue.code.padEnd(20) + issue.documentPath
    if issue.expected != nil line += "   expected " + issue.expected + ", got " + issue.actual
    say line
}
say

# Group by code. KS maps use key=value syntax; [=] is an empty map.
var byCode = [=]
for issue in result.issues {
    byCode[issue.code] = (byCode[issue.code] ?: 0) + 1
}
say "Issue counts by code:"
for code in byCode.keys say "   " + code.padEnd(20) + byCode[code]
say

# Group by top-level tag, which is useful for per-record reports.
fun recordOf(path: String): String {
    let m = @"^/([^/]+)".rex.find(path)
    return if m != nil m.groupValues[1] else "(document)"
}
var byRecord = [=]
for issue in result.issues {
    let key = recordOf(issue.documentPath)
    byRecord[key] = (byRecord[key] ?: 0) + 1
}
say "Issue counts by record:"
for key in byRecord.keys say "   " + key.padEnd(20) + byRecord[key]
say

# ── 3. ValidationOptions ────────────────────────────────────────────────────

# ValidationOptions(maxIssues, maxDepth). The defaults are 100 and 128.
# When the issue cap is reached, truncated=true tells you the list is
# incomplete, so a cheap "first few problems" pass is safe to do.
let quick = schema.validate(data, ValidationOptions(3, 128))
say "With maxIssues=3: issues=" + quick.issues.size + "  truncated=" + quick.truncated
for issue in quick.issues say "   " + issue.code.padEnd(20) + issue.message
say

# maxDepth guards against runaway nesting in programmatically built trees.
let deep = """
experiment id="EXP-9" {
    sample "a" { sample "b" { sample "c" { sample "d" } } }
}
"""
let shallow = schema.validate(deep, ValidationOptions(100, 2))
say "With maxDepth=2 on a deeply nested document:"
for issue in shallow.issues say "   " + issue.code.padEnd(20) + issue.message
say

# ── 4. A reusable reporter ──────────────────────────────────────────────────

fun describe(result): String {
    if result.isValid return "OK"
    let n = result.issues.size
    let head = result.issues.take(3).map { it.code + "@" + it.documentPath }
    var text = n + " issue(s): " + head
    if n > 3 text += " ..."
    if result.truncated text += " (truncated)"
    return text
}

say "describe():"
say "   good doc  -> " + describe(schema.validate(`experiment id="EXP-1" { sample "wt" }`))
say "   bad doc   -> " + describe(result)
say "   capped    -> " + describe(quick)
say

say "Done."
