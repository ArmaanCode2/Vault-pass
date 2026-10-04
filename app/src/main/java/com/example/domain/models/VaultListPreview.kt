package com.example.domain.models

/**
 * The second line shown under an entry's title in every list (dashboard, search results,
 * recycle bin, security center lists).
 *
 * Rule: the username if it isn't blank, else the host of the entry's website, else "".
 * The password and custom-field values are never used, so a list can never reveal a secret.
 */
object VaultListPreview {

    fun line(entry: VaultEntry): String {
        if (entry.username.isNotBlank()) return entry.username
        return websiteHost(entry.website) ?: ""
    }

    /**
     * Host part of a website as the user typed it: scheme, user info, port, path, query and
     * fragment are dropped, a leading "www." is removed and the result is lowercased.
     * Works without a scheme ("example.com/login"). Only the first non-blank line is used.
     * Returns null when nothing host-like is left (blank, or text with spaces in it).
     */
    fun websiteHost(website: String): String? {
        val firstLine = website.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null

        val schemeIndex = firstLine.indexOf("://")
        val withoutScheme = if (schemeIndex >= 0) firstLine.substring(schemeIndex + 3) else firstLine

        val authorityEnd = withoutScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd >= 0) withoutScheme.substring(0, authorityEnd) else withoutScheme
        val hostAndPort = authority.substringAfterLast('@')

        var host = if (hostAndPort.startsWith("[")) {
            // IPv6 literal: keep the brackets, drop the port.
            val close = hostAndPort.indexOf(']')
            if (close >= 0) hostAndPort.substring(0, close + 1) else hostAndPort
        } else {
            hostAndPort.substringBefore(':')
        }

        host = host.trim().trimEnd('.').lowercase()
        if (host.startsWith("www.")) host = host.substring(4)
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        return host
    }
}
