package com.example

import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.repository.AutofillDiagnosticsRepository
import com.example.service.AutofillCredentialMatcher
import com.example.service.VaultAutofillService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Audit F15: autofill diagnostics write nothing in release builds and never contain entry content. */
class AutofillDiagnosticsLoggingTest {

    private class RecordingSink : AutofillDiagnosticsRepository.Sink {
        val lines = mutableListOf<String>()
        override fun write(isError: Boolean, tag: String, message: String) {
            lines += message
        }
    }

    private fun exerciseAll(diagnostics: AutofillDiagnosticsRepository) {
        diagnostics.updateRequestStart()
        diagnostics.log("message")
        diagnostics.logError("error")
        diagnostics.updatePackageAndDomain("com.example.app", "example.com")
        diagnostics.updateMatches(3, 2)
    }

    @Test
    fun disabled_writesNothing() {
        val sink = RecordingSink()
        exerciseAll(AutofillDiagnosticsRepository(enabled = false, sink = sink))
        assertTrue("Release (DEBUG = false) diagnostics must write nothing: ${sink.lines}", sink.lines.isEmpty())
    }

    @Test
    fun enabled_writesEveryCall() {
        val sink = RecordingSink()
        exerciseAll(AutofillDiagnosticsRepository(enabled = true, sink = sink))
        assertEquals(5, sink.lines.size)
    }

    @Test
    fun defaultFollowsBuildConfigDebug() {
        val sink = RecordingSink()
        AutofillDiagnosticsRepository(sink = sink).log("message")
        assertEquals(BuildConfig.DEBUG, sink.lines.isNotEmpty())
    }

    @Test
    fun matchAndDatasetLines_containNoEntryContent() {
        val entry = VaultEntry(
            id = 1,
            title = "SecretTitleXYZ",
            username = "secret.user@example.com",
            password = "Pa55wordQQQ",
            website = "https://example.com",
            notes = "private note text",
            customFields = listOf(CustomField("pin", "CustomValue987")),
            syncId = "sync-1"
        )
        val matches = AutofillCredentialMatcher.matchEntries(
            entries = listOf(entry),
            requestedPackage = "com.android.chrome",
            requestedWebDomain = "example.com",
            publicSuffixList = TestPublicSuffixList.list,
            verifiedBrowser = true
        )
        assertEquals(1, matches.size)

        val lines = matches.mapIndexed { index, match -> VaultAutofillService.matchLogLine(index, match) } +
            VaultAutofillService.datasetLogLine(1, usernamePopulated = true, passwordPopulated = true)
        assertTrue(lines.first().contains("Score=${matches.first().score}"))
        for (line in lines) {
            for (secret in listOf(entry.title, entry.username, entry.password, entry.notes, "CustomValue987")) {
                assertFalse("Log line leaks entry content '$secret': $line", line.contains(secret))
            }
        }
    }

    /**
     * Source check: no log call in the autofill code passes an entry's title, username, password, notes, custom
     * fields or a field value. Catches new log lines that the unit tests above don't exercise.
     */
    @Test
    fun autofillSources_logCallsNeverReferenceEntryContent() {
        val root = listOf(File("src/main/java/com/example"), File("app/src/main/java/com/example"))
            .firstOrNull { it.isDirectory } ?: error("main source set not found")
        val files = listOf(
            "service/VaultAutofillService.kt",
            "service/AutofillFieldDetector.kt",
            "service/AutofillCredentialMatcher.kt",
            "service/AutofillPick.kt",
            "ui/AutofillAuthActivity.kt",
            "repository/AutofillDiagnosticsRepository.kt"
        ).map { File(root, it) }
        files.forEach { assertTrue("missing ${it.path}", it.isFile) }

        val logCall = Regex("""\b(log|logError)\s*\(|\blog\?\.invoke\s*\(|\bLog\.[vdiwe]\s*\(|\bprintln\s*\(""")
        val forbidden = Regex(
            """\.(title|username|password|notes|customFields|autofillValue|text)\b|\$\{?(title|username|password|notes)\b"""
        )
        var calls = 0
        for (file in files) {
            val source = file.readText()
            for (match in logCall.findAll(source)) {
                val args = balancedArguments(source, match.range.last)
                calls++
                val hit = forbidden.find(args)
                assertTrue("${file.name}: log call references entry content (${hit?.value}): $args", hit == null)
            }
        }
        assertTrue("expected to find the autofill log calls, found $calls", calls > 20)
    }

    /** The text between the '(' at [openIndex] and its matching ')'. */
    private fun balancedArguments(source: String, openIndex: Int): String {
        var depth = 0
        for (i in openIndex until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return source.substring(openIndex + 1, i)
            }
        }
        return source.substring(openIndex + 1)
    }
}
