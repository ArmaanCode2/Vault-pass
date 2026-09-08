package com.example.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.models.VaultEntry
import com.example.domain.sync.diff.EntryDiffItem
import com.example.domain.sync.diff.SyncDiffEngine
import com.example.domain.sync.diff.SyncDiffResult
import com.example.domain.sync.diff.SyncEntryManifest
import com.example.domain.sync.diff.SyncMergeExecutor
import com.example.domain.sync.models.PairedDevice
import com.example.domain.sync.models.SyncEntryDto
import com.example.domain.sync.models.SyncFrame
import com.example.domain.sync.models.SyncState
import com.example.network.sync.LanDiscoveryManager
import com.example.network.sync.LanSocketTransport
import com.example.repository.PairedDeviceRepository
import com.example.repository.VaultRepository
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class LanSyncViewModel(
    private val pairedDeviceRepository: PairedDeviceRepository,
    private val lanDiscoveryManager: LanDiscoveryManager,
    private val lanSocketTransport: LanSocketTransport,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val syncJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

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

    private val _diffResult = MutableStateFlow<SyncDiffResult?>(null)
    val diffResult: StateFlow<SyncDiffResult?> = _diffResult.asStateFlow()

    private val _activeSyncDevice = MutableStateFlow<PairedDevice?>(null)
    val activeSyncDevice: StateFlow<PairedDevice?> = _activeSyncDevice.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatusMessage = MutableStateFlow<String?>(null)
    val syncStatusMessage: StateFlow<String?> = _syncStatusMessage.asStateFlow()

    private var syncTimeoutJob: Job? = null

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        lanDiscoveryManager.start()
        lanSocketTransport.startServer()

        viewModelScope.launch {
            lanSocketTransport.receiveFrames().collect { frame ->
                handleIncomingSyncFrame(frame)
            }
        }

        viewModelScope.launch {
            lanSocketTransport.syncState.collect { state ->
                if (state == SyncState.IDLE || state == SyncState.ERROR) {
                    if (_isSyncing.value && _diffResult.value == null) {
                        _isSyncing.value = false
                        if (_errorMessage.value == null) {
                            _errorMessage.value = "Connection closed by peer device."
                        }
                    }
                }
            }
        }
    }

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
    }

    fun getLocalIp(): String? = lanDiscoveryManager.getLocalIpAddress()

    fun initiateSyncWithDevice(device: PairedDevice) {
        val ip = device.ipAddress
        if (ip.isNullOrBlank()) {
            _errorMessage.value = "Device IP address unknown. Make sure device is online."
            return
        }

        viewModelScope.launch {
            _isSyncing.value = true
            _syncStatusMessage.value = "Connecting to ${device.deviceName}..."
            _activeSyncDevice.value = device

            lanSocketTransport.startServer()

            val directConnected = withTimeoutOrNull(3500) {
                lanSocketTransport.connectToPeer(ip, device.port)
            } == true

            val connected = if (directConnected) {
                true
            } else {
                lanSocketTransport.disconnect()
                _syncStatusMessage.value = "Direct connection blocked; requesting reverse connection from Desktop..."
                val localIp = lanDiscoveryManager.getLocalIpAddress() ?: "127.0.0.1"
                lanDiscoveryManager.sendReverseSyncSignal(
                    desktopIp = ip,
                    mobileIp = localIp,
                    mobilePort = LanDiscoveryManager.SYNC_TCP_PORT
                )

                val reverseConnected = withTimeoutOrNull(5000) {
                    while (!lanSocketTransport.isConnected) {
                        delay(100)
                    }
                    true
                } ?: false
                reverseConnected
            }

            if (!connected) {
                _isSyncing.value = false
                _errorMessage.value = "Could not establish connection to ${device.deviceName} on port ${device.port}."
                return@launch
            }

            lanSocketTransport.activeSharedSecret = device.sharedSecret
            lanSocketTransport.activePeerDeviceId = device.deviceId
            lanSocketTransport.activePeerDeviceName = device.deviceName

            _syncStatusMessage.value = "Waiting for ${device.deviceName} to accept sync..."
            syncTimeoutJob?.cancel()
            syncTimeoutJob = viewModelScope.launch {
                delay(60_000L)
                if (_isSyncing.value && _diffResult.value == null) {
                    _isSyncing.value = false
                    _syncStatusMessage.value = null
                    _errorMessage.value = "Sync timed out: No response from ${device.deviceName} within 60 seconds."
                    try {
                        lanSocketTransport.sendFrame(
                            SyncFrame.CancelSync(reason = "Sync request timed out after 60 seconds"),
                            sharedSecret = lanSocketTransport.activeSharedSecret
                        )
                        delay(200L)
                    } catch (_: Exception) {}
                    lanSocketTransport.disconnect()
                }
            }
            val request = SyncFrame.SyncRequest(
                deviceId = lanSocketTransport.localDeviceId,
                deviceName = lanSocketTransport.localDeviceName
            )
            val sent = lanSocketTransport.sendFrame(request, sharedSecret = null)
            if (!sent) {
                _isSyncing.value = false
                _errorMessage.value = "Failed to send sync request."
            }
        }
    }

    fun acceptIncomingSync(request: SyncFrame.SyncRequest) {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncStatusMessage.value = "Accepting sync with ${request.deviceName}..."

            val pairedDevice = pairedDevices.value.find { it.deviceId == request.deviceId }
            if (pairedDevice != null) {
                _activeSyncDevice.value = pairedDevice
                lanSocketTransport.activeSharedSecret = pairedDevice.sharedSecret
                lanSocketTransport.activePeerDeviceId = pairedDevice.deviceId
                lanSocketTransport.activePeerDeviceName = pairedDevice.deviceName
            }

            val acceptance = SyncFrame.SyncAcceptance(
                deviceId = lanSocketTransport.localDeviceId,
                isAccepted = true
            )
            lanSocketTransport.sendFrame(acceptance, sharedSecret = lanSocketTransport.activeSharedSecret)

            // Send local payload
            sendLocalPayloadBatch()
        }
    }

    fun declineIncomingSync(request: SyncFrame.SyncRequest) {
        viewModelScope.launch {
            val rejection = SyncFrame.SyncAcceptance(
                deviceId = lanSocketTransport.localDeviceId,
                isAccepted = false
            )
            lanSocketTransport.sendFrame(rejection, sharedSecret = lanSocketTransport.activeSharedSecret)
            delay(200L)
            lanSocketTransport.disconnect()
            _isSyncing.value = false
        }
    }

    fun cancelActiveSync() {
        syncTimeoutJob?.cancel()
        _isSyncing.value = false
        _syncStatusMessage.value = null
        viewModelScope.launch {
            try {
                lanSocketTransport.sendFrame(
                    SyncFrame.CancelSync(reason = "User cancelled sync"),
                    sharedSecret = lanSocketTransport.activeSharedSecret
                )
                delay(200L)
            } catch (_: Exception) {}
            lanSocketTransport.disconnect()
        }
    }

    private suspend fun sendLocalPayloadBatch() {
        val entries = try {
            withTimeoutOrNull(10_000L) {
                vaultRepository.decryptedEntries.first { it.isNotEmpty() }
            } ?: vaultRepository.decryptedEntries.value
        } catch (e: Exception) {
            vaultRepository.decryptedEntries.value
        }
        val dtos = entries.map { SyncEntryDto.fromVaultEntry(it) }
        val batchJson = json.encodeToString(ListSerializer(SyncEntryDto.serializer()), dtos)
        val batchFrame = SyncFrame.PayloadBatch(encryptedBatchJson = batchJson)
        lanSocketTransport.sendFrame(batchFrame, sharedSecret = lanSocketTransport.activeSharedSecret)
    }

    private suspend fun handleIncomingSyncFrame(frame: SyncFrame) {
        when (frame) {
            is SyncFrame.SyncAcceptance -> {
                syncTimeoutJob?.cancel()
                if (frame.isAccepted) {
                    _syncStatusMessage.value = "Sync accepted. Exchanging vault records..."
                    sendLocalPayloadBatch()
                } else {
                    _isSyncing.value = false
                    _syncStatusMessage.value = null
                    _errorMessage.value = "Sync request was declined by ${activeSyncDevice.value?.deviceName ?: "Desktop"}."
                    lanSocketTransport.disconnect()
                }
            }
            is SyncFrame.PayloadBatch -> {
                syncTimeoutJob?.cancel()
                processRemotePayloadBatch(frame.encryptedBatchJson)
            }
            is SyncFrame.CancelSync -> {
                syncTimeoutJob?.cancel()
                _isSyncing.value = false
                if (frame.reason.startsWith("SYNC_COMPLETE")) {
                    val count = frame.reason.substringAfter(":", "").toIntOrNull()
                    _syncStatusMessage.value = "Desktop merged ${count ?: "all"} entries successfully."
                } else {
                    _errorMessage.value = "Sync cancelled by peer: ${frame.reason}"
                }
                lanSocketTransport.disconnect()
            }
            else -> {
                // Handled elsewhere
            }
        }
    }

    private suspend fun processRemotePayloadBatch(batchJson: String) {
        _syncStatusMessage.value = "Computing differences..."
        try {
            val remoteDtos = syncJson.decodeFromString(
                ListSerializer(SyncEntryDto.serializer()),
                batchJson
            )
            val remoteEntries = remoteDtos.map { it.toVaultEntry() }
            val localEntries = vaultRepository.decryptedEntries.value
            val recycleBinKeys = try {
                withTimeoutOrNull(5_000L) {
                    vaultRepository.recycleBinEntries.first()
                }?.map { SyncEntryManifest.computeSyncKey(it.title, it.username) }?.toSet()
                    ?: vaultRepository.recycleBinEntries.value
                        .map { SyncEntryManifest.computeSyncKey(it.title, it.username) }
                        .toSet()
            } catch (e: Exception) {
                vaultRepository.recycleBinEntries.value
                    .map { SyncEntryManifest.computeSyncKey(it.title, it.username) }
                    .toSet()
            }
            val diff = withContext(Dispatchers.Default) {
                SyncDiffEngine.computeDiff(localEntries, remoteEntries, recycleBinKeys)
            }
            _diffResult.value = diff
            _isSyncing.value = false
            _syncStatusMessage.value = null
        } catch (e: Exception) {
            _isSyncing.value = false
            _syncStatusMessage.value = null
            _errorMessage.value = "Failed to parse remote vault payload: ${e.message}"
        }
    }

    fun toggleItemSelection(syncKey: String, isSelected: Boolean) {
        _diffResult.update { current ->
            if (current == null) return@update null
            val updatedItems = current.diffItems.map {
                if (it.syncKey == syncKey) it.copy(isSelectedForSync = isSelected) else it
            }
            current.copy(diffItems = updatedItems)
        }
    }

    fun toggleSelectAll(selectAll: Boolean) {
        _diffResult.update { current ->
            if (current == null) return@update null
            val updatedItems = current.diffItems.map { it.copy(isSelectedForSync = selectAll) }
            current.copy(diffItems = updatedItems)
        }
    }

    fun updateEntryFieldOverride(syncKey: String, updatedEntry: VaultEntry) {
        _diffResult.update { current ->
            if (current == null) return@update null
            val updatedItems = current.diffItems.map {
                if (it.syncKey == syncKey) it.copy(editedEntry = updatedEntry) else it
            }
            current.copy(diffItems = updatedItems)
        }
    }

    fun quickUseDesktop(syncKey: String) {
        _diffResult.update { current ->
            if (current == null) return@update null
            val updatedItems = current.diffItems.map {
                if (it.syncKey == syncKey && it.remoteEntry != null) {
                    it.copy(editedEntry = it.remoteEntry)
                } else it
            }
            current.copy(diffItems = updatedItems)
        }
    }

    fun quickKeepPhone(syncKey: String) {
        _diffResult.update { current ->
            if (current == null) return@update null
            val updatedItems = current.diffItems.map {
                if (it.syncKey == syncKey && it.localEntry != null) {
                    it.copy(editedEntry = it.localEntry)
                } else it
            }
            current.copy(diffItems = updatedItems)
        }
    }

    fun executeMerge(
        onSuccess: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val diff = _diffResult.value
        if (diff == null) {
            onError("No diff to merge")
            return
        }

        viewModelScope.launch {
            val result = SyncMergeExecutor.executeMerge(diff.diffItems, vaultRepository)
            result.onSuccess { count ->
                _activeSyncDevice.value?.let { device ->
                    pairedDeviceRepository.updateLastSync(device.deviceId, System.currentTimeMillis())
                }
                _diffResult.value = null
                lanSocketTransport.disconnect()
                onSuccess(count)
            }.onFailure { error ->
                onError(error.message ?: "Failed to merge entries into vault")
            }
        }
    }

    fun cancelSyncReview() {
        syncTimeoutJob?.cancel()
        _diffResult.value = null
        lanSocketTransport.disconnect()
    }

    fun setDiffResultForTesting(result: SyncDiffResult?) {
        _diffResult.value = result
    }
}

class LanSyncViewModelFactory(
    private val pairedDeviceRepository: PairedDeviceRepository,
    private val lanDiscoveryManager: LanDiscoveryManager,
    private val lanSocketTransport: LanSocketTransport,
    private val vaultRepository: VaultRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LanSyncViewModel::class.java)) {
            return LanSyncViewModel(
                pairedDeviceRepository,
                lanDiscoveryManager,
                lanSocketTransport,
                vaultRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
