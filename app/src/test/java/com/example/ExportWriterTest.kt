package com.example

import com.example.security.VaultLockedException
import com.example.ui.ExportDocument
import com.example.ui.ExportOutcome
import com.example.ui.ExportWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** C1/R1: a failed or interrupted export never costs the user a file, and a finished one is never deleted. */
class ExportWriterTest {

    /** A document holding [initial]; records what happens to it. */
    private class FakeDocument(
        initial: ByteArray,
        private val failWriteAfter: Int? = null,
        private val failName: Boolean = false,
        private val rejectModes: Set<String> = emptySet(),
        private val failDelete: Boolean = false,
        private val unknownSize: Boolean = false,
        private val openError: ((String) -> Exception?)? = null,
        private val onSize: () -> Unit = {},
        private val onWrite: () -> Unit = {}
    ) : ExportDocument {
        var content: ByteArray = initial
        var deleted = false
        var opened = false
        val openedModes = mutableListOf<String>()

        override fun size(): Long? {
            onSize()
            return if (unknownSize) null else content.size.toLong()
        }

        override fun openForWriting(mode: String): OutputStream {
            openError?.invoke(mode)?.let { throw it }
            if (mode in rejectModes) throw IllegalArgumentException("Unsupported mode: $mode")
            opened = true
            openedModes += mode
            // "w" here doesn't truncate (like some providers): the old tail stays after what is written.
            val oldTail = if (mode == "wt") ByteArray(0) else content
            content = if (mode == "wt") ByteArray(0) else content
            val buffer = ByteArrayOutputStream()
            fun update() {
                val written = buffer.toByteArray()
                content = written + if (oldTail.size > written.size) oldTail.copyOfRange(written.size, oldTail.size) else ByteArray(0)
            }
            return object : OutputStream() {
                override fun write(b: Int) {
                    write(byteArrayOf(b.toByte()), 0, 1)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    val limit = failWriteAfter
                    if (limit != null) {
                        buffer.write(b, off, minOf(len, limit))
                        update()
                        throw IOException("Disk full")
                    }
                    buffer.write(b, off, len)
                    update()
                    onWrite()
                }
            }
        }

        override fun delete() {
            if (failDelete) throw IOException("Provider refused")
            deleted = true
        }

        override fun displayName(): String? = if (failName) throw IllegalStateException("Provider gone") else "vault.vpex"
    }

    private val oldBackup = "my last good backup".toByteArray()
    private val payload = "the new export".toByteArray()

    @Test
    fun noTruncatingMode_onAFreshDocument_fallsBackToPlainWrite() = runBlocking {
        val doc = FakeDocument(ByteArray(0), rejectModes = setOf("wt"))
        val outcome = ExportWriter.export(doc) { payload }

        assertTrue(outcome is ExportOutcome.Written)
        assertEquals(listOf("w"), doc.openedModes)
        assertArrayEquals(payload, doc.content)
    }

    @Test
    fun noTruncatingMode_onAnExistingFile_failsClearly_andLeavesItUntouched() = runBlocking {
        val doc = FakeDocument("a much longer old backup than the new export".toByteArray(), rejectModes = setOf("wt"))
        val before = doc.content
        val outcome = ExportWriter.export(doc) { payload }

        assertTrue(outcome is ExportOutcome.Failed)
        outcome as ExportOutcome.Failed
        assertTrue("Says why: ${outcome.error}", outcome.error is com.example.ui.CannotOverwriteSafelyException)
        assertFalse("Nothing was written to it", outcome.mayBeIncomplete)
        assertTrue("Never opened without truncation", doc.openedModes.isEmpty())
        assertFalse(doc.deleted)
        assertArrayEquals(before, doc.content)
    }

    @Test
    fun noTruncatingMode_andAnUnknownSize_saysSoNeutrally_andKeepsTheFile() = runBlocking {
        val doc = FakeDocument(oldBackup, rejectModes = setOf("wt"), unknownSize = true)
        val outcome = ExportWriter.export(doc) { payload }

        outcome as ExportOutcome.Failed
        assertEquals(
            "This location doesn't support safe overwriting; nothing was written. Export to a new file instead.",
            outcome.error.message
        )
        assertFalse(outcome.mayBeIncomplete)
        assertFalse("It may be the user's file", doc.deleted)
        assertTrue(doc.openedModes.isEmpty())
        assertArrayEquals(oldBackup, doc.content)
    }

    @Test
    fun anExistingFileThatCantBeOpened_reportsTheRealReason_notTheModeMessage() = runBlocking {
        val doc = FakeDocument(oldBackup, openError = { java.io.FileNotFoundException("Permission denied") })
        val outcome = ExportWriter.export(doc) { payload }

        outcome as ExportOutcome.Failed
        assertFalse(outcome.error is com.example.ui.CannotOverwriteSafelyException)
        assertEquals("Couldn't open the file: Permission denied. Nothing was changed.", outcome.error.message)
        assertFalse(outcome.mayBeIncomplete)
        assertFalse(doc.deleted)
        assertArrayEquals(oldBackup, doc.content)
    }

    @Test
    fun anExistingFile_whoseProviderReportsTheModeAsUnsupported_isLeftAlone() = runBlocking {
        val doc = FakeDocument(oldBackup, openError = { mode ->
            if (mode == "wt") java.io.FileNotFoundException("Unsupported mode: wt") else null
        })
        val outcome = ExportWriter.export(doc) { payload }

        outcome as ExportOutcome.Failed
        assertTrue(outcome.error is com.example.ui.CannotOverwriteSafelyException)
        assertTrue("Never opened with plain \"w\"", doc.openedModes.isEmpty())
        assertArrayEquals(oldBackup, doc.content)
    }

    @Test
    fun aCancellationRightAfterTheSizeRead_stillCleansUpTheFreshDocument() = runBlocking {
        var export: Deferred<ExportOutcome>? = null
        val doc = FakeDocument(ByteArray(0), onSize = { export?.cancel() })
        export = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            ExportWriter.export(doc) { payload }
        }
        export.start()
        try {
            export.await()
            fail("The cancellation must reach the caller")
        } catch (expected: CancellationException) {
        }
        assertTrue("The empty document the picker created is removed", doc.deleted)
        assertFalse(doc.opened)
    }

    @Test
    fun aPartlyWrittenFreshDocumentThatCantBeDeleted_isReportedAsMaybeIncomplete() = runBlocking {
        val doc = FakeDocument(ByteArray(0), failWriteAfter = 3, failDelete = true)
        val outcome = ExportWriter.export(doc) { payload }

        assertTrue(outcome is ExportOutcome.Failed)
        assertTrue((outcome as ExportOutcome.Failed).mayBeIncomplete)
    }

    @Test
    fun thePayloadIsMadeOffTheCallingThread_beforeTheDocumentIsOpened() = runBlocking {
        val caller = Thread.currentThread()
        var generatedOn: Thread? = null
        val doc = FakeDocument(ByteArray(0))
        val outcome = ExportWriter.export(doc) {
            generatedOn = Thread.currentThread()
            assertFalse("Generated before the document is opened", doc.opened)
            payload
        }

        assertTrue(outcome is ExportOutcome.Written)
        assertNotSame(caller, generatedOn)
    }

    @Test
    fun generationFails_onAnExistingFile_leavesItUntouched() = runBlocking {
        val doc = FakeDocument(oldBackup)
        val outcome = ExportWriter.export(doc) { throw VaultLockedException() }

        assertTrue(outcome is ExportOutcome.Failed)
        assertFalse("Not an overwrite that broke halfway", (outcome as ExportOutcome.Failed).mayBeIncomplete)
        assertFalse(doc.deleted)
        assertFalse("Never even opened", doc.opened)
        assertArrayEquals(oldBackup, doc.content)
    }

    @Test
    fun generationFails_onAFreshEmptyDocument_deletesIt() = runBlocking {
        val doc = FakeDocument(ByteArray(0))
        val outcome = ExportWriter.export(doc) { throw VaultLockedException() }

        assertTrue(outcome is ExportOutcome.Failed)
        assertTrue("No empty export left behind", doc.deleted)
    }

    @Test
    fun writeFails_onAFreshDocument_deletesIt() = runBlocking {
        val doc = FakeDocument(ByteArray(0), failWriteAfter = 3)
        val outcome = ExportWriter.export(doc) { payload }

        assertTrue(outcome is ExportOutcome.Failed)
        assertTrue("No partial export left behind", doc.deleted)
    }

    @Test
    fun writeFails_whileOverwritingAnExistingFile_keepsItAndSaysItMayBeIncomplete() = runBlocking {
        val doc = FakeDocument(oldBackup, failWriteAfter = 3)
        val outcome = ExportWriter.export(doc) { payload }

        assertTrue(outcome is ExportOutcome.Failed)
        assertTrue((outcome as ExportOutcome.Failed).mayBeIncomplete)
        assertFalse("The user's file is never deleted", doc.deleted)
    }

    @Test
    fun aSuccessfulWrite_isNeverDeleted_evenIfALaterStepFails() = runBlocking {
        val doc = FakeDocument(ByteArray(0), failName = true)
        val outcome = ExportWriter.export(doc) { payload }

        assertTrue(outcome is ExportOutcome.Written)
        assertNull((outcome as ExportOutcome.Written).fileName)
        assertFalse(doc.deleted)
        assertArrayEquals(payload, doc.content)
    }

    @Test
    fun anOverwrite_replacesTheWholeOldContent() = runBlocking {
        val doc = FakeDocument("a much longer old backup than the new export".toByteArray())
        val outcome = ExportWriter.export(doc) { payload }

        assertEquals("vault.vpex", (outcome as ExportOutcome.Written).fileName)
        assertArrayEquals("Truncated, no old tail", payload, doc.content)
    }

    @Test
    fun cancellationAfterACompletedWrite_keepsTheFile_andIsRethrown() = runBlocking {
        var export: Deferred<ExportOutcome>? = null
        // The screen goes away right as the last byte is written (a fresh, empty document).
        val doc = FakeDocument(ByteArray(0), onWrite = { export?.cancel() })
        export = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            ExportWriter.export(doc) { payload }
        }
        export.start()
        try {
            export.await()
            fail("The cancellation must reach the caller")
        } catch (expected: CancellationException) {
        }
        assertFalse("A finished export is never deleted", doc.deleted)
        assertArrayEquals(payload, doc.content)
    }

    @Test
    fun cancellationBeforeWriting_onAFreshDocument_deletesIt_andIsRethrown() = runBlocking {
        val doc = FakeDocument(ByteArray(0))
        val export = async(Dispatchers.Default) {
            ExportWriter.export(doc) { throw CancellationException("Screen closed") }
        }
        try {
            export.await()
            fail("The cancellation must reach the caller")
        } catch (expected: CancellationException) {
        }
        assertTrue(doc.deleted)
    }
}
