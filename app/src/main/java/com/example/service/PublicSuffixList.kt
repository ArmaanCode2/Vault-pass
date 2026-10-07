package com.example.service

import android.content.Context
import java.io.InputStream
import java.net.IDN

/**
 * The Mozilla Public Suffix List (bundled as assets/public_suffix_list.dat, MPL-2.0, see
 * assets/licenses/public_suffix_list_NOTICE.txt). Used by autofill to decide whether two hosts belong to the
 * same site: `a.github.io` and `b.github.io` do not, `mail.google.com` and `google.com` do.
 *
 * Both the ICANN and the PRIVATE sections are used. Rules and hosts are compared in their ASCII (punycode)
 * form, lowercase, without a trailing dot.
 */
class PublicSuffixList private constructor(
    private val rules: Set<String>,
    /** `*.kawasaki.jp` is stored as `kawasaki.jp`. */
    private val wildcardRules: Set<String>,
    /** `!city.kawasaki.jp` is stored as `city.kawasaki.jp`. */
    private val exceptionRules: Set<String>,
    val icannRuleCount: Int,
    val privateRuleCount: Int
) {

    /**
     * The registrable domain ("eTLD+1") of [host], in ASCII form: `a.b.example.co.uk` -> `example.co.uk`.
     * Returns null for public suffixes themselves (`co.uk`, `github.io`), IP literals, and malformed hosts.
     * Hosts under a TLD that is not on the list fall back to the implicit `*` rule (`a.b.example` -> `b.example`).
     */
    fun registrableDomain(host: String?): String? {
        val normalized = normalizeHost(host) ?: return null
        if (isIpLiteral(normalized)) return null
        val labels = normalized.split('.')
        if (labels.any { it.isEmpty() }) return null

        val suffixLabelCount = publicSuffixLabelCount(labels)
        if (labels.size <= suffixLabelCount) return null
        return labels.subList(labels.size - suffixLabelCount - 1, labels.size).joinToString(".")
    }

    /** Number of labels of the public suffix of [labels], following the PSL algorithm. */
    private fun publicSuffixLabelCount(labels: List<String>): Int {
        // Exception rules win over every other rule; the public suffix is the exception minus its first label.
        for (i in labels.indices) {
            if (labels.subList(i, labels.size).joinToString(".") in exceptionRules) {
                return labels.size - i - 1
            }
        }
        var longest = 1 // implicit "*" rule
        for (i in labels.indices) {
            val suffix = labels.subList(i, labels.size).joinToString(".")
            val length = labels.size - i
            if (length > longest && suffix in rules) longest = length
            // "*.suffix" matches one more label in front of the suffix.
            if (i > 0 && length + 1 > longest && suffix in wildcardRules) longest = length + 1
        }
        return longest
    }

    companion object {
        const val ASSET_NAME = "public_suffix_list.dat"

        @Volatile
        private var loaded: PublicSuffixList? = null

        /**
         * The bundled list, parsed once on first use. Reads an asset, so it must be called off the main thread
         * (the autofill fill path runs on a background dispatcher).
         */
        fun get(context: Context): PublicSuffixList {
            loaded?.let { return it }
            return synchronized(this) {
                loaded ?: context.applicationContext.assets.open(ASSET_NAME).use { parse(it) }.also { loaded = it }
            }
        }

        /** Parses a list in the publicsuffix.org format (UTF-8). */
        fun parse(input: InputStream): PublicSuffixList {
            val rules = HashSet<String>()
            val wildcards = HashSet<String>()
            val exceptions = HashSet<String>()
            var icann = 0
            var private = 0
            var section = Section.NONE

            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (rawLine in lines) {
                    val line = rawLine.trim()
                    if (line.isEmpty()) continue
                    if (line.startsWith("//")) {
                        section = when {
                            line.contains("===BEGIN ICANN DOMAINS===") -> Section.ICANN
                            line.contains("===BEGIN PRIVATE DOMAINS===") -> Section.PRIVATE
                            line.contains("===END ICANN DOMAINS===") || line.contains("===END PRIVATE DOMAINS===") -> Section.NONE
                            else -> section
                        }
                        continue
                    }
                    // A rule is the first whitespace-delimited token of the line.
                    val rule = line.split(' ', '\t').first()
                    val added = when {
                        rule.startsWith("!") -> normalizeHost(rule.substring(1))?.let { exceptions.add(it) }
                        rule.startsWith("*.") -> normalizeHost(rule.substring(2))?.let { wildcards.add(it) }
                        else -> normalizeHost(rule)?.let { rules.add(it) }
                    }
                    if (added == true) {
                        when (section) {
                            Section.ICANN -> icann++
                            Section.PRIVATE -> private++
                            Section.NONE -> Unit
                        }
                    }
                }
            }
            return PublicSuffixList(rules, wildcards, exceptions, icann, private)
        }

        /**
         * Lowercase ASCII form of a host or rule: IDN labels converted to punycode where [IDN.toASCII] accepts
         * them, trailing dot removed. Null for blank input or a leading dot (empty first label).
         */
        fun normalizeHost(host: String?): String? {
            var value = host?.trim() ?: return null
            if (value.endsWith(".")) value = value.dropLast(1)
            if (value.isEmpty() || value.startsWith(".")) return null
            val ascii = try {
                IDN.toASCII(value, IDN.ALLOW_UNASSIGNED)
            } catch (e: IllegalArgumentException) {
                value
            }
            return ascii.lowercase().takeIf { it.isNotEmpty() }
        }

        /** IPv4 dotted quads, IPv6 (with or without brackets) and other all-numeric last labels have no site. */
        fun isIpLiteral(host: String): Boolean =
            host.contains(':') || host.startsWith("[") || host.substringAfterLast('.').all { it.isDigit() }

        private enum class Section { NONE, ICANN, PRIVATE }
    }
}
