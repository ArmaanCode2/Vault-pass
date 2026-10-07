package com.example

import android.util.Base64
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.data.models.VaultEntryEntity
import com.example.domain.models.VaultEntry
import com.example.domain.security.SecurityStatsSummary
import com.example.domain.sync.diff.SyncMergeExecutor
import com.example.security.CryptoManager
import com.example.security.VaultLockedException
import com.example.security.VaultSessionManager
import com.example.ui.AuthResult
import com.example.ui.VaultViewModel
import com.vaultpass.synccore.PlanAction
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.SyncRecord
import com.example.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * F16: a write never stores data it could not encrypt, and a lock that lands while a write or a
 * sync merge runs leaves the database exactly as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultLockRaceTest {

    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel
    private val masterPassword = "MasterPassword123!"

    private val repo get() = app.container.vaultRepository
    private val crypto get() = repo.cryptoManager
    private val dao get() = app.container.appDatabase.vaultDao()

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        VaultSessionManager.resetForTesting()
        withContext(Dispatchers.IO) { app.container.appDatabase.clearAllTables() }
        viewModel = VaultViewModel(repo, app.container.settingsRepository)
        viewModel.lock()
        ShadowLooper.idleMainLooper()
        viewModel.setupMasterPasswordSync(masterPassword)
        assertTrue(viewModel.isUnlocked.first())
        viewModel.awaitSecurityStatsRecalculation()
    }

    @After
    fun tearDown() = runBlocking {
        repo.refreshDecryptHook = null
        viewModel.awaitSecurityStatsRecalculation()
        repo.clearSoftwareDek()
        VaultSessionManager.resetForTesting()
    }

    /** Every stored row (recycle bin included) and every tombstone, as on disk. */
    private suspend fun storedState(): Pair<List<Any>, List<Any>> =
        dao.getAllEntitiesIncludingDeletedSync().sortedBy { it.id } to dao.getAllTombstones().sortedBy { it.syncId }

    private suspend fun seed() {
        repo.insertEntry(VaultEntry(syncId = "id-keep", title = "Keep", password = "old"))
        repo.insertEntry(VaultEntry(syncId = "id-bin", title = "Bin me", password = "b"))
    }

    // --- Sync merge ---

    @Test
    fun lockInsideTheMerge_rollsEverythingBack() = runBlocking {
        seed()
        val before = storedState()

        val failure = runCatching {
            repo.runSyncMerge {
                repo.upsertSyncedEntry(VaultEntry(syncId = "id-new", title = "New", password = "n"))
                repo.upsertSyncedEntry(VaultEntry(syncId = "id-keep", title = "Keep", password = "changed"))
                repo.moveToRecycleBinBySyncId("id-bin", 5_000L)
                crypto.clearSoftwareDek()
            }
        }.exceptionOrNull()

        assertTrue("Got $failure", failure is VaultLockedException)
        assertEquals("Every row unchanged", before, storedState())
    }

    @Test
    fun lockAndUnlockWithTheSameKeyInsideTheMerge_stillRollsBack() = runBlocking {
        seed()
        val before = storedState()
        val dek = crypto.getSoftwareDek()!!

        val failure = runCatching {
            repo.runSyncMerge {
                repo.upsertSyncedEntry(VaultEntry(syncId = "id-new", title = "New", password = "n"))
                repo.moveToRecycleBinBySyncId("id-bin", 5_000L)
                crypto.clearSoftwareDek()
                crypto.injectSoftwareDek(dek)
            }
        }.exceptionOrNull()

        assertTrue("Got $failure", failure is VaultLockedException)
        assertTrue("The key is back", crypto.hasKey())
        assertEquals("Every row unchanged", before, storedState())
    }

    @Test
    fun upsertOfAStoredEntryAfterTheLock_throwsInsteadOfSkipping() = runBlocking {
        seed()
        val before = storedState()
        repo.clearSoftwareDek()

        // Called on its own (no merge transaction to catch it later): the stored row is unreadable
        // only because the vault locked, which must not read as "skip this one" (false).
        val failure = runCatching {
            repo.upsertSyncedEntry(VaultEntry(syncId = "id-keep", title = "Keep", password = "changed"))
        }.exceptionOrNull()

        assertTrue("Got $failure", failure is VaultLockedException)
        assertEquals(before, storedState())
    }

    @Test
    fun theSameKeyInjectedAgainInsideTheMerge_doesNotRollItBack() = runBlocking {
        seed()
        val dek = crypto.getSoftwareDek()!!
        val state = crypto.keyState()

        // E.g. the autofill screen unlocks with biometrics while the app is open and syncing.
        repo.runSyncMerge {
            repo.upsertSyncedEntry(VaultEntry(syncId = "id-new", title = "New", password = "n"))
            repo.injectSoftwareDek(dek)
        }

        assertEquals("Not a new key", state, crypto.keyState())
        assertNotNull("The merge was stored", dao.getEntryBySyncId("id-new"))
    }

    @Test
    fun applyPlan_whileLocked_failsAndChangesNothing() = runBlocking {
        seed()
        val before = storedState()
        repo.clearSoftwareDek()

        val plan = listOf(
            PlanItem("id-new", PlanAction.UPSERT, SyncRecord(syncId = "id-new", title = "New", password = "n", updatedAt = 9_000L)),
            PlanItem("id-bin", PlanAction.RECYCLE, deletedAt = 9_000L)
        )
        val result = SyncMergeExecutor.applyPlan(plan, repo)

        assertTrue("Got ${result.exceptionOrNull()}", result.exceptionOrNull() is VaultLockedException)
        assertEquals(before, storedState())
    }

    @Test
    fun syncSnapshot_whileLocked_throws() = runBlocking<Unit> {
        seed()
        repo.clearSoftwareDek()
        try {
            repo.getSyncSnapshot()
            fail("A locked vault must not be offered to the peer")
        } catch (expected: VaultLockedException) {
        }
    }

    // --- Publishing decrypted data ---

    @Test
    fun publishIfCurrent_withAStaleGeneration_publishesNothing() {
        val dek = crypto.getSoftwareDek()!!
        val stale = crypto.keyGeneration
        repo.clearSoftwareDek()
        repo.injectSoftwareDek(dek)

        var published = false
        assertFalse(repo.publishIfCurrent(stale) { published = true })
        assertFalse("Decrypted under the old key: never published", published)

        assertTrue(repo.publishIfCurrent(crypto.keyGeneration) { published = true })
        assertTrue(published)

        published = false
        val current = crypto.keyGeneration
        repo.clearSoftwareDek()
        assertFalse(repo.publishIfCurrent(current) { published = true })
        assertFalse(published)
        assertFalse("Locked: nothing is current", repo.publishIfCurrent(crypto.keyGeneration) { published = true })
        assertFalse(published)
    }

    @Test
    fun keyState_isOneConsistentSnapshot_whileTheKeyChanges() {
        // Fresh manager: generation 0 and no key, so "has a key" must always mean "odd generation".
        val manager = CryptoManager(app.container.settingsRepository)
        val dek = crypto.getSoftwareDek()!!
        val stop = AtomicBoolean(false)
        val torn = AtomicReference<CryptoManager.KeyState?>(null)
        val writer = thread {
            while (!stop.get()) {
                manager.injectSoftwareDek(dek)
                manager.clearSoftwareDek()
            }
        }
        try {
            repeat(200_000) {
                val state = manager.keyState()
                if (state.hasKey != (state.generation % 2 == 1L)) torn.compareAndSet(null, state)
            }
        } finally {
            stop.set(true)
            writer.join()
        }
        assertNull("A snapshot mixed two key changes: ${torn.get()}", torn.get())
        assertEquals(CryptoManager.KeyState(manager.keyGeneration, false), manager.keyState())
    }

    @Test
    fun anUnreadableRow_isRememberedForTheKey_andForgottenOnLock() = runBlocking {
        seed()
        insertUnreadableRow()

        // Every write refreshes the list: the row shows as a placeholder and is remembered.
        repo.insertEntry(VaultEntry(syncId = "id-third", title = "Third", password = "t"))
        assertEquals(1, repo.decryptedEntries.value.count { it.isDecryptionFailed })
        assertEquals(1, repo.knownUndecryptableCount())

        // The next refresh shows it the same way, from what is remembered.
        repo.insertEntry(VaultEntry(syncId = "id-fourth", title = "Fourth", password = "f"))
        assertEquals(4, repo.decryptedEntries.value.count { !it.isDecryptionFailed })
        assertEquals(1, repo.decryptedEntries.value.count { it.isDecryptionFailed })
        assertEquals(1, repo.knownUndecryptableCount())

        repo.clearSoftwareDek()
        assertEquals("Another key may open it: forgotten on lock", 0, repo.knownUndecryptableCount())
    }

    @Test
    fun aLockInTheMiddleOfARefresh_publishesAndCachesNothing_andTheNextUnlockShowsTheEntries() = runBlocking {
        seed()
        repo.insertEntry(VaultEntry(syncId = "id-third", title = "Third", password = "t"))
        val dek = crypto.getSoftwareDek()!!
        reloadKeyQuietly(dek)
        lockMidRefresh()

        // This read decrypts every row itself; the vault locks after the first one.
        val read = repo.getAllEntriesSync()

        assertEquals("No \"Decryption Failed\" placeholders", emptyList<VaultEntry>(), read)
        assertEquals(emptyList<VaultEntry>(), repo.decryptedEntries.value)
        assertEquals("Nothing decrypted then is cached", 0, repo.cachedEntryCount())
        assertEquals("A lock is not an unreadable row", 0, repo.knownUndecryptableCount())

        repo.refreshDecryptHook = null
        repo.injectSoftwareDek(dek)
        assertTrue(eventually { repo.decryptedEntries.value.size == 3 })
        assertTrue(repo.decryptedEntries.value.none { it.isDecryptionFailed })
        assertEquals(setOf("Keep", "Bin me", "Third"), repo.decryptedEntries.value.map { it.title }.toSet())
    }

    @Test
    fun anExportDuringWhichTheVaultLocksAndUnlocks_isRefused() = runBlocking<Unit> {
        seed()
        val dek = crypto.getSoftwareDek()!!
        reloadKeyQuietly(dek)
        lockMidRefresh(thenUnlockWith = dek)

        // The key is back by the end, but what was read meanwhile is not the vault.
        assertThrows(VaultLockedException::class.java) {
            runBlocking { viewModel.generateSimplifiedJsonExportPayload() }
        }
    }

    @Test
    fun securityStats_whenTheVaultLocksAndUnlocksMidRun_keepTheLastSavedOnes() = runBlocking {
        seed()
        viewModel.recalculateSecurityStats()
        viewModel.awaitSecurityStatsRecalculation()
        val saved = app.container.settingsRepository.securityStatsSummary.first()
        assertEquals(2, saved?.totalPasswords)

        val dek = crypto.getSoftwareDek()!!
        reloadKeyQuietly(dek)
        lockMidRefresh(thenUnlockWith = dek)
        viewModel.recalculateSecurityStats()
        viewModel.awaitSecurityStatsRecalculation()

        assertEquals("Not replaced by stats of a half-read vault", saved, app.container.settingsRepository.securityStatsSummary.first())
    }

    /**
     * Locks and unlocks with [dek] without restarting the collectors: the caches are empty, so the
     * next read decrypts every row itself, on the calling thread.
     */
    private fun reloadKeyQuietly(dek: ByteArray) {
        repo.clearSoftwareDek()
        crypto.injectSoftwareDek(dek)
    }

    /** Locks the vault (then unlocks with [thenUnlockWith], if given) as a refresh decrypts its first row. */
    private fun lockMidRefresh(thenUnlockWith: ByteArray? = null) {
        val fired = AtomicBoolean(false)
        repo.refreshDecryptHook = {
            if (fired.compareAndSet(false, true)) {
                repo.clearSoftwareDek()
                thenUnlockWith?.let { crypto.injectSoftwareDek(it) }
            }
        }
    }

    // --- Unlocks racing a lock (O1, R3) ---

    @Test
    fun openVault_afterALockThatRanSinceTheUnlockStarted_staysLocked() {
        val dek = crypto.getSoftwareDek()!!
        val manager = viewModel.sessionManager
        val epoch = manager.currentLockEpoch()
        manager.lock()

        assertFalse(manager.openVault(dek, epoch))
        assertFalse(manager.isUnlocked.value)
        assertFalse("Locked means no key", crypto.hasKey())

        assertTrue(manager.openVault(dek, manager.currentLockEpoch()))
        assertTrue(manager.isUnlocked.value)
        assertTrue(crypto.hasKey())
    }

    @Test
    fun aLockWhileThePasswordUnlockRuns_keepsTheVaultLocked() = runBlocking {
        seed()
        viewModel.lock()
        val unlockLock = com.example.security.VaultUnlockLock.mutex
        unlockLock.lock()
        val unlocking = async(Dispatchers.Default) { viewModel.unlockWithPassword(masterPassword) }
        try {
            assertTrue(eventually { viewModel.isUnlocking.value })
            // E.g. "Lock immediately" and the user goes Home while the key is being derived.
            viewModel.lock()
        } finally {
            unlockLock.unlock()
        }

        assertEquals(AuthResult.INTERRUPTED, unlocking.await())
        assertFalse(viewModel.isUnlocked.value)
        assertFalse(crypto.hasKey())
        assertEquals("A right password is never a failed attempt", AuthResult.SUCCESS, viewModel.unlockWithPassword(masterPassword))
        assertTrue(viewModel.isUnlocked.value)
    }

    @Test
    fun aLockWhileTheBiometricUnlockRuns_keepsTheVaultLocked() = runBlocking {
        val dek = crypto.getSoftwareDek()!!
        viewModel.lock()
        val unlockLock = com.example.security.VaultUnlockLock.mutex
        unlockLock.lock()
        val unlocking = async(Dispatchers.Default) { viewModel.unlockWithBiometrics(dek.clone()) }
        try {
            assertTrue(eventually { viewModel.isUnlocking.value })
            viewModel.lock()
        } finally {
            unlockLock.unlock()
        }

        assertFalse(unlocking.await())
        assertFalse(viewModel.isUnlocked.value)
        assertFalse(crypto.hasKey())
    }

    @Test
    fun theStepsAfterAPasswordUnlock_finishEvenThoughTheLockScreensScopeIsCancelled() = runBlocking {
        seed()
        // An old recycle-bin row for the cleanup, stale stats, and a failed attempt to reset.
        val binned = repo.getAllEntriesSync().first { it.syncId == "id-bin" }
        dao.softDeleteEntry(binned.id, System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        viewModel.lock()
        app.container.settingsRepository.saveSecurityStatsSummary(SecurityStatsSummary.Empty)
        assertEquals(AuthResult.INVALID_PASSWORD, viewModel.unlockWithPassword("wrong password"))
        assertEquals(1, app.container.settingsRepository.readStoredPreferencesOrThrow()[SettingsRepository.FAILED_AUTH_ATTEMPTS])

        // Like LockScreen: the vault opening removes the screen, which cancels its scope.
        val lockScreenScope = CoroutineScope(Dispatchers.Default + Job())
        viewModel.vaultOpenedHook = { lockScreenScope.cancel() }
        val unlocking = lockScreenScope.launch { viewModel.unlockWithPassword(masterPassword) }
        unlocking.join()
        viewModel.vaultOpenedHook = null

        assertTrue("The caller was cancelled", unlocking.isCancelled)
        assertTrue(viewModel.isUnlocked.value)
        viewModel.awaitSecurityStatsRecalculation()
        assertEquals("Stats recalculated", 1, app.container.settingsRepository.securityStatsSummary.first()?.totalPasswords)
        assertNull("Failed attempts reset", app.container.settingsRepository.readStoredPreferencesOrThrow()[SettingsRepository.FAILED_AUTH_ATTEMPTS])
        assertNull("Recycle bin cleaned up", dao.getEntryBySyncId("id-bin"))
    }

    @Test
    fun theStepsAfterABiometricUnlock_finishEvenThoughTheCallersScopeIsCancelled() = runBlocking {
        seed()
        val dek = crypto.getSoftwareDek()!!
        viewModel.lock()
        app.container.settingsRepository.saveSecurityStatsSummary(SecurityStatsSummary.Empty)
        assertEquals(AuthResult.INVALID_PASSWORD, viewModel.unlockWithPassword("wrong password"))

        val callerScope = CoroutineScope(Dispatchers.Default + Job())
        viewModel.vaultOpenedHook = { callerScope.cancel() }
        val unlocking = callerScope.launch { viewModel.unlockWithBiometrics(dek) }
        unlocking.join()
        viewModel.vaultOpenedHook = null

        assertTrue(viewModel.isUnlocked.value)
        viewModel.awaitSecurityStatsRecalculation()
        assertEquals(2, app.container.settingsRepository.securityStatsSummary.first()?.totalPasswords)
        assertNull(app.container.settingsRepository.readStoredPreferencesOrThrow()[SettingsRepository.FAILED_AUTH_ATTEMPTS])
    }

    @Test
    fun aBiometricUnlockOfAnOpenVault_changesNothing() = runBlocking {
        seed()
        val state = crypto.keyState()
        val otherKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

        assertTrue(viewModel.unlockWithBiometrics(otherKey))

        assertEquals("Still the same key: nothing running under it looks locked", state, crypto.keyState())
        assertEquals(2, repo.getAllEntriesSync().count { !it.isDecryptionFailed })
    }

    // --- Exports (O4) ---

    @Test
    fun exports_leaveOutUnreadableEntries_andCountThem() = runBlocking {
        seed()
        insertUnreadableRow()
        // A write refreshes the list, so the export sees the unreadable row too.
        repo.insertEntry(VaultEntry(syncId = "id-third", title = "Third", password = "t"))
        assertEquals(1, repo.getAllEntriesSync().count { it.isDecryptionFailed })

        val json = viewModel.generateExportPayload("json", "")
        assertEquals(1, json.skippedEntries)
        val text = String(json.bytes, Charsets.UTF_8)
        assertFalse(text.contains("Decryption Failed"))
        assertTrue(text.contains("Keep") && text.contains("Third"))

        val txt = viewModel.generateExportPayload("txt", "")
        assertEquals(1, txt.skippedEntries)
        assertFalse(String(txt.bytes, Charsets.UTF_8).contains("Decryption Failed"))
        assertFalse(String(viewModel.generateTxtExportPayload(), Charsets.UTF_8).contains("Decryption Failed"))
    }

    private suspend fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(10)
        }
        return condition()
    }

    /** A live row this vault's key can't read (e.g. still under a legacy key). */
    private suspend fun insertUnreadableRow() {
        val otherKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, otherKey, GCMParameterSpec(128, iv)) }
        val titleEnc = Base64.encodeToString(iv + cipher.doFinal("Legacy".toByteArray()), Base64.NO_WRAP)
        dao.insertEntry(
            VaultEntryEntity(
                titleEnc = titleEnc, usernameEnc = "", passwordEnc = "", websiteEnc = "", notesEnc = "",
                categoryEnc = "", tagsEnc = "", customFieldsEnc = "",
                isFavorite = false, timestamp = 1L, isDeleted = false, deletedAt = null, syncId = UUID.randomUUID().toString()
            )
        )
    }

    // --- Writes while locked ---

    @Test
    fun updateEntry_whileLocked_throwsAndLeavesTheRowAlone() = runBlocking {
        seed()
        val entry = repo.getAllEntriesSync().first { it.syncId == "id-keep" }
        val before = storedState()
        repo.clearSoftwareDek()

        assertThrows(VaultLockedException::class.java) {
            runBlocking { repo.updateEntry(entry.copy(password = "changed")) }
        }
        assertThrows(VaultLockedException::class.java) {
            runBlocking { repo.updateEntries(listOf(entry.copy(password = "changed"))) }
        }
        assertEquals(before, storedState())
    }

    @Test
    fun insertEntries_whileLocked_throwsAndInsertsNothing() = runBlocking {
        seed()
        val before = storedState()
        repo.clearSoftwareDek()

        assertThrows(VaultLockedException::class.java) {
            runBlocking { repo.insertEntries((1..3).map { VaultEntry(title = "Imported $it", password = "p$it") }) }
        }
        assertThrows(VaultLockedException::class.java) {
            runBlocking { repo.insertEntry(VaultEntry(title = "One more", password = "p")) }
        }
        assertEquals(before, storedState())
    }

    @Test
    fun addEntry_whileLocked_savesNothingAndDoesNotCrash() = runBlocking {
        seed()
        val before = storedState()
        viewModel.lock()

        val uncaught = recordUncaughtExceptions {
            val launched = runAndCollectLaunched { viewModel.addEntry(VaultEntry(title = "Late", password = "p")) }
            assertTrue("addEntry runs in the background", launched.isNotEmpty())
            awaitOnMain(launched)
            assertTrue("The save neither crashed nor failed its job", launched.none { it.isCancelled })
        }
        assertEquals("Nothing reaches the uncaught-exception handler", emptyList<Throwable>(), uncaught)
        assertEquals(before, storedState())
    }

    @Test
    fun updateEntry_inTheViewModel_whileLocked_savesNothingAndDoesNotCrash() = runBlocking {
        seed()
        val entry = repo.getAllEntriesSync().first { it.syncId == "id-keep" }
        val before = storedState()
        viewModel.lock()

        val uncaught = recordUncaughtExceptions {
            val launched = runAndCollectLaunched { viewModel.updateEntry(entry.copy(password = "changed")) }
            assertTrue(launched.isNotEmpty())
            awaitOnMain(launched)
            assertTrue(launched.none { it.isCancelled })
        }
        assertEquals(emptyList<Throwable>(), uncaught)
        assertEquals(before, storedState())
    }

    /** The view-model coroutines [action] launched. */
    private fun runAndCollectLaunched(action: () -> Unit): List<Job> {
        val before = viewModel.viewModelScope.coroutineContext.job.children.toSet()
        action()
        return viewModel.viewModelScope.coroutineContext.job.children.filter { it !in before }.toList()
    }

    /** Joins [jobs] that resume on the main looper, which this (test) thread has to run. */
    private fun awaitOnMain(jobs: List<Job>) {
        val deadline = System.currentTimeMillis() + 10_000
        while (jobs.any { !it.isCompleted } && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("The background work finished", jobs.all { it.isCompleted })
    }

    /** Runs [block] and returns every throwable that reached an uncaught-exception handler meanwhile. */
    private fun recordUncaughtExceptions(block: () -> Unit): List<Throwable> {
        val recorded = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val recorder = Thread.UncaughtExceptionHandler { _, e -> recorded += e }
        val thread = Thread.currentThread()
        val previousThread = thread.uncaughtExceptionHandler
        val previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        thread.uncaughtExceptionHandler = recorder
        Thread.setDefaultUncaughtExceptionHandler(recorder)
        try {
            block()
        } finally {
            thread.uncaughtExceptionHandler = previousThread
            Thread.setDefaultUncaughtExceptionHandler(previousDefault)
        }
        return recorded.toList()
    }

    // --- Reads while locked ---

    @Test
    fun getAllEntriesSync_whileLocked_isEmpty() = runBlocking {
        seed()
        repo.clearSoftwareDek()
        assertEquals("No \"Decryption Failed\" placeholders", emptyList<VaultEntry>(), repo.getAllEntriesSync())
    }

    @Test
    fun export_whileLocked_isRefused() = runBlocking<Unit> {
        seed()
        viewModel.lock()
        assertThrows(VaultLockedException::class.java) {
            runBlocking { viewModel.generateSimplifiedJsonExportPayload() }
        }
        assertThrows(VaultLockedException::class.java) {
            runBlocking { viewModel.generateTxtExportPayload() }
        }
    }

    @Test
    fun securityStats_whileLocked_keepTheLastSavedOnes() = runBlocking {
        seed()
        viewModel.recalculateSecurityStats()
        viewModel.awaitSecurityStatsRecalculation()
        val saved = app.container.settingsRepository.securityStatsSummary.first()
        assertEquals(2, saved?.totalPasswords)

        viewModel.lock()
        viewModel.recalculateSecurityStats()
        viewModel.awaitSecurityStatsRecalculation()
        val afterLock: SecurityStatsSummary? = app.container.settingsRepository.securityStatsSummary.first()
        assertEquals("Not replaced by empty stats", saved, afterLock)
    }
}
