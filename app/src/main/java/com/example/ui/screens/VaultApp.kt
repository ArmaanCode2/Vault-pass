package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
        
        NavHost(navController = navController, startDestination = "dashboard") {
            composable("dashboard") {
                DashboardScreen(viewModel, navController)
            }
            composable("add_entry") {
                PasswordEntryScreen(viewModel = viewModel, navController = navController, entryId = null)
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
