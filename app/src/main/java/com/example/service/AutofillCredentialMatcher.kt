package com.example.service

import com.example.domain.models.VaultEntry

/**
 * Decides which vault entries autofill may offer for a fill request (audit F3).
 *
 * - Web content: only when the requesting app is a verified browser (the caller checks it with [BrowserVerifier]:
 *   listed in Google's privileged-apps list AND signed with a listed certificate) and reported a webDomain. An
 *   entry matches on the exact host (200) or on the same registrable domain per the Public Suffix List (150).
 *   On an http:// page (webScheme known) only entries saved with an explicit http:// URL match.
 *   Any other app's webDomain is ignored: an app can put any domain into its view structure.
 * - Native apps: entries the user linked to the requesting package (picked once through "Search VaultPass…",
 *   stored locally in autofill_app_links) and entries whose website is exactly `androidapp://<requesting package>`.
 *   Browsers are never linked, and links stored for a browser package are ignored. An app that is not verified
 *   but uses a known browser's package name ([BROWSER_PACKAGE_NAMES]) gets nothing.
 *
 * Titles, app labels and package-name similarity are never used: they let lookalike apps and phishing pages
 * receive credentials.
 */
object AutofillCredentialMatcher {

    const val SCORE_EXACT_HOST = 200
    const val SCORE_SAME_SITE = 150
    const val SCORE_LINKED_APP = 200

    /**
     * Package names of browsers, used only to refuse app links and to give an unverified app using one of these
     * names nothing. A name never makes a webDomain trusted: that needs [BrowserVerifier] (signing certificate).
     * Kiwi, Cromite, Mull and Tor Browser are kept here although they are not in Google's privileged list (so
     * their webDomain is not trusted any more).
     */
    val BROWSER_PACKAGE_NAMES: Set<String> = setOf(
        // Chrome (stable, beta, dev, canary)
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        // Brave
        "com.brave.browser",
        // Firefox (release, beta, Focus, Nightly)
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus", "org.mozilla.fenix",
        // Microsoft Edge
        "com.microsoft.emmx",
        // Samsung Internet
        "com.sec.android.app.sbrowser",
        // DuckDuckGo
        "com.duckduckgo.mobile.android",
        // Opera, Opera Mini
        "com.opera.browser", "com.opera.mini.native",
        // Vivaldi
        "com.vivaldi.browser",
        // Kiwi
        "com.kiwibrowser.browser",
        // Cromite
        "org.cromite.cromite",
        // Mull
        "us.spotco.fennec_dos",
        // Tor Browser
        "org.torproject.torbrowser"
    )

    private const val ANDROID_APP_SCHEME = "androidapp://"
    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val ASCII_HOST = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)*$")

    data class ScoredEntry(
        val entry: VaultEntry,
        val score: Int,
        val reason: String
    )

    /** What a fill request is for, after deciding whether its webDomain can be trusted. */
    sealed class Target {
        /** [scheme]: the page's scheme when the browser reported it ("http", "https"), else null. */
        data class Web(val host: String, val scheme: String? = null) : Target()
        /** [linkedSyncIds]: entries linked to this app; ids without an entry (orphans) simply match nothing. */
        data class App(val packageName: String, val linkedSyncIds: Set<String> = emptySet()) : Target()
        object None : Target()
    }

    fun isBrowserPackageName(packageName: String?): Boolean = packageName != null && packageName in BROWSER_PACKAGE_NAMES

    /** Whether a picked entry may be linked to [packageName]: any native app, never a browser. */
    fun canLinkPackage(packageName: String?): Boolean = !packageName.isNullOrBlank() && !isBrowserPackageName(packageName)

    /**
     * [verifiedBrowser]: [requestedPackage] passed [BrowserVerifier.isVerifiedBrowser]. [webScheme]: the scheme
     * of the login field's frame, when known.
     */
    fun resolveTarget(
        requestedPackage: String?,
        requestedWebDomain: String?,
        verifiedBrowser: Boolean,
        webScheme: String? = null
    ): Target {
        if (requestedPackage.isNullOrBlank()) return Target.None
        if (verifiedBrowser) {
            // A browser without a usable webDomain (its own UI, or an unidentifiable page) gets nothing.
            return extractHost(requestedWebDomain)?.let { Target.Web(it, webScheme?.trim()?.lowercase()) } ?: Target.None
        }
        // An unverified app using a browser's package name (e.g. a sideloaded lookalike) gets nothing.
        if (isBrowserPackageName(requestedPackage)) return Target.None
        // Not a verified browser: its webDomain is ignored and the request is handled as a native app.
        return Target.App(requestedPackage)
    }

    /** Whether [website] was saved with an explicit `http://` scheme. */
    fun hasExplicitHttpScheme(website: String?): Boolean =
        website?.trim()?.startsWith("http://", ignoreCase = true) == true

    /**
     * Normalised host of a URL, bare domain or webDomain: IDN to ASCII, lowercase, no trailing dot, no port,
     * no user info. Null for non-web schemes (e.g. `androidapp://`) and anything that is not a valid host.
     */
    fun extractHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        val scheme = SCHEME_PREFIX.find(trimmed)?.value?.lowercase()
        if (scheme != null && scheme != "http://" && scheme != "https://") return null
        val withoutScheme = if (scheme != null) trimmed.substring(scheme.length) else trimmed

        // Authority = everything up to the first path/query/fragment delimiter, without user info and port.
        var authority = withoutScheme.split('/', '?', '#', '\\').first().substringAfterLast('@')
        authority = if (authority.startsWith("[")) {
            authority.substringBefore(']') + "]"
        } else {
            authority.substringBefore(':')
        }
        val host = PublicSuffixList.normalizeHost(authority) ?: return null
        val valid = (host.startsWith("[") && host.endsWith("]") && host.length > 2) || ASCII_HOST.matches(host)
        return if (valid) host else null
    }

    /** `www.example.com` and `example.com` count as the same host (but `www.ck` stays `www.ck`). */
    private fun sameHostKey(host: String): String {
        if (host.startsWith("www.") && host.indexOf('.', startIndex = 4) > 0) return host.substring(4)
        return host
    }

    /** `androidapp://<package>`: the scheme is case-insensitive, the package name must match exactly. */
    fun linkedAppPackage(website: String?): String? {
        val trimmed = website?.trim() ?: return null
        if (!trimmed.startsWith(ANDROID_APP_SCHEME, ignoreCase = true)) return null
        return trimmed.substring(ANDROID_APP_SCHEME.length).takeIf { it.isNotEmpty() }
    }

    fun calculateMatchScoreWithReason(
        entry: VaultEntry,
        target: Target,
        publicSuffixList: PublicSuffixList?
    ): Pair<Int, String> {
        if (entry.isDecryptionFailed) return Pair(0, "Decryption failed")

        return when (target) {
            is Target.Web -> {
                val entryHost = extractHost(entry.website) ?: return Pair(0, "No match")
                // An http:// page only gets entries the user saved for http:// (https or no scheme: not offered).
                if (target.scheme == "http" && !hasExplicitHttpScheme(entry.website)) {
                    return Pair(0, "Page is http, entry is not saved for http")
                }
                if (sameHostKey(entryHost) == sameHostKey(target.host)) {
                    return Pair(SCORE_EXACT_HOST, "Exact host match (Score $SCORE_EXACT_HOST)")
                }
                val requestSite = publicSuffixList?.registrableDomain(target.host)
                val entrySite = publicSuffixList?.registrableDomain(entryHost)
                if (requestSite != null && requestSite == entrySite) {
                    Pair(SCORE_SAME_SITE, "Same registrable domain $requestSite (Score $SCORE_SAME_SITE)")
                } else {
                    Pair(0, "No match")
                }
            }
            // Native apps: explicit associations only (androidapp:// website or a stored link), nothing fuzzy.
            is Target.App -> when {
                linkedAppPackage(entry.website) == target.packageName ->
                    Pair(SCORE_LINKED_APP, "Linked Android app (Score $SCORE_LINKED_APP)")
                entry.syncId.isNotEmpty() && entry.syncId in target.linkedSyncIds ->
                    Pair(SCORE_LINKED_APP, "Linked to this app by the user (Score $SCORE_LINKED_APP)")
                else -> Pair(0, "No match")
            }
            Target.None -> Pair(0, "No match")
        }
    }

    fun calculateMatchScore(
        entry: VaultEntry,
        requestedPackage: String?,
        requestedWebDomain: String?,
        publicSuffixList: PublicSuffixList?,
        verifiedBrowser: Boolean,
        webScheme: String? = null
    ): Int {
        val target = resolveTarget(requestedPackage, requestedWebDomain, verifiedBrowser, webScheme)
        return calculateMatchScoreWithReason(entry, target, publicSuffixList).first
    }

    /**
     * [linkedSyncIds]: the entries linked to [requestedPackage]. Only used for native-app requests; ignored for
     * browsers (web requests match on the domain only). [verifiedBrowser] and [webScheme]: see [resolveTarget].
     */
    fun matchEntries(
        entries: List<VaultEntry>,
        requestedPackage: String?,
        requestedWebDomain: String?,
        publicSuffixList: PublicSuffixList?,
        verifiedBrowser: Boolean,
        linkedSyncIds: Set<String> = emptySet(),
        webScheme: String? = null
    ): List<ScoredEntry> {
        val target = when (val resolved = resolveTarget(requestedPackage, requestedWebDomain, verifiedBrowser, webScheme)) {
            is Target.App -> resolved.copy(linkedSyncIds = linkedSyncIds)
            else -> resolved
        }
        if (target == Target.None) return emptyList()
        val matches = mutableListOf<ScoredEntry>()
        for (entry in entries) {
            val (score, reason) = calculateMatchScoreWithReason(entry, target, publicSuffixList)
            if (score > 0) {
                matches.add(ScoredEntry(entry, score, reason))
            }
        }
        return matches.sortedByDescending { it.score }
    }
}
