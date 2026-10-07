package com.example.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Downloads an [UpdateInfo] APK to updates/VaultPass-<version>.apk through a .part file that is unique to
 * each attempt. The announced size and [maxBytes] are enforced while streaming and the release digest must
 * match; on any failure or cancellation this attempt's .part file is deleted. Cleanup never touches another
 * attempt's files, so a cancelled download that finishes late (blocked in read()) cannot delete a newer
 * download. Starting a download removes older files in updates/; when [updatesDir] is not exactly
 * filesDir/updates nothing is deleted and the download fails (STORAGE).
 */
class UpdateDownloader(
    private val updatesDir: File,
    private val filesDir: File,
    private val policy: UpdateUrlPolicy = UpdateConfig.urlPolicy(),
    userAgent: String = UpdateConfig.userAgent,
    timeoutMs: Int = UpdateConfig.TIMEOUT_MS,
    private val maxBytes: Long = UpdateConfig.MAX_APK_BYTES
) {
    constructor(context: Context) : this(UpdateFiles.updatesDir(context), context.filesDir)

    private val http = UpdateHttp(policy, userAgent, timeoutMs)

    /**
     * [onProgress] gets (bytes so far, total bytes) on the IO dispatcher, at most every [PROGRESS_STEP]
     * bytes and once at the end.
     */
    suspend fun download(
        info: UpdateInfo,
        onProgress: suspend (bytes: Long, total: Long) -> Unit = { _, _ -> }
    ): DownloadResult = withContext(Dispatchers.IO) {
        val version = AppVersion.normalize(info.version)
            ?: return@withContext DownloadResult.Failed(UpdateFailure.BAD_VERSION, "Unusable version ${info.version}")
        val total = info.sizeBytes
        if (total <= 0 || total > maxBytes) {
            return@withContext DownloadResult.Failed(UpdateFailure.BAD_SIZE, "${info.assetName} is $total bytes")
        }
        val expectedSha256 = info.sha256.lowercase()
        if (!SHA256_HEX.matches(expectedSha256)) {
            return@withContext DownloadResult.Failed(UpdateFailure.NO_CHECKSUM, "No usable SHA-256 for ${info.assetName}")
        }
        if (!policy.isAllowed(info.downloadUrl)) {
            return@withContext DownloadResult.Failed(UpdateFailure.INSECURE_URL, "Refusing download URL ${info.downloadUrl}")
        }
        if (!prepareDir()) {
            return@withContext DownloadResult.Failed(UpdateFailure.STORAGE, "Cannot create $updatesDir")
        }
        val target = File(updatesDir, "VaultPass-$version.apk")
        // Unique per attempt: the cleanup below deletes only this attempt's own file.
        val part = File(updatesDir, "${target.name}.${UUID.randomUUID()}.part")
        var success = false
        try {
            val connection = http.get(info.downloadUrl, "application/octet-stream")
            val sha256 = try {
                val announced = connection.contentLengthLong
                if (announced > maxBytes) {
                    return@withContext DownloadResult.Failed(UpdateFailure.BAD_SIZE, "Server announced $announced bytes")
                }
                if (announced >= 0 && announced != total) {
                    return@withContext DownloadResult.Failed(UpdateFailure.SIZE_MISMATCH, "Server announced $announced bytes, release said $total")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var reported = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            received += read
                            if (received > total) {
                                return@withContext DownloadResult.Failed(UpdateFailure.SIZE_MISMATCH, "More than the announced $total bytes")
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            if (received - reported >= PROGRESS_STEP) {
                                reported = received
                                onProgress(received, total)
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (received != total) {
                    return@withContext DownloadResult.Failed(UpdateFailure.SIZE_MISMATCH, "Got $received of $total bytes")
                }
                onProgress(received, total)
                digest.digest().toHex()
            } finally {
                connection.disconnect()
            }
            if (expectedSha256 != sha256) {
                return@withContext DownloadResult.Failed(UpdateFailure.DIGEST_MISMATCH, "SHA-256 $sha256, release says $expectedSha256")
            }
            if ((target.exists() && !target.delete()) || !part.renameTo(target)) {
                return@withContext DownloadResult.Failed(UpdateFailure.STORAGE, "Cannot move the download to $target")
            }
            success = true
            DownloadResult.Success(target, sha256)
        } catch (e: UpdateHttp.Failure) {
            DownloadResult.Failed(e.failure, e.message)
        } catch (e: IOException) {
            DownloadResult.Failed(UpdateFailure.NETWORK, e.message)
        } finally {
            // Only this attempt's .part file. The target is never deleted here: once this attempt has
            // renamed onto it, it succeeded; before that, the target (if any) belongs to someone else.
            if (!success) part.delete()
        }
    }

    /** updates/ exists and holds nothing from earlier downloads. False (nothing touched) for any other directory. */
    private fun prepareDir(): Boolean {
        if (!UpdateFiles.isUpdatesDir(updatesDir, filesDir)) return false
        if (!updatesDir.isDirectory && !updatesDir.mkdirs()) return false
        updatesDir.listFiles()?.forEach { it.deleteRecursively() }
        return true
    }

    companion object {
        const val PROGRESS_STEP = 128L * 1024
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

/** Location and hashing helpers shared by the update classes. */
object UpdateFiles {
    fun updatesDir(context: Context): File = File(context.filesDir, UpdateConfig.UPDATES_DIR_NAME)

    /**
     * Guard for every recursive delete in the updater: true only when [dir], canonicalised, is named exactly
     * [UpdateConfig.UPDATES_DIR_NAME] and its parent is the canonical [filesDir]. A misconfigured directory
     * (filesDir itself, anything outside it, another name) must never be emptied.
     */
    fun isUpdatesDir(dir: File, filesDir: File): Boolean = try {
        val canonical = dir.canonicalFile
        canonical.name == UpdateConfig.UPDATES_DIR_NAME && canonical.parentFile == filesDir.canonicalFile
    } catch (e: IOException) {
        false
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
