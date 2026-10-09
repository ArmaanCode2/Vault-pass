package com.example

import android.app.Activity
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.example.ui.AutofillAuthActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F20: screenshot blocking is on by default and covers the window before the setting loads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotProtectionTest {

    private fun Activity.isSecure(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    @Test
    fun screenshotBlocking_isOnWhenNeverSet() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VaultPassApplication>()
        assertTrue(app.container.settingsRepository.disableScreenshots.first())
    }

    @Test
    fun mainActivity_isSecureFromCreate() {
        // Only CREATED: the settings collector (STARTED) hasn't run yet.
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        assertTrue("FLAG_SECURE must be set before the setting loads", activity.isSecure())
    }

    @Test
    fun autofillAuthActivity_isSecureFromCreate() {
        val activity = Robolectric.buildActivity(AutofillAuthActivity::class.java).create().get()
        assertTrue("FLAG_SECURE must be set before the setting loads", activity.isSecure())
    }
}
