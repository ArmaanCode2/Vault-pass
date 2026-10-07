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
3. `AutofillFieldDetector` finds the `AutofillId`s of the username and password fields and the `webDomain` and `webScheme` of their frame (e.g. an iframe). The frame is the focused login field's (else the first password field's); both fields are taken from that frame only, so a field in another domain's frame is never filled. Hidden views (`visibility != VISIBLE`) and everything inside them are skipped. The locked and unlocked paths use the same detection.
4. The service checks the cryptographic state via `CryptoManager`.
    - **If Locked:** It returns a `FillResponse` whose authentication is attached to the detected login fields, so the "Tap to unlock VaultPass" chip appears on the real username/password fields. The chip fires a `PendingIntent` (`FLAG_MUTABLE` on Android 12+, new request code each time) to `AutofillAuthActivity`, which reads `EXTRA_ASSIST_STRUCTURE`, unlocks the vault and returns the matching datasets. If no login field is found, nothing is offered.
    - **If Unlocked:** It continues with matching.
5. **Deciding what the request is for** (`AutofillCredentialMatcher.resolveTarget()`):
    - *Web:* only when the requesting package is listed in Google's privileged-apps list (bundled as `assets/privileged_browsers.json`) and its current signing certificate matches a listed release fingerprint (`BrowserVerifier`), and it reported a usable `webDomain`. Only `release` fingerprints are used, never `userdebug` ones. The result is cached per process and checked again when the package is reinstalled or updated; any error means "not verified". A verified browser without a `webDomain` (its own UI) gets nothing. Kiwi, Cromite, Mull and Tor Browser are not in Google's list, so their `webDomain` is not trusted.
    - *Native app:* any other package. Its `webDomain` is ignored, because an app can put any domain into its view structure. An unverified package that uses a known browser's name (`BROWSER_PACKAGE_NAMES`) gets nothing.
6. **Filtering** (deleted entries and entries that can't be decrypted never match):
    - *Web:* An entry matches when the host of its `website` equals the page's host (`www.` ignored, score 200) or both hosts have the same registrable domain according to the bundled Public Suffix List (score 150). Hosts are compared in lowercase ASCII form (IDN via `java.net.IDN`). `mail.google.com` matches an entry for `google.com`; `a.github.io` does not match `b.github.io`; a lookalike IDN host does not match the ASCII brand. When the frame's scheme is known to be `http`, only entries whose website starts with `http://` match (https and scheme-less entries are not offered).
    - *Native App:* An entry matches when the user linked it to the requesting package (table `autofill_app_links`) or when its `website` is exactly `androidapp://<package>` (score 200). Titles, app labels and package-name similarity are not used.
7. Show suggestions: For each matching entry, a `Dataset` is built using `R.layout.autofill_dropdown_item` displaying `Title (Username)`. A last item, **"Search VaultPass…"**, is added for every request that has a requesting package (apps, and verified browsers on an identified page).
8. User selects an entry from the Android OS dropdown, or taps "Search VaultPass…".
9. Fields are filled: The OS applies the `AutofillValue.forText()` payloads directly to the target `AutofillId`s.

### "Search VaultPass…"
- An authentication-required dataset whose `PendingIntent` opens `AutofillAuthActivity` in pick mode. Pick mode, the requesting package and, for a verified browser, the page's host are carried in the intent's data URI (`vaultpass-autofill-pick:<package>#<host>`), which a fill-in intent can't replace; the locked-vault chip uses its own data URI (`vaultpass-autofill-unlock:response`).
- The pick screen (`AutofillPickScreen`) shows the website or app being filled at the top ("Filling on the website bank.com" / "Filling in the app Label (package)"). When the picked entry's registrable domain differs from the page's, it asks "This entry is for example.com, the page is evil.org. Fill anyway?" before filling.
- It unlocks the vault if needed and lists entries, filtered by title, username and website. Passwords, notes and custom fields are not searched, and entries that can't be decrypted are left out.
- Picking an entry fills it. For a native app (never a web page, a known browser package or VaultPass itself) the pick also saves a link (`packageName`, entry `syncId`) so the app is offered that entry directly next time.
- Links are local to the device: they are not part of sync records, exports or backups. They follow an entry when its `syncId` is renamed by a sync and are deleted when the entry is permanently deleted (by hand or by the 7-day Recycle Bin cleanup). Links whose entry no longer exists match nothing.
- 2.6.x matched apps by title and app label. Those matches are gone, so each app has to be linked once through "Search VaultPass…".

## Integration Points
- Service Implementation: `com.example.service.VaultAutofillService` (Inherits from `android.service.autofill.AutofillService`).
- Field Detection: `com.example.service.AutofillFieldDetector`.
- Matching: `com.example.service.AutofillCredentialMatcher`; Public Suffix List parser `com.example.service.PublicSuffixList` (data in `app/src/main/assets/public_suffix_list.dat`, MPL-2.0, notice in `assets/licenses/public_suffix_list_NOTICE.txt`). If the list can't be loaded, only exact host matches work.
- Browser verification: `com.example.service.BrowserVerifier` / `PrivilegedBrowserList` (data in `app/src/main/assets/privileged_browsers.json`, notice in `assets/licenses/privileged_browsers_NOTICE.txt`).
- Pick Mode: `com.example.service.AutofillPick`, `com.example.ui.AutofillAuthActivity`, `com.example.ui.screens.AutofillPickScreen`.
- App Links: `VaultRepository.linkAutofillApp()` / `linkedAutofillSyncIds()`, table `autofill_app_links` (see `DATABASE_SCHEMA.md`).
- Dataset Building: Retrieves decrypted entries synchronously via `app.container.vaultRepository.getAllEntriesSync()` inside a `Dispatchers.Default` coroutine.
- Field Validation: A rigid gatekeeper logic rejects `className`s containing "layout" (unless they are "edittext") to prevent false positives and reduce noise during traversal.

## Compatibility
- Works with: Most native Android applications (once linked, or with an `androidapp://` website) and verified browsers (Google's privileged-apps list + signing certificate) when they populate `webDomain`. Other browsers are handled like native apps.
- Minimum Android API: API 26 (Android 8.0 Oreo - the introduction of the Autofill Framework).
- Known limitations: Browsers or apps that render their own custom canvas for text inputs (bypassing native Android Views) will not trigger the `AssistStructure` correctly.

## Current Status
- ✅ Credential Autofill (Native Apps & Web)
- ✅ Locked-state Authentication Bridge (`AutofillAuthActivity`), attached to the detected login fields
- ✅ Web matching by registrable domain (bundled Public Suffix List), browsers verified by signing certificate
- ✅ App links through "Search VaultPass…" and `androidapp://<package>` websites
- ✅ Heuristic DOM Traversal for legacy apps
- ❌ Credit Card / Address Autofill
- ❌ On-Save Request (`onSaveRequest` is currently a no-op stub returning `callback.onSuccess()`)
