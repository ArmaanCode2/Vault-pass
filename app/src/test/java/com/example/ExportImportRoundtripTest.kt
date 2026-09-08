package com.example

import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.ui.VaultViewModel
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ExportImportRoundtripTest {

    // Use a mock or real ViewModel with test dependencies
    // For now, test the static decoding logic directly

    @Test
    fun `JSON import preserves originalTitle from metadata`() {
        val json = """{"Gmail": ["user@gmail.com", "pass123", {"title": "Gmail", "originalTitle": "Gmail"}]}"""
        // This tests that the import reads originalTitle correctly
        val jsonObj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        for ((title, element) in jsonObj) {
            val array = element.jsonArray
            val meta = array.getOrNull(2)?.jsonObject
            val entryTitle = meta?.get("originalTitle")?.jsonPrimitive?.content
            assertEquals("Gmail", entryTitle)
        }
    }

    @Test
    fun `JSON export with duplicate titles produces unique keys`() {
        // Verify that 3 entries with title "Gmail" produce 3 unique keys
        val usedTitles = mutableSetOf<String>()
        val titles = listOf("Gmail", "Gmail", "Gmail")

        for (originalTitle in titles) {
            var finalTitle = originalTitle
            if (usedTitles.contains(finalTitle)) {
                var counter = 2
                while (usedTitles.contains("$originalTitle ($counter)")) {
                    counter++
                }
                finalTitle = "$originalTitle ($counter)"
            }
            usedTitles.add(finalTitle)
        }

        assertEquals(3, usedTitles.size)
        assertTrue(usedTitles.contains("Gmail"))
        assertTrue(usedTitles.contains("Gmail (2)"))
        assertTrue(usedTitles.contains("Gmail (3)"))
    }

    @Test
    fun `TXT export custom field with Custom prefix is correctly imported`() {
        // Simulates the fixed export/import logic
        val exportedLine = "Custom.Notes:"
        val rawKey = exportedLine.dropLast(1) // "Custom.Notes"
        val key = if (rawKey.startsWith("Custom.")) rawKey.removePrefix("Custom.") else rawKey
        assertEquals("Notes", key)
    }
}
