package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.ui.VaultViewModel
import kotlinx.coroutines.launch

@Composable
fun LockScreen(
    viewModel: VaultViewModel,
    onShowBiometricPrompt: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val isBiometricEnabled by viewModel.settingsRepository.isBiometricEnabled.collectAsStateWithLifecycle(initialValue = false)

    var hasAutoPrompted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isBiometricEnabled) {
        if (isBiometricEnabled && !hasAutoPrompted) {
            hasAutoPrompted = true
            onShowBiometricPrompt()
        }
    }

    val lockoutEndTime by viewModel.lockoutEndTime.collectAsStateWithLifecycle()
    var lockoutSeconds by remember { mutableStateOf(0) }

    LaunchedEffect(lockoutEndTime) {
        android.util.Log.d("BruteForceDebug", "LockScreen: LaunchedEffect(lockoutEndTime=$lockoutEndTime) triggered")
        while (true) {
            val remainingMs = lockoutEndTime - System.currentTimeMillis()
            android.util.Log.d("BruteForceDebug", "LockScreen: remainingMs=$remainingMs")
            if (remainingMs > 0) {
                lockoutSeconds = (remainingMs / 1000).toInt() + 1
                kotlinx.coroutines.delay(500)
            } else {
                lockoutSeconds = 0
                break
            }
        }
    }

    val isLockedOut = lockoutSeconds > 0

    // Premium UI Background Effect (Optional ambient glows could be added to Box/Surface)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Header
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("VaultPass", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("Vault Locked", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            Spacer(modifier = Modifier.height(32.dp))

            // Glass Panel Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isBiometricEnabled) {
                        // Biometric Visualizer
                        Box(
                            modifier = Modifier
                                .size(128.dp)
                                .clickable { onShowBiometricPrompt() },
                            contentAlignment = Alignment.Center
                        ) {
                            // Inner circle
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .background(MaterialTheme.colorScheme.surface, shape = CircleShape)
                                    .border(1.dp, MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Fingerprint,
                                    contentDescription = "Use Biometrics",
                                    tint = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorMessage = null },
                        placeholder = { Text("Master Password") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isLockedOut,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primaryContainer,
                            unfocusedBorderColor = Color.Transparent,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            val icon = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(icon, contentDescription = "Toggle password visibility", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    )

                    if (isLockedOut) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val minutes = lockoutSeconds / 60
                        val seconds = lockoutSeconds % 60
                        val formattedTime = String.format("%02d:%02d", minutes, seconds)
                        Text("Too many failed attempts. Try again in $formattedTime", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val isUnlocking by viewModel.isUnlocking.collectAsStateWithLifecycle()
                    val coroutineScope = rememberCoroutineScope()
                    val isButtonDisabled = isUnlocking || isLockedOut
                    android.util.Log.d("BruteForceDebug", "LockScreen: Recomposition - isUnlocking=$isUnlocking, isLockedOut=$isLockedOut, isButtonDisabled=$isButtonDisabled")

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val result = viewModel.unlockWithPassword(password)
                                if (result == com.example.ui.AuthResult.INVALID_PASSWORD) {
                                    errorMessage = "Incorrect master password"
                                }
                            }
                        },
                        enabled = !isButtonDisabled,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        if (isUnlocking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.LockOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Unlock", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    

                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.5f), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("End-to-End Encrypted", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.5f))
            }
        }
    }
}
