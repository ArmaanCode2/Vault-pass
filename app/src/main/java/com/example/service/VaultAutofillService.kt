package com.example.service

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import kotlinx.coroutines.launch

class VaultAutofillService : AutofillService() {

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
        
        try {
            val dek = app.container.cryptoManager.getSoftwareDek()

            if (dek == null) {
                diagnostics.log("Vault is locked. Launching Authentication Bridge.")
                val intent = android.content.Intent(this, com.example.ui.AutofillAuthActivity::class.java)
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT
                )
                val intentSender = pendingIntent.intentSender

                val presentation = android.widget.RemoteViews(packageName, com.example.R.layout.autofill_dropdown_item)
                presentation.setTextViewText(com.example.R.id.text1, "Tap to unlock VaultPass")

                val ids = mutableListOf<android.view.autofill.AutofillId>()
                val contexts = request.fillContexts
                if (contexts.isNotEmpty()) {
                    val structure = contexts.last().structure
                    val numWindows = structure.windowNodeCount
                    for (i in 0 until numWindows) {
                        val windowNode = structure.getWindowNodeAt(i)
                        val rootNode = windowNode.rootViewNode
                        val nodes = mutableListOf(rootNode)
                        while (nodes.isNotEmpty() && ids.isEmpty()) {
                            val node = nodes.removeAt(0)
                            if (node.autofillId != null) {
                                ids.add(node.autofillId!!)
                            }
                            for (j in 0 until node.childCount) {
                                nodes.add(node.getChildAt(j))
                            }
                        }
                    }
                }

                if (ids.isEmpty()) {
                    diagnostics.logError("Locked vault, but could not find a valid AutofillId to attach authentication to.")
                    callback.onSuccess(null)
                    return
                }

                val fillResponse = android.service.autofill.FillResponse.Builder()
                    .setAuthentication(ids.toTypedArray(), intentSender, presentation)
                    .build()

                diagnostics.log("Returned Authentication FillResponse")
                callback.onSuccess(fillResponse)
                return
            }

            diagnostics.log("Vault is unlocked. Analyzing AssistStructure...")
            
            var requestedWebDomain: String? = null
            var requestedPackageName: String? = null
            
            val contexts = request.fillContexts
            var usernameId: android.view.autofill.AutofillId? = null
            var passwordId: android.view.autofill.AutofillId? = null
            
            if (contexts.isNotEmpty()) {
                val structure = contexts.last().structure
                
                // Get the package name of the app being autofilled
                val componentName = structure.activityComponent
                requestedPackageName = componentName.packageName
                diagnostics.log("Detected PackageName: $requestedPackageName")
                
                val numWindows = structure.windowNodeCount
                for (i in 0 until numWindows) {
                    val windowNode = structure.getWindowNodeAt(i)
                    val rootNode = windowNode.rootViewNode
                    val nodes = mutableListOf(rootNode)
                    while (nodes.isNotEmpty()) {
                        val node = nodes.removeAt(0)
                        
                        if (node.webDomain != null && requestedWebDomain == null) {
                            requestedWebDomain = node.webDomain
                            diagnostics.log("Detected WebDomain: $requestedWebDomain")
                        }
                        
                        val hints = node.autofillHints
                        val classNameStr = node.className?.toString()?.lowercase() ?: ""
                        
                        // Only consider nodes that represent actual editable text inputs.
                        // Reject layout containers explicitly.
                        val isLayoutContainer = classNameStr.contains("layout") && !classNameStr.contains("edittext")
                        val isEditableClass = classNameStr.contains("edittext")
                        
                        // Minimal safe relaxation: Bypass if node explicitly declares password hints
                        val hasPasswordHint = hints?.contains(android.view.View.AUTOFILL_HINT_PASSWORD) == true ||
                                              hints?.contains("current-password") == true ||
                                              hints?.contains("new-password") == true
                                              
                        val isValidTarget = !isLayoutContainer && (isEditableClass || node.inputType != 0 || node.isFocused || hasPasswordHint)
                        
                        if (isValidTarget) {
                            if (hints != null) {
                                if (hints.contains(android.view.View.AUTOFILL_HINT_USERNAME) || hints.contains(android.view.View.AUTOFILL_HINT_EMAIL_ADDRESS)) {
                                    if (usernameId == null) {
                                        usernameId = node.autofillId
                                        diagnostics.log("Identified Username Field via Hint: ${node.idEntry}")
                                    } else {
                                        diagnostics.log("Prevented overwrite of Username AutofillId by hint on: ${node.idEntry}")
                                    }
                                }
                                if (hints.contains(android.view.View.AUTOFILL_HINT_PASSWORD)) {
                                    if (passwordId == null) {
                                        passwordId = node.autofillId
                                        diagnostics.log("Identified Password Field via Hint: ${node.idEntry}")
                                    } else {
                                        diagnostics.log("Prevented overwrite of Password AutofillId by hint on: ${node.idEntry}")
                                    }
                                }
                            }
                            
                            val viewId = node.idEntry?.lowercase() ?: ""
                            val hintText = node.hint?.toString()?.lowercase() ?: ""
                            
                            val isUsernameHeuristic = viewId.contains("username") || viewId.contains("email") || hintText.contains("username") || hintText.contains("email")
                            if (isUsernameHeuristic) {
                                if (usernameId == null) {
                                    usernameId = node.autofillId
                                    diagnostics.log("Username Target:\\nClass: ${node.className}\\nAutofillId: ${node.autofillId}\\nInputType: ${node.inputType}\\nFocusable: ${node.isFocusable}")
                                } else if (node.autofillId != usernameId) {
                                    diagnostics.log("Prevented overwrite of Username AutofillId by heuristic on: ${node.idEntry}")
                                }
                            }
                            
                            val variation = node.inputType and android.text.InputType.TYPE_MASK_VARIATION
                            val isPasswordType = (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) || 
                                                 (variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) || 
                                                 (variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) || 
                                                 (variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                                                 
                            val isPasswordHeuristic = viewId.contains("password") || hintText.contains("password") || isPasswordType
                            if (isPasswordHeuristic) {
                                if (passwordId == null) {
                                    passwordId = node.autofillId
                                    diagnostics.log("Password Target:\\nClass: ${node.className}\\nAutofillId: ${node.autofillId}\\nInputType: ${node.inputType}\\nFocusable: ${node.isFocusable}")
                                    if (variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                                        diagnostics.log("Numeric Password Field correctly detected: ${node.idEntry}")
                                    }
                                } else if (node.autofillId != passwordId) {
                                    diagnostics.log("Prevented overwrite of Password AutofillId by heuristic on: ${node.idEntry}")
                                }
                            }
                        } else {
                            // Phase 3: Gather evidence for rejected targets (excluding layout containers to avoid noise)
                            if (!isLayoutContainer && (node.hint != null || node.idEntry != null || hints != null)) {
                                val rejectedReason = if (!isEditableClass && node.inputType == 0 && !node.isFocused && !hasPasswordHint) "NOT_EDITABLE_CLASS_NO_INPUTTYPE_NOT_FOCUSED" else "UNKNOWN_REJECTION"
                                diagnostics.log("REJECTED BY GATEKEEPER ->\\n" +
                                    "ClassName: ${node.className}\\n" +
                                    "InputType: ${node.inputType}\\n" +
                                    "Focusable: ${node.isFocusable}\\n" +
                                    "Clickable: ${node.isClickable}\\n" +
                                    "Enabled: ${node.isEnabled}\\n" +
                                    "Visibility: ${if (node.visibility == android.view.View.VISIBLE) "VISIBLE" else node.visibility}\\n" +
                                    "AutofillHints: ${hints?.joinToString()}\\n" +
                                    "AutofillId: ${node.autofillId}\\n" +
                                    "RejectedReason=$rejectedReason")
                            }
                        }
                        
                        for (j in 0 until node.childCount) {
                            nodes.add(node.getChildAt(j))
                        }
                    }
                }
            }

            diagnostics.updatePackageAndDomain(requestedPackageName, requestedWebDomain)

            fun extractBaseDomain(url: String?): String? {
                if (url.isNullOrBlank()) return null
                try {
                    val parsedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
                    val uri = android.net.Uri.parse(parsedUrl)
                    var host = uri.host ?: return null
                    host = host.lowercase()
                    if (host.startsWith("www.")) host = host.substring(4)
                    return host
                } catch (e: Exception) {
                    return null
                }
            }

            val requestedBaseDomain = extractBaseDomain(requestedWebDomain)
            
            var requestedAppLabel: String? = null
            if (requestedPackageName != null) {
                try {
                    val pm = packageManager
                    val applicationInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        pm.getApplicationInfo(requestedPackageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        pm.getApplicationInfo(requestedPackageName, 0)
                    }
                    requestedAppLabel = pm.getApplicationLabel(applicationInfo).toString()
                    diagnostics.log("Detected App Label: $requestedAppLabel")
                } catch (e: Exception) {
                    diagnostics.logError("Failed to get App Label for $requestedPackageName: ${e.message}")
                }
            }

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    diagnostics.log("Fetching decrypted vault entries...")
                    val entries = app.container.vaultRepository.getAllEntriesSync()
                    val matchedEntries = mutableListOf<com.example.domain.models.VaultEntry>()
                    
                    if (requestedBaseDomain != null) {
                        for (entry in entries) {
                            if (entry.isDecryptionFailed) continue
                            val entryBaseDomain = extractBaseDomain(entry.website) ?: continue
                            if (requestedBaseDomain == entryBaseDomain || requestedBaseDomain.endsWith(".$entryBaseDomain")) {
                                matchedEntries.add(entry)
                            }
                        }
                    }
                    
                    if (matchedEntries.isEmpty() && requestedPackageName != null) {
                        // Native app matching fallback
                        diagnostics.log("No web domain found. Attempting native app matching for: $requestedPackageName (Label: $requestedAppLabel)")
                        
                        fun normalize(str: String): String = str.lowercase().replace(Regex("[^a-z0-9]"), "")
                        val normalizedRequestedLabel = requestedAppLabel?.let { normalize(it) } ?: ""
                        
                        data class ScoredEntry(val entry: com.example.domain.models.VaultEntry, val score: Int, val reason: String)
                        val scoredMatches = mutableListOf<ScoredEntry>()
                        
                        for (entry in entries) {
                            if (entry.isDecryptionFailed) continue
                            
                            var score = 0
                            var matchReason = ""
                            val normalizedTitle = normalize(entry.title)
                            
                            if (normalizedTitle.isNotEmpty() && normalizedRequestedLabel.isNotEmpty()) {
                                if (normalizedTitle == normalizedRequestedLabel) {
                                    score = 100
                                    matchReason = "Exact title match (Score 100)"
                                } else if (normalizedTitle.contains(normalizedRequestedLabel)) {
                                    score = 85
                                    matchReason = "Title contains app label (Score 85)"
                                } else if (normalizedRequestedLabel.contains(normalizedTitle)) {
                                    score = 80
                                    matchReason = "App label contains title (Score 80)"
                                }
                            }
                            
                            if (score == 0) {
                                val isPackageMatch = entry.website.contains(requestedPackageName, ignoreCase = true) || 
                                    requestedPackageName.contains(entry.title.replace(" ", ""), ignoreCase = true)
                                if (isPackageMatch) {
                                    score = 60
                                    matchReason = "Package name fallback (Score 60)"
                                }
                            }
                            
                            if (score > 50) {
                                scoredMatches.add(ScoredEntry(entry, score, matchReason))
                            }
                        }
                        
                        scoredMatches.sortByDescending { it.score }
                        for (match in scoredMatches) {
                            diagnostics.log("MATCH: Title='${match.entry.title}', Score=${match.score}, Reason='${match.reason}'")
                        }
                        matchedEntries.addAll(scoredMatches.map { it.entry })
                    }
                    
                    diagnostics.log("Total matching entries found: ${matchedEntries.size}")
                    diagnostics.log("Target Username AutofillId: $usernameId")
                    diagnostics.log("Target Password AutofillId: $passwordId")
                    
                    diagnostics.updateMatches(matchedEntries.size, if (usernameId != null || passwordId != null) matchedEntries.size else 0)
                    
                    if (matchedEntries.isEmpty() || (usernameId == null && passwordId == null)) {
                        diagnostics.log("Empty response (no matches or no valid fields found)")
                        callback.onSuccess(null)
                        return@launch
                    }
                    
                    val responseBuilder = android.service.autofill.FillResponse.Builder()
                    var datasetsAdded = 0
                    
                    for (entry in matchedEntries) {
                        val datasetBuilder = android.service.autofill.Dataset.Builder()
                        val presentation = android.widget.RemoteViews(packageName, com.example.R.layout.autofill_dropdown_item)
                        val displayTitle = if (entry.username.isNotBlank()) "${entry.title} (${entry.username})" else entry.title
                        presentation.setTextViewText(com.example.R.id.text1, displayTitle)
                        
                        var hasData = false
                        var usernamePopulated = false
                        var passwordPopulated = false
                        
                        if (usernameId != null && entry.username.isNotBlank()) {
                            datasetBuilder.setValue(usernameId, android.view.autofill.AutofillValue.forText(entry.username), presentation)
                            hasData = true
                            usernamePopulated = true
                        }
                        if (passwordId != null && entry.password.isNotBlank()) {
                            datasetBuilder.setValue(passwordId, android.view.autofill.AutofillValue.forText(entry.password), presentation)
                            hasData = true
                            passwordPopulated = true
                        }
                        
                        if (hasData) {
                            responseBuilder.addDataset(datasetBuilder.build())
                            datasetsAdded++
                            diagnostics.log("DATASET BUILT: Title='$displayTitle', UsernamePopulated=$usernamePopulated, PasswordPopulated=$passwordPopulated")
                        }
                    }
                    
                    diagnostics.log("FillResponse generated? YES (Datasets built: $datasetsAdded)")
                    diagnostics.log("Calling callback.onSuccess()...")
                    callback.onSuccess(responseBuilder.build())
                    diagnostics.log("callback.onSuccess() called successfully!")
                } catch (e: Exception) {
                    diagnostics.logError("Coroutine matching error: ${e.message}")
                    e.printStackTrace()
                    callback.onSuccess(null)
                }
            }
        } catch (e: Exception) {
            diagnostics.logError("onFillRequest catastrophic error: ${e.message}")
            e.printStackTrace()
            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val app = application as com.example.VaultPassApplication
        app.container.autofillDiagnosticsRepository.log("onSaveRequest triggered (Ignoring for Phase 1)")
        callback.onSuccess()
    }
}
