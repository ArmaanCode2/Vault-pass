# Vault Pass Mobile - Android Specifics

## Target Configuration
- Min SDK: `24`
- Target SDK: `36`
- Compile SDK: `36` (Specified as `release(36) { minorApiLevel = 1 }`)
*(Source: `app/build.gradle.kts`)*

## Permissions
```xml
<!-- Declared in app/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION" />

<!-- Implicitly merged from androidx.biometric dependency -->
<!-- <uses-permission android:name="android.permission.USE_BIOMETRIC" /> -->
<!-- <uses-permission android:name="android.permission.USE_FINGERPRINT" /> -->
```

`QUERY_ALL_PACKAGES` is not declared. Autofill takes the requesting app's package name from the fill request (`AssistStructure.activityComponent`) and never looks up other installed apps; `SecurityHardeningTest` fails if the permission comes back into the manifest.

### Permission Usage
| Permission | Purpose | Code Location |
|-----------|---------|----------------|
| `USE_BIOMETRIC` | Enables the `BiometricPrompt` for hardware-backed master key decryption. | `com.example.security.BiometricCryptoHelper` |
| `BIND_AUTOFILL_SERVICE` | Required by the OS to bind to VaultPass as an autofill provider. | `AndroidManifest.xml` (`<service>`) |
| `INTERNET` | Required by Android for any socket: the local-network connections of LAN sync and the optional HTTPS update check against GitHub. The Privacy Policy link opens in the browser and does not need it. | `com.example.network.sync.LanSocketTransport`, `com.example.update.UpdateHttp` |
| `REQUEST_INSTALL_PACKAGES` | Lets the in-app updater hand a downloaded, verified update to `PackageInstaller`. The user still has to allow VaultPass under "Install unknown apps" (checked with `canRequestPackageInstalls()` on Android 8+); the update dialog explains this and opens that settings page. | `com.example.update.UpdateInstaller`, `com.example.update.UpdateController` |
| `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | On Android 12+ the install session asks for `USER_ACTION_NOT_REQUIRED`. Android honours it only when VaultPass is the installer of record of the installed app, so the first in-app update always shows Android's confirmation and later ones may install without it. | `com.example.update.UpdateInstaller.sessionParams()` |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | Finds the phone's local address and broadcast addresses for LAN sync discovery. | `com.example.network.sync.LanDiscoveryManager` |
| `CAMERA` | Scans the pairing QR code shown by VaultPass Desktop. The camera is optional hardware (`required="false"`). | `com.example.ui.sync.CameraQrScanScreen` |

## Android Lifecycle
The application utilizes a Single-Activity architecture (`MainActivity.kt`). 
- **Persistence:** UI states are hoisted in `VaultViewModel` using `StateFlow`, persisting across configuration changes. 
- **Security Lifecycle:** The app uses `ProcessLifecycleOwner.get().lifecycle.addObserver()` to detect when the entire application goes into the background (`onStop`). This triggers an auto-lock timer. If the timer expires before the app returns to the foreground (`onStart`), the `Software DEK` is scrubbed from memory, forcing the user to re-authenticate.

## Background Operations
- **Sync Service:** ❌ None. LAN sync with VaultPass Desktop runs only while the Sync screen is open, inside `LanSyncViewModel`; nothing syncs in the background.
- **Update check:** `MainActivity.onCreate` calls `UpdateController.onAppOpen()` once per process. It runs on `AppContainer.applicationScope` and either offers an already downloaded update or, only when "Check for updates when the app opens" is on, asks the GitHub releases API. Downloads and installs also run on that scope while the process lives; nothing is scheduled for later.
- **Work Manager Tasks:** ❌ Not Used.
- **Coroutines:** Fire-and-forget background tasks (like `cleanupRecycleBin` and KDF upgrades) are launched via `viewModelScope.launch(Dispatchers.IO)` directly tied to the ViewModel lifecycle, ensuring they run off the main thread but do not run persistently when the app is closed.

## Autofill Framework Integration
VaultPass implements a native Android Autofill Service using custom DOM traversal heuristics.
- **Service Class:** `com.example.service.VaultAutofillService`
- **Configuration:** `@xml/autofill_service_config`
- **Supported autofill fields:** 
  - `Username / Email`: Detected via `AUTOFILL_HINT_USERNAME`, `AUTOFILL_HINT_EMAIL_ADDRESS`, or by checking if the View ID/Hint contains "username" or "email".
  - `Password`: Detected via `AUTOFILL_HINT_PASSWORD`, `current-password`, `new-password`, or via InputType variations (`TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`).
- **Field detection:** `com.example.service.AutofillFieldDetector`, shared by the locked and unlocked paths.
- **Security Bridge:** If the vault is locked, the authentication ("Tap to unlock VaultPass") is attached to the detected username/password `AutofillId`s, so Android shows the chip on the real login fields. Its `PendingIntent` opens `AutofillAuthActivity`, which reads `EXTRA_ASSIST_STRUCTURE` and returns the matching datasets after unlock. The `PendingIntent` is `FLAG_MUTABLE` on Android 12+ (Android adds the structure as a fill-in extra) with a new request code each time. Without a detected login field nothing is offered.
- **Matching (`com.example.service.AutofillCredentialMatcher`):**
  - *Web:* the request's `webDomain` is used only when the requesting package is listed in Google's privileged-apps list (bundled as `assets/privileged_browsers.json`) and its current signing certificate matches a listed release fingerprint (`BrowserVerifier`). A `<queries>` entry for https-browsable apps makes browsers visible for that check (no `QUERY_ALL_PACKAGES`). The login fields and the `webDomain` come from one frame (the focused login field's), hidden fields are skipped, and on an `http` page only entries saved with `http://` match. An entry matches on the exact host (`www.` ignored, score 200) or the same registrable domain from the bundled Public Suffix List (score 150), so `mail.google.com` matches `google.com` but `a.github.io` does not match `b.github.io`.
  - *Native apps:* any other package is treated as an app and its `webDomain` is ignored. Entries match only when the user linked them to that package (`autofill_app_links`) or when the entry's website is exactly `androidapp://<package>`. Titles, app labels and package-name similarity are not used.
  - *"Search VaultPass…":* an extra authentication-required dataset that opens `AutofillAuthActivity` in pick mode. Picking an entry fills it and, for a native app (never a browser or VaultPass itself), stores the link so the app is offered that entry directly next time.

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
