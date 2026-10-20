package com.example

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.domain.models.CustomField
import com.example.domain.models.EntryPasskeys
import com.example.domain.models.PasskeyRecord
import com.example.domain.models.VaultEntry
import com.example.ui.VaultViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Passkeys in the vault: the entry editor never drops one, and JSON/.vpex backups keep every one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasskeyStorageTest {

    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel

    private fun passkey(id: String, user: String) =
        PasskeyRecord(credentialId = id, rpId = "example.com", userHandle = user, userName = "alice", keyValue = "a2V5$id")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        viewModel = VaultViewModel(app.container.vaultRepository, app.container.settingsRepository)
        app.container.vaultRepository.injectSoftwareDek(ByteArray(32) { 0x5A })
    }

    @Test
    fun editorSave_keepsAPasskeyAddedWhileItWasOpen() {
        val stored = runBlocking {
            app.container.vaultRepository.insertEntry(VaultEntry(title = "Example", username = "alice", website = "https://example.com"))
            app.container.vaultRepository.getAllEntriesSync().first { it.title == "Example" }
        }
        // The editor opened before the passkey existed...
        val edited = stored.copy(title = "Example (work)", customFields = listOf(CustomField("PIN", "1234")))
        // ...and the sign-in sheet added one meanwhile.
        runBlocking {
            app.container.vaultRepository.updateEntry(
                stored.copy(customFields = EntryPasskeys.withPasskey(stored.customFields, passkey("AQID", "dXNlcg")))
            )
        }

        viewModel.saveFromEditor("token", edited, isEditing = true)
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.editorSave.value?.done != true && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(true, viewModel.editorSave.value?.success)

        val saved = runBlocking { app.container.vaultRepository.getEntryById(stored.id)!! }
        assertEquals("Example (work)", saved.title)
        assertEquals(listOf("AQID"), EntryPasskeys.passkeys(saved).map { it.credentialId })
        assertTrue(CustomField("PIN", "1234") in saved.customFields)
    }

    @Test
    fun jsonBackup_keepsEveryFieldWithARepeatedKey() {
        val fields = EntryPasskeys.withPasskey(
            EntryPasskeys.withPasskey(listOf(CustomField("vp:website", "a.example.com"), CustomField("vp:website", "b.example.com")), passkey("AQID", "dXNlcg")),
            passkey("BAUG", "b3RoZXI")
        )
        runBlocking { app.container.vaultRepository.insertEntry(VaultEntry(title = "Backup", username = "alice", password = "pw", customFields = fields)) }

        val (imported, invalid) = runBlocking {
            viewModel.decodeSimplifiedJsonImportPayload(String(viewModel.generateSimplifiedJsonExportPayload(), Charsets.UTF_8))
        }
        assertEquals(0, invalid)
        val entry = imported.first { it.title == "Backup" }
        assertEquals(fields, entry.customFields)

        // A backup written before the list existed (object only) still imports.
        val (old, _) = viewModel.decodeSimplifiedJsonImportPayload(
            """{"Old": ["u", "p", {"title": "Old", "customFields": {"PIN": "1", "Note": "x"}}]}"""
        )
        assertEquals(listOf(CustomField("PIN", "1"), CustomField("Note", "x")), old.single().customFields)
    }

    @Test
    fun keepStored_andAppsNeverReplaceAPasskey() {
        val withKey = EntryPasskeys.withPasskey(emptyList(), passkey("AQID", "dXNlcg"))
        val edited = listOf(CustomField("PIN", "1"))
        assertEquals(edited + withKey, EntryPasskeys.keepStored(edited, withKey))
        assertEquals("Stored entry unreadable: the editor's fields", edited, EntryPasskeys.keepStored(edited, null))

        // Same account again: a browser's replaces it, an app's is added next to it.
        val again = passkey("BAUG", "dXNlcg")
        assertEquals(listOf("BAUG"), EntryPasskeys.passkeys(EntryPasskeys.withPasskey(withKey, again)).map { it.credentialId })
        assertEquals(
            listOf("AQID", "BAUG"),
            EntryPasskeys.passkeys(EntryPasskeys.withPasskey(withKey, again, replaceSameAccount = false)).map { it.credentialId }
        )
    }
}
