package io.kixi.ks.interp

import io.kixi.ks.KSRuntime
import io.kixi.ks.TypeError
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.PrintWriter
import java.io.StringWriter

/** A host class with Kotlin operator get overloads, like RevDB's Row. */
class HostLookup {
    operator fun get(i: Int): Int = i * 10
    operator fun get(key: String): String = key.uppercase()
}

/** As in Kotlin, obj[index] on a host object calls its public get(index) (hostLang only). */
class HostIndexTest : FunSpec({

    fun eval(source: String, hostLang: Boolean): Any? {
        val rt = KSRuntime(
            hostLang = hostLang, colorOutput = false,
            outputWriter = PrintWriter(StringWriter(), true), errorWriter = PrintWriter(StringWriter(), true), debugMode = false,
        )
        val interp = Interpreter(rt)
        interp.define("h", HostLookup())
        return interp.executeProgram(Parser(Lexer(source).tokenize()).parse())
    }

    test("an index calls the get overload that matches the argument") {
        eval("h[4]", hostLang = true) shouldBe 40
        eval("h[\"ab\"]", hostLang = true) shouldBe "AB"
    }

    test("without host interop a host object cannot be indexed") {
        shouldThrow<TypeError> { eval("h[4]", hostLang = false) }.message shouldContain "Cannot index into HostLookup"
    }
})
