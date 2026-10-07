package com.example.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.DigestInputStream
import java.security.MessageDigest

/** How starting an install ended. The final result arrives later in [UpdateInstallReceiver]. */
sealed interface InstallStart {
    /** Session committed; Android reports the outcome to [UpdateInstallReceiver]. */
    data class Committed(val sessionId: Int, val version: String) : InstallStart
    /** "Install unknown apps" is off for VaultPass. Nothing was started. */
    object NeedsPermission : InstallStart
    /** No pending update, or it failed re-verification (and was deleted). Nothing was started. */
    object NoPendingUpdate : InstallStart
    /** The app left the foreground before the commit; the session was abandoned. */
    object NotForeground : InstallStart
    data class Failed(val detail: String?) : InstallStart
}

/** The installer as the update UI sees it (fakes in tests). */
interface UpdateInstallOperations {
    fun canRequestInstalls(): Boolean
    /** Commits the pending update only while [isForeground] is true. */
    suspend fun install(isForeground: () -> Boolean): InstallStart
}

/**
 * Installs the pending update with a PackageInstaller session: re-verified right before writing,
 * the written bytes re-hashed against the recorded digest, committed only in the foreground.
 */
class UpdateInstaller(
    private val context: Context,
    private val updates: UpdateOperations,
    private val canRequest: () -> Boolean = { defaultCanRequestInstalls(context) }
) : UpdateInstallOperations {

    override fun canRequestInstalls(): Boolean = canRequest()

    override suspend fun install(isForeground: () -> Boolean): InstallStart {
        if (!canRequestInstalls()) return InstallStart.NeedsPermission
        // Re-verifies digest, package, versionCode and signer; anything invalid is deleted here.
        val pending = updates.pendingUpdate() ?: return InstallStart.NoPendingUpdate
        return withContext(Dispatchers.IO) {
            val installer = context.packageManager.packageInstaller
            val sessionId = try {
                installer.createSession(sessionParams(pending))
            } catch (e: Exception) {
                return@withContext InstallStart.Failed(e.message)
            }
            var session: PackageInstaller.Session? = null
            var committed = false
            try {
                session = installer.openSession(sessionId)
                if (!writeApk(session, pending)) return@withContext InstallStart.NoPendingUpdate
                if (!isForeground()) return@withContext InstallStart.NotForeground
                session.commit(statusIntent(sessionId).intentSender)
                committed = true
                InstallStart.Committed(sessionId, pending.version)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) e.printStackTrace()
                InstallStart.Failed(e.message)
            } finally {
                if (!committed) {
                    try {
                        if (session != null) session.abandon() else installer.abandonSession(sessionId)
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) e.printStackTrace()
                    }
                }
                session?.close()
            }
        }
    }

    private fun sessionParams(pending: PendingUpdate): PackageInstaller.SessionParams {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(pending.file.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Silent only when VaultPass is the installer of record; otherwise Android still asks.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        return params
    }

    /** Copies the APK into the session; false when the bytes no longer match the recorded digest. Throws on I/O errors. */
    private fun writeApk(session: PackageInstaller.Session, pending: PendingUpdate): Boolean {
        val file = pending.file
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { input ->
            session.openWrite(SESSION_APK_NAME, 0, file.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }
        return digest.digest().toHex() == pending.sha256.lowercase()
    }

    private fun statusIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, UpdateInstallReceiver::class.java)
            .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
            .setPackage(context.packageName)
        // Mutable on 31+ so Android can add the status extras; explicit intent, one request code per session.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    companion object {
        private const val SESSION_APK_NAME = "VaultPass.apk"

        fun defaultCanRequestInstalls(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }
}
