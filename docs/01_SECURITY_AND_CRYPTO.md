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

- **Algorithm:** `PBKDF2WithHmacSHA256`
- **Current Iterations:** `300,000` (Defined in `SecurityPolicy.CURRENT_KDF_ITERATIONS`)
- **Current Version:** `1` (Defined in `SecurityPolicy.CURRENT_KDF_VERSION`)
- **Salt Generation:** 16-byte random array via `SecureRandom`.

*Source: `PasswordHashHelper.kt`, `SecurityPolicy.kt`*

## 3. The Software Data Encryption Key (DEK)
To allow for seamless master password changes without re-encrypting the entire database, VaultPass uses a two-tier key system:
1. **Data Encryption Key (DEK):** A 32-byte (256-bit) AES key generated via `SecureRandom` when the vault is first created. This key encrypts all database entries.
2. **Key Encryption Key (KEK):** Derived from the Master Password. The KEK is used *only* to encrypt (wrap) the DEK.

### Wrapping the DEK
The Software DEK is wrapped using the KEK before being saved to storage.
- **Cipher:** `AES/GCM/NoPadding`
- **Output:** The 12-byte IV is prepended to the encrypted DEK payload.
- **Payload Length Validation:** Unwrapping strictly validates that the combined Base64 payload length is `> 48 bytes` (12 byte IV + 32 byte DEK + 16 byte GCM tag).

*Source: `CryptoManager.kt` (`wrapDekWithKek`, `unwrapDekWithKek`)*

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
  2. A temporary KEK is derived from the user-provided export password.
  3. A new 12-byte IV is generated.
  4. The JSON payload is encrypted via `AES/GCM/NoPadding`.
  5. **File Format Structure:** The final byte array is concatenated exactly as: `[16-byte Salt] + [12-byte IV] + [Encrypted JSON Data]`.
- **Decryption Fallback:** The `decryptBackup` function handles backwards compatibility by attempting a 12-byte IV first, and falling back to a legacy 16-byte IV if the GCM tag fails verification.

*Source: `CryptoManager.kt` (`encryptBackup`, `decryptBackup`)*
