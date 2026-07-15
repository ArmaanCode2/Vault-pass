# Vault Pass Mobile - Features

## Free Tier Features
*Note: All features in this application are currently available to all users. No premium tier restrictions exist in the codebase.*

### Password Management
- ✅ Create Password - Location: `com.example.ui.screens.PasswordEntryScreen` / `com.example.ui.VaultViewModel.addEntry()`
- ✅ Edit Password - Location: `com.example.ui.screens.PasswordEntryScreen` / `com.example.ui.VaultViewModel.updateEntry()`
- ✅ View Password Details - Location: `com.example.ui.screens.PasswordDetailsScreen`
- ✅ Browse & Search Passwords - Location: `com.example.ui.screens.DashboardScreen` / `com.example.ui.VaultViewModel.dashboardEntries`
- ✅ Delete Password (Soft-Delete) - Location: `com.example.repository.VaultRepository.deleteEntry()`
- ✅ Recycle Bin (Restore / Permanent Delete) - Location: `com.example.ui.screens.RecycleBinScreen` / `com.example.repository.VaultRepository.cleanupRecycleBin()`

### Authentication & Security
- ✅ Master Password Setup - Location: `com.example.ui.screens.SetupScreen` / `com.example.ui.VaultViewModel.setupMasterPassword()`
- ✅ Unlock Vault - Location: `com.example.ui.screens.LockScreen` / `com.example.ui.VaultViewModel.unlockWithPassword()`
- ✅ Biometric Unlock (Fingerprint/Face) - Location: `com.example.ui.screens.LockScreen` / `com.example.security.BiometricCryptoHelper`
- ✅ Auto-Lock Timer - Location: `com.example.ui.VaultViewModel.onStop()` / `com.example.repository.SettingsRepository.autoLockTimer`
- ✅ Brute-Force Protection & Lockout - Location: `com.example.ui.VaultViewModel.lockoutEndTime` / `com.example.repository.SettingsRepository.failedAuthAttempts`
- ✅ Clipboard Auto-Clear - Location: `com.example.ui.VaultViewModel.copyToClipboard()`

### Security Center (Analytics)
- ✅ Security Score Dashboard - Location: `com.example.ui.screens.SecurityScreen` / `com.example.domain.security.SecurityAnalyzer`
- ✅ Identify Weak Passwords - Location: `com.example.ui.screens.WeakPasswordsScreen`
- ✅ Identify Reused Passwords - Location: `com.example.ui.screens.ReusedPasswordsScreen`
- ✅ Identify Missing Passwords - Location: `com.example.ui.screens.MissingPasswordsScreen`

### Utilities & Settings
- ✅ Password Generator - Location: `com.example.ui.screens.PasswordGeneratorScreen`
- ✅ Data Export (TXT, JSON, VPEX) - Location: `com.example.ui.screens.SettingsScreen` / `com.example.ui.VaultViewModel.generateVpexExportPayload()`
- ✅ Data Import - Location: `com.example.ui.screens.SettingsScreen` / `com.example.ui.VaultViewModel.decodeImportPayload()`
- ✅ Dynamic Theme Switching (Light/Dark/System) - Location: `com.example.ui.screens.SettingsScreen` / `com.example.repository.SettingsRepository.themeMode`

## Premium Features
- ❌ Not Implemented - *There are no premium-gated features in the current codebase.*

## Feature Status Summary

| Feature | Status | Premium | Code Location |
|---------|--------|---------|---------------|
| Create Password | ✅ Done | No | `com.example.ui.screens.PasswordEntryScreen` |
| Edit Password | ✅ Done | No | `com.example.ui.screens.PasswordEntryScreen` |
| View Password | ✅ Done | No | `com.example.ui.screens.PasswordDetailsScreen` |
| Search Passwords | ✅ Done | No | `com.example.ui.screens.DashboardScreen` |
| Soft Delete / Recycle Bin | ✅ Done | No | `com.example.ui.screens.RecycleBinScreen` |
| Master Password Auth | ✅ Done | No | `com.example.ui.screens.LockScreen` |
| Biometric Unlock | ✅ Done | No | `com.example.security.BiometricCryptoHelper` |
| Auto-Lock Timer | ✅ Done | No | `com.example.ui.VaultViewModel` |
| Security Analyzer (Score) | ✅ Done | No | `com.example.domain.security.SecurityAnalyzer` |
| Password Generator | ✅ Done | No | `com.example.ui.screens.PasswordGeneratorScreen` |
| Import / Export | ✅ Done | No | `com.example.ui.screens.SettingsScreen` |
| Cloud Synchronization | ❌ Not Started | No | `[UNCLEAR - Not in codebase]` |
