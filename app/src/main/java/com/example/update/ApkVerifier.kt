package com.example.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/** SHA-256 (hex) of signing certificates. [history] is the rotation lineage, oldest first (may be empty). */
data class SignerSet(val current: Set<String>, val history: List<String> = emptyList())

/** What the updater needs from a PackageInfo. [signers] is null when they could not be read. */
data class PackageSnapshot(
    val packageName: String,
    val longVersionCode: Long,
    val versionName: String?,
    val signers: SignerSet?
)

/** Package data of the running app and of a downloaded archive (injectable for tests). */
interface PackageInfoSource {
    fun installed(): PackageSnapshot?
    fun archive(file: File): PackageSnapshot?
}

enum class ApkRejection {
    MISSING_FILE,
    /** Android could not parse the archive. */
    UNREADABLE,
    INSTALLED_INFO_UNAVAILABLE,
    WRONG_PACKAGE,
    /** versionCode is not strictly higher than the installed one. */
    NOT_NEWER,
    FOREIGN_SIGNER,
    /** The signer of the installed app or (on API 28+) of the archive could not be read. */
    SIGNER_UNREADABLE
}

sealed interface ApkVerification {
    /**
     * The archive may be offered. [signerChecked] is false only on API 24-27 when the archive's signer
     * could not be read (see [ApkVerifier]); then Android's own install-time signature check protects it.
     */
    data class Verified(
        val packageName: String,
        val versionCode: Long,
        val versionName: String?,
        val signerChecked: Boolean
    ) : ApkVerification

    /** The file has been deleted. */
    data class Rejected(val reason: ApkRejection, val detail: String? = null) : ApkVerification
}

/**
 * Checks a downloaded APK before it is offered: same package as the running app, strictly higher
 * versionCode, and signed by the installed app's signer (directly or through the archive's rotation
 * history). Anything rejected is deleted.
 *
 * A signer that can't be read is refused, with one exception: on API 24-27 Android's
 * getPackageArchiveInfo often returns no signatures for an archive. There, and only when the archive's
 * signers are the unreadable part, the update is allowed (signerChecked = false) because Android's own
 * install check still refuses an update signed by a different key than the installed app.
 */
class ApkVerifier(
    private val source: PackageInfoSource,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) {

    constructor(context: Context) : this(AndroidPackageInfoSource(context))

    fun verify(file: File): ApkVerification {
        val result = evaluate(file)
        if (result is ApkVerification.Rejected) file.delete()
        return result
    }

    private fun evaluate(file: File): ApkVerification {
        if (!file.isFile) return ApkVerification.Rejected(ApkRejection.MISSING_FILE, file.path)
        val installed = source.installed()
            ?: return ApkVerification.Rejected(ApkRejection.INSTALLED_INFO_UNAVAILABLE)
        val archive = source.archive(file)
            ?: return ApkVerification.Rejected(ApkRejection.UNREADABLE, file.name)
        if (archive.packageName != installed.packageName) {
            return ApkVerification.Rejected(
                ApkRejection.WRONG_PACKAGE,
                "${archive.packageName} is not ${installed.packageName}"
            )
        }
        if (archive.longVersionCode <= installed.longVersionCode) {
            return ApkVerification.Rejected(
                ApkRejection.NOT_NEWER,
                "versionCode ${archive.longVersionCode} <= installed ${installed.longVersionCode}"
            )
        }
        val installedSigners = installed.signers?.takeIf { it.current.isNotEmpty() }
            ?: return ApkVerification.Rejected(ApkRejection.SIGNER_UNREADABLE, "Installed app's signer unreadable")
        val archiveSigners = archive.signers?.takeIf { it.current.isNotEmpty() }
        if (archiveSigners == null) {
            if (sdkInt >= Build.VERSION_CODES.P) {
                return ApkVerification.Rejected(ApkRejection.SIGNER_UNREADABLE, "Archive signer unreadable on API $sdkInt")
            }
            // API 24-27: Android's install check still enforces the installed app's signature.
            return ApkVerification.Verified(archive.packageName, archive.longVersionCode, archive.versionName, signerChecked = false)
        }
        if (!signerMatches(installedSigners, archiveSigners)) {
            return ApkVerification.Rejected(ApkRejection.FOREIGN_SIGNER, "Signed by ${archiveSigners.current}")
        }
        return ApkVerification.Verified(archive.packageName, archive.longVersionCode, archive.versionName, signerChecked = true)
    }

    companion object {
        /** Same current signer(s), or (single signer) the installed signer appears in the archive's lineage. */
        fun signerMatches(installed: SignerSet, archive: SignerSet): Boolean {
            if (installed.current == archive.current) return true
            return installed.current.size == 1 && archive.current.size == 1 &&
                installed.current.single() in archive.history
        }
    }
}

/** PackageManager-backed [PackageInfoSource]. */
class AndroidPackageInfoSource(private val context: Context) : PackageInfoSource {

    @Suppress("DEPRECATION")
    private val flags: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // GET_SIGNATURES as well: some archives come back without signingInfo.
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }

    override fun installed(): PackageSnapshot? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, flags)?.let(::snapshot)
    } catch (e: Exception) {
        null
    }

    override fun archive(file: File): PackageSnapshot? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)?.let(::snapshot)
    } catch (e: Exception) {
        null
    }

    private fun snapshot(info: PackageInfo): PackageSnapshot = PackageSnapshot(
        packageName = info.packageName,
        longVersionCode = PackageInfoCompat.getLongVersionCode(info),
        versionName = info.versionName,
        signers = signers(info)
    )

    companion object {
        /** Prefers signingInfo (API 28+, with rotation history); falls back to the legacy signatures array. */
        @Suppress("DEPRECATION")
        fun signers(info: PackageInfo): SignerSet? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo
                val contents = signingInfo?.apkContentsSigners
                if (signingInfo != null && !contents.isNullOrEmpty()) {
                    val history = if (signingInfo.hasMultipleSigners()) {
                        emptyList()
                    } else {
                        signingInfo.signingCertificateHistory.orEmpty().map(::digest)
                    }
                    return SignerSet(contents.map(::digest).toSet(), history)
                }
            }
            val legacy = info.signatures
            if (legacy.isNullOrEmpty()) return null
            return SignerSet(legacy.map(::digest).toSet())
        }

        private fun digest(signature: Signature): String =
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
    }
}
