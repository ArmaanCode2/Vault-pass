package com.example

import com.example.service.AutofillCredentialMatcher
import com.example.domain.models.VaultEntry
import org.junit.Assert.*
import org.junit.Test

class AutofillMatcherTest {

    @Test
    fun `exact domain match scores 200`() {
        val entry = VaultEntry(title = "Gmail", website = "https://mail.google.com")
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry, null, "mail.google.com", null
        )
        assertEquals(200, score)
    }

    @Test
    fun `subdomain match scores 150`() {
        val entry = VaultEntry(title = "Google", website = "https://google.com")
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry, null, "accounts.google.com", null
        )
        assertEquals(150, score)
    }

    @Test
    fun `no match scores 0`() {
        val entry = VaultEntry(title = "Gmail", website = "https://gmail.com")
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry, null, "facebook.com", null
        )
        assertEquals(0, score)
    }

    @Test
    fun `extractHost strips www prefix`() {
        assertEquals("gmail.com", AutofillCredentialMatcher.extractHost("https://www.gmail.com"))
    }

    @Test
    fun `extractHost handles bare domain`() {
        assertEquals("gmail.com", AutofillCredentialMatcher.extractHost("gmail.com"))
    }

    @Test
    fun `decryption-failed entry scores 0`() {
        val entry = VaultEntry(title = "Failed", isDecryptionFailed = true)
        val score = AutofillCredentialMatcher.calculateMatchScore(
            entry, null, "anything.com", null
        )
        assertEquals(0, score)
    }
}
