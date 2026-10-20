package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.security.VaultSessionManager
import com.example.ui.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F9: a TXT export of awkward entries imports back unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TxtRoundTripTest {

    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        VaultSessionManager.resetForTesting()
        withContext(Dispatchers.IO) { app.container.appDatabase.clearAllTables() }
        app.container.vaultRepository.injectSoftwareDek(ByteArray(32) { 0x42 })
        viewModel = VaultViewModel(app.container.vaultRepository, app.container.settingsRepository)
    }

    @After
    fun tearDown() {
        app.container.vaultRepository.clearSoftwareDek()
        VaultSessionManager.resetForTesting()
    }

    private fun VaultEntry.comparable() =
        listOf(title, username, password, isFavorite, website, notes, category, tags, customFields)

    @Test
    fun awkwardEntries_survive() = runBlocking {
        val entries = listOf(
            VaultEntry(title = "No username"),
            VaultEntry(title = "Dashes", username = "a---b", password = "---", website = "https://x/---"),
            VaultEntry(title = "Spaces", username = " lead", password = "trail "),
            VaultEntry(title = "PEM", password = "p", notes = "-----BEGIN KEY-----\nabc\n\n-----END KEY-----\n"),
            VaultEntry(
                title = "Everything", username = "u", password = "p", isFavorite = true, website = "https://e.example",
                notes = "first\n\nPassword:\nnot a label here", category = "Work", tags = listOf("one", "two words"),
                customFields = listOf(CustomField("PIN", "4321"), CustomField("Notes", "a custom field named Notes"))
            )
        )
        entries.forEach { app.container.vaultRepository.insertEntry(it) }
        assertEquals("Nothing here needs a warning", 0, viewModel.txtExportInexactCount())

        val txt = String(viewModel.generateTxtExportPayload(), Charsets.UTF_8)
        val (imported, invalid) = viewModel.decodeImportPayload(txt)

        assertEquals(0, invalid)
        assertEquals(entries.map { it.comparable() }.sortedBy { it.first() as String },
            imported.map { it.comparable() }.sortedBy { it.first() as String })
    }

    @Test
    fun entriesTheFormatCantHold_areCountedBeforeExport() = runBlocking {
        app.container.vaultRepository.insertEntry(VaultEntry(title = "Two-line password", password = "one\ntwo"))
        app.container.vaultRepository.insertEntry(VaultEntry(title = "Fine", password = "p"))
        assertEquals(1, viewModel.txtExportInexactCount())
    }

    @Test
    fun aBomAtTheStart_stillImportsAsTxt() {
        val txt = 0xFEFF.toChar() + "Title: A\n\nUsername:\nu\n\nPassword:\np\n\n---\n\n"
        assertTrue(viewModel.isPlaintextImport(txt))
        assertEquals("A", viewModel.decodeImportPayload(txt).first.single().title)
    }
}
