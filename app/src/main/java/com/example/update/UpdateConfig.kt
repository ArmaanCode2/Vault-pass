package com.example.update

import com.example.BuildConfig
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/** Constants and build-type switches of the in-app updater. */
object UpdateConfig {
    /** The only feed release builds ever use (drafts and prereleases are excluded by GitHub). */
    const val GITHUB_FEED_URL = "https://api.github.com/repos/ArmaanCode2/Vault-pass/releases/latest"
    const val PREFERRED_ASSET_NAME = "VaultPass.apk"
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val TIMEOUT_MS = 10_000
    const val UPDATES_DIR_NAME = "updates"

    private const val RELEASE_BUILD_TYPE = "release"

    /**
     * Always GitHub in release, whatever BuildConfig says. Debug is blank (updater hidden) unless built with
     * -PupdateFeedUrl; updateTest uses -PupdateFeedUrl or GitHub.
     */
    val feedUrl: String get() = feedUrlFor(BuildConfig.BUILD_TYPE, BuildConfig.UPDATE_FEED_URL)

    /** The whole update UI (settings, banner, install) is hidden in builds without a feed. */
    val isEnabled: Boolean get() = feedUrl.isNotBlank()

    val userAgent: String get() = "VaultPass-Android/${BuildConfig.VERSION_NAME}"

    fun urlPolicy(): UpdateUrlPolicy = urlPolicyFor(BuildConfig.BUILD_TYPE, BuildConfig.UPDATE_ALLOW_LOCALHOST_HTTP)

    /** Code-level lock: a release build ignores the configured feed and always uses [GITHUB_FEED_URL]. */
    internal fun feedUrlFor(buildType: String, configuredFeedUrl: String): String =
        if (buildType == RELEASE_BUILD_TYPE) GITHUB_FEED_URL else configuredFeedUrl.trim()

    /** Code-level lock: a release build never allows plain HTTP, not even to localhost. */
    internal fun urlPolicyFor(buildType: String, allowLocalhostHttp: Boolean): UpdateUrlPolicy =
        UpdateUrlPolicy(allowLocalhostHttp = allowLocalhostHttp && buildType != RELEASE_BUILD_TYPE)
}

/**
 * Which URLs the updater may contact (feed, download and every redirect hop). HTTPS always;
 * plain HTTP only to localhost / 127.0.0.1 and only when [allowLocalhostHttp] (debug, updateTest).
 */
class UpdateUrlPolicy(val allowLocalhostHttp: Boolean) {

    /** The parsed URL when [url] may be contacted, else null. */
    fun check(url: String): URL? {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (uri.rawUserInfo != null) return null
        val allowed = when (scheme) {
            "https" -> true
            "http" -> allowLocalhostHttp && host in LOCAL_HOSTS
            else -> false
        }
        if (!allowed) return null
        return try {
            uri.toURL()
        } catch (e: Exception) {
            null
        }
    }

    fun isAllowed(url: String): Boolean = check(url) != null

    private companion object {
        val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")
    }
}
