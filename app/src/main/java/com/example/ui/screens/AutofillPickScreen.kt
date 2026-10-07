package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.models.VaultEntry
import com.example.service.AutofillPick

/**
 * Autofill "Search VaultPass…": a searchable list of entries (title + the usual preview line, never a password).
 * [entries] is null while the vault is still loading. [targetLabel]: the page's domain or the app being filled,
 * shown at the top. [confirmationFor]: a question to ask before filling the picked entry (another site's entry on
 * a web page), or null to fill right away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutofillPickScreen(
    entries: List<VaultEntry>?,
    showRememberNote: Boolean,
    targetLabel: String,
    confirmationFor: (VaultEntry) -> String?,
    onPick: (VaultEntry) -> Unit,
    onCancel: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var pendingConfirmation by remember { mutableStateOf<Pair<VaultEntry, String>?>(null) }

    pendingConfirmation?.let { (entry, message) ->
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text(stringResource(R.string.autofill_pick_mismatch_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    pendingConfirmation = null
                    onPick(entry)
                }) { Text(stringResource(R.string.autofill_pick_fill_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.autofill_pick_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                text = targetLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.dashboard_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (showRememberNote) {
                Text(
                    text = stringResource(R.string.autofill_pick_remember_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (entries == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            val results = remember(entries, query) { AutofillPick.search(entries, query) }
            if (results.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.autofill_pick_no_results),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(AutofillPick.previewLine(entry), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                val confirmation = confirmationFor(entry)
                                if (confirmation == null) onPick(entry) else pendingConfirmation = entry to confirmation
                            }
                    )
                }
            }
        }
    }
}
