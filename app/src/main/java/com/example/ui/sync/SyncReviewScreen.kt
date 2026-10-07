package com.example.ui.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.domain.models.VaultEntry
import com.example.domain.sync.diff.EntryDiffItem
import com.example.domain.sync.diff.EntrySyncCategory
import com.example.domain.sync.diff.FieldChangeType
import com.vaultpass.synccore.EntryLimits
import com.vaultpass.synccore.ItemOutcome

/** What each outcome does, in the words the user reads. */
fun outcomeLabel(outcome: ItemOutcome, peerName: String): String = when (outcome) {
    ItemOutcome.USE_REMOTE -> "Use $peerName's version"
    ItemOutcome.USE_LOCAL -> "Keep this device's version"
    ItemOutcome.DELETE_BOTH -> "Delete on both"
    ItemOutcome.SKIP -> "Leave unchanged"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncReviewScreen(
    viewModel: LanSyncViewModel,
    onNavigateBack: () -> Unit,
    onSyncCompleted: () -> Unit
) {
    val diffResult by viewModel.diffResult.collectAsState()
    val activeSyncDevice by viewModel.activeSyncDevice.collectAsState()
    val peerName = activeSyncDevice?.deviceName ?: "the other device"

    var showCancelDialog by remember { mutableStateOf(false) }
    var entryBeingEdited by remember { mutableStateOf<EntryDiffItem?>(null) }

    val diff = diffResult

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review Synchronization") },
                navigationIcon = {
                    IconButton(onClick = { showCancelDialog = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (diff != null) {
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        if (diff.hasUnansweredConflicts) {
                            Text(
                                text = "Choose what to keep for every conflict before applying.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = { showCancelDialog = true }) {
                                Text("Cancel")
                            }

                            val changes = diff.plannedChanges
                            // An empty plan runs the same flow: it ends the session properly.
                            Button(
                                onClick = {
                                    viewModel.applyReviewedPlan()
                                    onSyncCompleted()
                                },
                                enabled = !diff.hasUnansweredConflicts,
                                colors = if (changes == 0) {
                                    ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2E7D32),
                                        contentColor = Color.White
                                    )
                                } else {
                                    ButtonDefaults.buttonColors()
                                }
                            ) {
                                if (changes == 0) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Devices are up to date (Finish)")
                                } else {
                                    Text("Apply to both devices ($changes)")
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        if (diff == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No differences to display", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onNavigateBack) {
                        Text("Return to Sync")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Summary Header Banner
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SummaryBadge(
                            count = diff.newRemoteCount,
                            label = "New",
                            color = Color(0xFF2E7D32)
                        )
                        SummaryBadge(
                            count = diff.modifiedCount,
                            label = "Modified",
                            color = Color(0xFFE65100)
                        )
                        if (diff.conflictCount > 0) {
                            SummaryBadge(
                                count = diff.conflictCount,
                                label = "Conflicts",
                                color = Color(0xFF6A1B9A)
                            )
                        }
                        if (diff.deletedRemoteCount + diff.deletedLocalCount > 0) {
                            SummaryBadge(
                                count = diff.deletedRemoteCount + diff.deletedLocalCount,
                                label = "Deleted",
                                color = Color(0xFFC62828)
                            )
                        }
                        SummaryBadge(
                            count = diff.unchangedCount,
                            label = "Unchanged",
                            color = Color(0xFF757575)
                        )
                    }
                }

                Text(
                    text = "Both devices end up the same: pick what to keep for each entry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )

                // Diff Items List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(diff.diffItems, key = { it.syncKey }) { item ->
                        DiffItemCard(
                            item = item,
                            peerName = peerName,
                            onOutcomeSelected = { outcome ->
                                viewModel.updateItemOutcome(item.syncKey, outcome)
                            },
                            onEdit = {
                                entryBeingEdited = item
                            }
                        )
                    }
                }
            }
        }

        // Cancel Confirmation Dialog
        if (showCancelDialog) {
            AlertDialog(
                onDismissRequest = { showCancelDialog = false },
                title = { Text("Cancel Synchronization") },
                text = {
                    Text("Cancel synchronization? Neither device will be changed.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showCancelDialog = false
                            viewModel.cancelSyncReview()
                            onNavigateBack()
                        }
                    ) {
                        Text("Yes, Cancel")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCancelDialog = false }) {
                        Text("Continue Review")
                    }
                }
            )
        }

        // Inline Entry Edit Dialog
        entryBeingEdited?.let { item ->
            InlineEditEntryDialog(
                item = item,
                onDismiss = { entryBeingEdited = null },
                onSave = { updatedEntry ->
                    viewModel.updateEntryFieldOverride(item.syncKey, updatedEntry)
                    entryBeingEdited = null
                }
            )
        }
    }
}

@Composable
fun SummaryBadge(count: Int, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.15f))
                .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DiffItemCard(
    item: EntryDiffItem,
    peerName: String,
    onOutcomeSelected: (ItemOutcome) -> Unit,
    onEdit: () -> Unit
) {
    val comparesVersions = item.category == EntrySyncCategory.MODIFIED || item.category == EntrySyncCategory.CONFLICT
    var expanded by remember { mutableStateOf(comparesVersions) }

    val activeEntry = item.editedEntry ?: item.remoteEntry ?: item.localEntry ?: VaultEntry()
    val unanswered = item.needsChoice && !item.answered

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        border = if (unanswered) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeEntry.title.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = activeEntry.username.ifBlank { "No username" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    categoryHint(item.category, peerName)?.let { hint ->
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Category Badge
                CategoryBadge(item.category)

                if (item.editedEntry != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Edited",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }

            if (unanswered) {
                Text(
                    text = "Changed on both devices: choose one.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }

            // The outcome this entry gets on BOTH devices.
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item.choices.forEach { choice ->
                    FilterChip(
                        selected = !unanswered && item.outcome == choice,
                        onClick = { onOutcomeSelected(choice) },
                        label = {
                            Text(
                                text = outcomeLabel(choice, peerName),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                }
            }

            // Expandable Field Comparison
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    item.fieldDiffs.forEach { diff ->
                        FieldDiffRow(diff = diff)
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Deleting on both devices has nothing to edit.
                        if (item.outcome != ItemOutcome.DELETE_BOTH) {
                            AssistChip(
                                onClick = onEdit,
                                label = { Text("Edit Entry") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What the item means, when the badge alone doesn't say. */
private fun categoryHint(category: EntrySyncCategory, peerName: String): String? = when (category) {
    EntrySyncCategory.CONFLICT -> "Changed on both devices since the last sync"
    EntrySyncCategory.DELETED_REMOTE -> "Deleted on $peerName"
    EntrySyncCategory.DELETED_LOCAL -> "Deleted on this device"
    else -> null
}

@Composable
fun CategoryBadge(category: EntrySyncCategory) {
    val (bgColor, textColor, text) = when (category) {
        EntrySyncCategory.NEW_REMOTE -> Triple(Color(0xFF2E7D32), Color.White, "NEW")
        EntrySyncCategory.MODIFIED -> Triple(Color(0xFFE65100), Color.White, "MODIFIED")
        EntrySyncCategory.CONFLICT -> Triple(Color(0xFF6A1B9A), Color.White, "CONFLICT")
        EntrySyncCategory.NEW_LOCAL -> Triple(Color(0xFF1565C0), Color.White, "LOCAL ONLY")
        EntrySyncCategory.UNCHANGED -> Triple(Color(0xFF757575), Color.White, "UNCHANGED")
        EntrySyncCategory.DELETED_REMOTE -> Triple(Color(0xFFC62828), Color.White, "DELETED THERE")
        EntrySyncCategory.DELETED_LOCAL -> Triple(Color(0xFFC62828), Color.White, "DELETED HERE")
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun FieldDiffRow(diff: com.example.domain.sync.diff.FieldDiff) {
    val isModified = diff.changeType == FieldChangeType.MODIFIED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isModified) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                else Color.Transparent
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = diff.fieldName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (isModified) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Phone: ${diff.localValue.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Desktop: ${diff.remoteValue.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isModified) FontWeight.Bold else FontWeight.Normal,
                color = if (isModified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** The "Max N characters" line under a field that is too long; null while it fits. */
private fun limitMessage(value: String, limit: Int): (@Composable () -> Unit)? =
    if (value.length > limit) ({ Text(EntryLimits.tooLong(limit)) }) else null

@Composable
fun InlineEditEntryDialog(
    item: EntryDiffItem,
    onDismiss: () -> Unit,
    onSave: (VaultEntry) -> Unit
) {
    // copy() below keeps everything the dialog doesn't show: syncId, custom fields, tags, favorite.
    val initial = item.editedEntry
        ?: (if (item.outcome == ItemOutcome.USE_LOCAL) item.localEntry else item.remoteEntry)
        ?: item.remoteEntry ?: item.localEntry ?: VaultEntry()

    var title by remember { mutableStateOf(initial.title) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var website by remember { mutableStateOf(initial.website) }
    var notes by remember { mutableStateOf(initial.notes) }
    var category by remember { mutableStateOf(initial.category) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    // The same limits as the entry editor, so a merged entry can be opened and saved anywhere.
    val withinLimits = title.length <= EntryLimits.TITLE && username.length <= EntryLimits.USERNAME &&
        password.length <= EntryLimits.PASSWORD && website.length <= EntryLimits.WEBSITE &&
        notes.length <= EntryLimits.NOTES

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Entry Before Merge") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    isError = title.length > EntryLimits.TITLE,
                    supportingText = limitMessage(title, EntryLimits.TITLE),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    isError = username.length > EntryLimits.USERNAME,
                    supportingText = limitMessage(username, EntryLimits.USERNAME),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = password.length > EntryLimits.PASSWORD,
                    supportingText = limitMessage(password, EntryLimits.PASSWORD),
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isPasswordVisible) "Hide" else "Show"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = website,
                    onValueChange = { website = it },
                    label = { Text("Website") },
                    singleLine = true,
                    isError = website.length > EntryLimits.WEBSITE,
                    supportingText = limitMessage(website, EntryLimits.WEBSITE),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    maxLines = 3,
                    isError = notes.length > EntryLimits.NOTES,
                    supportingText = limitMessage(notes, EntryLimits.NOTES),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val updated = initial.copy(
                        syncId = item.syncKey,
                        title = title,
                        username = username,
                        password = password,
                        website = website,
                        notes = notes,
                        category = category
                    )
                    onSave(updated)
                },
                enabled = withinLimits
            ) {
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
