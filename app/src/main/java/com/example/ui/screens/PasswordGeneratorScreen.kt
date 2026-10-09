package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.R
import com.example.domain.security.PasswordGenerator
import kotlin.math.log2

@Composable
fun PasswordGeneratorScreen(
    navController: NavController,
    viewModel: com.example.ui.VaultViewModel? = null
) {
    var length by remember { mutableFloatStateOf(18f) }
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
        var charPool = ""
        if (useUppercase) charPool += PasswordGenerator.UPPER_CHARS
        if (useLowercase) charPool += PasswordGenerator.LOWER_CHARS
        if (useNumbers) charPool += PasswordGenerator.NUMBER_CHARS
        if (useSymbols) charPool += PasswordGenerator.SYMBOL_CHARS
        
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

    // Same sets generate() draws from, so entropy and crack time aren't overstated.
    val poolSize = PasswordGenerator.poolSize(useUppercase, useLowercase, useNumbers, useSymbols)
    val entropyBits = if (poolSize > 1) (length * log2(poolSize.toDouble())).toInt() else 0
    val strengthLabel = when {
        entropyBits < 50 -> "Weak"
        entropyBits < 80 -> "Good"
        else -> "Strong"
    }
    val strengthColor = when {
        entropyBits < 50 -> MaterialTheme.colorScheme.error
        entropyBits < 80 -> Color(0xFFFFB4AB) // Orange-ish or just secondary
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val crackTime = when {
        entropyBits < 40 -> "Instantly"
        entropyBits < 60 -> "Hours"
        entropyBits < 80 -> "Months"
        entropyBits < 100 -> "Years"
        else -> "Centuries"
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
                Icon(Icons.Default.Password, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Generator", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Password Display Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("GENERATED PASSWORD", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(
                                color = strengthColor.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = strengthColor, modifier = Modifier.size(16.dp))
                                    Text(strengthLabel, style = MaterialTheme.typography.labelSmall, color = strengthColor)
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha=0.5f), RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.05f), RoundedCornerShape(8.dp))
                                .padding(16.dp)
                                .defaultMinSize(minHeight = 80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = generatedPassword,
                                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { viewModel?.copyToClipboard(context, "Password", generatedPassword) },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Copy Password")
                            }

                            OutlinedButton(
                                onClick = { generate() },
                                modifier = Modifier.size(48.dp),
                                contentPadding = PaddingValues(0.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Regenerate", tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }

                // Security Analysis Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("SECURITY ANALYSIS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        
                        // Progress Bar
                        Row(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
                            val ratio = (entropyBits / 120f).coerceIn(0f, 1f)
                            Box(modifier = Modifier.weight(ratio.coerceAtLeast(0.01f)).fillMaxHeight().background(strengthColor))
                            Box(modifier = Modifier.weight((1f - ratio).coerceAtLeast(0.01f)).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer))
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            AnalysisStat("Entropy", "$entropyBits bits", MaterialTheme.colorScheme.primary)
                            AnalysisStat("Crack Time", crackTime, MaterialTheme.colorScheme.primary)
                            AnalysisStat("Pattern", "Random", MaterialTheme.colorScheme.primary)
                            AnalysisStat("Pool", "$poolSize chars", MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // Configuration Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("CONFIGURATION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f))
                        
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Length", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                Box(
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text(length.toInt().toString(), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Slider(
                                value = length,
                                onValueChange = { length = it },
                                valueRange = 8f..64f,
                                steps = 55
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ConfigToggle("A", "Uppercase", useUppercase) { useUppercase = it }
                            ConfigToggle("a", "Lowercase", useLowercase) { useLowercase = it }
                            ConfigToggle("1", "Numbers", useNumbers) { useNumbers = it }
                            ConfigToggle("@", "Symbols", useSymbols) { useSymbols = it }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun AnalysisStat(label: String, value: String, valueColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ConfigToggle(iconLabel: String, title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha=0.4f), RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.05f), RoundedCornerShape(8.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(iconLabel, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primaryContainer)
        )
    }
}
