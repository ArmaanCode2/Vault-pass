package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
    val isFirstLaunch by viewModel.isFirstLaunch.collectAsStateWithLifecycle()
    val isUnlocked by viewModel.isUnlocked.collectAsStateWithLifecycle()

    if (isFirstLaunch == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (isFirstLaunch == true) {
        SetupScreen(viewModel)
    } else if (!isUnlocked) {
        LockScreen(viewModel, onShowBiometricPrompt)
    } else {
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
                        PasswordGeneratorScreen(navController)
                    }
                }
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
