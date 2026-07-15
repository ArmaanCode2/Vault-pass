# Vault Pass Mobile - Android Autofill Integration

## Autofill Service Setup
- Service Class: `com.example.service.VaultAutofillService`
- Configuration: `res/xml/autofill_service_config.xml` (declared in `AndroidManifest.xml` via `<meta-data android:name="android.autofill" ... />`)
- Is Enabled by Default: Yes (Requires user to explicitly select VaultPass as the system default Autofill provider in Android Settings).

## Supported Autofill Fields
- **Username/Email fields:** Detected if explicit Android `autofillHints` contain `AUTOFILL_HINT_USERNAME` or `AUTOFILL_HINT_EMAIL_ADDRESS`. If hints are missing, the service falls back to checking if the View's `idEntry` or `hint` text contains the substrings "username" or "email".
- **Password fields:** Detected via `AUTOFILL_HINT_PASSWORD`, `current-password`, `new-password`, or via Android InputTypes (`TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_TEXT_VARIATION_WEB_PASSWORD`, `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`). Fallback string heuristics also check for the substring "password" in IDs and hints.
- **Other fields:** ❌ Not Implemented (The current integration exclusively targets credentials; credit cards and addresses are ignored).

## Autofill Flow
1. User opens a login form in a native Android app or a supported browser.
2. The Android OS invokes `VaultAutofillService.onFillRequest()`, passing an `AssistStructure` representing the screen's DOM.
3. The service checks the cryptographic state via `CryptoManager`.
    - **If Locked:** It returns a `FillResponse` requiring authentication, presenting a "Tap to unlock VaultPass" dropdown that fires a `PendingIntent` to `AutofillAuthActivity`.
    - **If Unlocked:** It proceeds to traverse the `AssistStructure`.
4. The traversal identifies the `AutofillId`s for the username and password fields.
5. The service attempts to extract a `webDomain` (if in a browser) or uses `PackageManager` to retrieve the `applicationLabel` (if in a native app).
6. **Filtering:**
    - *Web:* Filters the decrypted `VaultRepository` entries by matching the extracted base domain against the entry's stored `website` field.
    - *Native App:* Uses a fuzzy scoring heuristic (Score 100 for exact title match, 85/80 for partial string overlap with the app label, 60 for package name match).
7. Show suggestions: For each matching entry, a `Dataset` is built using `R.layout.autofill_dropdown_item` displaying `Title (Username)`.
8. User selects a password from the Android OS dropdown.
9. Fields are filled: The OS applies the `AutofillValue.forText()` payloads directly to the target `AutofillId`s.

## Integration Points
- Service Implementation: `com.example.service.VaultAutofillService` (Inherits from `android.service.autofill.AutofillService`).
- Dataset Building: Retrieves decrypted entries synchronously via `app.container.vaultRepository.getAllEntriesSync()` inside a `Dispatchers.IO` coroutine.
- Field Validation: A rigid gatekeeper logic rejects `className`s containing "layout" (unless they are "edittext") to prevent false positives and reduce noise during traversal.

## Compatibility
- Works with: Most native Android applications and modern mobile browsers (Chrome, Firefox, Edge) that correctly populate `webDomain` or `autofillHints`.
- Minimum Android API: API 26 (Android 8.0 Oreo - the introduction of the Autofill Framework).
- Known limitations: Browsers or apps that render their own custom canvas for text inputs (bypassing native Android Views) will not trigger the `AssistStructure` correctly.

## Current Status
- ✅ Credential Autofill (Native Apps & Web)
- ✅ Locked-state Authentication Bridge (`AutofillAuthActivity`)
- ✅ Heuristic DOM Traversal for legacy apps
- ❌ Credit Card / Address Autofill
- ❌ On-Save Request (`onSaveRequest` is currently a no-op stub returning `callback.onSuccess()`)
