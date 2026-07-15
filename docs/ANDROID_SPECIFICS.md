# Vault Pass Mobile - Android Specifics

## Target Configuration
- Min SDK: `24`
- Target SDK: `36`
- Compile SDK: `36` (Specified as `release(36) { minorApiLevel = 1 }`)
*(Source: `app/build.gradle.kts`)*

## Permissions
```xml
<!-- Required to query installed apps for Autofill heuristic matching (e.g., matching a package name like 'com.twitter.android' to a Vault title) -->
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />

<!-- Implicitly merged from androidx.biometric dependency -->
<!-- <uses-permission android:name="android.permission.USE_BIOMETRIC" /> -->
<!-- <uses-permission android:name="android.permission.USE_FINGERPRINT" /> -->
```

### Permission Usage
| Permission | Purpose | Code Location |
|-----------|---------|----------------|
| `QUERY_ALL_PACKAGES` | Allows the Autofill Service to query the `PackageManager` for the label of the app requesting autofill, improving matching accuracy. | `com.example.service.VaultAutofillService` |
| `USE_BIOMETRIC` | Enables the `BiometricPrompt` for hardware-backed master key decryption. | `com.example.security.BiometricCryptoHelper` |
| `BIND_AUTOFILL_SERVICE` | Required by the OS to bind to VaultPass as an autofill provider. | `AndroidManifest.xml` (`<service>`) |

## Android Lifecycle
The application utilizes a Single-Activity architecture (`MainActivity.kt`). 
- **Persistence:** UI states are hoisted in `VaultViewModel` using `StateFlow`, persisting across configuration changes. 
- **Security Lifecycle:** The app uses `ProcessLifecycleOwner.get().lifecycle.addObserver()` to detect when the entire application goes into the background (`onStop`). This triggers an auto-lock timer. If the timer expires before the app returns to the foreground (`onStart`), the `Software DEK` is scrubbed from memory, forcing the user to re-authenticate.

## Background Operations
- **Sync Service:** ❌ Not Implemented (Offline only).
- **Work Manager Tasks:** ❌ Not Used.
- **Coroutines:** Fire-and-forget background tasks (like `cleanupRecycleBin` and KDF upgrades) are launched via `viewModelScope.launch(Dispatchers.IO)` directly tied to the ViewModel lifecycle, ensuring they run off the main thread but do not run persistently when the app is closed.

## Autofill Framework Integration
VaultPass implements a native Android Autofill Service using custom DOM traversal heuristics.
- **Service Class:** `com.example.service.VaultAutofillService`
- **Configuration:** `@xml/autofill_service_config`
- **Supported autofill fields:** 
  - `Username / Email`: Detected via `AUTOFILL_HINT_USERNAME`, `AUTOFILL_HINT_EMAIL_ADDRESS`, or by checking if the View ID/Hint contains "username" or "email".
  - `Password`: Detected via `AUTOFILL_HINT_PASSWORD`, `current-password`, `new-password`, or via InputType variations (`TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`).
- **Security Bridge:** If the vault is locked when autofill is requested, it launches a `PendingIntent` to `AutofillAuthActivity` to authenticate the user before releasing credentials.

## UI Framework
- **Framework:** 100% Jetpack Compose (`androidx.compose.ui:ui`). XML layouts are not used for UI screens.
- **View Binding:** No (`buildFeatures { viewBinding = true }` is absent).
- **Layout System:** Standard Compose Modifiers, `Scaffold`, `LazyColumn` for lists, and custom Material 3 components.

## Notification Handling
- ❌ Not Implemented. The application does not use push notifications or local Android notifications.

## Android Testing
- **Unit Tests Framework:** JUnit 4 (`junit:junit`), Robolectric (`org.robolectric:robolectric`), and Kotlinx Coroutines Test (`kotlinx-coroutines-test`).
- **Snapshot Testing:** Roborazzi (`io.github.takahirom.roborazzi`) for automated UI screenshot verification.
- **Instrumentation Tests:** Espresso Core (`androidx.test.espresso:espresso-core`) and Compose UI Test (`androidx.compose.ui.test.junit4`).
