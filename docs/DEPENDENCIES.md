# Vault Pass Mobile - Dependencies

## Core Framework
- **Kotlin**: `2.2.10`
- **Jetpack Compose (BOM)**: `2024.09.00`
- **AndroidX Core KTX**: `1.18.0`

## Data Storage
- **Room Runtime/KTX/Compiler**: `2.7.0`
- **DataStore Preferences**: `1.1.7`

## Security & Cryptography
- **AndroidX Biometric**: `1.2.0-alpha05`
- **AndroidX Security Crypto KTX**: `1.1.0-alpha06`
*(Note: BouncyCastle or Tink are not used; the application relies on the native `java.security` and `javax.crypto` libraries backed by `AndroidKeyStore`)*

## UI Components
- **Material Design 3**: (Managed via Compose BOM `androidx.compose.material3:material3`)
- **Material Icons Core & Extended**: (Managed via Compose BOM)
- **Navigation Compose**: `2.8.9`
- **Coil Compose**: `2.7.0` (Image loading - declared in TOML)

## Architecture Components
- **Lifecycle ViewModel Compose**: `2.8.7`
- **Lifecycle Runtime Compose / KTX**: `2.8.7`
- **Activity Compose**: `1.10.1`

## Utility Libraries
- **Kotlinx Coroutines (Core & Android)**: `1.10.2`
- **Kotlinx Serialization JSON**: `1.6.3`
- **Google Accompanist Permissions**: `0.37.3` (Declared in TOML)

## LAN Sync & QR Pairing
- **ZXing Core**: `3.5.3` (decodes the pairing QR code on the device)
- **CameraX (camera2, lifecycle, view)**: `1.5.0` (camera preview for the QR scanner)
- Networking uses plain Java sockets; no networking library is involved.

## In-App Updates
- No library. The update check and download use `java.net.HttpURLConnection`, the install uses Android's `PackageInstaller`, and the GitHub response is parsed with Kotlinx Serialization JSON (already listed above).
- The release signing check in `app/build.gradle.kts` uses `com.android.apksig.ApkVerifier`, which comes with the Android Gradle Plugin; it runs at build time only and is not part of the app.

## Bundled Data
- **Public Suffix List**: `app/src/main/assets/public_suffix_list.dat`, an unmodified copy of https://publicsuffix.org/list/public_suffix_list.dat, version `2026-09-30_20-56-07_UTC` (commit `714ac1bf5f2d038161c7419478cc3207431d706d`), 334,832 bytes. Licensed under the Mozilla Public License 2.0; the notice with source, download date and SHA-256 is `app/src/main/assets/licenses/public_suffix_list_NOTICE.txt`. Autofill uses it (`com.example.service.PublicSuffixList`) to decide which hosts belong to the same site. It is read from the APK; the app never downloads it.
- **Privileged apps list**: `app/src/main/assets/privileged_browsers.json`, an unmodified copy of https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json (the list Google Password Manager uses, linked from developer.android.com/identity/sign-in/credential-provider), downloaded 2026-10-02, 29,657 bytes. The notice with source, SHA-256 and the license statement is `app/src/main/assets/licenses/privileged_browsers_NOTICE.txt`. Autofill uses it (`com.example.service.BrowserVerifier`) to trust a browser's web domain only when the browser's signing certificate is listed. It is read from the APK; the app never downloads it.

## Networking & APIs
*(Note: These are declared in `libs.versions.toml` but none of them is compiled into the app. They may be present for future integrations.)*
- **Retrofit**: `2.12.0`
- **Moshi (Kotlin & Codegen)**: `1.15.2`
- **OkHttp (Logging Interceptor)**: `4.10.0`
- **Firebase BOM**: `34.12.0`

## Android Testing Frameworks
- **JUnit 4**: `4.13.2`
- **AndroidX Test (Core/Runner)**: `1.6.1` / `1.6.2`
- **Espresso Core**: `3.7.0`
- **Compose UI Test JUnit4**: (Managed via Compose BOM)
- **Robolectric**: `4.16.1`
- **Roborazzi (Snapshot Testing)**: `1.59.0`
- **Kotlinx Coroutines Test**: `1.10.2`
