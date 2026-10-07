package com.example.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/** The fields of GitHub's "latest release" response that the updater reads. */
@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String? = null,
    val assets: List<GitHubAsset> = emptyList()
)

@Serializable
internal data class GitHubAsset(
    val name: String? = null,
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String? = null,
    /** "sha256:<64 hex>" on newer releases; absent on older ones (such a release is refused). */
    val digest: String? = null
)

/** A newer release with an acceptable APK asset. */
data class UpdateInfo(
    /** Normalized version, e.g. "2.7.1" (no "v"). */
    val version: String,
    val tagName: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /**
     * Lowercase hex SHA-256 from the release asset digest. Always present: a release without a usable
     * sha256 digest is refused by [UpdateChecker] ([UpdateFailure.NO_CHECKSUM]).
     */
    val sha256: String
)

enum class UpdateFailure {
    /** Connection, timeout or read error. */
    NETWORK,
    /** The server answered with a status other than 200 (after redirects). */
    HTTP_STATUS,
    /** Unparseable or oversized feed, missing redirect target. */
    BAD_RESPONSE,
    /** The release asset has no usable sha256 digest (missing, another algorithm or malformed), so it can't be verified. */
    NO_CHECKSUM,
    /** The release tag or the requested version is not a plain numeric version. */
    BAD_VERSION,
    /** The release has no VaultPass.apk and not exactly one *.apk. */
    NO_APK_ASSET,
    /** Asset size <= 0 or above [UpdateConfig.MAX_APK_BYTES]. */
    BAD_SIZE,
    /** A feed, download or redirect URL that is not HTTPS (or allowed localhost HTTP). */
    INSECURE_URL,
    TOO_MANY_REDIRECTS,
    /** The downloaded byte count differs from the size the release announced. */
    SIZE_MISMATCH,
    /** The downloaded file's SHA-256 differs from the release digest. */
    DIGEST_MISMATCH,
    /** The file could not be written or moved into place. */
    STORAGE
}

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    /** No newer release. [latestVersion] is the release's version, or null when its tag was unusable. */
    data class UpToDate(val latestVersion: String?) : UpdateCheckResult
    data class Failed(val failure: UpdateFailure, val detail: String? = null) : UpdateCheckResult
}

sealed interface DownloadResult {
    data class Success(val file: File, val sha256: String) : DownloadResult
    /** Nothing is left on disk for a failed download. */
    data class Failed(val failure: UpdateFailure, val detail: String? = null) : DownloadResult
}
