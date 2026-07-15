# Vault Pass Mobile - Biometric Authentication

## API Version
- Using: `BiometricPrompt` API (AndroidX)
- Minimum API Level: Android 7.0 (API 24) via AndroidX compat, but `BiometricManager` capabilities vary by device hardware.
- Code Location: `com.example.security.BiometricCryptoHelper` and `com.example.MainActivity.showBiometricPrompt()`

## Supported Biometric Methods
- Fingerprint: ✅ Yes
- Face: ✅ Yes (If recognized by the device as `BIOMETRIC_STRONG` capable of integrating with AndroidKeyStore).
- Iris: ✅ Yes (If recognized by the device as `BIOMETRIC_STRONG`).

## Authentication Flow
VaultPass implements a dual-path biometric authentication flow to support both legacy users and new users utilizing the Software DEK architecture.

1. User opens app and taps the fingerprint icon on the Lock Screen.
2. `MainActivity.showBiometricPrompt()` is triggered.
3. The app checks if a `dekBioWrapped` payload exists in `SettingsRepository`.
4. **Path A (Modern AES DEK Wrapping):**
   - The app reads the stored IV from the `dekBioWrapped` payload.
   - It requests a decryption `Cipher` from `BiometricCryptoHelper` initialized with the hardware AES key and the IV.
   - The `Cipher` is wrapped in a `BiometricPrompt.CryptoObject`.
5. **Path B (Legacy EC Challenge-Response):**
   - If no wrapped DEK exists, the app requests an EC `Signature` from `BiometricCryptoHelper`.
   - A 32-byte secure random challenge is generated.
   - The `Signature` is wrapped in a `BiometricPrompt.CryptoObject`.
6. The `BiometricPrompt` is displayed to the user via OS native UI.
7. Upon successful authentication:
   - **Path A:** The cipher is unlocked, the payload is decrypted, yielding the Software DEK in memory.
   - **Path B:** The signature signs the challenge, and `VaultViewModel.unlockWithBiometrics(challenge, signatureBytes)` validates it against the legacy KeyStore configuration.

## Fallback Mechanism
- Primary: Hardware-backed Biometric Authentication.
- Secondary: Master Password.
- Configuration: `setNegativeButtonText("Use Master Password")` explicitly routes the user back to the password input field if biometrics fail or are cancelled.
- Code Location: `com.example.MainActivity.showBiometricPrompt()`

## Biometric Key Encryption
- KeyStore Type: `AndroidKeyStore` (Hardware-backed).
- Key Algorithms:
  1. `KEY_ALGORITHM_AES` (Block Mode GCM, NoPadding) for modern DEK wrapping.
  2. `KEY_ALGORITHM_EC` (SHA256withECDSA) for legacy signature verification.
- Key Validity: Keys are strictly generated with `setUserAuthenticationRequired(true)` and `setInvalidatedByBiometricEnrollment(true)`. 
- Security Action: If a user adds a new fingerprint to their OS settings, the Keystore automatically permanently invalidates these keys. The user will be forced to log in with their Master Password and re-enroll biometrics to generate new keys.
- Code Location: `com.example.security.BiometricCryptoHelper.generateBiometricAesKey()`

## Error Handling
- Biometric not available / Key invalidated: A Toast notification is shown ("Biometric key missing or invalidated. Please use Master Password and re-enable in Settings.") and the prompt is aborted.
- Failed attempts: Handled natively by the Android OS `BiometricPrompt` UI (which introduces its own hardware-level timeouts).
- Decryption/Signature failure post-auth: Caught in a `try/catch` block within `onAuthenticationSucceeded`, displaying an "Authentication error" Toast and leaving the vault locked.

## Biometric Data Privacy
- Biometric data storage: VaultPass **never** has access to raw biometric data (fingerprint ridges or face maps).
- Verification method: Verification occurs entirely within the device's Trusted Execution Environment (TEE) or Secure Enclave. VaultPass only receives a cryptographic token (unlocked Cipher or Signature) proving the OS successfully authenticated the user.
- Code References: Usage of `BiometricPrompt.CryptoObject` strictly enforces this hardware boundary.
