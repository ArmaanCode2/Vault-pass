package com.example

import com.example.service.AutofillCredentialMatcher
import com.example.domain.models.VaultEntry
import org.junit.Assert.*
import org.junit.Test

class AutofillMatcherTest {

    private val psl = TestPublicSuffixList.list
    private val browser = "com.brave.browser"

    /** Stands for a browser that passed BrowserVerifier (signing certificate checked). */
    private fun browserVerified(pkg: String) = pkg == browser

    @Test
    fun `exact domain match scores 200`() {
        val entry = VaultEntry(title = "Gmail", website = "https://mail.google.com")
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry, browser, "mail.google.com", psl, verifiedBrowser = browserVerified(browser)
        )
        assertEquals(200, score)
    }

    @Test
    fun `without a requesting package nothing matches`() {
        val entry = VaultEntry(title = "Gmail", website = "https://mail.google.com")
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry, null, "mail.google.com", psl, verifiedBrowser = false
        )
        assertEquals(0, score)
    }

    @Test
    fun `subdomain match scores 150`() {
        val entry = VaultEntry(title = "Google", website = "https://google.com")
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry, browser, "accounts.google.com", psl, verifiedBrowser = browserVerified(browser)
        )
        assertEquals(150, score)
    }

    @Test
    fun `no match scores 0`() {
        val entry = VaultEntry(title = "Gmail", website = "https://gmail.com")
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry, browser, "facebook.com", psl, verifiedBrowser = browserVerified(browser)
        )
        assertEquals(0, score)
    }

    @Test
    fun `www host matches bare host`() {
        val entry = VaultEntry(title = "Gmail", website = "https://www.gmail.com")
        assertEquals(200, AutofillCredentialMatcher.calculateMatchScore(entry, browser, "gmail.com", psl, verifiedBrowser = true))
    }

    @Test
    fun `extractHost handles bare domain`() {
        assertEquals("gmail.com", AutofillCredentialMatcher.extractHost("gmail.com"))
    }

    @Test
    fun `decryption-failed entry scores 0`() {
        val entry = VaultEntry(title = "Failed", website = "https://anything.com", isDecryptionFailed = true)
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry, browser, "anything.com", psl, verifiedBrowser = browserVerified(browser)
        )
        assertEquals(0, score)
    }
}
