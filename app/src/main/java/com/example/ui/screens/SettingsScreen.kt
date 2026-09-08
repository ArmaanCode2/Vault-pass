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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.R
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
    val accentColorName by viewModel.settingsRepository.accentColor.collectAsStateWithLifecycle(initialValue = "BLUE")
    val themeOptions = listOf("System Default", "Light Mode", "Dark Mode")
    var isAppearanceExpanded by remember { mutableStateOf(false) }
    var isBiometricEnabling by remember { mutableStateOf(false) }

    var autoLockExpanded by remember { mutableStateOf(false) }
    val autoLockTimer by viewModel.settingsRepository.autoLockTimer.collectAsStateWithLifecycle(initialValue = 60000L)
    val autoLockOptions = mapOf(
        0L to stringResource(R.string.settings_auto_lock_immediately),
        30000L to stringResource(R.string.settings_auto_lock_30s),
        60000L to stringResource(R.string.settings_auto_lock_1m),
        300000L to stringResource(R.string.settings_auto_lock_5m),
        900000L to stringResource(R.string.settings_auto_lock_15m),
        -1L to stringResource(R.string.settings_auto_lock_never)
    )
    
    var previewEntries by remember { mutableStateOf<List<VaultEntry>?>(null) }
    var invalidCount by remember { mutableStateOf(0) }
    
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var showExportFormatDialog by remember { mutableStateOf(false) }
    var pendingExportFormat by remember { mutableStateOf("vpex") }
    var exportPasswordVisible by remember { mutableStateOf(false) }
    
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }
    var pendingImportBytes by remember { mutableStateOf<ByteArray?>(null) }

    fun processDecodedImport(jsonString: String) {
        try {
            val (validEntries, localInvalidCount) = viewModel.decodeImportPayload(jsonString)
            previewEntries = validEntries
            invalidCount = localInvalidCount
        } catch (e: Exception) {
            Toast.makeText(context, "Import parsing failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        viewModel.setPerformingSystemOperation(false)
        if (com.example.BuildConfig.DEBUG) Log.d("VaultPass", "Export launcher returned URI: $uri")
        uri?.let {
            scope.launch {
                try {
                    val payload = when (pendingExportFormat) {
                        "txt" -> viewModel.generateTxtExportPayload()
                        "json" -> viewModel.generateSimplifiedJsonExportPayload()
                        else -> viewModel.generateVpexExportPayload(exportPassword)
                    }
                    withContext(Dispatchers.IO) {
                        val outputStream = context.contentResolver.openOutputStream(it)
                            ?: throw java.io.IOException("Unable to open output stream for destination: $it")
                        outputStream.use { out ->
                            out.write(payload)
                            out.flush()
                        }
                    }
                    val fileName = getFileName(context, uri)
                    if (com.example.BuildConfig.DEBUG) Log.d("VaultPass", "Secure export successful to file: $fileName")
                    Toast.makeText(context, "Secure export completed\nSaved to: $fileName", Toast.LENGTH_LONG).show()
                    exportPassword = ""
                } catch (e: Exception) {
                    if (com.example.BuildConfig.DEBUG) Log.e("VaultPass", "Exception during export data generation/write", e)
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        viewModel.setPerformingSystemOperation(false)
        if (com.example.BuildConfig.DEBUG) Log.d("VaultPass", "Import launcher returned URI: $uri")
        uri?.let {
            scope.launch {
                try {
                    val fileContent = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                            reader.readText()
                        } ?: ""
                    }
                    
                    try {
                        // Test if it's plaintext JSON or TXT
                        if (fileContent.trimStart().startsWith("{") || fileContent.trimStart().startsWith("Title:") || fileContent.trimStart().startsWith("[")) {
                            processDecodedImport(fileContent)
                        } else {
                            throw Exception("Not plaintext")
                        }
                    } catch (e: Exception) {
                        // Probably encrypted Base64
                        try {
                            val bytes = android.util.Base64.decode(fileContent, android.util.Base64.NO_WRAP)
                            pendingImportBytes = bytes
                            showImportPasswordDialog = true
                        } catch (e2: Exception) {
                            Toast.makeText(context, "Invalid backup file format", Toast.LENGTH_SHORT).show()
                        }
                    }
                    
                } catch (e: Exception) {
                    if (com.example.BuildConfig.DEBUG) Log.e("VaultPass", "Exception during import parsing", e)
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
        if (showExportFormatDialog) {
            AlertDialog(
                onDismissRequest = { showExportFormatDialog = false },
                title = { Text(stringResource(R.string.settings_choose_export_format)) },
                text = {
                    val formats = listOf(
                        "txt" to stringResource(R.string.settings_export_txt),
                        "json" to stringResource(R.string.settings_export_json),
                        "vpex" to stringResource(R.string.settings_export_vpex)
                    )
                    Column {
                        formats.forEach { (formatKey, label) ->
                            val isSelected = (pendingExportFormat == formatKey)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                                    .background(
                                        color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        pendingExportFormat = formatKey
                                        showExportFormatDialog = false
                                        if (formatKey == "vpex") {
                                            showExportPasswordDialog = true
                                        } else {
                                            try {
                                                viewModel.setPerformingSystemOperation(true)
                                                exportLauncher.launch("vaultpass_backup.$formatKey")
                                            } catch (e: Exception) {}
                                        }
                                    }
                                    .padding(vertical = 12.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        pendingExportFormat = formatKey
                                        showExportFormatDialog = false
                                        if (formatKey == "vpex") {
                                            showExportPasswordDialog = true
                                        } else {
                                            try {
                                                viewModel.setPerformingSystemOperation(true)
                                                exportLauncher.launch("vaultpass_backup.$formatKey")
                                            } catch (e: Exception) {}
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showExportFormatDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }

        if (showExportPasswordDialog) {
            AlertDialog(
                onDismissRequest = { showExportPasswordDialog = false },
                title = { Text(stringResource(R.string.settings_secure_export)) },
                text = {
                    Column {
                        Text(stringResource(R.string.settings_export_password_hint))
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = exportPassword,
                            onValueChange = { exportPassword = it },
                            label = { Text(stringResource(R.string.settings_backup_password)) },
                            singleLine = true,
                            visualTransformation = if (exportPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { exportPasswordVisible = !exportPasswordVisible }) {
                                    Icon(
                                        imageVector = if (exportPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (exportPasswordVisible) "Hide password" else "Show password"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (exportPassword.length >= 8) {
                                showExportPasswordDialog = false
                                try {
                                    viewModel.setPerformingSystemOperation(true)
                                    exportLauncher.launch("vaultpass_backup.vpex")
                                } catch (e: Exception) {
                                    viewModel.setPerformingSystemOperation(false)
                                    Toast.makeText(context, "Launch failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                Toast.makeText(context, "Password must be at least 8 characters", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.settings_export))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExportPasswordDialog = false; exportPassword = "" }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        if (showImportPasswordDialog) {
            AlertDialog(
                onDismissRequest = { showImportPasswordDialog = false },
                title = { Text(stringResource(R.string.settings_unlock_backup)) },
                text = {
                    Column {
                        Text(stringResource(R.string.settings_unlock_backup_hint))
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = importPassword,
                            onValueChange = { importPassword = it },
                            label = { Text(stringResource(R.string.settings_backup_password)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (importPassword.isNotEmpty() && pendingImportBytes != null) {
                                val jsonString = com.example.security.CryptoManager.decryptBackup(pendingImportBytes!!, importPassword)
                                if (jsonString != null) {
                                    showImportPasswordDialog = false
                                    importPassword = ""
                                    pendingImportBytes = null
                                    processDecodedImport(jsonString)
                                } else {
                                    Toast.makeText(context, "Incorrect password or corrupted backup", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.settings_unlock_import))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportPasswordDialog = false; importPassword = ""; pendingImportBytes = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
        
        if (isImporting) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.settings_importing)) },
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
                                if (com.example.BuildConfig.DEBUG) Log.d("VaultPass", "Successfully imported ${toImport.size} entries")
                                Toast.makeText(context, "Successfully imported ${toImport.size} entries", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                if (com.example.BuildConfig.DEBUG) Log.e("VaultPass", "Exception during database import", e)
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
                        Text(stringResource(R.string.common_cancel))
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
                    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Manage your vault preferences and security configurations.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Appearance
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            SettingsRow(
                                title = stringResource(R.string.settings_appearance),
                                subtitle = "${stringResource(R.string.settings_theme)} and ${stringResource(R.string.settings_accent_color)}",
                                icon = Icons.Default.Palette,
                                iconColor = MaterialTheme.colorScheme.primary,
                                iconBgColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                                trailingContent = {
                                    Icon(
                                        if (isAppearanceExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        contentDescription = null
                                    )
                                },
                                onClick = { isAppearanceExpanded = !isAppearanceExpanded }
                            )
                            androidx.compose.animation.AnimatedVisibility(visible = isAppearanceExpanded) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // Theme Mode Dropdown
                                    var themeExpanded by remember { mutableStateOf(false) }
                                    ExposedDropdownMenuBox(
                                        expanded = themeExpanded,
                                        onExpandedChange = { themeExpanded = !themeExpanded }
                                    ) {
                                        OutlinedTextField(
                                            value = themeOptions[themeMode],
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text(stringResource(R.string.settings_theme)) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeExpanded) },
                                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                            modifier = Modifier.menuAnchor().fillMaxWidth()
                                        )
                                        ExposedDropdownMenu(
                                            expanded = themeExpanded,
                                            onDismissRequest = { themeExpanded = false }
                                        ) {
                                            themeOptions.forEachIndexed { index, option ->
                                                DropdownMenuItem(
                                                    text = { Text(option) },
                                                    onClick = {
                                                        scope.launch { viewModel.settingsRepository.setThemeMode(index) }
                                                        themeExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    
                                    // Accent Color Dropdown
                                    var accentExpanded by remember { mutableStateOf(false) }
                                    val accentOptions = com.example.ui.theme.AccentColor.values()
                                    val currentAccent = try { com.example.ui.theme.AccentColor.valueOf(accentColorName) } catch (e: Exception) { com.example.ui.theme.AccentColor.BLUE }
                                    
                                    ExposedDropdownMenuBox(
                                        expanded = accentExpanded,
                                        onExpandedChange = { accentExpanded = !accentExpanded }
                                    ) {
                                        OutlinedTextField(
                                            value = currentAccent.title,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text(stringResource(R.string.settings_accent_color)) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accentExpanded) },
                                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                            modifier = Modifier.menuAnchor().fillMaxWidth()
                                        )
                                        ExposedDropdownMenu(
                                            expanded = accentExpanded,
                                            onDismissRequest = { accentExpanded = false }
                                        ) {
                                            accentOptions.forEach { colorOption ->
                                                DropdownMenuItem(
                                                    text = { 
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Box(modifier = Modifier.size(16.dp).background(colorOption.lightPrimary, RoundedCornerShape(8.dp)))
                                                             Spacer(modifier = Modifier.width(12.dp))
                                                            Text(colorOption.title) 
                                                        }
                                                    },
                                                    onClick = {
                                                        scope.launch { viewModel.settingsRepository.setAccentColor(colorOption.name) }
                                                        accentExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            SettingsRow(
                                title = stringResource(R.string.settings_hide_passwords),
                                subtitle = stringResource(R.string.settings_hide_passwords_subtitle),
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
                    Text(stringResource(R.string.settings_security), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            SettingsRow(
                                title = stringResource(R.string.settings_biometric_unlock),
                                subtitle = stringResource(R.string.settings_biometric_subtitle),
                                icon = Icons.Default.Fingerprint,
                                iconColor = MaterialTheme.colorScheme.primary,
                                iconBgColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                                trailingContent = { 
                                    Switch(
                                        checked = isBiometricEnabled || isBiometricEnabling, 
                                        onCheckedChange = { isEnabled -> 
                                            if (isEnabled) {
                                                val biometricManager = androidx.biometric.BiometricManager.from(context)
                                                val canAuth = biometricManager.canAuthenticate(
                                                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
                                                )
                                                when (canAuth) {
                                                    androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> { /* proceed */ }
                                                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                                                        android.widget.Toast.makeText(context, "This device does not have biometric hardware", android.widget.Toast.LENGTH_LONG).show()
                                                        return@Switch
                                                    }
                                                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                                                        android.widget.Toast.makeText(context, "No biometrics enrolled. Please add a fingerprint or face in device Settings", android.widget.Toast.LENGTH_LONG).show()
                                                        return@Switch
                                                    }
                                                    else -> {
                                                        android.widget.Toast.makeText(context, "Biometric authentication is not available", android.widget.Toast.LENGTH_LONG).show()
                                                        return@Switch
                                                    }
                                                }

                                                isBiometricEnabling = true

                                                val fragmentActivity = context as? androidx.fragment.app.FragmentActivity
                                                if (fragmentActivity == null) {
                                                    isBiometricEnabling = false
                                                    android.widget.Toast.makeText(context, "Cannot show biometric prompt on this device", android.widget.Toast.LENGTH_SHORT).show()
                                                    return@Switch
                                                }
                                                try {
                                                    com.example.security.BiometricCryptoHelper.generateBiometricKey()
                                                    com.example.security.BiometricCryptoHelper.generateBiometricAesKey()
                                                    
                                                    val cipher = com.example.security.BiometricCryptoHelper.getEncryptCipherForBiometric()
                                                    if (cipher == null) {
                                                        isBiometricEnabling = false
                                                        android.widget.Toast.makeText(context, "Failed to initialize biometric encryption", android.widget.Toast.LENGTH_SHORT).show()
                                                        return@Switch
                                                    }
                                                    
                                                    val executor = androidx.core.content.ContextCompat.getMainExecutor(context)
                                                    val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                                                        .setTitle("Enable Biometric Unlock")
                                                        .setSubtitle("Authenticate to secure your vault")
                                                        .setNegativeButtonText("Cancel")
                                                        .build()
                                                        
                                                    val biometricPrompt = androidx.biometric.BiometricPrompt(fragmentActivity, executor,
                                                        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                                                            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                                                                super.onAuthenticationSucceeded(result)
                                                                scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                                                                    try {
                                                                        val authCipher = result.cryptoObject?.cipher ?: throw IllegalStateException("Cipher null")
                                                                        val softwareDek = viewModel.vaultRepository.getSoftwareDek() ?: throw IllegalStateException("Vault is locked")
                                                                        val iv = authCipher.iv
                                                                        val encryptedData = authCipher.doFinal(softwareDek)
                                                                        val combined = iv + encryptedData
                                                                        val dekBioWrapped = android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
                                                                        
                                                                        viewModel.settingsRepository.saveDekBioWrappedSync(dekBioWrapped)
                                                                        viewModel.settingsRepository.setBiometricEnabled(true)
                                                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                                            isBiometricEnabling = false
                                                                        }
                                                                    } catch (e: Exception) {
                                                                        if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                                                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                                            isBiometricEnabling = false
                                                                            android.widget.Toast.makeText(context, "Encryption failed: ${e.message ?: e.toString()}", android.widget.Toast.LENGTH_LONG).show()
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                            
                                                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                                                isBiometricEnabling = false
                                                                android.widget.Toast.makeText(context, "Auth error: $errString", android.widget.Toast.LENGTH_SHORT).show()
                                                            }
                                                        })
                                                        
                                                    biometricPrompt.authenticate(promptInfo, androidx.biometric.BiometricPrompt.CryptoObject(cipher))
                                                    
                                                } catch (e: Exception) {
                                                    isBiometricEnabling = false
                                                    if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                                                    android.widget.Toast.makeText(context, "Biometric setup failed: ${e.message ?: e.toString()}", android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            } else {
                                                scope.launch { viewModel.settingsRepository.setBiometricEnabled(false) }
                                            }
                                        }
                                    ) 
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            SettingsRow(
                                title = stringResource(R.string.settings_disable_screenshots),
                                subtitle = stringResource(R.string.settings_disable_screenshots_subtitle),
                                icon = Icons.Default.Security,
                                iconColor = MaterialTheme.colorScheme.error,
                                iconBgColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                                trailingContent = { 
                                    Switch(checked = disableScreenshots, onCheckedChange = { scope.launch { viewModel.settingsRepository.setDisableScreenshots(it) } }) 
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            SettingsRow(
                                title = stringResource(R.string.settings_auto_lock),
                                subtitle = autoLockOptions[autoLockTimer] ?: "Unknown",
                                icon = Icons.Default.Timer,
                                iconColor = MaterialTheme.colorScheme.tertiary,
                                iconBgColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f),
                                trailingContent = {},
                                onClick = { autoLockExpanded = true }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            val autofillManager = context.getSystemService(android.view.autofill.AutofillManager::class.java)
                            val isAutofillEnabled = autofillManager?.hasEnabledAutofillServices() == true
                            
                            SettingsRow(
                                title = stringResource(R.string.settings_autofill_enable),
                                subtitle = if (isAutofillEnabled) stringResource(R.string.settings_autofill_enabled) else stringResource(R.string.settings_autofill_subtitle),
                                icon = Icons.Default.Edit,
                                iconColor = if (isAutofillEnabled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                                iconBgColor = if (isAutofillEnabled) Color(0xFF2E7D32).copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                                trailingContent = { Icon(Icons.Default.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = null) },
                                onClick = {
                                    if (isAutofillEnabled) {
                                        android.widget.Toast.makeText(context, "VaultPass is already your Autofill provider!", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                                        intent.data = android.net.Uri.parse("package:${context.packageName}")
                                        try {
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "Cannot open autofill settings", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_autofill_warning),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                            if (autoLockExpanded) {
                                AlertDialog(
                                    onDismissRequest = { autoLockExpanded = false },
                                    title = { Text(stringResource(R.string.settings_choose_auto_lock)) },
                                    text = {
                                        Column {
                                            autoLockOptions.forEach { (time, label) ->
                                                val isSelected = (time == autoLockTimer)
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 4.dp)
                                                        .background(
                                                            color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent,
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable(
                                                            onClick = {
                                                                scope.launch { viewModel.settingsRepository.setAutoLockTimer(time) }
                                                                autoLockExpanded = false
                                                            }
                                                        )
                                                        .padding(vertical = 12.dp, horizontal = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    RadioButton(
                                                        selected = isSelected,
                                                        onClick = {
                                                            scope.launch { viewModel.settingsRepository.setAutoLockTimer(time) }
                                                            autoLockExpanded = false
                                                        }
                                                    )
                                                    Spacer(modifier = Modifier.width(16.dp))
                                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {},
                                    dismissButton = {
                                        TextButton(onClick = { autoLockExpanded = false }) { Text(stringResource(R.string.common_cancel)) }
                                    }
                                )
                            }
                        }
                    }
                }

                // Advanced Data
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_data_backup), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            SettingsRow(
                                title = stringResource(R.string.settings_recycle_bin),
                                subtitle = stringResource(R.string.settings_recycle_bin_subtitle),
                                icon = Icons.Default.DeleteOutline,
                                iconColor = MaterialTheme.colorScheme.error,
                                iconBgColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                                trailingContent = { Icon(Icons.Default.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = null) },
                                onClick = { navController.navigate("recycle_bin") }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            SettingsRow(
                                title = stringResource(R.string.settings_export),
                                subtitle = stringResource(R.string.settings_export_subtitle),
                                icon = Icons.Default.Upload,
                                iconColor = MaterialTheme.colorScheme.secondary,
                                iconBgColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
                                trailingContent = { Icon(Icons.Default.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = null) },
                                onClick = { 
                                    showExportFormatDialog = true
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            SettingsRow(
                                title = stringResource(R.string.settings_import),
                                subtitle = stringResource(R.string.settings_import_subtitle),
                                icon = Icons.Default.Download,
                                iconColor = MaterialTheme.colorScheme.secondary,
                                iconBgColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
                                trailingContent = { Icon(Icons.Default.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = null) },
                                onClick = { 
                                    try {
                                        viewModel.setPerformingSystemOperation(true)
                                        importLauncher.launch(arrayOf("*/*"))
                                    } catch (e: Exception) {
                                        viewModel.setPerformingSystemOperation(false)
                                        Toast.makeText(context, "Launch failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }
                    }
                }

                // Sync & Devices
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_sync), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SettingsRow(
                            title = stringResource(R.string.settings_device_sync),
                            subtitle = stringResource(R.string.settings_device_sync_subtitle),
                            icon = Icons.Default.Sync,
                            iconColor = MaterialTheme.colorScheme.primary,
                            iconBgColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                            trailingContent = { Icon(Icons.Default.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = null) },
                            onClick = { navController.navigate("lan_sync") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))

                Text(
                    text = stringResource(R.string.common_privacy_policy),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(com.example.util.Constants.PRIVACY_POLICY_URL))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                            }
                        }
                        .padding(16.dp)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    iconBgColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
    trailingContent: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = { onLongClick?.invoke() },
                        role = Role.Button
                    )
                } else Modifier
            )
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

