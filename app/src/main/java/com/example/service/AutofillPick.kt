package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.autofill.AutofillId
import com.example.domain.models.VaultEntry
import com.example.domain.models.VaultListPreview

/**
 * "Search VaultPass…": the authentication-required dataset that opens [com.example.ui.AutofillAuthActivity] in pick
 * mode, and the non-UI parts of that flow (search, the values the picked dataset fills, whether to save an app link).
 */
object AutofillPick {

    private const val PICK_SCHEME = "vaultpass-autofill-pick"
    private const val UNLOCK_SCHEME = "vaultpass-autofill-unlock"
    private const val EXTRA_USERNAME_ID = "com.example.autofill.PICK_USERNAME_ID"
    private const val EXTRA_PASSWORD_ID = "com.example.autofill.PICK_PASSWORD_ID"

    /**
     * A pick-mode launch: the requesting app, the login fields detected in its fill request and, for a verified
     * browser, the page's host ([webDomain]; null for native apps).
     */
    data class Request(
        val packageName: String,
        val usernameId: AutofillId?,
        val passwordId: AutofillId?,
        val webDomain: String? = null
    )

    /**
     * Pick mode, the requesting package and the page's domain are carried in the intent's data URI
     * (`vaultpass-autofill-pick:<package>#<webDomain>`): the fill-in intent the framework merges into the mutable
     * PendingIntent can add extras but cannot replace data that is already set.
     */
    fun createIntent(
        context: Context,
        packageName: String,
        webDomain: String?,
        usernameId: AutofillId?,
        passwordId: AutofillId?
    ): Intent =
        Intent(context, com.example.ui.AutofillAuthActivity::class.java).apply {
            data = Uri.Builder().scheme(PICK_SCHEME).opaquePart(packageName)
                .apply { if (!webDomain.isNullOrBlank()) fragment(webDomain) }
                .build()
            usernameId?.let { putExtra(EXTRA_USERNAME_ID, it) }
            passwordId?.let { putExtra(EXTRA_PASSWORD_ID, it) }
        }

    /**
     * The locked-vault "Tap to unlock" intent (whole fill response). It also carries a data URI, so a fill-in intent
     * can't turn it into pick mode.
     */
    fun createUnlockIntent(context: Context): Intent =
        Intent(context, com.example.ui.AutofillAuthActivity::class.java).apply {
            data = Uri.Builder().scheme(UNLOCK_SCHEME).opaquePart("response").build()
        }

    /** The pick request of [intent], or null when the activity was started to unlock the whole fill response. */
    fun readRequest(intent: Intent?): Request? {
        val uri = intent?.data ?: return null
        if (uri.scheme != PICK_SCHEME) return null
        val packageName = uri.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return null
        return Request(
            packageName = packageName,
            usernameId = androidx.core.content.IntentCompat.getParcelableExtra(intent, EXTRA_USERNAME_ID, AutofillId::class.java),
            passwordId = androidx.core.content.IntentCompat.getParcelableExtra(intent, EXTRA_PASSWORD_ID, AutofillId::class.java),
            webDomain = uri.fragment?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Whether picking an entry for [request] saves a link: native apps only, never web pages (verified browsers),
     * known browser package names or VaultPass itself.
     */
    fun shouldSaveLink(request: Request, ownPackageName: String): Boolean =
        request.webDomain == null &&
            AutofillCredentialMatcher.canLinkPackage(request.packageName) && request.packageName != ownPackageName

    /**
     * For a web page: the picked [entry] is for another site than [pageHost]. Returns the entry's site (its
     * registrable domain, else its host, or "" when it has no web address) when the user must confirm, null when
     * the entry belongs to the page's site. [publicSuffixList] null: hosts are compared exactly.
     */
    fun siteMismatch(entry: VaultEntry, pageHost: String, publicSuffixList: PublicSuffixList?): String? {
        val pageSite = siteOf(pageHost, publicSuffixList) ?: return ""
        val entryHost = AutofillCredentialMatcher.extractHost(entry.website) ?: return ""
        val entrySite = siteOf(entryHost, publicSuffixList) ?: return ""
        return if (entrySite == pageSite) null else entrySite
    }

    /** The site of a host for display and comparison: its registrable domain, else the host itself. */
    fun siteOf(host: String, publicSuffixList: PublicSuffixList?): String? {
        val normalized = AutofillCredentialMatcher.extractHost(host) ?: return null
        return publicSuffixList?.registrableDomain(normalized) ?: normalized
    }

    /**
     * Entries shown in the pick list, filtered by [query] (every word must appear in the title, username or website).
     * Entries that can't be decrypted are left out. Passwords, notes and custom fields are never searched.
     */
    fun search(entries: List<VaultEntry>, query: String): List<VaultEntry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return entries.filter { entry ->
            !entry.isDecryptionFailed && words.all { word ->
                entry.title.lowercase().contains(word) ||
                    entry.username.lowercase().contains(word) ||
                    entry.website.lowercase().contains(word)
            }
        }
    }

    /** The second line of a pick-list row: the same preview as every other list (never a password). */
    fun previewLine(entry: VaultEntry): String = VaultListPreview.line(entry)

    /**
     * What a dataset built from [entry] fills: the username into [usernameId] and the password into [passwordId].
     * Blank values and missing fields are skipped; empty when there is nothing to fill.
     */
    fun <ID : Any> fillValues(entry: VaultEntry, usernameId: ID?, passwordId: ID?): List<Pair<ID, String>> {
        if (entry.isDecryptionFailed) return emptyList()
        val values = mutableListOf<Pair<ID, String>>()
        if (usernameId != null && entry.username.isNotBlank()) values += usernameId to entry.username
        if (passwordId != null && entry.password.isNotBlank()) values += passwordId to entry.password
        return values
    }
}
