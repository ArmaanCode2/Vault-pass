package com.example.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun comparesNumerically() {
        assertTrue(AppVersion.isNewer("2.10.0", "2.9.0"))
        assertFalse(AppVersion.isNewer("2.9.0", "2.10.0"))
        assertTrue(AppVersion.isNewer("3.0.0", "2.99.99"))
        assertTrue(AppVersion.isNewer("2.7.1", "2.7.0"))
        assertFalse(AppVersion.isNewer("2.7.0", "2.7.0"))
        assertFalse(AppVersion.isNewer("2.6.3", "2.7.0"))
    }

    @Test
    fun acceptsVPrefix() {
        assertTrue(AppVersion.isNewer("v2.7.1", "2.7.0"))
        assertFalse(AppVersion.isNewer("v2.7.0", "2.7.0"))
        assertEquals("2.10.0", AppVersion.normalize("v2.10.0"))
        assertEquals(0, AppVersion.compare("V2.7.0", "2.7.0"))
    }

    @Test
    fun missingAndExtraComponentsCountAsZero() {
        assertEquals(0, AppVersion.compare("2.7", "2.7.0"))
        assertEquals(0, AppVersion.compare("2.7.0.0", "2.7"))
        assertTrue(AppVersion.isNewer("2.7.0.1", "2.7"))
        assertTrue(AppVersion.isNewer("3", "2.9.9"))
    }

    @Test
    fun garbageIsNeverNewer() {
        for (bad in listOf("", "v", "latest", "2.7.0-beta", "2..7", "2.7.", ".2.7", "2.x.0", "v 2.8", "1.2.3.4.5.6.7", "99999999999.0", null)) {
            assertNull("parse($bad)", AppVersion.parse(bad))
            assertFalse("isNewer($bad)", AppVersion.isNewer(bad, "2.7.0"))
            assertFalse("installed $bad", AppVersion.isNewer("9.9.9", bad))
        }
    }
}
