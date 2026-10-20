package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.repository.SettingsRepository
import com.example.security.CryptoManager
import com.example.security.PasswordHashHelper
import com.example.security.SecurityPolicy
import com.example.security.VaultSessionManager
import com.example.ui.AuthResult
import com.example.ui.VaultViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.security.SecureRandom

/** F13: the KDF upgrade finishes even though opening the vault cancels the lock screen's scope. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KdfUpgradeTest {

    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel
    private val settings get() = app.container.settingsRepository
    private val password = "Upgrade-Me-42!"
    // Below CURRENT_KDF_ITERATIONS (and small, so the test stays fast): the unlock must upgrade it.
    private val oldIterations = 2000
    private val algorithm = "PBKDF2WithHmacSHA256"
    private lateinit var oldSalt: String
    private val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        VaultSessionManager.resetForTesting()
        withContext(Dispatchers.IO) { app.container.appDatabase.clearAllTables() }
        app.container.vaultRepository.clearSoftwareDek()
        app.getSharedPreferences("vaultpass_sync_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        settings.resetFailedAttempts()

        // A vault from before the 300k default: its key wrapped with the old KEK, old parameters stored.
        oldSalt = PasswordHashHelper.generateSalt()
        settings.saveMasterPasswordAndKdfMetadata(
            PasswordHashHelper.hashPassword(password, oldSalt, oldIterations, algorithm), oldSalt, 1, oldIterations, algorithm
        )
        val oldKek = PasswordHashHelper.deriveMasterKey(password, oldSalt, oldIterations, algorithm)
        assertTrue(settings.saveDekMpWrappedSync(CryptoManager.wrapDekWithKek(dek, oldKek)))

        viewModel = VaultViewModel(app.container.vaultRepository, settings)
        viewModel.lock()
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() = runBlocking {
        viewModel.vaultOpenedHook = null
        viewModel.awaitSecurityStatsRecalculation()
        app.container.vaultRepository.clearSoftwareDek()
        VaultSessionManager.resetForTesting()
        settings.resetFailedAttempts()
    }

    @Test
    fun upgradeCompletes_whenTheLockScreensScopeIsCancelled() = runBlocking {
        // Like LockScreen: the vault opening removes the screen, which cancels the scope the unlock runs in.
        val lockScreenScope = CoroutineScope(Dispatchers.Default + Job())
        viewModel.vaultOpenedHook = { lockScreenScope.cancel() }
        val unlocking = lockScreenScope.launch { viewModel.unlockWithPassword(password) }
        unlocking.join()
        viewModel.vaultOpenedHook = null
        assertTrue("The caller was cancelled", unlocking.isCancelled)
        assertTrue(viewModel.isUnlocked.value)

        val stored = settings.readStoredPreferencesOrThrow()
        assertEquals(SecurityPolicy.CURRENT_KDF_ITERATIONS, stored[SettingsRepository.MASTER_KDF_ITERATIONS])
        val newSalt = stored[SettingsRepository.MASTER_SALT]!!
        assertNotEquals("The upgrade uses a fresh salt", oldSalt, newSalt)
        val newKek = PasswordHashHelper.deriveMasterKey(password, newSalt, SecurityPolicy.CURRENT_KDF_ITERATIONS, SecurityPolicy.CURRENT_KDF_ALGORITHM)
        assertArrayEquals("Same vault key, wrapped with the new KEK", dek, CryptoManager.unwrapDekWithKek(settings.getDekMpWrappedSync()!!, newKek))
        assertNull("Nothing left pending", settings.getPendingDekMpWrappedV2Sync())

        // The next unlock works with the upgraded parameters.
        viewModel.lock()
        ShadowLooper.idleMainLooper()
        assertEquals(AuthResult.SUCCESS, viewModel.unlockWithPassword(password))
        assertArrayEquals(dek, app.container.vaultRepository.getSoftwareDek())
    }

    @Test
    fun aCrashAfterPrepare_keepsTheOldParametersWorking_andTheNextUnlockUpgrades() = runBlocking {
        // State after step 1 only: a pending wrap under a KEK whose parameters never got committed.
        val strayKek = PasswordHashHelper.deriveMasterKey(password, PasswordHashHelper.generateSalt(), 2000, algorithm)
        assertTrue(settings.savePendingDekMpWrappedV2Sync(CryptoManager.wrapDekWithKek(dek, strayKek)))

        assertEquals(AuthResult.SUCCESS, viewModel.unlockWithPassword(password))
        assertArrayEquals(dek, app.container.vaultRepository.getSoftwareDek())
        val stored = settings.readStoredPreferencesOrThrow()
        assertEquals("Upgraded on this unlock", SecurityPolicy.CURRENT_KDF_ITERATIONS, stored[SettingsRepository.MASTER_KDF_ITERATIONS])
        val newKek = PasswordHashHelper.deriveMasterKey(
            password, stored[SettingsRepository.MASTER_SALT]!!, SecurityPolicy.CURRENT_KDF_ITERATIONS, SecurityPolicy.CURRENT_KDF_ALGORITHM
        )
        assertArrayEquals(dek, CryptoManager.unwrapDekWithKek(settings.getDekMpWrappedSync()!!, newKek))
        assertNull("The stray pending wrap is replaced and then cleared", settings.getPendingDekMpWrappedV2Sync())
    }

    @Test
    fun aCrashAfterCommit_isFinishedByTheNextUnlock() = runBlocking {
        // State after step 2 of the upgrade: new parameters stored, the new wrap only in the pending copy.
        val newSalt = PasswordHashHelper.generateSalt()
        val root = PasswordHashHelper.deriveRootMasterKey(password, newSalt, SecurityPolicy.CURRENT_KDF_ITERATIONS, SecurityPolicy.CURRENT_KDF_ALGORITHM)
        val newKek = PasswordHashHelper.deriveKek(root)
        assertTrue(settings.savePendingDekMpWrappedV2Sync(CryptoManager.wrapDekWithKek(dek, newKek)))
        settings.saveMasterPasswordAndKdfMetadata(
            PasswordHashHelper.deriveAuthHash(root), newSalt, SecurityPolicy.CURRENT_KDF_VERSION,
            SecurityPolicy.CURRENT_KDF_ITERATIONS, SecurityPolicy.CURRENT_KDF_ALGORITHM
        )

        assertEquals(AuthResult.SUCCESS, viewModel.unlockWithPassword(password))
        assertArrayEquals(dek, app.container.vaultRepository.getSoftwareDek())
        assertArrayEquals(dek, CryptoManager.unwrapDekWithKek(settings.getDekMpWrappedSync()!!, newKek))
        assertNull(settings.getPendingDekMpWrappedV2Sync())
    }
}
