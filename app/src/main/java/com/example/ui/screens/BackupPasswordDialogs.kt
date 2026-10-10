package com.example.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.EXPORT_PASSWORD_MIN_LENGTH

/**
 * Asks for the password that encrypts a .vpex backup, twice: a typo would leave a backup nobody
 * can open (F26). [password] lives in the view model; the confirmation only here, so it goes when
 * the dialog goes. [onExport] gets the confirmation, to check against the live password: a key can
 * arrive in the same frame as the tap.
 */
@Composable
internal fun ExportPasswordDialog(
    password: String,
    onPasswordChange: (String) -> Unit,
    onExport: (confirmation: String) -> Unit,
    onDismiss: () -> Unit
) {
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val longEnough = password.length >= EXPORT_PASSWORD_MIN_LENGTH
    val matches = password == confirmation
    val canExport = longEnough && matches
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_secure_export)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_export_password_hint))
                Spacer(modifier = Modifier.height(16.dp))
                BackupPasswordField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.settings_backup_password),
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                    error = if (password.isNotEmpty() && !longEnough) {
                        stringResource(R.string.settings_export_password_too_short, EXPORT_PASSWORD_MIN_LENGTH)
                    } else {
                        null
                    },
                    imeAction = ImeAction.Next,
                    modifier = Modifier.testTag(EXPORT_PASSWORD_TAG)
                )
                Spacer(modifier = Modifier.height(8.dp))
                BackupPasswordField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = stringResource(R.string.settings_confirm_backup_password),
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                    error = if (confirmation.isNotEmpty() && !matches) stringResource(R.string.settings_export_passwords_differ) else null,
                    imeAction = ImeAction.Done,
                    onDone = { if (canExport) onExport(confirmation) },
                    modifier = Modifier.testTag(EXPORT_CONFIRMATION_TAG)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (canExport) onExport(confirmation) }, enabled = canExport) {
                Text(stringResource(R.string.settings_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** Asks for the password of an encrypted backup being imported. Hidden until shown (F26). */
@Composable
internal fun ImportPasswordDialog(
    password: String,
    onPasswordChange: (String) -> Unit,
    isDecrypting: Boolean,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        // Stays open while the backup is being decrypted; its result needs this dialog.
        onDismissRequest = { if (!isDecrypting) onDismiss() },
        title = { Text(stringResource(R.string.settings_unlock_backup)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_unlock_backup_hint))
                Spacer(modifier = Modifier.height(16.dp))
                BackupPasswordField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.settings_backup_password),
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                    enabled = !isDecrypting,
                    imeAction = ImeAction.Done,
                    onDone = onUnlock,
                    modifier = Modifier.testTag(IMPORT_PASSWORD_TAG)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !isDecrypting, onClick = onUnlock) {
                if (isDecrypting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.settings_unlock_import))
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !isDecrypting, onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/** A masked password field with a show/hide button and a password keyboard that learns nothing. */
@Composable
private fun BackupPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
    onDone: () -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(if (visible) R.string.settings_hide_password else R.string.settings_show_password)
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

internal const val EXPORT_PASSWORD_TAG = "export_password"
internal const val EXPORT_CONFIRMATION_TAG = "export_password_confirmation"
internal const val IMPORT_PASSWORD_TAG = "import_password"
