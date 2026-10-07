package com.example.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.example.VaultPassApplication

/**
 * Status of an update install session (not exported; reached only through the session's PendingIntent).
 * The handling itself is in [UpdateController.onInstallStatus].
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val app = context.applicationContext as? VaultPassApplication ?: return
        app.container.updateController.onInstallStatus(
            sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
            status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        )
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.example.update.ACTION_INSTALL_STATUS"
    }
}
