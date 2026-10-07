package com.example.update

import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.example.VaultPassApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** PackageInstaller session, the status receiver, the manifest entries and the startup cleanup thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateInstallTest {

    private lateinit var app: VaultPassApplication
    private val packageInstaller get() = app.packageManager.packageInstaller

    private class FixedPending(var pending: PendingUpdate?) : UpdateOperations {
        var pendingCalls = 0
        override suspend fun check(): UpdateCheckResult = UpdateCheckResult.UpToDate(null)
        override suspend fun prepare(info: UpdateInfo, onProgress: suspend (bytes: Long, total: Long) -> Unit): PrepareResult =
            throw UnsupportedOperationException()
        override suspend fun pendingUpdate(): PendingUpdate? {
            pendingCalls++
            return pending
        }
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking { app.container.updateEngine.awaitStartupCleanup() }
    }

    private fun savedApk(content: String = "apk-bytes"): PendingUpdate {
        val dir = UpdateFiles.updatesDir(app).apply { mkdirs() }
        val file = File(dir, "VaultPass-9.0.0.apk").apply { writeText(content) }
        return PendingUpdate("9.0.0", 14, file.path, UpdateFiles.sha256(file))
    }

    private fun activity(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun statusIntent(status: Int, confirmation: Intent? = null, sessionId: Int = 5) =
        Intent(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            .putExtra(PackageInstaller.EXTRA_STATUS, status)
            .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "msg")
            .apply { if (confirmation != null) putExtra(Intent.EXTRA_INTENT, confirmation) }

    private fun confirmIntent() = Intent("android.content.pm.action.CONFIRM_INSTALL")

    // --- installer ---

    @Test
    fun installRefusesWhenThereIsNoValidPendingUpdate() = runBlocking {
        val ops = FixedPending(null)
        val result = UpdateInstaller(app, ops, canRequest = { true }).install { true }
        assertEquals(InstallStart.NoPendingUpdate, result)
        assertEquals(1, ops.pendingCalls)
        assertTrue(packageInstaller.allSessions.isEmpty())
    }

    @Test
    fun installNeedsUnknownSourcesPermissionFirst() = runBlocking {
        val ops = FixedPending(savedApk())
        val installer = UpdateInstaller(app, ops, canRequest = { false })
        assertFalse(installer.canRequestInstalls())
        assertEquals(InstallStart.NeedsPermission, installer.install { true })
        assertEquals(0, ops.pendingCalls)
        assertTrue(packageInstaller.allSessions.isEmpty())
    }

    @Test
    fun installCommitsInTheForeground() = runBlocking {
        val pending = savedApk()
        val result = UpdateInstaller(app, FixedPending(pending), canRequest = { true }).install { true }
        assertTrue("$result", result is InstallStart.Committed)
        assertEquals("9.0.0", (result as InstallStart.Committed).version)
        val session = packageInstaller.getSessionInfo(result.sessionId)
        assertNotNull(session)
        assertEquals(app.packageName, session!!.appPackageName)
        assertTrue("the pending file stays until the new version starts", pending.file.exists())
    }

    @Test
    fun installAbandonsWhenNotInForeground() = runBlocking {
        val result = UpdateInstaller(app, FixedPending(savedApk()), canRequest = { true }).install { false }
        assertEquals(InstallStart.NotForeground, result)
        assertTrue(packageInstaller.allSessions.isEmpty())
    }

    @Test
    fun installAbandonsWhenTheFileChangedAfterVerification() = runBlocking {
        val pending = savedApk().copy(sha256 = "00".repeat(32))
        val result = UpdateInstaller(app, FixedPending(pending), canRequest = { true }).install { true }
        assertEquals(InstallStart.NoPendingUpdate, result)
        assertTrue(packageInstaller.allSessions.isEmpty())
    }

    // --- receiver ---

    @Test
    fun pendingUserActionInForegroundLaunchesConfirmation() {
        val controller = app.container.updateController
        val activity = activity()
        controller.onActivityResumed(activity)
        UpdateInstallReceiver().onReceive(app, statusIntent(PackageInstaller.STATUS_PENDING_USER_ACTION, confirmIntent()))
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", shadowOf(activity).nextStartedActivity?.action)
        assertNull(controller.storedConfirmation)
    }

    @Test
    fun pendingUserActionInBackgroundIsShownOnNextResume() {
        val controller = app.container.updateController
        val activity = activity()
        controller.onActivityResumed(activity)
        controller.onActivityPaused(activity)

        UpdateInstallReceiver().onReceive(app, statusIntent(PackageInstaller.STATUS_PENDING_USER_ACTION, confirmIntent()))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", controller.storedConfirmation?.action)

        controller.onActivityResumed(activity)
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", shadowOf(activity).nextStartedActivity?.action)
        assertNull(controller.storedConfirmation)
    }

    @Test
    fun receiverSuccessConflictAndAbort() {
        val controller = app.container.updateController
        val receiver = UpdateInstallReceiver()

        receiver.onReceive(app, statusIntent(PackageInstaller.STATUS_SUCCESS))
        assertNull(controller.dialog.value)

        receiver.onReceive(app, statusIntent(PackageInstaller.STATUS_FAILURE_CONFLICT))
        val conflict = controller.dialog.value as UpdateDialog.Failed
        assertEquals(UpdateProblem.SignedWithDifferentKey, conflict.problem)
        assertEquals(UpdateRetry.INSTALL, conflict.retry)

        receiver.onReceive(app, statusIntent(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(UpdateProblem.InstallCancelled, (controller.dialog.value as UpdateDialog.Failed).problem)

        receiver.onReceive(app, statusIntent(PackageInstaller.STATUS_FAILURE_INVALID))
        assertEquals(UpdateProblem.InstallFailed("msg"), (controller.dialog.value as UpdateDialog.Failed).problem)

        // Other actions are ignored.
        controller.dismissDialog()
        receiver.onReceive(app, Intent("something.else").putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE))
        assertNull(controller.dialog.value)
    }

    // --- manifest ---

    @Test
    fun manifestDeclaresInstallPermissionsAndPrivateReceiver() {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS
        )
        val permissions = info.requestedPermissions!!.toList()
        assertTrue(permissions.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(permissions.contains("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"))
        val receiver = info.receivers!!.single { it.name == UpdateInstallReceiver::class.java.name }
        assertFalse(receiver.exported)
    }

    // --- startup cleanup ---

    @Test
    fun applicationStartsTheCleanupInTheBackground() {
        assertTrue(app.container.updateEngine.isStartupCleanupStarted)
    }

    @Test
    fun startupCleanupRunsOffTheMainThreadAndGatesPendingUpdate() = runBlocking {
        val engine = UpdateEngine(app)
        val ranOn = AtomicReference<Thread>()
        val release = CountDownLatch(1)
        engine.startStartupCleanup(CoroutineScope(SupervisorJob())) {
            ranOn.set(Thread.currentThread())
            release.await(10, TimeUnit.SECONDS)
        }
        val read = async(Dispatchers.IO) { engine.pendingUpdate() }
        delay(300)
        assertFalse("pendingUpdate() must wait for the cleanup", read.isCompleted)
        release.countDown()
        assertNull(read.await())
        assertNotNull(ranOn.get())
        assertNotSame(Looper.getMainLooper().thread, ranOn.get())
        assertSame(Looper.getMainLooper().thread, Thread.currentThread())
    }

    @Test
    fun startupCleanupStillDeletesLeftovers() = runBlocking {
        val dir = UpdateFiles.updatesDir(app).apply { mkdirs() }
        File(dir, "VaultPass-9.0.0.apk.part").writeText("x")
        val engine = UpdateEngine(app)
        engine.startStartupCleanup(CoroutineScope(SupervisorJob())).join()
        assertFalse(dir.exists())
    }
}
