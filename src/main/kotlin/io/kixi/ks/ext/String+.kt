@file:Suppress("unused")

package io.kixi.ks.ext

/**
 * Returns a [Regex] compiled from this string's content.
 *
 * This extension provides a concise way to create regex patterns from strings
 * in Ki Script:
 *
 * ```ks
 * let r = "\d{3}-\d{2}-\d{4}".rex
 * say r.matches("123-45-6789")   // true
 * ```
 *
 * Raw strings are particularly useful for regex patterns since they don't
 * require double-escaping:
 *
 * ```ks
 * let r = `\d{3}-\d{2}-\d{4}`.rex
 * ```
 */
val String.rex: Regex get() = Regex(this)