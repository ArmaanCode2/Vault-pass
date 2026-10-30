package com.example

import android.view.View
import com.example.service.AutofillFieldDetector
import com.example.service.AutofillFieldNode
import com.example.service.AutofillFormRecorder
import com.example.service.AutofillFormRecorder.RecordedForm
import com.example.service.AutofillFormRecorder.RecordedNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Replays every form in src/test/resources/autofill-forms/ through the field detection. The files use the debug
 * form recorder's format, so a form recorded on a device becomes a test by copying its file there and filling in
 * "expected". The current files are hand-made (see each file's "description").
 */
class AutofillFormFixturesTest {

    /** A recorded node as an [AutofillFieldNode]; its autofill ID is the node's name in the file. */
    private class FixtureNode(private val node: RecordedNode) : AutofillFieldNode<String> {
        override val autofillId: String? get() = node.id
        override val autofillHints: Array<String>? get() = node.hints?.toTypedArray()
        override val className: String? get() = node.className
        override val inputType: Int get() = node.inputType
        override val isFocused: Boolean get() = node.focused
        override val isFocusable: Boolean get() = true
        override val isClickable: Boolean get() = false
        override val isEnabled: Boolean get() = true
        override val visibility: Int get() = node.visibility
        override val idEntry: String? get() = node.idEntry
        override val hint: String? get() = node.hint
        override val webDomain: String? get() = node.webDomain
        override val webScheme: String? get() = node.webScheme
        override val autofillType: Int get() = node.autofillType
        override val width: Int get() = node.width
        override val height: Int get() = node.height
        override val htmlTag: String? get() = node.htmlTag
        override val htmlAttributes: Map<String, String> get() = node.htmlAttributes.orEmpty()
        override val maxLength: Int get() = node.maxLength
        override val childCount: Int get() = node.children.size
        override fun getChildAt(index: Int): AutofillFieldNode<String> = FixtureNode(node.children[index])
    }

    private fun fixtureFiles(): List<File> {
        val url = javaClass.classLoader!!.getResource("autofill-forms") ?: error("autofill-forms resources not found")
        return File(url.toURI()).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
    }

    private fun load(file: File): RecordedForm =
        AutofillFormRecorder.json.decodeFromString(RecordedForm.serializer(), file.readText())

    @Test
    fun everyFixture_isDetectedAsExpected() {
        val files = fixtureFiles()
        assertTrue("expected the fixture files, found ${files.size}", files.size >= 10)
        for (file in files) {
            val form = load(file)
            assertNotNull("${file.name}: no \"expected\" block", form.expected)
            val expected = form.expected!!
            val fields = AutofillFieldDetector.detect(form.windows.map { FixtureNode(it) })

            assertEquals("${file.name}: username", expected.username, fields.usernameId)
            assertEquals("${file.name}: password", expected.password, fields.passwordId)
            assertEquals("${file.name}: webDomain", expected.webDomain, fields.webDomain)
            assertEquals("${file.name}: form kind", expected.formKind ?: "NONE", fields.formKind.name)
            assertEquals("${file.name}: focused field", expected.focused, fields.focused?.id)
            assertEquals("${file.name}: one-time-code boxes", expected.otp.orEmpty(), fields.otpIds)
        }
    }

    // --- The recorder ---

    private class TreeNode(
        override val autofillId: String?,
        override val className: String? = null,
        override val inputType: Int = 0,
        override val hint: String? = null,
        override val htmlTag: String? = null,
        override val htmlAttributes: Map<String, String> = emptyMap(),
        override val isFocused: Boolean = false,
        override val webDomain: String? = null,
        val children: List<TreeNode> = emptyList()
    ) : AutofillFieldNode<String> {
        override val autofillHints: Array<String>? = null
        override val isFocusable: Boolean = true
        override val isClickable: Boolean = false
        override val isEnabled: Boolean = true
        override val visibility: Int = View.VISIBLE
        override val idEntry: String? = null
        override val autofillType: Int = View.AUTOFILL_TYPE_TEXT
        override val width: Int = 900
        override val height: Int = 140
        override val childCount: Int get() = children.size
        override fun getChildAt(index: Int): AutofillFieldNode<String> = children[index]
    }

    private val recordedTree = TreeNode(
        "root", className = "android.widget.FrameLayout", webDomain = "example.com", htmlTag = "form",
        children = listOf(
            TreeNode("u", htmlTag = "input", isFocused = true,
                htmlAttributes = mapOf("type" to "email", "name" to "email", "value" to "alice@example.com",
                    "data-prefill" to "alice@example.org")),
            TreeNode("p", htmlTag = "input", htmlAttributes = mapOf("type" to "password", "VALUE" to "hunter2")),
            TreeNode(null, className = "android.widget.TextView", hint = "Welcome")
        )
    )

    @Test
    fun recorder_keepsOnlyTheAttributesTheDetectionReads() {
        val text = AutofillFormRecorder.json.encodeToString(
            RecordedForm.serializer(), AutofillFormRecorder.record(listOf(recordedTree), "com.android.chrome")
        )
        assertFalse(text.contains("alice@example.com"))
        assertFalse(text.contains("alice@example.org"))
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("\"value\"", ignoreCase = true))
        assertTrue(text.contains("\"password\""))
    }

    @Test
    fun recordedForm_detectsTheSameFieldsAsTheLiveStructure() {
        val live = AutofillFieldDetector.detect(listOf(recordedTree))
        val form = AutofillFormRecorder.json.decodeFromString(
            RecordedForm.serializer(),
            AutofillFormRecorder.json.encodeToString(RecordedForm.serializer(), AutofillFormRecorder.record(listOf(recordedTree), "pkg"))
        )
        val replayed = AutofillFieldDetector.detect(form.windows.map { FixtureNode(it) })

        // Recorded IDs are renamed n0, n1… in tree order; the view without an autofill ID gets none.
        assertEquals("u", live.usernameId)
        assertEquals("p", live.passwordId)
        assertEquals("n1", replayed.usernameId)
        assertEquals("n2", replayed.passwordId)
        assertEquals(live.webDomain, replayed.webDomain)
        assertEquals(live.formKind, replayed.formKind)
        assertEquals(null, form.windows.single().children[2].id)
    }

    @get:org.junit.Rule
    val tempFolder = org.junit.rules.TemporaryFolder()

    @Test
    fun recorderFiles_onlyTheNewestAreKept() {
        val dir = tempFolder.newFolder("autofill-forms")
        val form = AutofillFormRecorder.record(listOf(recordedTree), "com.example/../evil")
        val files = (1..AutofillFormRecorder.MAX_FILES + 5).map { AutofillFormRecorder.save(dir, form) }

        val kept = dir.listFiles().orEmpty()
        assertEquals(AutofillFormRecorder.MAX_FILES, kept.size)
        assertTrue("The newest file is kept", kept.any { it.name == files.last().name })
        assertTrue("The package name can't leave the directory", kept.all { it.parentFile == dir && !it.name.contains("/") })
    }
}
