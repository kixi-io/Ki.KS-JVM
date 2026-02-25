package io.kixi.ks

/**
 * KS Runtime Configuration
 *
 * Holds configuration options that affect both interpretation and compilation.
 * This is the "context" for running KS code — it determines what features are
 * available and how certain behaviors work.
 *
 * Shared between Interpreter and Compiler:
 * - Interpreter uses it to determine runtime behavior
 * - Compiler uses it to determine what code to generate and what checks to perform
 *
 * ## Host Language Interop
 *
 * The [hostLang] flag is the primary configuration for portability:
 *
 * - **hostLang = true (default)**: Interop mode. KS code can access the host
 *   language's types, functions, and libraries directly. This is the most
 *   productive mode for development on a specific platform.
 *
 * - **hostLang = false**: Portable mode. KS code is limited to the KS standard
 *   library (KD types, basic I/O, etc.). Code written this way will run on any
 *   KS implementation (Kotlin, Swift, Rust, .NET, etc.).
 *
 * ## Configuration File
 *
 * Runtime options can be overridden via a `ks-config.kd` file. The loader
 * checks the following locations in order:
 *
 * 1. `./ks-config.kd` — project-local config
 * 2. `~/.ks/ks-config.kd` — user-level config
 * 3. Falls back to [DEFAULT] if no config file is found
 *
 * ```kd
 * runtime {
 *     hostLang true
 *     strictNullSafety true
 *     checkConstraints true
 *     maxRecursionDepth 1000
 *     maxLoopIterations 10_000_000
 *     colorOutput true
 *     debugMode false
 * }
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * // Default: interop mode
 * val interpreter = Interpreter()  // Uses KSRuntime.DEFAULT
 *
 * // Explicit portable mode
 * val runtime = KSRuntime(hostLang = false)
 * val interpreter = Interpreter(runtime)
 * interpreter.execute(source)
 * ```
 */
data class KSRuntime(
    /**
     * Whether host language interop is enabled.
     *
     * When `true` (default), KS code can access Kotlin/Swift/etc. types and
     * functions directly. When `false`, KS code is limited to the portable
     * standard library — code written this way runs on any KS implementation
     * (Kotlin, Swift, Rust, .NET).
     */
    val hostLang: Boolean = true,

    /**
     * Whether to enforce strict null safety.
     *
     * When `true`, operations on nullable types require explicit null handling.
     * When `false`, some null operations may silently return nil.
     */
    val strictNullSafety: Boolean = true,

    /**
     * Whether to check constraints at runtime.
     *
     * Constraints are always checked by default. This flag exists primarily for
     * performance testing or when constraints have been verified at compile time.
     */
    val checkConstraints: Boolean = true,

    /**
     * Maximum recursion depth for function calls.
     *
     * Prevents stack overflow from infinite recursion. Set to 0 for unlimited
     * (not recommended).
     */
    val maxRecursionDepth: Int = 1000,

    /**
     * Maximum iterations for loops.
     *
     * Prevents infinite loops. Set to 0 for unlimited (not recommended for
     * untrusted code).
     */
    val maxLoopIterations: Long = 10_000_000L,

    /**
     * Output stream for `say` statements.
     *
     * Defaults to [System.out]. Can be redirected for testing or embedding.
     */
    val outputWriter: java.io.PrintWriter = java.io.PrintWriter(System.out, true),

    /**
     * Error stream for `say.error` statements.
     *
     * Defaults to [System.err]. Can be redirected for testing or embedding.
     */
    val errorWriter: java.io.PrintWriter = java.io.PrintWriter(System.err, true),

    /**
     * Whether to include ANSI color codes in output.
     *
     * Used for `say.error`, `say.warn`, `say.note` formatting.
     * Disable for environments that don't support ANSI codes.
     */
    val colorOutput: Boolean = true,

    /**
     * Debug mode flag.
     *
     * When enabled, the interpreter may:
     * - Print additional diagnostic information
     * - Include more detailed stack traces
     * - Perform additional validation checks
     */
    val debugMode: Boolean = false
) {
    companion object {
        /**
         * Default runtime configuration.
         *
         * Interop mode (hostLang = true), all safety checks enabled.
         * Override via `ks-config.kd` or explicit construction.
         */
        val DEFAULT = KSRuntime()

        /**
         * Configuration for portable mode.
         *
         * Limits KS code to the portable standard library. Code written this
         * way runs on any KS implementation (Kotlin, Swift, Rust, .NET, etc.).
         */
        val PORTABLE = KSRuntime(hostLang = false)

        /**
         * Configuration for testing.
         *
         * Disables colors, redirects output to provided writers.
         * Uses portable mode by default for deterministic test behavior.
         */
        fun forTesting(
            output: java.io.StringWriter = java.io.StringWriter(),
            error: java.io.StringWriter = java.io.StringWriter()
        ) = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = java.io.PrintWriter(output, true),
            errorWriter = java.io.PrintWriter(error, true),
            debugMode = true
        )

        /**
         * Configuration for testing with host language interop enabled.
         *
         * Same as [forTesting] but with `hostLang = true`, for tests that
         * exercise JVM class imports or reflection features.
         */
        fun forInteropTesting(
            output: java.io.StringWriter = java.io.StringWriter(),
            error: java.io.StringWriter = java.io.StringWriter()
        ) = KSRuntime(
            hostLang = true,
            colorOutput = false,
            outputWriter = java.io.PrintWriter(output, true),
            errorWriter = java.io.PrintWriter(error, true),
            debugMode = true
        )

        // ================================================================
        // KD Configuration File Loading
        // ================================================================

        /**
         * Standard config file name.
         */
        private const val CONFIG_FILE_NAME = "ks-config.kd"

        /**
         * Load runtime configuration from a KD file.
         *
         * Looks for `ks-config.kd` in the following locations (in order):
         * 1. Current working directory: `./ks-config.kd`
         * 2. User home KS directory: `~/.ks/ks-config.kd`
         * 3. Falls back to [DEFAULT] if no config file is found
         *
         * ## KD Config Format
         *
         * ```kd
         * runtime {
         *     hostLang true
         *     strictNullSafety true
         *     checkConstraints true
         *     maxRecursionDepth 1000
         *     maxLoopIterations 10_000_000
         *     colorOutput true
         *     debugMode false
         * }
         * ```
         *
         * All fields are optional — unspecified fields use defaults.
         *
         * @return KSRuntime configured from the KD file, or [DEFAULT]
         */
        fun fromConfig(): KSRuntime {
            val configLocations = listOf(
                java.io.File(CONFIG_FILE_NAME),
                java.io.File(System.getProperty("user.home"), ".ks/$CONFIG_FILE_NAME")
            )

            for (file in configLocations) {
                if (file.exists() && file.canRead()) {
                    return parseConfigFile(file)
                }
            }

            return DEFAULT
        }

        /**
         * Parse a KD configuration file into a KSRuntime.
         *
         * Currently a placeholder — full implementation requires Ki.KD library
         * integration. Returns [DEFAULT] until KD parsing is wired in.
         *
         * TODO: Wire in Ki.KD library for config parsing:
         * ```kotlin
         * val doc = io.kixi.kd.KD.read(file)
         * val runtime = doc["runtime"]
         * return KSRuntime(
         *     hostLang = runtime?.getBool("hostLang") ?: true,
         *     strictNullSafety = runtime?.getBool("strictNullSafety") ?: true,
         *     checkConstraints = runtime?.getBool("checkConstraints") ?: true,
         *     maxRecursionDepth = runtime?.getInt("maxRecursionDepth") ?: 1000,
         *     maxLoopIterations = runtime?.getLong("maxLoopIterations") ?: 10_000_000L,
         *     colorOutput = runtime?.getBool("colorOutput") ?: true,
         *     debugMode = runtime?.getBool("debugMode") ?: false
         * )
         * ```
         */
        private fun parseConfigFile(file: java.io.File): KSRuntime {
            // TODO: Implement KD config parsing when Ki.KD integration is ready
            return DEFAULT
        }
    }

    /**
     * Creates a copy of this runtime with the specified changes.
     *
     * Kotlin's data class `copy()` is already available, but this provides
     * a more fluent API for common modifications.
     */
    fun withHostLang(enabled: Boolean) = copy(hostLang = enabled)
    fun withDebugMode(enabled: Boolean) = copy(debugMode = enabled)
    fun withColorOutput(enabled: Boolean) = copy(colorOutput = enabled)
    fun withMaxRecursion(depth: Int) = copy(maxRecursionDepth = depth)
    fun withMaxIterations(iterations: Long) = copy(maxLoopIterations = iterations)
}

// ============================================================================
// ANSI Color Support
// ============================================================================

/**
 * ANSI escape codes for terminal coloring.
 *
 * Used by `say.error`, `say.warn`, `say.note` when [KSRuntime.colorOutput] is true.
 */
object ANSI {
    const val RESET = "\u001B[0m"
    const val BOLD = "\u001B[1m"
    const val RED = "\u001B[31m"
    const val GREEN = "\u001B[32m"
    const val YELLOW = "\u001B[33m"
    const val ORANGE = "\u001B[38;5;208m"
    const val BLUE = "\u001B[34m"
    const val MAGENTA = "\u001B[35m"
    const val CYAN = "\u001B[36m"
    const val WHITE = "\u001B[37m"
    const val BRIGHT_YELLOW = "\u001B[93m"  // Orange-ish for warnings
    const val BRIGHT_RED = "\u001B[91m"

    /**
     * Wrap text in color codes if color is enabled.
     */
    fun color(text: String, colorCode: String, enabled: Boolean): String {
        return if (enabled) "$colorCode$text$RESET" else text
    }

    fun red(text: String, enabled: Boolean = true) = color(text, RED, enabled)
    fun green(text: String, enabled: Boolean = true) = color(text, GREEN, enabled)
    fun yellow(text: String, enabled: Boolean = true) = color(text, YELLOW, enabled)
    fun blue(text: String, enabled: Boolean = true) = color(text, BLUE, enabled)
    fun bold(text: String, enabled: Boolean = true) = color(text, BOLD, enabled)
    fun warn(text: String, enabled: Boolean = true) = color(text, ORANGE, enabled)
    fun error(text: String, enabled: Boolean = true) = color(text, RED, enabled)
}

// ============================================================================
// Standard Library Marker
// ============================================================================

/**
 * Marker annotation for KS standard library functions.
 *
 * Functions and types marked with this annotation are available in all KS
 * environments, regardless of the [KSRuntime.hostLang] setting.
 *
 * The Compiler can use this annotation to verify that portable code only
 * uses standard library features.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class KSStandardLibrary(
    /**
     * Brief description of the standard library feature.
     */
    val description: String = ""
)

/**
 * Marker annotation for host-language-specific features.
 *
 * Functions and types marked with this annotation are only available when
 * [KSRuntime.hostLang] is `true`.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class KSHostSpecific(
    /**
     * The host language this feature is specific to.
     */
    val host: String = "Kotlin"
)