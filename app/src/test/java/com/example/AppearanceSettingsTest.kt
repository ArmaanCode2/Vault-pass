package com.example

import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.example.repository.SettingsRepository
import com.example.ui.screens.AppearanceOptions
import com.example.ui.theme.AccentColor
import com.example.ui.theme.FontSize
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.Typography
import com.example.ui.theme.scaled
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Appearance card: AMOLED theme, text size and accent circles, each applied the moment it's tapped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearanceSettingsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fontSize_missingOrUnknownNameReadsAsDefault() {
        assertEquals(FontSize.DEFAULT, FontSize.fromName(null))
        assertEquals(FontSize.DEFAULT, FontSize.fromName("HUGE"))
        assertEquals(FontSize.LARGEST, FontSize.fromName("LARGEST"))
    }

    @Test
    fun scaledTypography_multipliesSizeAndLineHeight() {
        val scaled = Typography.scaled(1.3f)
        assertEquals(16.sp * 1.3f, scaled.bodyLarge.fontSize)
        assertEquals(Typography.bodyLarge.lineHeight * 1.3f, scaled.bodyLarge.lineHeight)
        assertEquals(Typography.titleLarge.fontSize * 1.3f, scaled.titleLarge.fontSize)
        assertEquals(Typography.labelSmall.fontSize * 1.3f, scaled.labelSmall.fontSize)
        // Default size: the same object, nothing rebuilt.
        assertSame(Typography, Typography.scaled(1f))
    }

    @Test
    fun amoled_isBlackOnlyInDarkTheme_andTextSizeReachesTheTheme() {
        lateinit var amoled: ColorScheme
        lateinit var dark: ColorScheme
        lateinit var light: ColorScheme
        lateinit var largeTypography: androidx.compose.material3.Typography
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = true, amoled = true, fontSize = FontSize.LARGE) {
                amoled = MaterialTheme.colorScheme
                largeTypography = MaterialTheme.typography
            }
            MyApplicationTheme(darkTheme = true) { dark = MaterialTheme.colorScheme }
            MyApplicationTheme(darkTheme = false, amoled = true) { light = MaterialTheme.colorScheme }
        }
        composeTestRule.waitForIdle()

        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.Black, amoled.surface)
        // Cards stay visible on the black background.
        assertNotEquals(Color.Black, amoled.surfaceVariant)
        assertNotEquals(Color.Black, dark.background)
        assertNotEquals(Color.Black, light.background)
        assertEquals(Typography.bodyLarge.fontSize * 1.15f, largeTypography.bodyLarge.fontSize)
    }

    @Test
    fun textSize_stopsAtTwiceTheBaseSizeWithThePhonesFontScale() {
        lateinit var largest: androidx.compose.material3.Typography
        lateinit var small: androidx.compose.material3.Typography
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                MyApplicationTheme(fontSize = FontSize.LARGEST) { largest = MaterialTheme.typography }
                MyApplicationTheme(fontSize = FontSize.SMALL) { small = MaterialTheme.typography }
            }
        }
        composeTestRule.waitForIdle()

        // The phone is already at 2x: Largest adds nothing, Small still shrinks.
        assertEquals(Typography.bodyLarge.fontSize, largest.bodyLarge.fontSize)
        assertEquals(Typography.bodyLarge.fontSize * 0.85f, small.bodyLarge.fontSize)
    }

    @Test
    fun eachTapIsPassedOnAtOnce() {
        val themeModes = mutableListOf<Int>()
        val accents = mutableListOf<AccentColor>()
        val sizes = mutableListOf<FontSize>()
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = false) {
                AppearanceOptions(
                    themeMode = 0,
                    accentColor = AccentColor.BLUE,
                    fontSize = FontSize.DEFAULT,
                    onThemeModeChange = { themeModes += it },
                    onAccentColorChange = { accents += it },
                    onFontSizeChange = { sizes += it }
                )
            }
        }

        composeTestRule.onNodeWithText("AMOLED Dark Mode").performClick()
        composeTestRule.onNodeWithContentDescription("Red").performClick()
        // Below the fold on the test's small screen: the card scrolls.
        composeTestRule.onNodeWithContentDescription("Largest").performScrollTo().performClick()

        assertEquals(listOf(3), themeModes)
        assertEquals(listOf(AccentColor.RED), accents)
        assertEquals(listOf(FontSize.LARGEST), sizes)
    }

    @Test
    fun unknownStoredThemeMode_showsSystemDefault() {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = false) {
                AppearanceOptions(
                    themeMode = 9,
                    accentColor = AccentColor.BLUE,
                    fontSize = FontSize.DEFAULT,
                    onThemeModeChange = {},
                    onAccentColorChange = {},
                    onFontSizeChange = {}
                )
            }
        }

        composeTestRule.onNodeWithText("System Default").assertIsSelected()
        composeTestRule.onNodeWithContentDescription("Blue").assertIsSelected()
        composeTestRule.onNodeWithContentDescription("Default").assertIsSelected()
    }

    @Test
    fun themeModeAndFontSize_areStored() = runTest {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())

        settings.setThemeMode(3)
        settings.setFontSize(FontSize.LARGE.name)

        assertEquals(3, settings.themeMode.first())
        assertEquals("LARGE", settings.fontSize.first())
    }
}
