package com.example

import androidx.test.core.app.ApplicationProvider
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
}
