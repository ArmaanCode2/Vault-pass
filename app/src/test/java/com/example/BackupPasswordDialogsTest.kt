package com.example

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import com.example.ui.screens.EXPORT_CONFIRMATION_TAG
import com.example.ui.screens.EXPORT_PASSWORD_TAG
import com.example.ui.screens.ExportPasswordDialog
import com.example.ui.screens.IMPORT_PASSWORD_TAG
import com.example.ui.screens.ImportPasswordDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F26: the backup password is typed twice on export, and hidden on both export and import. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupPasswordDialogsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val exportButton get() = composeTestRule.onNodeWithText("Export Vault")

    private fun SemanticsNodeInteraction.shownText(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun setExportDialog(onExport: (String) -> Unit = {}) {
        composeTestRule.setContent {
            var password by remember { mutableStateOf("") }
            ExportPasswordDialog(password = password, onPasswordChange = { password = it }, onExport = onExport, onDismiss = {})
        }
    }

    @Test
    fun export_isOnlyPossible_whenBothPasswordsMatch_andAreLongEnough() {
        var exports = 0
        setExportDialog { exports++ }
        exportButton.assertIsNotEnabled()

        composeTestRule.onNodeWithTag(EXPORT_PASSWORD_TAG).performTextReplacement("Backup-Pass-99")
        exportButton.assertIsNotEnabled()

        composeTestRule.onNodeWithTag(EXPORT_CONFIRMATION_TAG).performTextReplacement("Backup-Pass-98")
        exportButton.assertIsNotEnabled()
        composeTestRule.onNodeWithText("The passwords don't match").assertExists()

        composeTestRule.onNodeWithTag(EXPORT_CONFIRMATION_TAG).performTextReplacement("Backup-Pass-99")
        exportButton.assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(1, exports) }
    }

    @Test
    fun theKeyboardsDoneKey_exportsOnlyMatchingPasswords_andPassesTheConfirmation() {
        val exported = mutableListOf<String>()
        setExportDialog { exported += it }
        composeTestRule.onNodeWithTag(EXPORT_PASSWORD_TAG).performTextReplacement("Backup-Pass-99")
        val confirmation = composeTestRule.onNodeWithTag(EXPORT_CONFIRMATION_TAG)

        confirmation.performTextReplacement("Backup-Pass-98")
        confirmation.performImeAction()
        composeTestRule.runOnIdle { assertEquals(emptyList<String>(), exported) }

        confirmation.performTextReplacement("Backup-Pass-99")
        confirmation.performImeAction()
        composeTestRule.runOnIdle { assertEquals(listOf("Backup-Pass-99"), exported) }
    }

    @Test
    fun aShortPassword_cantBeExported_evenWhenConfirmed() {
        setExportDialog()
        composeTestRule.onNodeWithTag(EXPORT_PASSWORD_TAG).performTextReplacement("short")
        composeTestRule.onNodeWithTag(EXPORT_CONFIRMATION_TAG).performTextReplacement("short")

        exportButton.assertIsNotEnabled()
        composeTestRule.onNodeWithText("At least 8 characters").assertExists()
    }

    @Test
    fun bothExportFields_areHidden() {
        setExportDialog()
        for (tag in listOf(EXPORT_PASSWORD_TAG, EXPORT_CONFIRMATION_TAG)) {
            val field = composeTestRule.onNodeWithTag(tag)
            field.performTextReplacement("Backup-Pass-99")
            assertNotEquals("Backup-Pass-99", field.shownText())
        }
    }

    @Test
    fun theImportPassword_isHiddenUntilShown() {
        composeTestRule.setContent {
            var password by remember { mutableStateOf("") }
            ImportPasswordDialog(password = password, onPasswordChange = { password = it }, isDecrypting = false, onUnlock = {}, onDismiss = {})
        }
        val field = composeTestRule.onNodeWithTag(IMPORT_PASSWORD_TAG)
        field.performTextReplacement("Backup-Pass-99")
        assertNotEquals("Backup-Pass-99", field.shownText())

        composeTestRule.onNodeWithContentDescription("Show password").performClick()
        assertEquals("Backup-Pass-99", field.shownText())
    }
}
