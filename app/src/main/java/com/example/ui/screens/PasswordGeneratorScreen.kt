package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordGeneratorScreen(navController: NavController) {
    var length by remember { mutableFloatStateOf(16f) }
    var useUppercase by remember { mutableStateOf(true) }
    var useLowercase by remember { mutableStateOf(true) }
    var useNumbers by remember { mutableStateOf(true) }
    var useSymbols by remember { mutableStateOf(true) }
    
    var generatedPassword by remember { mutableStateOf("") }
    
    val context = LocalContext.current

    fun generate() {
        if (!useUppercase && !useLowercase && !useNumbers && !useSymbols) {
            generatedPassword = ""
            return
        }
        val upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val lower = "abcdefghijklmnopqrstuvwxyz"
        val numbers = "0123456789"
        val symbols = "!@#\$%^&*()_+-=[]{}|;:,.<>?"
        
        var charPool = ""
        if (useUppercase) charPool += upper
        if (useLowercase) charPool += lower
        if (useNumbers) charPool += numbers
        if (useSymbols) charPool += symbols
        
        val random = java.security.SecureRandom()
        val pwd = StringBuilder()
        for (i in 0 until length.toInt()) {
            val randomChar = charPool[random.nextInt(charPool.length)]
            pwd.append(randomChar)
        }
        generatedPassword = pwd.toString()
    }
    
    LaunchedEffect(length, useUppercase, useLowercase, useNumbers, useSymbols) {
        generate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Password Generator") },
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = generatedPassword,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    textAlign = TextAlign.Center
                )
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Password", generatedPassword)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy")
                }
                
                FilledTonalButton(onClick = { generate() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Regenerate")
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text("Length: ${length.toInt()}", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
            Slider(
                value = length,
                onValueChange = { length = it },
                valueRange = 8f..64f,
                steps = 55
            )
            
            ListItem(
                headlineContent = { Text("Uppercase (A-Z)") },
                trailingContent = { Switch(checked = useUppercase, onCheckedChange = { useUppercase = it }) }
            )
            ListItem(
                headlineContent = { Text("Lowercase (a-z)") },
                trailingContent = { Switch(checked = useLowercase, onCheckedChange = { useLowercase = it }) }
            )
            ListItem(
                headlineContent = { Text("Numbers (0-9)") },
                trailingContent = { Switch(checked = useNumbers, onCheckedChange = { useNumbers = it }) }
            )
            ListItem(
                headlineContent = { Text("Symbols (!@#)") },
                trailingContent = { Switch(checked = useSymbols, onCheckedChange = { useSymbols = it }) }
            )
        }
    }
}
