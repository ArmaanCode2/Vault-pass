# VaultPass

> Note: This project is under active development. Maintain independent backups of your vault data.

VaultPass is an offline Android password manager built with Kotlin and Jetpack Compose. It stores credentials locally and does not use cloud services or third-party synchronization. Cryptographic operations and data persistence occur on the device.

## Security Verification

The latest release APK has been scanned with VirusTotal.

[VirusTotal Report](https://www.virustotal.com/gui/file/ac993d64518225a9350b73f60edaafe82d7e8642dc864ba7c70c6aeccd4b20c7)

Users are encouraged to independently verify any release APK before installation.

## Features

### Authentication
* Master password authentication
* Biometric unlock via Android Keystore
* Versioned Key Derivation Function (KDF) architecture
* Background KDF migration

### Security
* AES-GCM vault encryption
* Dynamic KDF metadata storage
* Auto-lock mechanism
* FLAG_SECURE implementation
* Clipboard auto-clear
* In-memory DEK zeroization on lock

### Vault Management
* Create, read, update, and delete credentials
* Unlimited custom fields
* Password generator
* Search (titles, usernames, categories, custom fields)
* Categories and favorites

### Autofill
* Android Autofill Service integration
* DOM traversal via BFS
* Matching heuristics (domain, app label, package name)
* Gatekeeper logic to ignore non-input layout containers

### Import / Export
* Import formats: JSON, TXT, encrypted VPEX
* Export formats: Encrypted VPEX (Base64 AES-GCM)
* Fallback parser for legacy backups

### Security Center
* Password hygiene tracking
* Weak password tracking
* Reused password tracking
* Missing password tracking
* Dashboard privacy masking

### User Experience
* Jetpack Compose UI
* Material 3 dynamic theming (Light, Dark, System)

## Security Architecture

* **Master Password**: Verified with constant-time MessageDigest.isEqual().
* **PBKDF2-HMAC-SHA256**: Default KDF.
* **Dynamic KDF metadata**: Dynamic storage for iteration counts, versioning, and algorithms.
* **KDF versioning**: Schema versioning for backward compatibility.
* **Automatic migration**: Background migration of legacy vaults using a Two-Phase Commit pattern.
* **Software DEK**: In-memory Data Encryption Key (DEK) for AES-GCM operations; zeroized on lock.
* **Password-wrapped DEK**: DEK wrapped with PBKDF2-derived KEK.
* **Biometric-wrapped DEK**: DEK wrapped using Android Keystore for biometric unlock.
* **Android Keystore**: Anchors biometric authentication to hardware.
* **AES-GCM**: Encrypts Room database payloads.
* **Auto-lock**: Enforced via ProcessLifecycleOwner and Activity hooks.

## Authentication & Protection

Local brute-force protection:

* **Constant-time password verification**: Mitigates timing attacks.
* **Failed-attempt tracking**: Failed attempts tracked in DataStore.
* **Cooldown enforcement**: Incremental lockout timers (5 failures = 30s, 20+ failures = 15m).
* **Biometric unlock**: Secondary DEK unwrap method.
* **Counter reset**: Counters reset upon successful password or biometric authentication.

## Technology Stack

* Language: Kotlin
* UI Toolkit: Jetpack Compose, Material 3
* Architecture: MVVM (Model-View-ViewModel)
* Local Storage: Room Database, Jetpack DataStore, SharedPreferences
* Cryptography: javax.crypto (AES-GCM, PBKDF2), Android Keystore (BiometricPrompt)
* System Integration: Android Autofill Framework

## Project Status

### Implemented
* Versioned KDF architecture
* Dynamic KDF configuration
* KDF migration framework
* Constant-time password verification
* Brute-force protection and cooldowns
* Hardware-backed biometric unlock
* Multi-format Import/Export
* Autofill DOM traversal and heuristics
* Security Center hygiene tracking
* Dynamic Material 3 theming

### Planned
* Cross-device synchronization via encrypted cloud providers
* Native Windows/Desktop companion application
* Expanded Autofill dataset capabilities (e.g., credit cards, addresses)
* Automated scheduled background backups

## Notes

VaultPass operates offline. It does not require an account, does not use network communication, and stores all encrypted data locally.

## Installation and Development

### Requirements
* Android Studio
* Android SDK
* JDK 17 or newer
* Gradle

### Build Instructions
1. Clone the repository.
2. Open the project in Android Studio.
3. Allow Gradle Sync to complete.
4. Build and run via Assemble Project.