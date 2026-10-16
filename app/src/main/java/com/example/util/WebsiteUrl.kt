package com.example.util

import com.example.service.AutofillCredentialMatcher

/** Which stored websites the entry details screen may open in a browser. */
object WebsiteUrl {

    // A scheme as in RFC 3986. It also matches "example.com:" in "example.com:8443": see [PORT].
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    private val PORT = Regex("^\\d+([/?#].*)?$")

    /**
     * The http(s) URL to open for [website], or null when there is nothing safe to open: blank,
     * an `androidapp://` link (autofill), any scheme other than http and https (`javascript:`,
     * `file:`, `intent:`, `content:`…) or no host. A website without a scheme gets `https://`.
     * The scheme is lowercased: Android matches intent schemes case-sensitively.
     */
    fun browserUrl(website: String?): String? {
        val trimmed = website?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val scheme = SCHEME.find(trimmed)
        val url = if (scheme == null || PORT.matches(trimmed.substring(scheme.value.length))) {
            // No scheme: "example.com", or "example.com:8443/login" where the colon starts a port.
            "https://$trimmed"
        } else {
            val name = scheme.groupValues[1].lowercase()
            val rest = trimmed.substring(scheme.value.length)
            if ((name != "http" && name != "https") || !rest.startsWith("//")) return null
            "$name:$rest"
        }
        return url.takeIf { AutofillCredentialMatcher.extractHost(it) != null }
    }
}
