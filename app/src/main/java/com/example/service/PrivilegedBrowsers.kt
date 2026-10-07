package com.example.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Google's list of privileged apps (browsers) used by Credential Manager / Google Password Manager, bundled
 * unmodified as assets/privileged_browsers.json (source, date and checksum in
 * assets/licenses/privileged_browsers_NOTICE.txt).
 *
 * Only `"type": "android"` apps and `"build": "release"` signatures are used: userdebug certificates are
 * development keys and are never trusted. Fingerprints are stored as 64 uppercase hex digits without colons.
 */
class PrivilegedBrowserList private constructor(
    private val releaseFingerprints: Map<String, Set<String>>
) {
    /** Package names with at least one release signing certificate. */
    val packageNames: Set<String> get() = releaseFingerprints.keys

    /** Release signing-certificate SHA-256 fingerprints listed for [packageName]; empty when it is not listed. */
    fun fingerprints(packageName: String): Set<String> = releaseFingerprints[packageName].orEmpty()

    companion object {
        const val ASSET_NAME = "privileged_browsers.json"

        @Volatile
        private var loaded: PrivilegedBrowserList? = null

        /** The bundled list, parsed once. Reads an asset, so it must be called off the main thread. */
        fun get(context: Context): PrivilegedBrowserList {
            loaded?.let { return it }
            return synchronized(this) {
                loaded ?: context.applicationContext.assets.open(ASSET_NAME).use { parse(it) }.also { loaded = it }
            }
        }

        /** Parses the list's JSON format: `{"apps":[{"type":"android","info":{"package_name":..,"signatures":[..]}}]}`. */
        fun parse(input: InputStream): PrivilegedBrowserList {
            val root = Json.parseToJsonElement(input.bufferedReader(Charsets.UTF_8).readText()) as JsonObject
            val apps = root["apps"] as? JsonArray ?: error("No apps array")
            val result = HashMap<String, MutableSet<String>>()
            for (app in apps) {
                val appObject = app as? JsonObject ?: continue
                if (appObject["type"]?.jsonPrimitive?.content != "android") continue
                val info = appObject["info"] as? JsonObject ?: continue
                val packageName = info["package_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: continue
                val signatures = info["signatures"] as? JsonArray ?: continue
                for (signature in signatures) {
                    val signatureObject = signature as? JsonObject ?: continue
                    if (signatureObject["build"]?.jsonPrimitive?.content != "release") continue
                    val fingerprint = normalizeFingerprint(signatureObject["cert_fingerprint_sha256"]?.jsonPrimitive?.content)
                        ?: continue
                    result.getOrPut(packageName) { HashSet() } += fingerprint
                }
            }
            return PrivilegedBrowserList(result)
        }

        /** `F0:FD:6c:...` -> `F0FD6C...`; null unless it is exactly 32 bytes of hex. */
        fun normalizeFingerprint(value: String?): String? {
            val hex = value?.replace(":", "")?.trim()?.uppercase() ?: return null
            return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
        }

        /** SHA-256 of a DER-encoded certificate, in the list's normalized form. */
        fun certificateFingerprint(certificate: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02X".format(it) }
    }
}

/** What [BrowserVerifier] needs to know about installed packages (abstracted for tests). */
interface InstalledPackageSigners {
    /** Changes when the package is installed, reinstalled or updated; null when it is not installed. */
    fun installStamp(packageName: String): Pair<Long, Long>?

    /** DER-encoded certificates the installed package is currently signed with (no past certificates). */
    fun currentSigningCertificates(packageName: String): List<ByteArray>
}

/**
 * Decides whether a package's reported webDomain can be trusted: the package must be in Google's privileged list
 * ([PrivilegedBrowserList]) and every certificate it is currently signed with must be one of the release
 * fingerprints listed for it. A package name alone proves nothing (any sideloaded app can use an unused name).
 *
 * Results are cached per process and recomputed when the package is installed again or updated. Any error
 * (list not loadable, package info not readable) means "not trusted".
 */
class BrowserVerifier(
    private val list: () -> PrivilegedBrowserList,
    private val signers: InstalledPackageSigners
) {
    private data class Cached(val stamp: Pair<Long, Long>, val trusted: Boolean)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun isVerifiedBrowser(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return try {
            val listed = list().fingerprints(packageName)
            if (listed.isEmpty()) return false
            val stamp = signers.installStamp(packageName) ?: return false
            cache[packageName]?.takeIf { it.stamp == stamp }?.let { return it.trusted }
            val certificates = signers.currentSigningCertificates(packageName)
            val trusted = certificates.isNotEmpty() &&
                certificates.all { PrivilegedBrowserList.certificateFingerprint(it) in listed }
            cache[packageName] = Cached(stamp, trusted)
            trusted
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        @Volatile
        private var instance: BrowserVerifier? = null

        /** The process-wide verifier (its cache lives as long as the process). Call off the main thread. */
        fun get(context: Context): BrowserVerifier {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    BrowserVerifier(
                        list = { PrivilegedBrowserList.get(appContext) },
                        signers = PackageManagerSigners(appContext.packageManager)
                    )
                }.also { instance = it }
            }
        }
    }
}

/** [InstalledPackageSigners] backed by PackageManager: GET_SIGNING_CERTIFICATES on API 28+, GET_SIGNATURES below. */
class PackageManagerSigners(private val packageManager: PackageManager) : InstalledPackageSigners {

    override fun installStamp(packageName: String): Pair<Long, Long>? = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        info.firstInstallTime to info.lastUpdateTime
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    override fun currentSigningCertificates(packageName: String): List<ByteArray> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageManager
                .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo ?: return emptyList()
            // The certificates the APK is signed with now (all of them when there are several signers); past
            // certificates from key rotation are not considered.
            signingInfo.apkContentsSigners.orEmpty().map { it.toByteArray() }
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                .signatures.orEmpty().map { it.toByteArray() }
        }
    }
}
