package io.kixi.ks.ext

import io.kixi.Range
import io.kixi.Range.Bound

/**
 * Extensions for [Range] that provide discrete enumeration of values.
 *
 * Supports ranges over discrete, countable types where a natural "step of 1"
 * exists: [Int], [Long], and [Char]. Reversed ranges (e.g. `5..1`) produce
 * descending sequences. All four [Bound] modes are respected.
 *
 * ```ks
 * (1..5).toList()          // [1, 2, 3, 4, 5]
 * (1..<5).toList()         // [1, 2, 3, 4]
 * (1<..<5).toList()        // [2, 3, 4]
 * (5..1).toList()          // [5, 4, 3, 2, 1]
 * ('a'..'f').toList()      // [a, b, c, d, e, f]
 * (0..10).toList(step=2)   // [0, 2, 4, 6, 8, 10]
 * ```
 *
 * Open ranges (`_..5`, `1.._`) cannot be enumerated and will throw
 * [IllegalArgumentException].
 *
 * @see Range
 */

// ============================================================================
// toList — enumerate all values in a closed, discrete range
// ============================================================================

/**
 * Returns a list of all discrete values in this range.
 *
 * Dispatches to the appropriate typed helper based on the runtime type of
 * the range's endpoints. Supports [Int], [Long], and [Char].
 *
 * @param step The increment between successive values (default: 1).
 *             Must be positive — direction is determined automatically
 *             from the range.
 * @return A list of values from start to end, respecting bound exclusivity
 *         and direction.
 * @throws IllegalArgumentException if the range is open or the element type
 *         is not discrete
 * @throws IllegalArgumentException if [step] is less than 1
 */
fun Range<*>.toList(step: Int = 1): List<Any> {
    require(isClosed) { "Cannot enumerate an open range: $this" }
    require(step >= 1) { "Step must be >= 1, got $step" }

    return when (start) {
        is Int    -> intToList(start as Int, end as Int, bound, step)
        is Long   -> longToList(start as Long, end as Long, bound, step.toLong())
        is Char   -> charToList(start as Char, end as Char, bound, step)
        else -> throw IllegalArgumentException(
            "Cannot enumerate Range<${start!!::class.simpleName}>: " +
                    "only Int, Long, and Char ranges are discrete"
        )
    }
}

/**
 * Returns a lazy sequence of all discrete values in this range.
 *
 * Identical semantics to [toList] but avoids materializing the entire
 * collection up front — useful for large ranges or early termination.
 *
 * ```ks
 * (1..1_000_000).asSequence().take(5)   // [1, 2, 3, 4, 5]
 * ```
 *
 * @param step The increment between successive values (default: 1).
 * @return A sequence of values from start to end.
 * @throws IllegalArgumentException if the range is open or non-discrete
 */
fun Range<*>.asSequence(step: Int = 1): Sequence<Any> {
    require(isClosed) { "Cannot enumerate an open range: $this" }
    require(step >= 1) { "Step must be >= 1, got $step" }

    return when (start) {
        is Int    -> intSequence(start as Int, end as Int, bound, step)
        is Long   -> longSequence(start as Long, end as Long, bound, step.toLong())
        is Char   -> charSequence(start as Char, end as Char, bound, step)
        else -> throw IllegalArgumentException(
            "Cannot enumerate Range<${start!!::class.simpleName}>: " +
                    "only Int, Long, and Char ranges are discrete"
        )
    }
}

/**
 * The number of discrete values in this range.
 *
 * Computes the count without materializing the full list. Returns 0 if
 * the effective range is empty (e.g. `5<..<6` has no integers between
 * 5 and 6 exclusive).
 *
 * @param step The step size (default: 1).
 * @return The number of values that [toList] would produce.
 * @throws IllegalArgumentException if the range is open or non-discrete
 */
fun Range<*>.count(step: Int = 1): Int {
    require(isClosed) { "Cannot count an open range: $this" }
    require(step >= 1) { "Step must be >= 1, got $step" }

    return when (start) {
        is Int -> {
            val s = start as Int; val e = end as Int
            val (first, last) = intBounds(s, e, bound)
            discreteCount(first.toLong(), last.toLong(), s <= e, step.toLong())
        }
        is Long -> {
            val s = start as Long; val e = end as Long
            val (first, last) = longBounds(s, e, bound)
            discreteCount(first, last, s <= e, step.toLong())
        }
        is Char -> {
            val s = start as Char; val e = end as Char
            val (first, last) = charBounds(s, e, bound)
            discreteCount(first.code.toLong(), last.code.toLong(), s <= e, step.toLong())
        }
        else -> throw IllegalArgumentException(
            "Cannot count Range<${start!!::class.simpleName}>"
        )
    }
}

// ============================================================================
// Int helpers
// ============================================================================

/**
 * Resolves the effective first and last values for an Int range after
 * applying exclusivity adjustments.
 *
 * For a forward range `2<..5` the effective bounds are `(3, 5)`.
 * For a reversed range `5..<2` the effective bounds are `(5, 3)`.
 */
private fun intBounds(start: Int, end: Int, bound: Bound): Pair<Int, Int> {
    val forward = start <= end
    val excludeStart = bound == Bound.ExclusiveStart || bound == Bound.Exclusive
    val excludeEnd = bound == Bound.ExclusiveEnd || bound == Bound.Exclusive

    return if (forward) {
        val first = if (excludeStart) start + 1 else start
        val last  = if (excludeEnd)   end - 1   else end
        first to last
    } else {
        val first = if (excludeStart) start - 1 else start
        val last  = if (excludeEnd)   end + 1   else end
        first to last
    }
}

private fun intToList(start: Int, end: Int, bound: Bound, step: Int): List<Any> =
    intSequence(start, end, bound, step).toList()

private fun intSequence(start: Int, end: Int, bound: Bound, step: Int): Sequence<Any> {
    val (first, last) = intBounds(start, end, bound)
    val forward = start <= end

    return sequence {
        if (forward) {
            var v = first
            while (v <= last) { yield(v); v += step }
        } else {
            var v = first
            while (v >= last) { yield(v); v -= step }
        }
    }
}

// ============================================================================
// Long helpers
// ============================================================================

private fun longBounds(start: Long, end: Long, bound: Bound): Pair<Long, Long> {
    val forward = start <= end
    val excludeStart = bound == Bound.ExclusiveStart || bound == Bound.Exclusive
    val excludeEnd = bound == Bound.ExclusiveEnd || bound == Bound.Exclusive

    return if (forward) {
        val first = if (excludeStart) start + 1 else start
        val last  = if (excludeEnd)   end - 1   else end
        first to last
    } else {
        val first = if (excludeStart) start - 1 else start
        val last  = if (excludeEnd)   end + 1   else end
        first to last
    }
}

private fun longToList(start: Long, end: Long, bound: Bound, step: Long): List<Any> =
    longSequence(start, end, bound, step).toList()

private fun longSequence(start: Long, end: Long, bound: Bound, step: Long): Sequence<Any> {
    val (first, last) = longBounds(start, end, bound)
    val forward = start <= end

    return sequence {
        if (forward) {
            var v = first
            while (v <= last) { yield(v); v += step }
        } else {
            var v = first
            while (v >= last) { yield(v); v -= step }
        }
    }
}

// ============================================================================
// Char helpers
// ============================================================================

private fun charBounds(start: Char, end: Char, bound: Bound): Pair<Char, Char> {
    val forward = start <= end
    val excludeStart = bound == Bound.ExclusiveStart || bound == Bound.Exclusive
    val excludeEnd = bound == Bound.ExclusiveEnd || bound == Bound.Exclusive

    return if (forward) {
        val first = if (excludeStart) start + 1 else start
        val last  = if (excludeEnd)   end - 1   else end
        first to last
    } else {
        val first = if (excludeStart) start - 1 else start
        val last  = if (excludeEnd)   end + 1   else end
        first to last
    }
}

private fun charToList(start: Char, end: Char, bound: Bound, step: Int): List<Any> =
    charSequence(start, end, bound, step).toList()

private fun charSequence(start: Char, end: Char, bound: Bound, step: Int): Sequence<Any> {
    val (first, last) = charBounds(start, end, bound)
    val forward = start <= end

    return sequence {
        if (forward) {
            var v = first
            while (v <= last) { yield(v); v += step }
        } else {
            var v = first
            while (v >= last) { yield(v); v -= step }
        }
    }
}

// ============================================================================
// Shared helpers
// ============================================================================

/**
 * Computes the number of discrete steps from [first] to [last] with the
 * given [step]. Returns 0 if the range is empty (bounds crossed over after
 * exclusivity adjustment).
 *
 * @param first Adjusted start bound (after exclusivity)
 * @param last  Adjusted end bound (after exclusivity)
 * @param forward Original range direction (true if start <= end)
 * @param step  Step size (positive)
 */
private fun discreteCount(first: Long, last: Long, forward: Boolean, step: Long): Int {
    return if (forward) {
        if (first > last) 0  // empty: bounds crossed after exclusivity adjustment
        else ((last - first) / step + 1).toInt()
    } else {
        if (first < last) 0  // empty: bounds crossed after exclusivity adjustment
        else ((first - last) / step + 1).toInt()
    }
}