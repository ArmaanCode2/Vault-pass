package com.example.ui.screens

import android.net.Uri
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
import com.example.domain.models.VaultEntry
import com.example.ui.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: VaultViewModel, navController: NavController) {
    val isBiometricEnabled by viewModel.settingsRepository.isBiometricEnabled.collectAsStateWithLifecycle(initialValue = false)
    val hidePasswords by viewModel.settingsRepository.hidePasswordsByDefault.collectAsStateWithLifecycle(initialValue = true)
    val disableScreenshots by viewModel.settingsRepository.disableScreenshots.collectAsStateWithLifecycle(initialValue = true)
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val entries = viewModel.entries.value
                    val jsonString = Json.encodeToString(entries)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(it)?.use { out ->
                            out.write(jsonString.toByteArray(Charsets.UTF_8))
                        }
                    }
                    Toast.makeText(context, "Exported successfully", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val jsonString = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                            reader.readText()
                        } ?: ""
                    }
                    val importedEntries = Json.decodeFromString<List<VaultEntry>>(jsonString)
                    var count = 0
                    importedEntries.forEach { entry ->
                        // Add as new entry to prevent ID conflicts
                        viewModel.addEntry(entry.copy(id = 0))
                        count++
                    }
                    Toast.makeText(context, "Imported $count entries", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed. Invalid format.", Toast.LENGTH_SHORT).show()
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
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
                modifier = Modifier.clickable { exportLauncher.launch("vaultpass_backup.json") }
            )
            
            ListItem(
                headlineContent = { Text("Import JSON") },
                supportingContent = { Text("Restore entries from a JSON file") },
                leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json", "*/*")) }
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

