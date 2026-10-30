package com.example

import com.example.domain.models.CustomField
import com.example.domain.models.EntryTotp
import com.example.domain.models.ReservedFields
import com.example.domain.models.VaultEntry
import com.example.domain.security.Totp
import com.example.domain.security.TotpConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** TOTP codes (RFC 6238 test vectors), setup links and keys, and the entry field that holds them. */
class TotpTest {

    private fun base32(ascii: String): String {
        // Encode an ASCII seed as Base32 so the RFC's seeds go through the same path as real keys.
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bytes = ascii.toByteArray(Charsets.US_ASCII)
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(alphabet[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    @Test
    fun rfc6238_testVectors() {
        val sha1 = TotpConfig(base32("12345678901234567890"), TotpConfig.Algorithm.SHA1, digits = 8)
        val sha256 = TotpConfig(base32("12345678901234567890123456789012"), TotpConfig.Algorithm.SHA256, digits = 8)
        val sha512 = TotpConfig(base32("1234567890123456789012345678901234567890123456789012345678901234"), TotpConfig.Algorithm.SHA512, digits = 8)
        val vectors = listOf(
            Triple(59L, listOf("94287082", "46119246", "90693936"), Unit),
            Triple(1111111109L, listOf("07081804", "68084774", "25091201"), Unit),
            Triple(1111111111L, listOf("14050471", "67062674", "99943326"), Unit),
            Triple(1234567890L, listOf("89005924", "91819424", "93441116"), Unit),
            Triple(2000000000L, listOf("69279037", "90698825", "38618901"), Unit),
            Triple(20000000000L, listOf("65353130", "77737706", "47863826"), Unit)
        )
        for ((seconds, expected, _) in vectors) {
            assertEquals("SHA1 at $seconds", expected[0], Totp.code(sha1, seconds * 1000))
            assertEquals("SHA256 at $seconds", expected[1], Totp.code(sha256, seconds * 1000))
            assertEquals("SHA512 at $seconds", expected[2], Totp.code(sha512, seconds * 1000))
        }
        // Six digits are the last six of the eight.
        assertEquals("287082", Totp.code(sha1.copy(digits = 6), 59_000))
    }

    @Test
    fun base32_decodesGooglesExampleKey() {
        // Google's key-URI page: JBSWY3DPEHPK3PXP is "Hello!" followed by DE AD BE EF.
        val expected = "Hello!".toByteArray() + byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertArrayEquals(expected, Totp.base32Decode("JBSWY3DPEHPK3PXP"))
        assertNull(Totp.base32Decode("JBSW1"))
    }

    @Test
    fun parse_setupLinksAndKeys() {
        val simple = Totp.parse("otpauth://totp/Example:alice@google.com?secret=JBSWY3DPEHPK3PXP&issuer=Example")!!
        assertEquals("JBSWY3DPEHPK3PXP", simple.secretBase32)
        assertEquals("Example", simple.issuer)
        assertEquals("alice@google.com", simple.account)
        assertEquals(6, simple.digits)
        assertEquals(30, simple.period)

        val full = Totp.parse("otpauth://totp/ACME%20Co:john.doe@email.com?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&issuer=ACME%20Co&algorithm=SHA256&digits=8&period=60")!!
        assertEquals("ACME Co", full.issuer)
        assertEquals("john.doe@email.com", full.account)
        assertEquals(TotpConfig.Algorithm.SHA256, full.algorithm)
        assertEquals(8, full.digits)
        assertEquals(60, full.period)
        assertEquals(full, Totp.parse(full.toUri()))

        // The issuer from the label when the parameter is missing; a bare key with spaces and lowercase.
        assertEquals("GitHub", Totp.parse("otpauth://totp/GitHub:octo?secret=JBSWY3DPEHPK3PXP")!!.issuer)
        assertEquals("JBSWY3DPEHPK3PXP", Totp.parse("jbsw y3dp ehpk 3pxp")!!.secretBase32)

        assertNull("Counter-based codes aren't supported", Totp.parse("otpauth://hotp/X?secret=JBSWY3DPEHPK3PXP&counter=1"))
        assertNull(Totp.parse("otpauth://totp/X?issuer=X"))
        assertNull(Totp.parse("otpauth://totp/X?secret=not*base32"))
        assertNull(Totp.parse("otpauth://totp/X?secret=JBSWY3DPEHPK3PXP&digits=4"))
        assertNull(Totp.parse("otpauth://totp/X?secret=JBSWY3DPEHPK3PXP&algorithm=MD5"))
        assertNull(Totp.parse(""))
        assertNull(Totp.parse("https://example.com"))

        // A link without a label is fine; text shorter than 16 Base32 characters (a pasted 6-digit code) is not a key.
        assertEquals("JBSWY3DPEHPK3PXP", Totp.parse("otpauth://totp?secret=JBSWY3DPEHPK3PXP")!!.secretBase32)
        assertNull(Totp.parse("234567"))
        assertNull(Totp.parse("JBSWY3DP"))
        assertNull(Totp.parse("otpauth://totp/X?secret=JBSWY3DP"))
    }

    @Test
    fun countdownDisplayAndNoSecretInText() {
        val config = TotpConfig("JBSWY3DPEHPK3PXP")
        assertEquals(30, Totp.secondsRemaining(config, 60_000))
        assertEquals(1, Totp.secondsRemaining(config, 89_999))
        assertEquals("123 456", Totp.formatForDisplay("123456"))
        assertEquals("1234 5678", Totp.formatForDisplay("12345678"))
        assertFalse(config.toString().contains("JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun entryField_holdsTheLink_andReservedFieldsStayHidden() {
        val config = Totp.parse("otpauth://totp/Bank:alice?secret=JBSWY3DPEHPK3PXP&issuer=Bank")!!
        val fields = EntryTotp.withConfig(listOf(CustomField("PIN", "1234"), CustomField("vp:website", "bank.example")), config)
        val entry = VaultEntry(title = "Bank", customFields = fields)
        assertEquals(config, EntryTotp.config(entry))
        assertEquals(listOf(CustomField("PIN", "1234")), ReservedFields.visible(fields))
        assertNull(EntryTotp.config(EntryTotp.withConfig(fields, null)))
        assertEquals(1, EntryTotp.withConfig(fields, config).count { it.key == EntryTotp.KEY })

        // Text VaultPass can't read (a hotp link from elsewhere, extra parameters) is written back exactly.
        val foreign = "otpauth://hotp/Bank:alice?secret=JBSWY3DPEHPK3PXP&counter=4&image=https%3A%2F%2Fx"
        val kept = EntryTotp.withRaw(listOf(CustomField("PIN", "1234")), foreign)
        assertEquals(foreign, EntryTotp.rawValue(kept))
        assertNull(EntryTotp.config(kept))
        assertNull(EntryTotp.rawValue(EntryTotp.withRaw(kept, "")))
    }
}
