# Vault Pass Mobile - Detailed Features

## Password Management

### Create Password
- **Status**: ✅ Implemented
- **Location**: `com.example.ui.screens.PasswordEntryScreen` / `com.example.ui.VaultViewModel.addEntry()`
- **Description**: User can create a new password entry into their encrypted vault.
- **Fields**: Title, Username/Email, Password, Website/URL, Notes, Category, Tags (List), Custom Fields (Key-Value map).
- **Validation**: Title is mandatory and cannot be blank. All string inputs are trimmed.
- **Encryption**: Immediately encrypted at the `VaultRepository` level before database insertion using the in-memory AES/GCM Software DEK.

### Edit Password
- **Status**: ✅ Implemented
- **Location**: `com.example.ui.screens.PasswordEntryScreen` / `com.example.ui.VaultViewModel.updateEntry()`
- **Key Logic**: Re-uses the Create screen. Compares the original `id` to overwrite the existing database entity rather than creating a new one.

### Delete Password
- **Status**: ✅ Implemented
- **Soft Delete or Permanent?**: Soft Delete (Version 2 Database migration).
- **Recovery**: Soft deleted items are moved to a Recycle Bin (`isDeleted = true`) and can be restored or permanently purged by the user manually, or cleared in bulk by the system after 7 days.

### Search Passwords
- **Status**: ✅ Implemented
- **Algorithm**: Linear, tokenized in-memory search across decrypted strings. Splits the query by whitespace and ensures every token (`keywords.all { q -> ... }`) matches at least one field.
- **Fields Searched**: Title, Username, Website, Notes, Category, Tags, Custom Fields. (Falls back to Title and Username only if the full decryption pass is still running in the background).

## Security Features

### Biometric Unlock
- **Status**: ✅ Implemented
- **Location**: `com.example.security.BiometricCryptoHelper`
- **Methods**: Fingerprint, Face Recognition (Dependent on device `BIOMETRIC_STRONG` capabilities).

### Master Password
- **Status**: ✅ Implemented
- **Setup**: Configured on initial app launch. The raw password is never saved; it is passed through PBKDF2WithHmacSHA256 (300k iterations) and the derived KEK encrypts the randomly generated Software DEK.
- **Change Password**: ❌ Not Implemented (Currently missing from Settings).

### Auto Lock/Logout
- **Status**: ✅ Implemented
- **Timeout**: Configurable via settings. Defaults to `60000L` (1 minute) of background inactivity.
- **Location**: `com.example.ui.VaultViewModel.onStop()` and `com.example.repository.SettingsRepository.autoLockTimer`.

### Clipboard Auto-Clear
- **Status**: ✅ Implemented
- **Duration**: Configurable. Defaults to `30000L` (30 seconds) after a copy action.
- **Location**: `com.example.ui.VaultViewModel.copyToClipboard()` using `kotlinx.coroutines.delay`.

## Theming

### Available Themes
- Light: ✅ Implemented
- Dark: ✅ Implemented
- System: ✅ Implemented
- Custom Accent Colors: ✅ Implemented (Stored as string enums like "BLUE", "RED", "GREEN").

### Theme Configuration
- Configuration File: Jetpack Compose logic within `com.example.ui.theme.Theme.kt` and `Color.kt`
- Theme Change Location: `com.example.ui.screens.SettingsScreen`
- Storage: `androidx.datastore.preferences` (`THEME_MODE` and `ACCENT_COLOR` in `SettingsRepository`).
