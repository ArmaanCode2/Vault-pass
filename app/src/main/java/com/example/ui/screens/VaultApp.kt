package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.example.R
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.VaultViewModel

@Composable
fun VaultApp(
    viewModel: VaultViewModel,
    onShowBiometricPrompt: () -> Unit
) {
    val launchState by viewModel.launchState.collectAsStateWithLifecycle()
    val isUnlocked by viewModel.isUnlocked.collectAsStateWithLifecycle()

    if (launchState == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (launchState == com.example.ui.VaultLaunchState.SETUP) {
        SetupScreen(viewModel)
    } else if (launchState == com.example.ui.VaultLaunchState.UNREADABLE && !isUnlocked) {
        // F17: a settings read error never shows Setup (which would overwrite the vault key).
        VaultUnreadableScreen(onRetry = { viewModel.retryLaunchCheck() })
    } else if (!isUnlocked) {
        LockScreen(viewModel, onShowBiometricPrompt)
    } else {
        val migrationUnreadable by viewModel.migrationUnreadableCount.collectAsStateWithLifecycle()
        migrationUnreadable?.let { count ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissMigrationNotice() },
                title = { Text(stringResource(R.string.migration_partial_title)) },
                text = { Text(stringResource(R.string.migration_partial_message, count)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissMigrationNotice() }) {
                        Text(stringResource(R.string.migration_partial_ok))
                    }
                }
            )
        }
        rememberUpdateController()?.let { UpdateDialogHost(it) }
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route?.substringBefore("/")
        val showBottomNav = currentRoute in listOf("dashboard", "security", "generator", "settings")
        
        Scaffold(
            bottomBar = {
                if (showBottomNav) {
                    VaultBottomNavigation(navController = navController, currentRoute = currentRoute)
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                NavHost(navController = navController, startDestination = "dashboard") {
                    composable("dashboard") {
                        DashboardScreen(viewModel, navController)
                    }
                    composable("add_entry") {
                        PasswordEntryScreen(viewModel = viewModel, navController = navController, entryId = null)
                    }
                    composable("recycle_bin") {
                        RecycleBinScreen(viewModel = viewModel, navController = navController)
                    }
                    composable("entry_details/{entryId}") { backStackEntry ->
                        val id = backStackEntry.arguments?.getString("entryId")?.toIntOrNull()
                        PasswordDetailsScreen(viewModel = viewModel, navController = navController, entryId = id)
                    }
                    composable("edit_entry/{entryId}") { backStackEntry ->
                        val id = backStackEntry.arguments?.getString("entryId")?.toIntOrNull()
                        PasswordEntryScreen(viewModel = viewModel, navController = navController, entryId = id)
                    }
                    composable("settings") {
                        SettingsScreen(viewModel, navController)
                    }
                    composable("security") {
                        SecurityScreen(viewModel, navController)
                    }
                    composable("weak_passwords") {
                        WeakPasswordsScreen(viewModel, navController)
                    }
                    composable("reused_passwords") {
                        ReusedPasswordsScreen(viewModel, navController)
                    }
                    composable("missing_passwords") {
                        MissingPasswordsScreen(viewModel, navController)
                    }
                    composable("generator") {
                        PasswordGeneratorScreen(navController, viewModel)
                    }
                    composable("lan_sync") {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val app = context.applicationContext as com.example.VaultPassApplication
                        val lanSyncViewModel: com.example.ui.sync.LanSyncViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                            factory = com.example.ui.sync.LanSyncViewModelFactory(
                                pairedDeviceRepository = app.container.pairedDeviceRepository,
                                lanDiscoveryManager = app.container.lanDiscoveryManager,
                                lanSocketTransport = app.container.lanSocketTransport,
                                vaultRepository = app.container.vaultRepository,
                                isVaultUnlocked = viewModel.isUnlocked
                            )
                        )
                        com.example.ui.sync.LanSyncScreen(
                            viewModel = lanSyncViewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToScanner = { navController.navigate("sync_scanner") },
                            onNavigateToReview = { navController.navigate("sync_review") }
                        )
                    }
                    composable("sync_scanner") {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val app = context.applicationContext as com.example.VaultPassApplication
                        com.example.ui.sync.CameraQrScanScreen(
                            lanSocketTransport = app.container.lanSocketTransport,
                            lanDiscoveryManager = app.container.lanDiscoveryManager,
                            onNavigateBack = { navController.popBackStack() },
                            onPairingSuccess = { navController.popBackStack() }
                        )
                    }
                    composable("sync_review") {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val app = context.applicationContext as com.example.VaultPassApplication
                        val parentEntry: androidx.navigation.NavBackStackEntry? = androidx.compose.runtime.remember(navController) {
                            try {
                                navController.getBackStackEntry("lan_sync")
                            } catch (_: Exception) {
                                null
                            }
                        }
                        val lanSyncViewModel: com.example.ui.sync.LanSyncViewModel = if (parentEntry != null) {
                            androidx.lifecycle.viewmodel.compose.viewModel(
                                viewModelStoreOwner = parentEntry,
                                factory = com.example.ui.sync.LanSyncViewModelFactory(
                                    pairedDeviceRepository = app.container.pairedDeviceRepository,
                                    lanDiscoveryManager = app.container.lanDiscoveryManager,
                                    lanSocketTransport = app.container.lanSocketTransport,
                                    vaultRepository = app.container.vaultRepository,
                                    isVaultUnlocked = viewModel.isUnlocked
                                )
                            )
                        } else {
                            androidx.lifecycle.viewmodel.compose.viewModel(
                                factory = com.example.ui.sync.LanSyncViewModelFactory(
                                    pairedDeviceRepository = app.container.pairedDeviceRepository,
                                    lanDiscoveryManager = app.container.lanDiscoveryManager,
                                    lanSocketTransport = app.container.lanSocketTransport,
                                    vaultRepository = app.container.vaultRepository,
                                    isVaultUnlocked = viewModel.isUnlocked
                                )
                            )
                        }
                        com.example.ui.sync.SyncReviewScreen(
                            viewModel = lanSyncViewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onSyncCompleted = { navController.popBackStack("lan_sync", inclusive = false) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultUnreadableScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.vault_unreadable_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                stringResource(R.string.vault_unreadable_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.vault_unreadable_retry))
            }
        }
    }
}

@Composable
fun VaultBottomNavigation(navController: NavController, currentRoute: String?) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 8.dp
    ) {
        NavigationBarItem(
            selected = currentRoute == "dashboard",
            onClick = {
                navController.navigate("dashboard") {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            icon = { Icon(Icons.Default.Lock, contentDescription = "Vault") },
            label = { Text("Vault") }
        )
        NavigationBarItem(
            selected = currentRoute == "security",
            onClick = {
                navController.navigate("security") {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            icon = { Icon(Icons.Default.Shield, contentDescription = "Security") },
            label = { Text("Security") }
        )
        NavigationBarItem(
            selected = currentRoute == "generator",
            onClick = {
                navController.navigate("generator") {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            icon = { Icon(Icons.Default.VpnKey, contentDescription = "Generator") },
            label = { Text("Generator") }
        )
        NavigationBarItem(
            selected = currentRoute == "settings",
            onClick = {
                navController.navigate("settings") {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") }
        )
    }
}
