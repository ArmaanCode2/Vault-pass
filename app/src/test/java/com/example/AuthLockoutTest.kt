package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.security.AuthLockout
import com.example.security.AuthLockout.State
import com.example.security.DeviceClock
import com.example.security.PasswordHashHelper
import com.example.ui.AuthResult
import com.example.ui.VaultViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F4: failed-unlock lockout survives reboots without becoming negative, stuck or longer than its tier. */
class AuthLockoutRemainingTest {

    private val sec = 1000L
    private val min = 60 * sec
    private val hour = 60 * min
    private val day = 24 * hour

    @Test
    fun tiers_keepExistingThresholds() {
        assertEquals(0L, AuthLockout.tierForAttempts(0))
        assertEquals(0L, AuthLockout.tierForAttempts(4))
        assertEquals(30 * sec, AuthLockout.tierForAttempts(5))
        assertEquals(30 * sec, AuthLockout.tierForAttempts(9))
        assertEquals(60 * sec, AuthLockout.tierForAttempts(10))
        assertEquals(5 * min, AuthLockout.tierForAttempts(15))
        assertEquals(15 * min, AuthLockout.tierForAttempts(20))
        assertEquals(15 * min, AuthLockout.tierForAttempts(500))
    }

    @Test
    fun noFailures_noLockout() {
        assertEquals(0L, AuthLockout.remainingMs(State.NONE, 5 * min, 3))
        assertEquals(0L, AuthLockout.remainingMs(State.NONE, 0L, null))
        assertEquals(0L, AuthLockout.remainingMs(State(attempts = 4, failElapsedMs = 10 * sec, bootCount = 3, tierMs = 0L), 10 * sec, 3))
    }

    @Test
    fun sameBoot_unchangedBehaviour() {
        val state = State(attempts = 5, failElapsedMs = 10 * min, bootCount = 7, tierMs = 30 * sec)
        assertEquals(30 * sec, AuthLockout.remainingMs(state, 10 * min, 7))
        assertEquals(20 * sec, AuthLockout.remainingMs(state, 10 * min + 10 * sec, 7))
        assertEquals(1L, AuthLockout.remainingMs(state, 10 * min + 30 * sec - 1, 7))
        assertEquals(0L, AuthLockout.remainingMs(state, 10 * min + 30 * sec, 7))
        assertEquals(0L, AuthLockout.remainingMs(state, 2 * hour, 7))
    }

    @Test
    fun tenDaysUptime_thenReboot_waitsAtMostTier() {
        val tier = 15 * min
        val state = State(attempts = 20, failElapsedMs = 10 * day, bootCount = 7, tierMs = tier)
        // Right after reboot (boot count changed, uptime small): at most one tier, never 10 days.
        val justBooted = AuthLockout.remainingMs(state, 40 * sec, 8)
        assertTrue(justBooted in 1..tier)
        assertEquals(tier - 40 * sec, justBooted)
        // Same with an unknown boot count: the stored elapsed time is ahead of now.
        val unknownBoot = AuthLockout.remainingMs(state, 40 * sec, null)
        assertEquals(tier - 40 * sec, unknownBoot)
    }

    @Test
    fun reboot_thenTwoHoursUptime_isZero() {
        val state = State(attempts = 20, failElapsedMs = 3 * day, bootCount = 7, tierMs = 15 * min)
        assertEquals(0L, AuthLockout.remainingMs(state, 2 * hour, 8))
        assertEquals(0L, AuthLockout.remainingMs(state, 2 * hour, null))
        // Rebooted shortly after the failure, stored elapsed below now but boot count differs.
        val early = State(attempts = 20, failElapsedMs = 1 * min, bootCount = 7, tierMs = 15 * min)
        assertEquals(0L, AuthLockout.remainingMs(early, 2 * hour, 8))
    }

    @Test
    fun differentBoot_withSmallerStoredElapsed_restartsAtBoot() {
        // Failure at 30 s uptime, reboot, now 10 s uptime on a new boot: cooldown counted from boot.
        val state = State(attempts = 10, failElapsedMs = 5 * sec, bootCount = 1, tierMs = 60 * sec)
        assertEquals(50 * sec, AuthLockout.remainingMs(state, 10 * sec, 2))
    }

    @Test
    fun bootCountUnavailable_decidedByElapsedTest() {
        val state = State(attempts = 5, failElapsedMs = 10 * min, bootCount = null, tierMs = 30 * sec)
        // Elapsed not ahead: treated like the same boot.
        assertEquals(20 * sec, AuthLockout.remainingMs(state, 10 * min + 10 * sec, null))
        assertEquals(20 * sec, AuthLockout.remainingMs(state, 10 * min + 10 * sec, 4))
        // Elapsed ahead of now: rebooted, counted from boot.
        assertEquals(25 * sec, AuthLockout.remainingMs(state, 5 * sec, null))
        // Stored boot count known, current one unknown: same elapsed test.
        val stored = state.copy(bootCount = 9)
        assertEquals(20 * sec, AuthLockout.remainingMs(stored, 10 * min + 10 * sec, null))
        assertEquals(25 * sec, AuthLockout.remainingMs(stored, 5 * sec, null))
    }

    @Test
    fun oldState_boundedByTierFromAttemptCount() {
        // 2.6.x stored only attempts + elapsed timestamp.
        val old = State(attempts = 20, failElapsedMs = 10 * day, bootCount = null, tierMs = null)
        val tier = AuthLockout.tierForAttempts(20)
        for (now in listOf(0L, 1L, 30 * sec, 10 * min, 2 * hour, 10 * day, 10 * day + 1 * min, 30 * day)) {
            val remaining = AuthLockout.remainingMs(old, now, 5)
            assertTrue("now=$now remaining=$remaining", remaining in 0..tier)
        }
        assertEquals(tier - 1 * min, AuthLockout.remainingMs(old, 1 * min, 5))
        assertEquals(tier - 1 * min, AuthLockout.remainingMs(old, 10 * day + 1 * min, 5))
        assertEquals(0L, AuthLockout.remainingMs(old, 2 * hour, null))

        val oldFew = State(attempts = 3, failElapsedMs = 10 * day, bootCount = null, tierMs = null)
        assertEquals(0L, AuthLockout.remainingMs(oldFew, 1 * sec, null))
    }

    @Test
    fun alwaysClampedToTier() {
        // A corrupted or oversized stored tier never exceeds the longest tier.
        val huge = State(attempts = 20, failElapsedMs = 0L, bootCount = 1, tierMs = 365 * day)
        assertEquals(AuthLockout.MAX_TIER_MS, AuthLockout.remainingMs(huge, 0L, 1))
        val negative = State(attempts = 5, failElapsedMs = -10 * day, bootCount = 1, tierMs = 30 * sec)
        assertEquals(0L, AuthLockout.remainingMs(negative, 0L, 1))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthLockoutUnlockTest {

    private class FakeClock(var elapsed: Long, var boot: Int?) : DeviceClock {
        override fun elapsedRealtime(): Long = elapsed
        override fun bootCount(): Int? = boot
    }

    private lateinit var app: VaultPassApplication
    private lateinit var clock: FakeClock
    private lateinit var viewModel: VaultViewModel
    private val password = "Correct-Horse-9!"

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        val settings = app.container.settingsRepository
        settings.resetFailedAttempts()
        // Cheap KDF so wrong-password checks stay fast; a correct password is never accepted here.
        val salt = PasswordHashHelper.generateSalt()
        val iterations = 1000
        val algorithm = "PBKDF2WithHmacSHA256"
        settings.saveMasterPasswordAndKdfMetadata(
            PasswordHashHelper.hashPassword(password, salt, iterations, algorithm), salt, 1, iterations, algorithm
        )
        clock = FakeClock(elapsed = 10 * 24 * 60 * 60 * 1000L, boot = 7)
        viewModel = VaultViewModel(app.container.vaultRepository, settings, deviceClock = clock)
    }

    @After
    fun tearDown() = runBlocking {
        app.container.settingsRepository.resetFailedAttempts()
    }

    private suspend fun failTimes(n: Int) {
        repeat(n) { assertEquals(AuthResult.INVALID_PASSWORD, viewModel.unlockWithPassword("wrong")) }
    }

    @Test
    fun failure_storesElapsedBootCountAndTier() = runBlocking {
        failTimes(5)
        val state = app.container.settingsRepository.authLockoutState.first()
        assertEquals(5, state.attempts)
        assertEquals(clock.elapsed, state.failElapsedMs)
        assertEquals(7, state.bootCount)
        assertEquals(30_000L, state.tierMs)
    }

    @Test
    fun unknownBootCount_isStoredAsUnknown() = runBlocking {
        app.container.settingsRepository.incrementFailedAttempts(1234L, 3)
        clock.boot = null
        failTimes(1)
        val state = app.container.settingsRepository.authLockoutState.first()
        assertNull(state.bootCount)
        assertEquals(2, state.attempts)
    }

    @Test
    fun unlock_followsRemainingFunctionExactly() = runBlocking {
        failTimes(5)
        val failedAt = clock.elapsed

        // Locked: even the correct password is refused without touching the KDF.
        clock.elapsed = failedAt + 29_999
        assertEquals(1L, viewModel.lockoutRemainingMs(app.container.settingsRepository.authLockoutState.first()))
        assertEquals(AuthResult.LOCKED_OUT, viewModel.unlockWithPassword(password))
        assertEquals(AuthResult.LOCKED_OUT, viewModel.unlockWithPassword("wrong"))
        assertEquals("Locked-out attempts are not counted", 5, app.container.settingsRepository.authLockoutState.first().attempts)

        // Exactly at the end of the tier: allowed (password is checked again).
        clock.elapsed = failedAt + 30_000
        assertEquals(0L, viewModel.lockoutRemainingMs(app.container.settingsRepository.authLockoutState.first()))
        assertEquals(AuthResult.INVALID_PASSWORD, viewModel.unlockWithPassword("wrong"))
        assertEquals(6, app.container.settingsRepository.authLockoutState.first().attempts)
        assertEquals(AuthResult.LOCKED_OUT, viewModel.unlockWithPassword("wrong"))
    }

    @Test
    fun unlock_afterRebootFromLongUptime_waitsAtMostTier() = runBlocking {
        failTimes(5) // at 10 days uptime, boot 7
        clock.boot = 8
        clock.elapsed = 10_000 // 10 s after reboot
        val remaining = viewModel.lockoutRemainingMs(app.container.settingsRepository.authLockoutState.first())
        assertEquals(20_000L, remaining)
        assertEquals(AuthResult.LOCKED_OUT, viewModel.unlockWithPassword("wrong"))

        clock.elapsed = 2 * 60 * 60 * 1000L // 2 h after reboot
        assertEquals(0L, viewModel.lockoutRemainingMs(app.container.settingsRepository.authLockoutState.first()))
        assertEquals(AuthResult.INVALID_PASSWORD, viewModel.unlockWithPassword("wrong"))
    }

    @Test
    fun noFailures_unlockIsNotLockedOut() = runBlocking {
        assertEquals(0L, viewModel.lockoutRemainingMs(app.container.settingsRepository.authLockoutState.first()))
        assertEquals(AuthResult.INVALID_PASSWORD, viewModel.unlockWithPassword("wrong"))
    }

    @Test
    fun reset_clearsNewKeys() = runBlocking {
        failTimes(5)
        app.container.settingsRepository.resetFailedAttempts()
        assertEquals(State.NONE, app.container.settingsRepository.authLockoutState.first())
    }
}
