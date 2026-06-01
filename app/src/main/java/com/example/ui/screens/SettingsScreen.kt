package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
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
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("Appearance")
            
            ListItem(
                headlineContent = { Text("Theme") },
                supportingContent = { Text(themeOptions[themeMode]) },
                modifier = Modifier.clickable { showThemeDialog = true }
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsGroup("Security")
            
            ListItem(
                headlineContent = { Text("Enable Biometrics") },
                supportingContent = { Text("Use fingerprint/face to unlock") },
                trailingContent = {
                    Switch(checked = isBiometricEnabled, onCheckedChange = { 
                        scope.launch { viewModel.settingsRepository.setBiometricEnabled(it) } 
                    })
                }
            )
            
            ListItem(
                headlineContent = { Text("Hide Passwords by Default") },
                trailingContent = {
                    Switch(checked = hidePasswords, onCheckedChange = { 
                        scope.launch { viewModel.settingsRepository.setHidePasswordsByDefault(it) } 
                    })
                }
            )
            
            ListItem(
                headlineContent = { Text("Disable Screenshots") },
                supportingContent = { Text("Prevents screenshots and recents preview") },
                trailingContent = {
                    Switch(checked = disableScreenshots, onCheckedChange = { 
                        scope.launch { viewModel.settingsRepository.setDisableScreenshots(it) } 
                    })
                }
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsGroup("Data Backup")
            
            ListItem(
                headlineContent = { Text("Export Vault") },
                supportingContent = { Text("Save encrypted JSON to device storage") },
                leadingContent = { Icon(Icons.Default.Upload, contentDescription = null) },
                modifier = Modifier.clickable {
                    try {
                        Log.d("VaultPass", "Launching export document picker")
                        exportLauncher.launch("vaultpass_backup.json")
                    } catch (e: Exception) {
                        Log.e("VaultPass", "Exception launching export picker", e)
                        Toast.makeText(context, "Launch failed: ${e.javaClass.simpleName} - ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            )
            
            ListItem(
                headlineContent = { Text("Import JSON") },
                supportingContent = { Text("Restore entries from a JSON file") },
                leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                modifier = Modifier.clickable {
                    try {
                        Log.d("VaultPass", "Launching import document picker")
                        importLauncher.launch(arrayOf("application/json"))
                    } catch (e: Exception) {
                        Log.e("VaultPass", "Exception launching import picker", e)
                        Toast.makeText(context, "Launch failed: ${e.javaClass.simpleName} - ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsGroup("About")
            
            ListItem(
                headlineContent = { Text("VaultPass Offline") },
                supportingContent = { Text("No internet access requested. All data encrypted with AES-256 GCM.") }
            )
        }
    }
}

@Composable
fun SettingsGroup(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

