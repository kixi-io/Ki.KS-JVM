package io.kixi.ks.interp

import io.kixi.ks.Callable
import io.kixi.ks.KSMembers
import io.kixi.ks.MemberNotFoundError
import io.kixi.ks.RuntimeError
import io.kixi.ks.SourceLocation
import io.kixi.ks.TypeError
import io.kixi.types.BagFunction
import io.kixi.types.ExtLiteral
import io.kixi.types.Member
import io.kixi.types.TypeBag
import io.kixi.types.TypeBags

/*
 * KS's side of type bags (io.kixi.types, Ki type bags design 3.2): the members of a
 * bag's values, its functions and constants through `use ki.<bag>`. Bags are curated
 * by their authors, so all of this works in every hostLang mode, portable included.
 */

/** A method of a bag value, bound to the value: `seq.slice` before it is called. */
internal class BagMethod(val receiver: Any, val member: Member<Any>) : Callable {
    override fun call(interpreter: Interpreter, arguments: List<Any?>, location: SourceLocation?): Any? {
        val arity = member.arity ?: 0..0
        if (arguments.size !in arity) {
            throw RuntimeError("${member.name}(...) takes ${describe(arity)}, not ${arguments.size}", location)
        }
        return runBag(location, member.name) { member.invoke(receiver, arguments) }
    }

    override fun toString(): String = "<bag method ${member.name}>"
}

/** A bag function bound by `use ki.<bag>.*` or reached through the bag object. */
internal class BagFunctionCallable(val function: BagFunction) : Callable {
    override fun call(interpreter: Interpreter, arguments: List<Any?>, location: SourceLocation?): Any? {
        if (arguments.size !in function.arity) {
            throw RuntimeError("${function.name}(...) takes ${describe(function.arity)}, not ${arguments.size}", location)
        }
        return runBag(location, function.name) { function.invoke(arguments) }
    }

    override fun toString(): String = "<bag fun ${function.name}>"
}

/** The bag itself, bound by `use ki.<bag>`: its functions and constants as members (`bio.revcomp(s)`). */
internal class BagObject(val bag: TypeBag) : KSMembers {
    override fun ksMember(name: String): Any? = bagValue(bag, name)

    override fun toString(): String = "<bag ${bag.name}>"
}

/** [bag]'s function (as a callable) or constant called [name], or null. */
internal fun bagValue(bag: TypeBag, name: String): Any? =
    bag.functions.firstOrNull { it.name == name }?.let { BagFunctionCallable(it) } ?: bag.constants[name]

/** Every function and constant of [bag], by name, as `use ki.<bag>.*` binds them. */
internal fun bagValues(bag: TypeBag): Map<String, Any> =
    bag.functions.associate { it.name to BagFunctionCallable(it) as Any } + bag.constants

/**
 * The member [name] of a bag value, or [notFound] when its type has none: a
 * property's value (which may be nil), or a method bound to the value.
 */
internal fun bagMember(obj: Any, name: String, location: SourceLocation?, notFound: Any): Any? {
    // A member the type does not declare may still be one the value exposes itself (KSMembers).
    val m = TypeBags.member(obj, name) ?: return (obj as? KSMembers)?.ksMember(name) ?: notFound
    return if (m.isProperty) runBag(location, name) { m.invoke(obj, emptyList()) } else BagMethod(obj, m)
}

/**
 * `<` and friends for two values of one type bag type that orders: their sort keys,
 * unsigned, as KQ and RevDB's indexes order them. Null when neither is a bag value.
 */
internal fun bagCompare(left: Any?, right: Any?): Int? {
    val l = left?.let { TypeBags.typeFor(it) }
    val r = right?.let { TypeBags.typeFor(it) }
    if (l == null && r == null) return null
    if (l == null || r == null || l !== r) {
        throw TypeError("Cannot compare ${l?.name ?: left?.let { it::class.simpleName } ?: "nil"} with ${r?.name ?: right?.let { it::class.simpleName } ?: "nil"}")
    }
    if (!l.ordered) throw TypeError("Cannot compare ${l.name} values: the type does not order")
    return java.util.Arrays.compareUnsigned(l.sortKey(left!!), l.sortKey(right!!))
}

/** The error for a member of a literal whose bag is not loaded. */
internal fun extLiteralMemberError(obj: ExtLiteral, member: String, location: SourceLocation?): MemberNotFoundError =
    MemberNotFoundError(member, "ExtLiteral", location,
        suggestion = "${obj.literal} was read without its type bag; load the bag that defines .${obj.name}(…) to use its members")

/** Runs bag code, turning its exceptions into KS errors at [location]. */
private inline fun runBag(location: SourceLocation?, what: String, block: () -> Any?): Any? = try {
    block()
} catch (e: RuntimeError) {
    throw e
} catch (e: Exception) {          // bag code may throw checked exceptions too
    throw RuntimeError("$what failed: ${e.message ?: e.javaClass.simpleName}", location, e)
}

private fun describe(r: IntRange): String = when {
    r.first == r.last -> if (r.first == 1) "1 argument" else "${r.first} arguments"
    r.last == Int.MAX_VALUE -> "at least ${r.first} arguments"
    else -> "${r.first} to ${r.last} arguments"
}

/**
 * Reads a type bag's literal (`.dna("ATG")`) through its bag. The lexer finds the
 * literal without asking which bags are loaded, so this is where a missing bag or a
 * literal the bag refuses is reported.
 */
internal fun parseBagLiteral(text: String, location: SourceLocation?): Any {
    val name = text.drop(1).takeWhile { it.isLetter() }
    val type = TypeBags.literal(name) ?: throw RuntimeError(
        "Unknown literal .$name(…)", location,
        suggestion = if (name.lowercase() in TypeBags.RESERVED_LITERALS) ".$name(…) is a KD literal that KS does not read"
        else "No loaded type bag reads .$name(…). Is the bag that defines it on the classpath?")
    return try {
        type.parse(text)
    } catch (e: RuntimeError) {
        throw e
    } catch (e: Exception) {
        throw RuntimeError("Invalid .$name literal: ${e.message ?: e.javaClass.simpleName}", location, e)
    }
}

/** `value in container` for a bag value whose type has `contains(x)`: true only when it answers true. */
internal fun bagContains(container: Any, contains: Member<Any>, value: Any?, location: SourceLocation? = null): Boolean =
    runBag(location, "contains") { contains.invoke(container, listOf(value)) } == true
