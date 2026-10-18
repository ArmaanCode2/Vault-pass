package com.example

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ui.screens.ScoreCountsRow
import com.example.ui.screens.SecurityScoreCard
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The score card on the Vault tab: each count still opens what it counts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityScoreCardTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun eachCountKeepsItsOwnTapAction() {
        val taps = mutableListOf<String>()
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = false) {
                SecurityScoreCard(score = 72, status = "Good") {
                    ScoreCountsRow(
                        total = 38,
                        weak = 3,
                        reused = 2,
                        onTotalClick = { taps += "total" },
                        onWeakClick = { taps += "weak_passwords" },
                        onReusedClick = { taps += "reused_passwords" }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("TOTAL").performClick()
        composeTestRule.onNodeWithText("WEAK").performClick()
        composeTestRule.onNodeWithText("REUSED").performClick()

        assertEquals(listOf("total", "weak_passwords", "reused_passwords"), taps)
        composeTestRule.onNodeWithText("72").assertExists()
        composeTestRule.onNodeWithText("Good").assertExists()
    }

    @Test
    fun scoreNotWorkedOutYet_showsAnalyzing() {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = false) {
                SecurityScoreCard(score = null, status = null) {}
            }
        }

        composeTestRule.onNodeWithText("Analyzing…").assertExists()
    }
}
