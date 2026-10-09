package com.example

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.PasswordGeneratorScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F21: no fake breach check, and the analysis uses the real 88-character pool. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasswordGeneratorScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun analysis_showsRealPoolAndNoPwnedClaim() {
        composeTestRule.setContent {
            PasswordGeneratorScreen(navController = rememberNavController())
        }

        composeTestRule.onNodeWithText("Pwned Check").assertDoesNotExist()
        composeTestRule.onNodeWithText("Clear").assertDoesNotExist()
        // Default: 18 characters, all four sets. 18 * log2(88) = 116 bits; the old count of 94 gave 117.
        composeTestRule.onNodeWithText("88 chars").assertExists()
        composeTestRule.onNodeWithText("116 bits").assertExists()
    }
}
