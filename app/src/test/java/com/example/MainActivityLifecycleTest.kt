package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.security.VaultSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The main screen's lifecycle and the session: a rotation doesn't lock with "Immediately" (F24),
 * and coming back to the main screen ends a file picker's grace period (F27).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityLifecycleTest {

    private lateinit var app: VaultPassApplication
    private lateinit var controller: ActivityController<MainActivity>
    private val session get() = VaultSessionManager.instance!!

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        VaultSessionManager.resetForTesting()
        runBlocking { app.container.settingsRepository.setAutoLockTimer(0L) }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        assertTrue(session.openVault(ByteArray(32) { 7 }, session.currentLockEpoch()))
    }

    @After
    fun tearDown() {
        session.lock()
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(session)
        runBlocking { app.container.settingsRepository.setAutoLockTimer(60_000L) }
        VaultSessionManager.resetForTesting()
    }

    /**
     * Runs the main looper until [done] or about two seconds have passed: the stop handler reads
     * the auto-lock setting from storage off the main thread before it locks.
     */
    private fun settle(done: () -> Boolean = { false }) {
        repeat(100) {
            ShadowLooper.idleMainLooper()
            if (done()) return
            Thread.sleep(20)
        }
    }

    @Test
    fun immediately_aRotationKeepsTheVaultOpen_leavingLocksIt() {
        // Android marks the activity as changing configurations before stopping it for a rotation;
        // Robolectric's recreate() doesn't, so the test does.
        org.robolectric.util.ReflectionHelpers.setField(controller.get(), "mChangingConfigurations", true)
        controller.recreate()
        settle { !session.isUnlocked.value }
        assertTrue("a configuration change is not leaving", session.isUnlocked.value)

        controller.pause().stop()
        settle { !session.isUnlocked.value }
        assertFalse(session.isUnlocked.value)
    }

    @Test
    fun comingBackToTheMainScreen_endsAPickersGrace() {
        // The picker covered the main screen; its result callback may never come (e.g. after a lock).
        session.setPerformingSystemOperation(true)
        controller.pause().stop()
        ShadowLooper.idleMainLooper()

        controller.start()
        assertFalse(session.getPerformingSystemOperation())
    }
}
