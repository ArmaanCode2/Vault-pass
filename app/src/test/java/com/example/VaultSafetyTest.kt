package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.data.models.VaultEntryEntity
import com.example.domain.models.VaultEntry
import com.example.repository.MasterHashRead
import com.example.repository.SettingsRepository
import com.example.security.VaultSessionManager
import com.example.ui.AuthResult
import com.example.ui.SetupResult
import com.example.ui.VaultLaunchState
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
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** M2 (recycle-bin cleanup never deletes rows it can't read) and M3 (a failed vault creation leaves nothing behind). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultSafetyTest {

    private lateinit var app: VaultPassApplication
    private val settings get() = app.container.settingsRepository
    private val repo get() = app.container.vaultRepository
    private val dao get() = app.container.appDatabase.vaultDao()
    private val prefs get() = app.getSharedPreferences("vaultpass_sync_prefs", Context.MODE_PRIVATE)
    private val viewModels = mutableListOf<VaultViewModel>()

    private val password = "Brand-New-Pass-99!"
    private val eightDaysAgo get() = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        VaultSessionManager.resetForTesting()
        withContext(Dispatchers.IO) { app.container.appDatabase.clearAllTables() }
        repo.clearSoftwareDek()
        prefs.edit().clear().commit()
        settings.clearMasterPasswordAndKdfMetadata()
        settings.resetFailedAttempts()
    }

    @After
    fun tearDown() = runBlocking {
        viewModels.forEach { it.awaitSecurityStatsRecalculation() }
        repo.clearSoftwareDek()
        VaultSessionManager.resetForTesting()
        settings.resetFailedAttempts()
    }

    private fun viewModel(saveNewVaultKey: ((String) -> Boolean)? = null): VaultViewModel =
        if (saveNewVaultKey == null) {
            VaultViewModel(repo, settings)
        } else {
            VaultViewModel(repo, settings, saveNewVaultKey = saveNewVaultKey)
        }.also { viewModels += it }

    private suspend fun unlockedVault(): VaultViewModel = viewModel().also {
        assertEquals(SetupResult.CREATED, it.setupMasterPasswordSync(password))
        assertTrue(it.isUnlocked.first())
    }

    private suspend fun insert(title: String): VaultEntry {
        repo.insertEntry(VaultEntry(title = title, password = "pw-$title"))
        return repo.getAllEntriesSync().first { it.title == title }
    }

    /** A recycle-bin row this vault's key can't read (e.g. still under a legacy key). */
    private suspend fun insertUnreadableBinnedRow(deletedAt: Long): VaultEntryEntity {
        val otherKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, otherKey, GCMParameterSpec(128, iv)) }
        val titleEnc = Base64.encodeToString(iv + cipher.doFinal("Legacy".toByteArray()), Base64.NO_WRAP)
        val syncId = UUID.randomUUID().toString()
        dao.insertEntry(
            VaultEntryEntity(
                titleEnc = titleEnc, usernameEnc = "", passwordEnc = "", websiteEnc = "", notesEnc = "",
                categoryEnc = "", tagsEnc = "", customFieldsEnc = "",
                isFavorite = false, timestamp = 1L, isDeleted = true, deletedAt = deletedAt, syncId = syncId
            )
        )
        return dao.getEntryBySyncId(syncId)!!
    }

    // --- M2 ---

    @Test
    fun recycleBinCleanup_keepsOldUndecryptableRows_andNeverTombstonesThem() = runBlocking {
        unlockedVault()
        val oldReadable = insert("Old readable")
        dao.softDeleteEntry(oldReadable.id, eightDaysAgo)
        val recent = insert("Recent")
        repo.deleteEntry(recent.id)
        val unreadable = insertUnreadableBinnedRow(eightDaysAgo)

        assertEquals("Only the old readable row is purged", 1, repo.cleanupRecycleBin())

        assertNull(dao.getEntryBySyncId(oldReadable.syncId))
        assertNotNull("Too recent to purge", dao.getEntryBySyncId(recent.syncId))
        assertEquals("Undecryptable row kept byte-for-byte", unreadable, dao.getEntryBySyncId(unreadable.syncId))
        val tombstoned = dao.getAllTombstones().map { it.syncId }
        assertEquals("Only the purged row is tombstoned", listOf(oldReadable.syncId), tombstoned)

        // Running again changes nothing.
        assertEquals(0, repo.cleanupRecycleBin())
        assertEquals(unreadable, dao.getEntryBySyncId(unreadable.syncId))
        assertEquals(listOf(oldReadable.syncId), dao.getAllTombstones().map { it.syncId })
    }

    @Test
    fun recycleBinCleanup_whileLocked_doesNothing() = runBlocking {
        val vm = unlockedVault()
        val oldReadable = insert("Old readable")
        dao.softDeleteEntry(oldReadable.id, eightDaysAgo)
        val unreadable = insertUnreadableBinnedRow(eightDaysAgo)
        vm.lock()

        // Without the key every row would look unreadable: cleanup must not run at all.
        assertEquals(0, repo.cleanupRecycleBin())
        assertNotNull(dao.getEntryBySyncId(oldReadable.syncId))
        assertEquals(unreadable, dao.getEntryBySyncId(unreadable.syncId))
        assertTrue(dao.getAllTombstones().isEmpty())

        // A view model created while locked (its init runs the cleanup) doesn't purge either.
        viewModel()
        ShadowLooper.idleMainLooper()
        assertNotNull(dao.getEntryBySyncId(oldReadable.syncId))

        // After unlocking, the readable one goes.
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
        repo.cleanupRecycleBin()
        assertNull(dao.getEntryBySyncId(oldReadable.syncId))
        assertEquals(unreadable, dao.getEntryBySyncId(unreadable.syncId))
        assertFalse(dao.getAllTombstones().any { it.syncId == unreadable.syncId })
    }

    // --- M3 ---

    /** The launch state a freshly started screen would show (its view model reads it in the background). */
    private fun launchStateOfNewScreen(): VaultLaunchState? {
        val vm = viewModel()
        repeat(200) {
            ShadowLooper.idleMainLooper()
            vm.launchState.value?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    @Test
    fun setup_failedKeySave_notUnlocked_rolledBack_setupShownAgain() = runBlocking {
        val failing = viewModel(saveNewVaultKey = { false })
        assertEquals(SetupResult.FAILED, failing.setupMasterPasswordSync(password))

        assertFalse("Not unlocked", failing.isUnlocked.first())
        assertNull("No DEK in memory", app.container.cryptoManager.getSoftwareDek())
        assertNull(settings.getDekMpWrappedSync())
        assertFalse(settings.hasStoredVaultKeySync())
        val stored = settings.readStoredPreferencesOrThrow()
        assertNull("Master hash rolled back", stored[SettingsRepository.MASTER_HASH])
        assertNull(stored[SettingsRepository.MASTER_SALT])
        assertNull(stored[SettingsRepository.MASTER_KDF_VERSION])
        assertNull(stored[SettingsRepository.MASTER_KDF_ITERATIONS])
        assertNull(stored[SettingsRepository.MASTER_KDF_ALGORITHM])
        assertEquals(MasterHashRead.ABSENT, settings.masterHashRead.first())
        assertEquals("Next launch shows Setup", VaultLaunchState.SETUP, launchStateOfNewScreen())

        // Setup works again once saving works.
        val vm = viewModel()
        assertEquals(SetupResult.CREATED, vm.setupMasterPasswordSync(password))
        assertTrue(vm.isUnlocked.first())
        vm.lock()
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
    }
}
