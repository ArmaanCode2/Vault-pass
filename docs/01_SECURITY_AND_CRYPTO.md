# 01. Security and Cryptography Architecture

This document strictly defines the security architecture implemented in VaultPass, based purely on the active source code.

## 1. Cryptographic Primitives
VaultPass uses standard Android and Java cryptographic libraries to secure data at rest.

- **Algorithm:** `AES` (Advanced Encryption Standard)
- **Block Mode:** `GCM` (Galois/Counter Mode)
- **Padding:** `NoPadding` (GCM does not require padding)
- **Key Length:** 256-bit (32 bytes)
- **Initialization Vector (IV):** 12 bytes (`SecureRandom().nextBytes(iv)`)
- **Base64 Encoding:** Android's `Base64.NO_WRAP` flag is used universally to prevent line-break corruption.

*Source: `CryptoManager.kt` (`TRANSFORMATION = "AES/GCM/NoPadding"`)*

## 2. Key Derivation Function (KDF)
The user's Master Password is never stored. Instead, a Key Encryption Key (KEK) is derived from it.

- **Algorithm:** `PBKDF2WithHmacSHA256`, 256-bit output (the root key).
- **Current Iterations:** `300,000` (Defined in `SecurityPolicy.CURRENT_KDF_ITERATIONS`)
- **Current Version:** `1` (Defined in `SecurityPolicy.CURRENT_KDF_VERSION`)
- **Salt Generation:** 16-byte random array via `SecureRandom`.
- **Domain Separation:** The root key is never used directly. `KEK = HMAC-SHA256(root, "vaultpass-kek-v1")` wraps the DEK; `authHash = HMAC-SHA256(root, "vaultpass-auth-v1")` is stored as `master_hash` in DataStore and checked at unlock (constant-time comparison). Knowing the stored hash does not give the KEK.
- **Stored Parameters:** Salt, iterations, algorithm and version are stored per vault, and unlock always uses the stored values. After a successful password unlock of a vault with fewer than 300,000 iterations, the DEK is re-wrapped with a new salt and 300,000 iterations (pending key in SharedPreferences first, then the new hash and parameters in DataStore, then the final wrapped key), so an interruption leaves a state the next unlock can finish.
- **Older Hashes:** Builds before 2026-06 stored the root key itself as `master_hash` and used it as the KEK. That hash is still accepted; on the next successful unlock the DEK is re-wrapped with the domain-separated KEK and the hash is replaced by `authHash`.

*Source: `PasswordHashHelper.kt`, `SecurityPolicy.kt`, `VaultViewModel.unlockWithPassword()`*

## 3. The Software Data Encryption Key (DEK)
So that the password-derived key can change (KDF upgrades; a password change is not implemented yet) without re-encrypting the entire database, VaultPass uses a two-tier key system:
1. **Data Encryption Key (DEK):** A 32-byte (256-bit) AES key generated via `SecureRandom` when the vault is first created. This key encrypts all database entries.
2. **Key Encryption Key (KEK):** Derived from the Master Password. The KEK is used *only* to encrypt (wrap) the DEK.

### Wrapping the DEK
The Software DEK is wrapped using the KEK before being saved to storage.
- **Cipher:** `AES/GCM/NoPadding`
- **Output:** The 12-byte IV is prepended to the encrypted DEK payload.
- **Payload Length Validation:** Unwrapping requires the decoded payload to be longer than 48 bytes (32-byte DEK + 16-byte GCM tag); everything before those 48 bytes is the IV (12 bytes when written by this version).
- **Storage:** The wrapped DEK is saved in the `vaultpass_sync_prefs` SharedPreferences (`dek_mp_wrapped`) with `commit()`, so it is on disk before the app continues.
- **Never Replaced by Setup:** Setup creates a DEK only for an empty install. It refuses (`SetupResult.VAULT_EXISTS`) when a wrapped or pending DEK or any entry row exists, or when that cannot be checked.

*Source: `CryptoManager.kt` (`wrapDekWithKek`, `unwrapDekWithKek`), `SettingsRepository.kt`*

### Upgrading Vaults From Before the Software DEK
Older builds encrypted each field directly (`AES/GCM/NoPadding`, Base64 of `IV(12) || ciphertext`) with either the Android Keystore key `vaultpass_keys` or a fallback AES key stored as `fallback_key` in `vaultpass_sync_prefs`.
- At the first unlock every row, recycle bin included, is decrypted with both legacy keys, field by field, before anything is written.
- A new DEK is generated and wrapped; its pending copy is saved first, then every fully readable row is re-encrypted in one Room transaction, then the wrapped DEK is finalised.
- Rows with a field that cannot be decrypted are left byte-for-byte unchanged. If rows with content exist but none of them can be read, nothing is changed and unlock returns `MIGRATION_FAILED`.
- The legacy keys are never deleted.

*Source: `LegacyVaultKeys.kt`, `LegacyVaultMigration.kt`, `VaultViewModel.performMigration()`*

## 4. Hardware-Backed Biometrics
When Biometric Unlock is enabled, VaultPass utilizes the Android Keystore system (`AndroidKeyStore`) to generate hardware-backed keys.

- **EC Key Pair:** `KEY_ALGORITHM_EC` used for `PURPOSE_SIGN` and `PURPOSE_VERIFY`.
- **AES Key:** `KEY_ALGORITHM_AES` with `BLOCK_MODE_GCM` used for encrypting the Software DEK (`PURPOSE_ENCRYPT` / `PURPOSE_DECRYPT`).
- **Invalidation:** `setInvalidatedByBiometricEnrollment(true)` ensures that if a user adds a new fingerprint to the OS, the biometric keys are permanently invalidated, forcing a master password fallback.

*Source: `BiometricCryptoHelper.kt`*

## 5. Export Encryption (VPEX)
Encrypted exports (`.vpex`) utilize a standalone encryption loop independent of the primary vault DEK.
- **Process:** 
  1. A unique 16-byte salt is generated.
  2. A temporary KEK is derived from the user-provided export password (PBKDF2WithHmacSHA256, 100,000 iterations, then the `vaultpass-kek-v1` HMAC step).
  3. A new 12-byte IV is generated.
  4. The JSON payload is encrypted via `AES/GCM/NoPadding`.
  5. **File Format Structure:** The final byte array is concatenated exactly as: `[16-byte Salt] + [12-byte IV] + [Encrypted JSON Data]`.
- **Decryption Fallback:** The `decryptBackup` function handles backwards compatibility by attempting a 12-byte IV first, and falling back to a legacy 16-byte IV if the GCM tag fails verification. If neither works, it repeats both attempts with the older KEK (the PBKDF2 output without the HMAC step).

*Source: `CryptoManager.kt` (`encryptBackup`, `decryptBackup`)*

## 6. Update Integrity
The optional in-app update (off by default) checks a downloaded APK before offering it:
- **Digest:** SHA-256 of the file, compared with the `sha256:` digest GitHub lists for the release asset. A release without a usable `sha256:` digest is refused before anything is downloaded. The size must match the listed size (at most 200 MB).
- **Package:** Same package name and a strictly higher `versionCode` than the installed app.
- **Signer:** SHA-256 of the APK's signing certificate must equal the installed app's, directly or through a key rotation recorded in the APK. If a signer can't be read the update is refused, except when only the archive's signer is unreadable on API 24-27 (Android often returns no archive signatures there, and its install-time check still enforces the key).
- Any APK that fails a check is deleted. Release builds only contact the GitHub releases API over HTTPS.

*Source: `com.example.update` (`UpdateChecker.kt`, `UpdateDownloader.kt`, `ApkVerifier.kt`)*
