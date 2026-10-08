# ─────────────────────────────────────────────────────────────────────────────
#  KDS Example 2: Validate documents built in KS with lang KD { ... }
# ─────────────────────────────────────────────────────────────────────────────
#
#  KS can build KD tag trees directly with an embedded lang KD block. The
#  values inside the block are KS expressions, so you can interpolate
#  variables, compute values, and use quantities. The result is a real
#  io.kixi.kd.Tag, which KDSchema.validate accepts without re-parsing.
#
#  Two things to remember inside lang KD blocks:
#    * strings are quoted ("A1"), because a bare word is a KS identifier
#    * the schema itself stays a KD string, since schema vocabulary such as
#      occurs=1.._ and type=String is KD, not KS

use io.kixi.kd.schema.KDS

say.note "── KDS Example 2: lang KD blocks ──"
say

# ── 1. The schema ───────────────────────────────────────────────────────────

let schema = KDS.compile(@"""
schema PlateRun kds=1 {
    tag run {
        attribute plate type=String required=true pattern=@"PL-\d{4}"
        attribute operator type=String required=true
        children {
            tag read occurs=1.._ {
                attribute well type=String required=true pattern=@"[A-H](1[0-2]|[1-9])"
                value type=Double min=0.0 max=4.0
            }
            tag incubation occurs=0..1 {
                value type=Quantity dimension=Temperature min=4°C max=42°C
                value type=Int min=1 max=1440
            }
        }
    }
}
""")
say "Schema: " + schema.name
say

# ── 2. Build a document from KS values ──────────────────────────────────────

let plateId = "PL-0017"
let operator = "dan"
let baseline = 0.25
let signal = [1.25, 1.5, 1.0, 2.5]
let wells = ["A1", "A2", "A3", "A4"]

# A single top-level tag comes back as that Tag.
let run = lang KD {
    run plate=$plateId operator=$operator {
        read ${signal[0] - baseline} well=${wells[0]}
        read ${signal[1] - baseline} well=${wells[1]}
        read ${signal[2] - baseline} well=${wells[2]}
        read ${signal[3] - baseline} well=${wells[3]}
        incubation 37°C 120
    }
}

say "Document built in KS:"
say run
say

let result = schema.validate(run)
say "Valid? " + result.isValid
say

# ── 3. Catch mistakes before the document leaves the script ─────────────────

let wrongPlate = "PLATE-17"          # does not match PL-\d{4}
let hotIncubation = 60°C              # above the 42°C limit

let broken = lang KD {
    run plate=$wrongPlate operator=$operator {
        read 5.5 well="A1"
        read 0.4 well="Z1"
        incubation $hotIncubation 30
    }
}

let check = schema.validate(broken)
say "Broken document valid? " + check.isValid
for issue in check.issues {
    say "  " + issue.code.padEnd(16) + issue.documentPath + "  " + issue.message
}
say

# ── 4. Several top-level tags ───────────────────────────────────────────────

# When a lang KD block has more than one top-level tag, KS wraps them in a
# synthetic "root" tag, just like KD.read. KDS treats "root" as a real tag
# name, so pass the children instead of the wrapper.
let batchSchema = KDS.compile("schema Batch kds=1 { tag sample occurs=2.._ { value type=Int min=1 } }")

let batch = lang KD {
    sample 1
    sample 2
    sample 3
}
say "Wrapper tag name: " + batch.name
say "Validate root directly:  " + batchSchema.validate(batch).isValid
say "Validate root.children:  " + batchSchema.validate(batch.children).isValid
say

say "Done."
