package com.example

import com.example.domain.models.CustomField
import com.example.domain.models.EntryPasskeys
import com.example.domain.models.PasskeyRecord
import com.example.domain.models.VaultEntry
import com.example.domain.security.Cbor
import com.example.domain.security.SecurityAnalyzer
import com.example.domain.security.WebAuthn
import com.example.service.CredentialCaller
import com.example.service.CredentialProviderSupport
import com.example.service.PrivilegedBrowserList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec

/** Passkeys: CBOR, authenticator data, registration and sign-in results, the request JSON, and how entries keep them. */
class PasskeyTest {

    /** A small CBOR reader (integers, byte/text strings, maps) to check what VaultPass writes. */
    private class CborReader(private val bytes: ByteArray, var position: Int = 0) {
        fun read(): Any {
            val initial = bytes[position++].toInt() and 0xFF
            val major = initial shr 5
            val length = length(initial and 0x1F)
            return when (major) {
                0 -> length
                1 -> -1 - length
                2 -> bytes.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
                3 -> String(bytes, position, length.toInt(), Charsets.UTF_8).also { position += length.toInt() }
                5 -> LinkedHashMap<Any, Any>().apply { repeat(length.toInt()) { put(read(), read()) } }
                else -> error("Unexpected major type $major")
            }
        }

        private fun length(info: Int): Long = when {
            info < 24 -> info.toLong()
            else -> {
                val count = 1 shl (info - 24)
                var value = 0L
                repeat(count) { value = (value shl 8) or (bytes[position++].toLong() and 0xFF) }
                value
            }
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun cbor_encodesTheCanonicalForms() {
        assertEquals("00", hex(Cbor.encode(0)))
        assertEquals("17", hex(Cbor.encode(23)))
        assertEquals("1818", hex(Cbor.encode(24)))
        assertEquals("190100", hex(Cbor.encode(256)))
        assertEquals("1a00010000", hex(Cbor.encode(65536)))
        assertEquals("20", hex(Cbor.encode(-1)))
        assertEquals("26", hex(Cbor.encode(-7)))
        assertEquals("3863", hex(Cbor.encode(-100)))
        assertEquals("43010203", hex(Cbor.encode(byteArrayOf(1, 2, 3))))
        assertEquals("63666d74", hex(Cbor.encode("fmt")))
        assertEquals("a0", hex(Cbor.encode(emptyMap<Any, Any>())))
        assertEquals("a20102200a", hex(Cbor.encode(linkedMapOf<Any, Any>(1 to 2, -1 to 10))))
    }

    @Test
    fun authenticatorData_layout() {
        val data = WebAuthn.authenticatorData("example.com", WebAuthn.FLAG_UP or WebAuthn.FLAG_UV, signCount = 0)
        assertEquals(37, data.size)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest("example.com".toByteArray()), data.copyOfRange(0, 32))
        assertEquals(0x05, data[32].toInt())
        assertArrayEquals(ByteArray(4), data.copyOfRange(33, 37))
    }

    @Test
    fun registration_attestationObjectAndCoseKey_signInVerifiesWithTheRegisteredKey() {
        val keyPair = WebAuthn.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey
        val credentialId = WebAuthn.newCredentialId()
        assertEquals(32, credentialId.size)
        val flags = WebAuthn.FLAG_UP or WebAuthn.FLAG_UV or WebAuthn.FLAG_BE or WebAuthn.FLAG_BS
        val authData = WebAuthn.authenticatorData("example.com", flags, attestedCredentialData = WebAuthn.attestedCredentialData(credentialId, publicKey))
        assertEquals(flags or WebAuthn.FLAG_AT, authData[32].toInt() and 0xFF)
        assertArrayEquals("AAGUID is zero (none attestation)", ByteArray(16), authData.copyOfRange(37, 53))
        assertEquals(32, ((authData[53].toInt() and 0xFF) shl 8) or (authData[54].toInt() and 0xFF))
        assertArrayEquals(credentialId, authData.copyOfRange(55, 87))

        // The COSE key fills the rest exactly.
        val reader = CborReader(authData, 87)
        @Suppress("UNCHECKED_CAST")
        val cose = reader.read() as Map<Any, Any>
        assertEquals(authData.size, reader.position)
        assertEquals(listOf<Any>(1L, 3L, -1L, -2L, -3L), cose.keys.toList())
        assertEquals(2L, cose[1L])
        assertEquals(-7L, cose[3L])
        assertEquals(1L, cose[-1L])
        val x = cose[-2L] as ByteArray
        val y = cose[-3L] as ByteArray
        assertEquals(32, x.size)
        assertEquals(32, y.size)

        @Suppress("UNCHECKED_CAST")
        val attestation = CborReader(WebAuthn.attestationObject(authData)).read() as Map<Any, Any>
        assertEquals(listOf<Any>("fmt", "attStmt", "authData"), attestation.keys.toList())
        assertEquals("none", attestation["fmt"])
        assertEquals(emptyMap<Any, Any>(), attestation["attStmt"])
        assertArrayEquals(authData, attestation["authData"] as ByteArray)

        // The site rebuilds the key from x and y; a sign-in signed with the stored PKCS#8 key verifies with it.
        val siteKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), publicKey.params))
        val stored = WebAuthn.privateKey(WebAuthn.decode(WebAuthn.encode(keyPair.private.encoded))!!)
        val signInData = WebAuthn.authenticatorData("example.com", flags)
        val clientDataHash = WebAuthn.sha256(WebAuthn.clientDataJson(WebAuthn.TYPE_GET, "Y2hhbGxlbmdl", "https://example.com"))
        val signature = WebAuthn.sign(stored, signInData, clientDataHash)
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(siteKey)
            update(signInData + clientDataHash)
        }
        assertTrue(verifier.verify(signature))
        // The SPKI form in the JSON result is the same key.
        assertEquals(publicKey, KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey.encoded)))
    }

    private val creationJson = """
        {
          "rp": {"id": "Example.COM", "name": "Example"},
          "user": {"id": "dXNlci0xMjM=", "name": "alice@example.com", "displayName": "Alice"},
          "challenge": "Y2hhbGxlbmdlLWNyZWF0ZQ==",
          "pubKeyCredParams": [{"type": "public-key", "alg": -8}, {"type": "public-key", "alg": -7}, {"type": "public-key", "alg": -257}],
          "excludeCredentials": [{"type": "public-key", "id": "AQID"}],
          "authenticatorSelection": {"residentKey": "required", "userVerification": "required"},
          "extensions": {"credProps": true},
          "attestation": "none"
        }
    """.trimIndent()

    @Test
    fun parse_creationAndRequestOptions() {
        val options = WebAuthn.parseCreationOptions(creationJson)!!
        assertEquals("example.com", options.rpId)
        assertEquals("Example", options.rpName)
        assertArrayEquals("user-123".toByteArray(), options.userHandle)
        assertEquals("alice@example.com", options.userName)
        assertEquals("Alice", options.userDisplayName)
        assertEquals("Padding is dropped", "Y2hhbGxlbmdlLWNyZWF0ZQ", options.challenge)
        assertTrue(options.supportsEs256)
        assertEquals(setOf("AQID"), options.excludeCredentialIds)
        assertTrue(options.userVerificationRequired)
        assertTrue(options.credProps)

        assertFalse(WebAuthn.parseCreationOptions(creationJson.replace("\"alg\": -7", "\"alg\": -35"))!!.supportsEs256)
        val noParams = """{"rp":{"id":"a.com"},"user":{"id":"AQ","name":"n"},"challenge":"AQ","pubKeyCredParams":[]}"""
        assertTrue("No list means the defaults, ES256 among them", WebAuthn.parseCreationOptions(noParams)!!.supportsEs256)
        assertNull("No user id", WebAuthn.parseCreationOptions("""{"rp":{"id":"a.com"},"user":{"name":"n"},"challenge":"AQ"}"""))
        assertNull("Challenge isn't base64url", WebAuthn.parseCreationOptions("""{"rp":{"id":"a.com"},"user":{"id":"AQ"},"challenge":"*"}"""))
        assertNull(WebAuthn.parseCreationOptions("not json"))
        val longHandle = WebAuthn.encode(ByteArray(65))
        assertNull("User handle over 64 bytes", WebAuthn.parseCreationOptions("""{"rp":{"id":"a.com"},"user":{"id":"$longHandle"},"challenge":"AQ"}"""))
        val longName = WebAuthn.parseCreationOptions("""{"rp":{"id":"a.com","name":"${"r".repeat(300)}"},"user":{"id":"AQ","name":"${"n".repeat(300)}"},"challenge":"AQ"}""")!!
        assertEquals(64, longName.userName.length)
        assertEquals(64, longName.rpName.length)

        val request = WebAuthn.parseRequestOptions(
            """{"rpId":"Example.com","challenge":"AQIDBA","allowCredentials":[{"type":"public-key","id":"AQID"},{"id":"BAU="}],"userVerification":"preferred"}"""
        )!!
        assertEquals("example.com", request.rpId)
        assertEquals("Padding is dropped", setOf("AQID", "BAU"), request.allowCredentialIds)
        assertFalse(request.userVerificationRequired)
        assertNull(WebAuthn.parseRequestOptions("""{"rpId":"example.com"}"""))
        // Standard base64 ids are read too; an id that can't be read keeps the list from meaning "any passkey".
        assertArrayEquals(byteArrayOf(0xFB.toByte(), 0xFF.toByte()), WebAuthn.decode("+/8="))
        val unreadable = WebAuthn.parseRequestOptions("""{"challenge":"AQ","allowCredentials":[{"id":"%%%"}]}""")!!
        assertEquals(setOf("%%%"), unreadable.allowCredentialIds)
        assertTrue(EntryPasskeys.matching(listOf(VaultEntry(customFields = EntryPasskeys.withPasskey(emptyList(), passkey()))), "example.com", unreadable.allowCredentialIds).isEmpty())
        assertNull("A browser may leave the rpId out", WebAuthn.parseRequestOptions("""{"challenge":"AQ"}""")!!.rpId)
    }

    @Test
    fun responseJson_hasTheFieldsCredentialManagerReads() {
        val keyPair = WebAuthn.generateKeyPair()
        val id = byteArrayOf(1, 2, 3)
        val authData = WebAuthn.authenticatorData("example.com", WebAuthn.FLAG_UP)
        val registration = Json.parseToJsonElement(
            WebAuthn.registrationResponseJson(id, "{}".toByteArray(), authData, keyPair.public.encoded, credProps = true)
        ).jsonObject
        assertEquals("AQID", registration["id"]!!.jsonPrimitive.content)
        assertEquals("AQID", registration["rawId"]!!.jsonPrimitive.content)
        assertEquals("public-key", registration["type"]!!.jsonPrimitive.content)
        val response = registration["response"]!!.jsonObject
        for (field in listOf("clientDataJSON", "attestationObject", "authenticatorData", "publicKey")) {
            assertNotNull(field, WebAuthn.decode(response[field]!!.jsonPrimitive.content))
        }
        assertEquals(-7, response["publicKeyAlgorithm"]!!.jsonPrimitive.int)
        assertEquals(listOf("internal", "hybrid"), response["transports"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(true, registration["clientExtensionResults"]!!.jsonObject["credProps"]!!.jsonObject["rk"]!!.jsonPrimitive.content.toBoolean())
        val withoutCredProps = Json.parseToJsonElement(
            WebAuthn.registrationResponseJson(id, "{}".toByteArray(), authData, keyPair.public.encoded, credProps = false)
        ).jsonObject
        assertEquals(JsonObject(emptyMap()), withoutCredProps["clientExtensionResults"])

        val assertion = Json.parseToJsonElement(
            WebAuthn.assertionResponseJson(id, "{}".toByteArray(), authData, byteArrayOf(9), "user".toByteArray())
        ).jsonObject["response"]!!.jsonObject
        assertArrayEquals("user".toByteArray(), WebAuthn.decode(assertion["userHandle"]!!.jsonPrimitive.content))
        assertArrayEquals(byteArrayOf(9), WebAuthn.decode(assertion["signature"]!!.jsonPrimitive.content))
        assertArrayEquals(authData, WebAuthn.decode(assertion["authenticatorData"]!!.jsonPrimitive.content))
    }

    @Test
    fun clientDataAndAppOrigin() {
        val json = String(WebAuthn.clientDataJson(WebAuthn.TYPE_CREATE, "AQID", "android:apk-key-hash:abc", "com.bank.app"))
        assertEquals("""{"type":"webauthn.create","challenge":"AQID","origin":"android:apk-key-hash:abc","androidPackageName":"com.bank.app"}""", json)

        val certificate = "app-signing-cert".toByteArray()
        val expected = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate))
        assertEquals("android:apk-key-hash:$expected", WebAuthn.appOrigin(certificate))
    }

    private fun passkey(rpId: String = "example.com", user: String = "dXNlcg", id: String = "AQID", userName: String = "alice") =
        PasskeyRecord(credentialId = id, rpId = rpId, userHandle = user, userName = userName, keyValue = "a2V5", createdAt = 5)

    @Test
    fun entryField_roundTrip_replaceAndRemove_neverShowsTheKey() {
        val record = passkey()
        assertEquals(record, EntryPasskeys.parse(EntryPasskeys.encode(record)))
        assertFalse(record.toString().contains("a2V5"))
        assertNull(EntryPasskeys.parse("{\"rpId\":\"example.com\"}"))
        assertNull(EntryPasskeys.parse("garbage"))

        val unreadable = CustomField(EntryPasskeys.KEY, "edited on desktop")
        val fields = EntryPasskeys.withPasskey(listOf(CustomField("PIN", "1"), unreadable), record)
        assertEquals(listOf(record), EntryPasskeys.passkeys(fields))
        assertTrue("A field VaultPass can't read is kept", unreadable in fields)

        // Same account again (new key, new id): replaced; another account: added.
        val replaced = EntryPasskeys.withPasskey(fields, record.copy(credentialId = "BAUG", keyValue = "bmV3"))
        assertEquals(listOf("BAUG"), EntryPasskeys.passkeys(replaced).map { it.credentialId })
        val two = EntryPasskeys.withPasskey(replaced, passkey(user = "b3RoZXI", id = "CQkJ"))
        assertEquals(2, EntryPasskeys.passkeys(two).size)
        assertEquals(listOf("CQkJ"), EntryPasskeys.passkeys(EntryPasskeys.withoutPasskey(two, "BAUG")).map { it.credentialId })
    }

    @Test
    fun largestPasskey_fitsOneSyncedCustomField() {
        val name = "\"".repeat(WebAuthn.MAX_NAME)
        val record = PasskeyRecord(
            credentialId = WebAuthn.encode(WebAuthn.newCredentialId()),
            rpId = ("a".repeat(63) + ".").repeat(3) + "a".repeat(61),
            rpName = name,
            userHandle = WebAuthn.encode(ByteArray(WebAuthn.MAX_USER_HANDLE_BYTES)),
            userName = name,
            userDisplayName = name,
            keyValue = WebAuthn.encode(WebAuthn.generateKeyPair().private.encoded),
            counter = Long.MAX_VALUE,
            createdAt = Long.MAX_VALUE
        )
        val value = EntryPasskeys.encode(record)
        assertTrue("${value.length} characters", value.length <= com.vaultpass.synccore.EntryLimits.CUSTOM_FIELD_VALUE)
    }

    @Test
    fun matching_siteAllowListAndExcludedPasskeys() {
        val entry = VaultEntry(id = 1, title = "Example", syncId = "s1", customFields = EntryPasskeys.withPasskey(emptyList(), passkey()))
        val other = VaultEntry(id = 2, title = "Other", syncId = "s2", customFields = EntryPasskeys.withPasskey(emptyList(), passkey(rpId = "other.com", id = "BAUG")))
        val failed = entry.copy(id = 3, isDecryptionFailed = true)
        val entries = listOf(entry, other, failed)

        assertEquals(listOf("s1"), EntryPasskeys.matching(entries, "EXAMPLE.com", emptySet()).map { it.first.syncId })
        assertEquals(listOf("s1"), EntryPasskeys.matching(entries, "example.com", setOf("AQID", "XXXX")).map { it.first.syncId })
        assertTrue("Not in the site's list", EntryPasskeys.matching(entries, "example.com", setOf("BAUG")).isEmpty())
        assertTrue("Another site's passkey", EntryPasskeys.matching(entries, "sub.example.com", emptySet()).isEmpty())

        assertTrue(EntryPasskeys.isExcluded(entries, "example.com", setOf("AQID")))
        assertFalse(EntryPasskeys.isExcluded(entries, "example.com", setOf("BAUG")))
        assertFalse(EntryPasskeys.isExcluded(entries, "example.com", emptySet()))
    }

    @Test
    fun targetEntry_sameAccountThenSameUserOnTheSiteElseNew() {
        val forSite = { e: VaultEntry -> e.website.contains("example.com") }
        val login = VaultEntry(id = 1, title = "Example", username = "Alice", website = "https://example.com", syncId = "s1")
        val otherSite = VaultEntry(id = 2, title = "Other", username = "alice", website = "https://other.com", syncId = "s2")
        val withPasskey = VaultEntry(id = 3, title = "Old", syncId = "s3", customFields = EntryPasskeys.withPasskey(emptyList(), passkey()))
        val draft = passkey(id = "")

        assertEquals("s3", EntryPasskeys.targetEntry(listOf(login, otherSite, withPasskey), draft, forSite)?.syncId)
        assertEquals("s1", EntryPasskeys.targetEntry(listOf(otherSite, login), draft, forSite)?.syncId)
        assertNull(EntryPasskeys.targetEntry(listOf(otherSite), draft, forSite))
        assertNull("No user name: always a new login", EntryPasskeys.targetEntry(listOf(login), draft.copy(userName = ""), forSite))
        assertNull(EntryPasskeys.targetEntry(listOf(login.copy(isDeleted = true)), draft, forSite))
    }

    @Test
    fun securityScore_passkeyOnlyLoginIsNotMissingAPassword() {
        val passkeyOnly = VaultEntry(id = 1, title = "Example", customFields = EntryPasskeys.withPasskey(emptyList(), passkey()))
        val empty = VaultEntry(id = 2, title = "Empty")
        val stats = SecurityAnalyzer.analyze(listOf(passkeyOnly, empty))
        assertEquals(setOf(2), stats.missingEntryIds)
        assertEquals(1, stats.totalPasswords)
    }

    @Test
    fun allowlist_releaseCertificatesOnly_inTheColonForm() {
        fun colon(cert: ByteArray) = PrivilegedBrowserList.certificateFingerprint(cert).chunked(2).joinToString(":")
        val release = "release".toByteArray()
        val debug = "debug".toByteArray()
        val list = PrivilegedBrowserList.parse(
            """{"apps":[{"type":"android","info":{"package_name":"com.browser","signatures":[
                {"build":"release","cert_fingerprint_sha256":"${colon(release).lowercase()}"},
                {"build":"userdebug","cert_fingerprint_sha256":"${colon(debug)}"}]}}]}""".byteInputStream()
        )
        val apps = Json.parseToJsonElement(CredentialProviderSupport.allowlistJson(list)).jsonObject["apps"]!!.jsonArray
        val info = apps.single().jsonObject["info"]!!.jsonObject
        assertEquals("com.browser", info["package_name"]!!.jsonPrimitive.content)
        val signatures = info["signatures"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(colon(release)), signatures.map { it["cert_fingerprint_sha256"]!!.jsonPrimitive.content })
        assertEquals(listOf("release"), signatures.map { it["build"]!!.jsonPrimitive.content })
    }

    @Test
    fun webTargetRpIdAndAppKey() {
        assertEquals("example.com" to "https", CredentialProviderSupport.webTarget("https://Example.com:8443"))
        assertEquals("example.com" to "http", CredentialProviderSupport.webTarget("http://example.com"))
        assertNull(CredentialProviderSupport.webTarget("android:apk-key-hash:abc"))
        assertNull(CredentialProviderSupport.webTarget("ftp://example.com"))

        val browser = CredentialCaller.Browser("com.android.chrome", "https://login.example.com", "login.example.com", "https")
        val app = CredentialCaller.App("com.bank.app", "android:apk-key-hash:x", com.example.service.PackageSigners(setOf("B", "A"), setOf("A", "B")))
        assertEquals("example.com", CredentialProviderSupport.rpIdFor("example.com", browser))
        assertEquals("A browser's page host when the rpId is left out", "login.example.com", CredentialProviderSupport.rpIdFor(null, browser))
        assertNull("An app must name the site", CredentialProviderSupport.rpIdFor(null, app))

        // An app's rpId must be a plain host name.
        assertNull(CredentialProviderSupport.rpIdFor("bank.com\nTap Continue", app))
        assertNull(CredentialProviderSupport.rpIdFor("https://bank.com", app))
        assertNull(CredentialProviderSupport.rpIdFor("a".repeat(254), app))
        assertNull(CredentialProviderSupport.rpIdFor("bank.com/login", browser))
        assertEquals("bank.com", CredentialProviderSupport.rpIdFor("bank.com", app))

        assertEquals("com.bank.app|A,B|bank.com", CredentialProviderSupport.passkeyAppKey(app, "Bank.com"))
        assertNull(CredentialProviderSupport.passkeyAppKey(app.copy(signers = null), "bank.com"))
    }
}
