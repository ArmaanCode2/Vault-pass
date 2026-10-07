package com.example.update

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PrepareResult {
    /** Downloaded, verified and saved as the pending update; ready for the installer. */
    data class Ready(val update: PendingUpdate, val signerChecked: Boolean) : PrepareResult
    data class DownloadFailed(val failure: UpdateFailure, val detail: String?) : PrepareResult
    /** The APK was deleted. */
    data class VerificationFailed(val reason: ApkRejection, val detail: String?) : PrepareResult
}

/** What the update UI and the installer need from [UpdateEngine] (fakes in tests). */
interface UpdateOperations {
    suspend fun check(): UpdateCheckResult
    suspend fun prepare(info: UpdateInfo, onProgress: suspend (bytes: Long, total: Long) -> Unit = { _, _ -> }): PrepareResult
    suspend fun pendingUpdate(): PendingUpdate?
}

/** Check -> download -> verify -> remember, for the update UI. Installing is not done here. */
class UpdateEngine(
    val checker: UpdateChecker,
    val downloader: UpdateDownloader,
    val verifier: ApkVerifier,
    val pendingStore: PendingUpdateStore,
    private val runningVersionCode: Long = BuildConfig.VERSION_CODE.toLong()
) : UpdateOperations {
    constructor(context: Context) : this(
        UpdateChecker(),
        UpdateDownloader(context),
        ApkVerifier(context),
        PendingUpdateStore(context)
    )

    @Volatile
    private var startupCleanup: Job? = null

    /**
     * Runs [cleanupAfterInstall] once at process start on a background thread (never the main thread).
     * [check], [prepare] and [pendingUpdate] wait for it, so no update UI sees the state before it.
     */
    fun startStartupCleanup(scope: CoroutineScope): Job = startStartupCleanup(scope) { cleanupAfterInstall() }

    @VisibleForTesting
    internal fun startStartupCleanup(scope: CoroutineScope, task: () -> Unit): Job {
        val job = scope.launch(Dispatchers.IO) {
            try {
                task()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) e.printStackTrace()
            }
        }
        startupCleanup = job
        return job
    }

    val isStartupCleanupStarted: Boolean get() = startupCleanup != null

    /** Returns once the startup cleanup (if one was started) has finished. */
    suspend fun awaitStartupCleanup() {
        startupCleanup?.join()
    }

    override suspend fun check(): UpdateCheckResult {
        awaitStartupCleanup()
        return checker.check()
    }

    /** Replaces any earlier pending update. [onProgress] receives (bytes, total). */
    override suspend fun prepare(
        info: UpdateInfo,
        onProgress: suspend (bytes: Long, total: Long) -> Unit
    ): PrepareResult {
        awaitStartupCleanup()
        withContext(Dispatchers.IO) { pendingStore.clear() }
        val downloaded = when (val result = downloader.download(info, onProgress)) {
            is DownloadResult.Failed -> return PrepareResult.DownloadFailed(result.failure, result.detail)
            is DownloadResult.Success -> result
        }
        return withContext(Dispatchers.IO) {
            when (val verification = verifier.verify(downloaded.file)) {
                is ApkVerification.Rejected -> PrepareResult.VerificationFailed(verification.reason, verification.detail)
                is ApkVerification.Verified -> {
                    val update = PendingUpdate(info.version, verification.versionCode, downloaded.file.path, downloaded.sha256)
                    if (pendingStore.save(update)) {
                        PrepareResult.Ready(update, verification.signerChecked)
                    } else {
                        downloaded.file.delete()
                        PrepareResult.DownloadFailed(UpdateFailure.STORAGE, "Could not save the pending update")
                    }
                }
            }
        }
    }

    /**
     * The pending update after re-checking its file (digest, package, versionCode, signer), or null.
     * Anything no longer valid is deleted and forgotten.
     */
    override suspend fun pendingUpdate(): PendingUpdate? = withContext(Dispatchers.IO) {
        awaitStartupCleanup()
        val pending = pendingStore.load(verifyDigest = true) ?: return@withContext null
        when (val verification = verifier.verify(pending.file)) {
            is ApkVerification.Rejected -> {
                pendingStore.clear()
                null
            }
            is ApkVerification.Verified ->
                if (verification.versionCode == pending.versionCode) pending else {
                    pending.file.delete()
                    pendingStore.clear()
                    null
                }
        }
    }

    /** See [PendingUpdateStore.cleanupAfterInstall]; at process start use [startStartupCleanup]. */
    fun cleanupAfterInstall(): Boolean = pendingStore.cleanupAfterInstall(runningVersionCode)
}
