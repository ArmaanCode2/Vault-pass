package com.example.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.sync.models.SyncState
import com.example.network.sync.LanSocketTransport
import com.example.repository.PairedDeviceRepository
import com.vaultpass.synccore.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket

/** Pairing QR codes (sync protocol v2), the transport's initial state and a listener that can't bind. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanSocketTransportTest {

    private val secretHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

    // --- QR code parsing ---

    @Test
    fun qrParser_validV2Code_parsesAllFields() {
        val uri = "vaultpass://pair?v=2&deviceId=desktop-999&name=MacBook%20Pro&secret=$secretHex&ip=192.168.1.120&port=53853"
        val data = LanSocketTransport.parseQrPairingUri(uri)

        assertNotNull(data)
        assertEquals("desktop-999", data?.deviceId)
        assertEquals("MacBook Pro", data?.deviceName)
        assertArrayEquals(Hex.decode(secretHex), data?.pairingSecret)
        assertEquals("192.168.1.120", data?.primaryIpAddress)
        assertEquals(53853, data?.port)
    }

    @Test
    fun qrParser_defaultPortAppliedWhenOmitted() {
        val data = LanSocketTransport.parseQrPairingUri("vaultpass://pair?v=2&deviceId=d&name=W&secret=$secretHex&ip=10.0.0.5")
        assertEquals(LanSocketTransport.DEFAULT_TCP_PORT, data?.port)
    }

    @Test
    fun qrParser_withCandidateIps_parsesListCorrectly() {
        val uri = "vaultpass://pair?v=2&deviceId=d&name=Desktop&secret=$secretHex&ip=192.168.1.100&ips=192.168.1.100,10.0.0.15,172.16.0.5"
        val data = LanSocketTransport.parseQrPairingUri(uri)

        assertEquals("192.168.1.100", data?.primaryIpAddress)
        assertEquals(listOf("192.168.1.100", "10.0.0.15", "172.16.0.5"), data?.candidateIpAddresses)
    }

    @Test
    fun qrParser_fallbackToSingleIp_whenIpsMissing() {
        val data = LanSocketTransport.parseQrPairingUri("vaultpass://pair?v=2&deviceId=d&name=Desktop&secret=$secretHex&ip=192.168.1.100")
        assertEquals(listOf("192.168.1.100"), data?.candidateIpAddresses)
    }

    @Test
    fun qrParser_rejectsV1AndMalformedCodes() {
        val base = "vaultpass://pair?v=2&deviceId=d&name=x&secret=$secretHex&ip=1.2.3.4"
        assertNotNull(LanSocketTransport.parseQrPairingUri(base))

        assertNull("v1 code", LanSocketTransport.parseQrPairingUri("vaultpass://pair?deviceId=d&name=x&token=abc&ip=1.2.3.4"))
        assertNull("Missing version", LanSocketTransport.parseQrPairingUri(base.replace("v=2&", "")))
        assertNull("Other version", LanSocketTransport.parseQrPairingUri(base.replace("v=2", "v=3")))
        assertNull("Missing secret", LanSocketTransport.parseQrPairingUri(base.replace("&secret=$secretHex", "")))
        assertNull("Short secret", LanSocketTransport.parseQrPairingUri(base.replace(secretHex, "0011")))
        assertNull("Non-hex secret", LanSocketTransport.parseQrPairingUri(base.replace(secretHex, "zz".repeat(32))))
        assertNull("Missing deviceId", LanSocketTransport.parseQrPairingUri(base.replace("deviceId=d&", "")))
        assertNull("Missing name", LanSocketTransport.parseQrPairingUri(base.replace("name=x&", "")))
        assertNull("Missing ip and ips", LanSocketTransport.parseQrPairingUri(base.replace("&ip=1.2.3.4", "")))
        assertNull("Bad port", LanSocketTransport.parseQrPairingUri("$base&port=70000"))
        assertNull("Non-numeric port", LanSocketTransport.parseQrPairingUri("$base&port=abc"))
        assertNull("Wrong scheme", LanSocketTransport.parseQrPairingUri(base.replace("vaultpass://", "https://")))
        assertNull("Random string", LanSocketTransport.parseQrPairingUri("not_a_valid_pairing_qr_code"))
    }

    @Test
    fun legacyQrCodes_areRecognisedSoTheUserCanBeToldToUpdate() {
        assertTrue(LanSocketTransport.isLegacyPairingUri("vaultpass://pair?deviceId=d&name=x&token=abc&ip=1.2.3.4"))
        assertTrue(LanSocketTransport.isLegacyPairingUri("vaultpass://pair?deviceId=d&name=x&pairingToken=abc&ip=1.2.3.4"))
        assertFalse(LanSocketTransport.isLegacyPairingUri("vaultpass://pair?v=2&deviceId=d&name=x&secret=$secretHex&ip=1.2.3.4"))
        assertFalse(LanSocketTransport.isLegacyPairingUri("https://example.com/?token=abc"))
    }

    // --- Initial state ---

    @Test
    fun newTransport_isIdleAndDisconnected() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transport = LanSocketTransport(
            context = context,
            localDeviceId = "mobile-dev-1",
            localDeviceName = "Pixel 8",
            pairedDeviceRepository = PairedDeviceRepository(context),
            scope = this
        )
        assertFalse(transport.isConnected)
        assertEquals(SyncState.IDLE, transport.syncState.value)
        assertEquals(LanSocketTransport.SyncRole.NONE, transport.syncRole)
        assertFalse(transport.canSendVaultData())
        assertFalse(transport.approveIncomingSync())
        assertNull("Nothing has failed yet", transport.lastFailure())
        assertNull(transport.listenerFailure())
    }

    @Test
    fun aPortThatIsTaken_isReportedInsteadOfSwallowed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ServerSocket(0).use { taken ->
            val transport = LanSocketTransport(
                context = context,
                localDeviceId = "mobile-dev-1",
                localDeviceName = "Pixel 8",
                pairedDeviceRepository = PairedDeviceRepository(context),
                scope = scope,
                listenPort = taken.localPort
            )
            try {
                transport.startServer()
                val failure = transport.listenerFailure()
                assertNotNull("A bind failure must be kept for the UI", failure)
                assertTrue(failure!!.contains("${taken.localPort}"))
            } finally {
                transport.stopServer()
                scope.cancel()
            }
        }
    }
}
