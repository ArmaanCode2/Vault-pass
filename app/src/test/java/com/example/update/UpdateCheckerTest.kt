package com.example.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val release = UpdateUrlPolicy(allowLocalhostHttp = false)
    private val localTest = UpdateUrlPolicy(allowLocalhostHttp = true)
    private val goodSha = "ab".repeat(32)

    private fun asset(name: String, size: Long = 1000, url: String = "https://github.com/x/$name", digest: String? = "sha256:$goodSha"): String {
        val digestField = if (digest == null) "" else ",\"digest\":\"$digest\""
        return """{"name":"$name","size":$size,"browser_download_url":"$url"$digestField,"content_type":"x"}"""
    }

    private fun feed(tag: String, vararg assets: String) =
        """{"tag_name":"$tag","draft":false,"prerelease":false,"name":"VaultPass $tag","assets":[${assets.joinToString(",")}]}"""

    private fun available(result: UpdateCheckResult): UpdateInfo {
        assertTrue("expected Available, got $result", result is UpdateCheckResult.Available)
        return (result as UpdateCheckResult.Available).info
    }

    private fun failure(result: UpdateCheckResult): UpdateFailure {
        assertTrue("expected Failed, got $result", result is UpdateCheckResult.Failed)
        return (result as UpdateCheckResult.Failed).failure
    }

    @Test
    fun newerReleaseWithDigestIsAvailable() {
        val info = available(
            UpdateChecker.evaluate(
                feed("v2.10.0", asset("VaultPass.apk", 4242, digest = "sha256:${goodSha.uppercase()}")),
                "2.9.0", release
            )
        )
        assertEquals("2.10.0", info.version)
        assertEquals("v2.10.0", info.tagName)
        assertEquals("VaultPass.apk", info.assetName)
        assertEquals(4242L, info.sizeBytes)
        assertEquals(goodSha, info.sha256)
        assertEquals("https://github.com/x/VaultPass.apk", info.downloadUrl)
    }

    @Test
    fun sameOrOlderOrGarbageTagOffersNothing() {
        assertEquals(UpdateCheckResult.UpToDate("2.7.0"), UpdateChecker.evaluate(feed("v2.7.0", asset("VaultPass.apk")), "2.7.0", release))
        assertEquals(UpdateCheckResult.UpToDate("2.6.3"), UpdateChecker.evaluate(feed("v2.6.3", asset("VaultPass.apk")), "2.7.0", release))
        assertEquals(UpdateFailure.BAD_VERSION, failure(UpdateChecker.evaluate(feed("nightly", asset("VaultPass.apk")), "2.7.0", release)))
        assertEquals(UpdateFailure.BAD_VERSION, failure(UpdateChecker.evaluate(feed("v9.0.0", asset("VaultPass.apk")), "dev", release)))
        assertEquals(UpdateFailure.BAD_RESPONSE, failure(UpdateChecker.evaluate("<html>rate limited</html>", "2.7.0", release)))
        assertEquals(UpdateFailure.BAD_VERSION, failure(UpdateChecker.evaluate("""{"assets":[]}""", "2.7.0", release)))
    }

    @Test
    fun assetSelection() {
        // VaultPass.apk wins over other APKs.
        assertEquals(
            "VaultPass.apk",
            available(UpdateChecker.evaluate(feed("v3.0.0", asset("v3.0.0.apk"), asset("VaultPass.apk"), asset("notes.txt")), "2.7.0", release)).assetName
        )
        // Otherwise the single *.apk.
        assertEquals(
            "v3.0.0.apk",
            available(UpdateChecker.evaluate(feed("v3.0.0", asset("v3.0.0.apk"), asset("checksums.txt")), "2.7.0", release)).assetName
        )
        // Two APKs without VaultPass.apk, or none at all: nothing.
        assertEquals(UpdateFailure.NO_APK_ASSET, failure(UpdateChecker.evaluate(feed("v3.0.0", asset("a.apk"), asset("b.apk")), "2.7.0", release)))
        assertEquals(UpdateFailure.NO_APK_ASSET, failure(UpdateChecker.evaluate(feed("v3.0.0", asset("source.zip")), "2.7.0", release)))
        assertEquals(UpdateFailure.NO_APK_ASSET, failure(UpdateChecker.evaluate(feed("v3.0.0"), "2.7.0", release)))
        assertNull(UpdateChecker.selectAsset(listOf(GitHubAsset("vaultpass.apk.sig"), GitHubAsset("x.zip"))))
    }

    @Test
    fun sizeCap() {
        val max = UpdateConfig.MAX_APK_BYTES
        assertEquals(max, available(UpdateChecker.evaluate(feed("v3.0.0", asset("VaultPass.apk", max)), "2.7.0", release)).sizeBytes)
        assertEquals(UpdateFailure.BAD_SIZE, failure(UpdateChecker.evaluate(feed("v3.0.0", asset("VaultPass.apk", max + 1)), "2.7.0", release)))
        assertEquals(UpdateFailure.BAD_SIZE, failure(UpdateChecker.evaluate(feed("v3.0.0", asset("VaultPass.apk", 0)), "2.7.0", release)))
        assertEquals(UpdateFailure.BAD_SIZE, failure(UpdateChecker.evaluate(feed("v3.0.0", asset("VaultPass.apk", -5)), "2.7.0", release)))
    }

    @Test
    fun nonHttpsDownloadRejectedInReleaseConfig() {
        val insecure = listOf(
            "http://github.com/x/VaultPass.apk",
            "http://localhost:8765/VaultPass.apk",
            "http://127.0.0.1/VaultPass.apk",
            "ftp://github.com/a.apk",
            "file:///sdcard/a.apk",
            "https://user@github.com/a.apk",
            "not a url"
        )
        for (url in insecure) {
            assertEquals(url, UpdateFailure.INSECURE_URL, failure(UpdateChecker.evaluate(feed("v3.0.0", asset("VaultPass.apk", url = url)), "2.7.0", release)))
        }
        // The debug/updateTest policy allows plain HTTP to localhost only.
        available(UpdateChecker.evaluate(feed("v3.0.0", asset("VaultPass.apk", url = "http://localhost:8765/VaultPass.apk")), "2.7.0", localTest))
        available(UpdateChecker.evaluate(feed("v3.0.0", asset("VaultPass.apk", url = "http://127.0.0.1:8765/VaultPass.apk")), "2.7.0", localTest))
        assertEquals(UpdateFailure.INSECURE_URL, failure(UpdateChecker.evaluate(feed("v3.0.0", asset("VaultPass.apk", url = "http://192.168.1.5/VaultPass.apk")), "2.7.0", localTest)))
        assertEquals(UpdateFailure.INSECURE_URL, failure(UpdateChecker.evaluate(feed("v3.0.0", asset("VaultPass.apk", url = "http://localhost.evil.com/VaultPass.apk")), "2.7.0", localTest)))
    }

    @Test
    fun releaseWithoutUsableSha256IsRefused() {
        assertEquals(goodSha, available(UpdateChecker.evaluate(feed("v3.0.0", asset("VaultPass.apk")), "2.7.0", release)).sha256)
        // Missing, another algorithm, empty or malformed: the update can't be verified, so it is refused.
        val unusable = listOf(null, "", "sha512:abc", "sha256:1234", "sha256:" + "zz".repeat(32), goodSha, "sha256:")
        for (digest in unusable) {
            assertEquals("$digest", UpdateFailure.NO_CHECKSUM, failure(UpdateChecker.evaluate(feed("v3.0.0", asset("VaultPass.apk", digest = digest)), "2.7.0", release)))
        }
        // Nothing to update: no checksum needed.
        assertEquals(UpdateCheckResult.UpToDate("2.7.0"), UpdateChecker.evaluate(feed("v2.7.0", asset("VaultPass.apk", digest = null)), "2.7.0", release))
    }

    @Test
    fun checkFetchesFeedWithHeadersAndFollowsLocalRedirect() = runBlocking {
        UpdateTestServer().use { server ->
            server.redirect("/releases/latest", "/releases/v3")
            server.body("/releases/v3", feed("v3.0.0", asset("VaultPass.apk", url = "${server.base}/VaultPass.apk")).toByteArray())
            val checker = UpdateChecker("${server.base}/releases/latest", "2.7.0", localTest, userAgent = "VaultPass-Android/2.7.0")
            val info = available(checker.check())
            assertEquals("3.0.0", info.version)
            val first = server.requests.first()
            assertEquals("/releases/latest", first.first)
            assertEquals("VaultPass-Android/2.7.0", first.second["User-Agent"])
            assertEquals("application/vnd.github+json", first.second["Accept"])
            assertEquals("/releases/v3", server.requests[1].first)
        }
    }

    @Test
    fun checkRefusesInsecureFeedAndRedirects() = runBlocking {
        UpdateTestServer().use { server ->
            server.body("/feed", feed("v3.0.0", asset("VaultPass.apk")).toByteArray())
            server.redirect("/to-http", "http://example.com/feed")
            // Release policy: a plain-HTTP feed is never contacted.
            assertEquals(UpdateFailure.INSECURE_URL, failure(UpdateChecker("${server.base}/feed", "2.7.0", release).check()))
            assertTrue(server.requests.isEmpty())
            // Local policy: a redirect to non-local HTTP is refused.
            assertEquals(UpdateFailure.INSECURE_URL, failure(UpdateChecker("${server.base}/to-http", "2.7.0", localTest).check()))
            assertEquals(UpdateFailure.HTTP_STATUS, failure(UpdateChecker("${server.base}/missing", "2.7.0", localTest).check()))
        }
    }

    @Test
    fun defaultUserAgentNamesTheVersion() {
        assertEquals("VaultPass-Android/${com.example.BuildConfig.VERSION_NAME}", UpdateConfig.userAgent)
    }
}
