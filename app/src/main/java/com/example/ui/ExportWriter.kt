package com.example.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/** The document an export is written to: a SAF document in the app, a fake in tests. */
interface ExportDocument {
    /** Its size in bytes, or null when the provider doesn't say. */
    fun size(): Long?

    /** Opens it for writing in [mode] ("wt": truncate, "w": provider-defined). */
    fun openForWriting(mode: String): OutputStream

    fun delete()

    /** Its name for the user, or null when unknown. */
    fun displayName(): String?
}

/** A document returned by ACTION_CREATE_DOCUMENT. */
class SafExportDocument(private val resolver: ContentResolver, private val uri: Uri) : ExportDocument {

    override fun size(): Long? = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index == -1 || cursor.isNull(index)) null else cursor.getLong(index)
    }

    override fun openForWriting(mode: String): OutputStream =
        resolver.openOutputStream(uri, mode) ?: throw IOException("Unable to open output stream for destination: $uri")

    override fun delete() {
        DocumentsContract.deleteDocument(resolver, uri)
    }

    override fun displayName(): String? = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index == -1) null else cursor.getString(index)
    }
}

sealed class ExportOutcome {
    /** Written completely. [fileName] is null when the provider doesn't say. */
    class Written(val fileName: String?) : ExportOutcome()

    /**
     * Not written. [mayBeIncomplete]: the file may now hold part of the export, because an
     * existing file was being overwritten (never deleted: it was the user's file), or because a
     * new one couldn't be deleted after a partial write.
     */
    class Failed(val error: Exception, val mayBeIncomplete: Boolean) : ExportOutcome()
}

/**
 * The location can't truncate the file ("wt"), and writing without truncating could corrupt an
 * existing one. Nothing was written.
 */
class CannotOverwriteSafelyException(message: String, cause: Throwable) : IOException(message, cause) {
    companion object {
        /** The file is known to exist with content. */
        fun existingFile(cause: Throwable) = CannotOverwriteSafelyException(
            "This location can't replace the existing file safely, so it was not changed. Export to a new file instead.", cause
        )

        /** Whether the file has content is unknown. */
        fun unknownFile(cause: Throwable) = CannotOverwriteSafelyException(
            "This location doesn't support safe overwriting; nothing was written. Export to a new file instead.", cause
        )
    }
}

/**
 * Writes an export so that a failure never costs the user a file:
 * - the payload is made first, off the main thread ([generateDispatcher]: VPEX runs PBKDF2), and
 *   before the document is opened, so a failure there (e.g. a locked vault) never touches it;
 * - the document is opened with "wt" (some providers don't truncate on "w", leaving the tail of a
 *   larger old file). Only a document the app created empty falls back to "w" when "wt" isn't
 *   supported; an existing file is then left alone;
 * - the document is only deleted if the app created it empty (the picker can also hand back an
 *   existing file to overwrite) and the write didn't complete;
 * - once the write completed, nothing deletes it, not even a cancellation right after.
 */
object ExportWriter {

    suspend fun export(
        document: ExportDocument,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        generateDispatcher: CoroutineDispatcher = Dispatchers.Default,
        generate: suspend () -> ByteArray
    ): ExportOutcome {
        var size: Long? = null
        var createdEmpty = false
        var opened = false
        var written = false
        try {
            // A document the picker just created is empty; one the user chose to overwrite is not.
            // Unknown counts as "not ours". Decided without interruption: a cancellation right after
            // the read must still find a fresh document to clean up.
            withContext(NonCancellable + ioDispatcher) {
                size = try {
                    document.size()
                } catch (e: Exception) {
                    null
                }
                createdEmpty = size == 0L
            }
            val payload = withContext(generateDispatcher) { generate() }
            withContext(ioDispatcher) {
                val out = try {
                    document.openForWriting("wt")
                } catch (e: Exception) {
                    if (!isUnsupportedMode(e)) {
                        // A new document is removed below; an existing file is reported as untouched.
                        if (createdEmpty) throw e
                        throw IOException("Couldn't open the file: ${e.message ?: e.javaClass.simpleName}. Nothing was changed.", e)
                    }
                    // Without truncation, an overwrite could leave old bytes after the new export.
                    if (!createdEmpty) {
                        throw if (size == null) CannotOverwriteSafelyException.unknownFile(e) else CannotOverwriteSafelyException.existingFile(e)
                    }
                    document.openForWriting("w")
                }
                opened = true
                out.use {
                    it.write(payload)
                    it.flush()
                }
                written = true
            }
        } catch (e: CancellationException) {
            if (!written) cleanUp(document, createdEmpty, ioDispatcher)
            throw e
        } catch (e: Exception) {
            val deleted = cleanUp(document, createdEmpty, ioDispatcher)
            return ExportOutcome.Failed(e, mayBeIncomplete = opened && !deleted)
        }
        // Outside the guarded part: a failure here can't make a finished export look failed.
        val name = try {
            withContext(ioDispatcher) { document.displayName() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return ExportOutcome.Written(name)
    }

    /**
     * Whether opening failed because the provider doesn't support the mode. A FileNotFoundException
     * only counts when it says so (ContentResolver reports "Unsupported mode"); otherwise it is a
     * real failure (no permission, the file is gone...).
     */
    private fun isUnsupportedMode(e: Exception): Boolean = when (e) {
        is IllegalArgumentException, is UnsupportedOperationException -> true
        is FileNotFoundException -> e.message?.contains("mode", ignoreCase = true) == true
        else -> false
    }

    /** Deletes the document only if the app created it empty; best effort. True when it was deleted. */
    private suspend fun cleanUp(document: ExportDocument, createdEmpty: Boolean, ioDispatcher: CoroutineDispatcher): Boolean {
        if (!createdEmpty) return false
        return withContext(NonCancellable + ioDispatcher) {
            try {
                document.delete()
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
