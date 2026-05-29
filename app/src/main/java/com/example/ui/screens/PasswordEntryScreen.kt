package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.ui.VaultViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordEntryScreen(
    viewModel: VaultViewModel,
    navController: NavController,
    entryId: Int?
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val hidePasswordsByDefault by viewModel.settingsRepository.hidePasswordsByDefault.collectAsStateWithLifecycle(initialValue = true)
    
    val existingEntry = remember(entries, entryId) { entries.find { it.id == entryId } }

    var title by remember { mutableStateOf(existingEntry?.title ?: "") }
    var username by remember { mutableStateOf(existingEntry?.username ?: "") }
    var password by remember { mutableStateOf(existingEntry?.password ?: "") }
    var website by remember { mutableStateOf(existingEntry?.website ?: "") }
    var notes by remember { mutableStateOf(existingEntry?.notes ?: "") }
    var category by remember { mutableStateOf(existingEntry?.category ?: "Personal") }
    var isFavorite by remember { mutableStateOf(existingEntry?.isFavorite ?: false) }
    
    var customFields by remember { mutableStateOf(existingEntry?.customFields ?: emptyList()) }
    
    var passwordVisible by remember { mutableStateOf(!hidePasswordsByDefault) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val copyToClipboard: (String, String) -> Unit = { label, text ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
        
        coroutineScope.launch {
            val timer = viewModel.settingsRepository.clipboardClearTimer.first()
            delay(timer)
            // clear clipboard
            if (clipboard.primaryClip?.getItemAt(0)?.text == text) {
                clipboard.clearPrimaryClip()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (entryId == null) "New Entry" else "Edit Entry") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { isFavorite = !isFavorite }) {
                        Icon(
                            if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    if (entryId != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val newEntry = VaultEntry(
                    id = entryId ?: 0,
                    title = title.takeIf { it.isNotBlank() } ?: "Untitled",
                    username = username,
                    password = password,
                    website = website,
                    notes = notes,
                    category = category,
                    customFields = customFields,
                    isFavorite = isFavorite
                )
                if (entryId == null) {
                    viewModel.addEntry(newEntry)
                } else {
                    viewModel.updateEntry(newEntry)
                }
                navController.popBackStack()
            }) {
                Icon(Icons.Default.Save, contentDescription = "Save")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username / Email") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                if (username.isNotEmpty()) {
                    IconButton(onClick = { copyToClipboard("Username", username) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy username")
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        val icon = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(icon, contentDescription = "Toggle password visibility")
                        }
                    }
                )
                if (password.isNotEmpty()) {
                    IconButton(onClick = { copyToClipboard("Password", password) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy password")
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = website,
                    onValueChange = { website = it },
                    label = { Text("Website") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                if (website.isNotEmpty()) {
                    IconButton(onClick = { copyToClipboard("Website", website) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy website")
                    }
                }
            }
            
            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("Category") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            Text("Custom Fields", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            
            customFields.forEachIndexed { index, field ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var editKey by remember { mutableStateOf(field.key) }
                    var editValue by remember { mutableStateOf(field.value) }
                    
                    OutlinedTextField(
                        value = editKey,
                        onValueChange = { 
                            editKey = it
                            val mut = customFields.toMutableList()
                            mut[index] = field.copy(key = it)
                            customFields = mut
                        },
                        label = { Text("Key") },
                        modifier = Modifier.weight(0.4f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = editValue,
                        onValueChange = { 
                            editValue = it
                            val mut = customFields.toMutableList()
                            mut[index] = field.copy(value = it)
                            customFields = mut
                        },
                        label = { Text("Value") },
                        modifier = Modifier.weight(0.6f),
                        singleLine = true
                    )
                    IconButton(onClick = { copyToClipboard(editKey, editValue) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy value")
                    }
                    IconButton(onClick = {
                        val mut = customFields.toMutableList()
                        mut.removeAt(index)
                        customFields = mut
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                }
            }

            TextButton(onClick = {
                val mut = customFields.toMutableList()
                mut.add(CustomField("", ""))
                customFields = mut
            }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Custom Field")
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Entry") },
            text = { Text("Are you sure you want to delete this password entry? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    entryId?.let { viewModel.deleteEntry(it) }
                    showDeleteConfirm = false
                    navController.popBackStack()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
