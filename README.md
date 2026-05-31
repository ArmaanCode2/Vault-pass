# VaultPass

> ⚠️ This project is under active development. Always maintain independent backups of your vault data.

VaultPass is a secure, offline-first Android password manager built with Kotlin and Jetpack Compose.

The application is designed for users who want full control over their credentials without relying on cloud services, online accounts, or third-party synchronization.

All password data is stored locally on the user's device, allowing the vault to function completely offline.

---

## Why VaultPass?

Many password managers require cloud accounts, subscriptions, or internet connectivity.

VaultPass focuses on:

- Offline password storage
- Local-first architecture
- Fast password retrieval
- Secure vault management
- Simple and modern user experience

Your data remains on your device and is accessible without an internet connection.

---

## Features

### 🔐 Secure Vault

- Master password protected vault
- Local encrypted storage
- Offline-first design
- No account creation required

### 📝 Password Management

- Create password entries
- Edit existing entries
- Delete existing entries
- Favorite important entries
- Organize credentials efficiently

### 🧩 Custom Fields

VaultPass supports unlimited custom fields for every entry.

Examples:

- API Keys
- Recovery Codes
- License Keys
- Server Credentials
- Security Questions
- Notes

### 🔎 Search

Quickly search through stored entries using the built-in search functionality.

Search supports:

- Titles
- Usernames
- Categories
- Custom fields

### 📥 Import JSON

Import credentials from JSON files directly into the vault.

Supported JSON formats can be imported and converted into VaultPass entries.

### 📤 Export JSON

Export vault entries to JSON format for:

- Backups
- Migration
- Data portability

### ⭐ Favorites

Mark frequently used entries as favorites for faster access.

### 📂 Categories

Organize entries into categories such as:

- Email
- Social Media
- Work
- Banking
- Gaming
- Custom Categories

### 🎨 Themes

- Light Theme
- Dark Theme
- System Theme

### 🌐 Offline Operation

VaultPass does not require:

- Internet access
- Cloud synchronization
- Online accounts
- External services

---


## Installation

### APK Installation

1. Download the latest APK from the Releases page.
2. Transfer the APK to your Android device.
3. Install the APK.
4. Launch VaultPass.
5. Create a master password.
6. Start storing credentials.

---

## Privacy

VaultPass is designed to operate locally on your device.

The application:

- Does not require an account
- Does not use cloud synchronization
- Does not upload vault data
- Does not require internet access for core functionality

---

# Development

## Requirements

- Android Studio
- Android SDK
- JDK 17 or newer
- Gradle

---

## Clone Repository

```bash
git clone https://github.com/ArmaanCode2/VaultPass.git
cd VaultPass
```

---

## Open Project

1. Open Android Studio.
2. Select **Open Project**.
3. Choose the VaultPass project folder.
4. Wait for Gradle Sync to complete.

---

## Build APK

Inside Android Studio:

```text
Build → Assemble Project
```

Generated APK location:

```text
app/build/outputs/apk/debug/
```

---

## Run on Emulator

1. Open Device Manager.
2. Create an Android Virtual Device.
3. Start the emulator.
4. Press Run.

---

## Run on Physical Device

1. Enable Developer Options.
2. Enable USB Debugging.
3. Connect device to your computer.
4. Allow USB debugging.
5. Press Run in Android Studio.

---

## Contributing

Contributions are welcome.

1. Fork the repository.
2. Create a feature branch.
3. Make your changes.
4. Test your changes.
5. Submit a Pull Request.

Bug reports and feature requests can be submitted through GitHub Issues.

---