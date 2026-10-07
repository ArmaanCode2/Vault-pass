package com.example.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApkVerifierTest {

    @get:Rule val tmp = TemporaryFolder()

    private val pkg = "com.aistudio.vaultpass.zxqwej"
    private val mine = SignerSet(setOf("aa"))
    private val installed = PackageSnapshot(pkg, 13, "2.7.0", mine)

    private class FakeSource(val installed: PackageSnapshot?, val archive: PackageSnapshot?) : PackageInfoSource {
        override fun installed() = installed
        override fun archive(file: File) = archive
    }

    private fun apk(): File = File.createTempFile("VaultPass-", ".apk", tmp.root).apply { writeText("apk") }

    private fun verify(
        archive: PackageSnapshot?,
        installedSnapshot: PackageSnapshot? = installed,
        sdkInt: Int = 34
    ): Pair<ApkVerification, File> {
        val file = apk()
        return ApkVerifier(FakeSource(installedSnapshot, archive), sdkInt).verify(file) to file
    }

    private fun assertRejected(reason: ApkRejection, result: Pair<ApkVerification, File>) {
        val (verification, file) = result
        assertTrue("expected Rejected, got $verification", verification is ApkVerification.Rejected)
        assertEquals(reason, (verification as ApkVerification.Rejected).reason)
        assertFalse("rejected file must be deleted", file.exists())
    }

    private fun assertVerified(result: Pair<ApkVerification, File>, signerChecked: Boolean = true) {
        val (verification, file) = result
        assertTrue("expected Verified, got $verification", verification is ApkVerification.Verified)
        assertEquals(signerChecked, (verification as ApkVerification.Verified).signerChecked)
        assertTrue(file.exists())
    }

    @Test
    fun sameSignerHigherVersionIsAccepted() {
        assertVerified(verify(PackageSnapshot(pkg, 14, "3.0.0", SignerSet(setOf("aa"), listOf("aa")))))
    }

    @Test
    fun otherPackageIsRejected() {
        assertRejected(ApkRejection.WRONG_PACKAGE, verify(PackageSnapshot("com.evil.vaultpass", 14, "3.0.0", mine)))
        assertRejected(ApkRejection.WRONG_PACKAGE, verify(PackageSnapshot("$pkg.debug", 14, "3.0.0", mine)))
    }

    @Test
    fun sameOrLowerVersionCodeIsRejected() {
        assertRejected(ApkRejection.NOT_NEWER, verify(PackageSnapshot(pkg, 13, "3.0.0", mine)))
        assertRejected(ApkRejection.NOT_NEWER, verify(PackageSnapshot(pkg, 12, "2.6.4", mine)))
    }

    @Test
    fun foreignSignerIsRejected() {
        assertRejected(ApkRejection.FOREIGN_SIGNER, verify(PackageSnapshot(pkg, 14, "3.0.0", SignerSet(setOf("bb")))))
        // A foreign key that merely lists ours as a co-signer is not ours.
        assertRejected(ApkRejection.FOREIGN_SIGNER, verify(PackageSnapshot(pkg, 14, "3.0.0", SignerSet(setOf("aa", "bb")))))
        // History that does not contain our key.
        assertRejected(ApkRejection.FOREIGN_SIGNER, verify(PackageSnapshot(pkg, 14, "3.0.0", SignerSet(setOf("cc"), listOf("bb", "cc")))))
    }

    @Test
    fun rotatedSignerWithOurKeyInHistoryIsAccepted() {
        assertVerified(verify(PackageSnapshot(pkg, 14, "3.0.0", SignerSet(setOf("cc"), listOf("aa", "cc")))))
    }

    @Test
    fun unreadableArchiveSignerIsRejectedOnApi28AndLater() {
        for (sdk in listOf(28, 34, 36)) {
            assertRejected(ApkRejection.SIGNER_UNREADABLE, verify(PackageSnapshot(pkg, 14, "3.0.0", null), sdkInt = sdk))
            assertRejected(ApkRejection.SIGNER_UNREADABLE, verify(PackageSnapshot(pkg, 14, "3.0.0", SignerSet(emptySet())), sdkInt = sdk))
        }
    }

    @Test
    fun unreadableArchiveSignerIsAllowedOnlyBelowApi28() {
        // API 24-27: Android often returns no archive signatures; its install check still enforces the key.
        for (sdk in 24..27) {
            assertVerified(verify(PackageSnapshot(pkg, 14, "3.0.0", null), sdkInt = sdk), signerChecked = false)
            assertVerified(verify(PackageSnapshot(pkg, 14, "3.0.0", SignerSet(emptySet())), sdkInt = sdk), signerChecked = false)
            // A readable foreign signer is still rejected there.
            assertRejected(ApkRejection.FOREIGN_SIGNER, verify(PackageSnapshot(pkg, 14, "3.0.0", SignerSet(setOf("bb"))), sdkInt = sdk))
        }
    }

    @Test
    fun unreadableInstalledSignerIsRejectedOnEveryApi() {
        for (sdk in listOf(24, 27, 28, 34)) {
            val noSigner = PackageSnapshot(pkg, 13, "2.7.0", null)
            assertRejected(ApkRejection.SIGNER_UNREADABLE, verify(PackageSnapshot(pkg, 14, "3.0.0", mine), noSigner, sdk))
            assertRejected(ApkRejection.SIGNER_UNREADABLE, verify(PackageSnapshot(pkg, 14, "3.0.0", null), noSigner, sdk))
        }
    }

    @Test
    fun unreadableOrMissingArchiveIsRejected() {
        assertRejected(ApkRejection.UNREADABLE, verify(null))
        assertRejected(ApkRejection.INSTALLED_INFO_UNAVAILABLE, verify(PackageSnapshot(pkg, 14, "3.0.0", mine), installedSnapshot = null))
        val missing = File(tmp.root, "gone.apk")
        val result = ApkVerifier(FakeSource(installed, PackageSnapshot(pkg, 14, "3.0.0", mine))).verify(missing)
        assertEquals(ApkRejection.MISSING_FILE, (result as ApkVerification.Rejected).reason)
    }
}
