package io.kixi.ks.interp

import io.kixi.Measure
import io.kixi.Vector
import io.kixi.kd.KD
import io.kixi.ks.KSRuntime
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.PrintWriter
import java.io.StringWriter

/**
 * TB7 re-check (2026-10-29), Ki.KS: literals against Ki.Core and KD, precedence, members,
 * errors a script can catch, and the manual's examples.
 */
class TB7RecheckKSTest : FunSpec({

    fun eval(source: String): Any? {
        val runtime = KSRuntime(
            hostLang = false, colorOutput = false,
            outputWriter = PrintWriter(StringWriter(), true), errorWriter = PrintWriter(StringWriter(), true), debugMode = false
        )
        return Interpreter(runtime).executeProgram(Parser(Lexer(source).tokenize()).parse())
    }
    fun run(source: String): String = try { "OK " + eval(source).let { if (it == null) "nil" else "$it (${it::class.simpleName})" } } catch (e: Throwable) { "ERR ${e::class.simpleName}: ${e.message?.lineSequence()?.first()}" }

    fun report(name: String, problems: List<String>) {
        if (problems.isNotEmpty()) println("[$name] ${problems.size} problems:\n  " + problems.joinToString("\n  "))
        withClue(problems.joinToString("\n")) { problems.size shouldBe 0 }
    }

    test("S1 a measure written in KS is the measure Ki.Core and KD read from the same text") {
        val problems = ArrayList<String>()
        for (text in listOf("0.512±0.008", "5±0.1cm", "5.0±1cm", "5±1.0cm", "0.5±1cm", "5cm±1mm", "20±1dF", "37±0.5dF", "5±1cd", "1±0.1mL",
            "12±1min", "12±1min:i", "5±1s", "5±1h", "2±1day", "5±1ms", "1e3±1", "5±1u", "1.5±0.1LT", "20±1°C")) {
            val core = try { Measure.parse(text) } catch (e: Exception) { null }
            val kd = try { KD.read("t $text").values.single() } catch (e: Exception) { null }
            val ks = try { eval(text) } catch (e: Throwable) { "ERR ${e::class.simpleName}: ${e.message?.lineSequence()?.first()}" }
            if (ks != core || kd != core) {
                val types = listOf(core, kd, ks).map { (it as? Measure)?.let { m -> ((m.value as? io.kixi.uom.Quantity<*>)?.value ?: m.value)::class.simpleName } }
                problems += "$text: Ki.Core $core, KD $kd, KS $ks (number types $types)"
            }
        }
        report("S1", problems)
    }

    test("S2 precedence: ± binds tighter than * and /, looser than ** and prefix minus; one ± per value") {
        val problems = ArrayList<String>()
        val expect = linkedMapOf(
            "-3±1" to Measure.of(-3, 1),
            "-(3±1)" to Measure.of(-3, 1),
            "2 * 5±1" to Measure.combine('*', 2, Measure.of(5, 1)),
            "10 / 5±1" to Measure.combine('/', 10, Measure.of(5, 1)),
            "1 + 5±1" to Measure.combine('+', 1, Measure.of(5, 1)),
            "2**2±1" to Measure.of(4, 1),
            "5±2**2" to Measure.of(5, 4),
        )
        for ((src, m) in expect) {
            val r = try { eval(src) } catch (e: Throwable) { "ERR ${e.message}" }
            if (r != m) problems += "$src = $r, expected $m"
        }
        if (!run("5 ± 1 ± 2").startsWith("ERR")) problems += "5 ± 1 ± 2 is not an error: ${run("5 ± 1 ± 2")}"
        report("S2", problems)
    }

    test("S3 every measure and vector error is one a script catches (F: try/catch)") {
        val problems = ArrayList<String>()
        for (expr in listOf(
            "(5±1mM) * (2±1mM)", "(5±1°C) * (2±1°C)", "(5±1) / 0", "(5±1) / (0±1)", "(5±1cm) + (5±1g)", "(5±1cm) < (5±1g)", "(5±1) < 5",
            "(5±1cm) + 1", "(5±1) % 2", "(5±1) ** 2", "5 ± -1", "5 ± \"a\"", "(5±1).nope",
            ".vec<Long> { 9223372036854775807 } + .vec<Long> { 1 }",   // a Long vector's sum() wraps, as Kotlin's and KQ's do (design 4.5)
            ".vec<Int> { 1 }.dot(.vec<Int> { 1 2 })", ".vec<Int> { 1 } / 0", ".vec<Int> { 2147483647 } * 2", "-.vec<Int> { -2147483648 }",
            ".vec<Float> { 3.0e38 } * 10", ".vec<Int> { 1 2 }[5]", ".vec<Int> { 1 2 }[-1]", ".vec<Int> { 1 } * (5±1)", ".vec<Int> { 1 } + 1",
            "(5±1cm).to(kg)",
        )) {
            val r = run("try { $expr } catch (e) { \"caught\" }")
            if (r != "OK caught (String)") problems += "$expr: $r"
        }
        report("S3", problems)
    }

    test("S4 arithmetic never makes a measure that cannot be read back") {
        val problems = ArrayList<String>()
        for (expr in listOf("(1e308±1) * 10", "(1.0e308±1.0) + (1.0e308±1.0)")) {
            val r = try { eval(expr) } catch (e: Throwable) { null }
            if (r is Measure) {
                val back = try { Measure.parse(r.toString()); "reads back" } catch (e: Exception) { "does not read back" }
                problems += "$expr = $r ($back)"
            }
        }
        report("S4", problems)
    }

    test("S5 members: value, uncertainty, relative, unit, to (type bags design 12.1); vector members and iteration") {
        val problems = ArrayList<String>()
        val expect = linkedMapOf(
            "(20±0.5°C).value" to "OK 20°C (Quantity)",          // the manual says 20
            "(20±0.5°C).uncertainty" to "OK 0.5°C (Quantity)",
            "(10±0.5).relative" to "OK 0.05 (Double)",
            "(5±1cm).unit" to "OK cm (String)",
            "(5±1).unit" to "OK nil",
            "(5±1cm).to(\"mm\")" to "OK 50±10mm (Measure)",           // KS names units as text, as convertTo("mm") does for a quantity
            "(5±1cm).convertTo(\"mm\")" to "OK 50±10mm (Measure)",
            "(20±0.5°C).to(\"°F\")" to "OK 68±0.9°F (Measure)",
            "(1±0.1cm) == (10±1mm)" to "OK true (Boolean)",
            "(5±1) < (5±2)" to "OK true (Boolean)",
            "(10±3) + (20±4)" to "OK 30.0±5.0 (Measure)",
            "(4±0.3) * 2" to "OK 8.0±0.6 (Measure)",
            "let v = .vec<Int> { 3 1 4 }\nvar s = 0\nfor x in v { s = s + x }\ns" to "OK 8 (Int)",
            ".vec<Int> { 3 1 4 }.sum()" to "OK 8 (Long)",
            ".vec<Int> { 3 1 4 }[2]" to "OK 4 (Int)",
            ".vec<Double> { 3 4 }.norm()" to "OK 5.0 (Double)",
            ".vec<Int> { 1 } == .vec<Double> { 1.0 }" to "OK true (Boolean)",
            "(5±1) is Measure" to "OK true (Boolean)",
            "\"m=\" + (5±1)" to "OK m=5±1 (String)",
        )
        for ((src, want) in expect) { val r = run(src); if (r != want) problems += "${src.replace("\n", "; ")} -> $r (expected $want)" }
        report("S5", problems)
    }

    test("S7 a difference of temperature measures in two units is the difference of the temperatures") {
        val problems = ArrayList<String>()
        for ((src, want) in listOf("((50±1°F) - (20±1°C)).value == 50°F - 20°C" to "OK true (Boolean)", "50°F - 20°C" to "OK -18°F (Quantity)")) {
            val r = run(src); if (r != want) problems += "$src -> $r (expected $want); (50±1°F) - (20±1°C) = ${run("(50±1°F) - (20±1°C)")}"
        }
        report("S7", problems)
    }

    test("S6 -0.0 in a KS vector literal keeps its sign as KD's does") {
        val problems = ArrayList<String>()
        for (text in listOf(".vec<Double> { -0.0 }", ".vec<Float> { -0.0 }", ".vec<Double> { 1, -0.0 }")) {
            val kd = KD.read("t $text").values.single()
            val ks = try { eval(text) } catch (e: Throwable) { "ERR ${e.message}" }
            if (ks != kd) problems += "$text: KS $ks, KD $kd"
        }
        report("S6", problems)
    }
})
