# Vault Pass Mobile - Changelog

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
