package com.example

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.example.domain.models.VaultEntry
import com.example.security.VaultSessionManager
import com.example.ui.VaultViewModel
import com.example.ui.screens.ENTRY_DETAILS_ROUTE
import com.example.ui.screens.PasswordDetailsScreen
import com.example.ui.screens.PasswordEntryScreen
import com.example.ui.screens.popAfterEntryDelete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F28: deleting an entry never leaves its details page on the back stack, and asks first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntryDeleteNavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel
    private lateinit var navController: NavHostController

    private val repo get() = app.container.vaultRepository
    private val dao get() = app.container.appDatabase.vaultDao()

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext<Context>() as VaultPassApplication
        VaultSessionManager.resetForTesting()
        withContext(Dispatchers.IO) {
            app.container.appDatabase.clearAllTables()
        }
        viewModel = VaultViewModel(app.container.vaultRepository, app.container.settingsRepository)
        viewModel.lock()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        viewModel.setupMasterPasswordSync("MasterPassword123!")
        assertTrue("Vault unlocked for tests", viewModel.isUnlocked.first())
    }

    /**
     * The routes of VaultApp's NavHost that matter here. With [realScreens] the entry routes show
     * the real details page and editor, otherwise placeholders (which never pop on their own).
     */
    private fun showGraph(realScreens: Boolean = true) {
        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = "dashboard") {
                composable("dashboard") { Text("Dashboard") }
                composable("weak_passwords") { Text("Weak passwords") }
                composable(ENTRY_DETAILS_ROUTE) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("entryId")?.toIntOrNull()
                    if (realScreens) PasswordDetailsScreen(viewModel = viewModel, navController = navController, entryId = id)
                    else Text("Details")
                }
                composable("edit_entry/{entryId}") { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("entryId")?.toIntOrNull()
                    if (realScreens) PasswordEntryScreen(viewModel = viewModel, navController = navController, entryId = id)
                    else Text("Editor")
                }
            }
        }
    }

    private fun navigate(vararg routes: String) {
        composeTestRule.runOnIdle { routes.forEach { navController.navigate(it) } }
    }

    private fun backStack(): List<String> = composeTestRule.runOnIdle { routesNow() }

    /** Without waiting for idle: before the (async) delete and any reload can have run. */
    private fun routesNow(): List<String> = navController.currentBackStack.value.mapNotNull { it.destination.route }

    private fun insertEntry(): VaultEntry = runBlocking {
        repo.insertEntry(VaultEntry(title = "Mail", username = "me@example.com", password = "secret", website = "example.com"))
        repo.decryptedEntries.value.single()
    }

    private fun waitForText(text: String) {
        composeTestRule.waitUntil(5_000) { composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitUntilBinned(id: Int) {
        composeTestRule.waitUntil(5_000) { runBlocking { dao.getEntryById(id)?.isDeleted == true } }
    }

    @Test
    fun deletingInTheEditor_popsTheDetailsPageToo() {
        val entry = insertEntry()
        showGraph()
        navigate("entry_details/${entry.id}")
        waitForText("Edit Entry")
        composeTestRule.onNodeWithText("Edit Entry").performClick()
        waitForText("Delete Entry")
        assertEquals(listOf("dashboard", ENTRY_DETAILS_ROUTE, "edit_entry/{entryId}"), backStack())

        composeTestRule.onNodeWithText("Delete Entry").performScrollTo().performClick()
        composeTestRule.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        // Popped at once: not left to the details page noticing the deletion when it reloads.
        assertEquals(listOf("dashboard"), routesNow())

        waitUntilBinned(entry.id)
        assertEquals(listOf("dashboard"), backStack())
        composeTestRule.onNodeWithText("Dashboard").assertExists()
    }

    @Test
    fun popAfterEntryDelete_returnsToWhereTheDetailsPageWasOpenedFrom() {
        showGraph(realScreens = false)
        navigate("weak_passwords", "entry_details/1", "edit_entry/1")
        composeTestRule.runOnIdle { popAfterEntryDelete(navController) }
        assertEquals(listOf("dashboard", "weak_passwords"), backStack())
    }

    @Test
    fun popAfterEntryDelete_withoutADetailsPage_popsOnlyTheEditor() {
        showGraph(realScreens = false)
        navigate("weak_passwords", "edit_entry/1")
        composeTestRule.runOnIdle { popAfterEntryDelete(navController) }
        assertEquals(listOf("dashboard", "weak_passwords"), backStack())
    }

    @Test
    fun anEntryInTheRecycleBin_opensNeitherDetailsNorEditor() {
        val entry = insertEntry()
        runBlocking { repo.deleteEntry(entry.id) }
        showGraph()

        for (route in listOf("entry_details/${entry.id}", "edit_entry/${entry.id}")) {
            navigate(route)
            composeTestRule.waitUntil(5_000) { navController.currentBackStackEntry?.destination?.route == "dashboard" }
            assertEquals(listOf("dashboard"), backStack())
        }
        assertTrue("Still in the recycle bin", runBlocking { dao.getEntryById(entry.id)!!.isDeleted })
    }

    @Test
    fun deletingOnTheDetailsPage_asksFirst() {
        val entry = insertEntry()
        showGraph()
        navigate("entry_details/${entry.id}")
        waitForText("Edit Entry")

        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.onNodeWithText("Delete Entry").assertExists()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Delete Entry").assertDoesNotExist()
        assertFalse("Nothing deleted yet", runBlocking { dao.getEntryById(entry.id)!!.isDeleted })
        assertEquals(listOf("dashboard", ENTRY_DETAILS_ROUTE), backStack())

        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()

        waitUntilBinned(entry.id)
        assertEquals(listOf("dashboard"), backStack())
    }
}
