package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.lexer.TokenType
import io.kixi.ks.parser.*
import java.nio.file.Files
import java.nio.file.Path

/*
 * Crates: Kotlin-package-like namespaces made of KS source files.
 *
 * Crate `lab.assays` is the `.ks` files directly in `<root>/lab/assays/` (a root
 * from KSRuntime.crateRoots) whose first declaration is `crate lab.assays`. A
 * script reaches its public declarations with `use`. Every file of a crate sees
 * every declaration of the crate; a file's own `use` lines and its `private`
 * declarations are visible only in that file; `internal` declarations are
 * visible in every file of the crate and never from outside it.
 *
 * Scopes, from the outside in:
 *
 *     host scope     values the host bound with Interpreter.define (shared)
 *     crate base     cratePath(...)
 *     crate scope    the crate's public and internal declarations
 *     file scope     one per file: its private declarations and its imports
 *
 * Crate code never sees the using script's own declarations.
 *
 * Loading (once per interpreter, cached): the files are read in name order and
 * parsed, then
 *   1. every declaration is bound: a function at once, every other declaration
 *      (let, var, class, struct, trait, enum) as a placeholder computed when
 *      first read (Environment.Deferred);
 *   2. each file's `use` lines are resolved, which may load other crates;
 *   3. the placeholders are computed: first the types, then each `extend` is
 *      applied, then the `let` and `var` initializers, each in file order. A
 *      placeholder read before its turn is computed then, so a declaration may
 *      refer to one in any file; one that is read while it is being computed is
 *      an initialization cycle, reported with the names involved.
 * After loading no placeholder is left.
 */

/**
 * A loaded crate: its name, folder, description and declarations. Hosts and tools
 * get one from [Interpreter.crate].
 *
 * @property name The crate's dotted name (`lab.assays`)
 * @property folder The folder holding the crate's files (`<root>/lab/assays`)
 * @property description The doc comment of the crate's `crate-info.ks`, without
 *     its comment markers, or null when there is no `crate-info.ks` or it has no
 *     doc comment
 * @property files The crate's source files in load (name) order, `crate-info.ks`
 *     not included
 */
class Crate internal constructor(
    val name: String,
    val folder: Path,
    val description: String?,
    val files: List<Path>
) {
    /** The crate scope: the public and internal declarations of every file. */
    internal lateinit var scope: Environment

    /** Public and internal declarations by name, in load order. */
    internal val declarations = LinkedHashMap<String, CrateDeclaration>()

    /** Private declarations by name; one name may be private to several files. */
    internal val privates = LinkedHashMap<String, MutableList<CrateDeclaration>>()

    /** The names of the crate's public declarations, in load order. */
    val publicNames: List<String>
        get() = declarations.values.filter { it.visibility == Visibility.PUBLIC }.map { it.name }

    /** The [Visibility] of the declaration called [name], or null when the crate has none. */
    fun visibilityOf(name: String): Visibility? =
        declarations[name]?.visibility ?: privates[name]?.let { Visibility.PRIVATE }

    override fun toString(): String = "crate $name"
}

/**
 * One top-level declaration of a crate file.
 *
 * @property name The declared name
 * @property node The declaration
 * @property visibility Its visibility
 * @property file The file that declares it
 * @property env The scope that binds it: the crate scope, or the file's scope for
 *     a private declaration
 */
internal class CrateDeclaration(
    val name: String,
    val node: Decl,
    val visibility: Visibility,
    val file: CrateFileScope,
    val env: Environment
) {
    /** The declaration's current value (a crate `var` is read live). */
    fun read(): Any? = if (node is FunDecl) env.getFunction(name) else env.get(name)
}

/**
 * What code from one crate file runs with: its crate, its path and its own
 * imports. Set on the file's scope ([Environment.fileScope]) and inherited by
 * every scope below it.
 *
 * @property crate The crate the file belongs to
 * @property path The file
 * @property display The file as error messages name it (its root-relative path
 *     when the root is relative, as the default current directory is)
 */
internal class CrateFileScope(
    val crate: Crate,
    val path: Path,
    val display: String,
    runtime: KSRuntime,
    crates: CrateRegistry
) {
    /** The file's own imports: a `use` in a crate file is visible only in that file. */
    val imports: ImportRegistry = ImportRegistry(runtime, this).also { it.crates = crates }
}

/**
 * A crate bound as a namespace by `use lab.assays` (the analog of `use ki.bio`
 * for a type bag): `assays.proteinConc(0.42)`, `assays.Bradford(...)`.
 *
 * @property crate The crate
 * @property viewer The crate whose file wrote the `use`, or null for a script;
 *     code of the same crate also reaches internal names through the namespace
 */
internal class CrateNamespace(val crate: Crate, private val viewer: Crate?) {

    /** The public (or, for the crate's own code, internal) declaration [name]. */
    fun member(name: String, location: SourceLocation?): Any? {
        val declaration = crate.declarations[name]
        if (declaration != null) {
            if (declaration.visibility == Visibility.INTERNAL && viewer !== crate) {
                throw CrateError("$name is internal to crate ${crate.name}", location, crate.name)
            }
            return declaration.read()
        }
        crate.privates[name]?.firstOrNull()?.let {
            throw CrateError("$name is private to file ${it.file.display}", location, crate.name)
        }
        throw MemberNotFoundError(name, "crate ${crate.name}", location)
    }

    override fun toString(): String = "crate ${crate.name}"
}

/**
 * Finds the crate line in a file's text without lexing it, so ordinary scripts in a
 * crate folder are never parsed while the crate loads. It reads the same shape as the
 * parser ([io.kixi.ks.parser.Parser]): comments and blank lines, then `crate` and a
 * dotted name written without spaces, then the end of the line, `;` or a comment.
 */
internal object CrateLine {

    /**
     * A crate line found in a file.
     *
     * @property name The crate name it gives
     * @property line Its 1-based line number
     */
    class Found(val name: String, val line: Int)

    /** The crate line [text] starts with (after comments), or null when it has none. */
    fun detect(text: String): Found? {
        val n = text.length
        var i = if (text.startsWith("﻿")) 1 else 0
        var line = 1
        while (i < n) {
            val c = text[i]
            when {
                c == '\n' -> { line++; i++ }
                c == ' ' || c == '\t' || c == '\r' || c == '\u000C' -> i++
                c == '#' || text.startsWith("//", i) -> while (i < n && text[i] != '\n') i++
                text.startsWith("/*", i) -> {
                    val end = blockCommentEnd(text, i)
                    if (end < 0) return null
                    line += text.substring(i, end).count { it == '\n' }
                    i = end
                }
                else -> break
            }
        }
        if (!text.startsWith("crate", i)) return null
        var j = i + 5
        if (j >= n || (text[j] != ' ' && text[j] != '\t')) return null
        while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
        val segments = mutableListOf<String>()
        while (true) {
            val start = j
            if (j >= n || !isIdentifierStart(text[j])) return null
            while (j < n && isIdentifierPart(text[j])) j++
            segments.add(text.substring(start, j))
            if (j < n && text[j] == '.') j++ else break
        }
        if (segments.first() in TokenType.KEYWORDS) return null
        while (j < n && (text[j] == ' ' || text[j] == '\t' || text[j] == '\r')) j++
        val ends = j >= n || text[j] == '\n' || text[j] == ';' || text[j] == '#' ||
                text.startsWith("//", j) || text.startsWith("/*", j)
        return if (ends) Found(segments.joinToString("."), line) else null
    }

    /**
     * The text of the first doc comment (one opened with slash, star, star) in [text], with its markers
     * and each line's leading `*` removed, or null when there is none. Used for
     * `crate-info.ks`, which holds only the crate line and comments.
     */
    fun docComment(text: String): String? {
        val n = text.length
        var i = 0
        while (i < n) {
            when {
                text.startsWith("/**", i) && !text.startsWith("/**/", i) -> {
                    val end = blockCommentEnd(text, i)
                    if (end < 0) return null
                    return cleanDoc(text.substring(i + 3, end - 2))
                }
                text.startsWith("/*", i) -> {
                    val end = blockCommentEnd(text, i)
                    if (end < 0) return null
                    i = end
                }
                text[i] == '#' || text.startsWith("//", i) -> while (i < n && text[i] != '\n') i++
                else -> i++
            }
        }
        return null
    }

    /** The index just past the end of the (nestable) block comment that opens at [start], or -1. */
    private fun blockCommentEnd(text: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> { depth++; i += 2 }
                text.startsWith("*/", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return i
                }
                else -> i++
            }
        }
        return -1
    }

    private fun cleanDoc(body: String): String? {
        val lines = body.lines().map { raw ->
            val trimmed = raw.trim()
            when {
                trimmed.startsWith("* ") -> trimmed.substring(2)
                trimmed.startsWith("*") -> trimmed.substring(1)
                else -> trimmed
            }.trimEnd()
        }.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        return lines.joinToString("\n").ifBlank { null }
    }

    /** A KS identifier's first character, as the lexer reads it. */
    fun isIdentifierStart(c: Char): Boolean = c == '_' || c.isLetter()

    /** A KS identifier's later characters, as the lexer reads them. */
    fun isIdentifierPart(c: Char): Boolean = isIdentifierStart(c) || c in '0'..'9'

    /** True when [s] is one name segment (a folder that may be part of a crate name). */
    fun isSegment(s: String): Boolean =
        s.isNotEmpty() && isIdentifierStart(s[0]) && s.all { isIdentifierPart(it) }
}

/**
 * The crates one interpreter has loaded, and the loader. A crate loads once per
 * interpreter and is cached; a fresh interpreter reads the files again, so edits
 * are seen on the next run.
 *
 * @param interp The interpreter crate code runs in
 */
internal class CrateRegistry(private val interp: Interpreter) {

    /** Crates loaded completely, by name. */
    private val loaded = LinkedHashMap<String, Crate>()

    /** Crates being loaded, outermost first: a crate used again from here is a loop. */
    private val loading = ArrayList<Crate>()

    /** Names already looked for under every root and not found. */
    private val missing = HashSet<String>()

    /** Declarations whose initializers are running, outermost first: the chain an initialization cycle reports. */
    private val initializing = ArrayList<CrateDeclaration>()

    /** Every enum the loaded crates declare, for `.CONSTANT` resolution. */
    private val crateEnums = ArrayList<Pair<Crate, KSEnum>>()

    /** The crates loaded so far, in load order. */
    val all: Collection<Crate> get() = loaded.values

    // ========================================================================
    // Finding crates
    // ========================================================================

    /**
     * The crate called [name], loading it on first use, or null when no crate root
     * holds one.
     *
     * @param from The crate file whose `use` asks, or null for a script. A crate
     *     reached again while it loads is a loop and an error, except from its own
     *     files (which see their crate's declarations anyway).
     * @throws CrateError for a loop, or when the crate cannot be loaded
     */
    fun find(name: String, location: SourceLocation?, from: CrateFileScope?): Crate? {
        loaded[name]?.let { return it }
        val index = loading.indexOfFirst { it.name == name }
        if (index >= 0) {
            if (from != null && from.crate === loading[index]) return loading[index]
            val loop = loading.subList(index, loading.size).map { it.name } + name
            throw CrateError(
                "Crates use each other: ${loop.joinToString(" -> ")}",
                location, name,
                suggestion = "Move what both need into a third crate that each of them uses"
            )
        }
        if (name in missing) return null
        for (root in interp.runtime.crateRoots) {
            val folder = name.split('.').fold(root) { path, segment -> path.resolve(segment) }
            if (!Files.isDirectory(folder)) continue
            val sources = scan(name, folder)
            if (sources.isNotEmpty()) return load(name, folder, sources)
        }
        missing.add(name)
        return null
    }

    /**
     * Crate [base], if there is one, and every crate in the folders below it, in
     * name order (for `use lab.**`).
     */
    fun under(base: String, location: SourceLocation?, from: CrateFileScope?): List<Crate> {
        val names = sortedSetOf<String>()
        for (root in interp.runtime.crateRoots) {
            val baseFolder = base.split('.').fold(root) { path, segment -> path.resolve(segment) }
            if (!Files.isDirectory(baseFolder)) continue
            Files.walk(baseFolder).use { paths ->
                paths.filter { Files.isDirectory(it) }.forEach { dir ->
                    val segments = baseFolder.relativize(dir).map { it.toString() }.filter { it.isNotEmpty() }
                    if (segments.all { CrateLine.isSegment(it) }) names.add((listOf(base) + segments).joinToString("."))
                }
            }
        }
        return names.mapNotNull { find(it, location, from) }
    }

    /** One `.ks` file of a crate folder that belongs to the crate. */
    private class Source(val path: Path, val display: String, val text: String, val line: Int) {
        val isInfo: Boolean get() = path.fileName.toString() == INFO_FILE
    }

    /**
     * The files of [folder] that belong to crate [name], in name order. Files with
     * no crate line are ordinary scripts and are skipped; one whose crate line names
     * another crate is an error.
     */
    private fun scan(name: String, folder: Path): List<Source> {
        val files = Files.list(folder).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".ks") }
                .sorted(compareBy { it.fileName.toString() })
                .toList()
        }
        val sources = ArrayList<Source>()
        for (file in files) {
            val display = file.toString()
            val text = String(Files.readAllBytes(file), Charsets.UTF_8)
            val found = CrateLine.detect(text)
            if (found == null) {
                if (file.fileName.toString() == INFO_FILE) {
                    throw CrateError(
                        "$display: crate-info.ks must start with the crate line, crate $name",
                        SourceLocation(1, 1, 0, display), name
                    )
                }
                continue
            }
            if (found.name != name) {
                throw CrateError(
                    "$display: its crate line says crate ${found.name}, but its folder makes it crate $name",
                    SourceLocation(found.line, 1, 0, display), name,
                    suggestion = "Change the line to crate $name, or move the file to the folder of crate ${found.name}"
                )
            }
            sources.add(Source(file, display, text, found.line))
        }
        return sources
    }

    // ========================================================================
    // Loading
    // ========================================================================

    /** One parsed file of the crate being loaded. */
    private class FileUnit(val source: Source, val program: Program, val scope: CrateFileScope, val env: Environment) {
        val declarations = ArrayList<CrateDeclaration>()
        val uses = ArrayList<UseDecl>()
        val extends = ArrayList<ExtendDecl>()
    }

    /** Loads crate [name] from [sources] (see the notes at the top of this file). */
    private fun load(name: String, folder: Path, sources: List<Source>): Crate {
        val info = sources.firstOrNull { it.isInfo }
        val code = sources.filter { !it.isInfo }
        val crate = Crate(name, folder, info?.let { readInfo(it, name) }, code.map { it.path })

        val base = interp.hostScope.child("crate-base:$name")
        base.define("cratePath", cratePathFunction(crate), mutable = false)
        crate.scope = base.child("crate:$name")

        loading.add(crate)
        val previous = interp.environment
        try {
            val units = code.map { source ->
                val env = crate.scope.child("file:${source.path.fileName}")
                val scope = CrateFileScope(crate, source.path, source.display, interp.runtime, this)
                env.fileScope = scope
                FileUnit(source, parse(source, name), scope, env)
            }
            for (unit in units) declare(crate, unit)

            for (unit in units) {
                interp.environment = unit.env
                for (use in unit.uses) interp.typeDecls.evaluateUseDecl(use)
            }

            for (unit in units) for (d in unit.declarations) if (d.node.isType()) d.env.get(d.name)
            for (unit in units) {
                interp.environment = unit.env
                for (extend in unit.extends) interp.typeDecls.evaluateExtendDecl(extend)
            }
            for (unit in units) for (d in unit.declarations) if (d.node is VarDecl) d.env.get(d.name)

            loaded[name] = crate
            return crate
        } catch (e: Throwable) {
            // Not cached, so the next use tries again; forget what it registered.
            crateEnums.removeAll { it.first === crate }
            throw e
        } finally {
            interp.environment = previous
            loading.removeAt(loading.lastIndex)
        }
    }

    /** True for the declarations that are types (computed before any initializer). */
    private fun Decl.isType(): Boolean = this is ClassDecl || this is StructDecl || this is TraitDecl || this is EnumDecl

    /**
     * Lexes and parses one crate file, with its path in every location, and checks
     * that it holds declarations only.
     */
    private fun parse(source: Source, crate: String): Program {
        val program = parseText(source, crate)
        if (program.body.firstOrNull() !is CrateDecl) {
            throw CrateError(
                "${source.display}: the crate line must stand alone on its line",
                SourceLocation(source.line, 1, 0, source.display), crate
            )
        }
        for (node in program.body.drop(1)) {
            when (node) {
                is VarDecl, is FunDecl, is ClassDecl, is StructDecl, is TraitDecl, is EnumDecl,
                is ExtendDecl, is UseDecl -> {}
                else -> throw CrateError(
                    "${source.display}: a crate file holds declarations only (fun, class, struct, trait, enum, " +
                            "extend, let, var and use), not ${describe(node)}",
                    node.location, crate,
                    suggestion = "Move it into a script that uses crate $crate"
                )
            }
        }
        return program
    }

    private fun parseText(source: Source, crate: String): Program = try {
        val tokens = Lexer(source.text).tokenize().map { it.copy(location = it.location.copy(file = source.display)) }
        Parser(tokens).parse()
    } catch (e: io.kixi.text.ParseException) {
        throw CrateError("${source.display}: ${e.message}", null, crate, cause = e)
    }

    /** What a non-declaration is, for the "declarations only" error. */
    private fun describe(node: Node): String = when (node) {
        is SayStmt -> "say"
        is ForStmt -> "a for loop"
        is WhileStmt -> "a while loop"
        is ReturnStmt -> "return"
        is ThrowStmt -> "throw"
        is BreakStmt -> "break"
        is ContinueStmt -> "continue"
        is StaticBlock -> "a static block"
        is ExprStmt -> when (node.expression) {
            is IfExpr -> "an if"
            is WhenExpr -> "a when"
            is TryExpr -> "a try"
            is AssignExpr -> "an assignment"
            is CallExpr -> "a call"
            else -> "an expression"
        }
        else -> node::class.simpleName ?: "a statement"
    }

    /**
     * Reads `crate-info.ks`: the crate line and a doc comment, nothing else. Returns
     * the doc comment's text.
     */
    private fun readInfo(source: Source, crate: String): String? {
        val program = parseText(source, crate)
        program.body.drop(1).firstOrNull()?.let {
            throw CrateError(
                "${source.display}: crate-info.ks holds only the crate line and a doc comment describing the crate",
                it.location, crate,
                suggestion = "Put declarations in another file of crate $crate"
            )
        }
        return CrateLine.docComment(source.text)
    }

    /**
     * Binds every declaration of [unit] (see the notes at the top of this file) and
     * records it on [crate]. A name may be declared once per crate, except that
     * several files may each have a private declaration of it.
     */
    private fun declare(crate: Crate, unit: FileUnit) {
        for (node in unit.program.body.drop(1)) {
            val (name, visibility) = when (node) {
                is UseDecl -> { unit.uses.add(node); continue }
                is ExtendDecl -> { unit.extends.add(node); continue }
                is VarDecl -> node.name to node.visibility
                is FunDecl -> node.name to node.visibility
                is ClassDecl -> node.name to node.visibility
                is StructDecl -> node.name to node.visibility
                is TraitDecl -> node.name to node.visibility
                is EnumDecl -> node.name to node.visibility
                else -> continue
            }
            val decl = node as Decl
            val env = if (visibility == Visibility.PRIVATE) unit.env else crate.scope

            val clash = crate.declarations[name]
                ?: crate.privates[name]?.firstOrNull { visibility != Visibility.PRIVATE || it.file === unit.scope }
            if (clash != null) {
                throw CrateError(
                    "${unit.source.display}: $name is declared twice in crate ${crate.name}, " +
                            "here and in ${clash.file.display} at line ${clash.node.location.line}",
                    node.location, crate.name
                )
            }

            val declaration = CrateDeclaration(name, decl, visibility, unit.scope, env)
            if (visibility == Visibility.PRIVATE) crate.privates.getOrPut(name) { ArrayList() }.add(declaration)
            else crate.declarations[name] = declaration
            unit.declarations.add(declaration)

            when (decl) {
                is FunDecl -> env.defineFunction(name, KSFunction(decl, unit.env), decl.location)
                is VarDecl -> env.define(
                    name, Pending(declaration, unit.env), decl.mutable,
                    decl.typeAnnotation, decl.constraint, decl.location
                )
                else -> env.define(name, Pending(declaration, unit.env), mutable = false, location = decl.location)
            }
        }
    }

    /**
     * The placeholder of a `let`, `var` or type declaration until it is computed:
     * the declaration is evaluated as a script would evaluate it, in a scope below
     * its file's, and the value is stored in the declaration's own binding.
     */
    private inner class Pending(val declaration: CrateDeclaration, val fileEnv: Environment) : Environment.Deferred {
        private var running = false

        override fun force(): Any? {
            val name = declaration.name
            if (running) {
                val chain = initializing.subList(initializing.indexOf(declaration), initializing.size).map { it.name } + name
                throw CrateError(
                    "Initialization cycle in crate ${declaration.file.crate.name}: ${chain.joinToString(" -> ")}",
                    declaration.node.location, declaration.file.crate.name,
                    suggestion = "Make one of them a function, which is evaluated when called"
                )
            }
            running = true
            initializing.add(declaration)
            val previous = interp.environment
            val scratch = fileEnv.child("init:$name")
            interp.environment = scratch
            try {
                interp.evaluate(declaration.node)
                val value = scratch.get(name)
                declaration.env.initialize(name, value)
                return value
            } finally {
                interp.environment = previous
                initializing.removeAt(initializing.lastIndex)
                running = false
            }
        }

        override fun toString(): String = "<${declaration.name}: not initialized yet>"
    }

    /** `cratePath("bsa-standards.csv")`: the absolute path of a file in [crate]'s folder, as a String. */
    private fun cratePathFunction(crate: Crate) = NativeFunction("cratePath", 1) { args ->
        val file = args.singleOrNull() as? String
            ?: throw RuntimeError("cratePath(name) takes one String, the name of a file in the folder of crate ${crate.name}")
        crate.folder.resolve(file).toAbsolutePath().normalize().toString()
    }

    // ========================================================================
    // Questions crate code asks
    // ========================================================================

    /**
     * Throws when [name], not found from code of [scope]'s file, is private to
     * another file of the same crate: a clearer error than an undefined name.
     */
    fun checkPrivateElsewhere(scope: CrateFileScope, name: String, location: SourceLocation?) {
        val other = scope.crate.privates[name]?.firstOrNull { it.file !== scope } ?: return
        throw CrateError("$name is private to file ${other.file.display}", location, scope.crate.name)
    }

    /** Records [enum], declared by code of [crate], for `.CONSTANT` resolution. */
    fun registerEnum(crate: Crate, enum: KSEnum) {
        crateEnums.add(crate to enum)
    }

    /**
     * The constant [name] of a crate enum, preferring [preferred]'s enums, then the
     * other loaded crates' in load order; null when none has it.
     */
    fun enumConstant(name: String, preferred: Crate?): KSEnumConstant? {
        if (preferred != null) {
            for ((crate, enum) in crateEnums) if (crate === preferred) enum.getConstant(name)?.let { return it }
        }
        for ((_, enum) in crateEnums) enum.getConstant(name)?.let { return it }
        return null
    }

    companion object {
        /** The optional file that describes a crate. */
        const val INFO_FILE = "crate-info.ks"
    }
}
