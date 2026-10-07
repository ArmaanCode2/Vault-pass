package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.domain.models.VaultListPreview
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F5: the list line under an entry's title never shows a password or custom-field value. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultListPreviewTest {

    private val password = "S3cret-Pa55word!"
    private val customValue = "4111-1111-1111-1111"

    private fun listLine(entry: VaultEntry): String {
        val app = ApplicationProvider.getApplicationContext<VaultPassApplication>()
        return app.container.vaultRepository.entryToVaultListEntry(entry).username
    }

    @Test
    fun emptyUsername_withPasswordAndCustomField_showsNeitherSecret() {
        val entry = VaultEntry(
            id = 1,
            title = "Bank",
            username = "",
            password = password,
            customFields = listOf(CustomField("PIN card", customValue))
        )
        val line = listLine(entry)
        assertFalse(line.contains(password))
        assertFalse(line.contains(customValue))
        assertEquals("", line)
    }

    @Test
    fun passwordOnly_doesNotShowPassword() {
        val line = listLine(VaultEntry(id = 1, title = "Wifi", password = password))
        assertEquals("", line)
    }

    @Test
    fun customFieldOnly_doesNotShowValue() {
        val line = listLine(VaultEntry(id = 1, title = "Card", customFields = listOf(CustomField("Number", customValue))))
        assertEquals("", line)
    }

    @Test
    fun websiteOnly_showsHost() {
        val entry = VaultEntry(
            id = 1,
            title = "Mail",
            password = password,
            website = "https://www.Mail.Example.com:8443/login?next=/inbox#top",
            customFields = listOf(CustomField("Recovery", customValue))
        )
        assertEquals("mail.example.com", listLine(entry))
    }

    @Test
    fun websiteWithoutScheme_showsHost() {
        assertEquals("example.com", listLine(VaultEntry(id = 1, title = "x", website = "example.com/account")))
        assertEquals("shop.example.org", listLine(VaultEntry(id = 1, title = "x", website = "  shop.example.org:8080  ")))
        assertEquals("example.net", listLine(VaultEntry(id = 1, title = "x", website = "www.example.net")))
    }

    @Test
    fun websiteUserInfo_isNotShown() {
        val line = listLine(VaultEntry(id = 1, title = "x", website = "https://admin:$password@router.local/"))
        assertEquals("router.local", line)
        assertFalse(line.contains(password))
    }

    @Test
    fun usernameWins_overWebsite() {
        val entry = VaultEntry(
            id = 1,
            title = "Mail",
            username = "alice@example.com",
            password = password,
            website = "https://mail.example.com"
        )
        assertEquals("alice@example.com", listLine(entry))
    }

    @Test
    fun blankUsername_fallsBackToWebsite() {
        assertEquals("example.com", listLine(VaultEntry(id = 1, title = "x", username = "   ", website = "http://example.com")))
    }

    @Test
    fun nothing_showsEmpty() {
        assertEquals("", listLine(VaultEntry(id = 1, title = "Empty")))
        assertEquals("", listLine(VaultEntry(id = 1, title = "x", website = "   ")))
        assertEquals("", listLine(VaultEntry(id = 1, title = "x", website = "My bank account")))
    }

    @Test
    fun websiteHost_edgeCases() {
        assertEquals("example.com", VaultListPreview.websiteHost("\n\nhttps://example.com/a\nhttps://other.com"))
        assertEquals("[::1]", VaultListPreview.websiteHost("http://[::1]:8080/"))
        assertEquals("com.example.app", VaultListPreview.websiteHost("androidapp://com.example.app"))
        assertNull(VaultListPreview.websiteHost("https:///path-only"))
        assertNull(VaultListPreview.websiteHost(""))
    }
}
