package com.example.update

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Recursive deletes in the updater only ever touch filesDir/updates, never a misconfigured directory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCleanupGuardTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var prefs: SharedPreferences
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("update_cleanup_guard_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        filesDir = tmp.newFolder("files")
    }

    /** A directory with a file and a nested file, standing in for data that must survive. */
    private fun populated(dir: File): List<File> {
        dir.mkdirs()
        val top = File(dir, "vault.db").apply { writeText("keep me") }
        val nested = File(File(dir, "nested").apply { mkdirs() }, "settings.pb").apply { writeText("keep me too") }
        return listOf(top, nested)
    }

    private fun misconfiguredDirs(): List<File> = listOf(
        filesDir,                                           // filesDir itself
        File(filesDir, "updates/.."),                       // resolves to filesDir
        File(tmp.newFolder("elsewhere"), "updates"),        // right name, outside filesDir
        File(filesDir, "downloads"),                        // inside filesDir, other name
        File(File(filesDir, "nested"), "updates"),          // right name, deeper than filesDir
        File(filesDir, "Updates2")
    )

    @Test
    fun guardAcceptsOnlyFilesDirUpdates() {
        assertTrue(UpdateFiles.isUpdatesDir(File(filesDir, UpdateConfig.UPDATES_DIR_NAME), filesDir))
        assertTrue(UpdateFiles.isUpdatesDir(File(File(filesDir, "x"), "../updates"), filesDir))
        for (dir in misconfiguredDirs()) {
            assertFalse("must refuse $dir", UpdateFiles.isUpdatesDir(dir, filesDir))
        }
    }

    @Test
    fun cleanupAfterInstall_neverDeletesMisconfiguredDir() {
        for (dir in misconfiguredDirs()) {
            val files = populated(dir.canonicalFile)
            // Both cleanup paths: leftovers without a record, and an installed pending update.
            val store = PendingUpdateStore(prefs, dir, filesDir)
            assertFalse("cleaned $dir", store.cleanupAfterInstall(13))
            prefs.edit().putLong("pending_version_code", 14).putString("pending_path", files[0].path).commit()
            assertFalse("cleaned $dir", store.cleanupAfterInstall(14))
            files.forEach { assertTrue("$it was deleted", it.isFile) }
            assertTrue(dir.isDirectory)
        }
    }

    @Test
    fun invalidRecordInMisconfiguredDir_fileNotDeleted() {
        val files = populated(filesDir)
        val store = PendingUpdateStore(prefs, filesDir, filesDir)
        store.save(PendingUpdate("3.0.0", 14, files[0].path, "00".repeat(32)))
        assertNull(store.load())
        assertTrue(files[0].isFile)
    }

    @Test
    fun cleanupAfterInstall_stillCleansTheRealUpdatesDir() {
        val updates = File(filesDir, UpdateConfig.UPDATES_DIR_NAME)
        populated(updates)
        val sibling = File(filesDir, "vault.db").apply { writeText("keep me") }
        assertTrue(PendingUpdateStore(prefs, updates, filesDir).cleanupAfterInstall(13))
        assertFalse(updates.exists())
        assertTrue(sibling.isFile)
    }

    @Test
    fun downloader_neverEmptiesMisconfiguredDir() = runBlocking {
        val info = UpdateInfo("3.0.0", "v3.0.0", "VaultPass.apk", "https://example.invalid/VaultPass.apk", 1024, "ab".repeat(32))
        for (dir in misconfiguredDirs()) {
            val files = populated(dir.canonicalFile)
            val result = UpdateDownloader(dir, filesDir, UpdateUrlPolicy(allowLocalhostHttp = false), "test", 1_000)
                .download(info)
            assertTrue("$dir: $result", result is DownloadResult.Failed)
            assertEquals(UpdateFailure.STORAGE, (result as DownloadResult.Failed).failure)
            files.forEach { assertTrue("$it was deleted", it.isFile) }
        }
    }
}
