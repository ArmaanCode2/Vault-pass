package com.example.sync

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.VaultPassApplication
import com.example.domain.models.VaultEntry
import com.example.domain.sync.diff.SyncDiffEngine
import com.example.domain.sync.diff.SyncMergeExecutor
import com.example.domain.sync.diff.SyncRecordMapper
import com.example.domain.sync.models.SyncFrame
import com.example.network.sync.AndroidPairKeyProtector
import com.example.network.sync.LanDiscoveryManager
import com.example.network.sync.LanSocketTransport
import com.example.repository.PairedDeviceRepository
import com.example.security.CryptoManager
import com.example.security.VaultSessionManager
import com.example.ui.VaultViewModel
import com.example.ui.sync.LanSyncViewModel
import com.vaultpass.synccore.ItemOutcome
import com.vaultpass.synccore.MergePlans
import com.vaultpass.synccore.PlanAction
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.SyncChange
import com.vaultpass.synccore.SyncCrypto
import com.vaultpass.synccore.SyncError
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Sync session v3 end to end, over real sockets: the phone reviews a sync it started, approves one
 * a desktop started, and both sides only report success once their fingerprints match.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncPlanSessionTest {

    private val port = 54903

    private lateinit var app: VaultPassApplication
    private lateinit var ioScope: CoroutineScope
    private lateinit var transport: LanSocketTransport
    private lateinit var discovery: LanDiscoveryManager
    private lateinit var repository: PairedDeviceRepository
    private lateinit var protector: AndroidPairKeyProtector
    private lateinit var pairKey: ByteArray
    private val unlocked = MutableStateFlow(true)

    private val repo get() = app.container.vaultRepository

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
            localDeviceId = "phone-plan",
            localDeviceName = "Pixel Plan",
            pairedDeviceRepository = repository,
            scope = ioScope,
            pairKeyProtector = protector,
            listenPort = port
        )
        discovery = LanDiscoveryManager(
            context = app,
            deviceId = "phone-plan",
            deviceName = "Pixel Plan",
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

    // --- The phone approves a sync the desktop started ---

    @Test
    fun anEmptyPlan_finishesAndRecordsTheSyncTime() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-1", title = "Shared", password = "p"))
        val viewModel = newViewModel()

        startSession(viewModel).use { desktop ->
            val fromPhone = desktop.collectInBackground()
            desktop.sendRecords(phoneRecords())
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })

            val fingerprint = MergePlans.vaultFingerprint(phoneRecords())
            desktop.sendPlan(emptyList(), fingerprint)

            // Nothing to decide, so the phone never asks its user.
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.SyncFinished } })
            assertNull(viewModel.pendingPlanApproval.value)
            val finished = fromPhone.filterIsInstance<SyncFrame.SyncFinished>().first()
            assertEquals(fingerprint, finished.fingerprint)
            assertEquals(0, finished.applied)

            desktop.send(SyncFrame.SyncFinished("", fingerprint, 0))
            assertTrue(waitUntil { viewModel.successMessage.value != null })
            assertEquals("Devices are in sync (0 entries updated)", viewModel.successMessage.value)
            assertTrue(repository.getPairedDevice("desktop-1")!!.lastSyncAt > 0)
            assertEquals(1, repo.decryptedEntries.value.size)
        }
    }

    @Test
    fun aDeclinedPlan_changesNothingOnEitherDevice() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-1", title = "Mine", password = "p"))
        val viewModel = newViewModel()

        startSession(viewModel).use { desktop ->
            val fromPhone = desktop.collectInBackground()
            desktop.sendRecords(phoneRecords())
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })

            val newEntry = SyncRecord(syncId = "desk-new", title = "Desk", password = "d", updatedAt = 9_000L)
            desktop.sendPlan(listOf(PlanItem("desk-new", PlanAction.UPSERT, newEntry)), "does-not-matter")

            assertTrue(waitUntil { viewModel.pendingPlanApproval.value != null })
            val request = viewModel.pendingPlanApproval.value!!
            assertEquals(1, request.summary.added)
            assertTrue(request.sentence.contains("Work PC wants to change this device"))

            viewModel.declinePlan()
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.MergePlanDecision } })
            val decision = fromPhone.filterIsInstance<SyncFrame.MergePlanDecision>().first()
            assertFalse(decision.accepted)
            assertEquals(SyncError.PLAN_REJECTED.code, decision.code)

            assertEquals(listOf("phone-1"), repo.decryptedEntries.value.map { it.syncId })
            assertEquals(0L, repository.getPairedDevice("desktop-1")!!.lastSyncAt)
            assertNull("Declining is a choice, not a failure", viewModel.errorMessage.value)
        }
    }

    @Test
    fun differingFingerprints_reportNotIdentical_andLeaveTheLastSyncTimeAlone() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-1", title = "Mine", password = "p"))
        val viewModel = newViewModel()

        startSession(viewModel).use { desktop ->
            val fromPhone = desktop.collectInBackground()
            desktop.sendRecords(phoneRecords())
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })

            val newEntry = SyncRecord(syncId = "desk-new", title = "Desk", password = "d", updatedAt = 9_000L)
            desktop.sendPlan(listOf(PlanItem("desk-new", PlanAction.UPSERT, newEntry)), "claimed")
            assertTrue(waitUntil { viewModel.pendingPlanApproval.value != null })
            viewModel.approvePlan()
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.SyncFinished } })

            desktop.send(SyncFrame.SyncFinished("", "a-different-fingerprint", 1))
            assertTrue(waitUntil { viewModel.errorMessage.value != null })
            assertEquals(SyncError.NOT_IDENTICAL.message, viewModel.errorMessage.value)
            assertEquals(SyncError.NOT_IDENTICAL.code, viewModel.errorCode.value)
            assertEquals("Sync again is offered", "desktop-1", viewModel.retryDevice.value?.deviceId)
            assertEquals(0L, repository.getPairedDevice("desktop-1")!!.lastSyncAt)
            assertEquals(
                "Nothing is undone",
                setOf("phone-1", "desk-new"),
                repo.decryptedEntries.value.map { it.syncId }.toSet()
            )
        }
    }

    @Test
    fun aDroppedConnectionMidPlan_reportsConnectionLost() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-1", title = "Mine", password = "p"))
        val viewModel = newViewModel()

        val desktop = startSession(viewModel)
        desktop.collectInBackground()
        desktop.sendRecords(phoneRecords())
        assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })

        val items = (1..4).map {
            PlanItem("desk-$it", PlanAction.UPSERT, SyncRecord(syncId = "desk-$it", title = "D$it", updatedAt = 1_000L))
        }
        val chunks = MergePlans.encodeChunks(items, 120)
        assertTrue(chunks.size >= 2)
        desktop.send(SyncFrame.MergePlanBatch(chunks[0], part = 0, last = false))
        assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.EXCHANGING })
        desktop.close()

        assertTrue(waitUntil { viewModel.errorMessage.value != null })
        assertEquals(SyncError.CONNECTION_LOST.message, viewModel.errorMessage.value)
        assertEquals(SyncError.CONNECTION_LOST.code, viewModel.errorCode.value)
        assertEquals(listOf("phone-1"), repo.decryptedEntries.value.map { it.syncId })
    }

    @Test
    fun theWatchdogEndsASessionThePeerStoppedAnswering() = runBlocking {
        val viewModel = newViewModel()

        startSession(viewModel).use { desktop ->
            val fromPhone = desktop.collectInBackground()
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.EXCHANGING })

            // The desktop says nothing at all: the state's own limit must end the session.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SyncSessionState.EXCHANGING.timeoutMs!! + 1_000))

            assertTrue(waitUntil { viewModel.errorMessage.value != null })
            assertEquals(SyncError.TIMED_OUT.message, viewModel.errorMessage.value)
            assertEquals(SyncError.TIMED_OUT.code, viewModel.errorCode.value)
            assertEquals(SyncSessionState.FAILED, viewModel.sessionState.value)
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.CancelSync } })
            assertEquals(
                SyncError.TIMED_OUT.code,
                fromPhone.filterIsInstance<SyncFrame.CancelSync>().first().code
            )
        }
    }

    @Test
    fun aLockDuringThePayloadExchange_failsAsVaultLocked_withoutSendingRecords() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-1", title = "Mine", password = "p"))
        val viewModel = newViewModel()

        startSession(viewModel).use { desktop ->
            val fromPhone = desktop.collectInBackground()
            // The key is gone before the lock watcher runs (the unlocked flag still says true).
            repo.clearSoftwareDek()
            desktop.sendRecords(listOf(SyncRecord(syncId = "desk-1", title = "Desk", password = "d", updatedAt = 1_000L)))

            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.FAILED })
            assertEquals(SyncError.VAULT_LOCKED.code, viewModel.errorCode.value)
            assertEquals(SyncError.VAULT_LOCKED.message, viewModel.errorMessage.value)
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.CancelSync } })
            assertEquals(SyncError.VAULT_LOCKED.code, fromPhone.filterIsInstance<SyncFrame.CancelSync>().first().code)
            assertFalse("A locked vault sends no records", fromPhone.any { it is SyncFrame.PayloadBatch })
        }
    }

    @Test
    fun aLockBeforeTheApprovedPlanIsApplied_reportsVaultLocked_andChangesNothing() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-1", title = "Mine", password = "p"))
        val viewModel = newViewModel()
        val dao = app.container.appDatabase.vaultDao()

        startSession(viewModel).use { desktop ->
            val fromPhone = desktop.collectInBackground()
            desktop.sendRecords(phoneRecords())
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })

            val newEntry = SyncRecord(syncId = "desk-new", title = "Desk", password = "d", updatedAt = 9_000L)
            desktop.sendPlan(listOf(PlanItem("desk-new", PlanAction.UPSERT, newEntry)), "claimed")
            assertTrue(waitUntil { viewModel.pendingPlanApproval.value != null })

            val before = dao.getAllEntitiesIncludingDeletedSync()
            repo.clearSoftwareDek()
            viewModel.approvePlan()

            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.SyncFinished } })
            assertEquals(SyncError.VAULT_LOCKED.code, fromPhone.filterIsInstance<SyncFrame.SyncFinished>().first().code)
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.FAILED })
            assertEquals(SyncError.VAULT_LOCKED.code, viewModel.errorCode.value)
            assertEquals("Nothing was stored", before, dao.getAllEntitiesIncludingDeletedSync())
        }
    }

    @Test
    fun aFailingLastSyncWrite_afterSuccess_neitherCrashesNorUndoesTheSuccess() = runBlocking {
        val store = FlakyPreferencesStore()
        val devices = PairedDeviceRepository(store)
        val ownPort = port + 11
        val ownTransport = LanSocketTransport(
            context = app,
            localDeviceId = "phone-plan",
            localDeviceName = "Pixel Plan",
            pairedDeviceRepository = devices,
            scope = ioScope,
            pairKeyProtector = protector,
            listenPort = ownPort
        )
        val key = pairTestDesktop(devices, protector, "desktop-1", "Work PC")
        repo.insertEntry(VaultEntry(syncId = "phone-1", title = "Shared", password = "p"))
        val viewModel = LanSyncViewModel(devices, discovery, ownTransport, app.container.vaultRepository, unlocked)

        try {
            ScriptedDesktop.connect(ownPort, key).use { desktop ->
                val fromPhone = desktop.collectInBackground()
                desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
                assertTrue(waitUntil { viewModel.pendingSyncRequest.value != null })
                viewModel.acceptIncomingSync(viewModel.pendingSyncRequest.value!!)
                assertTrue(waitUntil { ownTransport.canSendVaultData() })

                desktop.sendRecords(phoneRecords())
                assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })
                val fingerprint = MergePlans.vaultFingerprint(phoneRecords())
                desktop.sendPlan(emptyList(), fingerprint)
                assertTrue(waitUntil { fromPhone.any { it is SyncFrame.SyncFinished } })

                // Recording the sync time fails once both devices proved they match.
                store.failWrites = true
                desktop.send(SyncFrame.SyncFinished("", fingerprint, 0))

                assertTrue(waitUntil { viewModel.successMessage.value != null })
                assertEquals(SyncSessionState.DONE, viewModel.sessionState.value)
                assertNull(viewModel.errorMessage.value)
                // Give a late failure every chance to show up.
                waitUntil(500) { false }
                assertEquals(SyncSessionState.DONE, viewModel.sessionState.value)
                assertNull(viewModel.errorMessage.value)
                assertFalse("No cancel after success", fromPhone.any { it is SyncFrame.CancelSync })
            }
        } finally {
            ownTransport.stopServer()
        }
    }

    // --- The phone reviews a sync it started ---

    @Test
    fun thePhoneAsReviewer_sendsThePlan_andAppliesItOnlyAfterTheDesktopAccepts() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-only", title = "Phone", password = "p", timestamp = 1_000L))
        val deskRecord = SyncRecord(syncId = "desk-only", title = "Desk", password = "d", updatedAt = 2_000L)

        ServerSocket(0).use { fakeDesktop ->
            val key = pairTestDesktop(repository, protector, "desktop-2", "Studio", "127.0.0.1", fakeDesktop.localPort)
            val viewModel = newViewModel()
            val device = repository.getPairedDevice("desktop-2")!!

            val accepted = AtomicReference<ScriptedDesktop?>(null)
            kotlin.concurrent.thread(isDaemon = true) { accepted.set(ScriptedDesktop.accept(fakeDesktop, pairKey = key)) }
            viewModel.initiateSyncWithDevice(device)
            assertTrue(waitUntil { accepted.get() != null })

            accepted.get()!!.use { desktop ->
                val fromPhone = desktop.collectInBackground()
                assertTrue(waitUntil { fromPhone.any { it is SyncFrame.SyncRequest } })
                desktop.send(SyncFrame.SyncAcceptance("desktop-2", true))
                assertTrue(waitUntil { fromPhone.any { it is SyncFrame.PayloadBatch } })
                desktop.sendRecords(listOf(deskRecord))

                // This device started the sync, so this device reviews it.
                assertTrue(waitUntil { viewModel.diffResult.value != null })
                val diff = viewModel.diffResult.value!!
                assertEquals(2, diff.diffItems.size)
                assertEquals(2, diff.plannedChanges)
                assertFalse(diff.hasUnansweredConflicts)
                assertEquals(
                    ItemOutcome.USE_REMOTE,
                    diff.diffItems.first { it.syncKey == "desk-only" }.outcome
                )
                assertEquals(
                    ItemOutcome.USE_LOCAL,
                    diff.diffItems.first { it.syncKey == "phone-only" }.outcome
                )

                viewModel.applyReviewedPlan()
                assertTrue(waitUntil { fromPhone.any { it is SyncFrame.MergePlanBatch && it.last } })
                assertNull("The review is over once the plan is out", viewModel.diffResult.value)
                assertEquals(
                    "Not applied before the other device accepts",
                    listOf("phone-only"),
                    repo.decryptedEntries.value.map { it.syncId }
                )

                val planFrames = fromPhone.filterIsInstance<SyncFrame.MergePlanBatch>()
                val plan = planFrames.flatMap { MergePlans.decodeChunk(it.planJson) }
                MergePlans.validate(plan)
                assertEquals(setOf("phone-only", "desk-only"), plan.map { it.syncId }.toSet())
                val promised = planFrames.last { it.last }.expectedFingerprint
                assertTrue(promised.isNotEmpty())

                desktop.send(SyncFrame.MergePlanDecision(accepted = true))
                assertTrue(waitUntil { fromPhone.any { it is SyncFrame.SyncFinished } })
                val finished = fromPhone.filterIsInstance<SyncFrame.SyncFinished>().first()
                assertEquals("The promise matches what it really holds", promised, finished.fingerprint)
                assertEquals(2, finished.applied)
                assertEquals(setOf("phone-only", "desk-only"), repo.decryptedEntries.value.map { it.syncId }.toSet())

                desktop.send(SyncFrame.SyncFinished("", promised, 2))
                assertTrue(waitUntil { viewModel.successMessage.value != null })
                assertEquals("Devices are in sync (2 entries updated)", viewModel.successMessage.value)
                assertTrue(repository.getPairedDevice("desktop-2")!!.lastSyncAt > 0)
                assertNull(viewModel.errorMessage.value)
            }
        }
    }

    @Test
    fun aMergeFailureOnTheDesktop_reachesTheUser_andLeavesTheLastSyncTimeAlone() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-1", title = "Mine", password = "p"))
        val viewModel = newViewModel()

        startSession(viewModel).use { desktop ->
            val fromPhone = desktop.collectInBackground()
            desktop.sendRecords(phoneRecords())
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })
            assertNull("The approver never reviews", viewModel.diffResult.value)

            val newEntry = SyncRecord(syncId = "desk-new", title = "Desk", password = "d", updatedAt = 9_000L)
            desktop.sendPlan(listOf(PlanItem("desk-new", PlanAction.UPSERT, newEntry)), "claimed")
            assertTrue(waitUntil { viewModel.pendingPlanApproval.value != null })
            viewModel.approvePlan()
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.SyncFinished } })

            desktop.send(SyncFrame.SyncFinished(code = SyncError.MERGE_FAILED.code))
            assertTrue(waitUntil { viewModel.errorMessage.value != null })
            assertEquals(SyncError.MERGE_FAILED.message, viewModel.errorMessage.value)
            assertEquals(SyncError.MERGE_FAILED.code, viewModel.errorCode.value)
            assertNull(viewModel.successMessage.value)
            assertEquals(0L, repository.getPairedDevice("desktop-1")!!.lastSyncAt)
        }
    }

    @Test
    fun thePhoneAsReviewer_aDeclinedPlanChangesNothing_andSaysWhy() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-only", title = "Phone", password = "p", timestamp = 1_000L))
        val deskRecord = SyncRecord(syncId = "desk-only", title = "Desk", password = "d", updatedAt = 2_000L)

        asReviewer(listOf(deskRecord)) { viewModel, desktop, fromPhone ->
            viewModel.applyReviewedPlan()
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.MergePlanBatch && it.last } })

            desktop.send(SyncFrame.MergePlanDecision(accepted = false, code = SyncError.PLAN_REJECTED.code))
            assertTrue(waitUntil { viewModel.errorMessage.value != null })
            assertEquals(SyncError.PLAN_REJECTED.message, viewModel.errorMessage.value)
            assertEquals(SyncError.PLAN_REJECTED.code, viewModel.errorCode.value)
            assertEquals(listOf("phone-only"), repo.decryptedEntries.value.map { it.syncId })
            assertEquals(0L, repository.getPairedDevice("desktop-2")!!.lastSyncAt)
            assertTrue(fromPhone.none { it is SyncFrame.SyncFinished })
        }
    }

    @Test
    fun thePhoneAsReviewer_withNothingToChange_finishesWithAnEmptyPlan() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-1", title = "Shared", password = "p", timestamp = 1_000L))

        asReviewer(phoneRecords()) { viewModel, desktop, fromPhone ->
            val diff = viewModel.diffResult.value!!
            assertEquals("The button reads 'Devices are up to date (Finish)'", 0, diff.plannedChanges)

            viewModel.applyReviewedPlan()
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.MergePlanBatch && it.last } })
            val plan = fromPhone.filterIsInstance<SyncFrame.MergePlanBatch>().flatMap { MergePlans.decodeChunk(it.planJson) }
            assertTrue(plan.isEmpty())

            desktop.send(SyncFrame.MergePlanDecision(accepted = true))
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.SyncFinished } })
            val finished = fromPhone.filterIsInstance<SyncFrame.SyncFinished>().first()
            assertEquals(0, finished.applied)

            desktop.send(SyncFrame.SyncFinished("", finished.fingerprint, 0))
            assertTrue(waitUntil { viewModel.successMessage.value != null })
            assertEquals("Devices are in sync (0 entries updated)", viewModel.successMessage.value)
            assertTrue(repository.getPairedDevice("desktop-2")!!.lastSyncAt > 0)
        }
    }

    @Test
    fun aConflict_blocksTheMerge_untilTheUserChooses() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "shared", title = "Bank", password = "phone-pw", timestamp = 6_000L))
        val deskVersion = SyncRecord(syncId = "shared", title = "Bank", password = "desk-pw", updatedAt = 7_000L)

        asReviewer(listOf(deskVersion), lastSyncAt = 5_000L) { viewModel, _, fromPhone ->
            val item = viewModel.diffResult.value!!.diffItems.single()
            assertEquals(SyncChange.CONFLICT, item.change)
            assertTrue(viewModel.diffResult.value!!.hasUnansweredConflicts)

            viewModel.applyReviewedPlan()
            assertFalse("No plan while a conflict is unanswered", waitUntil(500) { fromPhone.any { it is SyncFrame.MergePlanBatch } })
            assertNotNull("The review stays open", viewModel.diffResult.value)

            viewModel.updateItemOutcome("shared", ItemOutcome.USE_LOCAL)
            assertFalse(viewModel.diffResult.value!!.hasUnansweredConflicts)
            viewModel.applyReviewedPlan()
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.MergePlanBatch && it.last } })
            val plan = fromPhone.filterIsInstance<SyncFrame.MergePlanBatch>().flatMap { MergePlans.decodeChunk(it.planJson) }
            assertEquals("phone-pw", plan.single().record!!.password)
        }
    }

    @Test
    fun theReviewersWatchdog_endsASessionTheDesktopNeverAnswers() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-only", title = "Phone", password = "p", timestamp = 1_000L))

        asReviewer(emptyList()) { viewModel, _, fromPhone ->
            viewModel.applyReviewedPlan()
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.MergePlanBatch && it.last } })
            assertEquals(SyncSessionState.AWAITING_PLAN_APPROVAL, viewModel.sessionState.value)

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SyncSessionState.AWAITING_PLAN_APPROVAL.timeoutMs!! + 1_000))

            assertTrue(waitUntil { viewModel.errorMessage.value != null })
            assertEquals(SyncError.TIMED_OUT.message, viewModel.errorMessage.value)
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.CancelSync } })
            assertEquals(SyncError.TIMED_OUT.code, fromPhone.filterIsInstance<SyncFrame.CancelSync>().first().code)
            assertEquals("Nothing applied without an answer", listOf("phone-only"), repo.decryptedEntries.value.map { it.syncId })
        }
    }

    @Test
    fun editingAnEntryLeftUnchanged_putsTheEditIntoThePlan() {
        val viewModel = newViewModel()
        val local = SyncRecord(syncId = "id-1", title = "Mine", password = "p", updatedAt = 1_000L)
        viewModel.setDiffResultForTesting(SyncDiffEngine.computeDiff(listOf(local), emptyList(), lastSyncAt = 0L))

        viewModel.updateItemOutcome("id-1", ItemOutcome.SKIP)
        viewModel.updateEntryFieldOverride("id-1", VaultEntry(syncId = "id-1", title = "Edited", password = "p"))

        val item = viewModel.diffResult.value!!.diffItems.single()
        assertEquals(ItemOutcome.USE_LOCAL, item.outcome)
        val planned = MergePlans.build(listOf(item.toPlanInput()), now = 9_000L).single()
        assertEquals("Edited", planned.record!!.title)
        assertEquals(9_000L, planned.record!!.updatedAt)
    }

    @Test
    fun aLongReview_isKeptAliveWellPastTheIdleLimit_andThenCompletes() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-1", title = "Shared", password = "p"))
        val quickPort = port + 1
        val quick = LanSocketTransport(
            context = app,
            localDeviceId = "phone-plan",
            localDeviceName = "Pixel Plan",
            pairedDeviceRepository = repository,
            scope = ioScope,
            pairKeyProtector = protector,
            listenPort = quickPort,
            idleReadTimeoutMs = 1_500,
            keepAliveMs = 300L
        )
        // Everything the view model is handed goes through this flow.
        val handedToTheApp = java.util.concurrent.CopyOnWriteArrayList<SyncFrame>()
        ioScope.launch { quick.receiveFrames().collect { handedToTheApp.add(it) } }
        val viewModel = newViewModel(quick)
        try {
            ScriptedDesktop.connect(quickPort, pairKey).use { desktop ->
                val fromPhone = desktop.collectInBackground()
                desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
                assertTrue(waitUntil { viewModel.pendingSyncRequest.value != null })
                viewModel.acceptIncomingSync(viewModel.pendingSyncRequest.value!!)
                assertTrue(waitUntil { quick.canSendVaultData() })
                desktop.keepAlive(300L)
                desktop.sendRecords(phoneRecords())
                assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })

                // The desktop's user takes three times the idle limit to review.
                waitUntil(4_500) { false }
                assertTrue("Still connected after a long review", quick.isConnected)
                assertEquals(SyncSessionState.REVIEWING, viewModel.sessionState.value)
                assertNull(viewModel.errorMessage.value)
                assertTrue(fromPhone.count { it is SyncFrame.KeepAlive } >= 5)

                val fingerprint = MergePlans.vaultFingerprint(phoneRecords())
                desktop.sendPlan(emptyList(), fingerprint)
                assertTrue(waitUntil { fromPhone.any { it is SyncFrame.SyncFinished } })
                desktop.send(SyncFrame.SyncFinished("", fingerprint, 0))
                assertTrue(waitUntil { viewModel.successMessage.value != null })
                assertNull(viewModel.errorMessage.value)
            }
            assertTrue("KeepAlive never reaches the view model", handedToTheApp.none { it is SyncFrame.KeepAlive })
            assertTrue(handedToTheApp.any { it is SyncFrame.SyncFinished })
        } finally {
            quick.stopServer()
        }
    }

    // --- Helpers ---

    private suspend fun phoneRecords(): List<SyncRecord> =
        SyncRecordMapper.localState(repo.getSyncSnapshot()).records

    /** The phone starts a sync with a scripted desktop holding [deskRecords] and gets to its review. */
    private suspend fun asReviewer(
        deskRecords: List<SyncRecord>,
        lastSyncAt: Long = 0L,
        block: suspend (LanSyncViewModel, ScriptedDesktop, List<SyncFrame>) -> Unit
    ) {
        ServerSocket(0).use { fakeDesktop ->
            val key = pairTestDesktop(repository, protector, "desktop-2", "Studio", "127.0.0.1", fakeDesktop.localPort)
            if (lastSyncAt > 0) repository.updateLastSync("desktop-2", lastSyncAt)
            val viewModel = newViewModel()

            val accepted = AtomicReference<ScriptedDesktop?>(null)
            kotlin.concurrent.thread(isDaemon = true) { accepted.set(ScriptedDesktop.accept(fakeDesktop, pairKey = key)) }
            viewModel.initiateSyncWithDevice(repository.getPairedDevice("desktop-2")!!)
            assertTrue(waitUntil { accepted.get() != null })

            accepted.get()!!.use { desktop ->
                val fromPhone = desktop.collectInBackground()
                assertTrue(waitUntil { fromPhone.any { it is SyncFrame.SyncRequest } })
                desktop.send(SyncFrame.SyncAcceptance("desktop-2", true))
                assertTrue(waitUntil { fromPhone.any { it is SyncFrame.PayloadBatch } })
                desktop.sendRecords(deskRecords)
                assertTrue(waitUntil { viewModel.diffResult.value != null })
                block(viewModel, desktop, fromPhone)
            }
        }
    }

    private fun newViewModel(onTransport: LanSocketTransport = transport) = LanSyncViewModel(
        pairedDeviceRepository = repository,
        lanDiscoveryManager = discovery,
        lanSocketTransport = onTransport,
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

    /**
     * Paired devices kept in memory. [failWrites] makes every write throw, like a full disk; a
     * [gate] holds every write until it completes (and [writeWaiting] says one is held).
     */
    private class FlakyPreferencesStore : DataStore<Preferences> {
        @Volatile
        var failWrites = false
        @Volatile
        var gate: CompletableDeferred<Unit>? = null
        @Volatile
        var writeWaiting = false
        private val state = MutableStateFlow(emptyPreferences())
        private val writeLock = Mutex()

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            gate?.let {
                writeWaiting = true
                it.await()
            }
            return writeLock.withLock {
                if (failWrites) throw IOException("No space left on device")
                transform(state.value).also { state.value = it }
            }
        }
    }

    /** A transport with its own in-memory paired-device store, so a test can hold or break its writes. */
    private inner class OwnSetup(val ownPort: Int) {
        val store = FlakyPreferencesStore()
        val devices = PairedDeviceRepository(store)
        val transport = LanSocketTransport(
            context = app,
            localDeviceId = "phone-plan",
            localDeviceName = "Pixel Plan",
            pairedDeviceRepository = devices,
            scope = ioScope,
            pairKeyProtector = protector,
            listenPort = ownPort
        )
        lateinit var key: ByteArray
        lateinit var viewModel: LanSyncViewModel

        suspend fun start(): OwnSetup {
            key = pairTestDesktop(devices, protector, "desktop-1", "Work PC")
            viewModel = LanSyncViewModel(devices, discovery, transport, app.container.vaultRepository, unlocked)
            return this
        }

        /** The desktop asks to sync and the phone's user accepts. */
        fun startSession(): ScriptedDesktop {
            val desktop = ScriptedDesktop.connect(ownPort, key)
            desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
            assertTrue(waitUntil { viewModel.pendingSyncRequest.value != null })
            viewModel.acceptIncomingSync(viewModel.pendingSyncRequest.value!!)
            assertTrue(waitUntil { transport.canSendVaultData() })
            return desktop
        }

        /**
         * Runs a session up to the phone saving the sync time, held by the [gate]: the session is
         * DONE and the connection still open. The phone approves a one-entry plan the desktop sent.
         */
        suspend fun runToTheHeldLastSyncWrite(desktop: ScriptedDesktop): List<SyncFrame> {
            val fromPhone = desktop.collectInBackground()
            val phone = phoneRecords()
            desktop.sendRecords(phone)
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })
            val items = listOf(PlanItem("desk-new", PlanAction.UPSERT, SyncRecord(syncId = "desk-new", title = "Desk", password = "d", updatedAt = 9_000L)))
            val expected = MergePlans.vaultFingerprint(SyncMergeExecutor.projectRecords(phone, items))
            desktop.sendPlan(items, expected)
            assertTrue(waitUntil { viewModel.pendingPlanApproval.value != null })
            // The desktop is done first, so the phone confirms from its own apply, not from a frame.
            desktop.send(SyncFrame.SyncFinished("", expected, 1))
            waitUntil(500) { false }

            store.gate = CompletableDeferred()
            viewModel.approvePlan()
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.DONE && store.writeWaiting })
            return fromPhone
        }
    }

    @Test
    fun aLateFailureAfterSuccess_neitherTurnsItIntoAFailureNorCancelsIt() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-1", title = "Mine", password = "p"))
        val setup = OwnSetup(port + 12).start()
        try {
            setup.startSession().use { desktop ->
                val fromPhone = setup.runToTheHeldLastSyncWrite(desktop)

                // A late frame the phone can't use (records out of order) after both devices proved
                // they match: handling it ends in failSync, which must leave the success alone.
                desktop.sendRecords(listOf(SyncRecord(syncId = "late-1", title = "Late", updatedAt = 1L)))
                waitUntil(500) { false }
                assertEquals(SyncSessionState.DONE, setup.viewModel.sessionState.value)
                assertNull(setup.viewModel.errorMessage.value)
                assertFalse("No cancel after success", fromPhone.any { it is SyncFrame.CancelSync })

                setup.store.gate!!.complete(Unit)
                assertTrue(waitUntil { setup.viewModel.successMessage.value != null })
                assertEquals(SyncSessionState.DONE, setup.viewModel.sessionState.value)
                assertNull(setup.viewModel.errorMessage.value)
            }
        } finally {
            setup.transport.stopServer()
        }
    }

    @Test
    fun aNewSyncStartedWhileTheLastOneSavesItsTime_isNotCutOff() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-1", title = "Mine", password = "p"))
        val setup = OwnSetup(port + 13).start()
        try {
            setup.startSession().use { first ->
                setup.runToTheHeldLastSyncWrite(first)
            }
            assertTrue(waitUntil { !setup.transport.isConnected })

            // The desktop syncs again right away, and the phone's user accepts.
            setup.startSession().use {
                assertTrue(setup.transport.canSendVaultData())
                setup.store.gate!!.complete(Unit)
                waitUntil(800) { false }

                assertTrue("The new session is still connected", setup.transport.canSendVaultData())
                assertEquals(SyncSessionState.EXCHANGING, setup.viewModel.sessionState.value)
                assertNull("The old success doesn't overwrite the new session", setup.viewModel.successMessage.value)
            }
        } finally {
            setup.transport.stopServer()
        }
    }

    @Test
    fun aNewSessionAfterASuccess_doesNotStartOutDone() = runBlocking {
        repo.insertEntry(VaultEntry(syncId = "phone-1", title = "Shared", password = "p"))
        val viewModel = newViewModel()
        startSession(viewModel).use { desktop ->
            val fromPhone = desktop.collectInBackground()
            desktop.sendRecords(phoneRecords())
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })
            val fingerprint = MergePlans.vaultFingerprint(phoneRecords())
            desktop.sendPlan(emptyList(), fingerprint)
            assertTrue(waitUntil { fromPhone.any { it is SyncFrame.SyncFinished } })
            desktop.send(SyncFrame.SyncFinished("", fingerprint, 0))
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.DONE })
        }

        // A sync to a device that is gone: it fails before reaching any session state.
        val gonePort = ServerSocket(0).use { it.localPort }
        pairTestDesktop(repository, protector, "desktop-gone", "Old PC", "127.0.0.1", gonePort)
        viewModel.initiateSyncWithDevice(repository.getPairedDevice("desktop-gone")!!)
        assertTrue(waitUntil { viewModel.errorMessage.value != null })
        assertNotEquals("The new session must not still read as the old success", SyncSessionState.DONE, viewModel.sessionState.value)
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
