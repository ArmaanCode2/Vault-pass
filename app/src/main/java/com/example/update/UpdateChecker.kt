package com.example.update

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Asks the release feed (GitHub "latest release", or a local feed in debug/updateTest) whether a newer
 * version exists and picks its APK asset. Sends nothing but a plain GET with a User-Agent.
 */
class UpdateChecker(
    private val feedUrl: String = UpdateConfig.feedUrl,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val policy: UpdateUrlPolicy = UpdateConfig.urlPolicy(),
    userAgent: String = UpdateConfig.userAgent,
    timeoutMs: Int = UpdateConfig.TIMEOUT_MS
) {
    private val http = UpdateHttp(policy, userAgent, timeoutMs)

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val body = try {
            val connection = http.get(feedUrl, "application/vnd.github+json")
            try {
                readLimited(connection.inputStream, MAX_FEED_BYTES)
            } finally {
                connection.disconnect()
            }
        } catch (e: UpdateHttp.Failure) {
            return@withContext UpdateCheckResult.Failed(e.failure, e.message)
        } catch (e: IOException) {
            return@withContext UpdateCheckResult.Failed(UpdateFailure.NETWORK, e.message)
        }
        if (body == null) return@withContext UpdateCheckResult.Failed(UpdateFailure.BAD_RESPONSE, "Feed too large")
        evaluate(body, currentVersion, policy)
    }

    companion object {
        private const val MAX_FEED_BYTES = 2 * 1024 * 1024
        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
        private val json = Json { ignoreUnknownKeys = true }

        /** Decides from a feed body; no network. */
        fun evaluate(body: String, currentVersion: String, policy: UpdateUrlPolicy): UpdateCheckResult {
            val release = try {
                json.decodeFromString(GitHubRelease.serializer(), body)
            } catch (e: SerializationException) {
                return UpdateCheckResult.Failed(UpdateFailure.BAD_RESPONSE, "Unreadable release feed")
            } catch (e: IllegalArgumentException) {
                return UpdateCheckResult.Failed(UpdateFailure.BAD_RESPONSE, "Unreadable release feed")
            }
            val tag = release.tagName
            val version = AppVersion.normalize(tag)
            if (tag == null || version == null || AppVersion.parse(currentVersion) == null) {
                return UpdateCheckResult.Failed(UpdateFailure.BAD_VERSION, "Unusable version: tag=$tag, installed=$currentVersion")
            }
            if (!AppVersion.isNewer(version, currentVersion)) return UpdateCheckResult.UpToDate(version)

            val asset = selectAsset(release.assets)
                ?: return UpdateCheckResult.Failed(UpdateFailure.NO_APK_ASSET, "Release $tag has no single APK")
            val name = asset.name.orEmpty()
            if (asset.size <= 0 || asset.size > UpdateConfig.MAX_APK_BYTES) {
                return UpdateCheckResult.Failed(UpdateFailure.BAD_SIZE, "$name is ${asset.size} bytes")
            }
            val url = asset.downloadUrl
            if (url == null || !policy.isAllowed(url)) {
                return UpdateCheckResult.Failed(UpdateFailure.INSECURE_URL, "Refusing download URL $url")
            }
            // No usable sha256 means the download can't be checked against the release: refused.
            val sha256 = when (val digest = parseDigest(asset.digest)) {
                is Digest.Sha256 -> digest.hex
                Digest.Invalid -> return UpdateCheckResult.Failed(UpdateFailure.NO_CHECKSUM, "Malformed digest ${asset.digest}")
                Digest.None -> return UpdateCheckResult.Failed(UpdateFailure.NO_CHECKSUM, "Release $tag has no sha256 digest for $name")
            }
            return UpdateCheckResult.Available(
                UpdateInfo(
                    version = version,
                    tagName = tag,
                    assetName = name,
                    downloadUrl = url,
                    sizeBytes = asset.size,
                    sha256 = sha256
                )
            )
        }

        /** "VaultPass.apk" if present, else the only *.apk, else null (none or several). */
        internal fun selectAsset(assets: List<GitHubAsset>): GitHubAsset? {
            assets.firstOrNull { it.name == UpdateConfig.PREFERRED_ASSET_NAME }?.let { return it }
            return assets.filter { it.name?.endsWith(".apk", ignoreCase = true) == true }.singleOrNull()
        }

        internal sealed interface Digest {
            object None : Digest
            object Invalid : Digest
            data class Sha256(val hex: String) : Digest
        }

        /** Absent or another algorithm -> None; bad sha256 value -> Invalid. Both are refused by [evaluate]. */
        internal fun parseDigest(digest: String?): Digest {
            if (digest.isNullOrBlank()) return Digest.None
            val separator = digest.indexOf(':')
            if (separator < 0) return Digest.Invalid
            if (!digest.substring(0, separator).trim().equals("sha256", ignoreCase = true)) return Digest.None
            val hex = digest.substring(separator + 1).trim()
            return if (SHA256_HEX.matches(hex)) Digest.Sha256(hex.lowercase()) else Digest.Invalid
        }

        /** The stream as UTF-8, or null when it exceeds [limit] bytes. */
        private fun readLimited(input: InputStream, limit: Int): String? {
            input.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > limit) return null
                    out.write(buffer, 0, read)
                }
                return out.toString("UTF-8")
            }
        }
    }
}
