# Vault Pass Mobile - Detailed Features

## Password Management

### Create Password
- **Status**: ✅ Implemented
- **Location**: `com.example.ui.screens.PasswordEntryScreen` / `com.example.ui.VaultViewModel.addEntry()`
- **Description**: User can create a new password entry into their encrypted vault.
- **Fields**: Title, Username/Email, Password, Website/URL, Notes, Category, Tags (List), Custom Fields (Key-Value map).
- **Validation**: Title is mandatory and cannot be blank. Title, username and website are trimmed; password and notes are saved exactly as typed.
- **Encryption**: Immediately encrypted at the `VaultRepository` level before database insertion using the in-memory AES/GCM Software DEK.

### Edit Password
- **Status**: ✅ Implemented
- **Location**: `com.example.ui.screens.PasswordEntryScreen` / `com.example.ui.VaultViewModel.updateEntry()`
- **Key Logic**: Re-uses the Create screen. Compares the original `id` to overwrite the existing database entity rather than creating a new one.

### Delete Password
- **Status**: ✅ Implemented
- **Soft Delete or Permanent?**: Soft Delete (Version 2 Database migration).
- **Recovery**: Soft deleted items are moved to a Recycle Bin (`isDeleted = true`) and can be restored or permanently purged by the user manually, or cleared in bulk by the system after 7 days.
- **Autofill links**: Permanently deleting an entry also deletes its autofill app links.

### Search Passwords
- **Status**: ✅ Implemented
- **Algorithm**: Linear, tokenized in-memory search across decrypted strings. Splits the query by whitespace and ensures every token (`keywords.all { q -> ... }`) matches at least one field.
- **Fields Searched**: Title, Username, Website, Notes, Category, Tags, Custom Field values. The list shows a loading state until every entry has been decrypted; there is no partial search.

### Vault List Preview
- **Status**: ✅ Implemented
- **Location**: `com.example.domain.models.VaultListPreview` / `VaultRepository.entryToVaultListEntry()`
- **Second line**: The entry's username; if it has none, the host of its website; otherwise empty. A password or custom-field value is never shown in the list.

## Security Features

### Biometric Unlock
- **Status**: ✅ Implemented
- **Location**: `com.example.security.BiometricCryptoHelper`
- **Methods**: Fingerprint, Face Recognition (Dependent on device `BIOMETRIC_STRONG` capabilities).

### Master Password
- **Status**: ✅ Implemented
- **Setup**: Configured on initial app launch. The raw password is never saved; it is passed through PBKDF2WithHmacSHA256 (300k iterations) and the derived KEK encrypts the randomly generated Software DEK.
- **Setup guard**: The setup screen appears only when no master password hash is stored and no vault data exists (`VaultLaunchState`). If the settings can't be read, or the hash is missing while vault data exists, a recovery screen is shown instead. Setup itself refuses (`SetupResult.VAULT_EXISTS`) when a wrapped or pending vault key or any entry row exists, or when that can't be checked, so an existing vault is never overwritten.
- **Change Password**: ❌ Not Implemented (Currently missing from Settings).

### Upgrade of Older Vaults
- **Status**: ✅ Implemented
- **Location**: `com.example.security.LegacyVaultMigration`, `LegacyVaultKeys`, `VaultViewModel.unlockWithPassword()`
- **Behaviour**: Entries written by builds before the Software DEK are re-encrypted at the first unlock. Every row (recycle bin included) is decrypted with the old keys (the Android Keystore key and the old fallback key, tried per field) before anything is written. Rows that decrypt fully are re-encrypted with a new DEK in one database transaction; rows that can't be read are left byte-for-byte unchanged and show as "Decryption Failed", and the user is told how many. The old keys are never deleted.
- **Failure**: If the vault has entries but none of them can be read, nothing is changed, the vault stays locked and the lock screen says so (`AuthResult.MIGRATION_FAILED`, not counted as a failed attempt). An upgrade interrupted by a crash is finished or redone at the next unlock.

### Failed-Unlock Lockout
- **Status**: ✅ Implemented
- **Thresholds**: 5 failed attempts: 30 seconds; 10: 1 minute; 15: 5 minutes; 20 or more: 15 minutes. A successful unlock resets the counter.
- **Timing**: Measured with `elapsedRealtime` and the boot count, so changing the clock has no effect; after a reboot the cooldown restarts from boot and is never longer than the lockout duration. The lock screen countdown uses the same calculation.
- **Location**: `com.example.security.AuthLockout`

### Auto Lock/Logout
- **Status**: ✅ Implemented
- **Timeout**: Configurable via settings. Defaults to `60000L` (1 minute) after the app goes to the background; `0` locks immediately.
- **Location**: `com.example.security.VaultSessionManager.onStop()` and `com.example.repository.SettingsRepository.autoLockTimer`.

### Clipboard Auto-Clear
- **Status**: ✅ Implemented
- **Duration**: Configurable. Defaults to `30000L` (30 seconds) after a copy action.
- **Location**: `com.example.ui.VaultViewModel.copyToClipboard()` using `kotlinx.coroutines.delay`.

## Autofill

### Autofill Service
- **Status**: ✅ Implemented
- **Location**: `com.example.service.VaultAutofillService`, `AutofillCredentialMatcher`, `AutofillFieldDetector`, `com.example.ui.AutofillAuthActivity`
- **Websites**: Only in browsers on Google's privileged-apps list whose signing certificate matches it (`BrowserVerifier`). The web domain of the login field's frame is compared (both fields come from that frame; hidden fields are skipped; on an http page only http:// entries match) with each entry's website: exact host (`www.` ignored) or the same registrable domain from the bundled Public Suffix List. Any other app's web domain is ignored.
- **Apps**: Entries whose website is `androidapp://<package>`, and entries the user linked to the app by choosing them once under "Search VaultPass…". Links are stored in the `autofill_app_links` table (Room version 4); browsers are never linked. Entry titles and app names are not used for matching, so each app is linked once by hand.
- **Locked vault**: A "Tap to unlock VaultPass" item appears on the detected username/password fields; after unlocking, the matching entries are offered.
- **Logging**: Diagnostics only in debug builds, without entry titles, usernames, passwords, notes or field values.

## In-App Updates

### Update Check
- **Status**: ✅ Implemented
- **Location**: `com.example.update` (`UpdateController`, `UpdateEngine`), switch in `com.example.ui.screens.SettingsScreen`
- **Default**: Off. Settings has "Check for updates when the app opens" and "Check now".
- **Flow**: Reads the latest GitHub release, downloads `VaultPass.apk` to `filesDir/updates/`, checks its size, SHA-256 digest (a release without one is refused), package name, version code and signing certificate, then offers "Restart now" or "Later". A kept update is offered again (re-verified) at the next app start. Installation goes through Android's `PackageInstaller`; the downloaded file is deleted on the first start of the new version.

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
