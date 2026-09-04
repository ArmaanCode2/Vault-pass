package com.example.service

import com.example.domain.models.VaultEntry

object AutofillCredentialMatcher {

    data class ScoredEntry(
        val entry: VaultEntry,
        val score: Int,
        val reason: String
    )

    fun extractHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        val parsedUrl = if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            "https://$trimmed"
        } else {
            trimmed
        }
        return try {
            val uri = java.net.URI(parsedUrl)
            var host = uri.host ?: fallbackExtractHost(trimmed) ?: return null
            host = host.lowercase()
            if (host.startsWith("www.")) host = host.substring(4)
            if (host.isNotBlank()) host else null
        } catch (e: Exception) {
            fallbackExtractHost(trimmed)
        }
    }

    private fun fallbackExtractHost(raw: String): String? {
        return try {
            val cleaned = raw.replace(Regex("^[a-zA-Z]+://"), "")
                .split('/', '?', '#', ':')[0].trim()
            var host = cleaned.lowercase()
            if (host.startsWith("www.")) host = host.substring(4)
            if (host.isNotBlank()) host else null
        } catch (e: Exception) {
            null
        }
    }

    fun extractBaseDomain(url: String?): String? {
        val host = extractHost(url) ?: return null
        val parts = host.split(".")
        if (parts.size <= 2) {
            return host
        }
        val secondToLast = parts[parts.size - 2]
        val commonSecondLevelDomains = setOf("co", "com", "org", "net", "edu", "gov", "ac")
        if (parts.size >= 3 && commonSecondLevelDomains.contains(secondToLast) && parts.last().length == 2) {
            return parts.takeLast(3).joinToString(".")
        }
        return parts.takeLast(2).joinToString(".")
    }

    fun normalize(str: String): String = str.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun calculateMatchScoreWithReason(
        entry: VaultEntry,
        requestedPackage: String?,
        requestedDomain: String?,
        appLabel: String?
    ): Pair<Int, String> {
        if (entry.isDecryptionFailed) return Pair(0, "Decryption failed")

        val reqHost = extractHost(requestedDomain)
        val entryHost = extractHost(entry.website)

        // 1. Web Domain Match
        if (reqHost != null && entryHost != null) {
            if (reqHost == entryHost) {
                return Pair(200, "Exact domain match (Score 200)")
            }
            val reqBase = extractBaseDomain(requestedDomain)
            val entryBase = extractBaseDomain(entry.website)
            if (reqBase != null && reqBase == entryBase) {
                return Pair(150, "Subdomain match (Score 150)")
            }
            if (reqHost.endsWith(".$entryHost") || entryHost.endsWith(".$reqHost")) {
                return Pair(150, "Subdomain match (Score 150)")
            }
        }

        // 2. App Label Heuristics
        val normalizedLabel = appLabel?.let { normalize(it) } ?: ""
        val normalizedTitle = normalize(entry.title)

        if (normalizedTitle.isNotEmpty() && normalizedLabel.isNotEmpty()) {
            if (normalizedTitle == normalizedLabel) {
                return Pair(100, "Exact title match (Score 100)")
            } else if (normalizedTitle.contains(normalizedLabel)) {
                return Pair(85, "Title contains app label (Score 85)")
            } else if (normalizedLabel.contains(normalizedTitle)) {
                return Pair(80, "App label contains title (Score 80)")
            }
        }

        // 3. Package Name Fallback
        if (requestedPackage != null) {
            val isPackageMatch = entry.website.contains(requestedPackage, ignoreCase = true) ||
                    requestedPackage.contains(entry.title.replace(" ", ""), ignoreCase = true)
            if (isPackageMatch) {
                return Pair(60, "Package name fallback (Score 60)")
            }
        }

        return Pair(0, "No match")
    }

    fun calculateMatchScore(
        entry: VaultEntry,
        requestedPackage: String?,
        requestedDomain: String?,
        appLabel: String?
    ): Int {
        return calculateMatchScoreWithReason(entry, requestedPackage, requestedDomain, appLabel).first
    }

    fun matchEntries(
        entries: List<VaultEntry>,
        requestedPackage: String?,
        requestedDomain: String?,
        appLabel: String?
    ): List<ScoredEntry> {
        val matches = mutableListOf<ScoredEntry>()
        for (entry in entries) {
            val (score, reason) = calculateMatchScoreWithReason(entry, requestedPackage, requestedDomain, appLabel)
            if (score > 50) {
                matches.add(ScoredEntry(entry, score, reason))
            }
        }
        return matches.sortedByDescending { it.score }
    }
}
