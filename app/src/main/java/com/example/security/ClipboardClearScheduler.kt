package com.example.security

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import com.example.VaultPassApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Copies secrets to the clipboard and clears them after the user's delay (F11). The clear doesn't
 * depend on a screen, a lock or the process staying alive:
 * - a coroutine in [scope] clears on time while the process lives;
 * - an alarm ([ClipboardClearReceiver]) clears after the process was killed or while the device sleeps;
 * - [sweep], run when the process starts, handles one whose alarm was dropped (a force-stop cancels alarms);
 * - [clearIfDue] with `inForeground = true`, run when VaultPass gets window focus, handles one that
 *   couldn't be cleared blind (see [decide]).
 * Only the deadline is stored, never the copied text.
 *
 * A copy is marked as ours in its ClipDescription extras. Only the description is read, never the
 * text, so Android 12+ shows no "pasted from your clipboard" notice. From Android 10 an app without
 * window focus can't read even the description, so a background clear can't tell our copy from
 * one the user made since.
 */
class ClipboardClearScheduler(
    context: Context,
    private val scope: CoroutineScope,
    /** The clear delay in ms; 0 or less = never clear. */
    private val clearDelayMs: suspend () -> Long,
    /** Elapsed realtime (counts sleep, never jumps) and the boot count, to spot a deadline from before a reboot. */
    private val clock: DeviceClock
) {
    private val appContext: Context = context.applicationContext ?: context
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Main thread only (scope is the app's main scope; copy, clearNow and the receiver run there too).
    private var clearJob: Job? = null

    init {
        instance = this
    }

    /** Puts [text] on the clipboard, marked as sensitive and as ours, and schedules its clear. */
    fun copy(label: String, text: String) {
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_VAULTPASS_CLIP, true)
            // Inlined string constant: safe below API 33, and some keyboards read it there too.
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        clipboard.setPrimaryClip(clip)

        clearJob?.cancel()
        clearJob = scope.launch {
            val delayMs = clearDelayMs()
            if (delayMs > 0) arm(clock.elapsedRealtime() + delayMs) else forget()
        }
    }

    /** Manual lock (VaultPass is in front): clears our copy now and drops the scheduled clear. */
    fun clearNow() {
        clearJob?.cancel()
        clearJob = null
        if (isOurs() != false) wipe() // null here means an empty clipboard: wiping it is harmless
        forget()
    }

    /**
     * Clears the clipboard if a scheduled clear is due. Run by the timer, the alarm and [sweep], and
     * with [inForeground] when VaultPass gets window focus (the clipboard is readable then).
     */
    fun clearIfDue(inForeground: Boolean = false) {
        val deadline = prefs.getLong(KEY_DEADLINE, 0L)
        if (deadline == 0L) return
        val overdue = if (fromEarlierBoot()) Long.MAX_VALUE else clock.elapsedRealtime() - deadline
        if (overdue < 0) return
        when (decide(isOurs(), overdue, inForeground)) {
            ClearAction.WIPE -> { wipe(); forget() }
            ClearAction.LEAVE -> forget()
            ClearAction.WAIT -> Unit
        }
    }

    /** At process start: handles an overdue copy, or re-arms the timer and alarm for a pending one. */
    fun sweep() {
        val deadline = prefs.getLong(KEY_DEADLINE, 0L)
        if (deadline == 0L) return
        if (!fromEarlierBoot() && clock.elapsedRealtime() < deadline) arm(deadline) else clearIfDue()
    }

    private fun arm(deadline: Long) {
        // commit(): the alarm below must never fire before the deadline it checks is on disk.
        prefs.edit()
            .putLong(KEY_DEADLINE, deadline)
            .putLong(KEY_ARMED_AT, clock.elapsedRealtime())
            .putInt(KEY_BOOT, clock.bootCount() ?: -1)
            .commit()
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, alarmIntent())
        clearJob = scope.launch {
            // delay() counts only awake time, so it never ends before the deadline; the alarm covers sleep.
            delay((deadline - clock.elapsedRealtime()).coerceAtLeast(0))
            clearIfDue()
        }
    }

    private fun forget() {
        prefs.edit().remove(KEY_DEADLINE).remove(KEY_ARMED_AT).remove(KEY_BOOT).apply()
        alarmManager.cancel(alarmIntent())
    }

    /** A deadline set before a reboot can't be compared with this boot's elapsed time. */
    private fun fromEarlierBoot(): Boolean {
        val storedBoot = prefs.getInt(KEY_BOOT, -1)
        val boot = clock.bootCount()
        if (storedBoot != -1 && boot != null) return storedBoot != boot
        return clock.elapsedRealtime() < prefs.getLong(KEY_ARMED_AT, 0L)
    }

    /** true: our copy. false: someone else's. null: empty, or not readable (no focus, Android 10+). */
    private fun isOurs(): Boolean? {
        val description = try {
            clipboard.primaryClipDescription
        } catch (e: Exception) {
            null
        } ?: return null
        return description.extras?.getBoolean(EXTRA_VAULTPASS_CLIP, false) == true
    }

    private fun wipe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
            // A ROM that refuses a background clear must not crash the alarm receiver.
        }
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        0,
        Intent(appContext, ClipboardClearReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    internal enum class ClearAction { WIPE, LEAVE, WAIT }

    companion object {
        internal const val PREFS = "vaultpass_clipboard"
        private const val KEY_DEADLINE = "clear_deadline"
        private const val KEY_ARMED_AT = "clear_armed_at"
        private const val KEY_BOOT = "clear_boot"
        internal const val EXTRA_VAULTPASS_CLIP = "com.example.vaultpass.CLIP"

        /** How late a clear may still wipe a clipboard it can't read (Doze delays alarms by minutes). */
        internal const val STALE_MS = 15 * 60 * 1000L

        /** Set by the app container at startup; the manual lock clears through it. */
        @Volatile
        var instance: ClipboardClearScheduler? = null
            private set

        fun get(context: Context): ClipboardClearScheduler =
            instance ?: (context.applicationContext as VaultPassApplication).container.clipboardClearScheduler

        /**
         * What a due clear does with the clipboard it finds.
         * - Ours: wipe. Someone else's: leave it.
         * - Unreadable (background, Android 10+): wipe while the clear is fresh, since our secret is
         *   the likelier content; later, wait until VaultPass is in front and can check.
         * - Empty in the foreground: nothing to do.
         */
        internal fun decide(ours: Boolean?, overdueMs: Long, inForeground: Boolean): ClearAction = when {
            ours == true -> ClearAction.WIPE
            ours == false -> ClearAction.LEAVE
            inForeground -> ClearAction.LEAVE
            overdueMs <= STALE_MS -> ClearAction.WIPE
            else -> ClearAction.WAIT
        }
    }
}
