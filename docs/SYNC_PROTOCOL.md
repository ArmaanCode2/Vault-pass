# Vault Pass Mobile - Sync Protocol (Mobile-Desktop)

## Sync Architecture
VaultPass is strictly an **offline-first** application. There is currently no network-based synchronization protocol implemented in the codebase. All data transfer between devices must be handled manually by the user via file exports and imports.

### Planned Sync Method
- ❌ QR Code Pairing + Local Network
- ❌ Bluetooth Low Energy (BLE)
- ❌ WiFi Direct
- ❌ Cloud Sync 
- ✅ USB/File Export (Currently Implemented)

### Selected Method
**Manual File Export/Import (.vpex, .json, .txt)**
The application generates export payloads which the user must manually transfer to the target device.

## Pairing Process
`[UNCLEAR - Not Implemented]`
There is no device pairing process because network sync does not exist in the codebase.

## Data Sync Flow
### Export Format
The primary export format is a simplified JSON array map. Keys are password titles (with numeric suffixes if duplicates exist). The array contains `[username, password, {metadata}]`.

**Unencrypted JSON Format (`generateSimplifiedJsonExportPayload`):**
```json
{
  "Github": [
    "user@email.com",
    "password123",
    {
      "isFavorite": true,
      "website": "github.com",
      "category": "Work",
      "tags": ["dev", "important"],
      "customFields": {
        "Recovery Code": "1234-5678"
      }
    }
  ],
  "Twitter (1)": [
    "alt_account",
    "password456"
  ]
}
```

**Encrypted VPEX Format (`generateVpexExportPayload`):**
The `.vpex` format takes the JSON string above and encrypts it using a unique password provided by the user at the time of export.
- It generates a 16-byte random salt.
- Derives a KEK from the user's export password via `PBKDF2WithHmacSHA256`.
- Generates a 12-byte IV.
- Encrypts the JSON payload via `AES/GCM/NoPadding`.
- The final payload is concatenated as `[16-byte Salt] + [12-byte IV] + [Encrypted JSON Data]` and then Base64 encoded (`Base64.NO_WRAP`).

## Conflict Resolution
- When sync conflicts occur: `[UNCLEAR - Not Implemented]` (Imports currently overwrite or append depending on user action, but there is no automated Last-Write-Wins logic for network syncing).
- Version tracking: None.
- Code Location: N/A

## Encryption During Sync
- Transport Encryption: N/A (Manual file transfer).
- End-to-End Encryption: ✅ Yes (If `.vpex` format is used).
- Key Exchange: Out-of-band (The user must remember the password they typed during export and manually type it again during import on the new device).
- Code Location: `com.example.security.CryptoManager.encryptBackup()` and `decryptBackup()`.

## Current Implementation Status
- ✅ Implemented: Manual JSON export, Manual Encrypted VPEX export, Manual TXT export.
- 🔄 In Progress: None.
- ❌ Planned: Automated Network Syncing (Cloud/LAN).

## Code References
- Main Sync Manager: `[Not Implemented]`
- Serialization: `com.example.ui.VaultViewModel` (Methods: `generateSimplifiedJsonExportPayload`, `decodeImportPayload`)
- Encryption: `com.example.security.CryptoManager` (Methods: `encryptBackup`, `decryptBackup`)
