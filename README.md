# VaultPass

> Note: This project is under active development. Maintain independent backups of your vault data.

VaultPass is an offline Android password manager built with Kotlin and Jetpack Compose. It stores credentials locally and does not use cloud services or third-party synchronization. It can sync directly with VaultPass Desktop over your local network, and it can check GitHub for new versions if you turn that on. Cryptographic operations and data persistence occur on the device.

## Security Verification

Each release is published on [GitHub Releases](https://github.com/ArmaanCode2/Vault-pass/releases) as `VaultPass.apk`. The download page of the project website shows the SHA-256 that GitHub publishes for the latest APK, with a VirusTotal lookup for that hash.

The v2.6.3 APK was scanned with VirusTotal: [VirusTotal Report](https://www.virustotal.com/gui/file/36cade08a69950b7f5b13f0144476f1c73e1b12dbd28bf0de7346acacf8372d9?nocache=1)

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
* Website matching by registrable domain (bundled Public Suffix List), with page domains trusted only from known browsers
* App matching only through explicit links: pick an entry once with "Search VaultPass…", or set the entry's website to `androidapp://<package>`
* "Tap to unlock VaultPass" on the login fields when the vault is locked
* Gatekeeper logic to ignore non-input layout containers

### Updates
* Optional update check against GitHub releases, off by default (Settings: "Check for updates when the app opens", "Check now")
* The download is checked before install: size, GitHub's SHA-256 digest (a release without one is refused), same package, newer version, same signing key
* The first in-app update shows Android's confirmation; later ones may install without it, and VaultPass just closes (open it again)
* Installed through Android's installer ("Restart now"), or later from the downloaded copy ("Later")

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
* **Cooldown enforcement**: Incremental lockout timers (5 failures = 30s, 20+ failures = 15m). A reboot can't skip or extend a cooldown.
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
* Encrypted local-network sync with VaultPass Desktop (QR pairing, reviewed merges)
* Optional in-app updates from GitHub releases

### Planned
* Cross-device synchronization via encrypted cloud providers
* Expanded Autofill dataset capabilities (e.g., credit cards, addresses)
* Automated scheduled background backups

## Notes

VaultPass works offline and does not require an account. It uses the network in two optional ways: LAN sync with VaultPass Desktop, which connects the two devices directly over your local network and never over the internet, and the update check, which is off by default and asks GitHub (`api.github.com`) for the latest release, sending nothing from your vault. See [PRIVACY.md](PRIVACY.md) and [docs/SYNC_PROTOCOL.md](docs/SYNC_PROTOCOL.md).

Upgrading from 2.6.x: 2.7.0 is the first build signed with the release key, so it can't install over 2.6.x.

> **If you can't unlock VaultPass 2.6.x, do NOT uninstall it: uninstalling deletes the vault. Contact us via [GitHub issues](https://github.com/ArmaanCode2/Vault-pass/issues) first.**

Then export a backup, uninstall, install 2.7.0, and restore. Autofill links to apps have to be made once with "Search VaultPass…". Later updates can come from inside VaultPass: the first in-app update shows Android's confirmation, and later ones may install without it, so VaultPass just closes; open it again.

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