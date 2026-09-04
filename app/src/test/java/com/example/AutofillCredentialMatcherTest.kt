package com.example

import com.example.domain.models.VaultEntry
import com.example.service.AutofillCredentialMatcher
import org.junit.Assert.*
import org.junit.Test

class AutofillCredentialMatcherTest {

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

    @Test
    fun extractBaseDomain_variousUrlFormats() {
        assertEquals("example.com", AutofillCredentialMatcher.extractBaseDomain("https://sub.example.com/login"))
        assertEquals("google.com", AutofillCredentialMatcher.extractBaseDomain("www.google.com"))
        assertEquals("google.com", AutofillCredentialMatcher.extractBaseDomain("https://mail.google.com/mail/u/0"))
        assertEquals("localhost", AutofillCredentialMatcher.extractBaseDomain("http://localhost:8080"))
        assertEquals("bbc.co.uk", AutofillCredentialMatcher.extractBaseDomain("https://news.bbc.co.uk/world"))
        assertEquals("github.com", AutofillCredentialMatcher.extractBaseDomain("github.com"))
        assertNull(AutofillCredentialMatcher.extractBaseDomain(""))
        assertNull(AutofillCredentialMatcher.extractBaseDomain(null))
    }

    @Test
    fun calculateMatchScore_exactDomainMatchScoresHighest() {
        val entry = createEntry(1, "GitHub", "https://github.com/login")
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry = entry,
            requestedPackage = "com.github.android",
            requestedDomain = "https://github.com",
            appLabel = "GitHub"
        )
        assertEquals(200, score)
    }

    @Test
    fun calculateMatchScore_subdomainMatchScores150() {
        val entry = createEntry(1, "Slack", "https://slack.com")
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry = entry,
            requestedPackage = "com.Slack",
            requestedDomain = "https://workspace.slack.com",
            appLabel = "Slack"
        )
        assertEquals(150, score)
    }

    @Test
    fun calculateMatchScore_exactAppLabelMatchScores100() {
        val entry = createEntry(1, "Twitter", "")
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry = entry,
            requestedPackage = "com.twitter.android",
            requestedDomain = null,
            appLabel = "Twitter"
        )
        assertEquals(100, score)
    }

    @Test
    fun calculateMatchScore_packageFallbackScores60() {
        val entry = createEntry(1, "Random Title", "com.spotify.music")
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry = entry,
            requestedPackage = "com.spotify.music",
            requestedDomain = null,
            appLabel = "Spotify"
        )
        // Title doesn't match app label, but package matches
        assertEquals(60, score)
    }

    @Test
    fun matchEntries_ordersByRelevance_domainOverLabelOverPackage() {
        val domainEntry = createEntry(1, "Custom GitHub Name", "https://github.com")
        val labelEntry = createEntry(2, "GitHub", "")
        val packageEntry = createEntry(3, "Different Name", "com.github.mobile")
        val unmatchingEntry = createEntry(4, "Netflix", "https://netflix.com")

        val matches = AutofillCredentialMatcher.matchEntries(
            entries = listOf(packageEntry, unmatchingEntry, labelEntry, domainEntry),
            requestedPackage = "com.github.mobile",
            requestedDomain = "https://github.com",
            appLabel = "GitHub"
        )

        assertEquals(3, matches.size)
        // 1st: domain match (score 200)
        assertEquals(domainEntry.id, matches[0].entry.id)
        assertEquals(200, matches[0].score)

        // 2nd: label match (score 100)
        assertEquals(labelEntry.id, matches[1].entry.id)
        assertEquals(100, matches[1].score)

        // 3rd: package match (score 60)
        assertEquals(packageEntry.id, matches[2].entry.id)
        assertEquals(60, matches[2].score)
    }

    @Test
    fun calculateMatchScore_ignoresDecryptionFailedEntries() {
        val failedEntry = createEntry(1, "GitHub", "https://github.com", isDecryptionFailed = true)
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry = failedEntry,
            requestedPackage = "com.github.android",
            requestedDomain = "https://github.com",
            appLabel = "GitHub"
        )
        assertEquals(0, score)
    }
}
