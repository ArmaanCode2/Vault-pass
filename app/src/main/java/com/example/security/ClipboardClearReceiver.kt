package com.example.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarm for a clipboard clear whose process was killed first (see [ClipboardClearScheduler]). */
class ClipboardClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ClipboardClearScheduler.get(context).clearIfDue()
    }
}
