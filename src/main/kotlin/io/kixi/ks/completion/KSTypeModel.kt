package io.kixi.ks.completion

import io.kixi.ks.KSFunction
import io.kixi.ks.interp.Interpreter
import io.kixi.ks.interp.KSClass
import io.kixi.ks.interp.KSTrait
import io.kixi.ks.interp.KSStruct
import io.kixi.ks.interp.KSEnum
import io.kixi.ks.interp.JVMClassProxy
import io.kixi.ks.interp.ResolvedImport
import io.kixi.ks.interp.MembersFormatter
import io.kixi.ks.interp.JVMMembersFormatter
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kixi.ks.parser.ClassDecl
import io.kixi.ks.parser.TraitDecl
import io.kixi.ks.parser.StructDecl
import io.kixi.ks.parser.EnumDecl
import io.kixi.ks.parser.UseDecl
import io.kixi.ks.parser.FunDecl
import io.kixi.ks.parser.VarDecl
import io.kixi.ks.parser.StaticBlock
import io.kixi.ks.parser.ConstructorParam
import io.kixi.ks.parser.BindingType
import io.kixi.ks.parser.Node
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// ─────────────────────────────────────────────────────────────────────────────
//  KS.buildTypeModel — side-effect-free "define types only" pass
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Build a read-only [TypeModel] from KS [source] for editor tooling
 * (code completion, hover, the future LSP).
 *
 * This is a **side-effect-free** pass: it lexes, parses, and registers
 * only the *type declarations* in the buffer — `class`, `trait`,
 * `struct`, `enum` — plus its `use` imports. It never executes statement
 * bodies, constructor bodies, or static initialisers, so no user code
 * runs and the editor cannot be made to do work (or harm) by typing.
 *
 * ## Why this is safe (and why it doesn't just call the evaluator loop)
 *
 * [io.kixi.ks.interp.TypeDeclarationEvaluator.evaluateClassDecl] does
 * three things beyond linking the type:
 *
 * 1. **Trait-conformance validation that throws.** On a buffer that is
 *    mid-edit (the common case in an editor) a class often does not yet
 *    implement its trait's abstract methods, which would throw and abort
 *    the whole pass.
 * 2. **Static initialisation** (`initializeStaticMembers`), which
 *    evaluates static `var` initialisers — arbitrary user code.
 * 3. **Order-sensitive supertype resolution** — a subclass read before
 *    its superclass is registered loses its link.
 *
 * So this pass does the linking the evaluator does (resolve superclass +
 * traits from the already-registered set, construct the runtime type,
 * register it) but **skips validation and static init**, and orders the
 * work so supertypes are registered before subtypes. The result is the
 * same fully-resolved [KSClass]/[KSTrait]/[KSStruct]/[KSEnum] graph the
 * interpreter would build, minus the parts that run code or reject
 * incomplete input.
 *
 * Never throws: any lexer/parser failure yields a partial model built
 * from whatever parsed, and per-declaration failures are skipped.
 *
 * @param source The full editor buffer text.
 * @return A [TypeModel]; empty if nothing could be parsed.
 */
fun buildKSTypeModel(source: String): TypeModel {
    val interp = Interpreter()

    val program = try {
        val tokens = Lexer(source).tokenize()
        Parser(tokens).parse()
    } catch (t: Throwable) {
        // Unparseable buffer — still return a usable (import/native/builtin
        // only) model rather than nothing.
        return KSTypeModel(interp, emptyList())
    }

    val topLevel: List<Node> = program.body

    // ── Imports first: `use` brings JVM + KS names into resolution scope,
    //    and later type resolution (resolveType) consults the registry. ──
    for (node in topLevel) {
        if (node is UseDecl) {
            runCatching { interp.typeDecls.evaluateUseDecl(node) }
        }
    }

    // ── Register types so supertypes precede subtypes. We can't know the
    //    dependency order up front without resolving, so we iterate to a
    //    fixed point: each pass registers every not-yet-registered type
    //    whose declaration we can link now. A type whose superclass is
    //    declared later in the file gets linked on a subsequent pass.
    //    Bounded by the number of type decls (no infinite loop: a pass
    //    that registers nothing terminates the loop). ──
    val typeDecls: List<Node> = topLevel.filter {
        it is ClassDecl || it is TraitDecl || it is StructDecl || it is EnumDecl
    }

    val pending = typeDecls.toMutableList()
    while (pending.isNotEmpty()) {
        val before = pending.size
        val it = pending.iterator()
        while (it.hasNext()) {
            val decl = it.next()
            val linked = tryDefineType(interp, decl)
            if (linked) it.remove()
        }
        // No progress this pass → remaining decls have unresolvable
        // supertypes (forward refs to nonexistent types, or cycles).
        // Define them anyway with whatever links resolve, so their own
        // members still complete, then stop.
        if (pending.size == before) {
            for (decl in pending) defineTypeUnchecked(interp, decl)
            break
        }
    }

    return KSTypeModel(interp, typeDecls)
}

/**
 * Try to define a single type declaration into [interp], linking its
 * supertypes from the already-registered set.
 *
 * @return `true` if the type was registered (or already present), `false`
 *         if it should be retried after more supertypes are registered.
 *         Traits, structs, and enums always return `true` (no ordering
 *         dependency that benefits from retry in slice 1).
 */
private fun tryDefineType(interp: Interpreter, decl: Node): Boolean {
    return when (decl) {
        is ClassDecl -> {
            if (interp.classes.containsKey(decl.name)) return true
            // A class with a superclass name that names a *class* not yet
            // registered should wait; if the name is a trait or unknown,
            // proceed now (trait links resolve from interp.traits, and an
            // unknown super won't ever resolve so there's no point waiting).
            val superName = decl.superTypes.firstOrNull()?.name
            val waitsOnClass = superName != null &&
                    !interp.classes.containsKey(superName) &&
                    interp.traits[superName] == null &&
                    typeDeclaredLaterAsClass(interp, superName)
            if (waitsOnClass) return false
            defineTypeUnchecked(interp, decl)
            true
        }
        else -> {
            defineTypeUnchecked(interp, decl)
            true
        }
    }
}

/**
 * Whether [name] is (potentially) going to be registered as a class — i.e.
 * it is not yet in [Interpreter.classes]. Used only to decide whether a
 * subclass should wait. Conservative: if unsure, we do not wait.
 */
private fun typeDeclaredLaterAsClass(interp: Interpreter, name: String): Boolean =
    !interp.classes.containsKey(name)

/**
 * Construct and register the runtime type for [decl] without validation
 * or static initialisation. Mirrors the *linking* portion of
 * [io.kixi.ks.interp.TypeDeclarationEvaluator] only.
 *
 * Supertype links are resolved from whatever is already registered;
 * unresolved supers simply yield a `null` superclass / fewer traits,
 * which is the correct degraded behaviour for an editor.
 */
private fun defineTypeUnchecked(interp: Interpreter, decl: Node) {
    runCatching {
        when (decl) {
            is ClassDecl -> {
                if (interp.classes.containsKey(decl.name)) return
                val superclass: KSClass? = decl.superTypes
                    .firstOrNull()
                    ?.let { interp.classes[it.name] }
                val traits: List<KSTrait> = decl.superTypes
                    .mapNotNull { interp.traits[it.name] }
                interp.classes[decl.name] =
                    KSClass(decl, superclass, traits, interp.environment)
            }
            is TraitDecl -> {
                if (interp.traits.containsKey(decl.name)) return
                val superTraits: List<KSTrait> = decl.superTraits
                    .mapNotNull { interp.traits[it.name] }
                interp.traits[decl.name] =
                    KSTrait(decl, superTraits, interp.environment)
            }
            is StructDecl -> {
                if (interp.structs.containsKey(decl.name)) return
                val traits: List<KSTrait> = decl.traits
                    .mapNotNull { interp.traits[it.name] }
                interp.structs[decl.name] =
                    KSStruct(decl, traits, interp.environment)
            }
            is EnumDecl -> {
                if (interp.enums.containsKey(decl.name)) return
                val ksEnum = KSEnum(decl, interp.environment)
                // Populate constant names (ordinal/args not needed for
                // completion — names + signatures come off the declaration).
                decl.constants.forEachIndexed { ordinal, c ->
                    ksEnum.addConstant(
                        c.name,
                        io.kixi.ks.interp.KSEnumConstant(ksEnum, c.name, ordinal, emptyList())
                    )
                }
                interp.enums[decl.name] = ksEnum
            }
            else -> { /* not a type declaration */ }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TypeModel implementation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Default [TypeModel] backed by a populated (define-types-only)
 * [Interpreter]. All enumeration is read-only; nothing here executes KS
 * code.
 *
 * @param interp     An interpreter whose type registries and import
 *                   registry have been populated by [buildKSTypeModel].
 * @param typeDecls  The in-buffer type declaration nodes, in source
 *                   order, used for [topLevelCallables] ordering and
 *                   [typeNames].
 */
internal class KSTypeModel(
    private val interp: Interpreter,
    private val typeDecls: List<Node>
) : TypeModel {

    override fun resolveType(name: String): ResolvedType? {
        // 1. In-buffer KS declarations.
        interp.classes[name]?.let { return KsClassType(it) }
        interp.structs[name]?.let { return KsStructType(it) }
        interp.traits[name]?.let { return KsTraitType(it) }
        interp.enums[name]?.let { return KsEnumType(it) }

        // 2. `use` imports (JVM classes; imported KS types already covered
        //    above once registered).
        if (interp.importRegistry.hasImports()) {
            val resolved = runCatching { interp.importRegistry.resolve(name) }.getOrNull()
            if (resolved is ResolvedImport.JVMClass) {
                return JvmType(name, resolved.proxy)
            }
        }

        // 3. Native Ki types.
        interp.nativeTypes[name]?.let { return NativeType(name) }

        // 4. Builtin sentinels (String, Int, Double, Bool, …).
        if (interp.builtinTypes.containsKey(name)) {
            return BuiltinType(name)
        }

        return null
    }

    override fun members(type: ResolvedType): List<MemberItem> = when (type) {
        is KsClassType  -> ksClassMembers(type.cls)
        is KsStructType -> ksStructMembers(type.struct)
        is KsTraitType  -> ksTraitMembers(type.trait)
        is KsEnumType   -> ksEnumMembers(type.enum)
        is JvmType      -> jvmMembers(type.name, type.proxy.clazz)
        is BuiltinType  -> builtinMembers(type.name)
        is NativeType   -> emptyList() // native member enumeration: slice 2+
    }

    override fun topLevelCallables(): List<MemberItem> {
        // (Top-level FunDecls would be added here once the buffer program is
        // retained; slice 1's prefix mode resolves those consumer-side from
        // the same parse. Imported free functions are enumerated via the
        // import registry in a later step.)
        return emptyList()
    }

    override fun typeNames(): List<String> {
        val names = LinkedHashSet<String>()
        // In-buffer declarations, source order.
        for (decl in typeDecls) {
            when (decl) {
                is ClassDecl  -> names.add(decl.name)
                is TraitDecl  -> names.add(decl.name)
                is StructDecl -> names.add(decl.name)
                is EnumDecl   -> names.add(decl.name)
                else -> {}
            }
        }
        // Builtins are always visible.
        names.addAll(interp.builtinTypes.keys)
        return names.toList()
    }

    // ── KS class: own members + superclass chain + traits ────────────────

    private fun ksClassMembers(cls: KSClass): List<MemberItem> {
        val out = mutableListOf<MemberItem>()
        val seen = HashSet<String>() // nearest-declaration-wins for KS names

        var current: KSClass? = cls
        while (current != null) {
            collectKsDeclMembers(
                declaringType = current.name,
                members = current.declaration.members,
                constructorParams = current.constructorParams,
                seen = seen,
                out = out
            )
            // Traits implemented at this level.
            for (trait in current.traits) {
                collectTraitMembers(trait, seen, out)
            }
            current = current.superclass
        }
        return out
    }

    // ── KS struct: own members + traits (structs have no superclass) ─────

    private fun ksStructMembers(struct: KSStruct): List<MemberItem> {
        val out = mutableListOf<MemberItem>()
        val seen = HashSet<String>()
        collectKsDeclMembers(
            declaringType = struct.name,
            members = struct.declaration.members,
            constructorParams = struct.constructorParams,
            seen = seen,
            out = out
        )
        for (trait in struct.traits) {
            collectTraitMembers(trait, seen, out)
        }
        return out
    }

    // ── KS trait: own methods + super-traits ─────────────────────────────

    private fun ksTraitMembers(trait: KSTrait): List<MemberItem> {
        val out = mutableListOf<MemberItem>()
        val seen = HashSet<String>()
        collectTraitMembers(trait, seen, out)
        return out
    }

    // ── KS enum: constants + own methods + properties ────────────────────

    private fun ksEnumMembers(enum: KSEnum): List<MemberItem> {
        val out = mutableListOf<MemberItem>()
        val decl = enum.declaration

        // Constants.
        for (constant in decl.constants) {
            out.add(
                MemberItem(
                    name = constant.name,
                    kind = MemberKind.ENUM_CONSTANT,
                    signature = constant.name,
                    declaringType = enum.name,
                    origin = MemberOrigin.KS
                )
            )
        }

        val seen = HashSet<String>()
        collectKsDeclMembers(
            declaringType = enum.name,
            members = decl.members,
            constructorParams = decl.constructorParams,
            seen = seen,
            out = out
        )
        return out
    }

    /**
     * Collect instance methods + properties declared directly on a KS type
     * level (class/struct/enum), honouring nearest-wins via [seen]. Static
     * members and embedded enums are skipped (statics belong to type-name
     * receivers, handled separately in a later step).
     */
    private fun collectKsDeclMembers(
        declaringType: String,
        members: List<Node>,
        constructorParams: List<ConstructorParam>,
        seen: MutableSet<String>,
        out: MutableList<MemberItem>
    ) {
        // Properties from constructor bindings (let/var params).
        for (param in constructorParams) {
            if (param.binding == null) continue
            if (!seen.add(param.name)) continue
            out.add(
                MemberItem(
                    name = param.name,
                    kind = MemberKind.PROPERTY,
                    signature = ksPropertySignature(
                        binding = param.binding,
                        name = param.name,
                        typeRef = param.type
                    ),
                    declaringType = declaringType,
                    origin = MemberOrigin.KS
                )
            )
        }

        for (member in members) {
            when (member) {
                is VarDecl -> {
                    if (!seen.add(member.name)) continue
                    val binding = if (member.mutable) BindingType.VAR else BindingType.LET
                    out.add(
                        MemberItem(
                            name = member.name,
                            kind = MemberKind.PROPERTY,
                            signature = ksPropertySignature(
                                binding = binding,
                                name = member.name,
                                typeRef = member.typeAnnotation
                            ),
                            declaringType = declaringType,
                            origin = MemberOrigin.KS
                        )
                    )
                }
                is FunDecl -> {
                    if (!seen.add(member.name)) continue
                    out.add(
                        MemberItem(
                            name = member.name,
                            kind = MemberKind.METHOD,
                            signature = MembersFormatter.formatFunSignature(member),
                            declaringType = declaringType,
                            origin = MemberOrigin.KS
                        )
                    )
                }
                else -> { /* StaticBlock, EnumDecl, etc. — not instance members */ }
            }
        }
    }

    private fun collectTraitMembers(
        trait: KSTrait,
        seen: MutableSet<String>,
        out: MutableList<MemberItem>
    ) {
        for (member in trait.declaration.members) {
            if (member is FunDecl) {
                if (!seen.add(member.name)) continue
                out.add(
                    MemberItem(
                        name = member.name,
                        kind = MemberKind.METHOD,
                        signature = MembersFormatter.formatFunSignature(member),
                        declaringType = trait.name,
                        origin = MemberOrigin.KS
                    )
                )
            }
        }
        for (superTrait in trait.superTraits) {
            collectTraitMembers(superTrait, seen, out)
        }
    }

    /**
     * Render a KS property signature consistently with `MembersFormatter`'s
     * property lines: `let name: String` / `var age: Int`. The leading
     * binding keyword is dropped from the completion [MemberItem.signature]
     * (the row reads `name: Type`), matching how IntelliJ shows fields; the
     * binding is implied by the property icon. Constraints are omitted here
     * for brevity in the completion row.
     */
    private fun ksPropertySignature(
        binding: BindingType,
        name: String,
        typeRef: io.kixi.ks.parser.TypeRef?
    ): String {
        val sb = StringBuilder(name)
        if (typeRef != null) {
            sb.append(": ")
            sb.append(MembersFormatter.formatTypeRef(typeRef))
        }
        return sb.toString()
    }

    // ── JVM class: reflection, inheritance free via clazz.methods ────────

    private fun jvmMembers(typeName: String, clazz: Class<*>): List<MemberItem> {
        val out = mutableListOf<MemberItem>()
        val seenProps = HashSet<String>()

        val methods: List<Method> = JVMMembersFormatter.getAllPublicMethods(clazz)

        for (method in methods) {
            if (Modifier.isStatic(method.modifiers)) continue // instance only (slice 1)

            // Getter → property.
            val propName = JVMMembersFormatter.extractPropertyName(method)
            if (propName != null) {
                if (seenProps.add(propName)) {
                    out.add(
                        MemberItem(
                            name = propName,
                            kind = MemberKind.PROPERTY,
                            signature = "$propName: ${JVMMembersFormatter.mapGenericType(method.genericReturnType)}",
                            declaringType = JVMMembersFormatter.mapTypeName(method.declaringClass),
                            origin = MemberOrigin.JVM
                        )
                    )
                }
                continue
            }

            out.add(
                MemberItem(
                    name = method.name,
                    kind = MemberKind.METHOD,
                    // formatMethod renders the full signature; overloads are
                    // distinct items, each with its own signature.
                    signature = JVMMembersFormatter.formatMethod(method),
                    declaringType = JVMMembersFormatter.mapTypeName(method.declaringClass),
                    origin = MemberOrigin.JVM
                )
            )
        }
        return out
    }

    // ── Builtin sentinels: parsed from JVMMembersFormatter's curated blob ─

    private fun builtinMembers(typeName: String): List<MemberItem> {
        // Single source of truth: JVMMembersFormatter's curated member blob,
        // parsed into structured items by BuiltinMembers (instance members
        // only for slice 1). No second table to drift.
        return BuiltinMembers.instanceMembers(typeName)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  ResolvedType implementations (opaque to consumers)
// ─────────────────────────────────────────────────────────────────────────────

private class KsClassType(val cls: KSClass) : ResolvedType {
    override val name: String get() = cls.name
    override val kind: TypeKind get() = TypeKind.CLASS
}

private class KsStructType(val struct: KSStruct) : ResolvedType {
    override val name: String get() = struct.name
    override val kind: TypeKind get() = TypeKind.STRUCT
}

private class KsTraitType(val trait: KSTrait) : ResolvedType {
    override val name: String get() = trait.name
    override val kind: TypeKind get() = TypeKind.TRAIT
}

private class KsEnumType(val enum: KSEnum) : ResolvedType {
    override val name: String get() = enum.name
    override val kind: TypeKind get() = TypeKind.ENUM
}

private class JvmType(
    override val name: String,
    val proxy: JVMClassProxy
) : ResolvedType {
    override val kind: TypeKind
        get() = if (proxy.clazz.isInterface) TypeKind.INTERFACE else TypeKind.CLASS
}

private class BuiltinType(override val name: String) : ResolvedType {
    override val kind: TypeKind get() = TypeKind.BUILTIN
}

private class NativeType(override val name: String) : ResolvedType {
    override val kind: TypeKind get() = TypeKind.NATIVE
}