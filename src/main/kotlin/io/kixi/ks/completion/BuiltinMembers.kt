package io.kixi.ks.completion

import io.kixi.ks.interp.JVMMembersFormatter

/**
 * Structured view over the curated builtin-type member blobs that
 * [JVMMembersFormatter.formatBuiltinType] returns for `String`, `Int`,
 * `List`, `Map`, etc.
 *
 * ## Why parse the blob instead of a separate structured table
 *
 * The builtin member surfaces live in exactly one place —
 * `JVMMembersFormatter.BUILTIN_MEMBERS`, as pre-rendered text blocks
 * whose lines are already canonical KS signatures
 * (`fun charAt(index: Int): Char`, `val length: Int`). Introducing a
 * second, structured table would duplicate that authority and the two
 * would drift the moment someone edits one and not the other. Parsing the
 * single existing source keeps **one** source of truth and touches
 * nothing in `JVMMembersFormatter`.
 *
 * The blob grammar is small and uniform across every entry:
 *
 * ```
 * class String
 *   extends Number            ← hierarchy line (skipped)
 *   implements CharSequence   ← hierarchy line (skipped)
 *
 * Constructors:               ← section header
 *   String(str: String)       ← member line (constructor; skipped in slice 1)
 *
 * Properties:                 ← section header
 *   val length: Int           ← member line (property)
 *
 * Methods:                    ← section header
 *   fun charAt(index: Int): Char   ← member line (method)
 *
 * Static:                     ← section header (skipped in slice 1)
 *   ...
 *
 * Note: free text …           ← non-member prose (skipped)
 * ```
 *
 * Slice 1 surfaces **instance** members only, so this reads the
 * `Properties:` and `Methods:` sections and ignores `Constructors:`,
 * `Static:`, hierarchy lines, and any free-text notes.
 *
 * Never throws: a name with no curated blob, or any unexpected line,
 * yields an empty list / is skipped.
 */
internal object BuiltinMembers {

    /** Section a parsed line belongs to. */
    private enum class Section { NONE, CONSTRUCTORS, PROPERTIES, METHODS, STATIC }

    /**
     * Structured instance members (properties + methods) for the builtin
     * type [typeName], or an empty list if there is no curated entry.
     */
    fun instanceMembers(typeName: String): List<MemberItem> {
        val blob = JVMMembersFormatter.formatBuiltinType(typeName) ?: return emptyList()

        val out = mutableListOf<MemberItem>()
        var section = Section.NONE

        for (raw in blob.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            // Section headers, e.g. "Properties:". Members are indented under
            // them; a header is a bare capitalised word ending in ':'.
            when (line) {
                "Constructors:" -> { section = Section.CONSTRUCTORS; continue }
                "Properties:"   -> { section = Section.PROPERTIES;   continue }
                "Methods:"      -> { section = Section.METHODS;      continue }
                "Static:"       -> { section = Section.STATIC;       continue }
            }

            when (section) {
                Section.PROPERTIES -> parseProperty(line, typeName)?.let { out.add(it) }
                Section.METHODS    -> parseMethod(line, typeName)?.let { out.add(it) }
                // Header line (`class String`), hierarchy lines (`extends …`,
                // `implements …`), constructors, statics, and free-text notes
                // are not instance members.
                else -> {}
            }
        }
        return out
    }

    /**
     * Parse a property line: `val length: Int` / `var count: Int`.
     * The completion row drops the binding keyword and shows `name: Type`,
     * matching the KS-class property rendering in [KSTypeModel].
     */
    private fun parseProperty(line: String, declaringType: String): MemberItem? {
        val rest = when {
            line.startsWith("val ") -> line.removePrefix("val ")
            line.startsWith("var ") -> line.removePrefix("var ")
            else -> return null
        }
        // `length: Int` → name is up to the first ':'.
        val name = rest.substringBefore(':').trim()
        if (name.isEmpty()) return null
        return MemberItem(
            name = name,
            kind = MemberKind.PROPERTY,
            signature = rest.trim(),       // "length: Int"
            declaringType = declaringType,
            origin = MemberOrigin.BUILTIN
        )
    }

    /**
     * Parse a method line: `fun charAt(index: Int): Char`,
     * `fun setCharAt(index: Int, c: Char)` (no return type), and
     * `infix fun …` if present. The member name is the token between the
     * `fun ` keyword and the opening `(`.
     */
    private fun parseMethod(line: String, declaringType: String): MemberItem? {
        // Strip a leading `infix ` modifier if present; keep it in the
        // displayed signature though.
        val afterFun = when {
            line.startsWith("fun ")       -> line.removePrefix("fun ")
            line.startsWith("infix fun ") -> line.removePrefix("infix fun ")
            else -> return null
        }
        val paren = afterFun.indexOf('(')
        if (paren <= 0) return null
        val name = afterFun.substring(0, paren).trim()
        if (name.isEmpty()) return null
        return MemberItem(
            name = name,
            kind = MemberKind.METHOD,
            signature = line,              // full canonical signature, e.g. "fun charAt(index: Int): Char"
            declaringType = declaringType,
            origin = MemberOrigin.BUILTIN
        )
    }
}