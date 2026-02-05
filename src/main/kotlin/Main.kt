package io.kixi.ks

import io.kixi.ks.interp.Interpreter
import java.io.File

fun main(args: Array<String>) {
    val interpreter = Interpreter()

    // Use first argument, or default to Demo.ks in src/main/kotlin/
    val scriptFile = if (args.isNotEmpty()) {
        File(args[0])
    } else {
        File("src/main/kotlin/Demo.ks")
    }

    if (scriptFile.exists()) {
        println("Running: ${scriptFile.absolutePath}")
        println("=".repeat(50))
        println()
        try {
            val source = scriptFile.readText()
            interpreter.execute(source)
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            e.printStackTrace()
        }
    } else {
        // Fallback to inline demo
        println("=== KS Interpreter ===")
        println("(Demo.ks not found at: ${scriptFile.absolutePath})")
        println()
        interpreter.execute("""say "Aloha world!" """)
        interpreter.execute("""say.note "This is a note" """)
        interpreter.execute("""say.warn "This is a warning" """)
        interpreter.execute("""say.error "This is an error" """)
    }
}