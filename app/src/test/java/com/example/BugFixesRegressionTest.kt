package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.ui.VaultViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.SecretKeyFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BugFixesRegressionTest {

    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        viewModel = VaultViewModel(app.container.vaultRepository, app.container.settingsRepository)
    }

    @Test
    fun metadataValidation_parsesCleanly() {
        val candidatePaths = listOf(
            File("metadata.json"),
            File("../metadata.json"),
            File("../../metadata.json"),
            File("e:/antigravity/password manager/vaultpass/metadata.json")
        )
        val metadataFile = candidatePaths.firstOrNull { it.exists() }
        assertNotNull("metadata.json should be found", metadataFile)
        val content = metadataFile!!.readText()

        val jsonElement = Json.parseToJsonElement(content)
        val jsonObject = jsonElement.jsonObject

        assertEquals("VaultPass", jsonObject["name"]?.toString()?.replace("\"", ""))
        assertEquals("Secure offline password manager", jsonObject["description"]?.toString()?.replace("\"", ""))
        assertTrue(jsonObject.containsKey("requestFramePermissions"))
        assertTrue(jsonObject.containsKey("majorCapabilities"))
    }

    @Test
    fun kdfAlgorithmSafety_returnsPBKDF2WithHmacSHA256() = runBlocking {
        val algorithm = app.container.settingsRepository.masterKdfAlgorithm.first()
        assertEquals("PBKDF2WithHmacSHA256", algorithm)

        val factory = SecretKeyFactory.getInstance(algorithm)
        assertNotNull("SecretKeyFactory must initialize without error", factory)
    }

    @Test
    fun biometricsSafety_requiresDekToUnlock() = runBlocking {
        viewModel.lock()
        assertFalse("Vault should be locked initially", viewModel.isUnlocked.value)

        val testDek = ByteArray(32) { 0x2A }
        val unlocked = viewModel.unlockWithBiometrics(testDek)

        assertTrue(unlocked)
        assertTrue(viewModel.isUnlocked.value)
        val injectedDek = app.container.vaultRepository.getSoftwareDek()
        assertNotNull(injectedDek)
        assertArrayEquals(testDek, injectedDek)
    }

    @Test
    fun jsonExportImportRoundtrip_preservesDuplicateTitles() = runBlocking {
        val testDek = ByteArray(32) { 0x3C }
        app.container.vaultRepository.injectSoftwareDek(testDek)

        val entry1 = VaultEntry(
            id = 1,
            title = "Work VPN",
            username = "alice",
            password = "secret1Password",
            tags = listOf("work")
        )
        val entry2 = VaultEntry(
            id = 2,
            title = "Work VPN",
            username = "bob",
            password = "secret2Password",
            tags = listOf("vpn")
        )

        app.container.vaultRepository.insertEntry(entry1)
        app.container.vaultRepository.insertEntry(entry2)

        val payloadBytes = viewModel.generateSimplifiedJsonExportPayload()
        val jsonString = String(payloadBytes, Charsets.UTF_8)

        val (importedEntries, invalidCount) = viewModel.decodeSimplifiedJsonImportPayload(jsonString)

        assertEquals(0, invalidCount)
        val workVpnEntries = importedEntries.filter { it.title == "Work VPN" }
        assertEquals("Both entries must maintain their exact original title 'Work VPN'", 2, workVpnEntries.size)
        assertTrue(workVpnEntries.any { it.username == "alice" })
        assertTrue(workVpnEntries.any { it.username == "bob" })
    }

    @Test
    fun tagsPreservation_tagsNotLostOnEdit() = runBlocking {
        val testDek = ByteArray(32) { 0x1B }
        app.container.vaultRepository.injectSoftwareDek(testDek)

        val originalEntry = VaultEntry(
            id = 10,
            title = "Banking",
            username = "user123",
            password = "BankPassword1!",
            tags = listOf("finance", "critical")
        )
        app.container.vaultRepository.insertEntry(originalEntry)

        val loaded = app.container.vaultRepository.getEntryById(10)
        assertNotNull(loaded)
        assertEquals(listOf("finance", "critical"), loaded!!.tags)

        // Simulating the save action from PasswordEntryScreen where tags = loaded.tags is passed
        val tags = loaded.tags
        val updatedEntry = loaded.copy(
            username = "updatedUser123",
            tags = tags
        )
        app.container.vaultRepository.updateEntry(updatedEntry)

        val reloaded = app.container.vaultRepository.getEntryById(10)
        assertNotNull(reloaded)
        assertEquals("updatedUser123", reloaded!!.username)
        assertEquals(listOf("finance", "critical"), reloaded.tags)
    }

    @Test
    fun legacyJsonImport_stripsDeduplicationSuffix() {
        val legacyJson = """
            {
                "Work VPN": ["alice", "pass1"],
                "Work VPN (2)": ["bob", "pass2"]
            }
        """.trimIndent()

        val (importedEntries, invalidCount) = viewModel.decodeSimplifiedJsonImportPayload(legacyJson)

        assertEquals(0, invalidCount)
        assertEquals(2, importedEntries.size)
        assertEquals("Work VPN", importedEntries[0].title)
        assertEquals("Work VPN", importedEntries[1].title)
    }

    @Test
    fun txtImport_preservesMultilineNotes() {
        val txtData = """
            Title: Server Backup

            Username:
            admin

            Password:
            mysecretpassword

            Notes:
            Line 1: Server IP is 10.0.0.1
            Line 2: SSH Key is stored in safe
            Line 3: Rotate every 90 days

            Category:
            Work

            ---
        """.trimIndent()

        val (entries, invalidCount) = viewModel.decodeImportPayload(txtData)
        assertEquals(0, invalidCount)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("Server Backup", entry.title)
        assertEquals("admin", entry.username)
        val expectedNotes = "Line 1: Server IP is 10.0.0.1\nLine 2: SSH Key is stored in safe\nLine 3: Rotate every 90 days"
        assertEquals(expectedNotes, entry.notes)
        assertEquals("Work", entry.category)
    }

    @Test
    fun txtExportImport_customFieldsKeywordDisambiguation() = runBlocking {
        val testDek = ByteArray(32) { 0x55 }
        app.container.vaultRepository.injectSoftwareDek(testDek)

        val entry = VaultEntry(
            id = 1,
            title = "Test Service",
            username = "user1",
            password = "pass1",
            notes = "Multiline\nNote",
            customFields = listOf(
                CustomField("Notes", "Custom field named Notes"),
                CustomField("PIN", "1234")
            )
        )
        app.container.vaultRepository.insertEntry(entry)

        val exportedBytes = viewModel.generateTxtExportPayload()
        val txt = String(exportedBytes, Charsets.UTF_8)
        assertTrue(txt.contains("Custom.Notes:"))
        assertTrue(txt.contains("Custom.PIN:"))

        val (imported, invalidCount) = viewModel.decodeImportPayload(txt)
        assertEquals(0, invalidCount)
        val testServiceEntry = imported.first { it.title == "Test Service" }
        assertEquals("Multiline\nNote", testServiceEntry.notes)
        assertEquals(2, testServiceEntry.customFields.size)
        assertEquals("Notes", testServiceEntry.customFields[0].key)
        assertEquals("Custom field named Notes", testServiceEntry.customFields[0].value)
        assertEquals("PIN", testServiceEntry.customFields[1].key)
        assertEquals("1234", testServiceEntry.customFields[1].value)
    }

    @Test
    fun jsonExport_handlesThreeOrMoreDuplicateTitlesWithoutOverwrite() = runBlocking {
        val testDek = ByteArray(32) { 0x77 }
        app.container.vaultRepository.injectSoftwareDek(testDek)

        val entries = listOf(
            VaultEntry(id = 101, title = "Gmail", username = "user1@gmail.com", password = "p1"),
            VaultEntry(id = 102, title = "Gmail", username = "user2@gmail.com", password = "p2"),
            VaultEntry(id = 103, title = "Gmail", username = "user3@gmail.com", password = "p3"),
            VaultEntry(id = 104, title = "Gmail", username = "user4@gmail.com", password = "p4")
        )
        app.container.vaultRepository.insertEntries(entries)

        val exportedBytes = viewModel.generateSimplifiedJsonExportPayload()
        val jsonString = String(exportedBytes, Charsets.UTF_8)

        // Verify JSON keys have the unique deduplication suffix
        assertTrue(jsonString.contains("\"Gmail\""))
        assertTrue(jsonString.contains("\"Gmail (2)\""))
        assertTrue(jsonString.contains("\"Gmail (3)\""))
        assertTrue(jsonString.contains("\"Gmail (4)\""))

        val (imported, invalidCount) = viewModel.decodeSimplifiedJsonImportPayload(jsonString)
        assertEquals(0, invalidCount)
        val gmailEntries = imported.filter { it.title == "Gmail" }
        assertEquals(4, gmailEntries.size)
        val usernames = gmailEntries.map { it.username }.toSet()
        assertEquals(setOf("user1@gmail.com", "user2@gmail.com", "user3@gmail.com", "user4@gmail.com"), usernames)
    }

    @Test
    fun addEntrySync_and_updateEntrySync_failWhenVaultLocked() = runBlocking {
        app.container.vaultRepository.clearSoftwareDek()
        val entry = VaultEntry(title = "Test Secret", username = "user", password = "pwd")

        val addResultLocked = viewModel.addEntrySync(entry)
        assertFalse("addEntrySync should fail when vault is locked", addResultLocked)

        val updateResultLocked = viewModel.updateEntrySync(entry.copy(id = 1))
        assertFalse("updateEntrySync should fail when vault is locked", updateResultLocked)

        // Inject DEK and verify success
        val testDek = ByteArray(32) { 0x33 }
        app.container.vaultRepository.injectSoftwareDek(testDek)

        val addResultUnlocked = viewModel.addEntrySync(entry)
        assertTrue("addEntrySync should succeed when vault is unlocked", addResultUnlocked)

        val added = app.container.vaultRepository.getAllEntriesSync().first { it.title == "Test Secret" }
        val updateResultUnlocked = viewModel.updateEntrySync(added.copy(username = "new_user"))
        assertTrue("updateEntrySync should succeed when vault is unlocked", updateResultUnlocked)
    }

    @Test
    fun vaultRepository_collectorsLifecycle_clearsAndRestarts() = runBlocking {
        val testDek = ByteArray(32) { 0x44 }
        app.container.vaultRepository.injectSoftwareDek(testDek)

        val entry = VaultEntry(title = "Lifecycle Test", username = "u1", password = "p1")
        app.container.vaultRepository.insertEntry(entry)

        // Verify DEK is present
        assertNotNull(app.container.vaultRepository.getSoftwareDek())

        // Clear DEK (locks vault)
        app.container.vaultRepository.clearSoftwareDek()
        assertNull(app.container.vaultRepository.getSoftwareDek())
        assertTrue("decryptedEntries should be empty after clearing DEK", app.container.vaultRepository.decryptedEntries.value.isEmpty())

        // Re-inject DEK (unlocks vault)
        app.container.vaultRepository.injectSoftwareDek(testDek)
        assertNotNull(app.container.vaultRepository.getSoftwareDek())
    }
}

