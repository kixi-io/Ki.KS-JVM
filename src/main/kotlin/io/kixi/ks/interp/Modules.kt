package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.parser.*
import java.nio.file.Path

/*
 * Script modules, namespaces and the files code comes from.
 *
 * A `.ks` file without a crate line is a script module, as a Python module is:
 * the first `use` of it in an interpreter runs it once, in its own scope, and its
 * public top-level declarations (fun, class, struct, trait, enum, let, var) are
 * its names. Like crate code, a module's code sees the host's bindings and its
 * own imports, never the using script's names.
 *
 * A `use` path is made of modules: a crate (a folder of crate files), a script
 * module (a file), or a plain folder (one that is neither, which holds modules).
 * The path is read as the longest prefix that is a module, then a member of it:
 * a crate's declaration, a module's top-level name, or a module inside a folder.
 * Dotted names (`use tools.stats.mean`) are looked up under the crate roots;
 * relative paths (`use ./plates/PlateMap`, `use ../shared/Plate`) from the folder
 * of the file the `use` is in. CrateRegistry finds and loads both.
 */

/**
 * The file a piece of code comes from, when it is not the script being run: a
 * crate file ([CrateFileScope]) or a script module ([ModuleFileScope]). It is set
 * on the file's own scope ([Environment.fileScope]) and inherited below it, so the
 * code finds its own imports, and the folder a relative `use` counts from,
 * wherever it runs. The script being run has none, which is what makes `isMain`
 * true there.
 *
 * @property path The file
 * @property display The file as messages name it
 * @property folder The folder a relative `use` in this file counts from (a crate
 *     file's crate folder, a module's own folder), absolute
 * @property root The crate root (or other folder) `../` may not go above, absolute
 */
internal sealed class FileScope(
    val path: Path,
    val display: String,
    val folder: Path,
    val root: Path,
    runtime: KSRuntime,
    crates: CrateRegistry
) {
    /** The file's own imports: a `use` in it is visible only in that file. */
    val imports: ImportRegistry = ImportRegistry(runtime, this).also { it.crates = crates }

    /** The crate the file belongs to, or null for a script module. */
    open val crate: Crate? get() = null

    /** What the file belongs to (its [Crate] or [ScriptModule]), which owns the enums it declares. */
    abstract val owner: Any
}

/**
 * The scope of a script module's code.
 *
 * @property module The module
 */
internal class ModuleFileScope(
    val module: ScriptModule,
    display: String,
    runtime: KSRuntime,
    crates: CrateRegistry
) : FileScope(module.file, display, module.file.parent, module.root, runtime, crates) {
    override val owner: Any get() = module
}

/**
 * A script module: a `.ks` file without a crate line, run once on its first `use`
 * in an interpreter. Its names are its public top-level declarations; `private`
 * and `internal` ones are not exported.
 *
 * @property name Its dotted name below its root (`tools.stats`), or its file name
 *     without `.ks` when it is not below a root as a dotted name
 * @property file The module's file, absolute
 * @property root The crate root (or other folder) it was found under, which its own
 *     relative uses may not go above
 */
internal class ScriptModule(val name: String, val file: Path, val root: Path) {

    /** The module's top-level scope, where its declarations live. */
    lateinit var scope: Environment

    /** The exported (public) top-level declarations by name, in source order. */
    val exported = LinkedHashMap<String, Decl>()

    /** The top-level names it does not export, with their visibility. */
    val unexported = LinkedHashMap<String, Visibility>()

    /** The names of its public top-level declarations, in source order. */
    val publicNames: List<String> get() = exported.keys.toList()

    /** Records [program]'s top-level declarations as this module's names. */
    fun record(program: Program) {
        for (node in program.body) {
            val (name, visibility) = when (node) {
                is VarDecl -> node.name to node.visibility
                is FunDecl -> node.name to node.visibility
                is ClassDecl -> node.name to node.visibility
                is StructDecl -> node.name to node.visibility
                is TraitDecl -> node.name to node.visibility
                is EnumDecl -> node.name to node.visibility
                else -> continue
            }
            if (visibility == Visibility.PUBLIC) exported[name] = node as Decl
            else unexported[name] = visibility
        }
    }

    /** The current value of the exported name [name] (a module `var` is read live). */
    fun read(name: String): Any? =
        if (exported[name] is FunDecl) scope.getFunction(name) else scope.get(name)

    /**
     * The error for reaching [member], which this module declares but does not
     * export, from outside it; null when the module has no such hidden name.
     */
    fun notExported(member: String, location: SourceLocation?): CrateError? {
        val visibility = unexported[member] ?: return null
        return CrateError(
            "$member is ${visibility.keyword} in module $name, so it is not exported",
            location, name,
            suggestion = "Only public top-level names of a module can be used from outside it"
        )
    }

    override fun toString(): String = "module $name"
}

/**
 * What a module path reaches: a crate, a script module or a plain folder.
 */
internal sealed class Module {
    /** A crate. */
    class OfCrate(val crate: Crate) : Module()

    /** A script module (already run). */
    class Script(val module: ScriptModule) : Module()

    /**
     * A plain folder: neither a crate nor a module file, a place that holds modules.
     *
     * @property folder The folder, absolute
     * @property root The root its modules are found under
     * @property display The folder as messages name it
     */
    class Folder(val folder: Path, val root: Path, val display: String) : Module()
}

/**
 * A module bound as a value (`use lab.assays`, `use ./helpers`, `use ./plates`):
 * its members are read with a dot (`helpers.dilute(...)`).
 */
internal interface Namespace {
    /**
     * The member [name], or an error when there is none or it is not visible.
     *
     * @throws CrateError when the member exists but is not visible from here
     * @throws MemberNotFoundError when there is no such member
     */
    fun member(name: String, location: SourceLocation?): Any?
}

/**
 * A script module bound as a namespace (`use ./helpers`, then `helpers.dilute(...)`).
 *
 * @property module The module
 */
internal class ModuleNamespace(val module: ScriptModule) : Namespace {
    override fun member(name: String, location: SourceLocation?): Any? {
        if (name in module.exported) return module.read(name)
        module.notExported(name, location)?.let { throw it }
        throw MemberNotFoundError(name, "module ${module.name}", location)
    }

    override fun toString(): String = "module ${module.name}"
}

/**
 * A plain folder bound as a namespace (`use ./plates`, then `plates.layout.wellName(...)`):
 * its members are the modules in it (crates, script modules and folders).
 *
 * @property module The folder
 * @property crates The loader, which loads a member on first use
 * @property from The file whose `use` bound it, or null for the script being run
 */
internal class FolderNamespace(
    val module: Module.Folder,
    private val crates: CrateRegistry,
    private val from: FileScope?
) : Namespace {
    /** The members found so far, so a loop over `plates.layout...` looks on disk once. */
    private val members = HashMap<String, Namespace>()

    override fun member(name: String, location: SourceLocation?): Any? = members.getOrPut(name) {
        val found = crates.moduleIn(module.folder, module.root, listOf(name), location, from)
            ?: throw MemberNotFoundError(name, "folder ${module.display}", location)
        crates.namespace(found, from)
    }

    override fun toString(): String = "folder ${module.display}"
}
