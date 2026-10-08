# ─────────────────────────────────────────────────────────────────────────────
#  KDS Example 3: Reusable definitions, quantities, currency, and grids
# ─────────────────────────────────────────────────────────────────────────────
#
#  This example leans on the parts of KDS that go beyond "is it a string":
#    * definitions { valueType ... tagType ... } for rules used in more than
#      one place, referenced with ref=Name
#    * Quantity rules with dimension= and unit=, where bounds convert units
#      (50mm satisfies max=10cm, 212°F satisfies max=100°C)
#    * currency bounds, which never convert between currencies
#    * Grid rules with rows=, columns=, and per-cell item rules
#    * enum membership, compared by magnitude for numbers and quantities

use io.kixi.kd.schema.KDS

say.note "── KDS Example 3: Definitions, quantities, and grids ──"
say

let schema = KDS.compile(@"""
schema Reagents kds=1 {
    definitions {
        valueType CatalogId type=String pattern=@"[A-Z]{2}-\d{5}"
        valueType Volume type=Quantity dimension=Volume min=1mℓ max=10ℓ
        valueType Price type=Quantity dimension=Currency unit=USD min=0USD

        tagType StorageRule {
            attribute temperature type=Quantity dimension=Temperature min=-80°C max=25°C
            attribute lightSensitive type=Bool
        }
    }

    tag reagent occurs=1.._ {
        attribute id ref=CatalogId required=true
        attribute volume ref=Volume required=true
        attribute price ref=Price
        attribute grade type=String enum=[ACS HPLC "cell culture"]
        children {
            tag storage ref=StorageRule occurs=0..1
            tag plateMap occurs=0..1 {
                value type=Grid rows=2 columns=3 {
                    items type=Number nullable=true min=0
                }
            }
        }
    }
}
""")
say "Schema: " + schema.name
say

# A small helper that prints a result in one or two lines per issue.
fun report(label: String, result) {
    if result.isValid {
        say "  ✔ " + label
    } else {
        say "  ✘ " + label
        for i in result.issues say "      " + i.code.padEnd(18) + i.documentPath + "  " + i.message
    }
}

# ── 1. Unit conversion inside bounds ────────────────────────────────────────

# Note the quotes around "AB-12345": a bare AB-12345 in KD parses as the
# string AB followed by the number -12345.

say "Quantity bounds convert compatible units:"
report("250mℓ is inside 1mℓ..10ℓ",
    schema.validate(`reagent id="AB-12345" volume=250mℓ`))
report("2.5ℓ is inside 1mℓ..10ℓ",
    schema.validate(`reagent id="AB-12345" volume=2.5ℓ`))
report("15ℓ exceeds 10ℓ",
    schema.validate(`reagent id="AB-12345" volume=15ℓ`))
report("500mg has the wrong dimension",
    schema.validate(`reagent id="AB-12345" volume=500mg`))
say

say "Temperature bounds include offset conversion (-80°C..25°C):"
report("-112°F storage (equals -80°C)",
    schema.validate(`reagent id="AB-12345" volume=1ℓ { storage temperature=-112°F }`))
report("298K storage (about 24.85°C)",
    schema.validate(`reagent id="AB-12345" volume=1ℓ { storage temperature=298K }`))
report("100°F storage (too warm)",
    schema.validate(`reagent id="AB-12345" volume=1ℓ { storage temperature=100°F }`))
say

# ── 2. Currency never converts ──────────────────────────────────────────────

say "Currency rules require the exact currency:"
report("$42.50 with unit=USD",
    schema.validate(`reagent id="AB-12345" volume=1ℓ price=$42.50`))
report("€40 is rejected even though EUR is also a Currency",
    schema.validate(`reagent id="AB-12345" volume=1ℓ price=€40`))
say

# ── 3. Enums ────────────────────────────────────────────────────────────────

say "Enum membership:"
report("grade=HPLC",
    schema.validate(`reagent id="AB-12345" volume=1ℓ grade=HPLC`))
report("grade=\"cell culture\" (multi-word members need quotes)",
    schema.validate(`reagent id="AB-12345" volume=1ℓ grade="cell culture"`))
report("grade=technical is not a member",
    schema.validate(`reagent id="AB-12345" volume=1ℓ grade=technical`))
say

# ── 4. Grid shape and cells ─────────────────────────────────────────────────

# The data uses KD's .grid literal; "-" is a nil cell, which the rule allows.
say "Grid rules check shape, then every cell:"
report("2x3 grid of non-negative numbers",
    schema.validate("""
        reagent id="AB-12345" volume=1ℓ {
            plateMap .grid {
                0.5  1.2  -
                2.0  0.0  3.3
            }
        }
    """))
report("3x3 grid (wrong shape) with a negative cell",
    schema.validate("""
        reagent id="AB-12345" volume=1ℓ {
            plateMap .grid {
                0.5  1.2  0.1
                2.0 -1.0  3.3
                0.0  0.0  0.0
            }
        }
    """))
say

# ── 5. Definitions are checked at compile time ──────────────────────────────

# A reference to a missing definition, or an unused definition with a typo,
# is a schema error (KDSchemaException), not a data error. In KS the catch
# variable holds the message, which includes the schema path.
say "Schema errors surface at compile time:"
try {
    KDS.compile("schema X kds=1 { tag a { value ref=Missing } }")
} catch(e) {
    say "  " + e
}
try {
    KDS.compile("schema X kds=1 { definitions { valueType Unused type=Typo }; tag a }")
} catch(e) {
    say "  " + e
}
say

say "Done."
