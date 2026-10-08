# ─────────────────────────────────────────────────────────────────────────────
#  KDS Example 6: KD documents that declare their own schema
# ─────────────────────────────────────────────────────────────────────────────
#
#  Two ways for a .kd file to say which schema it follows:
#
#    1. A .schema(path) directive as the first tag, naming a .kds file next
#       to the document (run-valid.kd, run-invalid.kd).
#    2. The schema itself as the first tag, data after it
#       (inline-valid.kd, inline-invalid.kd).
#
#  KD.load(file) is the one call for any KD file: it parses, resolves snips,
#  and if the document declares a schema, compiles it and validates. A file
#  with no declaration loads as plain data (schemas are optional). A schema
#  problem (missing file, bad schema) is thrown; a data problem is returned
#  as issues, so you can report it however you like. report() gives the
#  standard summary.
#
#  Run from the Ki.KS-JVM root so the relative folder resolves.

use io.kixi.kd.KD, LoadOptions
use java.io.File

let folder = "examples/self-describing"

say.note "── KDS Example 6: self-describing documents ──"
say

fun show(name: String) {
    say.note name
    try {
        let doc = KD.load(File(folder, name))
        when {
            !doc.hasSchema -> say "  no schema declared; loaded " + doc.tags.size + " top-level tag(s) as plain data"
            doc.isValid    -> say "  schema " + doc.schema.name + ": valid, " + doc.tags.size + " top-level tag(s)"
            else           -> say "🟠 " + doc.report()
        }
    } catch(e) {
        say.error "🚨 " + e.substring(e.indexOf("KD.load: ") + 9)
    }
    say
}

# ── 1. External schema via .schema(assay) ───────────────────────────────────

show("run-valid.kd")
show("run-invalid.kd")

# ── 2. Inline schema as the first tag ───────────────────────────────────────

show("inline-valid.kd")
show("inline-invalid.kd")

# ── 3. No declaration: a plain KD file, loaded the same way ─────────────────

show("plain.kd")

# When a schema is mandatory, say so and the missing declaration is an error.
try {
    KD.load(File(folder, "plain.kd"), LoadOptions.SCHEMA_REQUIRED)
} catch(e) {
    say.error "🚨 with SCHEMA_REQUIRED: " + e
}
say

# ── 4. Schema problems are thrown, not returned ─────────────────────────────

show("broken-schema.kd")
show("missing-schema.kd")

# ── 5. The data is yours after validation ───────────────────────────────────

let run = KD.load(File(folder, "run-valid.kd")).requireValid()
let assay = run.root
say "Assay " + assay["id"] + " by " + assay["operator"] + " on " + assay.getChild("instrument")["model"]
let signals = assay.children.filter { it.name == "measurement" }.map { it.value }
say "Mean signal: " + signals.fold(0.0) { acc, v -> acc + v } / signals.size
say

say "Done."
