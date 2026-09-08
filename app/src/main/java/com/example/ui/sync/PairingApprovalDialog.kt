package com.example.ui.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.domain.sync.models.SyncFrame

@Composable
fun PairingApprovalDialog(
    request: SyncFrame.PairingRequest?,
    onAccept: (SyncFrame.PairingRequest) -> Unit,
    onDecline: (SyncFrame.PairingRequest) -> Unit
) {
    if (request == null) return

    var isAcceptedWaiting by remember(request) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!isAcceptedWaiting) {
                onDecline(request)
            }
        },
        title = {
            Text(
                text = if (isAcceptedWaiting) "Waiting for Remote Device" else "Pairing Request",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isAcceptedWaiting) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Waiting for other device to accept...",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = "Do you want to pair with ${request.deviceName}?\nBoth devices must accept to complete pairing.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Security Token / Fingerprint:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = request.pairingToken.take(16) + if (request.pairingToken.length > 16) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            if (!isAcceptedWaiting) {
                Button(
                    onClick = {
                        isAcceptedWaiting = true
                        onAccept(request)
                    }
                ) {
                    Text("Accept")
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    onDecline(request)
                }
            ) {
                Text(if (isAcceptedWaiting) "Cancel" else "Decline")
            }
        }
    )
}
