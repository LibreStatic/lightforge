package com.librestatic.lightforge.feature.pdfstudio

private const val MAX_NAME = 80
private val counterSuffix = Regex(" \\d+$")

/**
 * Name for a duplicated project. [format] wraps a base name in the localized "(copy)" pattern.
 * A copy of a copy reuses the base instead of stacking suffixes, the base is shortened so the
 * suffix is never cut off by the 80-character limit, and a counter keeps the name unique among
 * [existing] names.
 */
internal fun duplicateProjectName(name: String, existing: Set<String>, format: (String) -> String): String {
    val marker = "\u0000"
    val pattern = format(marker)
    val prefix = pattern.substringBefore(marker)
    val suffix = pattern.substringAfter(marker)
    var base = name.trim()
    // Strip a trailing " (copy)" and the optional " N" counter left by an earlier duplicate.
    while (prefix.isEmpty() && suffix.isNotEmpty()) {
        val unnumbered = counterSuffix.replace(base, "")
        if (!unnumbered.endsWith(suffix)) break
        val stripped = unnumbered.removeSuffix(suffix).trimEnd()
        if (stripped.isEmpty()) break
        base = stripped
    }
    fun build(n: Int): String {
        val tail = if (n <= 1) "" else " $n"
        val room = (MAX_NAME - format("").length - tail.length).coerceAtLeast(1)
        return format(base.take(room)) + tail
    }
    var n = 1
    while (build(n) in existing) n++
    return build(n)
}
