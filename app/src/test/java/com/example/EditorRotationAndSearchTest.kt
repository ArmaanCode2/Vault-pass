package com.example

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.example.domain.models.VaultEntry
import com.example.security.VaultSessionManager
import com.example.ui.VaultViewModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.PasswordEntryScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F24: edits survive a rotation. F29: the keyboard's Search key keeps the query. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorRotationAndSearchTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext<Context>() as VaultPassApplication
        VaultSessionManager.resetForTesting()
        withContext(Dispatchers.IO) { app.container.appDatabase.clearAllTables() }
        viewModel = VaultViewModel(app.container.vaultRepository, app.container.settingsRepository)
        viewModel.lock()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        viewModel.setupMasterPasswordSync("MasterPassword123!")
        assertTrue("Vault unlocked for tests", viewModel.isUnlocked.first())
    }

    private suspend fun insert(title: String): VaultEntry {
        val repo = app.container.vaultRepository
        repo.insertEntry(VaultEntry(title = title, username = "me", password = "secret"))
        return repo.decryptedEntries.value.first { it.title == title }
    }

    @Test
    fun editsSurviveARotation_insteadOfBeingReloadedFromTheVault() {
        val entry = runBlocking { insert("Original title") }
        val restoration = StateRestorationTester(composeTestRule)
        restoration.setContent {
            PasswordEntryScreen(viewModel = viewModel, navController = rememberNavController(), entryId = entry.id)
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(hasText("Original title")).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNode(hasText("Original title").and(hasSetTextAction())).performTextReplacement("Edited title")

        // What a rotation does to the screen's state: saved, then restored into a new composition.
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasText("Edited title").and(hasSetTextAction())).assertExists()
        assertTrue(
            "The reload must not overwrite the edit",
            composeTestRule.onAllNodes(hasText("Original title")).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun theSearchKey_keepsTheQueryAndTheFilteredList() {
        runBlocking {
            insert("GitHub")
            insert("Bank")
        }
        var keyboardHidden = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() {}
            override fun hide() {
                keyboardHidden++
            }
        }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                DashboardScreen(viewModel = viewModel, navController = rememberNavController())
            }
        }
        // The dashboard's own list starts below its header; the search results start at the top.
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("Search").performClick()

        val searchField = composeTestRule.onNode(hasText("Search vault…").and(hasSetTextAction()))
        searchField.performTextInput("git")
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(hasText("GitHub")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasText("git").and(hasSetTextAction())).performImeAction()
        composeTestRule.waitForIdle()

        assertEquals("git", viewModel.searchQuery.value)
        assertTrue("the Search key hides the keyboard", keyboardHidden > 0)
        composeTestRule.onNode(hasText("git").and(hasSetTextAction())).assertExists()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(hasText("Bank")).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithText("GitHub").assertExists()
    }
}
