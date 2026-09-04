package com.example

import com.example.domain.models.VaultEntry
import com.example.domain.security.SecurityAnalyzer
import org.junit.Assert.*
import org.junit.Test

class SecurityAnalyzerTest {

    private fun createEntry(id: Int, title: String, password: String): VaultEntry {
        return VaultEntry(
            id = id,
            title = title,
            username = "user$id",
            password = password,
            website = "https://example$id.com",
            notes = "",
            category = "General",
            tags = emptyList(),
            customFields = emptyList(),
            isFavorite = false,
            timestamp = System.currentTimeMillis()
        )
    }

    @Test
    fun scorePassword_emptyPassword_returnsZero() {
        assertEquals(0, SecurityAnalyzer.scorePassword(""))
    }

    @Test
    fun scorePassword_weakPasswords_scoreLow() {
        val weakPasswords = listOf(
            "123456",
            "password",
            "password123",
            "qwerty",
            "admin",
            "abc123"
        )
        for (pwd in weakPasswords) {
            val score = SecurityAnalyzer.scorePassword(pwd)
            assertTrue("Password '$pwd' scored $score, expected <= 40", score <= 40)
        }
    }

    @Test
    fun scorePassword_strongPasswords_scoreHigh() {
        val strongPasswords = listOf(
            "V@ultPass_2026!#UltraSecure",
            "k8#mP\$9vL!2qXz@W7tY^4nB&",
            "Tr0ub4dor&3_CorrectHorseBatteryStaple!"
        )
        for (pwd in strongPasswords) {
            val score = SecurityAnalyzer.scorePassword(pwd)
            assertTrue("Password '$pwd' scored $score, expected >= 70", score >= 70)
        }
    }

    @Test
    fun analyzePasswords_identifiesWeakMediumAndStrong() {
        val entries = listOf(
            createEntry(1, "Weak Entry", "123456"),
            createEntry(2, "Medium Entry", "CorrectHorse99"),
            createEntry(3, "Strong Entry", "V@ultPass_2026!#UltraSecure")
        )

        val stats = SecurityAnalyzer.analyze(entries)
        assertEquals(3, stats.totalPasswords)
        assertEquals(1, stats.weakPasswords)
        assertTrue(stats.weakEntryIds.contains(1))
        assertTrue(stats.strongPasswords >= 1)
    }

    @Test
    fun analyzePasswords_identifiesReusedPasswords() {
        val sharedPass = "SharedPass123!@#"
        val entries = listOf(
            createEntry(1, "Service A", sharedPass),
            createEntry(2, "Service B", sharedPass),
            createEntry(3, "Service C", "UniquePass456!@#")
        )

        val stats = SecurityAnalyzer.analyze(entries)
        assertEquals(3, stats.totalPasswords)
        assertEquals(2, stats.reusedPasswords)
        assertTrue(stats.reusedEntryIds.contains(1))
        assertTrue(stats.reusedEntryIds.contains(2))
        assertFalse(stats.reusedEntryIds.contains(3))
    }

    @Test
    fun analyzePasswords_identifiesMissingPasswords() {
        val entries = listOf(
            createEntry(1, "Empty Entry 1", ""),
            createEntry(2, "Empty Entry 2", ""),
            createEntry(3, "Valid Entry", "ValidP@ssw0rd!123")
        )

        val stats = SecurityAnalyzer.analyze(entries)
        assertEquals(3, stats.totalPasswords)
        assertEquals(2, stats.missingPasswords)
        assertTrue(stats.missingEntryIds.contains(1))
        assertTrue(stats.missingEntryIds.contains(2))
        assertFalse(stats.missingEntryIds.contains(3))
    }
}
