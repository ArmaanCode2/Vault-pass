package com.example

import com.example.domain.models.VaultEntry
import com.example.service.AutofillCredentialMatcher
import org.junit.Assert.*
import org.junit.Test

/**
 * F3: web matches only via verified browsers + Public Suffix List; native apps only via androidapp://.
 * "Verified" (signing certificate checked) is decided by BrowserVerifier, see PrivilegedBrowsersTest; here the
 * packages in [verifiedBrowsers] stand for browsers that passed that check.
 */
class AutofillCredentialMatcherTest {

    private val psl = TestPublicSuffixList.list
    private val chrome = "com.android.chrome"
    private val verifiedBrowsers = setOf(chrome, "org.mozilla.firefox", "com.sec.android.app.sbrowser")
    private fun verified(pkg: String?) = pkg in verifiedBrowsers

    private fun createEntry(
        id: Int,
        title: String,
        website: String,
        isDecryptionFailed: Boolean = false
    ): VaultEntry {
        return VaultEntry(
            id = id,
            title = title,
            username = "user$id",
            password = "password$id",
            website = website,
            notes = "",
            category = "General",
            tags = emptyList(),
            customFields = emptyList(),
            isFavorite = false,
            timestamp = System.currentTimeMillis(),
            isDecryptionFailed = isDecryptionFailed
        )
    }

    private fun score(entry: VaultEntry, requestedPackage: String?, webDomain: String?, webScheme: String? = null) =
        AutofillCredentialMatcher.calculateMatchScore(entry, requestedPackage, webDomain, psl, verified(requestedPackage), webScheme)

    private fun registrable(url: String?) = psl.registrableDomain(AutofillCredentialMatcher.extractHost(url))

    // --- Former base-domain list, now the Public Suffix List ---

    @Test
    fun registrableDomain_variousUrlFormats() {
        assertEquals("example.com", registrable("https://sub.example.com/login"))
        assertEquals("google.com", registrable("www.google.com"))
        assertEquals("google.com", registrable("https://mail.google.com/mail/u/0"))
        assertEquals("localhost", AutofillCredentialMatcher.extractHost("http://localhost:8080"))
        assertNull("single-label host has no registrable domain", registrable("http://localhost:8080"))
        assertEquals("bbc.co.uk", registrable("https://news.bbc.co.uk/world"))
        assertEquals("github.com", registrable("github.com"))
        assertNull("shared host suffix", registrable("https://github.io"))
        assertNull(registrable(""))
        assertNull(registrable(null))
    }

    @Test
    fun extractHost_dropsPortUserInfoTrailingDotAndNonWebSchemes() {
        assertEquals("example.com", AutofillCredentialMatcher.extractHost("HTTPS://Example.COM.:443/x?y#z"))
        assertEquals("evil.com", AutofillCredentialMatcher.extractHost("https://paypal.com@evil.com/login"))
        assertEquals("192.168.1.1", AutofillCredentialMatcher.extractHost("http://192.168.1.1:8080"))
        assertNull(AutofillCredentialMatcher.extractHost("androidapp://com.paypal.android"))
        assertNull(AutofillCredentialMatcher.extractHost("ftp://example.com"))
        assertNull(AutofillCredentialMatcher.extractHost("not a host"))
    }

    // --- Web requests from a trusted browser ---

    @Test
    fun calculateMatchScore_exactDomainMatchScoresHighest() {
        val entry = createEntry(1, "GitHub", "https://github.com/login")
        assertEquals(200, score(entry, chrome, "github.com"))
    }

    @Test
    fun calculateMatchScore_subdomainMatchScores150() {
        val entry = createEntry(1, "Slack", "https://slack.com")
        assertEquals(150, score(entry, chrome, "workspace.slack.com"))
    }

    @Test
    fun mailGoogleCom_matchesGoogleComEntry_150() {
        val entry = createEntry(1, "Google", "https://google.com")
        assertEquals(150, score(entry, "org.mozilla.firefox", "mail.google.com"))
    }

    @Test
    fun wwwPrefix_trailingDot_case_andIdn_countAsSameHost() {
        assertEquals(200, score(createEntry(1, "Ex", "https://www.example.com"), chrome, "example.com"))
        assertEquals(200, score(createEntry(2, "Ex", "example.com"), chrome, "WWW.Example.com."))
        assertEquals(200, score(createEntry(3, "Shishi", "https://食狮.com.cn/login"), chrome, "xn--85x722f.com.cn"))
    }

    @Test
    fun sharedHostingSuffixes_doNotMatchEachOther() {
        val entry = createEntry(1, "My page", "https://a.github.io")
        assertEquals(0, score(entry, chrome, "b.github.io"))
        assertEquals(200, score(entry, chrome, "a.github.io"))
        assertEquals(150, score(entry, chrome, "docs.a.github.io"))
        assertEquals(0, score(createEntry(2, "App", "https://mine.herokuapp.com"), chrome, "theirs.herokuapp.com"))
    }

    @Test
    fun exampleCoUk_subdomainsMatch_otherCoUkSitesDoNot() {
        val entry = createEntry(1, "Example UK", "https://www.example.co.uk")
        assertEquals(200, score(entry, chrome, "example.co.uk"))
        assertEquals(150, score(entry, chrome, "login.example.co.uk"))
        assertEquals(150, score(entry, chrome, "a.b.example.co.uk"))
        assertEquals(0, score(entry, chrome, "other.co.uk"))
        assertEquals(0, score(entry, chrome, "co.uk"))
    }

    @Test
    fun idnHomograph_doesNotMatchAsciiBrand() {
        val entry = createEntry(1, "PayPal", "https://paypal.com")
        val cyrillicA = "pаypal.com"
        assertEquals("xn--pypal-4ve.com", AutofillCredentialMatcher.extractHost(cyrillicA))
        assertEquals(0, score(entry, chrome, cyrillicA))
        assertEquals(0, score(entry, chrome, "xn--pypal-4ve.com"))
        assertEquals(0, score(entry, chrome, "www.xn--pypal-4ve.com"))
    }

    @Test
    fun browserOnEvilCom_doesNotGetEntryTitledLikeTheBrowser() {
        val entries = listOf(
            createEntry(1, "Chrome", ""),
            createEntry(2, "Android", ""),
            createEntry(3, "Samsung", "https://samsung.com"),
            createEntry(4, "com.android.chrome", "")
        )
        assertTrue(AutofillCredentialMatcher.matchEntries(entries, chrome, "evil.com", psl, verifiedBrowser = true).isEmpty())
        assertTrue(AutofillCredentialMatcher.matchEntries(entries, "com.sec.android.app.sbrowser", "evil.com", psl, verifiedBrowser = true).isEmpty())
    }

    @Test
    fun browserWithoutWebDomain_getsNothing_evenForAnAndroidAppEntry() {
        val entry = createEntry(1, "Chrome", "androidapp://com.android.chrome")
        assertEquals(0, score(entry, chrome, null))
    }

    @Test
    fun ipHosts_matchOnlyExactly() {
        val entry = createEntry(1, "Router", "http://192.168.1.1:8080")
        assertEquals(200, score(entry, chrome, "192.168.1.1"))
        assertEquals(0, score(entry, chrome, "192.168.1.2"))
    }

    @Test
    fun userInfoInEntryUrl_isNotTheHost() {
        val entry = createEntry(1, "Trap", "https://paypal.com@evil.com")
        assertEquals(0, score(entry, chrome, "paypal.com"))
    }

    @Test
    fun withoutPublicSuffixList_onlyExactHostsMatch() {
        val entry = createEntry(1, "Google", "https://google.com")
        assertEquals(200, AutofillCredentialMatcher.calculateMatchScore(entry, chrome, "google.com", null, verifiedBrowser = true))
        assertEquals(0, AutofillCredentialMatcher.calculateMatchScore(entry, chrome, "mail.google.com", null, verifiedBrowser = true))
    }

    // --- webDomain from apps that are not trusted browsers ---

    @Test
    fun nonBrowserApp_claimingWebDomain_getsNoWebMatch() {
        val entry = createEntry(1, "PayPal", "https://paypal.com")
        assertEquals(0, score(entry, "com.evil.paypal.clone", "paypal.com"))
        assertEquals(0, score(entry, "com.github.android", "paypal.com"))
        assertEquals(200, score(entry, chrome, "paypal.com"))
    }

    // --- H2: a browser's package name alone is never trusted ---

    @Test
    fun browserPackageName_withoutCertificateVerification_getsNoWebMatch() {
        val entry = createEntry(1, "PayPal", "https://www.paypal.com")
        for (name in AutofillCredentialMatcher.BROWSER_PACKAGE_NAMES + "com.example.unknownbrowser") {
            assertEquals(name, 0, AutofillCredentialMatcher.calculateMatchScore(entry, name, "paypal.com", psl, verifiedBrowser = false))
            assertEquals(name, 200, AutofillCredentialMatcher.calculateMatchScore(entry, name, "paypal.com", psl, verifiedBrowser = true))
        }
    }

    @Test
    fun unverifiedAppUsingABrowserName_getsNothing_notEvenAppEntriesOrLinks() {
        // e.g. a sideloaded app named like a discontinued browser that is not installed.
        val kiwi = "com.kiwibrowser.browser"
        val entries = listOf(
            createEntry(1, "PayPal", "https://paypal.com"),
            createEntry(2, "Kiwi", "androidapp://$kiwi")
        )
        assertTrue(AutofillCredentialMatcher.isBrowserPackageName(kiwi))
        assertTrue(AutofillCredentialMatcher.matchEntries(entries, kiwi, "paypal.com", psl, verifiedBrowser = false,
            linkedSyncIds = setOf("x")).isEmpty())
        assertEquals(AutofillCredentialMatcher.Target.None, AutofillCredentialMatcher.resolveTarget(chrome, "paypal.com", verifiedBrowser = false))
        assertEquals(AutofillCredentialMatcher.Target.None, AutofillCredentialMatcher.resolveTarget(null, "paypal.com", verifiedBrowser = true))
        assertFalse(AutofillCredentialMatcher.canLinkPackage(kiwi))
        assertFalse(AutofillCredentialMatcher.isBrowserPackageName("com.android.chrome.evil"))
        assertFalse(AutofillCredentialMatcher.isBrowserPackageName("COM.ANDROID.CHROME"))
        assertFalse(AutofillCredentialMatcher.isBrowserPackageName(null))
    }

    // --- M5: http pages ---

    @Test
    fun httpPage_onlyGetsEntriesSavedWithExplicitHttp() {
        val https = createEntry(1, "Router https", "https://router.example.com")
        val bare = createEntry(2, "Router bare", "router.example.com")
        val http = createEntry(3, "Router http", "http://router.example.com/admin")
        val httpSameSite = createEntry(4, "Example http", "HTTP://example.com")

        assertEquals(0, score(https, chrome, "router.example.com", "http"))
        assertEquals(0, score(bare, chrome, "router.example.com", "http"))
        assertEquals(200, score(http, chrome, "router.example.com", "http"))
        assertEquals(150, score(httpSameSite, chrome, "router.example.com", "HTTP"))

        val matches = AutofillCredentialMatcher.matchEntries(listOf(https, bare, http, httpSameSite), chrome,
            "router.example.com", psl, verifiedBrowser = true, webScheme = "http")
        assertEquals(listOf(3, 4), matches.map { it.entry.id })
    }

    @Test
    fun httpsOrUnknownScheme_matchesAsBefore() {
        val https = createEntry(1, "Site", "https://example.com")
        val bare = createEntry(2, "Site", "example.com")
        val http = createEntry(3, "Site", "http://example.com")
        for (scheme in listOf("https", null)) {
            assertEquals(200, score(https, chrome, "example.com", scheme))
            assertEquals(200, score(bare, chrome, "example.com", scheme))
            assertEquals(200, score(http, chrome, "example.com", scheme))
        }
    }

    // --- Native apps (former title / app label / package heuristics) ---

    @Test
    fun entryTitledLikeTheApp_noLongerMatches() {
        // Was: exact title == app label -> 100.
        val entry = createEntry(1, "Twitter", "")
        assertEquals(0, score(entry, "com.twitter.android", null))
    }

    @Test
    fun packageNameInWebsiteWithoutScheme_noLongerMatches() {
        // Was: package name fallback -> 60.
        assertEquals(0, score(createEntry(1, "Random Title", "com.spotify.music"), "com.spotify.music", null))
        // Was: requested package contains the title -> 60.
        assertEquals(0, score(createEntry(2, "Spotify", ""), "com.spotify.music", null))
    }

    @Test
    fun shortAppLabel_e_matchesNothing() {
        // Was: an app labelled "e" matched every title containing an "e" (85) via substring heuristics.
        val entries = listOf(
            createEntry(1, "e", ""),
            createEntry(2, "PayPal (e-mail login)", "https://paypal.com"),
            createEntry(3, "eBay", "https://ebay.com"),
            createEntry(4, "Netflix", "")
        )
        assertTrue(AutofillCredentialMatcher.matchEntries(entries, "com.e", null, psl, verifiedBrowser = false).isEmpty())
        assertTrue(AutofillCredentialMatcher.matchEntries(entries, "e", null, psl, verifiedBrowser = false).isEmpty())
    }

    @Test
    fun androidAppEntry_matchesExactPackage_schemeCaseInsensitiveOnly() {
        val pkg = "com.paypal.android.p2pmobile"
        assertEquals(200, score(createEntry(1, "PayPal", "androidapp://$pkg"), pkg, null))
        assertEquals(200, score(createEntry(2, "PayPal", "AndroidApp://$pkg"), pkg, null))
        assertEquals(200, score(createEntry(3, "PayPal", "  ANDROIDAPP://$pkg "), pkg, null))
        assertEquals(0, score(createEntry(4, "PayPal", "androidapp://${pkg.uppercase()}"), pkg, null))
        assertEquals(0, score(createEntry(5, "PayPal", "androidapp://$pkg.evil"), pkg, null))
        assertEquals(0, score(createEntry(6, "PayPal", "androidapp://com.paypal"), pkg, null))
        assertEquals(0, score(createEntry(7, "PayPal", "androidapp://$pkg"), "com.paypal", null))
        assertEquals(0, score(createEntry(8, "PayPal", "androidapp://"), pkg, null))
    }

    @Test
    fun androidAppEntry_isNotOfferedToWebPages() {
        val entry = createEntry(1, "PayPal", "androidapp://com.paypal.android.p2pmobile")
        assertEquals(0, score(entry, chrome, "com.paypal.android.p2pmobile"))
    }

    @Test
    fun nonBrowserApp_withWebDomain_isTreatedAsNativeApp() {
        val pkg = "com.example.shop"
        val linked = createEntry(1, "Shop", "androidapp://$pkg")
        val web = createEntry(2, "Shop web", "https://shop.example.com")
        val matches = AutofillCredentialMatcher.matchEntries(listOf(web, linked), pkg, "shop.example.com", psl, verifiedBrowser = false)
        assertEquals(listOf(1), matches.map { it.entry.id })
    }

    @Test
    fun matchEntries_ordersByRelevance_exactHostBeforeSameSite_heuristicsGone() {
        val domainEntry = createEntry(1, "Custom GitHub Name", "https://github.com")
        val siteEntry = createEntry(2, "GitHub Gist", "https://gist.github.com")
        val labelEntry = createEntry(3, "GitHub", "")
        val packageEntry = createEntry(4, "Different Name", "com.github.mobile")
        val unmatchingEntry = createEntry(5, "Netflix", "https://netflix.com")

        val matches = AutofillCredentialMatcher.matchEntries(
            entries = listOf(packageEntry, unmatchingEntry, siteEntry, labelEntry, domainEntry),
            requestedPackage = chrome,
            requestedWebDomain = "github.com",
            publicSuffixList = psl,
            verifiedBrowser = true
        )

        assertEquals(listOf(1, 2), matches.map { it.entry.id })
        assertEquals(listOf(200, 150), matches.map { it.score })
    }

    @Test
    fun calculateMatchScore_ignoresDecryptionFailedEntries() {
        val failedEntry = createEntry(1, "GitHub", "https://github.com", isDecryptionFailed = true)
        assertEquals(0, score(failedEntry, chrome, "github.com"))
        val failedApp = createEntry(2, "GitHub", "androidapp://com.github.android", isDecryptionFailed = true)
        assertEquals(0, score(failedApp, "com.github.android", null))
    }
}
