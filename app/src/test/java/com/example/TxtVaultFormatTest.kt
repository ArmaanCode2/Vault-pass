package com.example

import com.example.domain.exportimport.TxtVaultFormat
import com.example.domain.exportimport.TxtVaultFormat.Record
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F9: the TXT format is read by position. The fixture (src/test/resources/txt) is byte-for-byte the
 * same file VaultPass Desktop tests its parser with: both apps must read it identically.
 */
class TxtVaultFormatTest {

    private fun fixture(): String =
        javaClass.classLoader!!.getResource("txt/vaultpass_txt_fixture.txt")!!.readText(Charsets.UTF_8)

    private val bom = 0xFEFF.toChar().toString()

    @Test
    fun sharedFixture_readsEveryFieldAsWritten() {
        val parsed = TxtVaultFormat.parse(fixture())
        assertEquals(0, parsed.invalidCount)
        assertEquals(
            listOf(
                Record("Empty fields", username = "", password = ""),
                Record("Separator inside", username = "user---name", password = "a---b", website = "https://x.example/---/path"),
                Record("  Lead space", username = " spaced ", password = "  two  "),
                Record("PEM key", username = "deploy", password = "pw", notes = "-----BEGIN KEY-----\nabc\n-----END KEY-----"),
                Record(
                    "Notes with blanks", username = "u", password = "p", favorite = true,
                    notes = "line1\n\nline3\n", category = "Work", tags = listOf("a", "b c"),
                    customFields = listOf("PIN" to "4321", "Recovery codes" to "x y z")
                )
            ),
            parsed.records
        )
    }

    @Test
    fun emptyUsername_doesNotSwallowThePassword() {
        // The audit's case: the old parser read "Password:" as the username and lost the password.
        val parsed = TxtVaultFormat.parse("Title: A\n\nUsername:\n\n\nPassword:\nsecret\n\n---\n\n")
        assertEquals(Record("A", username = "", password = "secret"), parsed.records.single())
    }

    @Test
    fun bomCrlfAndAFinalSeparatorWithoutABlankLine_areAccepted() {
        val text = bom + "Title: A\r\n\r\nUsername:\r\nu\r\n\r\nPassword:\r\np\r\n\r\n---"
        assertEquals(true, TxtVaultFormat.looksLikeTxt(text))
        assertEquals(Record("A", username = "u", password = "p"), TxtVaultFormat.parse(text).records.single())
    }

    @Test
    fun filesFrom263_withUnprefixedCustomFields() {
        // Up to 2.6.3 custom fields were written as "<key>:" without "Custom.".
        val afterTags = "Title: Old\n\nUsername:\nu\n\nPassword:\np\n\nTags:\nx\n\nPIN:\n1234\n\n---\n\n"
        assertEquals(listOf("PIN" to "1234"), TxtVaultFormat.parse(afterTags).records.single().customFields)

        // Directly after Notes they can't be told apart from note text: kept in the notes, not lost.
        val afterNotes = "Title: Old\n\nUsername:\nu\n\nPassword:\np\n\nNotes:\nn\n\nPIN:\n1234\n\n---\n\n"
        assertEquals("n\n\nPIN:\n1234", TxtVaultFormat.parse(afterNotes).records.single().notes)
    }

    @Test
    fun textOutsideAnEntry_isCountedInvalid_andTheNextEntryStillReads() {
        val parsed = TxtVaultFormat.parse("junk line\n\nTitle: A\n\nUsername:\nu\n\nPassword:\np\n\n---\n\n")
        assertEquals(1, parsed.invalidCount)
        assertEquals("A", parsed.records.single().title)
    }

    @Test
    fun handEdits_spacesAroundSeparatorsAndLabels_andOnBlankLines_areIgnored() {
        // Without this, "--- " (a trailing space) merged entry B into entry A.
        val parsed = TxtVaultFormat.parse(
            "Title: A\n\nUsername: \nua\n\n  Password:\npa\n  \n--- \n \nTitle: B\n\nUsername:\nub\n\nPassword:\npb\n\n---\n"
        )
        assertEquals(0, parsed.invalidCount)
        assertEquals(listOf(Record("A", "ua", "pa"), Record("B", "ub", "pb")), parsed.records)
    }

    @Test
    fun inexactEntries_areTheOnesTheFormatCantHold() {
        val fine = Record("Fine", username = "u", password = "  p  ", notes = "a\n\nb\n  ", tags = listOf("x", "y z"))
        val inexact = listOf(
            Record("Multi-line password", password = "one\ntwo"),
            Record("Comma tag", tags = listOf("a,b")),
            Record("Line break in a tag", tags = listOf("x", "a\nPassword:")),
            Record("Blank category", category = " "),
            Record("Notes that look like a label", notes = "a\n\nTags:\nb"),
            Record("Notes that look like a label after spaces", notes = "a\n  \n Custom.k:\nb"),
            Record("Notes that end the entry", notes = "x\n---\n\nTitle: y"),
            Record("Notes that end the entry, with spaces", notes = "x\n --- \n \nTitle: y"),
            Record("Line break in a custom field", customFields = listOf("k" to "v1\nv2"))
        )
        assertEquals(0, TxtVaultFormat.inexactEntries(listOf(fine)))
        assertEquals(inexact.size, TxtVaultFormat.inexactEntries(inexact + fine))
    }
}
