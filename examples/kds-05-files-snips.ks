# ─────────────────────────────────────────────────────────────────────────────
#  KDS Example 5: Schema and data files, snips, and resolve-then-validate
# ─────────────────────────────────────────────────────────────────────────────
#
#  Real projects keep schemas in .kds files and data in .kd files, and large
#  documents pull in shared pieces with .snip(path). KDS deliberately never
#  loads snips during validation: an unresolved directive is reported as
#  UNRESOLVED_SNIP. The pattern is therefore
#
#      KD.readWithSnips(file)  ->  schema.validate(tag)
#
#  This script writes a small project into the system temp folder, runs the
#  pattern both ways, and cleans up after itself.
#
#  File access uses the built-in IO object (IO.read, IO.write), which needs
#  no import. KD.readWithSnips(File) is a JVM overload; see kds-00-probe.ks
#  if that call reports an argument type mismatch on your build.

use io.kixi.kd.KD
use io.kixi.kd.schema.KDS
use java.io.File

say.note "── KDS Example 5: Files and snips ──"
say

# ── 1. Write a small project to disk ────────────────────────────────────────

# Created next to wherever the script runs, and removed at the end.
let root = File("kds-example-5")
let shared = File(root, "shared")
shared.mkdirs()

fun writeFile(folder, name: String, text: String): File {
    let f = File(folder, name)
    IO.write(text, f)
    return f
}

let schemaFile = writeFile(root, "protocol.kds", @"""
schema Protocol kds=1 {
    tag protocol {
        attribute name type=String required=true minLength=3
        attribute version type=Version required=true
        children {
            tag instrument {
                attribute model type=String required=true
                attribute serial type=String required=true pattern=@"[A-Z]{3}\d{6}"
                children {
                    tag calibration occurs=0..1 {
                        attribute date type=Date required=true
                        attribute by type=String
                    }
                }
            }
            tag step occurs=1.._ {
                value type=Int min=1
                value type=String minLength=1
                attribute duration type=Duration
            }
        }
    }
}
""")

# The shared piece. It has one top-level tag, so .snip(shared/instrument)
# inserts that tag; .snip(..., expand=true) would insert its children.
let instrumentFile = writeFile(shared, "instrument.kd", @"""
instrument model="Cytation 5" serial="BTK004512" {
    calibration date=2026/9/30 by="dan"
}
""")

let runFile = writeFile(root, "run.kd", @"""
protocol name="Reporter assay" version=1.2.0 {
    .snip(shared/instrument)
    step 1 "Seed cells" duration=30min
    step 2 "Add compound" duration=15min
    step 3 "Read plate" duration=5min
}
""")

say "Wrote:"
say "   " + schemaFile
say "   " + instrumentFile
say "   " + runFile
say

# ── 2. Compile the schema from its file ─────────────────────────────────────

let schema = KDS.compile(IO.read(schemaFile))
say "Compiled " + schema.name + " from " + schemaFile.getName()
say

# ── 3. Validate without resolving snips: the directive is reported ──────────

let unresolved = KD.readDocument(IO.read(runFile))
let first = schema.validate(unresolved)
say "Validate the raw file (snips unresolved):"
say "   valid=" + first.isValid
for issue in first.issues say "   " + issue.code.padEnd(18) + issue.documentPath + "  " + issue.message
say

# ── 4. Resolve snips first, then validate ───────────────────────────────────

# readWithSnips resolves paths relative to the file that contains the
# directive and returns the single top-level tag (or a synthetic root when
# there are several).
let resolved = KD.readWithSnips(runFile)
say "Resolved document:"
say resolved
say

let second = schema.validate(resolved)
say "Validate the resolved document:"
say "   valid=" + second.isValid
for issue in second.issues say "   " + issue.code.padEnd(18) + issue.documentPath + "  " + issue.message
say

# ── 5. A change in the shared file is caught the same way ───────────────────

writeFile(shared, "instrument.kd", @"""
instrument model="Cytation 5" serial="btk-4512" {
    calibration by="dan"
}
""")

let third = schema.validate(KD.readWithSnips(runFile))
say "After editing shared/instrument.kd:"
say "   valid=" + third.isValid
for issue in third.issues say "   " + issue.code.padEnd(18) + issue.documentPath + "  " + issue.message
say

# ── 6. Clean up ─────────────────────────────────────────────────────────────

for f in [runFile, instrumentFile, schemaFile] f.delete()
shared.delete()
root.delete()
say "Removed " + root.getAbsolutePath()
say

say "Done."
