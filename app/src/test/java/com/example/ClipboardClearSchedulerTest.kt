package com.example

import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.security.ClipboardClearReceiver
import com.example.security.ClipboardClearScheduler
import com.example.security.ClipboardClearScheduler.ClearAction
import com.example.security.DeviceClock
import com.example.security.VaultSessionManager
import com.example.ui.VaultViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * F11: a copied secret is cleared on time, on manual lock and after the process dies, never on
 * auto-lock. Runs on API 27 (no clearPrimaryClip) and 34.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 34])
class ClipboardClearSchedulerTest {

    private class FakeClock(var elapsed: Long, var boot: Int?) : DeviceClock {
        override fun elapsedRealtime(): Long = elapsed
        override fun bootCount(): Int? = boot
    }

    private val delayMs = 30_000L
    private val clock = FakeClock(elapsed = 5_000_000L, boot = 1)

    private lateinit var app: VaultPassApplication
    private lateinit var clipboard: ClipboardManager
    private lateinit var alarms: AlarmManager
    private lateinit var scope: CoroutineScope
    private lateinit var scheduler: ClipboardClearScheduler

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scheduler = newProcess()
    }

    @After
    fun tearDown() {
        scope.cancel()
        VaultSessionManager.resetForTesting()
    }

    /** A scheduler as a fresh process would build it: same stored state, no running timer. */
    private fun newProcess(delay: Long = delayMs): ClipboardClearScheduler =
        ClipboardClearScheduler(app, scope, clearDelayMs = { delay }, clock = clock)

    /** Kills the timer, as process death would. */
    private fun killProcess() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    private fun copySecret() {
        scheduler.copy("Password", "hunter2")
        ShadowLooper.idleMainLooper() // reads the delay and arms the timer and alarm
    }

    /** Lets [ms] pass with the device awake. */
    private fun pass(ms: Long) {
        clock.elapsed += ms
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)
    }

    private fun clipText(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    // API 28+ clears the clip; below that it is replaced by an empty one.
    private fun assertCleared() = assertTrue("clipboard should be cleared, was '${clipText()}'", clipText().isNullOrEmpty())

    @Test
    fun copy_isMarkedAndClearedAfterTheDelay() {
        copySecret()
        assertEquals("hunter2", clipText())
        val extras = clipboard.primaryClipDescription!!.extras!!
        assertTrue(extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        val alarm = shadowOf(alarms).nextScheduledAlarm!!
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        assertEquals(clock.elapsed + delayMs, alarm.triggerAtMs)
        assertTrue(alarm.isAllowWhileIdle)

        pass(delayMs - 1)
        assertEquals("not before the delay", "hunter2", clipText())
        pass(1)
        assertCleared()
        assertNull("alarm cancelled once cleared", shadowOf(alarms).nextScheduledAlarm)
    }

    @Test
    fun alarm_targetsADeclaredReceiver() {
        copySecret()
        val operation = shadowOf(alarms).nextScheduledAlarm!!.operation
        assertEquals(ClipboardClearReceiver::class.java.name, shadowOf(operation).savedIntent.component!!.className)
        val receivers = app.packageManager.queryBroadcastReceivers(Intent(app, ClipboardClearReceiver::class.java), 0)
        assertTrue("ClipboardClearReceiver must be in the manifest", receivers.isNotEmpty())
    }

    @Test
    fun storedState_isOnlyTheDeadline() {
        copySecret()
        val stored = app.getSharedPreferences(ClipboardClearScheduler.PREFS, Context.MODE_PRIVATE).all
        assertEquals(setOf("clear_deadline", "clear_armed_at", "clear_boot"), stored.keys)
        assertFalse("the secret is never stored", stored.values.any { it.toString().contains("hunter2") })
    }

    @Test
    @Config(sdk = [34])
    fun viewModelCopy_goesThroughTheScheduler() {
        VaultViewModel(app.container.vaultRepository, app.container.settingsRepository)
            .copyToClipboard(app, "Password", "hunter2")
        assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean(ClipboardClearScheduler.EXTRA_VAULTPASS_CLIP))
    }

    @Test
    @Config(sdk = [34])
    fun manualLock_clearsTheCopyNow() {
        copySecret()
        VaultViewModel(app.container.vaultRepository, app.container.settingsRepository).lock()
        assertCleared()
        assertNull(shadowOf(alarms).nextScheduledAlarm)
    }

    @Test
    fun manualLock_leavesAnotherAppsClip() {
        copySecret()
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "not ours"))
        scheduler.clearNow()
        assertEquals("not ours", clipText())
    }

    @Test
    fun timer_leavesAnotherAppsClip() {
        copySecret()
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "not ours"))
        pass(delayMs)
        assertEquals("not ours", clipText())
    }

    @Test
    @Config(sdk = [34])
    fun autoLock_leavesTheCopyUntilTheTimer() {
        copySecret()
        app.container.vaultSessionManager.lock() // what "Immediately" does on leaving the app
        assertEquals("the user is about to paste it", "hunter2", clipText())
        pass(delayMs)
        assertCleared()
    }

    @Test
    fun deviceAsleep_alarmClears() {
        copySecret()
        clock.elapsed += delayMs // asleep: the timer's delay doesn't advance, the wakeup alarm fires
        ClipboardClearReceiver().onReceive(app, Intent())
        assertCleared()
    }

    @Test
    fun processKilled_alarmClearsWhenDue() {
        copySecret()
        killProcess()
        clock.elapsed += delayMs
        ClipboardClearReceiver().onReceive(app, Intent())
        assertCleared()
    }

    @Test
    fun processKilled_nextStartClearsAnOverdueCopy() {
        copySecret()
        killProcess()
        clock.elapsed += 10 * delayMs // force-stopped: the alarm never came
        newProcess().sweep()
        assertCleared()
    }

    @Test
    fun processKilled_nextStartRearmsAPendingCopy() {
        copySecret()
        killProcess()
        clock.elapsed += 10_000L
        scheduler = newProcess()
        scheduler.sweep()
        assertEquals("hunter2", clipText())
        assertNotNull(shadowOf(alarms).nextScheduledAlarm)

        pass(delayMs - 10_000L)
        assertCleared()
    }

    @Test
    fun deadlineFromAnEarlierBoot_stillClearsOurCopy() {
        copySecret()
        killProcess()
        clock.boot = 2
        clock.elapsed = 1_000L // elapsed time restarted
        newProcess().sweep()
        assertCleared()
    }

    @Test
    fun deadlineFromAnEarlierBoot_withoutBootCount() {
        clock.boot = null
        copySecret()
        killProcess()
        clock.elapsed = 1_000L
        newProcess().sweep()
        assertCleared()
    }

    @Test
    @Config(sdk = [34])
    fun windowFocus_clearsADueCopyLeftByAKilledProcess() {
        copySecret()
        killProcess()
        clock.elapsed += delayMs + ClipboardClearScheduler.STALE_MS + 1
        Robolectric.buildActivity(MainActivity::class.java).create().get().onWindowFocusChanged(true)
        assertCleared()
    }

    @Test
    fun noDelay_meansNoClear() {
        scheduler = newProcess(delay = 0L)
        copySecret()
        assertNull(shadowOf(alarms).nextScheduledAlarm)
        pass(delayMs)
        assertEquals("hunter2", clipText())
    }

    @Test
    fun decide_coversEveryClipboardState() {
        val stale = ClipboardClearScheduler.STALE_MS
        fun decide(ours: Boolean?, overdue: Long, front: Boolean) = ClipboardClearScheduler.decide(ours, overdue, front)
        assertEquals(ClearAction.WIPE, decide(true, Long.MAX_VALUE, front = false))
        assertEquals(ClearAction.LEAVE, decide(false, 0, front = false))
        assertEquals("empty while in front: nothing to do", ClearAction.LEAVE, decide(null, Long.MAX_VALUE, front = true))
        assertEquals("unreadable but fresh: likely ours", ClearAction.WIPE, decide(null, stale, front = false))
        assertEquals("unreadable and stale: keep the deadline", ClearAction.WAIT, decide(null, stale + 1, front = false))
    }
}
