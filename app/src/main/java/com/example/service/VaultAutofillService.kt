package com.example.service

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class VaultAutofillService : AutofillService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onConnected() {
        super.onConnected()
        val app = application as com.example.VaultPassApplication
        app.container.autofillDiagnosticsRepository.log("Service connected to Android OS")
    }

    override fun onDisconnected() {
        super.onDisconnected()
        val app = application as com.example.VaultPassApplication
        app.container.autofillDiagnosticsRepository.log("Service disconnected from Android OS")
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val app = application as com.example.VaultPassApplication
        val diagnostics = app.container.autofillDiagnosticsRepository
        
        diagnostics.updateRequestStart()
        diagnostics.log("--- onFillRequest triggered ---")

        if (cancellationSignal.isCanceled) {
            diagnostics.log("Fill request was pre-cancelled by OS.")
            return
        }

        val callbackInvoked = AtomicBoolean(false)
        fun safeSuccess(response: android.service.autofill.FillResponse?) {
            if (cancellationSignal.isCanceled) return
            if (callbackInvoked.compareAndSet(false, true)) {
                try {
                    callback.onSuccess(response)
                } catch (e: Exception) {
                    diagnostics.logError("Failed to invoke onSuccess: ${e.message}")
                }
            }
        }
        
        try {
            val dek = app.container.cryptoManager.getSoftwareDek()
            val isUnlocked = dek != null
            dek?.let { java.util.Arrays.fill(it, 0.toByte()) }

            if (!isUnlocked) {
                diagnostics.log("Vault is locked. Launching Authentication Bridge.")
                // Attach the authentication to the detected login fields (the same detection the unlocked path
                // uses): Android only shows the unlock chip when one of the listed views is focused.
                val lockedStructure = request.fillContexts.lastOrNull()?.structure
                val ids = lockedStructure?.let {
                    AutofillFieldDetector.detect(it) { message -> diagnostics.log(message) }.fieldIds
                }.orEmpty()

                if (ids.isEmpty()) {
                    diagnostics.log("Locked vault and no username/password field found; nothing to offer.")
                    safeSuccess(null)
                    return
                }

                val intent = AutofillPick.createUnlockIntent(this)
                // Must be mutable: the framework adds EXTRA_ASSIST_STRUCTURE through fill-in extras.
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    nextAuthRequestCode(),
                    intent,
                    authPendingIntentFlags(android.os.Build.VERSION.SDK_INT)
                )
                val intentSender = pendingIntent.intentSender

                val presentation = android.widget.RemoteViews(packageName, com.example.R.layout.autofill_dropdown_item)
                presentation.setTextViewText(com.example.R.id.text1, "Tap to unlock VaultPass")

                val fillResponse = android.service.autofill.FillResponse.Builder()
                    .setAuthentication(ids.toTypedArray(), intentSender, presentation)
                    .build()

                diagnostics.log("Returned Authentication FillResponse for ${ids.size} field(s)")
                safeSuccess(fillResponse)
                return
            }

            val contexts = request.fillContexts
            if (contexts.isEmpty()) {
                safeSuccess(null)
                return
            }
            val structure = contexts.last().structure
            val fillJob = serviceScope.launch(Dispatchers.Default) {
                try {
                    val fillResponse = buildResponseForStructure(
                        this@VaultAutofillService,
                        structure,
                        app.container.vaultRepository,
                        diagnostics
                    )
                    safeSuccess(fillResponse)
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        diagnostics.logError("Coroutine matching error: ${e.message}")
                        if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                        safeSuccess(null)
                    }
                }
            }
            cancellationSignal.setOnCancelListener {
                diagnostics.log("Fill request cancellation received from OS.")
                fillJob.cancel()
            }
        } catch (e: Exception) {
            diagnostics.logError("onFillRequest catastrophic error: ${e.message}")
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            safeSuccess(null)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val app = application as com.example.VaultPassApplication
        app.container.autofillDiagnosticsRepository.log("onSaveRequest triggered (Ignoring for Phase 1)")
        callback.onSuccess()
    }

    companion object {
        private val authRequestCodes = java.util.concurrent.atomic.AtomicInteger(
            (System.currentTimeMillis() and 0x3FFFFFFF).toInt()
        )

        /** A request code not used by an earlier, possibly still displayed, authentication PendingIntent. */
        private fun nextAuthRequestCode(): Int = authRequestCodes.incrementAndGet() and 0x7FFFFFFF

        /**
         * FLAG_MUTABLE exists from API 31 (and is required there for fill-in extras); below 31 PendingIntents
         * are mutable unless FLAG_IMMUTABLE is set.
         */
        @android.annotation.SuppressLint("InlinedApi")
        internal fun authPendingIntentFlags(sdkInt: Int): Int =
            if (sdkInt >= android.os.Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT
            } else {
                android.app.PendingIntent.FLAG_CANCEL_CURRENT
            }

        /** Debug log line for one match: position, score and reason only, never the entry's title or username. */
        internal fun matchLogLine(index: Int, match: AutofillCredentialMatcher.ScoredEntry): String =
            "MATCH #${index + 1}: Score=${match.score}, Reason='${match.reason}'"

        /** Debug log line for a built dataset: its number and which fields it fills, never entry content. */
        internal fun datasetLogLine(number: Int, usernamePopulated: Boolean, passwordPopulated: Boolean): String =
            "DATASET BUILT #$number: UsernamePopulated=$usernamePopulated, PasswordPopulated=$passwordPopulated"

        suspend fun buildResponseForStructure(
            context: android.content.Context,
            structure: android.app.assist.AssistStructure,
            vaultRepository: com.example.repository.VaultRepository,
            diagnostics: com.example.repository.AutofillDiagnosticsRepository? = null
        ): android.service.autofill.FillResponse? {
            diagnostics?.log("Vault is unlocked. Analyzing AssistStructure...")
            
            val componentName = structure.activityComponent
            val requestedPackageName: String? = componentName?.packageName
            diagnostics?.log("Detected PackageName: $requestedPackageName")
            
            // Same field detection as the locked (authentication) path in onFillRequest.
            val fields = AutofillFieldDetector.detect(structure) { message -> diagnostics?.log(message) }
            val usernameId = fields.usernameId
            val passwordId = fields.passwordId
            val requestedWebDomain = fields.webDomain
            val requestedWebScheme = fields.webScheme

            diagnostics?.updatePackageAndDomain(requestedPackageName, requestedWebDomain)
            // A webDomain is only trusted from a browser whose signing certificate is in the bundled privileged list.
            val verifiedBrowser = BrowserVerifier.get(context).isVerifiedBrowser(requestedPackageName)
            if (requestedWebDomain != null && !verifiedBrowser) {
                diagnostics?.log("WebDomain ignored: $requestedPackageName is not a verified browser")
            }

            // Parsed once and cached; this function always runs on a background dispatcher.
            val publicSuffixList = try {
                PublicSuffixList.get(context)
            } catch (e: Exception) {
                diagnostics?.logError("Public Suffix List unavailable, exact host matches only: ${e.message}")
                null
            }

            val entries = vaultRepository.getAllEntriesSync()
            // Entries the user linked to this app via "Search VaultPass…" (never for browsers).
            val linkedSyncIds = if (requestedPackageName != null && !verifiedBrowser && AutofillCredentialMatcher.canLinkPackage(requestedPackageName)) {
                try {
                    vaultRepository.linkedAutofillSyncIds(requestedPackageName)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    diagnostics?.logError("Could not read autofill app links: ${e.message}")
                    emptySet()
                }
            } else {
                emptySet()
            }
            val scoredMatches = AutofillCredentialMatcher.matchEntries(
                entries = entries,
                requestedPackage = requestedPackageName,
                requestedWebDomain = requestedWebDomain,
                publicSuffixList = publicSuffixList,
                verifiedBrowser = verifiedBrowser,
                linkedSyncIds = linkedSyncIds,
                webScheme = requestedWebScheme
            )
            scoredMatches.forEachIndexed { index, match -> diagnostics?.log(matchLogLine(index, match)) }
            val matchedEntries = scoredMatches.map { it.entry }
            
            diagnostics?.log("Total matching entries found: ${matchedEntries.size}")
            diagnostics?.log("Target Username AutofillId: $usernameId")
            diagnostics?.log("Target Password AutofillId: $passwordId")
            
            diagnostics?.updateMatches(matchedEntries.size, if (usernameId != null || passwordId != null) matchedEntries.size else 0)
            
            if (usernameId == null && passwordId == null) {
                diagnostics?.log("Empty response (no valid fields found)")
                return null
            }

            // "Search VaultPass…": offered to native apps and to verified browsers on an identified page (only apps
            // get a saved link). The picker shows the page's domain and asks before filling another site's entry.
            val pickWebDomain = if (verifiedBrowser) AutofillCredentialMatcher.extractHost(requestedWebDomain) else null
            val searchDataset = if (!requestedPackageName.isNullOrBlank() && (!verifiedBrowser || pickWebDomain != null)) {
                buildSearchDataset(context, requestedPackageName, pickWebDomain, usernameId, passwordId)
            } else {
                null
            }

            if (matchedEntries.isEmpty() && searchDataset == null) {
                diagnostics?.log("Empty response (no matches and no requesting package)")
                return null
            }
            
            val responseBuilder = android.service.autofill.FillResponse.Builder()
            var datasetsAdded = 0
            
            for (entry in matchedEntries) {
                val dataset = buildEntryDataset(context, entry, usernameId, passwordId)
                if (dataset != null) {
                    responseBuilder.addDataset(dataset)
                    datasetsAdded++
                    val usernamePopulated = usernameId != null && entry.username.isNotBlank()
                    val passwordPopulated = passwordId != null && entry.password.isNotBlank()
                    diagnostics?.log(datasetLogLine(datasetsAdded, usernamePopulated, passwordPopulated))
                }
            }

            if (searchDataset != null) {
                responseBuilder.addDataset(searchDataset)
                diagnostics?.log("Added 'Search VaultPass' dataset for $requestedPackageName")
            }
            if (datasetsAdded == 0 && searchDataset == null) {
                diagnostics?.log("Empty response (matched entries have nothing to fill)")
                return null
            }
            
            diagnostics?.log("FillResponse generated? YES (Datasets built: $datasetsAdded)")
            return responseBuilder.build()
        }

        /**
         * A dataset filling [entry]'s username into [usernameId] and its password into [passwordId], shown as
         * "Title (username)". Null when it would fill nothing.
         */
        fun buildEntryDataset(
            context: android.content.Context,
            entry: com.example.domain.models.VaultEntry,
            usernameId: android.view.autofill.AutofillId?,
            passwordId: android.view.autofill.AutofillId?
        ): android.service.autofill.Dataset? {
            val values = AutofillPick.fillValues(entry, usernameId, passwordId)
            if (values.isEmpty()) return null
            val presentation = android.widget.RemoteViews(context.packageName, com.example.R.layout.autofill_dropdown_item)
            val displayTitle = if (entry.username.isNotBlank()) "${entry.title} (${entry.username})" else entry.title
            presentation.setTextViewText(com.example.R.id.text1, displayTitle)
            val datasetBuilder = android.service.autofill.Dataset.Builder()
            for ((id, value) in values) {
                datasetBuilder.setValue(id, android.view.autofill.AutofillValue.forText(value), presentation)
            }
            return datasetBuilder.build()
        }

        /**
         * The authentication-required "Search VaultPass…" dataset on the detected login fields. Its PendingIntent
         * opens AutofillAuthActivity in pick mode (same mutability rules and unique request codes as the unlock chip).
         * [webDomain]: the page's host for a verified browser, null for native apps.
         */
        private fun buildSearchDataset(
            context: android.content.Context,
            requestedPackageName: String,
            webDomain: String?,
            usernameId: android.view.autofill.AutofillId?,
            passwordId: android.view.autofill.AutofillId?
        ): android.service.autofill.Dataset? {
            val ids = listOfNotNull(usernameId, passwordId).distinct()
            if (ids.isEmpty()) return null
            val intent = AutofillPick.createIntent(context, requestedPackageName, webDomain, usernameId, passwordId)
            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                nextAuthRequestCode(),
                intent,
                authPendingIntentFlags(android.os.Build.VERSION.SDK_INT)
            )
            val presentation = android.widget.RemoteViews(context.packageName, com.example.R.layout.autofill_dropdown_item)
            presentation.setTextViewText(com.example.R.id.text1, context.getString(com.example.R.string.autofill_search_vault))
            val datasetBuilder = android.service.autofill.Dataset.Builder(presentation)
            // Values are supplied by the activity's result; null placeholders are allowed with authentication.
            for (id in ids) datasetBuilder.setValue(id, null)
            datasetBuilder.setAuthentication(pendingIntent.intentSender)
            return datasetBuilder.build()
        }
    }
}
