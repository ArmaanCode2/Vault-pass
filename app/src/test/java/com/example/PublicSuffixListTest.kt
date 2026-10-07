package com.example

import com.example.service.PublicSuffixList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registrable-domain lookups against the bundled list, using the standard vectors from
 * https://github.com/publicsuffix/list/blob/main/tests/test_psl.txt (results in ASCII form).
 */
class PublicSuffixListTest {

    private val psl = TestPublicSuffixList.list

    private fun check(host: String?, expected: String?) {
        assertEquals("registrableDomain($host)", expected, psl.registrableDomain(host))
    }

    @Test
    fun parsesBothSections() {
        assertTrue("ICANN rules: ${psl.icannRuleCount}", psl.icannRuleCount > 5000)
        assertTrue("PRIVATE rules: ${psl.privateRuleCount}", psl.privateRuleCount > 1000)
    }

    @Test
    fun nullAndMixedCase() {
        check(null, null)
        check("", null)
        check("COM", null)
        check("example.COM", "example.com")
        check("WwW.Example.COM", "example.com")
    }

    @Test
    fun leadingDotAndTrailingDot() {
        check(".com", null)
        check(".example", null)
        check(".example.com", null)
        check(".example.example", null)
        check("www.example.com.", "example.com")
        check("a..example.com", null)
    }

    @Test
    fun unlistedTld_usesImplicitStarRule() {
        check("example", null)
        check("example.example", "example.example")
        check("b.example.example", "example.example")
        check("a.b.example.example", "example.example")
    }

    @Test
    fun simpleRules() {
        check("biz", null)
        check("domain.biz", "domain.biz")
        check("b.domain.biz", "domain.biz")
        check("a.b.domain.biz", "domain.biz")
        check("com", null)
        check("example.com", "example.com")
        check("b.example.com", "example.com")
        check("a.b.example.com", "example.com")
        check("uk.com", null)
        check("example.uk.com", "example.uk.com")
        check("b.example.uk.com", "example.uk.com")
        check("a.b.example.uk.com", "example.uk.com")
        check("test.ac", "test.ac")
    }

    @Test
    fun coUk() {
        check("co.uk", null)
        check("example.co.uk", "example.co.uk")
        check("www.example.co.uk", "example.co.uk")
        check("a.b.example.co.uk", "example.co.uk")
    }

    @Test
    fun wildcardOnlyTld() {
        check("mm", null)
        check("c.mm", null)
        check("b.c.mm", "b.c.mm")
        check("a.b.c.mm", "b.c.mm")
    }

    @Test
    fun japaneseRules_wildcardsAndExceptions() {
        check("jp", null)
        check("test.jp", "test.jp")
        check("www.test.jp", "test.jp")
        check("ac.jp", null)
        check("test.ac.jp", "test.ac.jp")
        check("www.test.ac.jp", "test.ac.jp")
        check("kyoto.jp", null)
        check("test.kyoto.jp", "test.kyoto.jp")
        check("ide.kyoto.jp", null)
        check("b.ide.kyoto.jp", "b.ide.kyoto.jp")
        check("a.b.ide.kyoto.jp", "b.ide.kyoto.jp")
        check("c.kobe.jp", null)
        check("b.c.kobe.jp", "b.c.kobe.jp")
        check("a.b.c.kobe.jp", "b.c.kobe.jp")
        check("city.kobe.jp", "city.kobe.jp")
        check("www.city.kobe.jp", "city.kobe.jp")
        // *.kawasaki.jp with !city.kawasaki.jp
        check("c.kawasaki.jp", null)
        check("b.c.kawasaki.jp", "b.c.kawasaki.jp")
        check("city.kawasaki.jp", "city.kawasaki.jp")
        check("www.city.kawasaki.jp", "city.kawasaki.jp")
    }

    @Test
    fun wildcardTldWithException() {
        check("ck", null)
        check("test.ck", null)
        check("b.test.ck", "b.test.ck")
        check("a.b.test.ck", "b.test.ck")
        check("www.ck", "www.ck")
        check("www.www.ck", "www.ck")
    }

    @Test
    fun usK12() {
        check("us", null)
        check("test.us", "test.us")
        check("www.test.us", "test.us")
        check("ak.us", null)
        check("test.ak.us", "test.ak.us")
        check("www.test.ak.us", "test.ak.us")
        check("k12.ak.us", null)
        check("test.k12.ak.us", "test.k12.ak.us")
        check("www.test.k12.ak.us", "test.k12.ak.us")
    }

    @Test
    fun idnLabels_returnPunycode() {
        val shishi = "xn--85x722f"   // 食狮
        val gongsi = "xn--55qx5d"    // 公司
        val zhongguo = "xn--fiqs8s"  // 中国
        check("食狮.com.cn", "$shishi.com.cn")
        check("食狮.公司.cn", "$shishi.$gongsi.cn")
        check("www.食狮.公司.cn", "$shishi.$gongsi.cn")
        check("shishi.公司.cn", "shishi.$gongsi.cn")
        check("公司.cn", null)
        check("食狮.中国", "$shishi.$zhongguo")
        check("www.食狮.中国", "$shishi.$zhongguo")
        check("shishi.中国", "shishi.$zhongguo")
        check("中国", null)
        // Same, already punycoded.
        check("$shishi.com.cn", "$shishi.com.cn")
        check("$shishi.$gongsi.cn", "$shishi.$gongsi.cn")
        check("www.$shishi.$gongsi.cn", "$shishi.$gongsi.cn")
        check("$gongsi.cn", null)
        check("www.$shishi.$zhongguo", "$shishi.$zhongguo")
        check(zhongguo, null)
    }

    @Test
    fun privateSectionRules() {
        check("github.io", null)
        check("a.github.io", "a.github.io")
        check("x.a.github.io", "a.github.io")
        check("myapp.herokuapp.com", "myapp.herokuapp.com")
        check("herokuapp.com", null)
    }

    @Test
    fun ipLiterals_haveNoRegistrableDomain() {
        check("192.168.1.1", null)
        check("10.0.0.1", null)
        check("[::1]", null)
        check("2001:db8::1", null)
    }

    @Test
    fun normalizeHost_lowercasesConvertsIdnAndStripsTrailingDot() {
        assertEquals("example.com", PublicSuffixList.normalizeHost("Example.COM."))
        assertEquals("xn--85x722f.com.cn", PublicSuffixList.normalizeHost("食狮.com.cn"))
        assertNull(PublicSuffixList.normalizeHost("."))
        assertNull(PublicSuffixList.normalizeHost("   "))
    }
}
