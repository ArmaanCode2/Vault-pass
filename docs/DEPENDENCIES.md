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

## Networking & APIs
*(Note: These are declared in `libs.versions.toml` but the app operates strictly offline. They may be present for future integrations.)*
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
