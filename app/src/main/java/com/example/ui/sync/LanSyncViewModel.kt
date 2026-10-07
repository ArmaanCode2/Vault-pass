package com.example.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.models.VaultEntry
import com.example.domain.sync.diff.EntryDiffItem
import com.example.domain.sync.diff.LocalSyncState
import com.example.domain.sync.diff.SyncDiffEngine
import com.example.domain.sync.diff.SyncDiffResult
import com.example.domain.sync.diff.SyncMergeExecutor
import com.example.domain.sync.diff.SyncRecordMapper
import com.example.domain.sync.models.PairedDevice
import com.example.domain.sync.models.SyncFrame
import com.example.domain.sync.models.SyncState
import com.example.network.sync.LanDiscoveryManager
import com.example.network.sync.LanSocketTransport
import com.example.repository.PairedDeviceRepository
import com.example.repository.VaultRepository
import com.example.security.VaultLockedException
import com.vaultpass.synccore.ItemOutcome
import com.vaultpass.synccore.MergePlans
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.PlanSummary
import com.vaultpass.synccore.SyncError
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords
import com.vaultpass.synccore.SyncSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the other device's merge plan would change here, for the approval dialog. */
class PlanApprovalRequest(val peerName: String, val summary: PlanSummary) {
    val sentence: String
        get() = "$peerName wants to change this device: " +
            "${summary.added} added, ${summary.updated} updated, ${summary.deleted} deleted"
}

class LanSyncViewModel(
    private val pairedDeviceRepository: PairedDeviceRepository,
    private val lanDiscoveryManager: LanDiscoveryManager,
    private val lanSocketTransport: LanSocketTransport,
    private val vaultRepository: VaultRepository,
    private val isVaultUnlocked: StateFlow<Boolean>
) : ViewModel() {

    val pairedDevices: StateFlow<List<PairedDevice>> = combine(
        pairedDeviceRepository.observePairedDevices(),
        lanDiscoveryManager.discoveredDevices
    ) { pairedList, presenceMap ->
        pairedList.map { device ->
            val presence = presenceMap[device.deviceId]
            val isOnline = presence != null && lanDiscoveryManager.isDeviceOnline(device.deviceId)
            val liveIp = presence?.ipAddress ?: device.ipAddress
            val livePort = presence?.port ?: device.port
            device.copy(isOnline = isOnline, ipAddress = liveIp, port = livePort)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncState: StateFlow<SyncState> = lanSocketTransport.syncState
    val pendingSyncRequest: StateFlow<SyncFrame.SyncRequest?> = lanSocketTransport.pendingSyncRequest

    private val _sessionState = MutableStateFlow(SyncSessionState.IDLE)
    val sessionState: StateFlow<SyncSessionState> = _sessionState.asStateFlow()

    private val _diffResult = MutableStateFlow<SyncDiffResult?>(null)
    val diffResult: StateFlow<SyncDiffResult?> = _diffResult.asStateFlow()

    private val _pendingPlanApproval = MutableStateFlow<PlanApprovalRequest?>(null)
    val pendingPlanApproval: StateFlow<PlanApprovalRequest?> = _pendingPlanApproval.asStateFlow()

    private val _activeSyncDevice = MutableStateFlow<PairedDevice?>(null)
    val activeSyncDevice: StateFlow<PairedDevice?> = _activeSyncDevice.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatusMessage = MutableStateFlow<String?>(null)
    val syncStatusMessage: StateFlow<String?> = _syncStatusMessage.asStateFlow()

    /** Shown once both devices proved they hold the same entries. */
    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** The SyncError code behind [errorMessage], for the technical details. */
    private val _errorCode = MutableStateFlow<String?>(null)
    val errorCode: StateFlow<String?> = _errorCode.asStateFlow()

    /** Set when the failure is worth another try right away (the vaults still differ). */
    private val _retryDevice = MutableStateFlow<PairedDevice?>(null)
    val retryDevice: StateFlow<PairedDevice?> = _retryDevice.asStateFlow()

    /** One watchdog for the whole session; every state carries its own limit. */
    private var watchdogJob: Job? = null

    // The connect-and-handshake of a sync this phone starts. Cancelled when the user cancels, so
    // a late result can't overwrite their cancel.
    private var outgoingSyncJob: Job? = null

    // Whether this device already sent its records in the current sync session.
    private var localPayloadSent = false

    // The current sync session: who the peer is, what we sent, and the peer's records so far.
    private var activePeerId: String? = null
    private var sessionLocalState: LocalSyncState? = null
    private val receivedRecords = mutableListOf<SyncRecord>()
    private var receivedParts = 0
    private var receiveFailed = false

    // True when this device started the sync and therefore reviews it and builds the plan.
    private var isReviewer = false

    // The plan: built here (reviewer) or assembled from the peer's chunks (approver).
    private var planItems: List<PlanItem> = emptyList()
    private var planParts = 0
    private var planTotal = 0
    private var planApplied = false
    private var ownFingerprint: String? = null
    private var peerFinished: SyncFrame.SyncFinished? = null

    // Moves on with every new session, so work that suspended in one session can tell another started.
    private var sessionNumber = 0L

    private val frameCollectors = mutableListOf<Job>()

    // Set when the vault locks. Locking replaces the whole navigation graph, which can leave
    // this ViewModel orphaned instead of cleared, so it shuts itself down.
    private var retired = false

    init {
        // The listener and beacons run only while this screen is open and the vault unlocked.
        if (isVaultUnlocked.value) {
            startNetworking()
        }

        frameCollectors += viewModelScope.launch {
            lanSocketTransport.receiveFrames().collect { frame ->
                try {
                    handleIncomingSyncFrame(frame)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Safety net: a frame this device failed to handle ends the session, never the app.
                    failSync(if (e is VaultLockedException) SyncError.VAULT_LOCKED else SyncError.UNKNOWN)
                }
            }
        }

        frameCollectors += viewModelScope.launch {
            lanSocketTransport.syncState.collect { state ->
                // The transport hung up while a session was still running: say why.
                if ((state == SyncState.IDLE || state == SyncState.ERROR) && sessionIsRunning()) {
                    reportFailure(lanSocketTransport.lastFailure() ?: SyncError.CONNECTION_LOST)
                }
            }
        }

        viewModelScope.launch {
            isVaultUnlocked.first { unlocked -> !unlocked }
            if (sessionIsRunning()) {
                // Best effort: the peer learns why, instead of only seeing the connection drop.
                withTimeoutOrNull(1_000L) {
                    lanSocketTransport.sendFrame(SyncFrame.CancelSync(SyncError.VAULT_LOCKED.message, SyncError.VAULT_LOCKED.code))
                }
            }
            retire()
        }
    }

    private fun startNetworking() {
        // Pairings from sync protocol v1 sent their key in plaintext; they must be redone.
        viewModelScope.launch {
            try {
                pairedDeviceRepository.purgeLegacyPairings()
            } catch (_: Exception) {}
        }
        lanDiscoveryManager.start()
        lanSocketTransport.startServer()
        // Syncs started here still work, but no other device can reach this one: say so.
        lanSocketTransport.listenerFailure()?.let { details ->
            _errorMessage.value = "Other devices can't start a sync with this device. $details"
            _errorCode.value = null
        }
    }

    private fun stopNetworking() {
        lanSocketTransport.stopServer()
        lanDiscoveryManager.stop()
    }

    private fun retire() {
        if (retired) return
        retired = true
        frameCollectors.forEach { it.cancel() }
        frameCollectors.clear()
        watchdogJob?.cancel()
        outgoingSyncJob?.cancel()
        _isSyncing.value = false
        _syncStatusMessage.value = null
        _diffResult.value = null
        _pendingPlanApproval.value = null
        _activeSyncDevice.value = null
        _sessionState.value = SyncSessionState.IDLE
        resetSession(null)
        stopNetworking()
    }

    private fun resetSession(peerId: String?) {
        sessionNumber++
        // A new session never starts out "done": failures before its first state must still show.
        if (_sessionState.value == SyncSessionState.DONE) _sessionState.value = SyncSessionState.IDLE
        activePeerId = peerId
        localPayloadSent = false
        sessionLocalState = null
        receivedRecords.clear()
        receivedParts = 0
        receiveFailed = false
        isReviewer = false
        planItems = emptyList()
        planParts = 0
        planTotal = 0
        planApplied = false
        ownFingerprint = null
        peerFinished = null
    }

    override fun onCleared() {
        if (!retired) {
            stopNetworking()
        }
        super.onCleared()
    }

    // --- Session state and its watchdog ---

    private fun sessionIsRunning(): Boolean = when (_sessionState.value) {
        SyncSessionState.IDLE, SyncSessionState.DONE, SyncSessionState.FAILED -> false
        else -> true
    }

    /** Moves the session on and restarts the single watchdog with the new state's limit. */
    private fun setSessionState(state: SyncSessionState) {
        _sessionState.value = state
        watchdogJob?.cancel()
        val limit = state.timeoutMs ?: return
        watchdogJob = viewModelScope.launch {
            delay(limit)
            if (_sessionState.value == state) {
                // Forget the job first: ending the session must not cancel this coroutine.
                watchdogJob = null
                failSync(SyncError.TIMED_OUT)
            }
        }
    }

    // --- Devices ---

    fun refreshDevices() {
        lanDiscoveryManager.refreshNow()
    }

    fun unpairDevice(deviceId: String) {
        viewModelScope.launch {
            pairedDeviceRepository.unpairDevice(deviceId)
        }
    }

    fun clearError() {
        _errorMessage.value = null
        _errorCode.value = null
        _retryDevice.value = null
    }

    fun clearSuccess() {
        _successMessage.value = null
    }

    fun getLocalIp(): String? = lanDiscoveryManager.getLocalIpAddress()

    fun initiateSyncWithDevice(device: PairedDevice) {
        val ip = device.ipAddress
        if (ip.isNullOrBlank()) {
            _errorMessage.value = "Device IP address unknown. Make sure device is online."
            _errorCode.value = SyncError.CONNECTION_LOST.code
            return
        }
        outgoingSyncJob?.cancel()
        outgoingSyncJob = viewModelScope.launch {
            clearError()
            _successMessage.value = null
            _isSyncing.value = true
            _syncStatusMessage.value = "Connecting to ${device.deviceName}..."
            _activeSyncDevice.value = device
            // Reset before the request goes out: the reply can arrive right after it.
            resetSession(device.deviceId)
            isReviewer = true

            when (lanSocketTransport.startOutgoingSync(device)) {
                LanSocketTransport.OutgoingSyncResult.STARTED -> {
                    _syncStatusMessage.value = "Waiting for ${device.deviceName} to accept sync..."
                    setSessionState(SyncSessionState.AWAITING_APPROVAL)
                }
                LanSocketTransport.OutgoingSyncResult.NOT_PAIRED -> {
                    _isSyncing.value = false
                    _errorMessage.value = "${device.deviceName} is no longer paired with this device. Pair it again."
                    _errorCode.value = SyncError.PROTOCOL.code
                }
                LanSocketTransport.OutgoingSyncResult.UNREACHABLE -> {
                    _isSyncing.value = false
                    _errorMessage.value = "Could not establish connection to ${device.deviceName} on port ${device.port}. " +
                        "Open Settings > Sync on the desktop and make sure both devices are on the same network."
                    _errorCode.value = SyncError.CONNECTION_LOST.code
                }
                LanSocketTransport.OutgoingSyncResult.FAILED -> {
                    _isSyncing.value = false
                    val failure = lanSocketTransport.lastFailure()
                    _errorMessage.value = if (failure == SyncError.PEER_OUTDATED) {
                        failure.message
                    } else {
                        "Could not start a secure sync with ${device.deviceName}. " +
                            "Make sure VaultPass is up to date on both devices, or pair them again."
                    }
                    _errorCode.value = (failure ?: SyncError.PROTOCOL).code
                }
            }
        }
    }

    fun acceptIncomingSync(request: SyncFrame.SyncRequest) {
        viewModelScope.launch {
            clearError()
            _successMessage.value = null
            resetSession(request.deviceId)
            _activeSyncDevice.value = pairedDevices.value.find { it.deviceId == request.deviceId }
                ?: pairedDeviceRepository.getPairedDevice(request.deviceId)
            val sent = lanSocketTransport.acceptSync()
            if (!sent) {
                _isSyncing.value = false
                _syncStatusMessage.value = null
                _errorMessage.value = "Could not reply to ${request.deviceName}. Please try again."
                _errorCode.value = SyncError.CONNECTION_LOST.code
                _sessionState.value = SyncSessionState.FAILED
                lanSocketTransport.disconnect()
                return@launch
            }
            _isSyncing.value = true
            // Our records go out in reply to the peer's (see PayloadBatch handling).
            _syncStatusMessage.value = "Waiting for ${request.deviceName} to send vault records..."
            setSessionState(SyncSessionState.EXCHANGING)
        }
    }

    fun declineIncomingSync(request: SyncFrame.SyncRequest) {
        viewModelScope.launch {
            watchdogJob?.cancel()
            _sessionState.value = SyncSessionState.IDLE
            lanSocketTransport.declineSync()
            _isSyncing.value = false
            _syncStatusMessage.value = null
        }
    }

    fun cancelActiveSync() {
        endSessionOnPurpose()
    }

    fun cancelSyncReview() {
        endSessionOnPurpose()
    }

    /** The user ended the sync: the peer is told, but this device shows no error. */
    private fun endSessionOnPurpose() {
        watchdogJob?.cancel()
        outgoingSyncJob?.cancel()
        _isSyncing.value = false
        _syncStatusMessage.value = null
        _diffResult.value = null
        _pendingPlanApproval.value = null
        _sessionState.value = SyncSessionState.IDLE
        viewModelScope.launch {
            try {
                lanSocketTransport.sendFrame(SyncFrame.CancelSync(SyncError.CANCELLED.message, SyncError.CANCELLED.code))
                delay(200L)
            } catch (_: Exception) {}
            lanSocketTransport.disconnect()
        }
    }

    // --- Sending this device's records ---

    private suspend fun localSyncState(): LocalSyncState =
        sessionLocalState ?: SyncRecordMapper.localState(vaultRepository.getSyncSnapshot()).also { sessionLocalState = it }

    /** Sends our records once per session, in as many parts as needed. */
    private suspend fun sendLocalPayloadBatch() {
        // The transport enforces this too; checking here avoids decrypting the vault for nothing.
        if (localPayloadSent || !lanSocketTransport.canSendVaultData()) return
        localPayloadSent = true
        val chunks = SyncRecords.encodeChunks(localSyncState().records)
        chunks.forEachIndexed { i, chunk ->
            if (!lanSocketTransport.sendFrame(SyncFrame.PayloadBatch(chunk, part = i, last = i == chunks.lastIndex))) return
        }
    }

    /**
     * [sendLocalPayloadBatch], ending the session if this vault can't be read (e.g. it locked
     * meanwhile). Returns false when the session was ended.
     */
    private suspend fun trySendLocalPayloadBatch(): Boolean {
        return try {
            sendLocalPayloadBatch()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultLockedException) {
            failSync(SyncError.VAULT_LOCKED)
            false
        } catch (e: Exception) {
            failSync(SyncError.VAULT_UNREADABLE, e.message)
            false
        }
    }

    /** The error for this device failing to read its own vault: locked, or unreadable. */
    private fun ownVaultError(e: Exception): SyncError =
        if (e is VaultLockedException) SyncError.VAULT_LOCKED else SyncError.VAULT_UNREADABLE

    /** The detail shown with [ownVaultError]; a lock needs none. */
    private fun ownVaultDetail(e: Exception): String? =
        if (e is VaultLockedException) null else e.message

    // --- Incoming frames ---

    private suspend fun handleIncomingSyncFrame(frame: SyncFrame) {
        when (frame) {
            // The user may have answered before this frame was delivered here.
            is SyncFrame.SyncRequest -> if (lanSocketTransport.pendingSyncRequest.value != null) {
                setSessionState(SyncSessionState.AWAITING_APPROVAL)
            }
            is SyncFrame.SyncAcceptance -> {
                if (frame.isAccepted) {
                    setSessionState(SyncSessionState.EXCHANGING)
                    _syncStatusMessage.value = "Sync accepted. Exchanging vault records..."
                    trySendLocalPayloadBatch()
                } else {
                    failSync(SyncError.DECLINED, notifyPeer = false)
                }
            }
            is SyncFrame.PayloadBatch -> {
                // As responder, reply with our records now that the peer has proven the secret.
                if (trySendLocalPayloadBatch()) {
                    receivePayloadPart(frame)
                }
            }
            is SyncFrame.MergePlanBatch -> receivePlanPart(frame)
            is SyncFrame.MergePlanDecision -> {
                if (frame.accepted) {
                    applyAgreedPlan()
                } else {
                    failSync(SyncError.fromCode(frame.code.ifEmpty { SyncError.PLAN_REJECTED.code }), notifyPeer = false)
                }
            }
            is SyncFrame.SyncFinished -> {
                if (frame.code.isNotEmpty()) {
                    failSync(SyncError.fromCode(frame.code), notifyPeer = false)
                } else {
                    peerFinished = frame
                    compareFingerprints()
                }
            }
            is SyncFrame.CancelSync -> {
                watchdogJob?.cancel()
                _isSyncing.value = false
                _syncStatusMessage.value = null
                if (_sessionState.value != SyncSessionState.DONE) {
                    val error = if (frame.code.isNotEmpty()) SyncError.fromCode(frame.code) else SyncError.CANCELLED
                    // The transport may already have reported the hang-up; the peer's reason is better.
                    reportFailure(error, if (frame.code.isEmpty()) frame.reason else null, replace = true)
                }
                lanSocketTransport.disconnect()
            }
            else -> {
                // PairingInfo/PairingAcceptance belong to pairing, which the scanner screen drives.
            }
        }
    }

    private suspend fun receivePayloadPart(frame: SyncFrame.PayloadBatch) {
        if (receiveFailed) return
        if (frame.part != receivedParts) {
            failSync(SyncError.PROTOCOL, "The vault records arrived out of order.")
            return
        }
        if (receivedParts >= MAX_PAYLOAD_PARTS) {
            failSync(SyncError.TOO_MUCH_DATA, "The other device sent too many parts.")
            return
        }
        val records = try {
            SyncRecords.decodeChunk(frame.encryptedBatchJson)
        } catch (e: Exception) {
            failSync(SyncError.PROTOCOL, "The other device's vault records could not be read.")
            return
        }
        receivedParts++
        if (receivedRecords.size + records.size > MAX_PAYLOAD_RECORDS) {
            failSync(SyncError.TOO_MUCH_DATA, "The other device sent too many entries.")
            return
        }
        receivedRecords += records
        if (!frame.last) {
            _syncStatusMessage.value = "Receiving vault records (${receivedRecords.size})..."
            setSessionState(SyncSessionState.EXCHANGING)
            return
        }
        if (isReviewer) {
            reviewRemoteRecords(receivedRecords.toList())
        } else {
            // The approver doesn't compare anything: it waits for the reviewer's plan.
            _syncStatusMessage.value = "Waiting for ${peerName()} to choose what to sync..."
            setSessionState(SyncSessionState.REVIEWING)
        }
    }

    /** The reviewer's side: compare both vaults and open the review. */
    private suspend fun reviewRemoteRecords(remoteRecords: List<SyncRecord>) {
        _syncStatusMessage.value = "Computing differences..."
        try {
            val local = localSyncState()
            val lastSyncAt = activePeerId?.let { pairedDeviceRepository.getPairedDevice(it)?.lastSyncAt } ?: 0L
            val diff = withContext(Dispatchers.Default) {
                SyncDiffEngine.computeDiff(
                    localRecords = local.records,
                    remoteRecords = remoteRecords,
                    lastSyncAt = lastSyncAt,
                    blockedIds = local.blockedIds,
                    localEntries = local.entriesBySyncId
                )
            }
            _diffResult.value = diff
            _isSyncing.value = false
            _syncStatusMessage.value = null
            setSessionState(SyncSessionState.REVIEWING)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failSync(ownVaultError(e), ownVaultDetail(e))
        }
    }

    // --- Review (reviewer side) ---

    fun updateItemOutcome(syncKey: String, outcome: ItemOutcome) {
        _diffResult.update { current ->
            if (current == null) return@update null
            current.copy(
                diffItems = current.diffItems.map {
                    if (it.syncKey == syncKey) it.copy(outcome = outcome, answered = true) else it
                }
            )
        }
    }

    fun updateEntryFieldOverride(syncKey: String, updatedEntry: VaultEntry) {
        _diffResult.update { current ->
            if (current == null) return@update null
            current.copy(
                diffItems = current.diffItems.map {
                    if (it.syncKey == syncKey) it.withEdit(updatedEntry) else it
                }
            )
        }
    }

    /**
     * An edit only reaches the plan through a version that is stored, so editing an entry that was
     * left unchanged switches it to the version the editor started from.
     */
    private fun EntryDiffItem.withEdit(entry: VaultEntry): EntryDiffItem {
        val keepsAVersion = outcome == ItemOutcome.USE_REMOTE || outcome == ItemOutcome.USE_LOCAL
        val newOutcome = when {
            keepsAVersion -> outcome
            remoteEntry != null && ItemOutcome.USE_REMOTE in choices -> ItemOutcome.USE_REMOTE
            ItemOutcome.USE_LOCAL in choices -> ItemOutcome.USE_LOCAL
            else -> outcome
        }
        // Writing the version by hand is an answer to a conflict too.
        return copy(editedEntry = entry, outcome = newOutcome, answered = true)
    }

    /**
     * Turns the review into the plan both devices apply and sends it. The plan is applied here
     * only once the other device accepts it.
     */
    fun applyReviewedPlan() {
        val diff = _diffResult.value ?: return
        if (diff.hasUnansweredConflicts) return
        launchSessionWork {
            val now = System.currentTimeMillis()
            val local = try {
                localSyncState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failSync(ownVaultError(e), ownVaultDetail(e))
                return@launchSessionWork
            }
            val items = try {
                MergePlans.build(diff.diffItems.map { it.toPlanInput() }, now).also { MergePlans.validate(it) }
            } catch (e: Exception) {
                failSync(SyncError.MERGE_FAILED, e.message)
                return@launchSessionWork
            }
            planItems = items
            planTotal = MergePlans.summarize(items, local.records.map { it.syncId }.toSet()).total
            val expected = MergePlans.vaultFingerprint(SyncMergeExecutor.projectRecords(local.records, items))

            _diffResult.value = null
            _isSyncing.value = true
            _syncStatusMessage.value = "Waiting for ${peerName()} to approve the changes..."
            setSessionState(SyncSessionState.AWAITING_PLAN_APPROVAL)

            val chunks = MergePlans.encodeChunks(items)
            chunks.forEachIndexed { i, chunk ->
                val last = i == chunks.lastIndex
                val sent = lanSocketTransport.sendFrame(
                    SyncFrame.MergePlanBatch(chunk, part = i, last = last, expectedFingerprint = if (last) expected else "")
                )
                if (!sent) {
                    failSync(SyncError.CONNECTION_LOST)
                    return@launchSessionWork
                }
            }
        }
    }

    // --- Plan approval (approver side) ---

    private suspend fun receivePlanPart(frame: SyncFrame.MergePlanBatch) {
        if (receiveFailed) return
        if (frame.part != planParts) {
            failSync(SyncError.PROTOCOL, "The merge plan arrived out of order.")
            return
        }
        if (planParts >= MAX_PAYLOAD_PARTS) {
            failSync(SyncError.TOO_MUCH_DATA, "The other device sent too many plan parts.")
            return
        }
        val part = try {
            MergePlans.decodeChunk(frame.planJson)
        } catch (e: Exception) {
            failSync(SyncError.PROTOCOL, "The merge plan could not be read.")
            return
        }
        planParts++
        if (planItems.size + part.size > MergePlans.MAX_ITEMS) {
            failSync(SyncError.TOO_MUCH_DATA, "The merge plan is too large.")
            return
        }
        planItems = planItems + part
        if (!frame.last) {
            _syncStatusMessage.value = "Receiving the merge plan (${planItems.size})..."
            setSessionState(SyncSessionState.EXCHANGING)
            return
        }

        val local = try {
            localSyncState()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failSync(ownVaultError(e), ownVaultDetail(e))
            return
        }
        val summary = try {
            MergePlans.validate(planItems)
            MergePlans.summarize(planItems, local.records.map { it.syncId }.toSet())
        } catch (e: Exception) {
            failSync(SyncError.PROTOCOL, e.message)
            return
        }
        planTotal = summary.total
        if (summary.isEmpty) {
            // Nothing to decide: the devices already agree.
            approvePlan()
        } else {
            _isSyncing.value = false
            _syncStatusMessage.value = null
            _pendingPlanApproval.value = PlanApprovalRequest(peerName(), summary)
            setSessionState(SyncSessionState.AWAITING_PLAN_APPROVAL)
        }
    }

    fun approvePlan() {
        _pendingPlanApproval.value = null
        launchSessionWork {
            _isSyncing.value = true
            if (!lanSocketTransport.sendFrame(SyncFrame.MergePlanDecision(accepted = true))) {
                failSync(SyncError.CONNECTION_LOST)
                return@launchSessionWork
            }
            applyAgreedPlan()
        }
    }

    /** Runs session work in the background; a failure it doesn't handle ends the session, never the app. */
    private fun launchSessionWork(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failSync(if (e is VaultLockedException) SyncError.VAULT_LOCKED else SyncError.UNKNOWN)
        }
    }

    fun declinePlan() {
        _pendingPlanApproval.value = null
        // Ended on purpose, before the peer hangs up: its hang-up must not read as a failure here.
        watchdogJob?.cancel()
        _isSyncing.value = false
        _syncStatusMessage.value = null
        _sessionState.value = SyncSessionState.IDLE
        viewModelScope.launch {
            lanSocketTransport.sendFrame(
                SyncFrame.MergePlanDecision(accepted = false, code = SyncError.PLAN_REJECTED.code)
            )
            delay(200L)
            lanSocketTransport.disconnect()
        }
    }

    // --- Applying and confirming (both devices) ---

    private suspend fun applyAgreedPlan() {
        if (planApplied) return
        planApplied = true
        setSessionState(SyncSessionState.APPLYING)
        _isSyncing.value = true
        _syncStatusMessage.value = "Applying the agreed changes..."

        val result = SyncMergeExecutor.applyPlan(planItems, vaultRepository)
        if (result.isFailure) {
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) throw failure
            // Locked while applying: the merge was rolled back, and the peer learns why.
            val error = if (failure is VaultLockedException) SyncError.VAULT_LOCKED else SyncError.MERGE_FAILED
            lanSocketTransport.sendFrame(SyncFrame.SyncFinished(code = error.code))
            failSync(error, if (failure is VaultLockedException) null else failure?.message, notifyPeer = false)
            return
        }

        // The plan is stored either way, but a session that ended meanwhile has nothing to confirm.
        if (!sessionIsRunning()) return

        // The fingerprint must come from what is really stored now, not from the plan.
        sessionLocalState = null
        val fingerprint = try {
            MergePlans.vaultFingerprint(localSyncState().records)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = ownVaultError(e)
            lanSocketTransport.sendFrame(SyncFrame.SyncFinished(code = error.code))
            failSync(error, ownVaultDetail(e), notifyPeer = false)
            return
        }
        ownFingerprint = fingerprint
        setSessionState(SyncSessionState.CONFIRMING)
        _syncStatusMessage.value = "Confirming with ${peerName()}..."
        if (!lanSocketTransport.sendFrame(SyncFrame.SyncFinished("", fingerprint, planTotal))) {
            failSync(SyncError.CONNECTION_LOST)
            return
        }
        compareFingerprints()
    }

    /** Success is only reported once both devices proved they hold the same entries. */
    private suspend fun compareFingerprints() {
        val mine = ownFingerprint ?: return
        val theirs = peerFinished ?: return
        // The session may have failed meanwhile (e.g. the connection dropped): that error stands.
        if (!sessionIsRunning()) return
        watchdogJob?.cancel()
        _isSyncing.value = false
        _syncStatusMessage.value = null
        if (mine == theirs.fingerprint) {
            val session = sessionNumber
            _sessionState.value = SyncSessionState.DONE
            (activePeerId ?: _activeSyncDevice.value?.deviceId)?.let { peerId ->
                // Best effort: the devices are in sync whether or not the time is recorded.
                try {
                    pairedDeviceRepository.updateLastSync(peerId, System.currentTimeMillis())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                }
            }
            // A new sync may have started while the time was being saved: that one is left alone
            // (not hung up, and its screen not overwritten).
            if (sessionNumber != session || _sessionState.value != SyncSessionState.DONE) return
            _successMessage.value = "Devices are in sync ($planTotal entries updated)"
        } else {
            _sessionState.value = SyncSessionState.FAILED
            _errorMessage.value = SyncError.NOT_IDENTICAL.message
            _errorCode.value = SyncError.NOT_IDENTICAL.code
            _retryDevice.value = _activeSyncDevice.value
        }
        lanSocketTransport.disconnect()
    }

    // --- Failures ---

    /**
     * Ends the session because of [error]; the peer is told while the connection still works.
     * Does nothing once the sync succeeded: a late error can't undo a success or cancel it.
     */
    private suspend fun failSync(error: SyncError, detail: String? = null, notifyPeer: Boolean = true) {
        if (_sessionState.value == SyncSessionState.DONE) return
        receiveFailed = true
        reportFailure(error, detail)
        if (notifyPeer) {
            try {
                lanSocketTransport.sendFrame(SyncFrame.CancelSync(error.message, error.code))
                delay(200L)
            } catch (_: Exception) {
                // Best effort: the connection is already gone, and the error is on screen.
            }
        }
        lanSocketTransport.disconnect()
    }

    /**
     * Puts [error] on screen without touching the connection. The first reason a session ended is
     * kept (later ones are its consequences) unless [replace] says this one explains it better.
     */
    private fun reportFailure(error: SyncError, detail: String? = null, replace: Boolean = false) {
        if (_sessionState.value == SyncSessionState.DONE) return
        if (_sessionState.value == SyncSessionState.FAILED && _errorMessage.value != null && !replace) return
        watchdogJob?.cancel()
        _sessionState.value = SyncSessionState.FAILED
        _isSyncing.value = false
        _syncStatusMessage.value = null
        _diffResult.value = null
        _pendingPlanApproval.value = null
        _errorMessage.value = if (detail.isNullOrBlank()) error.message else error.message + " " + detail
        _errorCode.value = error.code
        _retryDevice.value = null
    }

    private fun peerName(): String = _activeSyncDevice.value?.deviceName ?: "the other device"

    fun setDiffResultForTesting(result: SyncDiffResult?) {
        _diffResult.value = result
        if (result != null) {
            isReviewer = true
            _sessionState.value = SyncSessionState.REVIEWING
        }
    }

    companion object {
        const val MAX_PAYLOAD_PARTS = 5_000
        const val MAX_PAYLOAD_RECORDS = 200_000
    }
}

class LanSyncViewModelFactory(
    private val pairedDeviceRepository: PairedDeviceRepository,
    private val lanDiscoveryManager: LanDiscoveryManager,
    private val lanSocketTransport: LanSocketTransport,
    private val vaultRepository: VaultRepository,
    private val isVaultUnlocked: StateFlow<Boolean>
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LanSyncViewModel::class.java)) {
            return LanSyncViewModel(
                pairedDeviceRepository,
                lanDiscoveryManager,
                lanSocketTransport,
                vaultRepository,
                isVaultUnlocked
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
