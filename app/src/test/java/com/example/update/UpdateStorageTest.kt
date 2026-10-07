package com.example.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import androidx.test.core.app.ApplicationProvider
import com.example.VaultPassApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Pending update store, startup cleanup, the settings flag and the PackageManager-backed verifier source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateStorageTest {

    private lateinit var app: VaultPassApplication
    private lateinit var store: PendingUpdateStore
    private val updatesDir get() = UpdateFiles.updatesDir(app)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // The startup cleanup runs in the background; let it finish before the test touches updates/.
        runBlocking { app.container.updateEngine.awaitStartupCleanup() }
        store = PendingUpdateStore(app)
        store.clear()
        updatesDir.deleteRecursively()
    }

    private fun writeApk(name: String = "VaultPass-3.0.0.apk", content: String = "apk-bytes"): File =
        File(updatesDir.apply { mkdirs() }, name).apply { writeText(content) }

    @Test
    fun pendingUpdateRoundTrip() {
        val file = writeApk()
        val pending = PendingUpdate("3.0.0", 14, file.path, UpdateFiles.sha256(file))
        assertTrue(store.save(pending))
        assertEquals(pending, PendingUpdateStore(app).load())
        assertEquals(pending, PendingUpdateStore(app).load(verifyDigest = false))
    }

    @Test
    fun modifiedOrMissingFileClearsTheRecord() {
        val file = writeApk()
        store.save(PendingUpdate("3.0.0", 14, file.path, UpdateFiles.sha256(file)))
        file.writeText("tampered")
        assertNull(store.load())
        assertFalse(file.exists())
        assertNull(store.load(verifyDigest = false))

        val other = writeApk()
        store.save(PendingUpdate("3.0.0", 14, other.path, UpdateFiles.sha256(other)))
        other.delete()
        assertNull(store.load())
    }

    @Test
    fun fileOutsideUpdatesDirIsRefusedAndNotDeleted() {
        val outside = File(app.filesDir, "elsewhere.apk").apply { writeText("x") }
        store.save(PendingUpdate("3.0.0", 14, outside.path, UpdateFiles.sha256(outside)))
        assertNull(store.load())
        assertTrue(outside.exists())
        outside.delete()
    }

    @Test
    fun cleanupAfterInstallDeletesUpdatesDir() {
        val file = writeApk()
        writeApk("VaultPass-3.0.0.apk.part")
        store.save(PendingUpdate("3.0.0", 14, file.path, UpdateFiles.sha256(file)))

        // Not installed yet (running 13 < pending 14): kept.
        assertFalse(store.cleanupAfterInstall(13))
        assertNotNull(store.load())

        // Running the pending version (or newer): updates/ and the record are gone.
        assertTrue(store.cleanupAfterInstall(14))
        assertFalse(updatesDir.exists())
        assertNull(store.load())
    }

    @Test
    fun cleanupRemovesLeftoversWithoutRecordAndNewerRunningVersion() {
        writeApk("VaultPass-3.0.0.apk.part")
        assertTrue(store.cleanupAfterInstall(13))
        assertFalse(updatesDir.exists())

        val file = writeApk()
        store.save(PendingUpdate("3.0.0", 14, file.path, UpdateFiles.sha256(file)))
        assertTrue(store.cleanupAfterInstall(15))
        assertFalse(updatesDir.exists())
        assertFalse(store.cleanupAfterInstall(15))
    }

    @Test
    fun checkForUpdatesFlagDefaultsOff() = runBlocking {
        val settings = app.container.settingsRepository
        assertFalse(settings.checkUpdatesOnOpen.first())
        settings.setCheckUpdatesOnOpen(true)
        assertTrue(settings.checkUpdatesOnOpen.first())
        settings.setCheckUpdatesOnOpen(false)
        assertFalse(settings.checkUpdatesOnOpen.first())
    }

    @Test
    fun androidSourceReadsArchiveAndInstalledSigners() {
        val pm = shadowOf(app.packageManager)
        val ours = Signature(ByteArray(64) { 1 })
        val theirs = Signature(ByteArray(64) { 2 })
        @Suppress("DEPRECATION")
        pm.getInternalMutablePackageInfo(app.packageName).apply {
            signatures = arrayOf(ours)
            longVersionCode = 13
        }
        fun archive(name: String, packageName: String, versionCode: Long, signer: Signature?): File {
            val file = writeApk(name)
            @Suppress("DEPRECATION")
            pm.setPackageArchiveInfo(file.absolutePath, PackageInfo().apply {
                this.packageName = packageName
                longVersionCode = versionCode
                versionName = "3.0.0"
                signatures = signer?.let { arrayOf(it) }
            })
            return file
        }
        val verifier = ApkVerifier(app)

        val good = verifier.verify(archive("good.apk", app.packageName, 14, ours))
        assertEquals(ApkVerification.Verified(app.packageName, 14, "3.0.0", signerChecked = true), good)

        val foreign = archive("foreign.apk", app.packageName, 14, theirs)
        assertEquals(ApkRejection.FOREIGN_SIGNER, (verifier.verify(foreign) as ApkVerification.Rejected).reason)
        assertFalse(foreign.exists())

        val older = archive("older.apk", app.packageName, 13, ours)
        assertEquals(ApkRejection.NOT_NEWER, (verifier.verify(older) as ApkVerification.Rejected).reason)

        val otherPackage = archive("other.apk", "com.evil", 14, ours)
        assertEquals(ApkRejection.WRONG_PACKAGE, (verifier.verify(otherPackage) as ApkVerification.Rejected).reason)

        // API 34: an archive whose signer can't be read is refused (and deleted).
        val unsigned = archive("nosig.apk", app.packageName, 14, null)
        assertEquals(ApkRejection.SIGNER_UNREADABLE, (verifier.verify(unsigned) as ApkVerification.Rejected).reason)
        assertFalse(unsigned.exists())

        val garbage = writeApk("garbage.apk")
        assertEquals(ApkRejection.UNREADABLE, (verifier.verify(garbage) as ApkVerification.Rejected).reason)
        assertFalse(garbage.exists())
    }

    @Test
    fun pendingStoreUsesItsOwnPrefsFile() {
        val file = writeApk()
        store.save(PendingUpdate("3.0.0", 14, file.path, UpdateFiles.sha256(file)))
        val prefs = app.getSharedPreferences(PendingUpdateStore.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(14L, prefs.getLong("pending_version_code", -1))
        assertFalse(app.getSharedPreferences("vaultpass_sync_prefs", Context.MODE_PRIVATE).contains("pending_version_code"))
    }
}
