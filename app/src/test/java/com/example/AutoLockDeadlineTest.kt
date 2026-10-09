package com.example

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.example.repository.SettingsRepository
import com.example.security.DeviceClock
import com.example.security.VaultSessionManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * F12: the auto-lock deadline is measured on elapsed realtime, which keeps counting while the
 * device sleeps. The fake clock below advances without the main looper, as in deep sleep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoLockDeadlineTest {

    private class FakeClock(var elapsed: Long) : DeviceClock {
        override fun elapsedRealtime(): Long = elapsed
        override fun bootCount(): Int? = null
    }

    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private lateinit var app: VaultPassApplication
    private lateinit var clock: FakeClock
    private lateinit var session: VaultSessionManager
    private var timeoutMs = 60_000L

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        clock = FakeClock(elapsed = 1_000_000L)
        VaultSessionManager.resetForTesting()
        session = VaultSessionManager(
            SettingsRepository(app, clock),
            app.container.cryptoManager,
            app.container.vaultRepository,
            autoLockTimeoutMs = { timeoutMs }
        )
        assertTrue(session.openVault(ByteArray(32) { 7 }, session.currentLockEpoch()))
    }

    @After
    fun tearDown() {
        session.lock()
        VaultSessionManager.resetForTesting()
    }

    private val unlocked get() = session.isUnlocked.value

    @Test
    fun sleepPastTheTimeout_locksOnReturn() {
        session.onStop(owner)
        ShadowLooper.idleMainLooper()

        clock.elapsed += TimeUnit.HOURS.toMillis(2) // asleep: the timer's delay doesn't advance
        assertTrue("still unlocked before the user returns", unlocked)

        session.onStart(owner)
        assertFalse("a deadline that passed in sleep locks on return", unlocked)
    }

    @Test
    fun wakeAfterTheDeadline_locksWithinSecondsWithoutReturning() {
        session.onStop(owner)
        ShadowLooper.idleMainLooper()

        clock.elapsed += TimeUnit.HOURS.toMillis(2) // asleep
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS) // a few seconds awake, app still in the background
        assertFalse("the key must not stay loaded for the rest of the timeout", unlocked)
    }

    @Test
    fun never_doesNotLock() {
        timeoutMs = -1L
        session.onStop(owner)
        ShadowLooper.idleMainLooper()

        clock.elapsed += TimeUnit.HOURS.toMillis(2)
        ShadowLooper.idleMainLooper(5, TimeUnit.MINUTES)
        assertFalse(session.lockIfExpired())
        session.onStart(owner)
        assertTrue(unlocked)
    }

    @Test
    fun backgroundUseAfterTheDeadline_locksFirst() {
        session.onStop(owner)
        ShadowLooper.idleMainLooper()

        clock.elapsed += 30_000L
        assertFalse("not due yet", session.lockIfExpired())
        assertTrue(unlocked)

        clock.elapsed += 31_000L
        assertTrue("due: autofill's check locks", session.lockIfExpired())
        assertFalse(unlocked)
    }

    @Test
    fun quickReturn_noTimerStartsAfterIt() {
        session.onStop(owner)
        session.onStart(owner) // back before the timer read the setting
        ShadowLooper.idleMainLooper()

        clock.elapsed += TimeUnit.MINUTES.toMillis(2)
        ShadowLooper.idleMainLooper(2, TimeUnit.MINUTES)
        assertTrue("a stale timer must not lock the vault in the foreground", unlocked)
    }

    @Test
    fun awake_timerStillLocks() {
        session.onStop(owner)
        ShadowLooper.idleMainLooper()

        clock.elapsed += 61_000L
        ShadowLooper.idleMainLooper(61, TimeUnit.SECONDS)
        assertFalse(unlocked)
    }

    @Test
    fun returnBeforeTheDeadline_staysUnlocked() {
        session.onStop(owner)
        ShadowLooper.idleMainLooper()

        clock.elapsed += 30_000L
        session.onStart(owner)
        assertTrue(unlocked)
        assertFalse("the deadline is dropped on return", session.lockIfExpired())
    }

    @Test
    fun immediately_locksOnStop() {
        timeoutMs = 0L
        session.onStop(owner)
        ShadowLooper.idleMainLooper()
        assertFalse(unlocked)
    }
}
