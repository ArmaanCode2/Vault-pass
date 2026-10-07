package com.example

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.domain.models.VaultEntry
import com.example.security.CryptoManager
import com.example.security.VaultSessionManager
import com.example.ui.ExportDocument
import com.example.ui.ExportOutcome
import com.example.ui.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** R1 (round 4): a .vpex export never goes out with an empty or short password, and the password never outlives an attempt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportFlowTest {

    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel
    private val repo get() = app.container.vaultRepository

    /** A document the file picker just created (empty). */
    private class FreshDocument : ExportDocument {
        var content = ByteArray(0)
        var opened = false
        var deleted = false

        override fun size(): Long = content.size.toLong()

        override fun openForWriting(mode: String): OutputStream {
            opened = true
            val buffer = ByteArrayOutputStream()
            return object : OutputStream() {
                override fun write(b: Int) {
                    buffer.write(b)
                    content = buffer.toByteArray()
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    buffer.write(b, off, len)
                    content = buffer.toByteArray()
                }
            }
        }

        override fun delete() {
            deleted = true
        }

        override fun displayName(): String = "vaultpass_backup.vpex"
    }

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        VaultSessionManager.resetForTesting()
        withContext(Dispatchers.IO) { app.container.appDatabase.clearAllTables() }
        viewModel = VaultViewModel(repo, app.container.settingsRepository)
        viewModel.lock()
        ShadowLooper.idleMainLooper()
        viewModel.setupMasterPasswordSync("MasterPassword123!")
        assertTrue(viewModel.isUnlocked.first())
        repo.insertEntry(VaultEntry(syncId = "id-bank", title = "Bank", username = "me", password = "s3cret!"))
    }

    @After
    fun tearDown() = runBlocking {
        viewModel.awaitSecurityStatsRecalculation()
        repo.clearSoftwareDek()
        VaultSessionManager.resetForTesting()
    }

    @Test
    fun vpex_withAnEmptyPassword_failsAndWritesNothing() = runBlocking {
        // E.g. the screen was recreated while the file picker was open and the password was lost.
        val doc = FreshDocument()
        val report = viewModel.exportTo(doc, "vpex")

        assertTrue(report.outcome is ExportOutcome.Failed)
        assertFalse("Never opened", doc.opened)
        assertEquals(0, doc.content.size)
        assertTrue("The empty document the picker created is removed", doc.deleted)
    }

    @Test
    fun vpex_withAShortPassword_failsAndClearsIt() = runBlocking {
        viewModel.setExportPassword("1234567")
        val doc = FreshDocument()
        val report = viewModel.exportTo(doc, "vpex")

        assertTrue(report.outcome is ExportOutcome.Failed)
        assertFalse(doc.opened)
        assertEquals("Cleared after a failed attempt", "", viewModel.exportPassword.value)
    }

    @Test
    fun vpex_withAGoodPassword_isWritten_andThePasswordIsCleared() = runBlocking {
        viewModel.setExportPassword("Backup-Pass-99")
        val doc = FreshDocument()
        val report = viewModel.exportTo(doc, "vpex")

        assertTrue(report.outcome is ExportOutcome.Written)
        assertEquals("Cleared after a successful attempt", "", viewModel.exportPassword.value)
        val decrypted = CryptoManager.decryptBackup(Base64.decode(String(doc.content, Charsets.UTF_8), Base64.NO_WRAP), "Backup-Pass-99")
        assertNotNull(decrypted)
        assertTrue(decrypted!!.contains("Bank"))
    }

    @Test
    fun aFailedPlainExport_clearsThePasswordToo() = runBlocking {
        viewModel.setExportPassword("Backup-Pass-99")
        viewModel.lock()
        val report = viewModel.exportTo(FreshDocument(), "json")

        assertTrue(report.outcome is ExportOutcome.Failed)
        assertEquals("", viewModel.exportPassword.value)
    }

    @Test
    fun locking_clearsThePassword() {
        viewModel.setExportPassword("Backup-Pass-99")
        viewModel.lock()
        assertEquals("", viewModel.exportPassword.value)
    }

    @Test
    fun aSessionLockByAnotherPath_clearsThePassword() {
        viewModel.setExportPassword("Backup-Pass-99")
        // Auto-lock calls the session manager directly, not the view model.
        viewModel.sessionManager.lock()
        ShadowLooper.idleMainLooper()
        assertEquals("", viewModel.exportPassword.value)
    }

    @Test
    fun exportTo_neverClearsAPasswordTypedForTheNextAttempt() = runBlocking {
        viewModel.setExportPassword("Backup-Pass-99")
        val doc = object : ExportDocument by FreshDocument() {
            override fun openForWriting(mode: String): OutputStream {
                // The user starts the next export while this one writes.
                viewModel.setExportPassword("Next-Backup-77")
                return ByteArrayOutputStream()
            }
        }
        val report = viewModel.exportTo(doc, "vpex")

        assertTrue(report.outcome is ExportOutcome.Written)
        assertEquals("Next-Backup-77", viewModel.exportPassword.value)
    }

    @Test
    fun thePasswordGoesWithItsDialog_unlessThePickerWasOpenedForIt() = runBlocking {
        viewModel.setExportPassword("Backup-Pass-99")
        viewModel.onExportPasswordDialogGone()
        assertEquals("Dialog closed or screen recreated: gone", "", viewModel.exportPassword.value)

        viewModel.setExportPassword("Backup-Pass-99")
        viewModel.onExportPickerLaunched()
        viewModel.onExportPasswordDialogGone()
        assertEquals("Kept for the picker's result", "Backup-Pass-99", viewModel.exportPassword.value)

        viewModel.exportTo(FreshDocument(), "vpex")
        assertEquals("", viewModel.exportPassword.value)

        // A later dialog, without a picker, is not covered by the earlier launch.
        viewModel.setExportPassword("Another-Pass-11")
        viewModel.onExportPasswordDialogGone()
        assertEquals("", viewModel.exportPassword.value)
    }

    @Test
    fun theOldVpexEntryPoint_refusesAShortPasswordToo() = runBlocking<Unit> {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { viewModel.generateVpexExportPayload("") }
        }
    }
}
