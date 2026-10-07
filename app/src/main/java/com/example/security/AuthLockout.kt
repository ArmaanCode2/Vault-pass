package com.example.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Time source for the failed-unlock lockout. Injected so tests don't depend on the device.
 */
interface DeviceClock {
    /** Milliseconds since boot (SystemClock.elapsedRealtime on a device). */
    fun elapsedRealtime(): Long

    /** Settings.Global.BOOT_COUNT, or null when the device doesn't provide it. */
    fun bootCount(): Int?
}

class AndroidDeviceClock(context: Context) : DeviceClock {
    private val appContext = context.applicationContext ?: context

    // The boot count can't change while this process is alive (a reboot ends it),
    // so it is read once.
    private val cachedBootCount: Int? by lazy {
        try {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (e: Exception) {
            // Missing on some devices/ROMs (SettingNotFoundException) or not readable.
            null
        }
    }

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Int? = cachedBootCount
}

/**
 * Lockout after failed master-password attempts.
 *
 * At each failure the app stores the elapsed-realtime clock, the boot count (when the device
 * has one) and the lockout duration ("tier") that applies after that failure. [remainingMs]
 * turns that into the time left, and is the only place this is decided: both the unlock check
 * and the lock screen countdown use it.
 */
object AuthLockout {

    const val MAX_TIER_MS = 15 * 60 * 1000L

    /** Stored lockout data. [bootCount] and [tierMs] are null for state written by 2.6.x. */
    data class State(
        val attempts: Int = 0,
        val failElapsedMs: Long = 0L,
        val bootCount: Int? = null,
        val tierMs: Long? = null
    ) {
        companion object {
            val NONE = State()
        }
    }

    /** Lockout duration after [attempts] consecutive failures (unchanged thresholds). */
    fun tierForAttempts(attempts: Int): Long {
        return when {
            attempts >= 20 -> 15 * 60 * 1000L
            attempts >= 15 -> 5 * 60 * 1000L
            attempts >= 10 -> 60 * 1000L
            attempts >= 5 -> 30 * 1000L
            else -> 0L
        }
    }

    /**
     * Milliseconds of lockout left, always within [0, tier].
     *
     * - Same boot: tier counted from the failure, `failElapsed + tier - nowElapsed`.
     * - Different boot, or the stored elapsed time is ahead of now (the device rebooted):
     *   the cooldown restarts at boot, `tier - nowElapsed`.
     * - Boot count unknown on either side (incl. 2.6.x state): decided by the elapsed test alone.
     * 2.6.x state has no stored tier; it is derived from the attempt count.
     */
    fun remainingMs(state: State, nowElapsed: Long, nowBootCount: Int?): Long {
        val tier = (state.tierMs ?: tierForAttempts(state.attempts)).coerceIn(0L, MAX_TIER_MS)
        if (tier == 0L) return 0L

        val differentBoot = state.bootCount != null && nowBootCount != null && state.bootCount != nowBootCount
        val rebooted = differentBoot || state.failElapsedMs > nowElapsed

        val remaining = if (rebooted) {
            tier - nowElapsed
        } else {
            state.failElapsedMs + tier - nowElapsed
        }
        return remaining.coerceIn(0L, tier)
    }
}
