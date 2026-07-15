# Vault Pass Mobile - Development Setup

## Prerequisites
- **Android Studio**: Ladybug or later (Recommended based on AGP 9.1.1)
- **JDK Version**: Java 11 (`sourceCompatibility = JavaVersion.VERSION_11`)
- **Emulator/Device**: Android 7.0 (API 24) or higher

## Build Configuration
- **AGP Version**: `9.1.1`
- **Gradle Wrapper**: `9.3.1` (Configured in `gradle/wrapper/gradle-wrapper.properties`)
- **Compile SDK**: `36`

## Secrets / Keys
- **Environment Variables**: The project utilizes the Secrets Gradle Plugin configured to read from a `.env` file (with a `.env.example` fallback). However, because the app operates strictly offline, no external API keys (like Firebase or Google Maps) are strictly required to build and run the `debug` variant.
- **Release Keystore**: Building the `release` variant requires environment variables to be set in your CI/CD or local environment:
  - `KEYSTORE_PATH` (defaults to `my-upload-key.jks`)
  - `STORE_PASSWORD`
  - `KEY_PASSWORD`

## Running the App
1. Clone the repository: `git clone https://github.com/ArmaanCode2/Vault-pass.git`
2. Open the project in Android Studio.
3. Sync the Gradle files.
4. Select the `app` run configuration and deploy to an Emulator or physical device running API 24+.

## Code Style / Architecture Rules
- **UI Framework**: Use Jetpack Compose for all new UI screens. XML layouts are strictly prohibited for UI unless required by specific Android APIs (e.g., Autofill RemoteViews).
- **Architecture**: Follow the MVVM (Model-View-ViewModel) pattern.
- **Dependency Injection**: Use the existing manual DI pattern (`AppContainer`), do not introduce Hilt or Dagger.
- **Security**: 
  - Do not commit actual passwords or personal testing data into the repository.
  - Never log raw decryption payloads or master passwords to Logcat.

## Testing Locally
- **Command to run Unit Tests**: `./gradlew testDebugUnitTest`
- **Command to run UI Instrumentation Tests**: `./gradlew connectedDebugAndroidTest`
- **Snapshot Tests**: Roborazzi is configured. Use `./gradlew recordRoborazziDebug` to capture baseline images and `./gradlew verifyRoborazziDebug` to test against them.
