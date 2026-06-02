package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.ui.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) result = result?.substring(cut + 1)
    }
    return result ?: "Unknown file"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: VaultViewModel, navController: NavController) {
    val isBiometricEnabled by viewModel.settingsRepository.isBiometricEnabled.collectAsStateWithLifecycle(initialValue = false)
    val hidePasswords by viewModel.settingsRepository.hidePasswordsByDefault.collectAsStateWithLifecycle(initialValue = true)
    val disableScreenshots by viewModel.settingsRepository.disableScreenshots.collectAsStateWithLifecycle(initialValue = true)
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val themeMode by viewModel.settingsRepository.themeMode.collectAsStateWithLifecycle(initialValue = 0)
    var showThemeDialog by remember { mutableStateOf(false) }
    val themeOptions = listOf("System Default", "Light Mode", "Dark Mode")
    
    var previewEntries by remember { mutableStateOf<List<VaultEntry>?>(null) }
    var invalidCount by remember { mutableStateOf(0) }
    
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        Log.d("VaultPass", "Export launcher returned URI: $uri")
        uri?.let {
            scope.launch {
                try {
                    val entries = viewModel.getAllEntriesDecrypted()
                    val exportMap = mutableMapOf<String, List<String>>()
                    entries.forEach { entry ->
                        val values = mutableListOf<String>()
                        values.add(entry.username)
                        values.add(entry.password)
                        entry.customFields.forEach { customField -> values.add(customField.value) }
                        
                        // Handle duplicate titles by appending a number if necessary
                        var key = entry.title
                        var counter = 1
                        while (exportMap.containsKey(key)) {
                            key = "${entry.title} ($counter)"
                            counter++
                        }
                        exportMap[key] = values
                    }
                    val jsonString = Json.encodeToString(exportMap)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(it)?.use { out ->
                            out.write(jsonString.toByteArray(Charsets.UTF_8))
                        }
                    }
                    val fileName = getFileName(context, uri)
                    Log.d("VaultPass", "Export successful to file: $fileName")
                    Toast.makeText(context, "Export completed\nSaved to: $fileName", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("VaultPass", "Exception during export data generation/write", e)
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        Log.d("VaultPass", "Import launcher returned URI: $uri")
        uri?.let {
            scope.launch {
                try {
                    val jsonString = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                            reader.readText()
                        } ?: ""
                    }
                    
                    val map = Json.decodeFromString<Map<String, List<String>>>(jsonString)
                    val validEntries = mutableListOf<VaultEntry>()
                    var localInvalidCount = 0
                    
                    for ((key, values) in map) {
                        if (values.isEmpty()) {
                            localInvalidCount++
                            continue
                        }
                        val username = values.getOrNull(0) ?: ""
                        val password = values.getOrNull(1) ?: ""
                        val customFields = mutableListOf<CustomField>()
                        if (values.size > 2) {
                            for (i in 2 until values.size) {
                                customFields.add(CustomField(key = "Field ${i - 1}", value = values[i]))
                            }
                        }
                        validEntries.add(VaultEntry(
                            title = key,
                            username = username,
                            password = password,
                            customFields = customFields
                        ))
                    }
                    
                    previewEntries = validEntries
                    invalidCount = localInvalidCount
                    Log.d("VaultPass", "Import preview ready. Valid: ${validEntries.size}, Invalid: $localInvalidCount")
                    
                } catch (e: Exception) {
                    Log.e("VaultPass", "Exception during import parsing", e)
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (isImporting) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Importing Vault") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Importing entries safely in the background...")
                    }
                },
                confirmButton = {}
            )
        }
        
        if (previewEntries != null) {
            AlertDialog(
                onDismissRequest = { previewEntries = null },
                title = { Text("Import Preview") },
                text = {
                    val total = previewEntries!!.size + invalidCount
                    Text("Found $total entries.\nValid entries: ${previewEntries!!.size}\nInvalid entries: $invalidCount\n\nProceed with import?")
                },
                confirmButton = {
                    TextButton(onClick = {
                        val toImport = previewEntries!!
                        previewEntries = null
                        scope.launch {
                            try {
                                viewModel.setImporting(true)
                                viewModel.addEntries(toImport)
                                Log.d("VaultPass", "Successfully imported ${toImport.size} entries")
                                Toast.makeText(context, "Successfully imported ${toImport.size} entries", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Log.e("VaultPass", "Exception during database import", e)
                                Toast.makeText(context, "Failed to import entries: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                viewModel.setImporting(false)
                            }
                        }
                    }) {
                        Text("Confirm")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { previewEntries = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
        
        if (showThemeDialog) {
            AlertDialog(
                onDismissRequest = { showThemeDialog = false },
                title = { Text("Choose Theme") },
                text = {
                    Column {
                        themeOptions.forEachIndexed { index, option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch { viewModel.settingsRepository.setThemeMode(index) }
                                        showThemeDialog = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (themeMode == index),
                                    onClick = null
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(option)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemeDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack(); Unit }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Intro Text
                Column {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Manage your vault preferences and security configurations.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Appearance
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("APPEARANCE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF112240)),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha=0.1f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            SettingsRow(
                                title = "Theme",
                                subtitle = themeOptions[themeMode],
                                icon = Icons.Default.Palette,
                                iconColor = MaterialTheme.colorScheme.primary,
                                iconBgColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                                trailingContent = { Icon(Icons.Default.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = null) },
                                onClick = { showThemeDialog = true }
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            SettingsRow(
                                title = "Hide Passwords by Default",
                                subtitle = "Obscure passwords in lists",
                                icon = Icons.Default.VisibilityOff,
                                iconColor = MaterialTheme.colorScheme.secondary,
                                iconBgColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
                                trailingContent = { 
                                    Switch(checked = hidePasswords, onCheckedChange = { scope.launch { viewModel.settingsRepository.setHidePasswordsByDefault(it) } }) 
                                }
                            )
                        }
                    }
                }

                // Security
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SECURITY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF112240)),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha=0.1f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            SettingsRow(
                                title = "Biometric Unlock",
                                subtitle = "Use fingerprint/face to access vault",
                                icon = Icons.Default.Fingerprint,
                                iconColor = MaterialTheme.colorScheme.primary,
                                iconBgColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                                trailingContent = { 
                                    Switch(checked = isBiometricEnabled, onCheckedChange = { scope.launch { viewModel.settingsRepository.setBiometricEnabled(it) } }) 
                                }
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            SettingsRow(
                                title = "Disable Screenshots",
                                subtitle = "Prevent screen capture & recents",
                                icon = Icons.Default.Security,
                                iconColor = MaterialTheme.colorScheme.error,
                                iconBgColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                                trailingContent = { 
                                    Switch(checked = disableScreenshots, onCheckedChange = { scope.launch { viewModel.settingsRepository.setDisableScreenshots(it) } }) 
                                }
                            )
                        }
                    }
                }

                // Advanced Data
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DATA BACKUP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF112240)),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha=0.1f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            SettingsRow(
                                title = "Export Vault",
                                subtitle = "Save encrypted JSON to device",
                                icon = Icons.Default.Upload,
                                iconColor = MaterialTheme.colorScheme.secondary,
                                iconBgColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
                                trailingContent = { Icon(Icons.Default.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = null) },
                                onClick = { 
                                    try {
                                        exportLauncher.launch("vaultpass_backup.json")
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Launch failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            SettingsRow(
                                title = "Import JSON",
                                subtitle = "Restore entries from backup",
                                icon = Icons.Default.Download,
                                iconColor = MaterialTheme.colorScheme.secondary,
                                iconBgColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
                                trailingContent = { Icon(Icons.Default.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = null) },
                                onClick = { 
                                    try {
                                        importLauncher.launch(arrayOf("application/json"))
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Launch failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    iconBgColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
    trailingContent: @Composable () -> Unit,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(iconBgColor, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailingContent()
    }
}

