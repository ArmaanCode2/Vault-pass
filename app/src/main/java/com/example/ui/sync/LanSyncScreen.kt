package com.example.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.domain.sync.models.PairedDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanSyncScreen(
    viewModel: LanSyncViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToScanner: () -> Unit,
    onNavigateToReview: () -> Unit
) {
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncStatusMessage by viewModel.syncStatusMessage.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val pendingSyncRequest by viewModel.pendingSyncRequest.collectAsState()
    val diffResult by viewModel.diffResult.collectAsState()
    val activeSyncDevice by viewModel.activeSyncDevice.collectAsState()
    val syncState by viewModel.syncState.collectAsState()

    var deviceToUnpair by remember { mutableStateOf<PairedDevice?>(null) }

    LaunchedEffect(diffResult) {
        if (diffResult != null) {
            onNavigateToReview()
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (isSyncing) {
                viewModel.cancelActiveSync()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Sync") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshDevices() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh devices"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Pair New Device Banner Button
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Pair New Device",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Scan the QR code shown on VaultPass Desktop",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onNavigateToScanner) {
                        Text("Scan QR")
                    }
                }
            }

            Text(
                text = "PAIRED DEVICES",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            if (pairedDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Computer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Paired Devices",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap 'Scan QR' above to pair with VaultPass on your computer over the local network.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pairedDevices, key = { it.deviceId }) { device ->
                        PairedDeviceCard(
                            device = device,
                            onSync = { viewModel.initiateSyncWithDevice(device) },
                            onUnpair = { deviceToUnpair = device }
                        )
                    }
                }
            }
        }

        // Unpair Confirmation Dialog
        deviceToUnpair?.let { device ->
            AlertDialog(
                onDismissRequest = { deviceToUnpair = null },
                title = { Text("Forget Device") },
                text = {
                    Text("Are you sure you want to unpair from ${device.deviceName}? You will need to scan a new QR code to sync with this device again.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.unpairDevice(device.deviceId)
                            deviceToUnpair = null
                        }
                    ) {
                        Text("Forget")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deviceToUnpair = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Incoming Sync Request Dialog
        pendingSyncRequest?.let { request ->
            AlertDialog(
                onDismissRequest = { viewModel.declineIncomingSync(request) },
                title = { Text("Incoming Sync Request") },
                text = {
                    Text("${request.deviceName} would like to synchronize credentials with this device. Would you like to review changes?")
                },
                confirmButton = {
                    Button(onClick = { viewModel.acceptIncomingSync(request) }) {
                        Text("Accept & Review")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { viewModel.declineIncomingSync(request) }) {
                        Text("Decline")
                    }
                }
            )
        }

        // Sync Progress Dialog
        if (isSyncing) {
            AlertDialog(
                onDismissRequest = { viewModel.cancelActiveSync() },
                title = { Text("Synchronizing Vault") },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = syncStatusMessage ?: "Waiting for other device...",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { viewModel.cancelActiveSync() }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Error Dialog
        errorMessage?.let { error ->
            ErrorDetailsDialog(
                errorMessage = error,
                technicalDetails = buildString {
                    appendLine("Target Device: ${activeSyncDevice?.deviceName ?: "Unknown Device"}")
                    appendLine("Target IP: ${activeSyncDevice?.ipAddress ?: "Unknown IP"}:${activeSyncDevice?.port ?: 53853}")
                    appendLine("Sync State: ${syncState.name}")
                    appendLine("Local IP: ${viewModel.getLocalIp() ?: "Unavailable"}")
                },
                onDismiss = { viewModel.clearError() }
            )
        }
    }
}

@Composable
fun PairedDeviceCard(
    device: PairedDevice,
    onSync: () -> Unit,
    onUnpair: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (device.ipAddress != null) "IP: ${device.ipAddress}:${device.port}" else "Port: ${device.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Bluetooth-style status indicator pill
                val (badgeColor, textColor, statusText) = if (device.isOnline) {
                    Triple(Color(0xFF2E7D32), Color.White, "Online")
                } else {
                    Triple(Color(0xFF757575), Color.White, "Offline")
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(badgeColor)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(textColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val lastSyncFormatted = if (device.lastSyncAt > 0L) {
                val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                "Last sync: ${formatter.format(Date(device.lastSyncAt))}"
            } else {
                "Never synced"
            }

            Text(
                text = lastSyncFormatted,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onUnpair) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Unpair device",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = onSync,
                    enabled = device.isOnline
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sync Now")
                }
            }
        }
    }
}
