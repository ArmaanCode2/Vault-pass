package com.example

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.ui.screens.rememberExportFormat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The export format survives the screen being recreated while the file picker is open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportFormatStateTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theChosenFormat_survivesRecreation() {
        val restoration = StateRestorationTester(composeTestRule)
        lateinit var format: MutableState<String>
        restoration.setContent { format = rememberExportFormat() }

        composeTestRule.runOnIdle {
            assertEquals("vpex", format.value)
            format.value = "txt"
        }
        restoration.emulateSavedInstanceStateRestore()

        composeTestRule.runOnIdle { assertEquals("txt", format.value) }
    }
}
