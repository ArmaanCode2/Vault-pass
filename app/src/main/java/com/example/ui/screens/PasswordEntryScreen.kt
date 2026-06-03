package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    val hidePasswordsByDefault by viewModel.settingsRepository.hidePasswordsByDefault.collectAsStateWithLifecycle(initialValue = true)
    
    var existingEntry by remember { mutableStateOf<VaultEntry?>(null) }
    var isLoading by remember { mutableStateOf(entryId != null) }

    var title by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var website by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Personal") }
    var isFavorite by remember { mutableStateOf(false) }
    var customFields by remember { mutableStateOf(emptyList<CustomField>()) }
    
    var passwordVisible by remember { mutableStateOf(!hidePasswordsByDefault) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(entryId) {
        if (entryId != null) {
            val loaded = viewModel.getEntryById(entryId)
            existingEntry = loaded
            if (loaded != null) {
                title = loaded.title
                username = loaded.username
                password = loaded.password
                website = loaded.website
                notes = loaded.notes
                category = loaded.category
                isFavorite = loaded.isFavorite
                customFields = loaded.customFields
            }
        }
        isLoading = false
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val copyToClipboard: (String, String) -> Unit = { label, text ->
        viewModel.copyToClipboard(context, label, text)
    }

    val isEditing = entryId != null
    
    val titleError = if (title.length > 100) "Max 100 characters" else if (title.isBlank()) "Title is required" else null
    val usernameError = if (username.length > 150) "Max 150 characters" else null
    val passwordError = if (password.length > 500) "Max 500 characters" else null
    val websiteError = if (website.length > 250) "Max 250 characters" else null
    val notesError = if (notes.length > 2000) "Max 2000 characters" else null
    
    val customFieldsErrors = customFields.map { 
        if (it.key.length > 50) "Key max 50 characters" 
        else if (it.value.length > 200) "Value max 200 characters" 
        else null
    }
    val hasCustomFieldsError = customFieldsErrors.any { it != null } || customFields.size > 20
    
    val hasErrors = titleError != null || usernameError != null || passwordError != null || websiteError != null || notesError != null || hasCustomFieldsError

    val saveEntry = {
        if (!hasErrors) {
            val newEntry = VaultEntry(
                id = entryId ?: 0,
                title = title.trim().takeIf { it.isNotBlank() } ?: "Untitled",
                username = username.trim(),
                password = password,
                website = website.trim(),
                notes = notes,
                category = category.takeIf { it.isNotBlank() } ?: "Personal",
                customFields = customFields,
                isFavorite = isFavorite
            )
            if (isEditing) {
                viewModel.updateEntry(newEntry)
            } else {
                viewModel.addEntry(newEntry)
            }
            navController.popBackStack()
        }
        Unit
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { navController.popBackStack(); Unit }) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = if (isEditing) "Edit Entry" else "New Entry",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = saveEntry, enabled = !hasErrors) {
                    val tintColor = if (!hasErrors) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.5f)
                    Icon(Icons.Default.Check, contentDescription = null, tint = tintColor)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save", color = tintColor, style = MaterialTheme.typography.titleMedium)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Category Selector
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val categories = listOf("Login" to Icons.Default.Login, "Card" to Icons.Default.CreditCard, "Secure Note" to Icons.Default.Notes)
                    categories.forEach { (catName, icon) ->
                        val isSelected = category == catName
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (!isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                            modifier = Modifier.clickable { category = catName }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(icon, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(catName, color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }

                // Core Details Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        EntryTextField(label = "Title", value = title, onValueChange = { title = it }, icon = Icons.Default.Title, placeholder = "e.g. My Bank", isError = titleError != null, errorMessage = titleError)
                        EntryTextField(label = "Website (URI)", value = website, onValueChange = { website = it }, icon = Icons.Default.Language, placeholder = "https://", isError = websiteError != null, errorMessage = websiteError)
                    }
                }

                // Credentials Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        EntryTextField(
                            label = "Username / Email",
                            value = username,
                            onValueChange = { username = it },
                            icon = Icons.Default.Person,
                            placeholder = "Username",
                            isError = usernameError != null,
                            errorMessage = usernameError,
                            trailingIcon = {
                                IconButton(onClick = { copyToClipboard("Username", username) }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        )
                        EntryTextField(
                            label = "Password",
                            value = password,
                            onValueChange = { password = it },
                            icon = Icons.Default.VpnKey,
                            placeholder = "Password",
                            isPassword = true,
                            passwordVisible = passwordVisible,
                            isError = passwordError != null,
                            errorMessage = passwordError,
                            trailingIcon = {
                                Row {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle Visibility", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { navController.navigate("generator") }) {
                                        Icon(Icons.Default.Password, contentDescription = "Generate", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        )
                    }
                }

                // Meta & Notes Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Favorite", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Switch(
                                checked = isFavorite,
                                onCheckedChange = { isFavorite = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primaryContainer)
                            )
                        }
                        
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.05f))

                        Column {
                            Text("Secure Notes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))
                            TextField(
                                value = notes,
                                onValueChange = { notes = it },
                                placeholder = { Text("Add any extra details, recovery codes, or hints here...", style = MaterialTheme.typography.bodyMedium) },
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 100.dp),
                                isError = notesError != null,
                                supportingText = notesError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF16274B),
                                    unfocusedContainerColor = Color(0xFF16274B),
                                    focusedIndicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }

                        if (customFields.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.05f))
                            customFields.forEachIndexed { index, field ->
                                val rowError = customFieldsErrors.getOrNull(index)
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        TextField(
                                            value = field.key,
                                            onValueChange = { 
                                                val mut = customFields.toMutableList()
                                                mut[index] = field.copy(key = it)
                                                customFields = mut
                                            },
                                            placeholder = { Text("Key") },
                                            modifier = Modifier.weight(0.4f),
                                            isError = field.key.length > 50,
                                            colors = TextFieldDefaults.colors(
                                                focusedContainerColor = Color(0xFF16274B), unfocusedContainerColor = Color(0xFF16274B),
                                                focusedIndicatorColor = MaterialTheme.colorScheme.primaryContainer, unfocusedIndicatorColor = Color.Transparent
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            singleLine = true
                                        )
                                        TextField(
                                            value = field.value,
                                            onValueChange = { 
                                                val mut = customFields.toMutableList()
                                                mut[index] = field.copy(value = it)
                                                customFields = mut
                                            },
                                            placeholder = { Text("Value") },
                                            modifier = Modifier.weight(0.6f),
                                            isError = field.value.length > 200,
                                            colors = TextFieldDefaults.colors(
                                                focusedContainerColor = Color(0xFF16274B), unfocusedContainerColor = Color(0xFF16274B),
                                                focusedIndicatorColor = MaterialTheme.colorScheme.primaryContainer, unfocusedIndicatorColor = Color.Transparent
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            singleLine = true
                                        )
                                        IconButton(onClick = {
                                            val mut = customFields.toMutableList()
                                            mut.removeAt(index)
                                            customFields = mut
                                        }) {
                                            Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    if (rowError != null) {
                                        Text(rowError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, start = 8.dp))
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val mut = customFields.toMutableList()
                                mut.add(CustomField("", ""))
                                customFields = mut
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Custom Field", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (isEditing) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete Entry")
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
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
                    Unit
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

@Composable
fun EntryTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    placeholder: String,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    isError: Boolean = false,
    errorMessage: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyLarge) },
            leadingIcon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = trailingIcon,
            visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = isError,
            supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF16274B),
                unfocusedContainerColor = Color(0xFF16274B),
                focusedIndicatorColor = MaterialTheme.colorScheme.primaryContainer,
                unfocusedIndicatorColor = Color.Transparent
            ),
            shape = RoundedCornerShape(8.dp)
        )
    }
}
