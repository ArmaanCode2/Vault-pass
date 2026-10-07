package com.example

import android.app.PendingIntent
import android.text.InputType
import android.view.View
import com.example.service.AutofillFieldDetector
import com.example.service.AutofillFieldNode
import com.example.service.VaultAutofillService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F6: the locked-vault authentication must be attached to the detected login fields, never to the root view. */
class AutofillFieldDetectorTest {

    private class FakeNode(
        override val autofillId: String?,
        override val className: String? = null,
        override val autofillHints: Array<String>? = null,
        override val inputType: Int = 0,
        override val isFocused: Boolean = false,
        override val idEntry: String? = null,
        override val hint: String? = null,
        override val webDomain: String? = null,
        override val webScheme: String? = null,
        override val visibility: Int = View.VISIBLE,
        val children: List<FakeNode> = emptyList()
    ) : AutofillFieldNode<String> {
        override val isFocusable: Boolean get() = inputType != 0
        override val isClickable: Boolean = false
        override val isEnabled: Boolean = true
        override val childCount: Int get() = children.size
        override fun getChildAt(index: Int): AutofillFieldNode<String> = children[index]
    }

    private val textInput = InputType.TYPE_CLASS_TEXT
    private val passwordInput = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

    private fun root(vararg children: FakeNode, focused: Boolean = false, webDomain: String? = null) = FakeNode(
        autofillId = "root",
        className = "com.android.internal.policy.DecorView",
        isFocused = focused,
        webDomain = webDomain,
        children = listOf(FakeNode("container", className = "android.widget.LinearLayout", children = children.toList()))
    )

    private fun usernameField(id: String = "user", focused: Boolean = false, visibility: Int = View.VISIBLE) =
        FakeNode(id, className = "android.widget.EditText", inputType = textInput, idEntry = "username_input",
            isFocused = focused, visibility = visibility)

    private fun passwordField(id: String = "pass", focused: Boolean = false, visibility: Int = View.VISIBLE) =
        FakeNode(id, className = "android.widget.EditText", inputType = passwordInput, idEntry = "pwd",
            isFocused = focused, visibility = visibility)

    private fun frame(id: String, domain: String, vararg children: FakeNode, scheme: String? = null, visibility: Int = View.VISIBLE) =
        FakeNode(id, className = "android.webkit.WebView", webDomain = domain, webScheme = scheme,
            visibility = visibility, children = children.toList())

    @Test
    fun loginForm_selectsUsernameAndPasswordFields_notRoot() {
        val fields = AutofillFieldDetector.detect(listOf(root(usernameField(), passwordField(), focused = true)))

        assertEquals("user", fields.usernameId)
        assertEquals("pass", fields.passwordId)
        assertEquals(listOf("user", "pass"), fields.fieldIds)
        assertFalse(fields.fieldIds.contains("root"))
        assertFalse(fields.fieldIds.contains("container"))
    }

    @Test
    fun usernameAndPasswordViaAutofillHints() {
        val email = FakeNode("email", className = "android.widget.EditText", inputType = textInput,
            autofillHints = arrayOf(View.AUTOFILL_HINT_EMAIL_ADDRESS))
        val secret = FakeNode("secret", className = "android.widget.EditText", inputType = textInput,
            autofillHints = arrayOf(View.AUTOFILL_HINT_PASSWORD))

        val fields = AutofillFieldDetector.detect(listOf(root(email, secret)))

        assertEquals(listOf("email", "secret"), fields.fieldIds)
    }

    @Test
    fun passwordOnlyForm_selectsOnlyPassword() {
        val fields = AutofillFieldDetector.detect(listOf(root(passwordField())))

        assertNull(fields.usernameId)
        assertEquals("pass", fields.passwordId)
        assertEquals(listOf("pass"), fields.fieldIds)
    }

    @Test
    fun usernameOnlyForm_selectsOnlyUsername() {
        val fields = AutofillFieldDetector.detect(listOf(root(usernameField())))

        assertEquals("user", fields.usernameId)
        assertNull(fields.passwordId)
        assertEquals(listOf("user"), fields.fieldIds)
    }

    @Test
    fun noLoginFields_selectsNothing_evenThoughRootHasAnAutofillId() {
        val label = FakeNode("label", className = "android.widget.TextView", hint = "Welcome")
        val search = FakeNode("search", className = "android.widget.EditText", inputType = textInput, idEntry = "search_box")

        val fields = AutofillFieldDetector.detect(listOf(root(label, search, focused = true)))

        assertNull(fields.usernameId)
        assertNull(fields.passwordId)
        assertTrue(fields.fieldIds.isEmpty())
    }

    @Test
    fun nodeWithoutAutofillId_isSkipped_nextCandidateUsed() {
        val noId = FakeNode(null, className = "android.widget.EditText", inputType = passwordInput, idEntry = "password_old")
        val fields = AutofillFieldDetector.detect(listOf(root(noId, passwordField("pass2"))))

        assertEquals("pass2", fields.passwordId)
    }

    @Test
    fun fieldsInSecondWindow_areFound() {
        val firstWindow = FakeNode("root1", className = "com.android.internal.policy.DecorView")
        val fields = AutofillFieldDetector.detect(listOf(firstWindow, root(usernameField(), passwordField())))

        assertEquals(listOf("user", "pass"), fields.fieldIds)
    }

    @Test
    fun webDomain_isReportedFromStructure() {
        val fields = AutofillFieldDetector.detect(listOf(root(usernameField(), passwordField(), webDomain = "example.com")))

        assertEquals("example.com", fields.webDomain)
    }

    @Test
    fun webDomain_iframeAroundLoginField_winsOverPageDomain() {
        val iframe = FakeNode("iframe", className = "android.webkit.WebView", webDomain = "login.idp-example.net",
            children = listOf(usernameField(), passwordField()))
        val fields = AutofillFieldDetector.detect(listOf(root(iframe, webDomain = "shop.example.com")))

        assertEquals(listOf("user", "pass"), fields.fieldIds)
        assertEquals("login.idp-example.net", fields.webDomain)
    }

    @Test
    fun webDomain_earlierAdFrame_doesNotReplaceTheLoginFieldsPageDomain() {
        // BFS meets the ad iframe's domain before the page's own login fields.
        val adFrame = FakeNode("ad", className = "android.webkit.WebView", webDomain = "ads.tracker-example.com")
        val page = FakeNode("page", className = "android.webkit.WebView", webDomain = "bank.example.com",
            children = listOf(FakeNode("form", className = "android.view.View",
                children = listOf(usernameField(), passwordField()))))
        val fields = AutofillFieldDetector.detect(listOf(root(adFrame, page)))

        assertEquals("bank.example.com", fields.webDomain)
    }

    // --- H1: fields are only taken from the chosen frame, and never hidden fields ---

    @Test
    fun crossFrame_passwordInAnotherDomainsIframe_isNeverReturned() {
        // bank.com page with a focused username field; evil.org iframe holds a password field.
        val page = frame("page", "bank.com", usernameField("bankUser", focused = true),
            frame("evil", "evil.org", passwordField("evilPass")))
        val fields = AutofillFieldDetector.detect(listOf(root(page)))

        assertEquals("bank.com", fields.webDomain)
        assertEquals("bankUser", fields.usernameId)
        assertNull("The other frame's password field must not be filled", fields.passwordId)
        assertEquals(listOf("bankUser"), fields.fieldIds)
    }

    @Test
    fun crossFrame_focusedFieldInEvilIframe_getsOnlyItsOwnFrameAndDomain() {
        val page = frame("page", "bank.com", usernameField("bankUser"), passwordField("bankPass"),
            frame("evil", "evil.org", usernameField("evilUser", focused = true)))
        val fields = AutofillFieldDetector.detect(listOf(root(page)))

        assertEquals("evil.org", fields.webDomain)
        assertEquals(listOf("evilUser"), fields.fieldIds)
    }

    @Test
    fun crossFrame_noFocus_passwordFramesFieldsOnly() {
        val userFrame = frame("f1", "accounts.example.com", usernameField("user1"))
        val passFrame = frame("f2", "other.example.org", passwordField("pass2"))
        val fields = AutofillFieldDetector.detect(listOf(root(userFrame, passFrame)))

        assertEquals("other.example.org", fields.webDomain)
        assertNull(fields.usernameId)
        assertEquals("pass2", fields.passwordId)
    }

    @Test
    fun crossFrame_nativeFieldsAreNotMixedWithWebFields() {
        val fields = AutofillFieldDetector.detect(listOf(root(usernameField("nativeUser", focused = true),
            frame("web", "evil.org", passwordField("webPass")))))

        assertNull(fields.webDomain)
        assertEquals(listOf("nativeUser"), fields.fieldIds)
    }

    @Test
    fun sameFrame_usernameAndPassword_stillBothReturned() {
        val page = frame("page", "bank.com", usernameField("u", focused = true), passwordField("p"),
            frame("ad", "ads.example.net"))
        val fields = AutofillFieldDetector.detect(listOf(root(page)))

        assertEquals("bank.com", fields.webDomain)
        assertEquals(listOf("u", "p"), fields.fieldIds)
    }

    @Test
    fun hiddenPasswordField_isNeverReturned() {
        for (hidden in listOf(View.INVISIBLE, View.GONE)) {
            val page = frame("page", "bank.com", usernameField("u", focused = true), passwordField("hiddenPass", visibility = hidden))
            val fields = AutofillFieldDetector.detect(listOf(root(page)))

            assertEquals(listOf("u"), fields.fieldIds)
        }
    }

    @Test
    fun fieldsInsideAHiddenContainerOrFrame_areNeverReturned() {
        val hiddenBox = FakeNode("box", className = "android.view.View", visibility = View.INVISIBLE,
            children = listOf(passwordField("insideHiddenBox")))
        val hiddenFrame = frame("hiddenFrame", "bank.com", usernameField("insideHiddenFrame"), visibility = View.GONE)
        val page = frame("page", "bank.com", hiddenBox, hiddenFrame, passwordField("visiblePass"))
        val fields = AutofillFieldDetector.detect(listOf(root(page)))

        assertEquals(listOf("visiblePass"), fields.fieldIds)
    }

    @Test
    fun hiddenFieldsOnly_selectNothing() {
        val fields = AutofillFieldDetector.detect(listOf(root(
            usernameField(visibility = View.GONE), passwordField(visibility = View.INVISIBLE), focused = true)))

        assertTrue(fields.fieldIds.isEmpty())
        assertNull(fields.webDomain)
    }

    @Test
    fun webScheme_isTheLoginFieldsFrameScheme() {
        val page = frame("page", "example.com",
            frame("login", "login.example.com", usernameField(), passwordField(), scheme = "HTTP"), scheme = "https")
        val fields = AutofillFieldDetector.detect(listOf(root(page)))

        assertEquals("login.example.com", fields.webDomain)
        assertEquals("http", fields.webScheme)
        assertNull("Unknown scheme stays null",
            AutofillFieldDetector.detect(listOf(root(usernameField(), webDomain = "example.com"))).webScheme)
    }

    @Test
    fun webDomain_isNull_whenLoginFieldIsOutsideWebContent() {
        val webView = FakeNode("web", className = "android.webkit.WebView", webDomain = "example.com")
        val fields = AutofillFieldDetector.detect(listOf(root(webView, usernameField(), passwordField())))

        assertEquals(listOf("user", "pass"), fields.fieldIds)
        assertNull(fields.webDomain)
    }

    @Test
    fun webDomain_isNull_whenNoLoginFieldFound() {
        val fields = AutofillFieldDetector.detect(listOf(root(webDomain = "example.com")))

        assertNull(fields.webDomain)
    }

    @Test
    fun authPendingIntent_isMutableAndCancelCurrentOnApi31Plus() {
        for (sdk in listOf(31, 34, 36)) {
            val flags = VaultAutofillService.authPendingIntentFlags(sdk)
            assertTrue(flags and PendingIntent.FLAG_MUTABLE != 0)
            assertTrue(flags and PendingIntent.FLAG_CANCEL_CURRENT != 0)
            assertEquals(0, flags and PendingIntent.FLAG_IMMUTABLE)
        }
    }

    @Test
    fun authPendingIntent_isNotImmutableBelowApi31() {
        for (sdk in listOf(24, 28, 30)) {
            val flags = VaultAutofillService.authPendingIntentFlags(sdk)
            assertEquals(PendingIntent.FLAG_CANCEL_CURRENT, flags)
            assertEquals(0, flags and PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
