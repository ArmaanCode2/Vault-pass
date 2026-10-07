package com.example.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.repository.SettingsRepository
import com.example.update.ApkRejection
import com.example.update.UpdateCheckStatus
import com.example.update.UpdateController
import com.example.update.UpdateDialog
import com.example.update.UpdateFailure
import com.example.update.UpdateOffer
import com.example.update.UpdateProblem
import com.example.update.UpdateRetry
import kotlinx.coroutines.launch

/** The app-wide update controller, or null when this build has no update feed. */
@Composable
fun rememberUpdateController(): UpdateController? {
    val app = LocalContext.current.applicationContext as? com.example.VaultPassApplication ?: return null
    return app.container.updateController.takeIf { it.enabled }
}

/** Dashboard card: "Update to latest version X.Y.Z" or "Install update X.Y.Z". */
@Composable
fun UpdateBanner(controller: UpdateController, modifier: Modifier = Modifier) {
    val offer by controller.offer.collectAsStateWithLifecycle()
    val current = offer ?: return
    val (title, subtitle) = when (current) {
        is UpdateOffer.Download -> stringResource(R.string.update_banner_download, current.version) to
            stringResource(R.string.update_banner_download_subtitle)
        is UpdateOffer.Install -> stringResource(R.string.update_banner_install, current.version) to
            stringResource(R.string.update_banner_install_subtitle)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { controller.openOffer() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** Download / verify / install dialog; shown on top of any unlocked screen. */
@Composable
fun UpdateDialogHost(controller: UpdateController) {
    val dialog by controller.dialog.collectAsStateWithLifecycle()
    val context = LocalContext.current
    when (val state = dialog) {
        null -> Unit
        is UpdateDialog.Downloading -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.update_dialog_downloading_title, state.version)) },
            text = {
                Column {
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(
                            R.string.update_dialog_progress,
                            Formatter.formatShortFileSize(context, state.bytes),
                            Formatter.formatShortFileSize(context, state.total)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { controller.cancelDownload() }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
        is UpdateDialog.Verifying -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.update_dialog_verifying_title, state.version)) },
            text = {
                Column {
                    LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.update_dialog_verifying), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {}
        )
        is UpdateDialog.Ready -> AlertDialog(
            onDismissRequest = { controller.later() },
            title = { Text(stringResource(R.string.update_dialog_ready_title, state.version)) },
            text = { Text(stringResource(R.string.update_dialog_ready_message)) },
            confirmButton = {
                TextButton(onClick = { controller.installPending() }) { Text(stringResource(R.string.update_restart_now)) }
            },
            dismissButton = {
                TextButton(onClick = { controller.later() }) { Text(stringResource(R.string.update_later)) }
            }
        )
        is UpdateDialog.NeedsInstallPermission -> AlertDialog(
            onDismissRequest = { controller.later() },
            title = { Text(stringResource(R.string.update_dialog_permission_title)) },
            text = { Text(stringResource(R.string.update_dialog_permission_message)) },
            confirmButton = {
                TextButton(onClick = { controller.openInstallPermissionSettings() }) { Text(stringResource(R.string.update_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { controller.later() }) { Text(stringResource(R.string.update_later)) }
            }
        )
        is UpdateDialog.Installing -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.update_dialog_installing_title, state.version)) },
            text = {
                Column {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.update_dialog_installing_message))
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { controller.dismissDialog() }) { Text(stringResource(R.string.update_close)) }
            }
        )
        is UpdateDialog.Failed -> AlertDialog(
            onDismissRequest = { controller.dismissDialog() },
            title = { Text(stringResource(R.string.update_dialog_failed_title)) },
            text = { Text(updateProblemText(state.problem)) },
            confirmButton = {
                if (state.retry != UpdateRetry.NONE) {
                    TextButton(onClick = { controller.retry() }) { Text(stringResource(R.string.update_retry)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.dismissDialog() }) { Text(stringResource(R.string.update_close)) }
            }
        )
    }
}

/** Settings card: the "check when the app opens" switch and "Check now". */
@Composable
fun UpdateSettingsSection(controller: UpdateController, settingsRepository: SettingsRepository) {
    val checkOnOpen by settingsRepository.checkUpdatesOnOpen.collectAsStateWithLifecycle(initialValue = false)
    val status by controller.checkStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_updates), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                SettingsRow(
                    title = stringResource(R.string.settings_update_check_on_open),
                    subtitle = stringResource(R.string.settings_update_check_on_open_subtitle),
                    icon = Icons.Default.Update,
                    iconColor = MaterialTheme.colorScheme.primary,
                    iconBgColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                    trailingContent = {
                        Switch(checked = checkOnOpen, onCheckedChange = { scope.launch { settingsRepository.setCheckUpdatesOnOpen(it) } })
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                val current = status
                SettingsRow(
                    title = stringResource(R.string.settings_update_check_now),
                    subtitle = when (current) {
                        UpdateCheckStatus.Idle -> stringResource(R.string.settings_update_installed_version, controller.runningVersion)
                        UpdateCheckStatus.Checking -> stringResource(R.string.settings_update_checking)
                        is UpdateCheckStatus.UpToDate -> stringResource(R.string.settings_update_up_to_date, controller.runningVersion)
                        is UpdateCheckStatus.Available -> stringResource(R.string.settings_update_available, current.version)
                        is UpdateCheckStatus.Failed -> updateProblemText(UpdateProblem.Download(current.failure))
                    },
                    icon = Icons.Default.SystemUpdate,
                    iconColor = if (current is UpdateCheckStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    iconBgColor = if (current is UpdateCheckStatus.Failed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
                    trailingContent = {
                        if (current == UpdateCheckStatus.Checking) {
                            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            Icon(Icons.Default.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = null)
                        }
                    },
                    onClick = {
                        if (current is UpdateCheckStatus.Available && controller.offer.value != null) {
                            controller.openOffer()
                        } else {
                            controller.checkNow()
                        }
                    }
                )
            }
        }
    }
}

/** Plain-words text for an update problem. */
@Composable
fun updateProblemText(problem: UpdateProblem): String = when (problem) {
    is UpdateProblem.Download -> stringResource(
        when (problem.failure) {
            UpdateFailure.NETWORK -> R.string.update_error_network
            UpdateFailure.HTTP_STATUS -> R.string.update_error_http
            UpdateFailure.BAD_RESPONSE -> R.string.update_error_bad_response
            UpdateFailure.NO_CHECKSUM -> R.string.update_error_no_checksum
            UpdateFailure.BAD_VERSION -> R.string.update_error_bad_version
            UpdateFailure.NO_APK_ASSET -> R.string.update_error_no_apk
            UpdateFailure.BAD_SIZE -> R.string.update_error_bad_size
            UpdateFailure.INSECURE_URL -> R.string.update_error_insecure
            UpdateFailure.TOO_MANY_REDIRECTS -> R.string.update_error_redirects
            UpdateFailure.SIZE_MISMATCH -> R.string.update_error_size_mismatch
            UpdateFailure.DIGEST_MISMATCH -> R.string.update_error_digest
            UpdateFailure.STORAGE -> R.string.update_error_storage
        }
    )
    is UpdateProblem.Verification -> stringResource(
        when (problem.reason) {
            ApkRejection.MISSING_FILE -> R.string.update_error_missing_file
            ApkRejection.UNREADABLE -> R.string.update_error_unreadable
            ApkRejection.INSTALLED_INFO_UNAVAILABLE -> R.string.update_error_installed_info
            ApkRejection.WRONG_PACKAGE -> R.string.update_error_wrong_package
            ApkRejection.NOT_NEWER -> R.string.update_error_not_newer
            ApkRejection.FOREIGN_SIGNER -> R.string.update_error_foreign_signer
            ApkRejection.SIGNER_UNREADABLE -> R.string.update_error_signer_unreadable
        }
    )
    UpdateProblem.PendingUpdateGone -> stringResource(R.string.update_error_pending_gone)
    UpdateProblem.InstallPermissionMissing -> stringResource(R.string.update_error_permission_missing)
    UpdateProblem.PermissionSettingsUnavailable -> stringResource(R.string.update_error_permission_settings)
    UpdateProblem.InstallStartFailed -> stringResource(R.string.update_error_install_start)
    UpdateProblem.SignedWithDifferentKey -> stringResource(R.string.update_error_signed_different_key)
    UpdateProblem.IncompatibleWithDevice -> stringResource(R.string.update_error_incompatible)
    UpdateProblem.InstallCancelled -> stringResource(R.string.update_error_install_cancelled)
    UpdateProblem.InstallBlocked -> stringResource(R.string.update_error_install_blocked)
    UpdateProblem.InstallStorage -> stringResource(R.string.update_error_install_storage)
    UpdateProblem.ConfirmationUnavailable -> stringResource(R.string.update_error_confirmation)
    is UpdateProblem.InstallFailed -> problem.systemMessage?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.update_error_install_failed_detail, it) }
        ?: stringResource(R.string.update_error_install_failed)
}
