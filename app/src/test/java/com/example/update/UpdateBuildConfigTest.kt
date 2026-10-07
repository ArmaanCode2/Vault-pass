package com.example.update

import com.example.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed override and localhost HTTP exist only in debug and updateTest. AGP runs unit tests for the debug
 * variant only, so the release branch applies if unit tests are ever enabled for release; until then the
 * release values are checked in the generated release BuildConfig (UPDATE_FEED_URL is always GitHub there)
 * and the code-level lock in [UpdateConfig] is tested directly below.
 */
class UpdateBuildConfigTest {

    @Test
    fun releaseAlwaysUsesGitHubOverHttps() {
        if (BuildConfig.BUILD_TYPE == "release") {
            assertEquals(UpdateConfig.GITHUB_FEED_URL, BuildConfig.UPDATE_FEED_URL)
            assertFalse(BuildConfig.UPDATE_ALLOW_LOCALHOST_HTTP)
            assertFalse(UpdateConfig.urlPolicy().isAllowed("http://localhost:8765/releases/latest"))
        } else {
            assertTrue(BuildConfig.BUILD_TYPE in setOf("debug", "updateTest"))
            assertTrue(BuildConfig.UPDATE_ALLOW_LOCALHOST_HTTP)
        }
        assertTrue(UpdateConfig.urlPolicy().isAllowed(UpdateConfig.GITHUB_FEED_URL))
    }

    @Test
    fun releaseFeedIsLockedToGitHubEvenIfBuildConfigIsWrong() {
        for (configured in listOf("", "   ", "http://localhost:8765/feed", "https://evil.example/releases/latest", UpdateConfig.GITHUB_FEED_URL)) {
            assertEquals(configured, UpdateConfig.GITHUB_FEED_URL, UpdateConfig.feedUrlFor("release", configured))
        }
        // Release never allows plain HTTP, even when the flag says so.
        assertFalse(UpdateConfig.urlPolicyFor("release", allowLocalhostHttp = true).allowLocalhostHttp)
        assertFalse(UpdateConfig.urlPolicyFor("release", allowLocalhostHttp = true).isAllowed("http://localhost:8765/feed"))
    }

    @Test
    fun debugFeedIsBlankUnlessGiven() {
        assertEquals("", UpdateConfig.feedUrlFor("debug", ""))
        assertEquals("http://localhost:8765/feed", UpdateConfig.feedUrlFor("debug", "http://localhost:8765/feed"))
        assertEquals("http://localhost:8765/feed", UpdateConfig.feedUrlFor("updateTest", "http://localhost:8765/feed"))
        assertTrue(UpdateConfig.urlPolicyFor("debug", allowLocalhostHttp = true).allowLocalhostHttp)

        // This debug build: the updater is hidden unless the tests were built with -PupdateFeedUrl.
        if (BuildConfig.BUILD_TYPE == "debug") {
            assertEquals(BuildConfig.UPDATE_FEED_URL.trim(), UpdateConfig.feedUrl)
            assertEquals(BuildConfig.UPDATE_FEED_URL.isNotBlank(), UpdateConfig.isEnabled)
        }
    }
}
