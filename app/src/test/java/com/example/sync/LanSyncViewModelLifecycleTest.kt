package com.example.sync

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.VaultPassApplication
import com.example.domain.sync.diff.SyncDiffResult
import com.example.domain.sync.models.SyncFrame
import com.example.network.sync.AndroidPairKeyProtector
import com.example.network.sync.LanDiscoveryManager
import com.example.network.sync.LanSocketTransport
import com.example.repository.PairedDeviceRepository
import com.example.security.CryptoManager
import com.example.ui.sync.LanSyncViewModel
import com.vaultpass.synccore.SyncCrypto
import com.vaultpass.synccore.SyncError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The sync listener must only run while the sync screen is open and the vault is unlocked,
 * and a peer finishing first must not wipe the review on this device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanSyncViewModelLifecycleTest {

    private val port = 54891

    private lateinit var app: VaultPassApplication
    private lateinit var ioScope: CoroutineScope
    private lateinit var transport: LanSocketTransport
    private lateinit var discovery: LanDiscoveryManager
    private lateinit var repository: PairedDeviceRepository
    private lateinit var protector: AndroidPairKeyProtector
    private val unlocked = MutableStateFlow(true)

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        repository = app.container.pairedDeviceRepository
        repository.clearAllPairedDevices()
        protector = AndroidPairKeyProtector(
            CryptoManager(app.container.settingsRepository).apply { injectSoftwareDek(SyncCrypto.randomBytes(32)) }
        )
        ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        transport = LanSocketTransport(
            context = app,
            localDeviceId = "phone-vm",
            localDeviceName = "Pixel VM",
            pairedDeviceRepository = repository,
            scope = ioScope,
            pairKeyProtector = protector,
            listenPort = port
        )
        // A cancelled scope: discovery binds its socket but never broadcasts from a test.
        discovery = LanDiscoveryManager(
            context = app,
            deviceId = "phone-vm",
            deviceName = "Pixel VM",
            scope = CoroutineScope(Job().apply { cancel() })
        )
    }

    @After
    fun tearDown() {
        transport.stopServer()
        discovery.stop()
        ioScope.cancel()
    }

    @Test
    fun listener_runsWhileUnlocked_andStopsWhenTheVaultLocks() {
        newViewModel()
        assertTrue("Listener should run while the sync screen is open", canConnect())

        unlocked.value = false
        assertTrue("Listener should stop when the vault locks", waitUntil { !canConnect() })
    }

    @Test
    fun lockedVault_neverStartsTheListener() {
        unlocked.value = false
        newViewModel()
        idleMain()
        assertFalse(canConnect())
    }

    @Test
    fun aCodedCancelFromThePeer_endsTheReviewAndNamesTheReason() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        val viewModel = newViewModel()
        viewModel.setDiffResultForTesting(
            SyncDiffResult(
                newRemoteCount = 1,
                newLocalCount = 0,
                modifiedCount = 0,
                unchangedCount = 0,
                diffItems = emptyList()
            )
        )

        ScriptedDesktop.connect(port, key).use { desktop ->
            desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
            assertTrue(waitUntil { transport.pendingSyncRequest.value != null })
            desktop.send(SyncFrame.CancelSync("no longer wanted", SyncError.PLAN_REJECTED.code))
            assertTrue(waitUntil { viewModel.errorMessage.value != null })
        }
        assertEquals(SyncError.PLAN_REJECTED.message, viewModel.errorMessage.value)
        assertEquals(SyncError.PLAN_REJECTED.code, viewModel.errorCode.value)
        assertNull("The review can't be finished without the peer", viewModel.diffResult.value)
    }

    @Test
    fun everySyncErrorCode_reachesTheUser() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        val viewModel = newViewModel()

        for (error in SyncError.values()) {
            ScriptedDesktop.connect(port, key).use { desktop ->
                desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
                assertTrue("Session for ${error.code}", waitUntil { transport.pendingSyncRequest.value != null })
                desktop.send(SyncFrame.CancelSync("ignored", error.code))
                assertTrue(
                    "${error.code} should reach the user",
                    waitUntil { viewModel.errorMessage.value == error.message }
                )
                assertEquals(error.code, viewModel.errorCode.value)
            }
            viewModel.clearError()
            assertTrue(waitUntil { !transport.isConnected })
        }
    }

    @Test
    fun lockingTheVaultMidSync_tellsThePeerWhy() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        newViewModel()

        ScriptedDesktop.connect(port, key).use { desktop ->
            val fromPhone = desktop.collectInBackground()
            desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
            assertTrue(waitUntil { transport.pendingSyncRequest.value != null })

            unlocked.value = false
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.CancelSync } })
            assertEquals(SyncError.VAULT_LOCKED.code, fromPhone.filterIsInstance<SyncFrame.CancelSync>().first().code)
            assertTrue(waitUntil { !transport.isConnected })
        }
    }

    @Test
    fun aListenerThatCantTakeItsPort_isReportedToTheUser() {
        ServerSocket(port).use {
            val viewModel = newViewModel()
            idleMain()
            val message = viewModel.errorMessage.value
            assertNotNull("The bind failure must reach the screen", message)
            assertTrue(message!!.contains("$port"))
        }
    }

    private fun newViewModel() = LanSyncViewModel(
        pairedDeviceRepository = app.container.pairedDeviceRepository,
        lanDiscoveryManager = discovery,
        lanSocketTransport = transport,
        vaultRepository = app.container.vaultRepository,
        isVaultUnlocked = unlocked
    )

    private fun canConnect(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    } catch (e: Exception) {
        false
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idleMain()
            if (condition()) return true
            Thread.sleep(20)
        }
        idleMain()
        return condition()
    }
}
