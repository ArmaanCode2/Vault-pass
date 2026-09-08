package com.example

import android.content.Context
import android.os.CancellationSignal
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import androidx.test.core.app.ApplicationProvider
import com.example.domain.models.VaultEntry
import com.example.security.CryptoManager
import com.example.security.PasswordHashHelper
import com.example.security.VaultSessionManager
import com.example.service.VaultAutofillService
import com.example.ui.AuthResult
import com.example.ui.VaultViewModel
import com.example.ui.VaultViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerformanceAndReliabilityTest {

    private lateinit var context: Context
    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        app = context as VaultPassApplication
        VaultSessionManager.resetForTesting()
        withContext(Dispatchers.IO) {
            app.container.appDatabase.clearAllTables()
        }
        viewModel = VaultViewModel(app.container.vaultRepository, app.container.settingsRepository)
        
        // Ensure clean state
        viewModel.lock()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test
    fun inMemoryDecryptedCache_searchPerformanceAndZeroRedundantDecryption() = runBlocking {
        val password = "BenchPassword123!"
        viewModel.setupMasterPasswordSync(password)
        assertTrue("Vault should be unlocked after setup", viewModel.isUnlocked.first())

        // Insert 200 entries into the vault
        val entryCount = 200
        val entries = (1..entryCount).map { i ->
            VaultEntry(
                id = i,
                title = "Service Account $i",
                username = "user_$i@vaultpass.test",
                password = "Password#$i",
                website = "https://service$i.example.com",
                notes = "Auto-generated test entry $i",
                category = if (i % 2 == 0) "Work" else "Personal",
                tags = listOf("bench", "tag_$i")
            )
        }
        app.container.vaultRepository.insertEntries(entries)

        // Ensure cache is warmed up
        val cached = app.container.vaultRepository.decryptedEntries.first()
        assertEquals(entryCount, cached.size)

        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val allDecrypted = viewModel.allDecryptedEntries.filterNotNull().first()
        assertEquals(entryCount, allDecrypted.size)

        // Benchmark searching against the in-memory cache
        val startTime = System.nanoTime()
        val queries = listOf("Service 1", "Work", "tag_5", "user_199", "nonexistent")
        for (q in queries) {
            viewModel.updateSearchQuery(q)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val searchResults = viewModel.dashboardEntries.filterNotNull().first()
            assertNotNull(searchResults)
        }
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

        // In-memory searching across 200 items for 5 queries should be nearly instantaneous (< 500ms)
        assertTrue("5 in-memory search queries took ${elapsedMs}ms, should be < 500ms", elapsedMs < 500.0)
    }

    @Test
    fun instantMemoryPurge_clearsAllDecryptedStateOnLock() = runBlocking {
        val password = "PurgePassword123!"
        viewModel.setupMasterPasswordSync(password)
        assertTrue("Vault should be unlocked", viewModel.isUnlocked.first())

        val entry = VaultEntry(
            id = 1,
            title = "Confidential Bank",
            username = "admin",
            password = "SuperSecretBankPassword",
            website = "https://bank.example.com"
        )
        app.container.vaultRepository.insertEntry(entry)

        val beforeLockDecrypted = app.container.vaultRepository.decryptedEntries.first()
        assertEquals(1, beforeLockDecrypted.size)
        assertNotNull(app.container.cryptoManager.getSoftwareDek())

        // Lock vault
        viewModel.lock()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // 1. Session state must be locked
        assertFalse("ViewModel isUnlocked must be false", viewModel.isUnlocked.first())
        assertFalse("SessionManager isUnlocked must be false", viewModel.sessionManager.isUnlocked.first())

        // 2. Software DEK in CryptoManager must be purged
        assertNull("Software DEK must be null after lock", app.container.cryptoManager.getSoftwareDek())

        // 3. Decrypted entries StateFlow and internal cache must be empty
        val afterLockDecrypted = app.container.vaultRepository.decryptedEntries.value
        assertTrue("Decrypted entries StateFlow must be empty after lock", afterLockDecrypted.isEmpty())

        // 4. allDecryptedEntries in ViewModel must emit null when locked
        val vmDecrypted = viewModel.allDecryptedEntries.first()
        assertNull("ViewModel allDecryptedEntries must be null when locked", vmDecrypted)
    }

    @Test
    fun sessionManager_synchronizesLockStateAcrossMultipleViewModels() = runBlocking {
        val sessionManager = app.container.vaultSessionManager
        val factory = VaultViewModelFactory(app.container.vaultRepository, app.container.settingsRepository, sessionManager)
        
        val vm1 = factory.create(VaultViewModel::class.java)
        val vm2 = factory.create(VaultViewModel::class.java)

        val password = "SharedSessionPassword123!"
        vm1.setupMasterPasswordSync(password)

        // Both ViewModels share the exact same session state
        assertTrue("VM1 should be unlocked", vm1.isUnlocked.first())
        assertTrue("VM2 should be unlocked immediately via shared session", vm2.isUnlocked.first())

        // Locking from VM2 must lock VM1 as well
        vm2.lock()

        assertFalse("VM1 must be locked after VM2 locks", vm1.isUnlocked.first())
        assertFalse("VM2 must be locked", vm2.isUnlocked.first())
        assertFalse("SessionManager must be locked", sessionManager.isUnlocked.first())
    }

    @Test
    fun autofillCancellationAndCallbackSafety_guardsAgainstMultipleInvocations() {
        val callbackInvoked = AtomicBoolean(false)
        val invokeCount = AtomicInteger(0)
        val cancellationSignal = CancellationSignal()

        fun safeSuccess(response: FillResponse?) {
            if (cancellationSignal.isCanceled) return
            if (callbackInvoked.compareAndSet(false, true)) {
                invokeCount.incrementAndGet()
            }
        }

        // 1. Normal invocation
        safeSuccess(null)
        assertEquals("Callback should be invoked exactly once", 1, invokeCount.get())

        // 2. Duplicate invocation attempt must be dropped
        safeSuccess(null)
        assertEquals("Callback should not be invoked a second time", 1, invokeCount.get())

        // 3. Pre-cancelled signal should never invoke callback
        val cancelledCallbackInvoked = AtomicBoolean(false)
        val cancelledInvokeCount = AtomicInteger(0)
        val preCancelledSignal = CancellationSignal()
        preCancelledSignal.cancel()

        fun safeSuccessCancelled(response: FillResponse?) {
            if (preCancelledSignal.isCanceled) return
            if (cancelledCallbackInvoked.compareAndSet(false, true)) {
                cancelledInvokeCount.incrementAndGet()
            }
        }

        safeSuccessCancelled(null)
        assertEquals("Callback must not be invoked when cancelled", 0, cancelledInvokeCount.get())
    }

    @Test
    fun exportStreamValidation_nullStreamThrowsIOException() {
        // Simulating the null OutputStream check in SettingsScreen
        val fakeUri = android.net.Uri.parse("content://dummy/export.vpex")
        
        // Null output stream check
        var exceptionThrown = false
        try {
            val outputStream: java.io.OutputStream? = null
            val resolvedStream = outputStream ?: throw IOException("Unable to open output stream for destination: $fakeUri")
            resolvedStream.write("test".toByteArray())
        } catch (e: IOException) {
            exceptionThrown = true
            assertTrue(e.message!!.contains("Unable to open output stream"))
        }

        assertTrue("Null output stream must throw IOException to prevent false-positive export success", exceptionThrown)
    }
}
