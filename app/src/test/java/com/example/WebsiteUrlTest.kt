package com.example

import com.example.util.WebsiteUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F28: the details screen's "Open" button only ever opens an http(s) page with a host. */
class WebsiteUrlTest {

    @Test
    fun blank_isNothingToOpen() {
        assertNull(WebsiteUrl.browserUrl(null))
        assertNull(WebsiteUrl.browserUrl(""))
        assertNull(WebsiteUrl.browserUrl("   "))
    }

    @Test
    fun aPlainDomain_getsHttps() {
        assertEquals("https://example.com", WebsiteUrl.browserUrl("example.com"))
        assertEquals("https://www.example.com/login?next=1", WebsiteUrl.browserUrl("www.example.com/login?next=1"))
        assertEquals("https://example.com:8443/login", WebsiteUrl.browserUrl("example.com:8443/login"))
        assertEquals("https://192.168.1.1:8080", WebsiteUrl.browserUrl("192.168.1.1:8080"))
    }

    @Test
    fun httpAndHttps_areKept() {
        assertEquals("http://example.com/a", WebsiteUrl.browserUrl("http://example.com/a"))
        assertEquals("https://example.com/a", WebsiteUrl.browserUrl("https://example.com/a"))
    }

    @Test
    fun anUppercaseScheme_isLowercased_andTheRestKept() {
        assertEquals("https://Example.COM/Path", WebsiteUrl.browserUrl("HTTPS://Example.COM/Path"))
        assertEquals("http://example.com", WebsiteUrl.browserUrl("Http://example.com"))
    }

    @Test
    fun surroundingSpaces_areTrimmed() {
        assertEquals("https://example.com", WebsiteUrl.browserUrl("  https://example.com \n"))
        assertEquals("https://example.com", WebsiteUrl.browserUrl("\texample.com  "))
    }

    @Test
    fun autofillAppLinks_areNotOpened() {
        assertNull(WebsiteUrl.browserUrl("androidapp://com.example.bank"))
        assertNull(WebsiteUrl.browserUrl("AndroidApp://com.example.bank"))
    }

    @Test
    fun otherSchemes_areRefused() {
        assertNull(WebsiteUrl.browserUrl("javascript:alert(1)"))
        assertNull(WebsiteUrl.browserUrl("JavaScript://example.com/%0aalert(1)"))
        assertNull(WebsiteUrl.browserUrl("file:///sdcard/secret.txt"))
        assertNull(WebsiteUrl.browserUrl("file:/etc/hosts"))
        assertNull(WebsiteUrl.browserUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(WebsiteUrl.browserUrl("content://com.example.provider/item"))
        assertNull(WebsiteUrl.browserUrl("data:text/html,<script>alert(1)</script>"))
        assertNull(WebsiteUrl.browserUrl("mailto:someone@example.com"))
        assertNull(WebsiteUrl.browserUrl("ftp://example.com"))
        // http(s) without "//" is not a web address either.
        assertNull(WebsiteUrl.browserUrl("https:example.com"))
    }

    @Test
    fun aMissingHost_isNothingToOpen() {
        assertNull(WebsiteUrl.browserUrl("https://"))
        assertNull(WebsiteUrl.browserUrl("http:///path"))
        assertNull(WebsiteUrl.browserUrl("https://:8080/path"))
        assertNull(WebsiteUrl.browserUrl("/just/a/path"))
        assertNull(WebsiteUrl.browserUrl("not a host"))
    }
}
