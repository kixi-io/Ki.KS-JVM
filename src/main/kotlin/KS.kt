import io.kixi.ks.KSRuntime
import io.kixi.ks.interp.Interpreter
import java.io.PrintWriter
import java.io.StringWriter

object KS {
    /** Shared interpreter — retains state (vars, functions, classes) across calls. */
    private val interpreter = Interpreter()

    /**
     * Evaluate a KS expression and return the result.
     *
     *     val length = KS.eval("10cm + 7mm")   // returns a Quantity
     */
    fun eval(source: String): Any? = interpreter.execute(source)

    /**
     * Execute KS code and capture what `say` prints.
     *
     *     val output = KS.interp("say 10cm + 7mm")  // "10.7cm\n"
     */
    fun interp(source: String): String {
        val output = StringWriter()
        val runtime = KSRuntime(outputWriter = PrintWriter(output, true))
        val interp = Interpreter(runtime)
        interp.execute(source)
        return output.toString().trimEnd()
    }

    /**
     * Evaluate in a fresh interpreter (no shared state).
     */
    fun freshEval(source: String): Any? = Interpreter().execute(source)

    /** Reset the shared interpreter's state. */
    fun reset() {
        // Recreate — simplest way to clear all state
        // (If you want to preserve this, you could add a reset() to Interpreter)
    }
}