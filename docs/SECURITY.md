# Vault Pass Mobile - Security

## Encryption Specifications
### Password Encryption
- Algorithm: AES-256-GCM (Advanced Encryption Standard with Galois/Counter Mode, NoPadding)
- Key Derivation: PBKDF2WithHmacSHA256. HMAC-SHA256 then splits its output into a key-encryption key (KEK) and a separate authentication hash, so the stored hash cannot be used as the key.
- IV/Nonce: 12-byte randomly generated (`SecureRandom().nextBytes(iv)`) per encryption payload. Stored prepended to the encrypted payload.
- Code Reference: `com.example.security.CryptoManager` and `com.example.security.PasswordHashHelper`

### Database Encryption
- Method: Column-level encryption via Application code. (The Room database itself is not encrypted via SQLCipher; instead, sensitive entity fields are encrypted *before* insertion into the Room database).
- Location: `com.example.repository.VaultRepository` (maps plain `VaultEntry` to encrypted `VaultEntryEntity`)
- Schema: Room version 4. The `autofill_app_links` table (added in version 4) stores only package names and entry sync IDs, in plain text.

## Authentication Methods
### Biometric Authentication
- Android API Used: `androidx.biometric.BiometricPrompt`
- Supported Methods: Fingerprint, Face ID (dependent on device `BIOMETRIC_STRONG` capabilities).
- Fallback: Master Password.
- Code Location: `com.example.security.BiometricCryptoHelper`

### Master Password
- Hashing Algorithm: PBKDF2WithHmacSHA256, followed by HMAC-SHA256 with the label `vaultpass-auth-v1` (the stored authentication hash).
- Salt: 16-byte cryptographically secure random array.
- Iterations: 300,000 for new vaults (`SecurityPolicy.CURRENT_KDF_ITERATIONS`). Each vault stores its own iteration count and algorithm, and unlock uses the stored values. A vault with fewer iterations is re-wrapped with 300,000 iterations and a new salt after a successful password unlock.
- Older vaults: a hash stored by builds before 2026-06 (the raw PBKDF2 output) is still accepted and is replaced by the authentication hash on the next successful unlock.
- Entries from before the Software DEK are re-encrypted with a new DEK at the first unlock (`LegacyVaultMigration`). Rows that cannot be decrypted are left unchanged; if the vault has entries and none can be read, nothing is changed and unlock returns `AuthResult.MIGRATION_FAILED`. The old keys are never deleted.
- Setup never overwrites a vault: the setup screen is shown only when no master hash is stored and no vault data exists (`VaultLaunchState`), and setup refuses (`SetupResult.VAULT_EXISTS`) when a wrapped or pending key or any entry row exists.
- Code Location: `com.example.security.PasswordHashHelper`

### Failed-Unlock Lockout
- Thresholds: 5 failed attempts lock the password field for 30 seconds, 10 for 1 minute, 15 for 5 minutes, 20 or more for 15 minutes. A successful unlock resets the counter.
- Timing: each failure stores `SystemClock.elapsedRealtime()`, the device boot count (`Settings.Global.BOOT_COUNT`, when available) and the lockout duration. Changing the wall clock has no effect. After a reboot the cooldown restarts from boot. The remaining time is never longer than the lockout duration.
- A correct password is never counted as a failed attempt when the upgrade of an older vault fails (`AuthResult.MIGRATION_FAILED`).
- Code Location: `com.example.security.AuthLockout`

## Android Permissions
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION" />
```
- INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE: LAN sync and the optional update check.
- CAMERA: scanning the pairing QR code (the camera feature is declared as not required).
- REQUEST_INSTALL_PACKAGES, UPDATE_PACKAGES_WITHOUT_USER_ACTION: installing a downloaded, verified update.
- `QUERY_ALL_PACKAGES` is not declared, and `SecurityHardeningTest` fails if it is added.

*(Note: Biometric permissions are implicitly merged via the `androidx.biometric` library dependency, and `BIND_AUTOFILL_SERVICE` is requested on the AutofillService definition in `AndroidManifest.xml`).*

## Autofill
- Websites: a webDomain is used only when the requesting app is listed in Google's privileged-apps list (bundled as `assets/privileged_browsers.json`) and its current signing certificate matches a listed release fingerprint (`BrowserVerifier`). The login fields and the domain come from one frame (the focused login field's); hidden fields and fields in other frames are never filled. On an http page only entries saved with http:// match. An entry matches on the exact host or on the same registrable domain according to the bundled Public Suffix List. Any other app's webDomain is ignored.
- Apps: only entries whose website is `androidapp://<package>` or that the user linked to that app by picking them once through "Search VaultPass…". Browsers are never linked. Entry titles, app labels and package-name similarity are not used for matching.
- Locked vault: Android shows a "Tap to unlock VaultPass" item on the detected login fields; after unlocking, the matching entries are offered.
- Logging: autofill diagnostics are written to logcat only in debug builds (`AutofillDiagnosticsRepository`), and never contain entry titles, usernames, passwords, notes or field values.

## In-App Updates
- Off by default. When enabled, VaultPass contacts `api.github.com` and, for a download, `github.com` and the GitHub download server it redirects to, over HTTPS only. Nothing from the vault is sent.
- Release builds always use `https://api.github.com/repos/ArmaanCode2/Vault-pass/releases/latest`. A different feed URL is accepted only in debug and `updateTest` builds, and plain HTTP only to localhost in those two build types.
- Before an update is offered, the downloaded APK must match the size and SHA-256 digest GitHub reports (a release without a `sha256:` digest is refused), have the same package name and a higher `versionCode`, and be signed with the installed app's certificate (directly, or through a key rotation recorded in the APK). Anything else is deleted. If Android cannot report the signer, the update is refused; the only exception is an archive whose signer can't be read on Android 7-8.1 (API 24-27), where `getPackageArchiveInfo` often returns no signatures and Android's own install-time check still rejects a different key.
- Release builds use the GitHub feed even if `BuildConfig` said otherwise: `UpdateConfig.feedUrl` returns the GitHub URL whenever `BuildConfig.BUILD_TYPE` is `release`, and release never allows plain HTTP. Debug builds have no update feed (the updater is hidden) unless built with `-PupdateFeedUrl`.
- The APK is kept in `filesDir/updates/` and installed through `PackageInstaller`. That directory is deleted on the first start of the new version. Recursive deletes in the updater only run on a directory named `updates` directly inside `filesDir` (`UpdateFiles.isUpdatesDir`).
- Code Location: `com.example.update`

## Threat Model
- What this app protects: User's database of passwords, usernames, URLs, and custom fields.
- Threat scenarios: Local device compromise, physical theft of unlocked device, extraction of SQLite database by root.
- Mitigations: AES-256 encryption at rest, memory wiping of the Software DEK when the vault locks, automatic clipboard clearing, automatic vault lockout on inactivity, failed-unlock lockout (see above).

## Security Best Practices
- ProGuard/R8 Enabled: Yes for release builds (`isMinifyEnabled = true`, `isShrinkResources = true`, rules in `app/proguard-rules.pro`).
- Obfuscation: Yes for classes not covered by keep rules. Room, kotlinx.serialization, DataStore, `com.example.domain.models`, `com.example.data.models`, `com.example.domain.security` and `com.example.security` are kept.
- Root Detection: No
- Debuggable APK: Yes, for debug builds only. Debug builds use the application ID suffix `.debug`, so they install next to the release app and never share its data.
- Release Signing: the release key comes from Android Studio's "Generate Signed APK" or from the `KEYSTORE_PATH` / `STORE_PASSWORD` (/ `KEY_PASSWORD`) environment variables, alias `upload`. There is no debug-key fallback. `checkReleaseSigning` stops the release build when no release key was provided. After packaging, `verifyReleaseApk` rejects an APK that is unsigned, debug-signed, or signed by a certificate whose SHA-256 differs from `vaultpass.releaseCertSha256` in `gradle.properties`, and copies an accepted APK to `app/release/VaultPass.apk`. A rejected APK is deleted from the build output too. `bundleRelease` is guarded the same way: `checkReleaseSigning` runs before the bundle is packaged and signed, and `verifyReleaseBundle` reads the bundle's JAR signature (apksig only verifies APKs), applies the same debug-certificate and pin checks, and deletes a rejected bundle.

## Data Storage Security
- Passwords Storage: Encrypted fields stored in AndroidX Room SQLite Database.
- Config Storage: Non-sensitive preferences stored in Jetpack DataStore (`androidx.datastore.preferences`).
- Backups: `android:allowBackup="false"`, and the backup and data-extraction rules exclude the Room database, `vaultpass_sync_prefs` (wrapped keys) and the DataStore file.
- Temporary Decrypted Data: The `Software DEK` is held in memory (raw bytes and a `SecretKeySpec`). On lock or timeout the bytes are overwritten with zeros and both references are cleared (`CryptoManager.clearSoftwareDek()`).
- Clipboard Handling: Auto-cleared after a configurable timeout. Default is `30000L` (30 seconds). 
  - Code Location: `com.example.ui.VaultViewModel.copyToClipboard()` and `com.example.repository.SettingsRepository.clipboardClearTimer`

## Known Security Considerations
- The Room database wrapper (SQLite) is not encrypted using SQLCipher. While the sensitive column payloads (usernames, passwords) are AES-encrypted, metadata such as the number of entries, relational IDs, and potentially timestamp metadata remain visible to an attacker with root access. The same applies to the package names stored in `autofill_app_links`.
- `vaultpass.releaseCertSha256` is not set in the repository. Until the maintainer sets it, `verifyReleaseApk` fails the release build and prints the certificate fingerprint to confirm.
- Updating from 2.6.x to 2.7.0 requires a one-time reinstall (2.7.0 is signed with a different key). **If you can't unlock VaultPass 2.6.x, do NOT uninstall it: uninstalling deletes the vault. Contact us via GitHub issues first (https://github.com/ArmaanCode2/Vault-pass/issues).** Otherwise: export, uninstall, install, restore.
