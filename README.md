<p align="center">
  <img src="docs/assets/logo.png" alt="VaultPass logo" width="120" height="120">
</p>

<h1 align="center">VaultPass</h1>

<p align="center">
  An offline password manager for Android.<br>
  Your vault is encrypted on the phone and needs no account or cloud service.
</p>

<p align="center">
  <a href="https://github.com/ArmaanCode2/Vault-pass/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/ArmaanCode2/Vault-pass?label=release&color=0B2A5B"></a>
  <img alt="Android 7.0 or newer" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin and Jetpack Compose" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white">
</p>

<p align="center">
  <a href="https://github.com/ArmaanCode2/Vault-pass/releases/latest"><b>Download</b></a>
  &nbsp;|&nbsp;
  <a href="https://armaancode2.github.io/Vault-pass/website/">Website</a>
  &nbsp;|&nbsp;
  <a href="docs/SECURITY.md">Security</a>
  &nbsp;|&nbsp;
  <a href="PRIVACY.md">Privacy</a>
  &nbsp;|&nbsp;
  <a href="docs/CHANGELOG.md">Changelog</a>
</p>

> [!NOTE]
> VaultPass is under active development. Keep your own backups of the vault (Settings, Export Vault).

VaultPass stores your passwords in an encrypted database on your phone. It works offline, and all encryption happens on the device. Its two network features are optional and described under [Privacy](#privacy).

## Features

### Vault

- Entries with a title, username, password, website, notes, category, tags and up to 50 custom fields
- Search across titles, usernames, websites, notes, categories, tags and custom field values
- Favorites and a password generator
- A recycle bin that keeps deleted entries for 7 days

### Security

- Master password derived with PBKDF2-HMAC-SHA256 (300,000 iterations); vaults created with fewer iterations are upgraded the next time you unlock with the master password
- Biometric unlock through the Android Keystore
- Auto-lock immediately, after 30 seconds to 15 minutes, or never
- Screenshots and the recent-apps preview blocked by default
- Copied passwords cleared from the clipboard after 30 seconds
- Cooldowns after failed unlocks, from 30 seconds after 5 failures up to 15 minutes after 20; restarting the phone doesn't skip a cooldown
- Security Center that lists weak, reused and missing passwords

### Autofill

- Fills logins in apps and browsers through the Android Autofill service (Android 8.0 or newer)
- Matches websites by registrable domain using the bundled Public Suffix List, and trusts page domains only from known browsers
- Offers an entry to an app only after you link the two: pick the entry once with "Search VaultPass…", or set its website to `androidapp://<package>`
- Shows "Tap to unlock VaultPass" on login fields while the vault is locked

### Sync with VaultPass Desktop

- Works with [VaultPass Desktop](https://github.com/ArmaanCode2/vault-pass-desktop) 1.1.0 or newer on Windows
- Pairing by scanning a QR code shown on the PC
- A direct connection over your local network, encrypted with AES-256-GCM after a P-256 key exchange
- Every difference is reviewed before it is applied, and deletions sync too

### Backup

- Encrypted `.vpex` backups protected by a password you choose
- Readable TXT and JSON exports
- Import from VPEX, JSON or TXT
- Backups made with 2.7.1 can't be opened by VaultPass 2.6.x or by VaultPass Desktop 1.1.0 and older

### Appearance

- System, Light, Dark and AMOLED (pure black) themes
- Five accent colors and four text sizes, applied as soon as you choose them

### Updates

- An optional check for new releases on GitHub, off by default
- Each download is checked before it installs: size, the SHA-256 digest GitHub publishes, package name, a newer version and the same signing key

## Download

Download the APK from the [latest release](https://github.com/ArmaanCode2/Vault-pass/releases/latest). VaultPass needs Android 7.0 or newer, and autofill needs Android 8.0 or newer.

### Verify the APK

- The [download page](https://armaancode2.github.io/Vault-pass/website/download.html) shows the SHA-256 that GitHub publishes for the latest APK, with a VirusTotal lookup for that hash.
- The v2.6.3 APK was scanned with VirusTotal: [VirusTotal report](https://www.virustotal.com/gui/file/36cade08a69950b7f5b13f0144476f1c73e1b12dbd28bf0de7346acacf8372d9?nocache=1).
- From 2.7.1, releases are signed with the release key below. Check a downloaded APK with `apksigner` from the Android SDK build-tools:

  ```sh
  apksigner verify --print-certs <path-to-apk>
  ```

  The signer's SHA-256 digest must be:

  ```text
  dd31394f0a429a9cfae92779675abcf725ab992b937162303b5d683747f13eac
  ```

## Upgrading from 2.6.x

> [!IMPORTANT]
> If you can't unlock VaultPass 2.6.x, do not uninstall it: uninstalling deletes the vault. Open a [GitHub issue](https://github.com/ArmaanCode2/Vault-pass/issues) first.

2.7.1 is the first build signed with the release key, so Android won't install it over 2.6.x.

1. Export a backup from VaultPass 2.6.x.
2. Uninstall 2.6.x.
3. Install 2.7.1 and import the backup.
4. Turn biometric unlock on again in Settings.
5. Link your apps for autofill again, once each, with "Search VaultPass…".
6. If you use VaultPass Desktop, update it and pair the phone again.

Later updates can come from inside VaultPass. The first in-app update shows Android's confirmation. Later ones may install without it: VaultPass closes, and you open it again to use the new version.

## Privacy

VaultPass works offline and doesn't require an account. It uses the network in two optional ways:

- **LAN sync** connects the phone directly to VaultPass Desktop on your local network. There is no VaultPass server in between.
- **The update check** is off by default. It asks GitHub (`api.github.com`) for the latest release and sends nothing from your vault.

Details are in the [privacy policy](PRIVACY.md) and the [sync protocol](docs/SYNC_PROTOCOL.md).

## Security architecture

| Part | Implementation |
| --- | --- |
| Master password | PBKDF2-HMAC-SHA256, 300,000 iterations, compared in constant time (`MessageDigest.isEqual`) |
| Vault key | A random data encryption key (DEK), held in memory only while the vault is unlocked |
| Key storage | The DEK is stored wrapped by a key derived from the master password; a second copy is wrapped by an Android Keystore key for biometric unlock |
| Entries | Title, username, password, website, notes, category, tags and custom fields are encrypted with AES-GCM in a Room database; the favorite flag, timestamps and recycle-bin state are not |
| KDF settings | Algorithm, iteration count and version are stored per vault, so they can change without breaking older vaults |
| KDF upgrades | Written in three steps (prepare, commit, finalize) so that a crash at any point leaves a vault that still unlocks |
| Auto-lock | Enforced through `ProcessLifecycleOwner` and activity lifecycle hooks |
| Release builds | `assembleRelease` and `bundleRelease` fail unless the output is signed with the pinned release key |

The full design is in [Security and Cryptography Architecture](docs/01_SECURITY_AND_CRYPTO.md).

## Tech stack

| Area | Used |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Architecture | MVVM |
| Storage | Room, Jetpack DataStore, SharedPreferences |
| Cryptography | `javax.crypto` (AES-GCM, PBKDF2), Android Keystore, BiometricPrompt |
| System integration | Android Autofill framework, CameraX and ZXing for QR pairing |

## Build from source

Requirements: an Android Studio version that supports Android Gradle Plugin 9.1, JDK 17 or newer, and the Android SDK with platform 36.1.

```sh
git clone https://github.com/ArmaanCode2/Vault-pass.git
cd Vault-pass
./gradlew assembleDebug        # debug APK; installs next to the release app as "VaultPass Debug"
./gradlew testDebugUnitTest    # unit tests
```

You can also open the project in Android Studio, let Gradle sync finish, and run the `app` configuration.

Release builds need the release signing key, passed through the `KEYSTORE_PATH` and `STORE_PASSWORD` environment variables (plus `KEY_PASSWORD` if the key's password differs). The key alias must be `upload`. Builds signed with any other key are rejected; a fork has to set its own certificate's SHA-256 as `vaultpass.releaseCertSha256` in `gradle.properties`.

## Roadmap

- Sync through encrypted cloud storage providers
- Autofill for credit cards and addresses
- Scheduled automatic backups

## Documentation

- [Security and cryptography](docs/01_SECURITY_AND_CRYPTO.md)
- [Security](docs/SECURITY.md)
- [Autofill](docs/AUTOFILL_GUIDE.md)
- [Biometric authentication](docs/BIOMETRIC_IMPLEMENTATION.md)
- [Sync protocol](docs/SYNC_PROTOCOL.md)
- [Database schema](docs/DATABASE_SCHEMA.md)
- [Development setup](docs/SETUP_DEVELOPMENT.md)
- [Changelog](docs/CHANGELOG.md)
- [Privacy policy](PRIVACY.md)

## Support

Report bugs and ask questions in [GitHub issues](https://github.com/ArmaanCode2/Vault-pass/issues).
