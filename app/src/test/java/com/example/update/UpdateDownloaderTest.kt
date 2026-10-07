package com.example.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

class UpdateDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val localTest = UpdateUrlPolicy(allowLocalhostHttp = true)
    private val release = UpdateUrlPolicy(allowLocalhostHttp = false)
    private val apk = Random(7).nextBytes(300 * 1024)
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).toHex()

    private fun updatesDir() = File(tmp.root, "updates")

    private fun info(url: String, size: Long = apk.size.toLong(), sha256: String = apkSha) =
        UpdateInfo("3.0.0", "v3.0.0", "VaultPass.apk", url, size, sha256)

    private fun downloader(policy: UpdateUrlPolicy = localTest, maxBytes: Long = UpdateConfig.MAX_APK_BYTES) =
        UpdateDownloader(updatesDir(), tmp.root, policy, "VaultPass-Android/test", 10_000, maxBytes)

    private fun failure(result: DownloadResult): UpdateFailure {
        assertTrue("expected Failed, got $result", result is DownloadResult.Failed)
        return (result as DownloadResult.Failed).failure
    }

    private fun assertNothingLeft() {
        assertTrue("left: ${updatesDir().list()?.toList()}", updatesDir().list().isNullOrEmpty())
    }

    @Test
    fun downloadsThroughRedirectWithProgressAndDigest() = runBlocking {
        UpdateTestServer().use { server ->
            server.redirect("/releases/download/v3.0.0/VaultPass.apk", "/objects/VaultPass.apk")
            server.body("/objects/VaultPass.apk", apk)
            // An older download is replaced.
            updatesDir().mkdirs()
            File(updatesDir(), "VaultPass-2.9.0.apk").writeText("old")

            val progress = mutableListOf<Pair<Long, Long>>()
            val result = downloader().download(info("${server.base}/releases/download/v3.0.0/VaultPass.apk")) { b, t -> progress += b to t }

            assertTrue("$result", result is DownloadResult.Success)
            val success = result as DownloadResult.Success
            assertEquals(File(updatesDir(), "VaultPass-3.0.0.apk"), success.file)
            assertArrayEquals(apk, success.file.readBytes())
            assertEquals(apkSha, success.sha256)
            assertEquals(listOf("VaultPass-3.0.0.apk"), updatesDir().list()!!.toList())
            assertEquals(apk.size.toLong() to apk.size.toLong(), progress.last())
            assertTrue(progress.size >= 2)
            assertTrue(progress.all { it.second == apk.size.toLong() })
            assertEquals("application/octet-stream", server.requests.first().second["Accept"])
        }
    }

    @Test
    fun withoutUsableDigestNothingIsDownloaded() = runBlocking {
        UpdateTestServer().use { server ->
            server.body("/a.apk", apk)
            for (bad in listOf("", "1234", "zz".repeat(32))) {
                assertEquals(bad, UpdateFailure.NO_CHECKSUM, failure(downloader().download(info("${server.base}/a.apk", sha256 = bad))))
            }
            assertTrue(server.requests.isEmpty())
            assertFalse(File(updatesDir(), "VaultPass-3.0.0.apk").exists())
            // Upper-case hex from the feed is fine.
            val result = downloader().download(info("${server.base}/a.apk", sha256 = apkSha.uppercase()))
            assertEquals(apkSha, (result as DownloadResult.Success).sha256)
        }
    }

    /**
     * M6: a cancelled download stuck in read() finishes only after a newer download succeeded. Its cleanup
     * must delete only its own .part file, never the newer verified APK.
     */
    @Test
    fun lateCancelledDownloadNeverDeletesTheNewerDownload() = runBlocking {
        val release = CountDownLatch(1)
        UpdateTestServer().use { slow ->
            UpdateTestServer().use { fast ->
                slow.slowBody("/a.apk", apk, firstChunk = 200 * 1024, release = release)
                fast.body("/a.apk", apk)
                val stalled = CompletableDeferred<Unit>()
                val late = async(Dispatchers.IO) {
                    downloader().download(info("${slow.base}/a.apk")) { bytes, _ ->
                        if (bytes >= UpdateDownloader.PROGRESS_STEP) stalled.complete(Unit)
                    }
                }
                withTimeout(10_000) { stalled.await() }
                late.cancel() // Still blocked in read(): cancellation can't interrupt it.

                val newer = downloader().download(info("${fast.base}/a.apk"))
                assertTrue("$newer", newer is DownloadResult.Success)
                val file = (newer as DownloadResult.Success).file

                release.countDown() // The stalled read returns; the cancelled attempt cleans up now.
                withTimeout(10_000) { late.join() }

                assertTrue("the newer download was deleted", file.isFile)
                assertArrayEquals(apk, file.readBytes())
                assertEquals(listOf("VaultPass-3.0.0.apk"), updatesDir().list()!!.toList())
            }
        }
    }

    @Test
    fun digestMismatchDeletesFile() = runBlocking {
        UpdateTestServer().use { server ->
            server.body("/a.apk", apk)
            val result = downloader().download(info("${server.base}/a.apk", sha256 = "00".repeat(32)))
            assertEquals(UpdateFailure.DIGEST_MISMATCH, failure(result))
            assertNothingLeft()
        }
    }

    @Test
    fun redirectToHttpIsRejected() = runBlocking {
        UpdateTestServer().use { server ->
            server.redirect("/a.apk", "http://objects.githubusercontent.com/a.apk")
            assertEquals(UpdateFailure.INSECURE_URL, failure(downloader().download(info("${server.base}/a.apk"))))
            assertNothingLeft()
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun releasePolicyNeverContactsPlainHttp() = runBlocking {
        UpdateTestServer().use { server ->
            server.body("/a.apk", apk)
            assertEquals(UpdateFailure.INSECURE_URL, failure(downloader(release).download(info("${server.base}/a.apk"))))
            assertTrue(server.requests.isEmpty())
            assertFalse(File(updatesDir(), "VaultPass-3.0.0.apk").exists())
        }
    }

    @Test
    fun sizeCapIsEnforced() = runBlocking {
        UpdateTestServer().use { server ->
            server.body("/a.apk", apk)
            // Announced size above the cap: refused before connecting.
            val small = downloader(maxBytes = 1000)
            assertEquals(UpdateFailure.BAD_SIZE, failure(small.download(info("${server.base}/a.apk"))))
            assertTrue(server.requests.isEmpty())

            // The server streams more than the release announced (no Content-Length): stopped and deleted.
            server.body("/chunked.apk", apk, contentLength = -1)
            assertEquals(UpdateFailure.SIZE_MISMATCH, failure(downloader().download(info("${server.base}/chunked.apk", size = 1000))))
            assertNothingLeft()

            // Content-Length disagrees with the release size.
            assertEquals(UpdateFailure.SIZE_MISMATCH, failure(downloader().download(info("${server.base}/a.apk", size = apk.size + 10L))))
            assertNothingLeft()

            // Truncated stream.
            server.body("/short.apk", apk.copyOf(1000), contentLength = -1)
            assertEquals(UpdateFailure.SIZE_MISMATCH, failure(downloader().download(info("${server.base}/short.apk"))))
            assertNothingLeft()
        }
    }

    @Test
    fun httpErrorsAndBadVersions() = runBlocking {
        UpdateTestServer().use { server ->
            assertEquals(UpdateFailure.HTTP_STATUS, failure(downloader().download(info("${server.base}/missing.apk"))))
            assertNothingLeft()
            val traversal = info("${server.base}/a.apk").copy(version = "../../evil")
            assertEquals(UpdateFailure.BAD_VERSION, failure(downloader().download(traversal)))
        }
    }
}
