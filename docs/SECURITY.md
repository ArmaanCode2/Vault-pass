# Vault Pass Mobile - Security

## Encryption Specifications
### Password Encryption
- Algorithm: AES-256-GCM (Advanced Encryption Standard with Galois/Counter Mode, NoPadding)
- Key Derivation: PBKDF2WithHmacSHA256
- IV/Nonce: 12-byte randomly generated (`SecureRandom().nextBytes(iv)`) per encryption payload. Stored prepended to the encrypted payload.
- Code Reference: `com.example.security.CryptoManager` and `com.example.security.PasswordHashHelper`

### Database Encryption
- Method: Column-level encryption via Application code. (The Room database itself is not encrypted via SQLCipher; instead, sensitive entity fields are encrypted *before* insertion into the Room database).
- Location: `com.example.repository.VaultRepository` (maps plain `VaultEntry` to encrypted `VaultEntryEntity`)

## Authentication Methods
### Biometric Authentication
- Android API Used: `androidx.biometric.BiometricPrompt`
- Supported Methods: Fingerprint, Face ID (dependent on device `BIOMETRIC_STRONG` capabilities).
- Fallback: Master Password.
- Code Location: `com.example.security.BiometricCryptoHelper`

### Master Password
- Hashing Algorithm: PBKDF2WithHmacSHA256
- Salt: 16-byte cryptographically secure random array.
- Iterations: 300,000 (Configured via `SecurityPolicy.CURRENT_KDF_ITERATIONS`)
- Code Location: `com.example.security.PasswordHashHelper`

## Android Permissions
```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```
*(Note: Biometric permissions are implicitly merged via the `androidx.biometric` library dependency, and `BIND_AUTOFILL_SERVICE` is requested on the AutofillService definition in `AndroidManifest.xml`).*

## Threat Model
- What this app protects: User's database of passwords, usernames, URLs, and custom fields.
- Threat scenarios: Local device compromise, physical theft of unlocked device, extraction of SQLite database by root.
- Mitigations: AES-256 encryption at rest, memory wiping of the Software DEK when the vault locks, automatic clipboard clearing, automatic vault lockout on inactivity, brute-force authentication delays.

## Security Best Practices
- ProGuard/R8 Enabled: No (Set to `isMinifyEnabled = false` in `app/build.gradle.kts`)
- Obfuscation: No
- Root Detection: No
- Debuggable APK: Yes, for debug builds (No explicit `android:debuggable="false"` restriction in `AndroidManifest.xml`).

## Data Storage Security
- Passwords Storage: Encrypted fields stored in AndroidX Room SQLite Database.
- Config Storage: Non-sensitive preferences stored in Jetpack DataStore (`androidx.datastore.preferences`).
- Temporary Decrypted Data: The `Software DEK` is stored in memory as a `SecretKey` and cleared (`softwareDek = null`) immediately upon app lock/timeout. 
- Clipboard Handling: Auto-cleared after a configurable timeout. Default is `30000L` (30 seconds). 
  - Code Location: `com.example.ui.VaultViewModel.copyToClipboard()` and `com.example.repository.SettingsRepository.clipboardClearTimer`

## Known Security Considerations
- `[UNCLEAR - needs clarification]`: R8/ProGuard is explicitly disabled in the `release` build type (`isMinifyEnabled = false`), which leaves the compiled bytecode vulnerable to easy reverse-engineering.
- The Room database wrapper (SQLite) is not encrypted using SQLCipher. While the sensitive column payloads (usernames, passwords) are AES-encrypted, metadata such as the number of entries, relational IDs, and potentially timestamp metadata remain visible to an attacker with root access.
