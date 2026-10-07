package com.example.update

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException

/** A downloaded and verified update waiting to be installed. */
data class PendingUpdate(
    val version: String,
    val versionCode: Long,
    val filePath: String,
    val sha256: String
) {
    val file: File get() = File(filePath)
}

/**
 * Remembers the one pending update across restarts. Invalid records (missing or modified file, file
 * outside updates/) are cleared on read, together with their file. Files are only ever deleted when
 * [updatesDir] is filesDir/updates (see [UpdateFiles.isUpdatesDir]).
 */
class PendingUpdateStore(
    private val prefs: SharedPreferences,
    private val updatesDir: File,
    private val filesDir: File
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        UpdateFiles.updatesDir(context),
        context.filesDir
    )

    /** Written to disk before returning; false if that failed. */
    fun save(update: PendingUpdate): Boolean = prefs.edit()
        .putString(KEY_VERSION, update.version)
        .putLong(KEY_VERSION_CODE, update.versionCode)
        .putString(KEY_PATH, update.filePath)
        .putString(KEY_SHA256, update.sha256.lowercase())
        .commit()

    /**
     * The pending update, or null. With [verifyDigest] the file is re-hashed (do this off the main
     * thread) and a changed file invalidates the record.
     */
    fun load(verifyDigest: Boolean = true): PendingUpdate? {
        val version = prefs.getString(KEY_VERSION, null)
        val versionCode = prefs.getLong(KEY_VERSION_CODE, -1L)
        val path = prefs.getString(KEY_PATH, null)
        val sha256 = prefs.getString(KEY_SHA256, null)
        if (version == null && path == null && sha256 == null && versionCode < 0) return null
        if (version == null || path == null || sha256 == null || versionCode <= 0) return invalid(path)
        val file = File(path)
        if (!file.isFile || !isInsideUpdatesDir(file)) return invalid(path)
        if (verifyDigest) {
            val actual = try {
                UpdateFiles.sha256(file)
            } catch (e: IOException) {
                return invalid(path)
            }
            if (actual != sha256) return invalid(path)
        }
        return PendingUpdate(version, versionCode, path, sha256)
    }

    /** Written to disk before returning; false if that failed. */
    fun clear(): Boolean = prefs.edit()
        .remove(KEY_VERSION)
        .remove(KEY_VERSION_CODE)
        .remove(KEY_PATH)
        .remove(KEY_SHA256)
        .commit()

    /**
     * Call once at process start. Deletes updates/ entirely (and the record) when the running app is at
     * least the pending version, i.e. the update was installed, or when no update is pending (leftovers
     * of an interrupted download). A pending update newer than the running app is kept.
     * Returns true when updates/ was cleaned. Deletes nothing and returns false when [updatesDir] is not
     * exactly filesDir/updates.
     */
    fun cleanupAfterInstall(runningVersionCode: Long): Boolean {
        val pendingCode = prefs.getLong(KEY_VERSION_CODE, -1L)
        val hasRecord = prefs.contains(KEY_VERSION_CODE) || prefs.contains(KEY_PATH)
        if (hasRecord && pendingCode > runningVersionCode) return false
        if (hasRecord) clear()
        if (!UpdateFiles.isUpdatesDir(updatesDir, filesDir)) return false
        if (!updatesDir.exists()) return hasRecord
        return updatesDir.deleteRecursively()
    }

    private fun invalid(path: String?): PendingUpdate? {
        if (path != null) {
            val file = File(path)
            if (isInsideUpdatesDir(file)) file.delete()
        }
        clear()
        return null
    }

    private fun isInsideUpdatesDir(file: File): Boolean = try {
        UpdateFiles.isUpdatesDir(updatesDir, filesDir) && file.canonicalFile.parentFile == updatesDir.canonicalFile
    } catch (e: IOException) {
        false
    }

    companion object {
        const val PREFS_NAME = "vaultpass_update_prefs"
        private const val KEY_VERSION = "pending_version"
        private const val KEY_VERSION_CODE = "pending_version_code"
        private const val KEY_PATH = "pending_path"
        private const val KEY_SHA256 = "pending_sha256"
    }
}
