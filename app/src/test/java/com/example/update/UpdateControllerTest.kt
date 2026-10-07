package com.example.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.example.VaultPassApplication
import com.example.repository.SettingsRepository
import com.example.security.CryptoManager
import com.example.security.PasswordHashHelper
import com.example.security.SecurityPolicy
import com.example.security.VaultSessionManager
import com.example.ui.AuthResult
import com.example.ui.VaultViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.random.Random

/** Update UI state machine: app-open check, Check now, progress, Later, install gate and status handling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var app: VaultPassApplication
    private val updates = FakeUpdates()
    private val installer = FakeInstaller()
    private val logs = mutableListOf<String>()

    private fun info(version: String = "9.0.0", url: String = "https://example.invalid/VaultPass.apk", size: Long = 100, sha: String = "ab".repeat(32)) =
        UpdateInfo(version, "v$version", "VaultPass.apk", url, size, sha)

    private fun controller(
        ops: UpdateOperations = updates,
        checkOnOpen: suspend () -> Boolean = { false },
        enabled: Boolean = true
    ) = UpdateController(
        updates = ops,
        installer = installer,
        checkOnOpen = checkOnOpen,
        scope = CoroutineScope(Dispatchers.Unconfined),
        enabled = enabled,
        runningVersion = "2.7.0",
        log = { logs += it }
    )

    private fun activity(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking { app.container.updateEngine.awaitStartupCleanup() }
    }

    @After
    fun tearDown() = runBlocking {
        app.container.settingsRepository.setCheckUpdatesOnOpen(false)
    }

    // --- settings switch ---

    @Test
    fun checkOnOpenSwitchDefaultsOffAndPersists() = runBlocking {
        val settings = app.container.settingsRepository
        assertFalse(settings.checkUpdatesOnOpen.first())
        settings.setCheckUpdatesOnOpen(true)
        assertTrue(SettingsRepository(app).checkUpdatesOnOpen.first())
        settings.setCheckUpdatesOnOpen(false)
        assertFalse(SettingsRepository(app).checkUpdatesOnOpen.first())
    }

    // --- app open ---

    @Test
    fun noNetworkCheckAtStartupWhenSwitchIsOff() = runBlocking {
        val settings = app.container.settingsRepository
        val c = controller(checkOnOpen = { settings.checkUpdatesOnOpen.first() })
        c.onAppOpen()!!.join()
        assertEquals(0, updates.checkCalls)
        assertEquals(0, updates.prepareCalls)
        assertNull(c.offer.value)
        assertNull(c.dialog.value)
        // Once per process.
        assertNull(c.onAppOpen())
    }

    @Test
    fun checkAtStartupWhenOnRunsInBackgroundAndDoesNotBlockUnlock() = runBlocking {
        val settings = app.container.settingsRepository
        settings.setCheckUpdatesOnOpen(true)
        val gate = CompletableDeferred<UpdateCheckResult>()
        val started = CompletableDeferred<Unit>()
        updates.check = { started.complete(Unit); gate.await() }
        val c = controller(checkOnOpen = { settings.checkUpdatesOnOpen.first() })

        val job = c.onAppOpen()!!
        withTimeout(10_000) { started.await() }
        assertEquals(1, updates.checkCalls)
        assertFalse("check still running", job.isCompleted)

        // Unlock works while the check hangs.
        val password = "Update-Check-Pass-1!"
        createVault(password)
        val vm = VaultViewModel(app.container.vaultRepository, settings)
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
        assertTrue(vm.isUnlocked.value)
        assertFalse(job.isCompleted)
        assertNull(c.offer.value)
        vm.lock()

        val available = info()
        gate.complete(UpdateCheckResult.Available(available))
        job.join()
        assertEquals(UpdateOffer.Download(available), c.offer.value)
        assertEquals(UpdateCheckStatus.Available("9.0.0"), c.checkStatus.value)
        assertNull("nothing pops up at startup", c.dialog.value)
        VaultSessionManager.resetForTesting()
    }

    @Test
    fun startupCheckFailureIsOnlyLogged() = runBlocking {
        updates.check = { UpdateCheckResult.Failed(UpdateFailure.NETWORK, "offline") }
        val c = controller(checkOnOpen = { true })
        c.onAppOpen()!!.join()
        assertNull(c.offer.value)
        assertNull(c.dialog.value)
        assertEquals(UpdateCheckStatus.Idle, c.checkStatus.value)
        assertTrue(logs.single().contains("NETWORK"))
    }

    @Test
    fun pendingUpdateIsOfferedAsInstallWithoutDownloadOrCheck() = runBlocking {
        updates.pending = PendingUpdate("9.0.0", 14, "/data/updates/VaultPass-9.0.0.apk", "ab")
        val c = controller(checkOnOpen = { true })
        c.onAppOpen()!!.join()
        assertEquals(UpdateOffer.Install("9.0.0"), c.offer.value)
        assertEquals(0, updates.checkCalls)
        assertEquals(0, updates.prepareCalls)

        // The banner tap goes straight to the installer.
        c.openOffer()
        assertEquals(1, installer.calls)
        assertEquals(0, updates.prepareCalls)
        assertEquals(UpdateDialog.Installing("9.0.0"), c.dialog.value)
    }

    @Test
    fun pendingUpdateNotNewerThanRunningFallsBackToCheck() = runBlocking {
        updates.pending = PendingUpdate("2.7.0", 14, "/x", "ab")
        val c = controller(checkOnOpen = { true })
        c.onAppOpen()!!.join()
        assertEquals(1, updates.checkCalls)
        assertNull(c.offer.value)
    }

    @Test
    fun disabledBuildDoesNothing() {
        val c = controller(checkOnOpen = { true }, enabled = false)
        assertNull(c.onAppOpen())
        c.checkNow()
        c.startDownload(info())
        c.installPending()
        assertEquals(0, updates.pendingCalls + updates.checkCalls + updates.prepareCalls + installer.calls)
        assertEquals(UpdateCheckStatus.Idle, c.checkStatus.value)
        assertNull(c.dialog.value)
        // Debug builds have no feed (no updater) unless built with -PupdateFeedUrl.
        assertEquals(com.example.BuildConfig.UPDATE_FEED_URL.isNotBlank(), UpdateConfig.isEnabled)
    }

    // --- Check now ---

    @Test
    fun checkNowReportsEachResult() {
        val c = controller()
        updates.check = { UpdateCheckResult.UpToDate("2.7.0") }
        c.checkNow()
        assertEquals(UpdateCheckStatus.UpToDate("2.7.0"), c.checkStatus.value)

        updates.check = { UpdateCheckResult.Failed(UpdateFailure.HTTP_STATUS) }
        c.checkNow()
        assertEquals(UpdateCheckStatus.Failed(UpdateFailure.HTTP_STATUS), c.checkStatus.value)

        val gate = CompletableDeferred<UpdateCheckResult>()
        updates.check = { gate.await() }
        c.checkNow()
        assertEquals(UpdateCheckStatus.Checking, c.checkStatus.value)
        c.checkNow() // ignored while running
        assertEquals(3, updates.checkCalls)
        gate.complete(UpdateCheckResult.Available(info("9.1.0")))
        assertEquals(UpdateCheckStatus.Available("9.1.0"), c.checkStatus.value)
        assertEquals(UpdateOffer.Download(info("9.1.0")), c.offer.value)
    }

    // --- download progress ---

    @Test
    fun downloadShowsProgressThenVerifyingThenReady() {
        val c = controller()
        val seen = mutableListOf<UpdateDialog?>()
        updates.prepare = { info, progress ->
            seen += c.dialog.value
            progress(0, 100); seen += c.dialog.value
            progress(40, 100); seen += c.dialog.value
            progress(100, 100); seen += c.dialog.value
            PrepareResult.Ready(PendingUpdate(info.version, 14, "/x", "ab"), signerChecked = true)
        }
        c.startDownload(info())
        assertEquals(
            listOf(
                UpdateDialog.Downloading("9.0.0", 0, 100),
                UpdateDialog.Downloading("9.0.0", 0, 100),
                UpdateDialog.Downloading("9.0.0", 40, 100),
                UpdateDialog.Verifying("9.0.0")
            ),
            seen
        )
        assertEquals(0.4f, UpdateDialog.Downloading("9.0.0", 40, 100).fraction)
        assertEquals(UpdateDialog.Ready("9.0.0"), c.dialog.value)
        assertEquals(UpdateOffer.Install("9.0.0"), c.offer.value)
    }

    @Test
    fun downloadAndVerificationFailuresOfferRetry() {
        val c = controller()
        updates.prepare = { _, _ -> PrepareResult.DownloadFailed(UpdateFailure.NETWORK, "timeout") }
        c.startDownload(info())
        assertEquals(
            UpdateDialog.Failed("9.0.0", UpdateProblem.Download(UpdateFailure.NETWORK), UpdateRetry.DOWNLOAD),
            c.dialog.value
        )
        updates.prepare = { _, _ -> PrepareResult.VerificationFailed(ApkRejection.FOREIGN_SIGNER, null) }
        c.retry()
        assertEquals(2, updates.prepareCalls)
        assertEquals(
            UpdateDialog.Failed("9.0.0", UpdateProblem.Verification(ApkRejection.FOREIGN_SIGNER), UpdateRetry.DOWNLOAD),
            c.dialog.value
        )
        c.dismissDialog()
        assertNull(c.dialog.value)
    }

    @Test
    fun cancelDownloadHidesTheDialog() {
        val c = controller()
        val gate = CompletableDeferred<PrepareResult>()
        updates.prepare = { _, _ -> gate.await() }
        c.startDownload(info())
        assertEquals(UpdateDialog.Downloading("9.0.0", 0, 100), c.dialog.value)
        c.cancelDownload()
        assertNull(c.dialog.value)
        assertTrue(gate.isActive)
    }

    @Test
    fun restartAfterCancelWaitsForTheCancelledDownloadToFinish() {
        val c = controller()
        val stuck = CompletableDeferred<PrepareResult>()
        val second = CompletableDeferred<PrepareResult>()
        val seen = mutableListOf<UpdateDialog?>()
        updates.prepare = { _, _ ->
            if (updates.prepareCalls == 1) {
                // Like a read() that cancellation can't interrupt.
                withContext(NonCancellable) { stuck.await() }
            } else {
                second.await()
            }
        }
        c.startDownload(info())
        c.cancelDownload()
        assertNull(c.dialog.value)

        c.startDownload(info())
        assertEquals("the new download must wait for the cancelled one", 1, updates.prepareCalls)
        assertEquals(UpdateDialog.Downloading("9.0.0", 0, 100), c.dialog.value)

        // The cancelled attempt finishes late: its result is dropped and it doesn't touch the new dialog.
        stuck.complete(PrepareResult.Ready(PendingUpdate("9.0.0", 14, "/old", "ab"), signerChecked = true))
        seen += c.dialog.value
        assertEquals(2, updates.prepareCalls)
        assertEquals(listOf<UpdateDialog?>(UpdateDialog.Downloading("9.0.0", 0, 100)), seen)
        assertNull("no offer from the cancelled attempt", c.offer.value)

        second.complete(PrepareResult.Ready(PendingUpdate("9.0.0", 14, "/new", "ab"), signerChecked = true))
        assertEquals(UpdateDialog.Ready("9.0.0"), c.dialog.value)
        assertEquals(UpdateOffer.Install("9.0.0"), c.offer.value)
    }

    // --- Later, with the real engine, downloader, store and verifier ---

    @Test
    fun laterKeepsTheFileAndTheNextOpenOffersInstallWithoutDownloading() = runBlocking {
        val apk = Random(3).nextBytes(64 * 1024)
        val sha = MessageDigest.getInstance("SHA-256").digest(apk).toHex()
        val dir = File(tmp.root, "updates")
        val prefs = app.getSharedPreferences("update_controller_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        UpdateTestServer().use { server ->
            server.body("/VaultPass.apk", apk)
            fun engine() = realEngine(dir, prefs, "${server.base}/feed")
            val available = info(url = "${server.base}/VaultPass.apk", size = apk.size.toLong(), sha = sha)

            val first = controller(ops = engine())
            first.startDownload(available)
            val ready = withTimeout(10_000) { first.dialog.first { it is UpdateDialog.Ready || it is UpdateDialog.Failed } }
            assertEquals(UpdateDialog.Ready("9.0.0"), ready)

            first.later()
            assertNull(first.dialog.value)
            assertEquals(UpdateOffer.Install("9.0.0"), first.offer.value)
            val file = File(dir, "VaultPass-9.0.0.apk")
            assertTrue(file.exists())

            // Next app open (new process): "Install update" from the saved file, no network at all.
            val requestsBefore = server.requests.size
            val next = controller(ops = engine(), checkOnOpen = { true })
            next.onAppOpen()!!.join()
            assertEquals(UpdateOffer.Install("9.0.0"), next.offer.value)
            assertEquals(requestsBefore, server.requests.size)
            assertTrue(file.exists())
        }
    }

    // --- install: unknown-sources gate ---

    @Test
    fun unknownSourcesGateExplainsOpensSettingsAndContinuesOnReturn() {
        val c = controller()
        updates.pending = PendingUpdate("9.0.0", 14, "/x", "ab")
        runBlocking { c.onAppOpen()!!.join() }
        installer.canRequest = false

        c.installPending()
        assertEquals(UpdateDialog.NeedsInstallPermission("9.0.0"), c.dialog.value)
        assertEquals(0, installer.calls)

        val activity = activity()
        c.onActivityResumed(activity)
        c.openInstallPermissionSettings()
        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, opened.action)
        assertEquals("package:${app.packageName}", opened.dataString)

        // Back without allowing it.
        c.onActivityPaused(activity)
        c.onActivityResumed(activity)
        assertEquals(
            UpdateDialog.Failed("9.0.0", UpdateProblem.InstallPermissionMissing, UpdateRetry.INSTALL),
            c.dialog.value
        )
        c.retry()
        assertEquals(UpdateDialog.NeedsInstallPermission("9.0.0"), c.dialog.value)

        // Allowed this time: the install continues by itself on return.
        c.openInstallPermissionSettings()
        installer.canRequest = true
        c.onActivityPaused(activity)
        c.onActivityResumed(activity)
        assertEquals(1, installer.calls)
        assertEquals(true, installer.lastForeground)
        assertEquals(UpdateDialog.Installing("9.0.0"), c.dialog.value)
    }

    @Test
    fun installOutcomesBeforeAndroidTakesOver() {
        val c = controller()
        updates.pending = PendingUpdate("9.0.0", 14, "/x", "ab")
        runBlocking { c.onAppOpen()!!.join() }

        installer.result = InstallStart.NoPendingUpdate
        c.installPending()
        assertEquals(UpdateDialog.Failed("9.0.0", UpdateProblem.PendingUpdateGone, UpdateRetry.NONE), c.dialog.value)
        assertNull("nothing left to install", c.offer.value)

        updates.pending = PendingUpdate("9.0.0", 14, "/x", "ab")
        val again = controller()
        runBlocking { again.onAppOpen()!!.join() }
        installer.result = InstallStart.NotForeground
        again.installPending()
        assertEquals(UpdateDialog.Ready("9.0.0"), again.dialog.value)
        assertEquals(false, installer.lastForeground)

        installer.result = InstallStart.Failed("io")
        again.installPending()
        assertEquals(UpdateDialog.Failed("9.0.0", UpdateProblem.InstallStartFailed, UpdateRetry.INSTALL), again.dialog.value)
    }

    // --- installer status (what UpdateInstallReceiver forwards) ---

    @Test
    fun failedOrAbortedInstallKeepsThePendingUpdateForRetry() {
        val apk = File(tmp.root, "VaultPass-9.0.0.apk").apply { writeText("apk") }
        updates.pending = PendingUpdate("9.0.0", 14, apk.path, "ab")
        val c = controller()
        runBlocking { c.onAppOpen()!!.join() }
        installer.result = InstallStart.Committed(7, "9.0.0")
        c.installPending()
        assertEquals(UpdateDialog.Installing("9.0.0"), c.dialog.value)

        // A status for some older session is ignored.
        c.onInstallStatus(99, PackageInstaller.STATUS_FAILURE, "old", null)
        assertEquals(UpdateDialog.Installing("9.0.0"), c.dialog.value)

        c.onInstallStatus(7, PackageInstaller.STATUS_FAILURE_ABORTED, null, null)
        assertEquals(UpdateDialog.Failed("9.0.0", UpdateProblem.InstallCancelled, UpdateRetry.INSTALL), c.dialog.value)
        assertEquals(UpdateOffer.Install("9.0.0"), c.offer.value)
        assertTrue(apk.exists())

        c.retry()
        assertEquals(2, installer.calls)
        c.onInstallStatus(7, PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE", null)
        assertEquals(UpdateDialog.Failed("9.0.0", UpdateProblem.SignedWithDifferentKey, UpdateRetry.INSTALL), c.dialog.value)

        // INCOMPATIBLE is not a key problem: this phone can't install this build.
        c.retry()
        c.onInstallStatus(7, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, null, null)
        assertEquals(
            UpdateDialog.Failed("9.0.0", UpdateProblem.IncompatibleWithDevice, UpdateRetry.INSTALL),
            c.dialog.value
        )
        assertEquals(UpdateOffer.Install("9.0.0"), c.offer.value)

        c.retry()
        c.onInstallStatus(7, PackageInstaller.STATUS_FAILURE, "disk on fire", null)
        assertEquals(UpdateProblem.InstallFailed("disk on fire"), (c.dialog.value as UpdateDialog.Failed).problem)
        c.retry()
        c.onInstallStatus(7, PackageInstaller.STATUS_FAILURE_STORAGE, null, null)
        assertEquals(UpdateProblem.InstallStorage, (c.dialog.value as UpdateDialog.Failed).problem)
        c.retry()
        c.onInstallStatus(7, PackageInstaller.STATUS_FAILURE_BLOCKED, null, null)
        assertEquals(UpdateProblem.InstallBlocked, (c.dialog.value as UpdateDialog.Failed).problem)
        assertTrue(apk.exists())
        assertEquals(UpdateOffer.Install("9.0.0"), c.offer.value)

        // Success: nothing to do, Android replaces and closes the app.
        c.retry()
        c.onInstallStatus(7, PackageInstaller.STATUS_SUCCESS, null, null)
        assertEquals(UpdateDialog.Installing("9.0.0"), c.dialog.value)
    }

    @Test
    fun pendingUserActionWithoutConfirmationIntentIsAnError() {
        val c = controller()
        c.onInstallStatus(1, PackageInstaller.STATUS_PENDING_USER_ACTION, null, null)
        assertEquals(UpdateProblem.ConfirmationUnavailable, (c.dialog.value as UpdateDialog.Failed).problem)
        assertNotNull(c.dialog.value)
    }

    // --- helpers ---

    private fun realEngine(dir: File, prefs: android.content.SharedPreferences, feedUrl: String): UpdateEngine {
        val local = UpdateUrlPolicy(allowLocalhostHttp = true)
        val mine = SignerSet(setOf("aa"))
        val source = object : PackageInfoSource {
            override fun installed() = PackageSnapshot(app.packageName, 13, "2.7.0", mine)
            override fun archive(file: File) = PackageSnapshot(app.packageName, 14, "9.0.0", mine)
        }
        return UpdateEngine(
            UpdateChecker(feedUrl, "2.7.0", local, "test", 5_000),
            UpdateDownloader(dir, dir.parentFile!!, local, "test", 5_000),
            ApkVerifier(source),
            PendingUpdateStore(prefs, dir, dir.parentFile!!),
            runningVersionCode = 13
        )
    }

    private suspend fun createVault(password: String) = withContext(Dispatchers.IO) {
        VaultSessionManager.resetForTesting()
        app.container.appDatabase.clearAllTables()
        app.getSharedPreferences("vaultpass_sync_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = app.container.settingsRepository
        settings.resetFailedAttempts()
        val iterations = SecurityPolicy.CURRENT_KDF_ITERATIONS
        val algorithm = SecurityPolicy.CURRENT_KDF_ALGORITHM
        val salt = PasswordHashHelper.generateSalt()
        settings.saveMasterPasswordAndKdfMetadata(
            PasswordHashHelper.hashPassword(password, salt, iterations, algorithm), salt,
            SecurityPolicy.CURRENT_KDF_VERSION, iterations, algorithm
        )
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val kek = PasswordHashHelper.deriveMasterKey(password, salt, iterations, algorithm)
        settings.saveDekMpWrappedSync(CryptoManager.wrapDekWithKek(dek, kek))
    }

    class FakeUpdates : UpdateOperations {
        var pending: PendingUpdate? = null
        var check: suspend () -> UpdateCheckResult = { UpdateCheckResult.UpToDate("2.7.0") }
        var prepare: suspend (UpdateInfo, suspend (Long, Long) -> Unit) -> PrepareResult =
            { _, _ -> PrepareResult.DownloadFailed(UpdateFailure.NETWORK, null) }
        var checkCalls = 0
        var prepareCalls = 0
        var pendingCalls = 0

        override suspend fun check(): UpdateCheckResult {
            checkCalls++
            return check.invoke()
        }

        override suspend fun prepare(info: UpdateInfo, onProgress: suspend (bytes: Long, total: Long) -> Unit): PrepareResult {
            prepareCalls++
            return prepare.invoke(info, onProgress)
        }

        override suspend fun pendingUpdate(): PendingUpdate? {
            pendingCalls++
            return pending
        }
    }

    class FakeInstaller : UpdateInstallOperations {
        var canRequest = true
        var result: InstallStart = InstallStart.Committed(1, "9.0.0")
        var calls = 0
        var lastForeground: Boolean? = null

        override fun canRequestInstalls() = canRequest

        override suspend fun install(isForeground: () -> Boolean): InstallStart {
            calls++
            lastForeground = isForeground()
            return result
        }
    }
}
