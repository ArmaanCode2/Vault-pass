package com.example.update

/**
 * Numeric version comparison for release tags ("v2.10.0" or "2.10.0") against BuildConfig.VERSION_NAME.
 * Components are compared as numbers (2.10.0 > 2.9.0); missing components count as 0 (2.7 == 2.7.0).
 * Anything else (suffixes like "-beta", empty parts, letters) is unparseable and never counts as newer.
 */
object AppVersion {
    private const val MAX_COMPONENTS = 6
    private val COMPONENT = Regex("[0-9]{1,9}")

    /** The numeric components, or null when [raw] is not a plain dotted version. */
    fun parse(raw: String?): List<Int>? {
        var text = raw?.trim() ?: return null
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1)
        if (text.isEmpty()) return null
        val parts = text.split('.')
        if (parts.size > MAX_COMPONENTS) return null
        if (parts.any { !COMPONENT.matches(it) }) return null
        return parts.map { it.toInt() }
    }

    /** "v2.7.0" -> "2.7.0"; null when unparseable. */
    fun normalize(raw: String?): String? = parse(raw)?.joinToString(".")

    /** Sign of [a] - [b], or null when either side is unparseable. */
    fun compare(a: String?, b: String?): Int? {
        val left = parse(a) ?: return null
        val right = parse(b) ?: return null
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    /** True only when both parse and [remote] is strictly newer than [current]. */
    fun isNewer(remote: String?, current: String?): Boolean = (compare(remote, current) ?: 0) > 0
}
