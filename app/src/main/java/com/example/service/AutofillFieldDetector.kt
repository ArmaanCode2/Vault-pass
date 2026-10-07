package com.example.service

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

/** The parts of a view-structure node the field detection needs (abstracted so it can be unit-tested). */
interface AutofillFieldNode<ID : Any> {
    val autofillId: ID?
    val autofillHints: Array<String>?
    val className: String?
    val inputType: Int
    val isFocused: Boolean
    val isFocusable: Boolean
    val isClickable: Boolean
    val isEnabled: Boolean
    val visibility: Int
    val idEntry: String?
    val hint: String?
    val webDomain: String?
    /** Scheme of the page or frame that reports [webDomain] ("http", "https"); API 28+, null when unknown. */
    val webScheme: String? get() = null
    val childCount: Int
    fun getChildAt(index: Int): AutofillFieldNode<ID>
}

/**
 * Result of the login-field detection. [webDomain] and [webScheme] belong to the web frame of the detected login
 * fields (e.g. an iframe), or are null when no login field was found or the fields are not inside web content.
 * Both IDs always come from that one frame: a field in a frame with another domain is never returned.
 */
data class DetectedLoginFields<ID : Any>(
    val usernameId: ID?,
    val passwordId: ID?,
    val webDomain: String?,
    val webScheme: String? = null
) {
    /** Distinct username/password IDs; used both for datasets and for attaching the locked-vault authentication. */
    val fieldIds: List<ID> get() = listOfNotNull(usernameId, passwordId).distinct()
}

/** Login-field detection shared by the unlocked fill path and the locked (authentication) path. */
object AutofillFieldDetector {

    fun detect(structure: AssistStructure, log: ((String) -> Unit)? = null): DetectedLoginFields<AutofillId> {
        val roots = (0 until structure.windowNodeCount).map {
            ViewNodeFieldNode(structure.getWindowNodeAt(it).rootViewNode)
        }
        return detect(roots, log)
    }

    /** The web frame of a node: its nearest ancestor-or-self with a webDomain. Null outside web content. */
    private data class Frame(val domain: String, val scheme: String?)

    private class Candidate<ID : Any>(
        val node: AutofillFieldNode<ID>,
        val frame: Frame?,
        val isUsername: Boolean,
        val isPassword: Boolean
    )

    fun <ID : Any> detect(
        roots: List<AutofillFieldNode<ID>>,
        log: ((String) -> Unit)? = null
    ): DetectedLoginFields<ID> {
        // Every visible username/password field with an autofill ID, in tree (BFS) order, with its frame.
        val candidates = mutableListOf<Candidate<ID>>()

        for (root in roots) {
            val nodes = ArrayDeque<Pair<AutofillFieldNode<ID>, Frame?>>()
            nodes.addLast(root to null)
            while (nodes.isNotEmpty()) {
                val (node, inheritedFrame) = nodes.removeFirst()

                // Hidden views and everything inside them are never filled: a page can hide a login field so
                // the user doesn't see credentials going into it.
                if (node.visibility != View.VISIBLE) {
                    log?.invoke("Skipped hidden view (and its children): ${node.idEntry}")
                    continue
                }

                val ownDomain = node.webDomain?.trim()?.takeIf { it.isNotEmpty() }
                val frame = if (ownDomain != null) {
                    Frame(ownDomain.lowercase(), node.webScheme?.trim()?.lowercase()?.takeIf { it.isNotEmpty() })
                } else {
                    inheritedFrame
                }

                val hints = node.autofillHints
                val classNameStr = node.className?.lowercase() ?: ""

                val isLayoutContainer = classNameStr.contains("layout") && !classNameStr.contains("edittext")
                val isEditableClass = classNameStr.contains("edittext")

                val hasPasswordHint = hints?.contains(View.AUTOFILL_HINT_PASSWORD) == true ||
                                      hints?.contains("current-password") == true ||
                                      hints?.contains("new-password") == true

                val isValidTarget = !isLayoutContainer && (isEditableClass || node.inputType != 0 || node.isFocused || hasPasswordHint)

                if (isValidTarget) {
                    val viewId = node.idEntry?.lowercase() ?: ""
                    val hintText = node.hint?.lowercase() ?: ""

                    val variation = node.inputType and InputType.TYPE_MASK_VARIATION
                    val isPasswordType = (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD) ||
                                         (variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
                                         (variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
                                         (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)

                    val isUsername = hints?.contains(View.AUTOFILL_HINT_USERNAME) == true ||
                        hints?.contains(View.AUTOFILL_HINT_EMAIL_ADDRESS) == true ||
                        viewId.contains("username") || viewId.contains("email") ||
                        hintText.contains("username") || hintText.contains("email")
                    val isPassword = hints?.contains(View.AUTOFILL_HINT_PASSWORD) == true ||
                        viewId.contains("password") || hintText.contains("password") || isPasswordType

                    if ((isUsername || isPassword) && node.autofillId != null) {
                        candidates += Candidate(node, frame, isUsername, isPassword)
                        log?.invoke("Login Field Candidate (username=$isUsername, password=$isPassword):\\nClass: ${node.className}\\nAutofillId: ${node.autofillId}\\nInputType: ${node.inputType}\\nFocusable: ${node.isFocusable}\\nFrame: ${frame?.domain}")
                        if (isPassword && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                            log?.invoke("Numeric Password Field correctly detected: ${node.idEntry}")
                        }
                    } else if (isUsername || isPassword) {
                        log?.invoke("Login field without AutofillId skipped: ${node.idEntry}")
                    }
                } else if (log != null) {
                    if (!isLayoutContainer && (node.hint != null || node.idEntry != null || hints != null)) {
                        val rejectedReason = if (!isEditableClass && node.inputType == 0 && !node.isFocused && !hasPasswordHint) "NOT_EDITABLE_CLASS_NO_INPUTTYPE_NOT_FOCUSED" else "UNKNOWN_REJECTION"
                        log("REJECTED BY GATEKEEPER ->\\n" +
                            "ClassName: ${node.className}\\n" +
                            "InputType: ${node.inputType}\\n" +
                            "Focusable: ${node.isFocusable}\\n" +
                            "Clickable: ${node.isClickable}\\n" +
                            "Enabled: ${node.isEnabled}\\n" +
                            "AutofillHints: ${hints?.joinToString()}\\n" +
                            "AutofillId: ${node.autofillId}\\n" +
                            "RejectedReason=$rejectedReason")
                    }
                }

                for (j in 0 until node.childCount) {
                    nodes.addLast(node.getChildAt(j) to frame)
                }
            }
        }

        // Choose the frame first: the focused login field's, else the first password field's, else the first
        // username field's. Both fields are then taken from that frame only (same webDomain), so a credential for
        // one site is never put into a field of another frame. Native fields have no frame and are never mixed
        // with web fields either.
        val anchor = candidates.firstOrNull { it.node.isFocused }
            ?: candidates.firstOrNull { it.isPassword }
            ?: candidates.firstOrNull()
            ?: return DetectedLoginFields(null, null, null, null)
        val sameFrame = candidates.filter { it.frame == anchor.frame }
        val usernameCandidate = if (anchor.isUsername) anchor else sameFrame.firstOrNull { it.isUsername }
        val passwordCandidate = if (anchor.isPassword) {
            anchor
        } else {
            sameFrame.firstOrNull { it.isPassword && it !== usernameCandidate } ?: sameFrame.firstOrNull { it.isPassword }
        }
        val ignored = candidates.size - sameFrame.size
        if (ignored > 0) log?.invoke("Ignored $ignored login field(s) outside the login field's frame")
        log?.invoke("Identified Username Field: ${usernameCandidate?.node?.idEntry}; Password Field: ${passwordCandidate?.node?.idEntry}")

        val frame = anchor.frame
        if (frame != null) log?.invoke("Detected WebDomain (login field's frame): ${frame.domain}, scheme: ${frame.scheme}")

        return DetectedLoginFields(usernameCandidate?.node?.autofillId, passwordCandidate?.node?.autofillId, frame?.domain, frame?.scheme)
    }

    private class ViewNodeFieldNode(private val node: AssistStructure.ViewNode) : AutofillFieldNode<AutofillId> {
        override val autofillId: AutofillId? get() = node.autofillId
        override val autofillHints: Array<String>? get() = node.autofillHints
        override val className: String? get() = node.className
        override val inputType: Int get() = node.inputType
        override val isFocused: Boolean get() = node.isFocused
        override val isFocusable: Boolean get() = node.isFocusable
        override val isClickable: Boolean get() = node.isClickable
        override val isEnabled: Boolean get() = node.isEnabled
        override val visibility: Int get() = node.visibility
        override val idEntry: String? get() = node.idEntry
        override val hint: String? get() = node.hint
        override val webDomain: String? get() = node.webDomain
        override val webScheme: String?
            get() = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) node.webScheme else null
        override val childCount: Int get() = node.childCount
        override fun getChildAt(index: Int): AutofillFieldNode<AutofillId> = ViewNodeFieldNode(node.getChildAt(index))
    }
}
