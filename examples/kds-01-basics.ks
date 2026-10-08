# ─────────────────────────────────────────────────────────────────────────────
#  KDS Example 1: Compile a schema, validate documents, read the result
# ─────────────────────────────────────────────────────────────────────────────
#
#  A KDS schema is an ordinary KD document, so it is written here as a KD
#  string. The @""" form is a verbatim multiline string: no escape processing
#  and no $interpolation, which is what you want for schema text that contains
#  regex patterns.
#
#  Libraries: Ki.KD (io.kixi.kd) and its schema package (io.kixi.kd.schema).
#  Ki.Core is pulled in by Ki.KD.

use io.kixi.kd.schema.KDS

say.note "── KDS Example 1: Basics ──"
say

# ── 1. Define and compile a schema ──────────────────────────────────────────

let assaySchema = @"""
schema Assay kds=1 {
    tag assay {
        attribute id type=String required=true minLength=1
        children {
            tag status {
                value type=String enum=[planned running complete]
            }
            tag temperature {
                value type=Quantity dimension=Temperature min=20°C max=40°C
            }
            tag measurement occurs=1.._ {
                attribute well type=String required=true pattern=@"[A-P]\d{1,2}"
                value type=Number min=0
            }
        }
    }
}
"""

# KDS.compile returns a KDSchema. A compiled schema is immutable and reusable.
let schema = KDS.compile(assaySchema)
say "Compiled schema: " + schema.name
say

# ── 2. Validate a document that satisfies the schema ────────────────────────

let goodDoc = """
assay id="HTS-0042" {
    status running
    temperature 37°C
    measurement 0.82 well=A1
    measurement 0.91 well=A2
    measurement 1.07 well=B1
}
"""

# validate(text) parses the KD text and returns a ValidationResult.
# It never modifies the document and never resolves snips.
let good = schema.validate(goodDoc)
say "Good document valid? " + good.isValid
say

# ── 3. Validate a document with several problems ────────────────────────────

let badDoc = """
assay id="" {
    status paused
    temperature 120°F
    measurement -0.5 well=Q1
    measurement 0.77 well=A2
    note "forgot to declare this tag"
}
"""

let bad = schema.validate(badDoc)
say "Bad document valid? " + bad.isValid
say "Issues found: " + bad.issues.size
say

# Each ValidationIssue carries a code, a path into the document, a path into
# the schema, a human message, and optional expected/actual values.
for issue in bad.issues {
    say "  " + issue.code
    say "      where:    " + issue.documentPath
    say "      rule:     " + issue.schemaPath
    say "      message:  " + issue.message
}
say

# ── 4. requireValid() for fail-fast workflows ───────────────────────────────

# When you would rather stop than inspect, requireValid() throws a
# KDValidationException whose message lists every issue. In KS the catch
# variable receives that message as text.
try {
    bad.requireValid()
    say "This line is not reached."
} catch(e) {
    say.warn "requireValid() rejected the document:"
    say e
}
say

say "Done."
