package io.kixi.ks.interp

import io.kixi.ks.KSRuntime
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.core.spec.style.FunSpec
import java.io.PrintWriter
import java.io.StringWriter

/** TB7 re-check (2026-10-29), context for S3: the same undefined products with plain quantities. Prints only. */
class TB7RecheckKSQuantityTest : FunSpec({
    fun eval(source: String): Any? {
        val runtime = KSRuntime(hostLang = false, colorOutput = false,
            outputWriter = PrintWriter(StringWriter(), true), errorWriter = PrintWriter(StringWriter(), true), debugMode = false)
        return Interpreter(runtime).executeProgram(Parser(Lexer(source).tokenize()).parse())
    }
    test("Q1 try/catch around undefined quantity products") {
        for (expr in listOf("5mM * 2mM", "5°C * 2°C", "(5±1mM) * (2±1mM)")) {
            val r = try { eval("try { $expr } catch (e) { \"caught\" }").toString() } catch (e: Throwable) { "escaped: ${e::class.simpleName}" }
            println("[Q1] $expr -> $r")
        }
    }
})
