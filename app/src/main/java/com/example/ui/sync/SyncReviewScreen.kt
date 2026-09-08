package com.example.ui.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncReviewScreen(
    viewModel: LanSyncViewModel,
    onNavigateBack: () -> Unit,
    onSyncCompleted: () -> Unit
) {
    val diffResult by viewModel.diffResult.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCancelDialog by remember { mutableStateOf(false) }
    var entryBeingEdited by remember { mutableStateOf<EntryDiffItem?>(null) }
    var isMerging by remember { mutableStateOf(false) }

    val diff = diffResult

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { showCancelDialog = true }) {
                            Text("Cancel")
                        }

                        val selectedCount = diff.diffItems.count { it.isSelectedForSync }

                        Button(
                            onClick = {
                                isMerging = true
                                viewModel.executeMerge(
                                    onSuccess = { count ->
                                        isMerging = false
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Successfully merged $count entries into vault")
                                            onSyncCompleted()
                                        }
                                    },
                                    onError = { err ->
                                        isMerging = false
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Error: $err")
                                        }
                                    }
                                )
                            },
                            enabled = selectedCount > 0 && !isMerging
                        ) {
                            if (isMerging) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Merge Selected ($selectedCount)")
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
                Text("No differences to display")
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
                        SummaryBadge(
                            count = diff.unchangedCount,
                            label = "Unchanged",
                            color = Color(0xFF757575)
                        )
                    }
                }

                // Select All Row
                val allSelected = diff.diffItems.all { it.isSelectedForSync }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { checked ->
                            viewModel.toggleSelectAll(checked)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (allSelected) "Deselect All" else "Select All",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

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
                            onToggleSelection = { isSelected ->
                                viewModel.toggleItemSelection(item.syncKey, isSelected)
                            },
                            onQuickUseDesktop = {
                                viewModel.quickUseDesktop(item.syncKey)
                            },
                            onQuickKeepPhone = {
                                viewModel.quickKeepPhone(item.syncKey)
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
                    Text("Cancel synchronization? Any unmerged changes will be discarded.")
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
    onToggleSelection: (Boolean) -> Unit,
    onQuickUseDesktop: () -> Unit,
    onQuickKeepPhone: () -> Unit,
    onEdit: () -> Unit
) {
    var expanded by remember { mutableStateOf(item.category == EntrySyncCategory.MODIFIED) }

    val activeEntry = item.editedEntry ?: item.remoteEntry ?: item.localEntry ?: VaultEntry()

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = item.isSelectedForSync,
                    onCheckedChange = onToggleSelection
                )
                Spacer(modifier = Modifier.width(8.dp))
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

                    // Action Chips & Inline Edit Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.category == EntrySyncCategory.MODIFIED) {
                            AssistChip(
                                onClick = onQuickUseDesktop,
                                label = { Text("Use Desktop") },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            AssistChip(
                                onClick = onQuickKeepPhone,
                                label = { Text("Keep Phone") }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

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

@Composable
fun CategoryBadge(category: EntrySyncCategory) {
    val (bgColor, textColor, text) = when (category) {
        EntrySyncCategory.NEW_REMOTE -> Triple(Color(0xFF2E7D32), Color.White, "NEW")
        EntrySyncCategory.MODIFIED -> Triple(Color(0xFFE65100), Color.White, "MODIFIED")
        EntrySyncCategory.NEW_LOCAL -> Triple(Color(0xFF1565C0), Color.White, "LOCAL ONLY")
        EntrySyncCategory.UNCHANGED -> Triple(Color(0xFF757575), Color.White, "UNCHANGED")
        EntrySyncCategory.DELETED_LOCAL -> Triple(Color(0xFFC62828), Color.White, "DELETED")
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

@Composable
fun InlineEditEntryDialog(
    item: EntryDiffItem,
    onDismiss: () -> Unit,
    onSave: (VaultEntry) -> Unit
) {
    val initial = item.editedEntry ?: item.remoteEntry ?: item.localEntry ?: VaultEntry()

    var title by remember { mutableStateOf(initial.title) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var website by remember { mutableStateOf(initial.website) }
    var notes by remember { mutableStateOf(initial.notes) }
    var category by remember { mutableStateOf(initial.category) }
    var isPasswordVisible by remember { mutableStateOf(false) }

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
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
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
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    maxLines = 3,
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
                        title = title,
                        username = username,
                        password = password,
                        website = website,
                        notes = notes,
                        category = category
                    )
                    onSave(updated)
                }
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
