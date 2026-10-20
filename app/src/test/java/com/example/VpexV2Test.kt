package com.example

import android.util.Base64
import com.example.security.CryptoManager
import com.example.security.SecurityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/**
 * F13: .vpex v2 names its iteration count in the file. VaultPass Desktop's VpexV2Test holds the
 * same three files (a v2 file from each app and a v1 file), so each app reads the other's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VpexV2Test {

    private val payload = """{"version":1,"entries":[{"title":"Vector","username":"user","password":"pass-v2"}]}"""
    private val password = "Vector-Password-2026"

    /** Written by VaultPass Desktop's VpexCryptoManager.encrypt: v2, 300,000 iterations. */
    private val desktopV2 = "VlBYMgAEk+DH7XRU6HycXsQ8/q3vY2mBs2gAqsDTISRy0kOyerqf5V4xV6Ke5lNAFT6tBMBMkV7W7Bt/d7v86//iYE0qQdZNkhglK87Hrle01POv7Rlcdw9loHYEASGRhLGaM/upsOrXcmEN+4BY4AsfSJwZkXy8VcnioPDzGiCidLvZomUj"

    /** Written by this app's CryptoManager.encryptBackup (the copy Desktop's test decrypts). */
    private val androidV2 = "VlBYMgAEk+AX7OzURd9hP0oP8uo3yNO3ETuGkUHyX71NV+gf0x+fRJyV4F17k8VQoWe4eSNtJxLjswtPZLTxvuQITNM9c9CBpb3s670zon5/z2TE70WlwVBhmhaEK2fKxrQ5htHMqVx4a6d/7cvwjm1e/ekLoEzZTQY+t1zNSHYpMa859sxS"

    /** v2 at 150,000 iterations, made by an independent implementation: the count must come from the file. */
    private val v2At150k = "VlBYMgACSfBEOtJfGuajGoeyxYa3HBnlbP9X43mTifX31YboqfOrrLRm6anZiswEPJ+3rq/h6+4phP0rGhP+PfOHz+vTcBcCAxLwSz3nzr9zXMNp0V5Qb1x0hr91OIOnesEe+QfX9raurSmtBLZ3F84I8o9WkwLNtTRvise2mV5EJoyT3Bdd"

    /** v1, as every earlier version of both apps wrote it: no header, 100,000 iterations. */
    private val v1 = "UmDNlSrKqqKjzlQUGV3UePPPZXNt+dRTKG+wULppTW3GsestNCVPNGKkEdNnsWsIRlJsPWNkpk+L4yeq2M/0J1w93QH4KKVLuf4tZ9+qFN2uhiJXKeT92nvuKYVRhq9bAL78ds8dyMn9OczmB3KGa1Ki2QotVOVAw0eIVglWNg=="

    private fun decode(base64: String): ByteArray = Base64.decode(base64, Base64.NO_WRAP)

    @Test
    fun newBackups_areV2_atTheCurrentIterationCount() {
        val bytes = CryptoManager.encryptBackup(payload, password)
        assertEquals("VPX2", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(SecurityPolicy.CURRENT_KDF_ITERATIONS, ByteBuffer.wrap(bytes, 4, 4).int)
        assertEquals(payload, CryptoManager.decryptBackup(bytes, password))
    }

    @Test
    fun v2FilesFromBothApps_andV1Files_decrypt() {
        assertEquals(payload, CryptoManager.decryptBackup(decode(desktopV2), password))
        assertEquals(payload, CryptoManager.decryptBackup(decode(androidV2), password))
        assertEquals(payload, CryptoManager.decryptBackup(decode(v1), password))
    }

    @Test
    fun theIterationCount_isReadFromTheFile() {
        assertEquals(150_000, ByteBuffer.wrap(decode(v2At150k), 4, 4).int)
        assertEquals(payload, CryptoManager.decryptBackup(decode(v2At150k), password))
    }

    @Test
    fun aWrongPassword_failsForV2() {
        assertNull(CryptoManager.decryptBackup(decode(desktopV2), "Wrong-Password-2026"))
    }

    @Test
    fun aChangedIterationCount_failsDecryption() {
        // Still a count a v2 file may name, so the header is read with it, and the file no longer opens.
        val bytes = decode(androidV2)
        ByteBuffer.wrap(bytes).putInt(4, 100_000)
        assertNull(CryptoManager.decryptBackup(bytes, password))
    }

    @Test
    fun aCountOutsideTheAllowedRange_isReadAsV1_notRun() {
        // 2 billion iterations would hang the app: the file is read as v1 instead, which fails.
        val bytes = decode(androidV2)
        ByteBuffer.wrap(bytes).putInt(4, Int.MAX_VALUE)
        assertNull(CryptoManager.decryptBackup(bytes, password))
    }
}
