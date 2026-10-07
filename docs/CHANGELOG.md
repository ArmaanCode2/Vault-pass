# Vault Pass Mobile - Changelog

## [Unreleased]
*(Version 2.7.0 / Build 13, not released yet)*

LAN sync with VaultPass Desktop, rebuilt for security and reliability. Update both apps together: this version can't sync with older versions, and every phone must be paired again once. This version also adds optional in-app updates and changes how autofill matches websites and apps.

### Before you update
- **If you can't unlock VaultPass 2.6.x, do NOT uninstall it: uninstalling deletes the vault. Contact us via GitHub issues first: https://github.com/ArmaanCode2/Vault-pass/issues**
- 2.7.0 is the first build signed with the release key, so it can't install over 2.6.x: export a backup, uninstall, install 2.7.0, then restore.
- After that, updates can come from inside VaultPass. The first in-app update shows Android's confirmation. Later updates may install without it: VaultPass just closes, and you open it again to use the new version.
- Apps need to be re-linked once for autofill: use "Search VaultPass…" in each app.

### Added
- **Reviewed sync that ends identical on both devices:** The device that starts a sync reviews every difference and chooses what happens to each entry; conflicts must be answered. The other device sees a summary and applies or declines it. Both apply the same changes and compare a fingerprint of their vaults, so a sync only reports success when the two vaults match.
- **Deletions sync:** Entries deleted on one device, including ones purged from the Recycle Bin, are deleted on the other device too.
- **Custom fields sync** with their entry.
- **Sync errors you can read:** Every failed sync shows its reason in an error dialog, and the other device is told why the sync stopped.
- **In-app updates, off by default:** Settings has "Check for updates when the app opens" and "Check now". VaultPass asks GitHub for the latest release (`api.github.com/repos/ArmaanCode2/Vault-pass/releases/latest`) and offers "Update to latest version X.Y.Z". Tapping it downloads the release asset `VaultPass.apk` and checks it: the size GitHub lists, GitHub's SHA-256 digest (a release without one is refused: "This release has no checksum, so it can't be verified"), the same package name, a higher version and the same signing key (an APK whose signer Android can't report is refused, except on Android 7-8.1, where Android's own install check still enforces the key). "Restart now" hands the file to Android's installer, which closes VaultPass while it installs. The first in-app update shows Android's dialog and then offers "Open"; later updates may install without Android's confirmation, and VaultPass just closes: open it again. "Later" keeps the verified file, and the next time the app starts it offers "Install update X.Y.Z" without downloading again. Nothing from the vault is sent.
- **"Search VaultPass…" in the autofill list:** Opens a list of your entries to pick one to fill. In an app (not a browser), VaultPass remembers the pick on this phone and offers that entry to the app directly from then on.
- **`androidapp://<package>` websites:** An entry whose website is `androidapp://` followed by an app's package name is offered to that app.

### Changed
- **Database:** Room upgraded to Version 4. Version 3 adds a `syncId` column, filled in automatically for existing entries, and the `sync_tombstones` table. Version 4 (`MIGRATION_3_4`) only adds the `autofill_app_links` table, which stays on this phone and is never synced or exported.
- **Autofill website matching:** An entry matches a web page on the exact host, or on the same registrable domain using the bundled Public Suffix List: `mail.google.com` matches an entry for `google.com`, but `a.github.io` doesn't match `b.github.io`. Page domains are trusted only from browsers on Google's privileged-apps list whose signing certificate matches that list (Chrome, Firefox, Edge, Samsung Internet, Brave, DuckDuckGo, Opera, Vivaldi and others); a domain reported by any other app is ignored. Kiwi, Cromite, Mull and Tor Browser are not on Google's list and are handled like apps. Only visible fields of the login field's own frame are filled, never fields in another site's iframe. On an `http://` page only entries saved with `http://` are offered. In a browser, "Search VaultPass…" shows the page's domain and asks before filling an entry for another site.
- **Autofill app matching:** Apps are offered only entries linked to them through "Search VaultPass…" or an `androidapp://` website. Entry titles, app names and package names are no longer compared, so a lookalike app can't pick up another app's login.
- **Entry lists:** The second line under an entry's title is its username, or the website's host when there is no username.
- **Debug builds** install next to the release app as `com.aistudio.vaultpass.zxqwej.debug` and are named "VaultPass Debug".
- **Networking only while syncing:** Discovery and the sync listener run only while the Sync screen is open.
- **Pairing starts on the desktop:** The phone pairs by scanning the desktop's QR code and never accepts incoming pairing requests.
- **Larger vaults:** Entries are sent in parts of about 1 MB.
- **Editor limits raised and shared with the desktop:** Title 200 characters, username 300, password 1,000, website 500, notes 20,000, custom field name 100 and value 2,000, up to 50 custom fields and 25 tags. Reaching the custom field or tag maximum now shows a message.
- **Privacy Policy** now describes LAN sync, discovery, the camera, the optional update check and autofill app links.

### Security
- **Sync protocol v2:** Each connection starts with a P-256 key exchange authenticated by the pairing key, and every message is encrypted with AES-256-GCM using a key and a counter per direction. A replayed, reordered or altered message ends the connection.
- **The QR code's secret never goes over the network**, and pairing keys are stored encrypted with the vault key. Pairings from the old protocol, whose keys were sent and stored in plain text, are deleted.
- **Discovery beacons carry no device name or ID**, and a recorded beacon can't redirect a device to another address.
- **One sync connection at a time:** Other devices are turned away while a sync is in progress.
- **Release signing:** Release builds must be signed with the release key. Packaging the APK (`assembleRelease`) or the bundle (`bundleRelease`) fails without a key, with the Android debug key, or with a key whose fingerprint differs from the pinned `vaultpass.releaseCertSha256`, and a rejected APK or bundle is deleted.
- **Entry lists never show a secret:** With an empty username, the list could show the entry's password or a custom field value as the second line. It now shows the website's host or nothing.
- **Updates are verified before install:** A download that fails the size, checksum, package, version or signer check is deleted, and only HTTPS addresses are used for the update check, the download and every redirect.

### Fixed
- Finishing a sync with nothing to change now records the sync time and disconnects.
- Synced or imported entries longer than the old editor limits can be edited and saved.
- **Lockout after a reboot:** A failed-unlock cooldown could last far longer than intended after the phone restarted. The remaining time is now always between zero and the cooldown (at most 15 minutes); after a reboot the cooldown starts again from the boot.
- **Upgrading very old vaults:** Unlocking a vault from an early version that still used the old key format could report success without unlocking, and entries could fail to migrate. VaultPass now reads every entry with the old keys first and migrates those it can read in one transaction. Entries it can't read are left unchanged and shown as "Decryption Failed". If no entry can be read, nothing is changed and the lock screen says so.
- **Setup never replaces an existing vault:** If vault data exists but its settings can't be read, VaultPass shows "Vault can't be opened" with a retry instead of offering to create a new vault, and setup refuses to run over an existing vault.
- **Autofill unlock chip:** "Tap to unlock VaultPass" is attached to the detected username and password fields, so it appears when one of them is focused.

## [2.6.2] - Current Release
*(Version 2.6.2 / Build 10)*

### Added
- **Recycle Bin (Soft Delete):** Deleted passwords are now moved to a secure Recycle Bin and are automatically purged after 7 days to prevent accidental data loss.
- **Advanced Autofill Heuristics:** Improved Autofill framework integration with fuzzy native app matching (Application Label vs Vault Title) and intelligent DOM traversal to bypass restrictive web forms.
- **Privacy Policy:** Added a Privacy Policy link directly to the biometric Lock Screen.
- **Multi-Format Export/Import:** Added support for importing and exporting data in `.json`, `.txt`, and encrypted `.vpex` formats with a cascading fallback parser.
- **Dynamic Theming:** Deep integration with Material 3 dynamic color tokens (`surfaceVariant`) to ensure WCAG contrast compliance across custom accent colors.

### Changed
- **Database Migration:** Room Database upgraded to Version 2 (introduced `isDeleted` and `deletedAt` columns for Recycle Bin support).
- **Security Center Routing:** Tapping a dashboard security stat card now navigates directly into dedicated Security Center sub-routes, deprecating the legacy main-list filter behavior.
- **Auto-Lock Overrides:** Introduced the `isPerformingSystemOperation` exemption flag to temporarily bypass the Auto-Lock system while Android OS overlays (like the Import/Export file picker) are active.

### Security
- **KDF Architecture Upgrade:** Transitioned to PBKDF2-HMAC-SHA256 with 300,000 iterations for Master Password derivation.
- **Brute-Force Protection:** Implemented exponential cooldown thresholds (30s, 60s, 5m, 15m) triggered at 5, 10, 15, and 20+ failed authentication attempts. Tracking metrics are stored securely in DataStore.
- **Constant-Time Verification:** Upgraded the Master Password verification flow to utilize constant-time `MessageDigest.isEqual()` to mitigate timing side-channel attacks.
- **Biometric Enforcement:** Hardened the Android KeyStore integration to ensure biometric DEK wrapping keys are permanently invalidated if the user modifies device-level fingerprint enrollments.

## [1.0.0] - Initial Release
*(Historical Baseline)*

### Added
- Core Vault CRUD operations.
- Initial AES-GCM-256 Software DEK architecture.
- Base Android Autofill integration.
- Light/Dark mode support.
