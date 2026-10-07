package com.example.sync

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.VaultPassApplication
import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.domain.sync.diff.SyncMergeExecutor
import com.example.domain.sync.models.SyncFrame
import com.example.network.sync.AndroidPairKeyProtector
import com.example.network.sync.LanDiscoveryManager
import com.example.network.sync.LanSocketTransport
import com.example.repository.PairedDeviceRepository
import com.example.security.CryptoManager
import com.example.security.VaultSessionManager
import com.example.ui.VaultViewModel
import com.example.ui.sync.LanSyncViewModel
import com.vaultpass.synccore.CustomFieldRecord
import com.vaultpass.synccore.MergePlans
import com.vaultpass.synccore.PlanAction
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.SyncError
import com.vaultpass.synccore.SyncCrypto
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords
import com.vaultpass.synccore.SyncSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The vault travels as several PayloadBatch parts; the phone accepts them only in order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncPayloadChunkingTest {

    private val port = 54897

    private lateinit var app: VaultPassApplication
    private lateinit var ioScope: CoroutineScope
    private lateinit var transport: LanSocketTransport
    private lateinit var discovery: LanDiscoveryManager
    private lateinit var repository: PairedDeviceRepository
    private lateinit var protector: AndroidPairKeyProtector
    private lateinit var pairKey: ByteArray
    private val unlocked = MutableStateFlow(true)

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        VaultSessionManager.resetForTesting()
        withContext(Dispatchers.IO) {
            app.container.appDatabase.clearAllTables()
        }
        val vaultViewModel = VaultViewModel(app.container.vaultRepository, app.container.settingsRepository)
        vaultViewModel.lock()
        idleMain()
        vaultViewModel.setupMasterPasswordSync("MasterPassword123!")
        assertTrue(vaultViewModel.isUnlocked.first())

        repository = app.container.pairedDeviceRepository
        repository.clearAllPairedDevices()
        protector = AndroidPairKeyProtector(
            CryptoManager(app.container.settingsRepository).apply { injectSoftwareDek(SyncCrypto.randomBytes(32)) }
        )
        ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        transport = LanSocketTransport(
            context = app,
            localDeviceId = "phone-chunks",
            localDeviceName = "Pixel Chunks",
            pairedDeviceRepository = repository,
            scope = ioScope,
            pairKeyProtector = protector,
            listenPort = port
        )
        discovery = LanDiscoveryManager(
            context = app,
            deviceId = "phone-chunks",
            deviceName = "Pixel Chunks",
            scope = CoroutineScope(Job().apply { cancel() })
        )
        pairKey = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
    }

    @After
    fun tearDown() {
        transport.stopServer()
        discovery.stop()
        ioScope.cancel()
    }

    private fun desktopRecords(count: Int) = (1..count).map {
        SyncRecord(
            syncId = "desktop-$it",
            title = "Desktop entry $it",
            username = "user$it",
            password = "secret-$it-" + "x".repeat(200),
            customFields = listOf(CustomFieldRecord("PIN", "$it")),
            updatedAt = 1_000L + it
        )
    }

    @Test
    fun multiplePartsAreAssembled_andThePhoneApprovesAndAppliesTheChunkedPlan() = runBlocking {
        val repo = app.container.vaultRepository
        repo.insertEntry(VaultEntry(syncId = "phone-kept", title = "Phone entry", customFields = listOf(CustomField("Q", "A"))))
        repo.insertEntry(VaultEntry(syncId = "phone-gone", title = "Removed"))
        repo.permanentlyDeleteEntry(repo.decryptedEntries.value.first { it.syncId == "phone-gone" }.id)

        val viewModel = newViewModel()
        val remote = desktopRecords(40)
        assertTrue("Test needs several parts", SyncRecords.encodeChunks(remote, maxBytes = 3_000).size >= 3)

        startSession(viewModel).use { desktop ->
            val fromPhone = desktop.collectInBackground()
            desktop.sendRecords(remote, maxBytes = 3_000)
            // The phone doesn't review: it waits for the desktop's plan.
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })
            assertNull(viewModel.diffResult.value)

            // Our side went out exactly once, as ordered parts, including the tombstone.
            val phoneParts = fromPhone.filterIsInstance<SyncFrame.PayloadBatch>()
            assertEquals(phoneParts.indices.toList(), phoneParts.map { it.part })
            assertEquals(listOf(true), phoneParts.filter { it.last }.map { it.last })
            val phoneRecords = phoneParts.flatMap { SyncRecords.decodeChunk(it.encryptedBatchJson) }
            assertEquals(setOf("phone-kept", "phone-gone"), phoneRecords.map { it.syncId }.toSet())
            assertTrue(phoneRecords.first { it.syncId == "phone-gone" }.deleted)
            assertEquals(listOf(CustomFieldRecord("Q", "A")), phoneRecords.first { it.syncId == "phone-kept" }.customFields)

            val items = remote.map { PlanItem(it.syncId, PlanAction.UPSERT, it) }
            val expected = MergePlans.vaultFingerprint(SyncMergeExecutor.projectRecords(phoneRecords, items))
            assertTrue("Test needs several plan parts", MergePlans.encodeChunks(items, 3_000).size >= 3)
            desktop.sendPlan(items, expected, maxBytes = 3_000)

            assertTrue(waitUntil { viewModel.pendingPlanApproval.value != null })
            assertEquals(40, viewModel.pendingPlanApproval.value!!.summary.added)
            assertEquals(0, viewModel.pendingPlanApproval.value!!.summary.deleted)

            viewModel.approvePlan()
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.SyncFinished } })
            val finished = fromPhone.filterIsInstance<SyncFrame.SyncFinished>().first()
            assertEquals("", finished.code)
            assertEquals("The phone proves what it now holds", expected, finished.fingerprint)
            assertEquals(40, finished.applied)
            assertTrue(fromPhone.any { it is SyncFrame.MergePlanDecision && it.accepted })

            desktop.send(SyncFrame.SyncFinished("", expected, 40))
            assertTrue(waitUntil { viewModel.successMessage.value != null })
            assertEquals("Devices are in sync (40 entries updated)", viewModel.successMessage.value)
            assertEquals(41, repo.decryptedEntries.value.size)
            assertTrue(repository.getPairedDevice("desktop-1")!!.lastSyncAt > 0)
            assertNull(viewModel.errorMessage.value)
        }
    }

    @Test
    fun anOutOfOrderPart_cancelsTheSync() = runBlocking {
        val viewModel = newViewModel()
        val chunks = SyncRecords.encodeChunks(desktopRecords(40), maxBytes = 3_000)

        startSession(viewModel).use { desktop ->
            val fromPhone = desktop.collectInBackground()
            desktop.send(SyncFrame.PayloadBatch(chunks[1], part = 1, last = false))
            assertTrue(waitUntil { viewModel.errorMessage.value != null })
            assertEquals(SyncError.PROTOCOL.code, viewModel.errorCode.value)
            assertTrue(viewModel.errorMessage.value!!.contains("out of order"))
            assertNull(viewModel.diffResult.value)
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.CancelSync } })
            assertEquals(
                SyncError.PROTOCOL.code,
                fromPhone.filterIsInstance<SyncFrame.CancelSync>().first().code
            )
            // The phone lets the cancel go out (a short delay) before it hangs up.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertTrue(waitUntil { !transport.isConnected })
        }
    }

    @Test
    fun anOutOfOrderPlanPart_cancelsTheSync() = runBlocking {
        val viewModel = newViewModel()
        val remote = desktopRecords(4)

        startSession(viewModel).use { desktop ->
            val fromPhone = desktop.collectInBackground()
            desktop.sendRecords(remote)
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })

            val items = remote.map { PlanItem(it.syncId, PlanAction.UPSERT, it) }
            val chunks = MergePlans.encodeChunks(items, 200)
            assertTrue(chunks.size >= 2)
            desktop.send(SyncFrame.MergePlanBatch(chunks[1], part = 1, last = false))

            assertTrue(waitUntil { viewModel.errorMessage.value != null })
            assertEquals(SyncError.PROTOCOL.code, viewModel.errorCode.value)
            assertTrue(viewModel.errorMessage.value!!.contains("out of order"))
            assertTrue("Nothing was applied", app.container.vaultRepository.decryptedEntries.value.isEmpty())
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.CancelSync } })
        }
    }

    @Test
    fun aPlanThePhoneValidatesAsBroken_isRefused() = runBlocking {
        val viewModel = newViewModel()
        val remote = desktopRecords(2)

        startSession(viewModel).use { desktop ->
            val fromPhone = desktop.collectInBackground()
            desktop.sendRecords(remote)
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })

            // The record inside the item claims another syncId: the plan contradicts itself.
            desktop.sendPlan(listOf(PlanItem("desktop-1", PlanAction.UPSERT, remote[1])), "whatever")

            assertTrue(waitUntil { viewModel.errorMessage.value != null })
            assertEquals(SyncError.PROTOCOL.code, viewModel.errorCode.value)
            assertNull(viewModel.pendingPlanApproval.value)
            assertTrue("Nothing was applied", app.container.vaultRepository.decryptedEntries.value.isEmpty())
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.CancelSync } })
            assertEquals(0L, repository.getPairedDevice("desktop-1")!!.lastSyncAt)
        }
    }

    private fun newViewModel() = LanSyncViewModel(
        pairedDeviceRepository = repository,
        lanDiscoveryManager = discovery,
        lanSocketTransport = transport,
        vaultRepository = app.container.vaultRepository,
        isVaultUnlocked = unlocked
    )

    /** The desktop asks to sync and the phone's user accepts. */
    private fun startSession(viewModel: LanSyncViewModel): ScriptedDesktop {
        val desktop = ScriptedDesktop.connect(port, pairKey)
        desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
        assertTrue(waitUntil { viewModel.pendingSyncRequest.value != null })
        viewModel.acceptIncomingSync(viewModel.pendingSyncRequest.value!!)
        assertTrue(waitUntil { transport.canSendVaultData() })
        return desktop
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
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
