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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.R
import com.example.domain.security.PasswordGenerator
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
    var tags by remember { mutableStateOf(emptyList<String>()) }
    
    var passwordVisible by remember { mutableStateOf(!hidePasswordsByDefault) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var showGeneratorPopover by remember { mutableStateOf(false) }
    var genLength by remember { mutableFloatStateOf(16f) }
    var genIncludeUpper by remember { mutableStateOf(true) }
    var genIncludeLower by remember { mutableStateOf(true) }
    var genIncludeNumbers by remember { mutableStateOf(true) }
    var genIncludeSymbols by remember { mutableStateOf(true) }
    var passwordFieldHeightPx by remember { mutableIntStateOf(0) }

    val keyboardController = LocalSoftwareKeyboardController.current

    fun generatePassword(
        length: Int = genLength.toInt(),
        upper: Boolean = genIncludeUpper,
        lower: Boolean = genIncludeLower,
        nums: Boolean = genIncludeNumbers,
        syms: Boolean = genIncludeSymbols
    ): String {
        return PasswordGenerator.generatePassword(length, upper, lower, nums, syms)
    }

    fun generateAndApply() {
        password = generatePassword(
            genLength.toInt(),
            genIncludeUpper,
            genIncludeLower,
            genIncludeNumbers,
            genIncludeSymbols
        )
        passwordVisible = true
    }

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
                tags = loaded.tags
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
                tags = tags,
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
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = stringResource(if (isEditing) R.string.entry_edit_title else R.string.entry_new_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = saveEntry, enabled = !hasErrors) {
                    val tintColor = if (!hasErrors) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.5f)
                    Icon(Icons.Default.Check, contentDescription = null, tint = tintColor)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.common_save), color = tintColor, style = MaterialTheme.typography.titleMedium)
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
                    val categories = listOf(
                        stringResource(R.string.entry_category_login) to Icons.Default.Login,
                        stringResource(R.string.entry_category_card) to Icons.Default.CreditCard,
                        stringResource(R.string.entry_category_note) to Icons.Default.Notes
                    )
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
                        EntryTextField(
                            label = stringResource(R.string.common_title),
                            value = title,
                            onValueChange = { title = it },
                            icon = Icons.Default.Title,
                            placeholder = stringResource(R.string.entry_title_placeholder),
                            isError = titleError != null,
                            errorMessage = titleError,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )
                        EntryTextField(
                            label = stringResource(R.string.common_website),
                            value = website,
                            onValueChange = { website = it },
                            icon = Icons.Default.Language,
                            placeholder = stringResource(R.string.entry_website_placeholder),
                            isError = websiteError != null,
                            errorMessage = websiteError,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )
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
                            label = stringResource(R.string.entry_username_label),
                            value = username,
                            onValueChange = { username = it },
                            icon = Icons.Default.Person,
                            placeholder = stringResource(R.string.common_username),
                            isError = usernameError != null,
                            errorMessage = usernameError,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            trailingIcon = {
                                IconButton(onClick = { copyToClipboard("Username", username) }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.common_copy), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    passwordFieldHeightPx = coordinates.size.height
                                }
                        ) {
                            EntryTextField(
                                label = stringResource(R.string.common_password),
                                value = password,
                                onValueChange = { password = it },
                                icon = Icons.Default.VpnKey,
                                placeholder = stringResource(R.string.common_password),
                                isPassword = true,
                                passwordVisible = passwordVisible,
                                isError = passwordError != null,
                                errorMessage = passwordError,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { if (!hasErrors) saveEntry() }
                                ),
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Icon(
                                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = stringResource(R.string.lock_toggle_password_visibility),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = {
                                            keyboardController?.hide()
                                            val willOpen = !showGeneratorPopover
                                            showGeneratorPopover = willOpen
                                            if (willOpen && password.isEmpty()) {
                                                generateAndApply()
                                            }
                                        }) {
                                            Icon(
                                                Icons.Default.Password,
                                                contentDescription = stringResource(R.string.entry_generate_password),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            )

                            if (showGeneratorPopover) {
                                Popup(
                                    alignment = Alignment.TopEnd,
                                    offset = IntOffset(x = 0, y = passwordFieldHeightPx + 8),
                                    onDismissRequest = { showGeneratorPopover = false },
                                    properties = PopupProperties(focusable = true, dismissOnClickOutside = true)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 8.dp,
                                        shadowElevation = 8.dp,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                        modifier = Modifier.widthIn(min = 280.dp, max = 320.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            // Header / Length Row
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Length: ${genLength.toInt()}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                IconButton(
                                                    onClick = { generateAndApply() },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Refresh,
                                                        contentDescription = "Regenerate password",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }

                                            // Compact Slider
                                            Slider(
                                                value = genLength,
                                                onValueChange = {
                                                    genLength = it
                                                    generateAndApply()
                                                },
                                                valueRange = 8f..32f,
                                                steps = 24,
                                                modifier = Modifier.fillMaxWidth()
                                            )

                                            // Character Set Toggle Chips Row
                                            val activeSetsCount = listOf(genIncludeUpper, genIncludeLower, genIncludeNumbers, genIncludeSymbols).count { it }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                GeneratorToggleChip(
                                                    label = "A",
                                                    selected = genIncludeUpper,
                                                    contentDescription = "Include uppercase letters",
                                                    onClick = {
                                                        if (!genIncludeUpper || activeSetsCount > 1) {
                                                            genIncludeUpper = !genIncludeUpper
                                                            generateAndApply()
                                                        }
                                                    }
                                                )
                                                GeneratorToggleChip(
                                                    label = "a",
                                                    selected = genIncludeLower,
                                                    contentDescription = "Include lowercase letters",
                                                    onClick = {
                                                        if (!genIncludeLower || activeSetsCount > 1) {
                                                            genIncludeLower = !genIncludeLower
                                                            generateAndApply()
                                                        }
                                                    }
                                                )
                                                GeneratorToggleChip(
                                                    label = "1",
                                                    selected = genIncludeNumbers,
                                                    contentDescription = "Include numbers",
                                                    onClick = {
                                                        if (!genIncludeNumbers || activeSetsCount > 1) {
                                                            genIncludeNumbers = !genIncludeNumbers
                                                            generateAndApply()
                                                        }
                                                    }
                                                )
                                                GeneratorToggleChip(
                                                    label = "@",
                                                    selected = genIncludeSymbols,
                                                    contentDescription = "Include symbols",
                                                    onClick = {
                                                        if (!genIncludeSymbols || activeSetsCount > 1) {
                                                            genIncludeSymbols = !genIncludeSymbols
                                                            generateAndApply()
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
                                Text(stringResource(R.string.common_favorite), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Switch(
                                checked = isFavorite,
                                onCheckedChange = { isFavorite = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primaryContainer)
                            )
                        }
                        
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.05f))

                        Column {
                            Text(stringResource(R.string.common_notes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))
                            TextField(
                                value = notes,
                                onValueChange = { notes = it },
                                placeholder = { Text(stringResource(R.string.entry_notes_placeholder), style = MaterialTheme.typography.bodyMedium) },
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 100.dp),
                                isError = notesError != null,
                                supportingText = notesError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                        Text(stringResource(R.string.entry_delete_title))
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.entry_delete_title)) },
            text = { Text(stringResource(R.string.entry_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    entryId?.let { viewModel.deleteEntry(it) }
                    showDeleteConfirm = false
                    navController.popBackStack()
                    Unit
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
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
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
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
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = isError,
            supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = MaterialTheme.colorScheme.primaryContainer,
                unfocusedIndicatorColor = Color.Transparent
            ),
            shape = RoundedCornerShape(8.dp)
        )
    }
}

@Composable
private fun GeneratorToggleChip(
    label: String,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .size(36.dp)
            .semantics {
                this.contentDescription = contentDescription
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

