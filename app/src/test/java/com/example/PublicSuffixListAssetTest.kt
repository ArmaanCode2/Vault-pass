package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.PublicSuffixList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The list is packaged as an app asset and loaded once through [PublicSuffixList.get]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PublicSuffixListAssetTest {

    @Test
    fun loadsFromAssets_once() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = PublicSuffixList.get(context)

        assertEquals("example.co.uk", first.registrableDomain("www.example.co.uk"))
        assertNull(first.registrableDomain("github.io"))
        assertSame(first, PublicSuffixList.get(context))
    }
}
