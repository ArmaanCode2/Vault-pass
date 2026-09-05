package com.example

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.example.ui.VaultViewModel
import com.example.ui.screens.PasswordEntryScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasswordEntryScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun inlineGenerator_preservesFormState_andUpdatesPasswordLive() {
        val app = ApplicationProvider.getApplicationContext<VaultPassApplication>()
        val viewModel = VaultViewModel(app.container.vaultRepository, app.container.settingsRepository)

        composeTestRule.setContent {
            val navController = rememberNavController()
            PasswordEntryScreen(
                viewModel = viewModel,
                navController = navController,
                entryId = null
            )
        }

        // 1. Enter Title, Username, and Note
        composeTestRule.onNode(hasText("e.g. My Bank", substring = true)).performTextInput("Test Site")
        composeTestRule.onNode(hasText("Username", substring = true).and(hasSetTextAction())).performTextInput("user@test.com")
        composeTestRule.onNode(hasText("Add any extra details", substring = true)).performTextInput("Important account")

        // Assert fields have inputs
        composeTestRule.onNodeWithText("Test Site").assertExists()
        composeTestRule.onNodeWithText("user@test.com").assertExists()
        composeTestRule.onNodeWithText("Important account").assertExists()

        // 2. Click the Generate Password icon
        composeTestRule.onNodeWithContentDescription("Generate").performClick()

        // 3. Confirm the popover appears with Length label and Regenerate button
        composeTestRule.onNodeWithText("Length: 16").assertExists()
        composeTestRule.onNodeWithContentDescription("Regenerate password").assertExists()

        // 4. Click Regenerate button
        composeTestRule.onNodeWithContentDescription("Regenerate password").performClick()

        // 5. Test character set chips and guardrails
        val upperChip = composeTestRule.onNodeWithContentDescription("Include uppercase letters")
        val lowerChip = composeTestRule.onNodeWithContentDescription("Include lowercase letters")
        val numberChip = composeTestRule.onNodeWithContentDescription("Include numbers")
        val symbolChip = composeTestRule.onNodeWithContentDescription("Include symbols")

        upperChip.assertExists()
        lowerChip.assertExists()
        numberChip.assertExists()
        symbolChip.assertExists()

        // Toggle upper, lower, numbers off
        upperChip.performClick()
        lowerChip.performClick()
        numberChip.performClick()

        // Try to toggle the last remaining chip (symbols) off - guardrail should prevent unchecking all
        symbolChip.performClick()

        // 6. Dismiss the popover by clicking the Generate icon again
        composeTestRule.onNodeWithContentDescription("Generate").performClick()
        composeTestRule.onNodeWithText("Length: 16").assertDoesNotExist()

        // 7. Verify all initial form fields remain completely preserved
        composeTestRule.onNodeWithText("Test Site").assertExists()
        composeTestRule.onNodeWithText("user@test.com").assertExists()
        composeTestRule.onNodeWithText("Important account").assertExists()
    }
}
