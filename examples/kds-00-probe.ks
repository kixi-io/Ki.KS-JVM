# ─────────────────────────────────────────────────────────────────────────────
#  kds-00-probe.ks: does this Ki.KS / Ki.KD build support the KDS examples?
# ─────────────────────────────────────────────────────────────────────────────
#
#  Run this first. It makes no changes and touches no files except a small
#  temp file it removes. Each check prints PASS or FAIL with the reason, and
#  the summary at the end says which of kds-01 .. kds-05 will run.
#
#  The checks correspond to behaviour that differed between the public
#  Ki.KS-JVM main branch and what the examples need. If every check passes,
#  your local tree already has the fixes and no patch is needed.

use io.kixi.kd.KD
use java.io.File

var failures = []

fun check(name: String, body): Bool {
    let outcome = try {
        body()
        "ok"
    } catch(e) {
        "" + e
    }
    if outcome == "ok" {
        say "PASS  " + name
        return true
    }
    say "FAIL  " + name
    say "      " + outcome
    failures += name
    return false
}

say.note "── KS / KD / KDS probe ──"
say

# 1. Is the KDS package on the classpath at all?
let hasKDS = check("KDS package available (io.kixi.kd.schema.KDS)") { ->
    use io.kixi.kd.schema.KDS
    let s = KDS.compile("schema Probe kds=1 { tag a { value type=Int } }")
    if !s.validate("a 1").isValid throw "validate returned issues for valid data"
}

# 2. KD.readDocument (added with KDS) resolves to the String overload.
check("KD.readDocument(String) returns the top-level tag list") { ->
    let tags = KD.readDocument("a 1; b 2")
    if tags.size != 2 throw "expected 2 tags, got " + tags.size
}

# 3. Static/companion overload selection by argument type.
#    On the public main branch this lands on read(URL) and fails.
check("KD.read(String) picks the String overload") { ->
    let t = KD.read("a 1")
    if t.name != "a" throw "unexpected tag " + t
}

# 4. lang KD: a tag with attributes AND a children block.
#    On the public main branch the `{` is consumed as a trailing lambda.
check("lang KD: attribute followed by a children block") { ->
    let doc = lang KD {
        app version="2.0.1" {
            ssl enabled=true
        }
    }
    if doc.children.size != 1 throw "expected 1 child, got " + doc.children.size
}

check("lang KD: interpolated attribute followed by a children block") { ->
    let who = "dan"
    let doc = lang KD {
        run operator=$who {
            read 1
        }
    }
    if doc["operator"] != "dan" throw "operator attribute not set"
}

# 5. File-based KD entry points used by kds-05.
let tmp = File("kds-probe-tmp.kd")
IO.write("app { child 1 }\n", tmp)
check("KD.readWithSnips(File) picks the File overload") { ->
    let t = KD.readWithSnips(tmp)
    if t.name != "app" throw "unexpected tag " + t
}
if hasKDS {
    check("KDS.compile(File) picks the File overload") { ->
        use io.kixi.kd.schema.KDS
        IO.write("schema P kds=1 { tag app { children { tag child { value type=Int } } } }\n", tmp)
        let s = KDS.compile(tmp)
        if s.name != "P" throw "unexpected schema " + s.name
    }
}
tmp.delete()

say
if failures.isEmpty {
    say.note "All checks passed. kds-01 through kds-05 should all run on this build."
} else {
    say.warn "" + failures.size + " check(s) failed."
    say "kds-01, kds-03 and kds-04 need only the first two checks."
    say "kds-02 needs the lang KD checks (KDBlockParser trailing-lambda fix)."
    say "kds-05 needs the File overload checks (JVMMethodProxy overload fix)."
}
