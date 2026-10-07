package com.example.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Result of the last update check, for the Settings "Check now" row. */
sealed interface UpdateCheckStatus {
    object Idle : UpdateCheckStatus
    object Checking : UpdateCheckStatus
    data class UpToDate(val latestVersion: String?) : UpdateCheckStatus
    data class Available(val version: String) : UpdateCheckStatus
    data class Failed(val failure: UpdateFailure) : UpdateCheckStatus
}

/** What the dashboard banner offers. */
sealed interface UpdateOffer {
    val version: String

    /** "Update to latest version X.Y.Z": download first. */
    data class Download(val info: UpdateInfo) : UpdateOffer {
        override val version: String get() = info.version
    }

    /** "Install update X.Y.Z": already downloaded and verified, goes straight to the installer. */
    data class Install(override val version: String) : UpdateOffer
}

/** Why something failed, in a form the UI turns into plain words. */
sealed interface UpdateProblem {
    data class Download(val failure: UpdateFailure) : UpdateProblem
    data class Verification(val reason: ApkRejection) : UpdateProblem
    /** The saved update vanished or failed re-verification (and was deleted). */
    object PendingUpdateGone : UpdateProblem
    /** Back from Android's settings, "Install unknown apps" is still off. */
    object InstallPermissionMissing : UpdateProblem
    object PermissionSettingsUnavailable : UpdateProblem
    /** The install session could not be created or written. */
    object InstallStartFailed : UpdateProblem
    /** STATUS_FAILURE_CONFLICT: the installed app is signed with a different key (or another conflict). */
    object SignedWithDifferentKey : UpdateProblem
    /** STATUS_FAILURE_INCOMPATIBLE: this phone can't install this build (e.g. ABI or Android version). */
    object IncompatibleWithDevice : UpdateProblem
    object InstallCancelled : UpdateProblem
    object InstallBlocked : UpdateProblem
    object InstallStorage : UpdateProblem
    object ConfirmationUnavailable : UpdateProblem
    data class InstallFailed(val systemMessage: String?) : UpdateProblem
}

enum class UpdateRetry { DOWNLOAD, INSTALL, NONE }

/** The update dialog; null when hidden. */
sealed interface UpdateDialog {
    val version: String

    data class Downloading(override val version: String, val bytes: Long, val total: Long) : UpdateDialog {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Verifying(override val version: String) : UpdateDialog
    /** Downloaded and verified: "Restart now" / "Later". */
    data class Ready(override val version: String) : UpdateDialog
    /** Explains "Install unknown apps" before opening Android's settings. */
    data class NeedsInstallPermission(override val version: String) : UpdateDialog
    /** Session committed (or being written); Android takes over. */
    data class Installing(override val version: String) : UpdateDialog
    data class Failed(override val version: String, val problem: UpdateProblem, val retry: UpdateRetry) : UpdateDialog
}

/**
 * Process-wide state of the in-app updater: the check at app open, "Check now", the dashboard offer,
 * the download/verify/install dialog and the installer's status callbacks.
 * Never touches the vault and never changes the auto-lock (no "system operation" bypass).
 */
class UpdateController(
    private val updates: UpdateOperations,
    private val installer: UpdateInstallOperations,
    private val checkOnOpen: suspend () -> Boolean,
    private val scope: CoroutineScope,
    /** False in builds without an update feed: no checks, no UI. */
    val enabled: Boolean = UpdateConfig.isEnabled,
    val runningVersion: String = BuildConfig.VERSION_NAME,
    private val log: (String) -> Unit = { Log.i(TAG, it) }
) {
    private val _checkStatus = MutableStateFlow<UpdateCheckStatus>(UpdateCheckStatus.Idle)
    val checkStatus: StateFlow<UpdateCheckStatus> = _checkStatus.asStateFlow()

    private val _offer = MutableStateFlow<UpdateOffer?>(null)
    val offer: StateFlow<UpdateOffer?> = _offer.asStateFlow()

    private val _dialog = MutableStateFlow<UpdateDialog?>(null)
    val dialog: StateFlow<UpdateDialog?> = _dialog.asStateFlow()

    private val opened = AtomicBoolean(false)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private val downloadAttempt = AtomicInteger(0)
    private var installJob: Job? = null

    @Volatile private var lastInfo: UpdateInfo? = null
    @Volatile private var installVersion: String? = null
    @Volatile private var committedSessionId: Int? = null
    @Volatile private var resumedActivity: WeakReference<Activity>? = null
    @Volatile private var pendingConfirmation: Intent? = null
    @Volatile private var awaitingInstallPermission = false

    /** The confirmation intent waiting for the next resume (tests). */
    internal val storedConfirmation: Intent? get() = pendingConfirmation

    /**
     * Once per process, in the background, never blocking unlock: a verified pending update newer than
     * the running app is offered as "Install update" (no download); otherwise, only when the user turned
     * the check on, the feed is asked. Failures are only logged.
     */
    fun onAppOpen(): Job? {
        if (!enabled || !opened.compareAndSet(false, true)) return null
        return scope.launch {
            try {
                val pending = updates.pendingUpdate()
                if (pending != null && AppVersion.isNewer(pending.version, runningVersion)) {
                    offerInstall(pending.version)
                    return@launch
                }
                if (!checkOnOpen()) return@launch
                when (val result = updates.check()) {
                    is UpdateCheckResult.Available -> {
                        _checkStatus.value = UpdateCheckStatus.Available(result.info.version)
                        offerDownload(result.info)
                    }
                    is UpdateCheckResult.UpToDate -> _checkStatus.value = UpdateCheckStatus.UpToDate(result.latestVersion)
                    is UpdateCheckResult.Failed -> log("Update check at app open failed: ${result.failure}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Update check at app open failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Settings "Check now". */
    fun checkNow() {
        if (!enabled || checkJob?.isActive == true) return
        _checkStatus.value = UpdateCheckStatus.Checking
        checkJob = scope.launch {
            val result = try {
                updates.check()
            } catch (e: CancellationException) {
                _checkStatus.value = UpdateCheckStatus.Idle
                throw e
            } catch (e: Exception) {
                UpdateCheckResult.Failed(UpdateFailure.NETWORK, e.message)
            }
            _checkStatus.value = when (result) {
                is UpdateCheckResult.Available -> {
                    offerDownload(result.info)
                    UpdateCheckStatus.Available(result.info.version)
                }
                is UpdateCheckResult.UpToDate -> UpdateCheckStatus.UpToDate(result.latestVersion)
                is UpdateCheckResult.Failed -> UpdateCheckStatus.Failed(result.failure)
            }
        }
    }

    /** Banner (or Settings) tap: download, or straight to the installer when already downloaded. */
    fun openOffer() {
        when (val current = _offer.value) {
            is UpdateOffer.Download -> startDownload(current.info)
            is UpdateOffer.Install -> installPending()
            null -> Unit
        }
    }

    /**
     * Starts a download. A cancelled earlier download may still be blocked in read(); the new one waits
     * for it to finish ([cancelAndJoin]) so the two never work in updates/ at the same time.
     */
    fun startDownload(info: UpdateInfo) {
        if (!enabled || downloadJob?.isActive == true || installJob?.isActive == true) return
        val version = info.version
        lastInfo = info
        val attempt = downloadAttempt.incrementAndGet()
        _dialog.value = UpdateDialog.Downloading(version, 0, info.sizeBytes)
        val previous = downloadJob
        downloadJob = scope.launch {
            previous?.cancelAndJoin()
            val result = try {
                updates.prepare(info) { bytes, total ->
                    _dialog.value = if (total > 0 && bytes >= total) {
                        UpdateDialog.Verifying(version)
                    } else {
                        UpdateDialog.Downloading(version, bytes, total)
                    }
                }
            } catch (e: CancellationException) {
                // A late-finishing cancelled attempt must not hide a newer attempt's dialog.
                if (downloadAttempt.get() == attempt) _dialog.value = null
                throw e
            } catch (e: Exception) {
                PrepareResult.DownloadFailed(UpdateFailure.STORAGE, e.message)
            }
            ensureActive()
            _dialog.value = when (result) {
                is PrepareResult.Ready -> {
                    offerInstall(result.update.version)
                    UpdateDialog.Ready(result.update.version)
                }
                is PrepareResult.DownloadFailed ->
                    UpdateDialog.Failed(version, UpdateProblem.Download(result.failure), UpdateRetry.DOWNLOAD)
                is PrepareResult.VerificationFailed ->
                    UpdateDialog.Failed(version, UpdateProblem.Verification(result.reason), UpdateRetry.DOWNLOAD)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _dialog.value = null
    }

    /** "Later": the verified file stays; the banner (and the next app open) offers "Install update". */
    fun later() {
        _dialog.value = null
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    fun retry() {
        val failed = _dialog.value as? UpdateDialog.Failed ?: return
        when (failed.retry) {
            UpdateRetry.DOWNLOAD -> lastInfo?.let { startDownload(it) } ?: dismissDialog()
            UpdateRetry.INSTALL -> installPending()
            UpdateRetry.NONE -> dismissDialog()
        }
    }

    /**
     * "Restart now" / "Install update": checks "Install unknown apps", then re-verifies the saved APK and
     * commits a PackageInstaller session while the app is in the foreground.
     */
    fun installPending() {
        if (!enabled || installJob?.isActive == true || downloadJob?.isActive == true) return
        val version = installVersion ?: _offer.value?.version ?: return
        if (!installer.canRequestInstalls()) {
            _dialog.value = UpdateDialog.NeedsInstallPermission(version)
            return
        }
        _dialog.value = UpdateDialog.Installing(version)
        val download = downloadJob
        installJob = scope.launch {
            download?.join() // A cancelled download may still be finishing in updates/.
            val result = try {
                installer.install { resumedActivity?.get() != null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                InstallStart.Failed(e.message)
            }
            when (result) {
                is InstallStart.Committed -> {
                    committedSessionId = result.sessionId
                    installVersion = result.version
                }
                InstallStart.NeedsPermission -> _dialog.value = UpdateDialog.NeedsInstallPermission(version)
                InstallStart.NoPendingUpdate -> {
                    installVersion = null
                    val info = lastInfo
                    _offer.value = info?.let { UpdateOffer.Download(it) }
                    _dialog.value = UpdateDialog.Failed(
                        version,
                        UpdateProblem.PendingUpdateGone,
                        if (info != null) UpdateRetry.DOWNLOAD else UpdateRetry.NONE
                    )
                }
                InstallStart.NotForeground -> _dialog.value = UpdateDialog.Ready(version)
                is InstallStart.Failed -> {
                    log("Update install could not start: ${result.detail}")
                    _dialog.value = UpdateDialog.Failed(version, UpdateProblem.InstallStartFailed, UpdateRetry.INSTALL)
                }
            }
        }
    }

    /** Opens Android's "Install unknown apps" page for VaultPass; the install continues on return. */
    fun openInstallPermissionSettings() {
        val activity = resumedActivity?.get() ?: return
        val version = (_dialog.value?.version) ?: installVersion ?: _offer.value?.version ?: ""
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            installPending()
            return
        }
        awaitingInstallPermission = true
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
        } catch (e: ActivityNotFoundException) {
            awaitingInstallPermission = false
            _dialog.value = UpdateDialog.Failed(version, UpdateProblem.PermissionSettingsUnavailable, UpdateRetry.INSTALL)
        }
    }

    fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        pendingConfirmation?.let { confirmation ->
            pendingConfirmation = null
            launchConfirmation(activity, confirmation)
        }
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false
            if (installer.canRequestInstalls()) {
                installPending()
            } else {
                val version = installVersion ?: _offer.value?.version ?: ""
                _dialog.value = UpdateDialog.Failed(version, UpdateProblem.InstallPermissionMissing, UpdateRetry.INSTALL)
            }
        }
    }

    fun onActivityPaused(activity: Activity) {
        if (resumedActivity?.get() === activity) resumedActivity = null
    }

    /** From [UpdateInstallReceiver], on the main thread. The pending update is kept on every failure. */
    fun onInstallStatus(sessionId: Int, status: Int, message: String?, confirmation: Intent?) {
        val expected = committedSessionId
        if (expected != null && sessionId != -1 && sessionId != expected) return // An older session.
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirmation == null) {
                    installFailed(UpdateProblem.ConfirmationUnavailable)
                    return
                }
                val activity = resumedActivity?.get()
                if (activity != null) {
                    launchConfirmation(activity, confirmation)
                } else {
                    pendingConfirmation = confirmation // Shown on the next resume.
                }
            }
            // Android replaces the app and closes it; the new version cleans up updates/ at start.
            PackageInstaller.STATUS_SUCCESS -> committedSessionId = null
            PackageInstaller.STATUS_FAILURE_CONFLICT -> installFailed(UpdateProblem.SignedWithDifferentKey)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> installFailed(UpdateProblem.IncompatibleWithDevice)
            PackageInstaller.STATUS_FAILURE_ABORTED -> installFailed(UpdateProblem.InstallCancelled)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> installFailed(UpdateProblem.InstallBlocked)
            PackageInstaller.STATUS_FAILURE_STORAGE -> installFailed(UpdateProblem.InstallStorage)
            else -> installFailed(UpdateProblem.InstallFailed(message))
        }
    }

    private fun launchConfirmation(activity: Activity, confirmation: Intent) {
        try {
            activity.startActivity(confirmation)
        } catch (e: Exception) {
            installFailed(UpdateProblem.ConfirmationUnavailable)
        }
    }

    private fun installFailed(problem: UpdateProblem) {
        committedSessionId = null
        pendingConfirmation = null
        val version = installVersion ?: _offer.value?.version ?: ""
        if (version.isNotEmpty()) _offer.value = UpdateOffer.Install(version)
        _dialog.value = UpdateDialog.Failed(version, problem, UpdateRetry.INSTALL)
    }

    private fun offerInstall(version: String) {
        installVersion = version
        _offer.value = UpdateOffer.Install(version)
    }

    private fun offerDownload(info: UpdateInfo) {
        lastInfo = info
        val current = _offer.value
        // A downloaded update of the same (or a newer) version is installed rather than downloaded again.
        if (current is UpdateOffer.Install && !AppVersion.isNewer(info.version, current.version)) return
        _offer.value = UpdateOffer.Download(info)
    }

    private companion object {
        const val TAG = "VaultPassUpdate"
    }
}
