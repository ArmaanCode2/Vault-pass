/*
 * VaultPass for Android: interactive demo with sample data.
 *
 * A clickable replica of the Android app (Kotlin, Jetpack Compose, Material 3) for the website.
 * Every screen follows the app's source in app/src/main/java/com/example/:
 *   ui/screens/VaultApp.kt (navigation and bottom bar), DashboardScreen.kt, SecurityScreen.kt,
 *   WeakPasswordsScreen.kt, ReusedPasswordsScreen.kt, MissingPasswordsScreen.kt,
 *   PasswordGeneratorScreen.kt, SettingsScreen.kt, UpdateUi.kt, LockScreen.kt,
 *   PasswordDetailsScreen.kt, PasswordEntryScreen.kt, RecycleBinScreen.kt, ui/sync/LanSyncScreen.kt,
 *   domain/security/SecurityAnalyzer.kt and PasswordGenerator.kt, repository/SettingsRepository.kt.
 *
 * Usage: window.VaultPassDemos.android.mount(hostEl, options) returns an API object; nothing mounts on
 * its own. Styles live in css/demo-mobile.css (root class .vpm). Icons are Material Icons path data
 * (Apache-2.0), taken from androidx.compose.material:material-icons-*:1.7.0, the set the app uses.
 */
(function () {
  'use strict';

  /* ------------------------------------------------------------------ Material icons */

  var ICONS = {
    lock: "M18 8h-1L17 6c0 -2.76 -2.24 -5 -5 -5S7 3.24 7 6v2L6 8c-1.1 0 -2 0.9 -2 2v10c0 1.1 0.9 2 2 2h12c1.1 0 2 -0.9 2 -2L20 10c0 -1.1 -0.9 -2 -2 -2ZM12 17c-1.1 0 -2 -0.9 -2 -2s0.9 -2 2 -2s2 0.9 2 2s-0.9 2 -2 2ZM15.1 8L8.9 8L8.9 6c0 -1.71 1.39 -3.1 3.1 -3.1c1.71 0 3.1 1.39 3.1 3.1v2Z",
    lockOpen: "M12 17c1.1 0 2 -0.9 2 -2s-0.9 -2 -2 -2s-2 0.9 -2 2s0.9 2 2 2ZM18 8h-1L17 6c0 -2.76 -2.24 -5 -5 -5S7 3.24 7 6h1.9c0 -1.71 1.39 -3.1 3.1 -3.1c1.71 0 3.1 1.39 3.1 3.1v2L6 8c-1.1 0 -2 0.9 -2 2v10c0 1.1 0.9 2 2 2h12c1.1 0 2 -0.9 2 -2L20 10c0 -1.1 -0.9 -2 -2 -2ZM18 20L6 20L6 10h12v10Z",
    shield: "M12 1L3 5v6c0 5.55 3.84 10.74 9 12c5.16 -1.26 9 -6.45 9 -12V5l-9 -4Z",
    security: "M12 1L3 5v6c0 5.55 3.84 10.74 9 12c5.16 -1.26 9 -6.45 9 -12L21 5l-9 -4ZM12 11.99h7c-0.53 4.12 -3.28 7.79 -7 8.94L12 12L5 12L5 6.3l7 -3.11v8.8Z",
    vpnKey: "M12.65 10C11.83 7.67 9.61 6 7 6c-3.31 0 -6 2.69 -6 6s2.69 6 6 6c2.61 0 4.83 -1.67 5.65 -4H17v4h4v-4h2v-4H12.65ZM7 14c-1.1 0 -2 -0.9 -2 -2s0.9 -2 2 -2s2 0.9 2 2s-0.9 2 -2 2Z",
    settings: "M19.14 12.94c0.04 -0.3 0.06 -0.61 0.06 -0.94c0 -0.32 -0.02 -0.64 -0.07 -0.94l2.03 -1.58c0.18 -0.14 0.23 -0.41 0.12 -0.61l-1.92 -3.32c-0.12 -0.22 -0.37 -0.29 -0.59 -0.22l-2.39 0.96c-0.5 -0.38 -1.03 -0.7 -1.62 -0.94L14.4 2.81c-0.04 -0.24 -0.24 -0.41 -0.48 -0.41h-3.84c-0.24 0 -0.43 0.17 -0.47 0.41L9.25 5.35C8.66 5.59 8.12 5.92 7.63 6.29L5.24 5.33c-0.22 -0.08 -0.47 0 -0.59 0.22L2.74 8.87C2.62 9.08 2.66 9.34 2.86 9.48l2.03 1.58C4.84 11.36 4.8 11.69 4.8 12s0.02 0.64 0.07 0.94l-2.03 1.58c-0.18 0.14 -0.23 0.41 -0.12 0.61l1.92 3.32c0.12 0.22 0.37 0.29 0.59 0.22l2.39 -0.96c0.5 0.38 1.03 0.7 1.62 0.94l0.36 2.54c0.05 0.24 0.24 0.41 0.48 0.41h3.84c0.24 0 0.44 -0.17 0.47 -0.41l0.36 -2.54c0.59 -0.24 1.13 -0.56 1.62 -0.94l2.39 0.96c0.22 0.08 0.47 0 0.59 -0.22l1.92 -3.32c0.12 -0.22 0.07 -0.47 -0.12 -0.61L19.14 12.94ZM12 15.6c-1.98 0 -3.6 -1.62 -3.6 -3.6s1.62 -3.6 3.6 -3.6s3.6 1.62 3.6 3.6S13.98 15.6 12 15.6Z",
    search: "M15.5 14h-0.79l-0.28 -0.27C15.41 12.59 16 11.11 16 9.5C16 5.91 13.09 3 9.5 3S3 5.91 3 9.5S5.91 16 9.5 16c1.61 0 3.09 -0.59 4.23 -1.57l0.27 0.28v0.79l5 4.99L20.49 19l-4.99 -5ZM9.5 14C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5S14 7.01 14 9.5S11.99 14 9.5 14Z",
    add: "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2Z",
    close: "M19 6.41L17.59 5L12 10.59L6.41 5L5 6.41L10.59 12L5 17.59L6.41 19L12 13.41L17.59 19L19 17.59L13.41 12Z",
    star: "M12 17.27L18.18 21l-1.64 -7.03L22 9.24l-7.19 -0.61L12 2L9.19 8.63L2 9.24l5.46 4.73L5.82 21Z",
    outlinedStarBorder: "M22 9.24l-7.19 -0.62L12 2L9.19 8.63L2 9.24l5.46 4.73L5.82 21L12 17.27L18.18 21l-1.63 -7.03L22 9.24ZM12 15.4l-3.76 2.27l1 -4.28l-3.32 -2.88l4.38 -0.38L12 6.1l1.71 4.04l4.38 0.38l-3.32 2.88l1 4.28L12 15.4Z",
    starBorder: "M22 9.24l-7.19 -0.62L12 2L9.19 8.63L2 9.24l5.46 4.73L5.82 21L12 17.27L18.18 21l-1.63 -7.03L22 9.24ZM12 15.4l-3.76 2.27l1 -4.28l-3.32 -2.88l4.38 -0.38L12 6.1l1.71 4.04l4.38 0.38l-3.32 2.88l1 4.28L12 15.4Z",
    arrowBack: "M20 11H7.83l5.59 -5.59L12 4l-8 8l8 8l1.41 -1.41L7.83 13H20v-2Z",
    edit: "M3 17.25V21h3.75L17.81 9.94l-3.75 -3.75L3 17.25ZM20.71 7.04c0.39 -0.39 0.39 -1.02 0 -1.41l-2.34 -2.34c-0.39 -0.39 -1.02 -0.39 -1.41 0l-1.83 1.83l3.75 3.75l1.83 -1.83Z",
    delete: "M6 19c0 1.1 0.9 2 2 2h8c1.1 0 2 -0.9 2 -2V7H6v12ZM19 4h-3.5l-1 -1h-5l-1 1H5v2h14V4Z",
    deleteOutline: "M6 19c0 1.1 0.9 2 2 2h8c1.1 0 2 -0.9 2 -2L18 7L6 7v12ZM8 9h8v10L8 19L8 9ZM15.5 4l-1 -1h-5l-1 1L5 4v2h14L19 4Z",
    deleteForever: "M6 19c0 1.1 0.9 2 2 2h8c1.1 0 2 -0.9 2 -2L18 7L6 7v12ZM8.46 11.88l1.41 -1.41L12 12.59l2.12 -2.12l1.41 1.41L13.41 14l2.12 2.12l-1.41 1.41L12 15.41l-2.12 2.12l-1.41 -1.41L10.59 14l-2.13 -2.12ZM15.5 4l-1 -1h-5l-1 1L5 4v2h14L19 4Z",
    deleteSweep: "M15 16h4v2h-4ZM15 8h7v2h-7ZM15 12h6v2h-6ZM3 18c0 1.1 0.9 2 2 2h6c1.1 0 2 -0.9 2 -2L13 8L3 8v10ZM14 5h-3l-1 -1L6 4L5 5L2 5v2h12Z",
    restore: "M13 3c-4.97 0 -9 4.03 -9 9L1 12l3.89 3.89l0.07 0.14L9 12L6 12c0 -3.87 3.13 -7 7 -7s7 3.13 7 7s-3.13 7 -7 7c-1.93 0 -3.68 -0.79 -4.94 -2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9 -4.03 9 -9s-4.03 -9 -9 -9ZM12 8v5l4.28 2.54l0.72 -1.21l-3.5 -2.08L13.5 8L12 8Z",
    contentCopy: "M16 1L4 1c-1.1 0 -2 0.9 -2 2v14h2L4 3h12L16 1ZM19 5L8 5c-1.1 0 -2 0.9 -2 2v14c0 1.1 0.9 2 2 2h11c1.1 0 2 -0.9 2 -2L21 7c0 -1.1 -0.9 -2 -2 -2ZM19 21L8 21L8 7h11v14Z",
    visibility: "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27 -3.11 11 -7.5c-1.73 -4.39 -6 -7.5 -11 -7.5ZM12 17c-2.76 0 -5 -2.24 -5 -5s2.24 -5 5 -5s5 2.24 5 5s-2.24 5 -5 5ZM12 9c-1.66 0 -3 1.34 -3 3s1.34 3 3 3s3 -1.34 3 -3s-1.34 -3 -3 -3Z",
    visibilityOff: "M12 7c2.76 0 5 2.24 5 5c0 0.65 -0.13 1.26 -0.36 1.83l2.92 2.92c1.51 -1.26 2.7 -2.89 3.43 -4.75c-1.73 -4.39 -6 -7.5 -11 -7.5c-1.4 0 -2.74 0.25 -3.98 0.7l2.16 2.16C10.74 7.13 11.35 7 12 7ZM2 4.27l2.28 2.28l0.46 0.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5c1.55 0 3.03 -0.3 4.38 -0.84l0.42 0.42L19.73 22L21 20.73L3.27 3L2 4.27ZM7.53 9.8l1.55 1.55c-0.05 0.21 -0.08 0.43 -0.08 0.65c0 1.66 1.34 3 3 3c0.22 0 0.44 -0.03 0.65 -0.08l1.55 1.55c-0.67 0.33 -1.41 0.53 -2.2 0.53c-2.76 0 -5 -2.24 -5 -5c0 -0.79 0.2 -1.53 0.53 -2.2ZM11.84 9.02l3.15 3.15l0.02 -0.16c0 -1.66 -1.34 -3 -3 -3l-0.17 0.01Z",
    fingerprint: "M17.81 4.47c-0.08 0 -0.16 -0.02 -0.23 -0.06C15.66 3.42 14 3 12.01 3c-1.98 0 -3.86 0.47 -5.57 1.41c-0.24 0.13 -0.54 0.04 -0.68 -0.2c-0.13 -0.24 -0.04 -0.55 0.2 -0.68C7.82 2.52 9.86 2 12.01 2c2.13 0 3.99 0.47 6.03 1.52c0.25 0.13 0.34 0.43 0.21 0.67c-0.09 0.18 -0.26 0.28 -0.44 0.28ZM3.5 9.72c-0.1 0 -0.2 -0.03 -0.29 -0.09c-0.23 -0.16 -0.28 -0.47 -0.12 -0.7c0.99 -1.4 2.25 -2.5 3.75 -3.27C9.98 4.04 14 4.03 17.15 5.65c1.5 0.77 2.76 1.86 3.75 3.25c0.16 0.22 0.11 0.54 -0.12 0.7c-0.23 0.16 -0.54 0.11 -0.7 -0.12c-0.9 -1.26 -2.04 -2.25 -3.39 -2.94c-2.87 -1.47 -6.54 -1.47 -9.4 0.01c-1.36 0.7 -2.5 1.7 -3.4 2.96c-0.08 0.14 -0.23 0.21 -0.39 0.21ZM9.75 21.79c-0.13 0 -0.26 -0.05 -0.35 -0.15c-0.87 -0.87 -1.34 -1.43 -2.01 -2.64c-0.69 -1.23 -1.05 -2.73 -1.05 -4.34c0 -2.97 2.54 -5.39 5.66 -5.39s5.66 2.42 5.66 5.39c0 0.28 -0.22 0.5 -0.5 0.5s-0.5 -0.22 -0.5 -0.5c0 -2.42 -2.09 -4.39 -4.66 -4.39c-2.57 0 -4.66 1.97 -4.66 4.39c0 1.44 0.32 2.77 0.93 3.85c0.64 1.15 1.08 1.64 1.85 2.42c0.19 0.2 0.19 0.51 0 0.71c-0.11 0.1 -0.24 0.15 -0.37 0.15ZM16.92 19.94c-1.19 0 -2.24 -0.3 -3.1 -0.89c-1.49 -1.01 -2.38 -2.65 -2.38 -4.39c0 -0.28 0.22 -0.5 0.5 -0.5s0.5 0.22 0.5 0.5c0 1.41 0.72 2.74 1.94 3.56c0.71 0.48 1.54 0.71 2.54 0.71c0.24 0 0.64 -0.03 1.04 -0.1c0.27 -0.05 0.53 0.13 0.58 0.41c0.05 0.27 -0.13 0.53 -0.41 0.58c-0.57 0.11 -1.07 0.12 -1.21 0.12ZM14.91 22c-0.04 0 -0.09 -0.01 -0.13 -0.02c-1.59 -0.44 -2.63 -1.03 -3.72 -2.1c-1.4 -1.39 -2.17 -3.24 -2.17 -5.22c0 -1.62 1.38 -2.94 3.08 -2.94c1.7 0 3.08 1.32 3.08 2.94c0 1.07 0.93 1.94 2.08 1.94s2.08 -0.87 2.08 -1.94c0 -3.77 -3.25 -6.83 -7.25 -6.83c-2.84 0 -5.44 1.58 -6.61 4.03c-0.39 0.81 -0.59 1.76 -0.59 2.8c0 0.78 0.07 2.01 0.67 3.61c0.1 0.26 -0.03 0.55 -0.29 0.64c-0.26 0.1 -0.55 -0.04 -0.64 -0.29c-0.49 -1.31 -0.73 -2.61 -0.73 -3.96c0 -1.2 0.23 -2.29 0.68 -3.24c1.33 -2.79 4.28 -4.6 7.51 -4.6c4.55 0 8.25 3.51 8.25 7.83c0 1.62 -1.38 2.94 -3.08 2.94s-3.08 -1.32 -3.08 -2.94c0 -1.07 -0.93 -1.94 -2.08 -1.94s-2.08 0.87 -2.08 1.94c0 1.71 0.66 3.31 1.87 4.51c0.95 0.94 1.86 1.46 3.27 1.85c0.27 0.07 0.42 0.35 0.35 0.61c-0.05 0.23 -0.26 0.38 -0.47 0.38Z",
    password: "M2 17h20v2H2V17ZM3.15 12.95L4 11.47l0.85 1.48l1.3 -0.75L5.3 10.72H7v-1.5H5.3l0.85 -1.47L4.85 7L4 8.47L3.15 7l-1.3 0.75L2.7 9.22H1v1.5h1.7L1.85 12.2L3.15 12.95ZM9.85 12.2l1.3 0.75L12 11.47l0.85 1.48l1.3 -0.75l-0.85 -1.48H15v-1.5h-1.7l0.85 -1.47L12.85 7L12 8.47L11.15 7l-1.3 0.75l0.85 1.47H9v1.5h1.7L9.85 12.2ZM23 9.22h-1.7l0.85 -1.47L20.85 7L20 8.47L19.15 7l-1.3 0.75l0.85 1.47H17v1.5h1.7l-0.85 1.48l1.3 0.75L20 11.47l0.85 1.48l1.3 -0.75l-0.85 -1.48H23V9.22Z",
    verifiedUser: "M12 1L3 5v6c0 5.55 3.84 10.74 9 12c5.16 -1.26 9 -6.45 9 -12L21 5l-9 -4ZM10 17l-4 -4l1.41 -1.41L10 14.17l6.59 -6.59L18 9l-8 8Z",
    refresh: "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0 -7.99 3.58 -7.99 8s3.57 8 7.99 8c3.73 0 6.84 -2.55 7.73 -6h-2.08c-0.82 2.33 -3.04 4 -5.65 4c-3.31 0 -6 -2.69 -6 -6s2.69 -6 6 -6c1.66 0 3.14 0.69 4.22 1.78L13 11h7V4l-2.35 2.35Z",
    warning: "M1 21h22L12 2L1 21ZM13 18h-2v-2h2v2ZM13 14h-2v-4h2v4Z",
    warningAmber: "M12 5.99L19.53 19H4.47L12 5.99M12 2L1 21h22L12 2L12 2ZM13 16l-2 0l0 2l2 0ZM13 10l-2 0l0 5l2 0Z",
    language: "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2ZM18.92 8h-2.95c-0.32 -1.25 -0.78 -2.45 -1.38 -3.56c1.84 0.63 3.37 1.91 4.33 3.56ZM12 4.04c0.83 1.2 1.48 2.53 1.91 3.96h-3.82c0.43 -1.43 1.08 -2.76 1.91 -3.96ZM4.26 14C4.1 13.36 4 12.69 4 12s0.1 -1.36 0.26 -2h3.38c-0.08 0.66 -0.14 1.32 -0.14 2c0 0.68 0.06 1.34 0.14 2L4.26 14ZM5.08 16h2.95c0.32 1.25 0.78 2.45 1.38 3.56c-1.84 -0.63 -3.37 -1.9 -4.33 -3.56ZM8.03 8L5.08 8c0.96 -1.66 2.49 -2.93 4.33 -3.56C8.81 5.55 8.35 6.75 8.03 8ZM12 19.96c-0.83 -1.2 -1.48 -2.53 -1.91 -3.96h3.82c-0.43 1.43 -1.08 2.76 -1.91 3.96ZM14.34 14L9.66 14c-0.09 -0.66 -0.16 -1.32 -0.16 -2c0 -0.68 0.07 -1.35 0.16 -2h4.68c0.09 0.65 0.16 1.32 0.16 2c0 0.68 -0.07 1.34 -0.16 2ZM14.59 19.56c0.6 -1.11 1.06 -2.31 1.38 -3.56h2.95c-0.96 1.65 -2.49 2.93 -4.33 3.56ZM16.36 14c0.08 -0.66 0.14 -1.32 0.14 -2c0 -0.68 -0.06 -1.34 -0.14 -2h3.38c0.16 0.64 0.26 1.31 0.26 2s-0.1 1.36 -0.26 2h-3.38Z",
    palette: "M12 2C6.49 2 2 6.49 2 12s4.49 10 10 10c1.38 0 2.5 -1.12 2.5 -2.5c0 -0.61 -0.23 -1.2 -0.64 -1.67c-0.08 -0.1 -0.13 -0.21 -0.13 -0.33c0 -0.28 0.22 -0.5 0.5 -0.5H16c3.31 0 6 -2.69 6 -6C22 6.04 17.51 2 12 2ZM17.5 13c-0.83 0 -1.5 -0.67 -1.5 -1.5c0 -0.83 0.67 -1.5 1.5 -1.5s1.5 0.67 1.5 1.5C19 12.33 18.33 13 17.5 13ZM14.5 9C13.67 9 13 8.33 13 7.5C13 6.67 13.67 6 14.5 6S16 6.67 16 7.5C16 8.33 15.33 9 14.5 9ZM5 11.5C5 10.67 5.67 10 6.5 10S8 10.67 8 11.5C8 12.33 7.33 13 6.5 13S5 12.33 5 11.5ZM11 7.5C11 8.33 10.33 9 9.5 9S8 8.33 8 7.5C8 6.67 8.67 6 9.5 6S11 6.67 11 7.5Z",
    expandMore: "M16.59 8.59L12 13.17L7.41 8.59L6 10l6 6l6 -6Z",
    expandLess: "M12 8l-6 6l1.41 1.41L12 10.83l4.59 4.58L18 14Z",
    arrowDropDown: "M7 10l5 5l5 -5Z",
    timer: "M9 1h6v2h-6ZM19.03 7.39l1.42 -1.42c-0.43 -0.51 -0.9 -0.99 -1.41 -1.41l-1.42 1.42C16.07 4.74 14.12 4 12 4c-4.97 0 -9 4.03 -9 9c0 4.97 4.02 9 9 9s9 -4.03 9 -9C21 10.88 20.26 8.93 19.03 7.39ZM13 14h-2V8h2V14Z",
    chevronRight: "M10 6L8.59 7.41L13.17 12l-4.58 4.59L10 18l6 -6Z",
    upload: "M5 20h14v-2H5V20ZM5 10h4v6h6v-6h4l-7 -7L5 10Z",
    download: "M5 20h14v-2H5V20ZM19 9h-4V3H9v6H5l7 7L19 9Z",
    sync: "M12 4L12 1L8 5l4 4L12 6c3.31 0 6 2.69 6 6c0 1.01 -0.25 1.97 -0.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0 -4.42 -3.58 -8 -8 -8ZM12 18c-3.31 0 -6 -2.69 -6 -6c0 -1.01 0.25 -1.97 0.7 -2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4 -4l-4 -4v3Z",
    update: "M21 10.12h-6.78l2.74 -2.82c-2.73 -2.7 -7.15 -2.8 -9.88 -0.1c-2.73 2.71 -2.73 7.08 0 9.79s7.15 2.71 9.88 0C18.32 15.65 19 14.08 19 12.1h2c0 1.98 -0.88 4.55 -2.64 6.29c-3.51 3.48 -9.21 3.48 -12.72 0c-3.5 -3.47 -3.53 -9.11 -0.02 -12.58s9.14 -3.47 12.65 0L21 3V10.12ZM12.5 8v4.25l3.5 2.08l-0.72 1.21L11 13V8H12.5Z",
    systemUpdate: "M17 1.01L7 1c-1.1 0 -2 0.9 -2 2v18c0 1.1 0.9 2 2 2h10c1.1 0 2 -0.9 2 -2L19 3c0 -1.1 -0.9 -1.99 -2 -1.99ZM17 19L7 19L7 5h10v14ZM16 13h-3L13 8h-2v5L8 13l4 4l4 -4Z",
    login: "M11 7L9.6 8.4l2.6 2.6H2v2h10.2l-2.6 2.6L11 17l5 -5L11 7ZM20 19h-8v2h8c1.1 0 2 -0.9 2 -2V5c0 -1.1 -0.9 -2 -2 -2h-8v2h8V19Z",
    creditCard: "M20 4L4 4c-1.11 0 -1.99 0.89 -1.99 2L2 18c0 1.11 0.89 2 2 2h16c1.11 0 2 -0.89 2 -2L22 6c0 -1.11 -0.89 -2 -2 -2ZM20 18L4 18v-6h16v6ZM20 8L4 8L4 6h16v2Z",
    notes: "M3 18h12v-2L3 16v2ZM3 6v2h18L21 6L3 6ZM3 13h18v-2L3 11v2Z",
    title: "M5 4v3h5.5v12h3V7H19V4Z",
    person: "M12 12c2.21 0 4 -1.79 4 -4s-1.79 -4 -4 -4s-4 1.79 -4 4s1.79 4 4 4ZM12 14c-2.67 0 -8 1.34 -8 4v2h16v-2c0 -2.66 -5.33 -4 -8 -4Z",
    check: "M9 16.17L4.83 12l-1.42 1.41L9 19L21 7l-1.41 -1.41Z",
    addCircleOutline: "M13 7h-2v4L7 11v2h4v4h2v-4h4v-2h-4L13 7ZM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10s10 -4.48 10 -10S17.52 2 12 2ZM12 20c-4.41 0 -8 -3.59 -8 -8s3.59 -8 8 -8s8 3.59 8 8s-3.59 8 -8 8Z",
    removeCircleOutline: "M7 11v2h10v-2L7 11ZM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10s10 -4.48 10 -10S17.52 2 12 2ZM12 20c-4.41 0 -8 -3.59 -8 -8s3.59 -8 8 -8s8 3.59 8 8s-3.59 8 -8 8Z",
    computer: "M20 18c1.1 0 1.99 -0.9 1.99 -2L22 6c0 -1.1 -0.9 -2 -2 -2H4c-1.1 0 -2 0.9 -2 2v10c0 1.1 0.9 2 2 2H0v2h24v-2h-4ZM4 6h16v10H4V6Z",
    qrCodeScanner: "M9.5 6.5v3h-3v-3H9.5M11 5H5v6h6V5L11 5ZM9.5 14.5v3h-3v-3H9.5M11 13H5v6h6V13L11 13ZM17.5 6.5v3h-3v-3H17.5M19 5h-6v6h6V5L19 5ZM13 13h1.5v1.5H13V13ZM14.5 14.5H16V16h-1.5V14.5ZM16 13h1.5v1.5H16V13ZM13 16h1.5v1.5H13V16ZM14.5 17.5H16V19h-1.5V17.5ZM16 16h1.5v1.5H16V16ZM17.5 14.5H19V16h-1.5V14.5ZM17.5 17.5H19V19h-1.5V17.5ZM22 7h-2V4h-3V2h5V7ZM22 22v-5h-2v3h-3v2H22ZM2 22h5v-2H4v-3H2V22ZM2 2v5h2V4h3V2H2Z",
    checkCircle: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10s10 -4.48 10 -10S17.52 2 12 2ZM10 17l-5 -5l1.41 -1.41L10 14.17l7.59 -7.59L19 8l-9 9Z",
    inbox: "M19 3L4.99 3c-1.11 0 -1.98 0.89 -1.98 2L3 19c0 1.1 0.88 2 1.99 2L19 21c1.1 0 2 -0.9 2 -2L21 5c0 -1.11 -0.9 -2 -2 -2ZM19 15h-4c0 1.66 -1.35 3 -3 3s-3 -1.34 -3 -3L4.99 15L4.99 5L19 5v10Z",
    error: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10s10 -4.48 10 -10S17.52 2 12 2ZM13 17h-2v-2h2v2ZM13 13h-2L11 7h2v6Z",
    signalCellular4Bar: "M2 22h20V2Z",
    wifi: "M1 9l2 2c4.97 -4.97 13.03 -4.97 18 0l2 -2C16.93 2.93 7.08 2.93 1 9ZM9 17l3 3l3 -3c-1.65 -1.66 -4.34 -1.66 -6 0ZM5 13l2 2c2.76 -2.76 7.24 -2.76 10 0l2 -2C15.14 9.14 8.87 9.14 5 13Z",
    batteryFull: "M15.67 4H14V2h-4v2H8.33C7.6 4 7 4.6 7 5.33v15.33C7 21.4 7.6 22 8.33 22h7.33c0.74 0 1.34 -0.6 1.34 -1.33V5.33C17 4.6 16.4 4 15.67 4Z"
  };

  /* ------------------------------------------------------------------ constants from the app */

  // ui/theme/AccentColor.kt (swatch = lightPrimary, as the Settings dropdown draws it)
  var ACCENTS = [
    { key: 'BLUE', title: 'Blue' },
    { key: 'RED', title: 'Red' },
    { key: 'GREEN', title: 'Green' },
    { key: 'PURPLE', title: 'Purple' },
    { key: 'ORANGE', title: 'Orange' }
  ];

  // SettingsScreen.kt
  var THEME_OPTIONS = ['System Default', 'Light Mode', 'Dark Mode'];
  var AUTO_LOCK = [
    [0, 'Immediately'],
    [30000, '30 Seconds'],
    [60000, '1 Minute'],
    [300000, '5 Minutes'],
    [900000, '15 Minutes'],
    [-1, 'Never']
  ];
  var EXPORT_FORMATS = [
    ['txt', 'Plain Text (.txt): readable, not a backup'],
    ['json', 'Simplified JSON (.json)'],
    ['vpex', 'Encrypted JSON (.vpex)']
  ];
  var EXPORT_PASSWORD_MIN_LENGTH = 8; // ui/VaultViewModel.kt

  // util/WebsiteUrl.kt: the http(s) address the details screen may open, or null when there is
  // nothing safe to open (blank, androidapp://, javascript:, file:, intent:, no host).
  var URL_SCHEME = /^([a-zA-Z][a-zA-Z0-9+.-]*):/;
  var URL_PORT = /^\d+([/?#][\s\S]*)?$/;
  var URL_HOST = /^[a-z0-9_-]+(\.[a-z0-9_-]+)*$/;

  // AutofillCredentialMatcher.extractHost, for the http(s) URL browserUrl() built
  function browserHost(url) {
    var authority = url.replace(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//, '').split(/[/?#\\]/)[0];
    authority = authority.slice(authority.lastIndexOf('@') + 1);
    var host = (authority.charAt(0) === '[' ? authority.split(']')[0] + ']' : authority.split(':')[0]).trim();
    if (host.charAt(host.length - 1) === '.') host = host.slice(0, -1);
    if (!host || host.charAt(0) === '.') return null;
    if (host.charAt(0) !== '[') {
      // IDN.toASCII: "bücher.de" becomes "xn--bcher-kva.de"
      try { host = new URL('http://' + host).hostname; } catch (err) { /* left as typed: URL_HOST rejects it */ }
    }
    host = host.toLowerCase();
    return (host.charAt(0) === '[' && host.charAt(host.length - 1) === ']' && host.length > 2) || URL_HOST.test(host) ? host : null;
  }

  function browserUrl(website) {
    var trimmed = String(website || '').trim();
    if (!trimmed) return null;
    var scheme = URL_SCHEME.exec(trimmed);
    var url;
    if (!scheme || URL_PORT.test(trimmed.slice(scheme[0].length))) {
      // No scheme: "example.com", or "example.com:8443/login" where the colon starts a port.
      url = 'https://' + trimmed;
    } else {
      var name = scheme[1].toLowerCase();
      var rest = trimmed.slice(scheme[0].length);
      if ((name !== 'http' && name !== 'https') || rest.slice(0, 2) !== '//') return null;
      url = name + ':' + rest;
    }
    return browserHost(url) ? url : null;
  }

  // domain/security/PasswordGenerator.kt
  var UPPER = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
  var LOWER = 'abcdefghijklmnopqrstuvwxyz';
  var DIGITS = '0123456789';
  var SYMBOLS = '!@#$%^&*()_+-=[]{}|;:,.<>?';

  // com/vaultpass/synccore/EntryLimits.kt
  var LIMITS = { TITLE: 200, USERNAME: 300, PASSWORD: 1000, WEBSITE: 500, NOTES: 20000, CF_KEY: 100, CF_VALUE: 2000, MAX_CF: 50, TAG: 50, MAX_TAGS: 25 };

  var PRIVACY_URL = 'https://github.com/ArmaanCode2/Vault-pass/blob/master/PRIVACY.md'; // util/Constants.kt
  var DEFAULT_APP_VERSION = '2.7.0'; // app/build.gradle.kts versionName
  var SYNC_PORT = 53853; // ui/sync/LanSyncScreen.kt default port
  var SAMPLE_PEER_IP = '192.168.1.24'; // sample data
  var RECYCLE_DAYS = 7; // repository/VaultRepository.kt cleanupRecycleBin
  var DAY_MS = 24 * 60 * 60 * 1000;
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  var instanceCount = 0;

  /* ------------------------------------------------------------------ helpers */

  function esc(value) {
    if (value === null || value === undefined) return '';
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function str(value) {
    return value === null || value === undefined ? '' : String(value);
  }

  function clamp(n, lo, hi) {
    return Math.max(lo, Math.min(hi, n));
  }

  function icon(name, cls) {
    return '<svg class="vpm-ic' + (cls ? ' ' + cls : '') + '" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="' + ICONS[name] + '"/></svg>';
  }

  // Kotlin: title.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
  function initial(title) {
    if (!title) return '?';
    var c = title.charAt(0);
    var u = c.toUpperCase();
    return u.length === 1 ? u : c;
  }

  function pad2(n) {
    return (n < 10 ? '0' : '') + n;
  }

  // RecycleBinScreen.kt: SimpleDateFormat("MMM dd, yyyy HH:mm")
  function fmtDeleted(ms) {
    var d = new Date(ms);
    return MONTHS[d.getMonth()] + ' ' + pad2(d.getDate()) + ', ' + d.getFullYear() + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }

  // LanSyncScreen.kt: SimpleDateFormat("MMM d, h:mm a")
  function fmtLastSync(ms) {
    var d = new Date(ms);
    var h = d.getHours() % 12 || 12;
    return MONTHS[d.getMonth()] + ' ' + d.getDate() + ', ' + h + ':' + pad2(d.getMinutes()) + ' ' + (d.getHours() < 12 ? 'AM' : 'PM');
  }

  function timeOf(iso) {
    var t = Date.parse(iso || '');
    return isNaN(t) ? 0 : t;
  }

  function nowIso() {
    return new Date().toISOString();
  }

  // Uniform index in [0, n) from crypto.getRandomValues, rejection sampling (no modulo bias).
  function randomIndex(n) {
    var range = 0x100000000;
    var limit = range - (range % n);
    var buf = new Uint32Array(1);
    var x;
    do {
      window.crypto.getRandomValues(buf);
      x = buf[0];
    } while (x >= limit);
    return x % n;
  }

  function poolFor(o) {
    return (o.upper ? UPPER : '') + (o.lower ? LOWER : '') + (o.nums ? DIGITS : '') + (o.syms ? SYMBOLS : '');
  }

  // PasswordGeneratorScreen.kt generate(): every character drawn from the combined pool.
  function generateSimple(length, o) {
    var pool = poolFor(o);
    if (!pool) return '';
    var out = '';
    for (var i = 0; i < length; i++) out += pool.charAt(randomIndex(pool.length));
    return out;
  }

  // PasswordGenerator.generatePassword(): one character from each chosen set, the rest from the
  // combined pool, then a Fisher-Yates shuffle (used by the entry screen's generator popover).
  function generateGuaranteed(length, o) {
    var sets = [];
    if (o.upper) sets.push(UPPER);
    if (o.lower) sets.push(LOWER);
    if (o.nums) sets.push(DIGITS);
    if (o.syms) sets.push(SYMBOLS);
    if (!sets.length) return '';
    var chars = sets.map(function (s) { return s.charAt(randomIndex(s.length)); });
    var pool = sets.join('');
    var target = Math.max(length, chars.length);
    while (chars.length < target) chars.push(pool.charAt(randomIndex(pool.length)));
    for (var i = chars.length - 1; i >= 1; i--) {
      var j = randomIndex(i + 1);
      var t = chars[i];
      chars[i] = chars[j];
      chars[j] = t;
    }
    return chars.join('');
  }

  /* ------------------------------------------------------------------ SecurityAnalyzer.kt */

  var COMMON_PASSWORDS = ['password', '123456', '12345678', 'qwerty', 'admin', 'letmein', 'password123'];
  var DICTIONARY_WORDS = ['football', 'dragon', 'arsenal', 'liverpool', 'monkey', 'sunshine', 'iloveyou'];
  var SEQUENTIAL = ['12345', 'qwerty', 'abcdef', '98765', 'asdfg'];
  var RE_LOWER = /\p{Lowercase}/u;
  var RE_UPPER = /\p{Uppercase}/u;
  var RE_DIGIT = /\p{Nd}/u;
  var RE_SYMBOL = /[^\p{L}\p{Nd}]/u;
  var RE_REPEAT = /(.)\1{2,}/u;

  function containsAny(haystack, words) {
    for (var i = 0; i < words.length; i++) if (haystack.indexOf(words[i]) !== -1) return true;
    return false;
  }

  function scorePassword(pw) {
    if (!pw) return 0;
    var pool = 0;
    if (RE_LOWER.test(pw)) pool += 26;
    if (RE_UPPER.test(pw)) pool += 26;
    if (RE_DIGIT.test(pw)) pool += 10;
    if (RE_SYMBOL.test(pw)) pool += 32;
    var entropy = pw.length * (pool > 0 ? Math.log(pool) / Math.log(2) : 0);
    var score = (entropy / 80) * 100;
    var low = pw.toLowerCase();
    if (COMMON_PASSWORDS.indexOf(low) !== -1) score -= 100;
    if (containsAny(low, DICTIONARY_WORDS)) score -= 50;
    if (RE_REPEAT.test(pw)) score -= 50;
    if (containsAny(low, SEQUENTIAL)) score -= 50;
    return clamp(Math.trunc(score) || 0, 0, 100);
  }

  function weaknessReasons(pw) {
    if (!pw) return ['Empty password'];
    var r = [];
    if (pw.length < 8) r.push('Too short');
    if (!RE_UPPER.test(pw)) r.push('Missing uppercase letter');
    if (!RE_LOWER.test(pw)) r.push('Missing lowercase letter');
    if (!RE_DIGIT.test(pw)) r.push('Missing number');
    if (!RE_SYMBOL.test(pw)) r.push('Missing symbol');
    var low = pw.toLowerCase();
    if (COMMON_PASSWORDS.indexOf(low) !== -1) r.push('Commonly used password');
    if (containsAny(low, DICTIONARY_WORDS)) r.push('Contains common dictionary word');
    if (RE_REPEAT.test(pw)) r.push('Repeated characters detected');
    if (containsAny(low, SEQUENTIAL)) r.push('Sequential pattern detected');
    return r;
  }

  function recommendations(weak, reused, missing) {
    var recs = [];
    if (missing > 0) recs.push({ title: 'Add ' + missing + ' missing passwords', desc: 'Incomplete entries are insecure and difficult to manage.', route: 'missing', icon: 'warning', priority: 0 });
    if (weak > 0) recs.push({ title: 'Replace ' + weak + ' weak passwords', desc: 'Weak passwords are easily guessable. Update them to improve your security score.', route: 'weak', icon: 'warning', priority: weak > 5 ? 1 : 3 });
    if (reused > 0) recs.push({ title: 'Fix ' + reused + ' reused passwords', desc: 'Reusing passwords increases risk across multiple accounts.', route: 'reused', icon: 'contentCopy', priority: reused > 3 ? 2 : 4 });
    return recs.sort(function (a, b) { return a.priority - b.priority; });
  }

  function analyze(list) {
    var out = {
      score: 0, total: list.length, strong: 0, medium: 0, weak: 0, reused: 0, missing: 0,
      status: 'No passwords stored', weakIds: {}, reusedIds: {}, missingIds: {}, scores: {}, reasons: {}
    };
    if (!list.length) return out;
    var totalScore = 0;
    var counts = {};
    list.forEach(function (e) {
      var pw = e.password;
      if (!pw) {
        out.missing++;
        out.missingIds[e.uid] = true;
        out.scores[e.uid] = 0;
        out.reasons[e.uid] = ['Empty password'];
        return;
      }
      counts[pw] = (counts[pw] || 0) + 1;
      var s = scorePassword(pw);
      totalScore += s;
      out.scores[e.uid] = s;
      if (s <= 40) {
        out.weak++;
        out.weakIds[e.uid] = true;
        out.reasons[e.uid] = weaknessReasons(pw);
      } else if (s <= 70) {
        out.medium++;
      } else {
        out.strong++;
      }
    });
    list.forEach(function (e) {
      if (e.password && counts[e.password] > 1) {
        out.reused++;
        out.reusedIds[e.uid] = true;
      }
    });
    var analyzed = out.total - out.missing;
    var average = analyzed > 0 ? Math.trunc(totalScore / analyzed) : 0;
    var penalty = analyzed > 0 ? (out.reused / analyzed) * 30 : 0;
    out.score = clamp(Math.trunc(average - penalty), 0, 100);
    if (analyzed === 0) out.status = 'Incomplete';
    else if (out.score >= 81) out.status = 'Excellent';
    else if (out.score >= 61) out.status = 'Good';
    else if (out.score >= 41) out.status = 'Fair';
    else if (out.score >= 21) out.status = 'Very Weak';
    else out.status = 'Critical';
    return out;
  }

  // domain/models/VaultListPreview.kt: username, else the website's host, else "".
  function previewLine(e) {
    if (e.username && e.username.trim()) return e.username;
    var lines = str(e.website).split('\n').map(function (l) { return l.trim(); }).filter(Boolean);
    if (!lines.length) return '';
    var s = lines[0];
    var scheme = s.indexOf('://');
    if (scheme >= 0) s = s.substring(scheme + 3);
    var end = s.search(/[/?#]/);
    if (end >= 0) s = s.substring(0, end);
    s = s.substring(s.lastIndexOf('@') + 1);
    var host;
    if (s.charAt(0) === '[') {
      var close = s.indexOf(']');
      host = close >= 0 ? s.substring(0, close + 1) : s;
    } else {
      host = s.split(':')[0];
    }
    host = host.trim().replace(/\.+$/, '').toLowerCase();
    if (host.indexOf('www.') === 0) host = host.substring(4);
    if (!host || /\s/.test(host)) return '';
    return host;
  }

  /* ------------------------------------------------------------------ mount */

  function mount(hostEl, options) {
    if (!hostEl) throw new Error('VaultPass Android demo: mount() needs a host element');
    options = options || {};
    var data = window.VaultPassDemoData || null;
    var uidBase = 'vpm' + (++instanceCount);
    var reduceMotion = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

    var entries = (Array.isArray(options.vault) ? options.vault : (data ? data.vaultFor('android') : [])).map(normEntry);
    // options.deviceName is accepted but not shown: no Android screen displays this phone's own name.
    var peerName = str(options.peerName) || (data && data.devices && data.devices.desktop ? data.devices.desktop.name : "Kay's PC");
    var appVersion = str(options.appVersion) || DEFAULT_APP_VERSION;

    var S = {
      locked: false,
      stack: [{ name: 'dashboard' }],
      // Settings (SettingsRepository.kt defaults; biometric unlock is shown turned on in this sample)
      themeMode: 2,
      accent: 'BLUE',
      hidePasswords: true,
      biometric: true,
      biometricEnabling: false,
      disableScreenshots: true,
      autoLock: 60000,
      autofillEnabled: true,
      checkUpdates: false,
      exportFormat: 'vpex',
      exportPassword: '',
      exportConfirmation: '',
      exportPasswordVisible: false,
      appearanceOpen: false,
      menu: null,
      search: { active: false, query: '' },
      gen: null,
      reveal: {},
      form: null,
      lock: { password: '', visible: false, error: null },
      dialog: null,
      sheet: null,
      paired: true,
      lastSync: 0,
      tags: {}
    };

    var changeCbs = [];
    var lockCbs = [];
    var timers = [];
    var toastTimer = null;
    var destroyed = false;
    var pendingFocus = null;
    var dialogOpenerFk = null;
    var scrollMemory = {};
    var lastOverlayId = '';
    var revealMenu = false;
    var restoringFocus = false;
    var keyboardNav = false;
    var lastPointerDown = 0;
    // Entries deleted permanently here. The Entry shape has no sync tombstone, so this local list keeps
    // a sync from bringing a purged entry back into the recycle bin (VaultRepository.kt writes one).
    var purged = Object.create(null);

    /* ---------- DOM skeleton */

    hostEl.innerHTML = '';
    var root = document.createElement('div');
    root.className = 'vpm';
    root.setAttribute('role', 'region');
    root.setAttribute('aria-label', 'Interactive demo of VaultPass for Android (sample data)');
    root.innerHTML =
      '<div class="vpm-screen">' +
        '<div class="vpm-scale">' +
          '<div class="vpm-app" data-app></div>' +
          '<div class="vpm-statusbar" aria-hidden="true">' +
            '<span class="vpm-status-time">9:41</span>' +
            '<span class="vpm-camera"></span>' +
            '<span class="vpm-status-icons">' + icon('wifi') + icon('signalCellular4Bar') + icon('batteryFull') + '</span>' +
          '</div>' +
          '<div data-overlay></div>' +
          '<div class="vpm-toast" aria-hidden="true"></div>' +
          '<div class="vpm-gesture" aria-hidden="true"></div>' +
        '</div>' +
      '</div>' +
      '<div class="vpm-live" role="status" aria-live="polite"></div>';
    hostEl.appendChild(root);

    var appEl = root.querySelector('[data-app]');
    var overlayEl = root.querySelector('[data-overlay]');
    var toastEl = root.querySelector('.vpm-toast');
    var liveEl = root.querySelector('.vpm-live');

    /* ---------- entries */

    function normEntry(e) {
      e = e && typeof e === 'object' ? e : {};
      var copy;
      try {
        copy = JSON.parse(JSON.stringify(e));
      } catch (err) {
        copy = {};
      }
      copy.uid = str(e.uid) || newUid();
      copy.title = str(e.title);
      copy.username = str(e.username);
      copy.password = str(e.password);
      copy.website = str(e.website);
      copy.category = e.category === undefined || e.category === null ? 'Personal' : str(e.category);
      copy.notes = str(e.notes);
      copy.favorite = !!e.favorite;
      copy.customFields = Array.isArray(e.customFields) ? e.customFields.map(function (f) {
        f = f || {};
        return { label: str(f.label), value: str(f.value), hidden: f.hidden !== false };
      }) : [];
      copy.createdAt = e.createdAt || null;
      copy.updatedAt = e.updatedAt || null;
      copy.deletedAt = e.deletedAt || null;
      return copy;
    }

    function newUid() {
      return 'u' + Date.now().toString(36) + randomIndex(1296).toString(36);
    }

    function active() {
      return entries.filter(function (e) { return !e.deletedAt; }).sort(function (a, b) {
        return timeOf(b.updatedAt || b.createdAt) - timeOf(a.updatedAt || a.createdAt);
      });
    }

    function binEntries() {
      return entries.filter(function (e) { return !!e.deletedAt; }).sort(function (a, b) {
        return timeOf(b.deletedAt) - timeOf(a.deletedAt);
      });
    }

    function findEntry(uid) {
      for (var i = 0; i < entries.length; i++) if (entries[i].uid === uid) return entries[i];
      return null;
    }

    function liveEntry(uid) {
      var e = findEntry(uid);
      return e && !e.deletedAt ? e : null;
    }

    // ui/VaultViewModel.kt dashboardEntries: every keyword must match some field.
    function filtered(list, query) {
      if (!query || !query.trim()) return list;
      var words = query.toLowerCase().split(/\s+/).filter(Boolean);
      return list.filter(function (e) {
        var tags = S.tags[e.uid] || [];
        return words.every(function (q) {
          return e.title.toLowerCase().indexOf(q) !== -1 ||
            e.username.toLowerCase().indexOf(q) !== -1 ||
            e.website.toLowerCase().indexOf(q) !== -1 ||
            e.notes.toLowerCase().indexOf(q) !== -1 ||
            e.category.toLowerCase().indexOf(q) !== -1 ||
            tags.some(function (t) { return t.toLowerCase().indexOf(q) !== -1; }) ||
            e.customFields.some(function (f) { return f.value.toLowerCase().indexOf(q) !== -1; });
        });
      });
    }

    function emitChange() {
      changeCbs.slice().forEach(function (cb) {
        try { cb(); } catch (err) { if (window.console) console.error(err); }
      });
    }

    function emitLock() {
      var unlocked = !S.locked;
      lockCbs.slice().forEach(function (cb) {
        try { cb(unlocked); } catch (err) { if (window.console) console.error(err); }
      });
    }

    /* ---------- timers */

    function later(fn, ms) {
      var id = setTimeout(function () {
        timers = timers.filter(function (t) { return t !== id; });
        if (!destroyed) fn();
      }, ms);
      timers.push(id);
      return id;
    }

    function cancel(id) {
      clearTimeout(id);
      timers = timers.filter(function (t) { return t !== id; });
    }

    /* ---------- navigation */

    function top() {
      return S.stack[S.stack.length - 1];
    }

    function routeKey(r) {
      return r.name + (r.uid ? ':' + r.uid : '');
    }

    function enterRoute(r) {
      // remember {} state in Compose starts over each time a screen enters composition
      S.menu = null;
      if (r.name === 'generator') resetGenerator();
      if (r.name === 'settings') S.appearanceOpen = false;
      if (r.name === 'details') S.reveal = {};
    }

    function push(r) {
      S.stack.push(r);
      if (r.name === 'add' || r.name === 'edit') openForm(r);
      enterRoute(r);
      render();
    }

    function pop() {
      if (S.stack.length <= 1) return;
      var gone = S.stack.pop();
      delete scrollMemory[routeKey(gone)];
      if (gone.name === 'add' || gone.name === 'edit') S.form = null;
      enterRoute(top());
      render();
    }

    function tab(name) {
      var current = top();
      if (current.name === name && S.stack.length <= 2) return;
      S.stack = name === 'dashboard' ? [{ name: 'dashboard' }] : [{ name: 'dashboard' }, { name: name }];
      enterRoute(top());
      render();
    }

    function stackFor(name) {
      switch (name) {
        case 'security': return [{ name: 'dashboard' }, { name: 'security' }];
        case 'generator': return [{ name: 'dashboard' }, { name: 'generator' }];
        case 'settings': return [{ name: 'dashboard' }, { name: 'settings' }];
        case 'trash':
        case 'recycle':
        case 'recycle_bin': return [{ name: 'dashboard' }, { name: 'settings' }, { name: 'recycle' }];
        case 'sync': return [{ name: 'dashboard' }, { name: 'settings' }, { name: 'sync' }];
        case 'weak':
        case 'reused':
        case 'missing': return [{ name: 'dashboard' }, { name: 'security' }, { name: name }];
        case 'dashboard':
        case 'vault': return [{ name: 'dashboard' }];
        default: return null;
      }
    }

    function resetGenerator() {
      S.gen = { length: 18, upper: true, lower: true, nums: true, syms: true, password: '' };
      S.gen.password = generateSimple(S.gen.length, S.gen);
    }

    /* ---------- lock */

    function dropSheet() {
      if (S.sheet && S.sheet.timer) cancel(S.sheet.timer);
      S.sheet = null;
      S.biometricEnabling = false;
    }

    function lockVault() {
      if (S.locked) return;
      dropSheet();
      S.locked = true;
      S.lock = { password: '', visible: false, error: null };
      S.dialog = null;
      S.sheet = null;
      S.menu = null;
      S.form = null;
      S.stack = [{ name: 'dashboard' }];
      S.search.active = false;
      render();
      scrollMemory = {};
      emitLock();
    }

    function unlockVault() {
      if (!S.locked) return;
      S.locked = false;
      S.sheet = null;
      S.lock = { password: '', visible: false, error: null };
      S.stack = [{ name: 'dashboard' }];
      enterRoute(top());
      render();
      emitLock();
    }

    /* ---------- toast and live region */

    function announce(message) {
      liveEl.textContent = '';
      later(function () { liveEl.textContent = message; }, 40);
    }

    function toast(message, long) {
      if (destroyed) return;
      toastEl.textContent = message;
      toastEl.classList.add('vpm-toast--show');
      announce(message);
      if (toastTimer) cancel(toastTimer);
      // Toast.LENGTH_SHORT is 2 s and LENGTH_LONG 3.5 s on Android
      toastTimer = later(function () {
        toastTimer = null;
        toastEl.classList.remove('vpm-toast--show');
      }, long ? 3500 : 2000);
    }

    /* ---------- clipboard: only on an explicit click, never cleared or overwritten later */

    function copyText(label, text) {
      if (!text) return;
      var done = function () { toast(label + ' copied to clipboard'); };
      var fallback = function () {
        if (destroyed) return;
        // Kept inside the clipped screen and focused without scrolling, so the host page never moves.
        var ta = document.createElement('textarea');
        ta.value = text;
        ta.setAttribute('readonly', '');
        ta.setAttribute('aria-hidden', 'true');
        ta.setAttribute('tabindex', '-1');
        ta.style.position = 'absolute';
        ta.style.left = '0';
        ta.style.top = '0';
        ta.style.width = '1px';
        ta.style.height = '1px';
        ta.style.opacity = '0';
        ta.style.pointerEvents = 'none';
        var prev = document.activeElement;
        var holder = root.querySelector('.vpm-screen') || root;
        restoringFocus = true;
        var ok = false;
        try {
          holder.appendChild(ta);
          ta.focus({ preventScroll: true });
          ta.setSelectionRange(0, ta.value.length);
          try { ok = document.execCommand('copy'); } catch (err) { ok = false; }
          holder.removeChild(ta);
          if (prev && prev.focus) prev.focus({ preventScroll: true });
        } finally {
          restoringFocus = false;
        }
        if (ok) done();
      };
      if (window.isSecureContext && navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done, fallback);
      } else {
        fallback();
      }
    }

    /* ------------------------------------------------------------------ shared templates */

    function iconBtn(act, iconName, label, extra) {
      return '<button type="button" class="vpm-iconbtn vpm-ripple' + (extra && extra.cls ? ' ' + extra.cls : '') + '" data-act="' + act + '"' +
        (extra && extra.attrs ? ' ' + extra.attrs : '') +
        ' aria-label="' + esc(label) + '" data-fk="' + esc(extra && extra.fk ? extra.fk : act) + '">' + icon(iconName) + '</button>';
    }

    function switchEl(key, checked, labelledBy, container) {
      return '<button type="button" role="switch" class="vpm-switch' + (container ? ' vpm-switch--container' : '') + '" aria-checked="' + (checked ? 'true' : 'false') + '"' +
        ' aria-labelledby="' + labelledBy + '" data-act="toggle" data-key="' + key + '" data-fk="sw-' + key + '"></button>';
    }

    // CircularProgressIndicator(progress) with the 1.3 track gap and round caps
    function ring(score, size, stroke, valueText) {
      var c = size / 2;
      var r = (size - stroke) / 2;
      var sweep = clamp(score, 0, 100) / 100 * 360;
      var gapSweep = ((4 + stroke) / (Math.PI * size)) * 360;
      var g = Math.min(sweep, gapSweep);
      var trackLen = 360 - sweep - 2 * g;
      var svg = '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '" aria-hidden="true">';
      if (trackLen > 0) {
        svg += '<circle class="vpm-ring-track" cx="' + c + '" cy="' + c + '" r="' + r + '" stroke-width="' + stroke + '" pathLength="360"' +
          ' stroke-dasharray="' + trackLen.toFixed(2) + ' 360" stroke-dashoffset="' + (-(sweep + g)).toFixed(2) + '" transform="rotate(-90 ' + c + ' ' + c + ')"/>';
      }
      if (sweep > 0) {
        svg += '<circle class="vpm-ring-ind" cx="' + c + '" cy="' + c + '" r="' + r + '" stroke-width="' + stroke + '" pathLength="360"' +
          ' stroke-dasharray="' + sweep.toFixed(2) + ' 360" transform="rotate(-90 ' + c + ' ' + c + ')"/>';
      }
      svg += '</svg>';
      return '<div class="vpm-ring vpm-ring--' + size + '">' + svg + '<span class="vpm-ring-value">' + esc(valueText) + '</span></div>';
    }

    // LinearProgressIndicator(progress) with the 1.3 gap and stop indicator, 6dp tall
    function linear(fraction, tone) {
      var pct = clamp(fraction, 0, 1) * 100;
      var html = '<div class="vpm-linear vpm-linear--' + tone + '" aria-hidden="true">';
      if (pct > 0) html += '<div class="vpm-linear-ind" data-width="' + pct.toFixed(2) + '%"></div>';
      if (pct < 100) {
        html += '<div class="vpm-linear-track" data-left="' + (pct > 0 ? 'calc(' + pct.toFixed(2) + '% + 4px)' : '0px') + '"></div>';
        html += '<span class="vpm-linear-stop"></span>';
      }
      return html + '</div>';
    }

    // Material 3 1.3 Slider. trackWidth is the track's length in dp (layout is fixed). o.steps is the
    // Compose `steps` argument (stops between the two ends); without it every whole number is a stop.
    // The range input uses the same stops, so like SliderState it snaps a value that sits between two
    // stops (the popover's starting 16) to the nearest one, while the label keeps showing o.text.
    function slider(o) {
      var steps = typeof o.steps === 'number' ? o.steps : o.max - o.min - 1;
      var stepSize = (o.max - o.min) / (steps + 1);
      var html = '<div class="vpm-slider" data-slider="' + o.key + '" data-min="' + o.min + '" data-max="' + o.max + '" data-track="' + o.trackWidth + '">' +
        '<div class="vpm-slider-track" aria-hidden="true"><div class="vpm-slider-active"></div><div class="vpm-slider-inactive"></div>';
      for (var i = 0; i <= steps + 1; i++) {
        html += '<span class="vpm-slider-tick" data-tick="' + i + '"></span>';
      }
      html += '</div><div class="vpm-slider-handle" aria-hidden="true"></div>' +
        '<input class="vpm-slider-input" type="range" min="' + o.min + '" max="' + o.max + '" step="' + String(+stepSize.toFixed(6)) + '" value="' + o.value + '"' +
        ' aria-label="' + esc(o.label) + '" aria-valuetext="' + esc(o.text) + ' characters" data-in="' + o.key + '" data-fk="slider-' + o.key + '"></div>';
      return html;
    }

    // Positions and colours of the slider's tick dots (Slider.kt drawTrack): dots on the active part use
    // the inactive track colour and the others the active colour; dots inside the handle gap and the last
    // one (the stop indicator's place) are not drawn as ticks.
    function paintSlider(el) {
      if (!el) return;
      var min = +el.getAttribute('data-min');
      var max = +el.getAttribute('data-max');
      var trackW = +el.getAttribute('data-track');
      var input = el.querySelector('input');
      var value = +input.value;
      var fraction = (value - min) / (max - min);
      el.style.setProperty('--vpm-p', fraction.toFixed(4));
      var ticks = el.querySelectorAll('.vpm-slider-tick');
      var n = ticks.length - 1;
      var thumbX = fraction * trackW;
      var atEnd = fraction >= 1;
      for (var i = 0; i <= n; i++) {
        var t = i / n;
        var x = 8 + (trackW - 16) * t;
        var tick = ticks[i];
        tick.style.left = x.toFixed(2) + 'px';
        var hidden = Math.abs(x - thumbX) <= 8 || (i === n && atEnd);
        tick.hidden = hidden;
        tick.classList.toggle('vpm-slider-tick--on', t <= fraction && i !== n);
      }
    }

    function topbar(o) {
      return '<header class="vpm-topbar' + (o.solid ? ' vpm-topbar--solid' : '') + '">' +
        (o.back ? iconBtn('back', 'arrowBack', 'Back', { cls: 'vpm-topbar-nav', fk: 'back' }) : '') +
        '<h1 class="vpm-topbar-title' + (o.brand ? ' vpm-topbar-title--brand' : '') + (o.bold ? ' vpm-topbar-title--bold' : '') + '">' + (o.icon ? icon(o.icon) : '') + '<span>' + esc(o.title) + '</span></h1>' +
        (o.actions || '') + '</header>';
    }

    function header(o) {
      return '<header class="vpm-header">' + iconBtn('back', 'arrowBack', 'Back', { fk: 'back' }) +
        '<h1 class="vpm-header-title' + (o.error ? ' vpm-header-title--error' : '') + '">' + icon(o.icon) + '<span>' + esc(o.title) + '</span></h1></header>';
    }

    function navbar(current) {
      var tabs = [['dashboard', 'lock', 'Vault'], ['security', 'shield', 'Security'], ['generator', 'vpnKey', 'Generator'], ['settings', 'settings', 'Settings']];
      return '<nav class="vpm-navbar" aria-label="VaultPass">' + tabs.map(function (t) {
        var on = current === t[0];
        return '<button type="button" class="vpm-navitem" data-act="tab" data-tab="' + t[0] + '" data-fk="tab-' + t[0] + '"' + (on ? ' aria-current="page"' : '') + '>' +
          '<span class="vpm-navitem-ind">' + icon(t[1]) + '</span><span class="vpm-navitem-label">' + t[2] + '</span></button>';
      }).join('') + '</nav>';
    }

    /* ------------------------------------------------------------------ screens */

    function renderDashboard() {
      var all = active();
      var stats = analyze(all);
      var q = S.search.query;
      var list = filtered(all, q);

      if (S.search.active) {
        return '<div class="vpm-search" role="search">' +
          '<div class="vpm-search-head">' + icon('search') +
            '<input class="vpm-search-input" type="text" data-in="search" data-fk="search-input" value="' + esc(q) + '" placeholder="Search vault…" aria-label="Search vault" autocomplete="off" spellcheck="false" enterkeyhint="search">' +
            iconBtn('search-close', 'close', 'Close', { fk: 'search-close' }) +
          '</div><div class="vpm-search-divider"></div>' +
          '<div class="vpm-scroll" data-scroll="search"><div class="vpm-search-list" data-results>' + renderSearchList(list) + '</div></div>' +
        '</div>' + navbar('dashboard');
      }

      var favorites = list.filter(function (e) { return e.favorite; });
      var html = '<div class="vpm-page">' +
        topbar({ title: 'VaultPass', icon: 'security', brand: true, actions:
          iconBtn('search-open', 'search', 'Search', { fk: 'dash-search' }) + iconBtn('lock', 'lock', 'Lock Vault', { fk: 'dash-lock' }) }) +
        '<div class="vpm-scroll" data-scroll="dashboard"><div class="vpm-dash-list">' +
          '<div class="vpm-dash-hero"><h2 class="vpm-dash-welcome">Welcome back</h2>' +
            '<div class="vpm-score-card">' + ring(stats.score, 64, 4, stats.score) +
              '<div><div class="vpm-score-title">Security Score</div><div class="vpm-score-status">' + esc(stats.status) + '</div></div></div></div>' +
          '<div class="vpm-stats">' +
            '<button type="button" class="vpm-stat vpm-ripple" data-act="stat-total" data-fk="stat-total"><span class="vpm-stat-count vpm-stat-count--total">' + stats.total + '</span><span class="vpm-stat-label">TOTAL</span></button>' +
            '<button type="button" class="vpm-stat vpm-ripple" data-act="go" data-route="weak" data-fk="stat-weak"><span class="vpm-stat-count vpm-stat-count--weak">' + stats.weak + '</span><span class="vpm-stat-label">WEAK</span></button>' +
            '<button type="button" class="vpm-stat vpm-ripple" data-act="go" data-route="reused" data-fk="stat-reused"><span class="vpm-stat-count vpm-stat-count--reused">' + stats.reused + '</span><span class="vpm-stat-label">REUSED</span></button>' +
          '</div>';

      if (favorites.length) {
        html += '<div class="vpm-section-row"><h3 class="vpm-section-title">Favorites</h3></div><div class="vpm-fav-row">' +
          favorites.map(function (e) {
            return '<button type="button" class="vpm-fav vpm-ripple" data-act="open" data-uid="' + esc(e.uid) + '" data-fk="fav-' + esc(e.uid) + '">' +
              '<span class="vpm-fav-avatar" aria-hidden="true">' + esc(initial(e.title)) + '</span>' +
              '<span class="vpm-fav-title">' + esc(e.title) + '</span><span class="vpm-fav-sub">Personal</span></button>';
          }).join('') + '</div>';
      }

      if (q.trim()) {
        html += '<div class="vpm-results-banner"><span>Showing results for: "' + esc(q) + '"</span>' +
          iconBtn('search-clear', 'close', 'Clear', { fk: 'banner-clear' }) + '</div>';
      }

      html += '<div class="vpm-section-row"><h3 class="vpm-section-title">Recently Accessed</h3>' +
        (q.trim() ? '<button type="button" class="vpm-textbtn vpm-ripple" data-act="clear-search" data-fk="clear-search">Clear Search</button>' : '') + '</div>';

      if (!list.length) {
        html += '<div class="vpm-empty">Your vault is empty</div>';
      } else {
        html += list.map(function (e) {
          return '<button type="button" class="vpm-entry vpm-ripple" data-act="open" data-uid="' + esc(e.uid) + '" data-fk="entry-' + esc(e.uid) + '">' +
            '<span class="vpm-entry-avatar" aria-hidden="true">' + esc(initial(e.title)) + '</span>' +
            '<span class="vpm-entry-text"><span class="vpm-entry-title">' + esc(e.title) + '</span><span class="vpm-entry-sub">' + esc(previewLine(e)) + '</span></span></button>';
        }).join('');
      }

      html += '</div></div>' +
        '<button type="button" class="vpm-fab vpm-ripple" data-act="go" data-route="add" aria-label="Add Entry" data-fk="fab">' + icon('add') + '</button>' +
        '</div>' + navbar('dashboard');
      return html;
    }

    function renderSearchList(list) {
      return list.map(function (e) {
        return '<button type="button" class="vpm-listitem vpm-ripple" data-act="open" data-uid="' + esc(e.uid) + '" data-fk="result-' + esc(e.uid) + '">' +
          '<span class="vpm-listitem-lead" aria-hidden="true">' + icon('vpnKey') + '</span>' +
          '<span class="vpm-listitem-text"><span class="vpm-listitem-title">' + esc(e.title) + '</span><span class="vpm-listitem-sub">' + esc(previewLine(e)) + '</span></span>' +
          (e.favorite ? '<span class="vpm-listitem-star" role="img" aria-label="Favorite">' + icon('star') + '</span>' : '') +
          '</button>';
      }).join('');
    }

    function renderSecurity() {
      var all = active();
      var st = analyze(all);
      var total = st.total;
      function pct(n) { return total > 0 ? n / total : 0; }
      function pctText(n) { return n + ' (' + Math.trunc(pct(n) * 100) + '%)'; }
      var recs = recommendations(st.weak, st.reused, st.missing);

      var html = '<div class="vpm-page">' +
        topbar({ title: 'Security Center', icon: 'shield', brand: true, actions: iconBtn('lock', 'lock', 'Lock Vault', { fk: 'sec-lock' }) }) +
        '<div class="vpm-scroll" data-scroll="security"><div class="vpm-sec-list">' +
          '<div class="vpm-sec-hero"><h2 class="vpm-sec-title">Vault Health</h2>' +
            '<p class="vpm-sec-desc">Your score is calculated based on password strength and reuse.</p>' +
            '<div class="vpm-health-card">' + ring(st.score, 120, 12, st.score) +
              '<div class="vpm-health-status">' + esc(st.status) + '</div></div></div>' +
          '<div class="vpm-sec-block"><h3 class="vpm-sec-block-title">Strength Distribution</h3>' +
            '<div class="vpm-dist-card">' +
              '<div class="vpm-dist-row"><strong>Strong</strong><span>' + pctText(st.strong) + '</span></div>' + linear(pct(st.strong), 'primary') +
              '<div class="vpm-dist-row"><strong>Medium</strong><span>' + pctText(st.medium) + '</span></div>' + linear(pct(st.medium), 'tertiary') +
              '<div class="vpm-dist-row"><strong>Weak</strong><span>' + pctText(st.weak) + '</span></div>' + linear(pct(st.weak), 'error') +
            '</div><div class="vpm-sec-spacer"></div></div>' +
          '<div class="vpm-sec-block"><h3 class="vpm-sec-block-title">Security Issues</h3>' +
            '<button type="button" class="vpm-issue vpm-ripple" data-act="go" data-route="weak" data-fk="issue-weak">' +
              '<span class="vpm-issue-badge">' + icon('warning') + '</span>' +
              '<span class="vpm-issue-text"><span class="vpm-issue-title">Weak Passwords</span><span class="vpm-issue-desc">Easily guessable passwords</span></span>' +
              '<span class="vpm-issue-count">' + st.weak + '</span></button>' +
            '<button type="button" class="vpm-issue vpm-ripple" data-act="go" data-route="reused" data-fk="issue-reused">' +
              icon('contentCopy', 'vpm-issue-icon--tertiary') +
              '<span class="vpm-issue-text"><span class="vpm-issue-title">Reused Passwords: ' + st.reused + '</span><span class="vpm-issue-desc">Same password used across accounts</span></span></button>' +
            '<button type="button" class="vpm-issue vpm-ripple" data-act="go" data-route="missing" data-fk="issue-missing">' +
              icon('warning', 'vpm-issue-icon--error') +
              '<span class="vpm-issue-text"><span class="vpm-issue-title">Missing Passwords: ' + st.missing + '</span><span class="vpm-issue-desc">Incomplete credential entries</span></span></button>' +
          '</div>' +
          '<div class="vpm-rec-block"><h3 class="vpm-sec-block-title">Recommendations</h3>';
      if (!recs.length) {
        html += '<div class="vpm-issue">' + icon('shield', 'vpm-issue-icon--primary') +
          '<span class="vpm-issue-text"><span class="vpm-issue-title">Looking good!</span><span class="vpm-issue-desc">No security recommendations at this time.</span></span></div>';
      } else {
        html += recs.map(function (r) {
          return '<button type="button" class="vpm-issue vpm-ripple" data-act="go" data-route="' + r.route + '" data-fk="rec-' + r.route + '">' +
            icon(r.icon, 'vpm-issue-icon--primary') +
            '<span class="vpm-issue-text"><span class="vpm-issue-title">' + esc(r.title) + '</span><span class="vpm-issue-desc">' + esc(r.desc) + '</span></span></button>';
        }).join('');
      }
      html += '</div></div></div></div>' + navbar('security');
      return html;
    }

    function reportShell(title, body) {
      return '<div class="vpm-page vpm-page--inset-bottom">' + topbar({ title: title, back: true, bold: true, solid: true }) +
        '<div class="vpm-scroll" data-scroll="' + esc(top().name) + '"><div class="vpm-report-list">' + body + '</div></div></div>';
    }

    function scorePill(score) {
      return '<span class="vpm-score-pill vpm-score-pill--' + (score <= 20 ? 'error' : 'tertiary') + '">' + score + '/100</span>';
    }

    function renderWeak() {
      var all = active();
      var st = analyze(all);
      var list = all.filter(function (e) { return st.weakIds[e.uid]; }).sort(function (a, b) { return st.scores[a.uid] - st.scores[b.uid]; });
      var body;
      if (!list.length) {
        body = '<div class="vpm-empty">No weak passwords found! Great job.</div>';
      } else {
        body = '<p class="vpm-report-intro">We found ' + list.length + ' weak passwords in your vault. Consider updating them to improve your security score.</p>' +
          list.map(function (e) {
            return '<button type="button" class="vpm-report-card vpm-report-card--pad vpm-ripple" data-act="open" data-uid="' + esc(e.uid) + '" data-fk="weak-' + esc(e.uid) + '">' +
              '<span class="vpm-report-head"><span class="vpm-report-who"><span class="vpm-report-avatar" aria-hidden="true">' + esc(initial(e.title)) + '</span>' +
              '<span class="vpm-entry-text"><span class="vpm-entry-title">' + esc(e.title) + '</span><span class="vpm-entry-sub">' + esc(previewLine(e)) + '</span></span></span>' +
              scorePill(st.scores[e.uid]) + '</span>' +
              '<span class="vpm-report-divider" aria-hidden="true"></span>' +
              '<span class="vpm-reasons-title">Weakness Reasons:</span>' +
              (st.reasons[e.uid] || []).map(function (r) {
                return '<span class="vpm-reason">' + icon('warning') + '<span>' + esc(r) + '</span></span>';
              }).join('') + '</button>';
          }).join('');
      }
      return reportShell('Weak Passwords', body);
    }

    function renderReused() {
      var all = active();
      var st = analyze(all);
      var groups = {};
      var order = [];
      all.forEach(function (e) {
        if (!st.reusedIds[e.uid]) return;
        if (!groups[e.password]) { groups[e.password] = []; order.push(e.password); }
        groups[e.password].push(e);
      });
      var list = order.map(function (pw) { return groups[pw]; }).sort(function (a, b) { return b.length - a.length; });
      var body;
      if (!list.length) {
        body = '<div class="vpm-empty">No reused passwords found! Excellent.</div>';
      } else {
        var accounts = list.reduce(function (n, g) { return n + g.length; }, 0);
        body = '<p class="vpm-report-intro">We found ' + accounts + ' accounts sharing ' + list.length + ' passwords. Reusing passwords increases your risk if one account is compromised.</p>' +
          list.map(function (g, gi) {
            return '<div class="vpm-report-card"><div class="vpm-group-head"><span class="vpm-group-head-title">Reused across ' + g.length + ' accounts</span>' + scorePill(st.scores[g[0].uid]) + '</div>' +
              '<div class="vpm-group-rows">' + g.map(function (e, i) {
                return (i ? '<div class="vpm-group-row-divider"></div>' : '') +
                  '<button type="button" class="vpm-group-row vpm-ripple" data-act="open" data-uid="' + esc(e.uid) + '" data-fk="reused-' + gi + '-' + esc(e.uid) + '">' +
                  '<span class="vpm-report-who"><span class="vpm-report-avatar vpm-report-avatar--round vpm-report-avatar--small" aria-hidden="true">' + esc(initial(e.title)) + '</span>' +
                  '<span class="vpm-entry-text"><span class="vpm-entry-title">' + esc(e.title) + '</span><span class="vpm-entry-sub">' + esc(previewLine(e)) + '</span></span></span>' +
                  icon('chevronRight', 'vpm-report-chevron') + '</button>';
              }).join('') + '</div></div>';
          }).join('');
      }
      return reportShell('Reused Passwords', body);
    }

    function renderMissing() {
      var all = active();
      var st = analyze(all);
      var list = all.filter(function (e) { return st.missingIds[e.uid]; });
      var body;
      if (!list.length) {
        if (!all.length) {
          body = '<div class="vpm-allgood">' + icon('inbox') + '<h2 class="vpm-allgood-title">Vault Empty</h2><p class="vpm-allgood-text">No entries have been added yet.</p></div>';
        } else {
          body = '<div class="vpm-allgood">' + icon('checkCircle') + '<h2 class="vpm-allgood-title">All Passwords Complete</h2>' +
            '<p class="vpm-allgood-text">All stored entries contain passwords.<br>Your vault is complete and no action is required.</p>' +
            '<span class="vpm-allgood-pill">Total Entries: ' + all.length + '</span></div>';
        }
      } else {
        body = '<p class="vpm-report-intro">We found ' + list.length + ' accounts with no password. Incomplete credentials leave you vulnerable. Tap an account to add a password.</p>' +
          list.map(function (e) {
            return '<button type="button" class="vpm-report-row vpm-ripple" data-act="open" data-uid="' + esc(e.uid) + '" data-fk="missing-' + esc(e.uid) + '">' +
              '<span class="vpm-report-who"><span class="vpm-report-avatar vpm-report-avatar--round" aria-hidden="true">' + esc(initial(e.title)) + '</span>' +
              '<span class="vpm-entry-text"><span class="vpm-entry-title">' + esc(e.title) + '</span><span class="vpm-entry-sub">' + esc(previewLine(e)) + '</span></span></span>' +
              icon('chevronRight', 'vpm-report-chevron') + '</button>';
          }).join('');
      }
      return reportShell('Missing Passwords', body);
    }

    function genAnalysis(g) {
      var pool = poolFor(g).length;
      var entropy = pool > 1 ? Math.trunc(g.length * Math.log2(pool)) : 0;
      var label = entropy < 50 ? 'Weak' : (entropy < 80 ? 'Good' : 'Strong');
      var crack = entropy < 40 ? 'Instantly' : entropy < 60 ? 'Hours' : entropy < 80 ? 'Months' : entropy < 100 ? 'Years' : 'Centuries';
      return { pool: pool, entropy: entropy, label: label, tone: label.toLowerCase(), crack: crack, ratio: clamp(entropy / 120, 0, 1) };
    }

    function renderGenerator() {
      var g = S.gen;
      var a = genAnalysis(g);
      var meterFill = Math.max(a.ratio, 0.01);
      var meterRest = Math.max(1 - a.ratio, 0.01);
      var toggles = [['genUpper', 'A', 'Uppercase', g.upper], ['genLower', 'a', 'Lowercase', g.lower], ['genNums', '1', 'Numbers', g.nums], ['genSyms', '@', 'Symbols', g.syms]];
      return '<div class="vpm-page">' +
        '<header class="vpm-header">' + iconBtn('back', 'arrowBack', 'Back', { fk: 'back' }) +
          '<h1 class="vpm-header-title">' + icon('password') + '<span>Generator</span></h1></header>' +
        '<div class="vpm-scroll" data-scroll="generator"><div class="vpm-gen-body">' +
          '<section class="vpm-gen-card vpm-gen-card--display">' +
            '<div class="vpm-gen-head"><span class="vpm-gen-cap">GENERATED PASSWORD</span>' +
              '<span class="vpm-strength vpm-strength--' + a.tone + '" data-gen="badge">' + icon('verifiedUser') + '<span data-gen="label">' + a.label + '</span></span></div>' +
            '<div class="vpm-gen-output" data-gen="output" aria-live="off">' + esc(g.password) + '</div>' +
            '<div class="vpm-gen-actions">' +
              '<button type="button" class="vpm-btn vpm-gen-copy vpm-ripple" data-act="gen-copy" data-fk="gen-copy">' + icon('contentCopy') + '<span>Copy Password</span></button>' +
              '<button type="button" class="vpm-gen-regen vpm-ripple" data-act="gen-regen" aria-label="Regenerate" data-fk="gen-regen">' + icon('refresh') + '</button>' +
            '</div></section>' +
          '<section class="vpm-gen-card" aria-label="Security analysis"><span class="vpm-gen-cap">SECURITY ANALYSIS</span>' +
            '<div class="vpm-gen-meter vpm-gen-meter--' + a.tone + '" data-gen="meter" aria-hidden="true"><div class="vpm-gen-meter-fill" data-gen="fill" data-width="' + (meterFill / (meterFill + meterRest) * 100).toFixed(2) + '%"></div></div>' +
            '<div class="vpm-gen-stats">' +
              '<div class="vpm-gen-stat"><span class="vpm-gen-stat-label">Entropy</span><span class="vpm-gen-stat-value" data-gen="entropy">' + a.entropy + ' bits</span></div>' +
              '<div class="vpm-gen-stat"><span class="vpm-gen-stat-label">Crack Time</span><span class="vpm-gen-stat-value" data-gen="crack">' + a.crack + '</span></div>' +
              '<div class="vpm-gen-stat"><span class="vpm-gen-stat-label">Pattern</span><span class="vpm-gen-stat-value">Random</span></div>' +
              '<div class="vpm-gen-stat"><span class="vpm-gen-stat-label">Pool</span><span class="vpm-gen-stat-value" data-gen="pool">' + a.pool + ' chars</span></div>' +
            '</div></section>' +
          '<section class="vpm-gen-card" aria-label="Configuration"><span class="vpm-gen-cap vpm-gen-cap--primary">CONFIGURATION</span><div class="vpm-divider vpm-divider--10"></div>' +
            '<div class="vpm-gen-length"><div class="vpm-gen-length-row"><span>Length</span><span class="vpm-gen-length-value" data-gen="length">' + g.length + '</span></div>' +
              slider({ key: 'gen-length', min: 8, max: 64, value: g.length, text: g.length, label: 'Length', trackWidth: 282 }) + '</div>' +
            '<div class="vpm-gen-toggles">' + toggles.map(function (t) {
              var id = uidBase + '-' + t[0];
              return '<div class="vpm-toggle-row"><span class="vpm-toggle-name"><span class="vpm-toggle-glyph" aria-hidden="true">' + t[1] + '</span><span id="' + id + '">' + t[2] + '</span></span>' +
                switchEl(t[0], t[3], id, true) + '</div>';
            }).join('') + '</div>' +
          '</section>' +
        '</div></div></div>' + navbar('generator');
    }

    function updateGeneratorView() {
      var g = S.gen;
      var a = genAnalysis(g);
      var q = function (k) { return appEl.querySelector('[data-gen="' + k + '"]'); };
      var out = q('output');
      if (!out) return;
      out.textContent = g.password;
      q('badge').className = 'vpm-strength vpm-strength--' + a.tone;
      q('label').textContent = a.label;
      q('meter').className = 'vpm-gen-meter vpm-gen-meter--' + a.tone;
      var fill = Math.max(a.ratio, 0.01);
      var rest = Math.max(1 - a.ratio, 0.01);
      q('fill').style.width = (fill / (fill + rest) * 100).toFixed(2) + '%';
      q('fill').removeAttribute('data-width');
      q('entropy').textContent = a.entropy + ' bits';
      q('crack').textContent = a.crack;
      q('pool').textContent = a.pool + ' chars';
      q('length').textContent = g.length;
    }

    function settingsRow(o) {
      var titleId = uidBase + '-row-' + o.key;
      var tag = o.act ? 'button' : 'div';
      return '<' + tag + (o.act ? ' type="button" data-act="' + o.act + '" data-fk="row-' + o.key + '"' + (o.attrs ? ' ' + o.attrs : '') : '') +
        ' class="vpm-row' + (o.act ? ' vpm-ripple' : '') + '">' +
        '<span class="vpm-row-main"><span class="vpm-row-icon vpm-row-icon--' + o.tone + '">' + icon(o.icon) + '</span>' +
        '<span class="vpm-row-text"><span class="vpm-row-title" id="' + titleId + '">' + esc(o.title) + '</span><span class="vpm-row-sub">' + esc(o.sub) + '</span></span></span>' +
        (o.switchKey ? switchEl(o.switchKey, o.checked, titleId, false) : '') +
        (o.trail ? '<span class="vpm-row-trail">' + o.trail + '</span>' : '') +
        '</' + tag + '>';
    }

    function selectField(key, label, value, options) {
      var open = S.menu === key;
      var labelId = uidBase + '-sel-' + key;
      var html = '<div class="vpm-select">' +
        '<button type="button" class="vpm-select-field" data-act="menu" data-menu="' + key + '" data-fk="sel-' + key + '" aria-haspopup="listbox" aria-expanded="' + (open ? 'true' : 'false') + '" aria-labelledby="' + labelId + ' ' + labelId + '-v">' +
          '<span class="vpm-select-label" id="' + labelId + '">' + esc(label) + '</span><span class="vpm-select-value" id="' + labelId + '-v">' + esc(value) + '</span>' + icon('arrowDropDown') +
        '</button>';
      if (open) {
        html += '<div class="vpm-menu" role="listbox" aria-labelledby="' + labelId + '">' + options.map(function (o, i) {
          return '<button type="button" role="option" class="vpm-menu-item vpm-ripple" aria-selected="' + (o.selected ? 'true' : 'false') + '" data-act="menu-pick" data-menu="' + key + '" data-value="' + esc(o.value) + '" data-fk="opt-' + key + '-' + i + '">' +
            (o.swatch ? '<span class="vpm-swatch" data-swatch="' + o.swatch + '" aria-hidden="true"></span>' : '') + '<span>' + esc(o.label) + '</span></button>';
        }).join('') + '</div>';
      }
      return html + '</div>';
    }

    function renderSettings() {
      var accent = ACCENTS.filter(function (a) { return a.key === S.accent; })[0] || ACCENTS[0];
      var autoLockLabel = 'Unknown';
      AUTO_LOCK.forEach(function (o) { if (o[0] === S.autoLock) autoLockLabel = o[1]; });
      var chevron = icon('chevronRight');

      var appearance = settingsRow({ key: 'appearance', act: 'appearance', attrs: 'aria-expanded="' + (S.appearanceOpen ? 'true' : 'false') + '"', title: 'APPEARANCE', sub: 'Theme and Accent Color', icon: 'palette', tone: 'primary', trail: icon(S.appearanceOpen ? 'expandLess' : 'expandMore') });
      if (S.appearanceOpen) {
        appearance += '<div class="vpm-appearance-panel">' +
          selectField('theme', 'Theme', THEME_OPTIONS[S.themeMode], THEME_OPTIONS.map(function (t, i) { return { value: String(i), label: t, selected: i === S.themeMode }; })) +
          selectField('accent', 'Accent Color', accent.title, ACCENTS.map(function (a) { return { value: a.key, label: a.title, swatch: a.key, selected: a.key === S.accent }; })) +
          '</div>';
      }

      return '<div class="vpm-page">' +
        '<header class="vpm-header">' + iconBtn('back', 'arrowBack', 'Back', { fk: 'back' }) +
          '<h1 class="vpm-header-title">' + icon('settings') + '<span>Settings</span></h1></header>' +
        '<div class="vpm-scroll" data-scroll="settings"><div class="vpm-settings-body">' +
          '<div class="vpm-settings-intro"><h2>Settings</h2><p>Manage your vault preferences and security configurations.</p></div>' +

          '<section class="vpm-group"><h3 class="vpm-group-label">APPEARANCE</h3><div class="vpm-group-card">' +
            appearance + '<div class="vpm-divider"></div>' +
            settingsRow({ key: 'hide', title: 'Hide Passwords by Default', sub: 'Obscure passwords in lists', icon: 'visibilityOff', tone: 'secondary', switchKey: 'hidePasswords', checked: S.hidePasswords }) +
          '</div></section>' +

          '<section class="vpm-group"><h3 class="vpm-group-label">SECURITY</h3><div class="vpm-group-card">' +
            settingsRow({ key: 'bio', title: 'Biometric Unlock', sub: 'Use fingerprint/face to access vault', icon: 'fingerprint', tone: 'primary', switchKey: 'biometric', checked: S.biometric || S.biometricEnabling }) +
            '<div class="vpm-divider"></div>' +
            settingsRow({ key: 'shots', title: 'Disable Screenshots', sub: 'Prevent screen capture & recents', icon: 'security', tone: 'error', switchKey: 'disableScreenshots', checked: S.disableScreenshots }) +
            '<div class="vpm-divider"></div>' +
            settingsRow({ key: 'autolock', act: 'autolock-open', attrs: 'aria-haspopup="dialog"', title: 'Auto Lock', sub: autoLockLabel, icon: 'timer', tone: 'tertiary' }) +
            '<div class="vpm-divider"></div>' +
            settingsRow({ key: 'autofill', act: 'autofill', title: 'Enable Autofill', sub: S.autofillEnabled ? 'Enabled' : 'Allow VaultPass to fill passwords', icon: 'edit', tone: S.autofillEnabled ? 'green' : 'primary', trail: chevron }) +
            '<p class="vpm-autofill-note">' + icon('warningAmber') + '<span>Autofill is an experimental feature. Compatibility may vary across apps, browsers, and Android versions.</span></p>' +
          '</div></section>' +

          '<section class="vpm-group"><h3 class="vpm-group-label">DATA BACKUP</h3><div class="vpm-group-card">' +
            settingsRow({ key: 'bin', act: 'go', attrs: 'data-route="recycle"', title: 'Recycle Bin', sub: 'Restore or permanently delete items', icon: 'deleteOutline', tone: 'error', trail: chevron }) +
            '<div class="vpm-divider"></div>' +
            settingsRow({ key: 'export', act: 'export-open', attrs: 'aria-haspopup="dialog"', title: 'Export Vault', sub: 'Save encrypted JSON to device', icon: 'upload', tone: 'secondary', trail: chevron }) +
            '<div class="vpm-divider"></div>' +
            settingsRow({ key: 'import', act: 'import', title: 'Import JSON', sub: 'Restore entries from backup', icon: 'download', tone: 'secondary', trail: chevron }) +
          '</div></section>' +

          '<section class="vpm-group"><h3 class="vpm-group-label">SYNC &amp; DEVICES</h3><div class="vpm-group-card">' +
            settingsRow({ key: 'sync', act: 'go', attrs: 'data-route="sync"', title: 'Device Synchronization', sub: 'Pair and sync with VaultPass Desktop on LAN', icon: 'sync', tone: 'primary', trail: chevron }) +
          '</div></section>' +

          '<section class="vpm-group"><h3 class="vpm-group-label">UPDATES</h3><div class="vpm-group-card">' +
            settingsRow({ key: 'updates', title: 'Check for updates when the app opens', sub: 'Asks GitHub for the latest version. Nothing from your vault is sent.', icon: 'update', tone: 'primary', switchKey: 'checkUpdates', checked: S.checkUpdates }) +
            '<div class="vpm-divider"></div>' +
            settingsRow({ key: 'checknow', act: 'check-now', title: 'Check now', sub: 'Installed version: ' + appVersion, icon: 'systemUpdate', tone: 'secondary', trail: chevron }) +
          '</div></section>' +

          '<a class="vpm-privacy-link" href="' + PRIVACY_URL + '" target="_blank" rel="noopener noreferrer" data-fk="privacy">Privacy Policy</a>' +
        '</div></div></div>' + navbar('settings');
    }

    function renderLock() {
      var L = S.lock;
      var inputId = uidBase + '-master';
      var msgId = uidBase + '-lockmsg';
      return '<div class="vpm-page vpm-lock">' +
        '<div class="vpm-lock-badge" aria-hidden="true">' + icon('security') + '</div>' +
        '<h1 class="vpm-lock-title">VaultPass</h1><p class="vpm-lock-sub">Vault Locked</p>' +
        '<div class="vpm-lock-card">' +
          (S.biometric ? '<button type="button" class="vpm-fp-btn" data-act="biometric" aria-label="Use Biometrics" data-fk="fp"><span class="vpm-fp-inner">' + icon('fingerprint') + '</span></button><div class="vpm-lock-gap"></div>' : '<div class="vpm-lock-gap"></div>') +
          '<div class="vpm-lock-field">' + icon('lock') +
            '<input id="' + inputId + '" class="vpm-lock-input" type="' + (L.visible ? 'text' : 'password') + '" data-in="lock" data-fk="lock-input" value="' + esc(L.password) + '" placeholder="Master Password" aria-label="Master Password" aria-describedby="' + msgId + '" autocomplete="off" spellcheck="false" enterkeyhint="done">' +
            iconBtn('lock-eye', L.visible ? 'visibility' : 'visibilityOff', 'Toggle password visibility', { fk: 'lock-eye', attrs: 'aria-pressed="' + (L.visible ? 'true' : 'false') + '"' }) +
          '</div>' +
          '<p class="vpm-lock-msg' + (L.error ? ' vpm-lock-msg--error' : '') + '" id="' + msgId + '">' + esc(L.error || 'Demo: any password unlocks') + '</p>' +
          '<button type="button" class="vpm-btn vpm-btn--container vpm-unlock-btn vpm-ripple" data-act="unlock" data-fk="unlock">' + icon('lockOpen') + '<span>Unlock</span></button>' +
        '</div>' +
        '<p class="vpm-e2e">' + icon('security') + '<span>End-to-End Encrypted</span></p>' +
        '<a class="vpm-lock-privacy" href="' + PRIVACY_URL + '" target="_blank" rel="noopener noreferrer" data-fk="lock-privacy">Privacy Policy</a>' +
        '</div>';
    }

    function renderDetails() {
      var e = liveEntry(top().uid);
      if (!e) return '';
      var fields = [];
      if (e.username.trim()) fields.push({ key: 'username', label: 'Username / Email', value: e.username, secret: false, copyLabel: 'Username' });
      if (e.password.trim()) fields.push({ key: 'password', label: 'Password', value: e.password, secret: true, copyLabel: 'Password' });
      e.customFields.forEach(function (f, i) {
        if (f.value.trim()) fields.push({ key: 'cf' + i, label: f.label, value: f.value, secret: true, copyLabel: f.label });
      });

      var html = '<div class="vpm-page">' +
        '<header class="vpm-header vpm-header--split">' + iconBtn('back', 'arrowBack', 'Back', { fk: 'back' }) +
          '<span class="vpm-header-center">VaultPass</span>' +
          iconBtn('edit', 'edit', 'Edit', { fk: 'detail-edit' }) + '</header>' +
        '<div class="vpm-scroll" data-scroll="' + esc(routeKey(top())) + '"><div class="vpm-detail-body">' +
          '<div class="vpm-detail-head"><div class="vpm-detail-who"><span class="vpm-detail-avatar" aria-hidden="true">' + esc(initial(e.title)) + '</span>' +
            '<div><h2 class="vpm-detail-title">' + esc(e.title) + '</h2><p class="vpm-detail-cat">' + esc(e.category) + '</p></div></div>' +
            '<button type="button" class="vpm-iconbtn vpm-ripple vpm-detail-star' + (e.favorite ? ' vpm-detail-star--on' : '') + '" data-act="detail-star" aria-label="Favorite" aria-pressed="' + (e.favorite ? 'true' : 'false') + '" data-fk="detail-star">' +
              icon(e.favorite ? 'star' : 'outlinedStarBorder') + '</button></div>' +
          '<section class="vpm-cred-card" aria-label="Credentials">' + fields.map(function (f) {
            var shown = !f.secret || S.reveal[f.key];
            return '<div class="vpm-cred-field"><span class="vpm-cap-label">' + esc(f.label) + '</span>' +
              '<div class="vpm-cred-value"><span class="vpm-cred-text">' + (shown ? esc(f.value) : '••••••••••••••••') + '</span>' +
              (f.secret ? iconBtn('reveal', S.reveal[f.key] ? 'visibilityOff' : 'visibility', 'Toggle password visibility', { fk: 'reveal-' + f.key, attrs: 'data-key="' + f.key + '" aria-pressed="' + (S.reveal[f.key] ? 'true' : 'false') + '"' }) : '') +
              iconBtn('copy', 'contentCopy', 'Copy', { fk: 'copy-' + f.key, attrs: 'data-key="' + f.key + '"' }) +
              '</div></div>';
          }).join('') + '</section>';

      if (e.website.trim()) {
        html += '<section class="vpm-section-card"><span class="vpm-cap-label">Website (URI)</span>' +
          '<div class="vpm-web-row">' + icon('language') + '<span class="vpm-web-text">' + esc(e.website) + '</span>' +
          (browserUrl(e.website) ? '<button type="button" class="vpm-textbtn vpm-ripple" data-act="web-open" data-fk="web-open">Open</button>' : '') +
          '</div></section>';
      }
      if (e.notes.trim()) {
        html += '<section class="vpm-section-card"><span class="vpm-cap-label">Secure Notes</span><div class="vpm-notes-box">' + esc(e.notes) + '</div></section>';
      }

      html += '</div></div>' +
        '<div class="vpm-detail-footer">' +
          '<button type="button" class="vpm-btn-danger vpm-ripple" data-act="delete-entry" data-fk="detail-delete">' + icon('delete') + '<span>Delete</span></button>' +
          '<button type="button" class="vpm-btn vpm-btn--container vpm-ripple" data-act="edit" data-fk="detail-edit-btn">' + icon('edit') + '<span>Edit Entry</span></button>' +
        '</div></div>';
      return html;
    }

    /* ---------- add / edit entry */

    function openForm(r) {
      var e = r.name === 'edit' ? liveEntry(r.uid) : null;
      var f = {
        mode: r.name,
        uid: e ? e.uid : null,
        title: e ? e.title : '',
        username: e ? e.username : '',
        password: e ? e.password : '',
        website: e ? e.website : '',
        notes: e ? e.notes : '',
        category: e ? e.category : 'Personal',
        favorite: e ? e.favorite : false,
        customFields: e ? e.customFields.map(function (c) { return { key: c.label, value: c.value, hidden: c.hidden }; }) : [],
        tags: e ? (S.tags[e.uid] || []).slice() : [],
        newTag: '',
        tagMsg: null,
        pwVisible: !S.hidePasswords,
        pop: false,
        gen: { value: 16, length: 16, upper: true, lower: true, nums: true, syms: true }
      };
      f.orig = JSON.stringify(formSnapshot(f));
      S.form = f;
    }

    function formSnapshot(f) {
      return { t: f.title, u: f.username, p: f.password, w: f.website, n: f.notes, c: f.category, fv: f.favorite, cf: f.customFields.map(function (c) { return [c.key, c.value]; }), tg: f.tags };
    }

    function formDirty(f) {
      if (f.mode === 'edit') return JSON.stringify(formSnapshot(f)) !== f.orig;
      return !!(f.title.trim() || f.username.trim() || f.password.trim() || f.website.trim() || f.notes.trim() || f.customFields.length);
    }

    function tooLong(limit) {
      return 'Max ' + limit + ' characters';
    }

    function formErrors(f) {
      var cfErr = f.customFields.map(function (c) {
        if (c.key.length > LIMITS.CF_KEY) return 'Key: ' + tooLong(LIMITS.CF_KEY);
        if (c.value.length > LIMITS.CF_VALUE) return 'Value: ' + tooLong(LIMITS.CF_VALUE);
        return null;
      });
      var errs = {
        title: f.title.length > LIMITS.TITLE ? tooLong(LIMITS.TITLE) : (!f.title.trim() ? 'Title is required' : null),
        username: f.username.length > LIMITS.USERNAME ? tooLong(LIMITS.USERNAME) : null,
        password: f.password.length > LIMITS.PASSWORD ? tooLong(LIMITS.PASSWORD) : null,
        website: f.website.length > LIMITS.WEBSITE ? tooLong(LIMITS.WEBSITE) : null,
        notes: f.notes.length > LIMITS.NOTES ? tooLong(LIMITS.NOTES) : null,
        cf: cfErr,
        tag: f.tags.some(function (t) { return t.length > LIMITS.TAG; }) ? tooLong(LIMITS.TAG) : null
      };
      errs.any = !!(errs.title || errs.username || errs.password || errs.website || errs.notes || errs.tag || cfErr.some(Boolean) || f.customFields.length > LIMITS.MAX_CF);
      return errs;
    }

    function textField(o) {
      var id = uidBase + '-f-' + o.key;
      var errId = id + '-err';
      return '<div class="vpm-field"><label class="vpm-field-label" for="' + id + '">' + esc(o.label) + '</label>' +
        '<div class="vpm-tf' + (o.error ? ' vpm-tf--error' : '') + '" data-tf="' + o.key + '">' + icon(o.icon, 'vpm-tf-lead') +
          '<input id="' + id + '" class="vpm-tf-input" type="' + (o.type || 'text') + '" data-in="form" data-key="' + o.key + '" data-fk="f-' + o.key + '" value="' + esc(o.value) + '" placeholder="' + esc(o.placeholder) + '"' +
          ' aria-describedby="' + errId + '" aria-invalid="' + (o.error ? 'true' : 'false') + '" autocomplete="off" spellcheck="false">' +
          (o.trail ? '<span class="vpm-tf-trail">' + o.trail + '</span>' : '') +
        '</div><div class="vpm-field-error" id="' + errId + '" data-err="' + o.key + '"' + (o.error ? '' : ' hidden') + '>' + esc(o.error || '') + '</div></div>';
    }

    function renderGenPop(f) {
      var g = f.gen;
      var chips = [['upper', 'A', 'Include uppercase letters'], ['lower', 'a', 'Include lowercase letters'], ['nums', '1', 'Include numbers'], ['syms', '@', 'Include symbols']];
      return '<div class="vpm-genpop" data-genpop role="group" aria-label="Generate">' +
        '<div class="vpm-genpop-head"><span data-genpop-len>Length: ' + g.length + '</span>' +
          iconBtn('genpop-regen', 'refresh', 'Regenerate password', { fk: 'genpop-regen' }) + '</div>' +
        slider({ key: 'genpop-length', min: 8, max: 32, steps: 24, value: g.value, text: g.length, label: 'Length', trackWidth: 288 }) +
        '<div class="vpm-genpop-chips">' + chips.map(function (c) {
          return '<button type="button" class="vpm-genpop-chip vpm-ripple" data-act="genpop-chip" data-key="' + c[0] + '" aria-pressed="' + (g[c[0]] ? 'true' : 'false') + '" aria-label="' + c[2] + '" data-fk="genpop-' + c[0] + '">' + c[1] + '</button>';
        }).join('') + '</div></div>';
    }

    function renderForm() {
      var f = S.form;
      if (!f) return '';
      var errs = formErrors(f);
      var editing = f.mode === 'edit';
      var categories = [['Login', 'login'], ['Card', 'creditCard'], ['Secure Note', 'notes']];
      var tagError = f.tagMsg || errs.tag;

      var html = '<div class="vpm-page">' +
        '<header class="vpm-header vpm-header--split">' + iconBtn('form-close', 'close', 'Close', { fk: 'form-close' }) +
          '<span class="vpm-header-center">' + (editing ? 'Edit Entry' : 'New Entry') + '</span>' +
          '<button type="button" class="vpm-header-save vpm-ripple" data-act="form-save" data-fk="form-save"' + (errs.any ? ' disabled' : '') + '>' + icon('check') + '<span>Save</span></button>' +
        '</header>' +
        '<div class="vpm-scroll" data-scroll="' + esc(routeKey(top())) + '"><div class="vpm-form-body">' +
          '<div class="vpm-chips" role="group" aria-label="Category">' + categories.map(function (c) {
            return '<button type="button" class="vpm-chip vpm-ripple" data-act="category" data-value="' + c[0] + '" aria-pressed="' + (f.category === c[0] ? 'true' : 'false') + '" data-fk="cat-' + c[1] + '">' + icon(c[1]) + '<span>' + c[0] + '</span></button>';
          }).join('') + '</div>' +

          '<section class="vpm-form-card">' +
            textField({ key: 'title', label: 'Title', icon: 'title', value: f.title, placeholder: 'e.g. My Bank', error: errs.title }) +
            textField({ key: 'website', label: 'Website (URI)', icon: 'language', value: f.website, placeholder: 'https://', error: errs.website }) +
          '</section>' +

          '<section class="vpm-form-card">' +
            textField({ key: 'username', label: 'Username / Email', icon: 'person', value: f.username, placeholder: 'Username', error: errs.username,
              trail: iconBtn('copy-form', 'contentCopy', 'Copy', { fk: 'copy-form-username' }) }) +
            '<div class="vpm-pw-wrap">' +
              textField({ key: 'password', label: 'Password', icon: 'vpnKey', value: f.password, placeholder: 'Password', type: f.pwVisible ? 'text' : 'password', error: errs.password,
                trail: iconBtn('pw-eye', f.pwVisible ? 'visibilityOff' : 'visibility', 'Toggle password visibility', { fk: 'pw-eye', attrs: 'aria-pressed="' + (f.pwVisible ? 'true' : 'false') + '"' }) +
                  iconBtn('gen-pop', 'password', 'Generate', { cls: 'vpm-gen-trigger', fk: 'gen-pop', attrs: 'aria-expanded="' + (f.pop ? 'true' : 'false') + '"' }) }) +
              (f.pop ? '<div class="vpm-genpop-anchor' + (errs.password ? ' vpm-genpop-anchor--error' : '') + '">' + renderGenPop(f) + '</div>' : '') +
            '</div>' +
          '</section>' +

          '<section class="vpm-form-card">' +
            '<div class="vpm-fav-switch"><span class="vpm-fav-switch-label">' + icon(f.favorite ? 'star' : 'starBorder') + '<span id="' + uidBase + '-fav">Favorite</span></span>' +
              switchEl('formFavorite', f.favorite, uidBase + '-fav', true) + '</div>' +
            '<div class="vpm-divider"></div>' +
            '<div class="vpm-field"><label class="vpm-field-label" for="' + uidBase + '-notes">Secure Notes</label>' +
              '<div class="vpm-tf vpm-tf-area' + (errs.notes ? ' vpm-tf--error' : '') + '" data-tf="notes"><textarea id="' + uidBase + '-notes" data-in="form" data-key="notes" data-fk="f-notes" placeholder="Add any extra details, recovery codes, or hints here…" spellcheck="false" rows="3">' + esc(f.notes) + '</textarea></div>' +
              '<div class="vpm-field-error" data-err="notes"' + (errs.notes ? '' : ' hidden') + '>' + esc(errs.notes || '') + '</div></div>' +
            '<div class="vpm-divider"></div>' +
            '<div class="vpm-field"><label class="vpm-field-label" for="' + uidBase + '-tag">Tags</label>' +
              (f.tags.length ? '<div class="vpm-tags">' + f.tags.map(function (t, i) {
                return '<button type="button" class="vpm-tag vpm-ripple" data-act="tag-remove" data-index="' + i + '" aria-label="Remove tag ' + esc(t) + '" data-fk="tag-' + i + '"><span>' + esc(t) + '</span>' + icon('close') + '</button>';
              }).join('') + '</div>' : '') +
              '<div class="vpm-tag-row"><div class="vpm-tf"><input id="' + uidBase + '-tag" class="vpm-tf-input" type="text" data-in="tag" data-fk="f-tag" value="' + esc(f.newTag) + '" placeholder="Add tag" autocomplete="off" spellcheck="false" enterkeyhint="done"></div>' +
                iconBtn('tag-add', 'add', 'Add tag', { cls: 'vpm-tag-add', fk: 'tag-add' }) + '</div>' +
              (tagError ? '<div class="vpm-field-error">' + esc(tagError) + '</div>' : '') +
            '</div>';

      if (f.customFields.length) {
        html += '<div class="vpm-divider"></div>' + f.customFields.map(function (c, i) {
          return '<div class="vpm-field"><div class="vpm-cf-row">' +
            '<div class="vpm-tf vpm-cf-key' + (c.key.length > LIMITS.CF_KEY ? ' vpm-tf--error' : '') + '"><input class="vpm-tf-input" type="text" data-in="cf" data-index="' + i + '" data-part="key" data-fk="cf-key-' + i + '" value="' + esc(c.key) + '" placeholder="Key" aria-label="Custom field ' + (i + 1) + ' key" autocomplete="off" spellcheck="false"></div>' +
            '<div class="vpm-tf vpm-cf-value' + (c.value.length > LIMITS.CF_VALUE ? ' vpm-tf--error' : '') + '"><input class="vpm-tf-input" type="text" data-in="cf" data-index="' + i + '" data-part="value" data-fk="cf-value-' + i + '" value="' + esc(c.value) + '" placeholder="Value" aria-label="Custom field ' + (i + 1) + ' value" autocomplete="off" spellcheck="false"></div>' +
            iconBtn('cf-remove', 'removeCircleOutline', 'Remove', { cls: 'vpm-cf-remove', fk: 'cf-remove-' + i, attrs: 'data-index="' + i + '"' }) +
            '</div>' + (errs.cf[i] ? '<div class="vpm-field-error">' + esc(errs.cf[i]) + '</div>' : '') + '</div>';
        }).join('');
      }

      var cfFull = f.customFields.length >= LIMITS.MAX_CF;
      html += '<button type="button" class="vpm-add-cf vpm-ripple" data-act="cf-add" data-fk="cf-add"' + (cfFull ? ' disabled' : '') + '>' + icon('addCircleOutline') + '<span>Add Custom Field</span></button>' +
        (cfFull ? '<div class="vpm-field-error">Max ' + LIMITS.MAX_CF + ' custom fields</div>' : '') +
        '</section>';

      if (editing) {
        html += '<button type="button" class="vpm-textbtn vpm-textbtn--error vpm-form-delete vpm-ripple" data-act="form-delete" aria-haspopup="dialog" data-fk="form-delete">' + icon('delete') + '<span>Delete Entry</span></button>';
      }
      html += '</div></div></div>';
      return html;
    }

    // Field errors and the Save button change while typing; the rest of the form stays as it is.
    function updateFormValidity() {
      var f = S.form;
      if (!f) return;
      var errs = formErrors(f);
      ['title', 'username', 'password', 'website', 'notes'].forEach(function (k) {
        var box = appEl.querySelector('[data-tf="' + k + '"]');
        var msg = appEl.querySelector('[data-err="' + k + '"]');
        if (box) box.classList.toggle('vpm-tf--error', !!errs[k]);
        if (msg) {
          msg.hidden = !errs[k];
          msg.textContent = errs[k] || '';
        }
        var input = box && box.querySelector('input, textarea');
        if (input) input.setAttribute('aria-invalid', errs[k] ? 'true' : 'false');
      });
      var save = appEl.querySelector('[data-act="form-save"]');
      if (save) save.disabled = errs.any;
      var anchor = appEl.querySelector('.vpm-genpop-anchor');
      if (anchor) anchor.classList.toggle('vpm-genpop-anchor--error', !!errs.password);
    }

    function saveForm() {
      var f = S.form;
      if (!f || formErrors(f).any) return;
      var now = nowIso();
      var fields = {
        title: f.title.trim() || 'Untitled',
        username: f.username.trim(),
        password: f.password,
        website: f.website.trim(),
        notes: f.notes,
        category: f.category.trim() ? f.category : 'Personal',
        favorite: f.favorite,
        customFields: f.customFields.map(function (c) { return { label: c.key, value: c.value, hidden: c.hidden !== false }; })
      };
      var e = f.mode === 'edit' ? findEntry(f.uid) : null;
      if (e) {
        Object.keys(fields).forEach(function (k) { e[k] = fields[k]; });
        e.updatedAt = now;
        e.deletedAt = null;
      } else {
        e = normEntry(fields);
        e.uid = newUid();
        e.createdAt = now;
        e.updatedAt = now;
        e.deletedAt = null;
        entries.push(e);
      }
      if (f.tags.length) S.tags[e.uid] = f.tags.slice(); else delete S.tags[e.uid];
      S.form = null;
      var gone = S.stack.pop();
      delete scrollMemory[routeKey(gone)];
      enterRoute(top());
      render();
      emitChange();
    }

    function genApply() {
      var f = S.form;
      f.password = generateGuaranteed(f.gen.length, f.gen);
      f.pwVisible = true;
    }

    function updatePasswordField() {
      var f = S.form;
      var input = appEl.querySelector('[data-key="password"]');
      if (input) {
        input.value = f.password;
        input.type = f.pwVisible ? 'text' : 'password';
      }
      var eye = appEl.querySelector('[data-act="pw-eye"]');
      if (eye) {
        eye.innerHTML = icon(f.pwVisible ? 'visibilityOff' : 'visibility');
        eye.setAttribute('aria-pressed', f.pwVisible ? 'true' : 'false');
      }
      var len = appEl.querySelector('[data-genpop-len]');
      if (len) len.textContent = 'Length: ' + f.gen.length;
      updateFormValidity();
    }

    /* ---------- recycle bin, sync */

    function renderRecycle() {
      var list = binEntries();
      var html = '<div class="vpm-page vpm-page--inset-bottom">' + header({ title: 'Recycle Bin', icon: 'deleteOutline', error: true });
      if (!list.length) {
        html += '<div class="vpm-bin-empty">' + icon('deleteSweep') + '<h3>Recycle Bin is Empty</h3><p>Deleted items will appear here for 7 days before being permanently removed.</p></div>';
      } else {
        html += '<div class="vpm-scroll" data-scroll="recycle"><div class="vpm-bin-list">' + list.map(function (e) {
          var deleted = timeOf(e.deletedAt);
          var days = deleted ? Math.max(0, Math.floor((deleted + RECYCLE_DAYS * DAY_MS - Date.now()) / DAY_MS)) : 0;
          return '<div class="vpm-bin-item"><div class="vpm-bin-text"><span class="vpm-bin-title">' + esc(e.title) + '</span>' +
            '<span class="vpm-bin-date">Deleted: ' + (deleted ? fmtDeleted(deleted) : 'Unknown') + '</span>' +
            '<span class="vpm-bin-days' + (days <= 1 ? ' vpm-bin-days--soon' : '') + '">' + days + ' days remaining</span></div>' +
            '<div class="vpm-bin-actions">' +
              iconBtn('restore', 'restore', 'Restore', { cls: 'vpm-bin-restore', fk: 'restore-' + e.uid, attrs: 'data-uid="' + esc(e.uid) + '"' }) +
              iconBtn('purge', 'deleteForever', 'Delete Permanently', { cls: 'vpm-bin-delete', fk: 'purge-' + e.uid, attrs: 'data-uid="' + esc(e.uid) + '" aria-haspopup="dialog"' }) +
            '</div></div>';
        }).join('') + '</div></div>';
      }
      return html + '</div>';
    }

    function renderSync() {
      var html = '<div class="vpm-page vpm-page--inset-bottom">' +
        topbar({ title: 'Device Sync', back: true, solid: true, actions: iconBtn('noop', 'refresh', 'Refresh devices', { fk: 'sync-refresh' }) }) +
        '<div class="vpm-pair-card">' + icon('qrCodeScanner') +
          '<div class="vpm-pair-text"><span class="vpm-pair-title">Pair New Device</span><span class="vpm-pair-sub">Scan the QR code shown on VaultPass Desktop</span></div>' +
          '<button type="button" class="vpm-btn vpm-ripple" data-act="scan-qr" data-fk="scan-qr">Scan QR</button></div>' +
        '<h2 class="vpm-paired-label">PAIRED DEVICES</h2>';
      if (!S.paired) {
        html += '<div class="vpm-no-devices">' + icon('computer') + '<h3>No Paired Devices</h3><p>Tap \'Scan QR\' above to pair with VaultPass on your computer over the local network.</p></div>';
      } else {
        html += '<div class="vpm-scroll" data-scroll="sync"><div class="vpm-device-list"><div class="vpm-device">' +
          '<div class="vpm-device-head">' + icon('computer') +
            '<div class="vpm-device-text"><span class="vpm-device-name">' + esc(peerName) + '</span><span class="vpm-device-meta">IP: ' + SAMPLE_PEER_IP + ':' + SYNC_PORT + '</span></div>' +
            '<span class="vpm-online">Online</span></div>' +
          '<p class="vpm-device-last">' + (S.lastSync ? 'Last sync: ' + fmtLastSync(S.lastSync) : 'Never synced') + '</p>' +
          '<div class="vpm-device-actions">' + iconBtn('forget', 'deleteOutline', 'Unpair device', { fk: 'forget', attrs: 'aria-haspopup="dialog"' }) +
            '<button type="button" class="vpm-btn vpm-btn--tonal vpm-ripple" data-act="sync-now" data-fk="sync-now">' + icon('sync') + '<span>Sync Now</span></button></div>' +
          '</div></div></div>';
      }
      return html + '</div>';
    }

    /* ------------------------------------------------------------------ dialogs and sheets */

    function dialogShell(o) {
      var titleId = uidBase + '-dlg-title';
      var textId = uidBase + '-dlg-text';
      return '<div class="vpm-overlay"><div class="vpm-scrim" data-act="scrim"></div>' +
        '<div class="vpm-dialog" role="' + (o.alert ? 'alertdialog' : 'dialog') + '" aria-modal="true" aria-labelledby="' + titleId + '"' + (o.text ? ' aria-describedby="' + textId + '"' : '') + '>' +
        (o.icon ? icon(o.icon, 'vpm-dialog-icon') : '') +
        '<h2 class="vpm-dialog-title" id="' + titleId + '">' + esc(o.title) + '</h2>' +
        (o.text ? '<p class="vpm-dialog-text' + (o.tight ? ' vpm-dialog-text--tight' : '') + '" id="' + textId + '">' + esc(o.text) + '</p>' : '') +
        (o.body || '') +
        '<div class="vpm-dialog-actions">' + o.actions + '</div></div></div>';
    }

    function textBtn(act, label, o) {
      o = o || {};
      return '<button type="button" class="' + (o.filled ? 'vpm-btn' : 'vpm-textbtn') + (o.error ? ' vpm-textbtn--error' : '') + ' vpm-ripple" data-act="' + act + '" data-fk="dlg-' + act + '"' + (o.autofocus ? ' data-autofocus' : '') + (o.disabled ? ' disabled' : '') + '>' + esc(label) + '</button>';
    }

    // BackupPasswordDialogs.kt ExportPasswordDialog: Export Vault only for a long enough password typed twice
    function exportState() {
      var longEnough = S.exportPassword.length >= EXPORT_PASSWORD_MIN_LENGTH;
      var matches = S.exportPassword === S.exportConfirmation;
      return {
        canExport: longEnough && matches,
        errors: {
          'export-password': S.exportPassword && !longEnough ? 'At least ' + EXPORT_PASSWORD_MIN_LENGTH + ' characters' : '',
          'export-confirmation': S.exportConfirmation && !matches ? 'The passwords don\'t match' : ''
        }
      };
    }

    // BackupPasswordField: a masked OutlinedTextField with a show/hide button and its error underneath
    function backupField(kind, label, value, error, first) {
      var id = uidBase + '-' + kind;
      var shown = S.exportPasswordVisible;
      return '<div class="vpm-otf-wrap"><div class="vpm-otf' + (error ? ' vpm-otf--error' : '') + '" data-otf="' + kind + '">' +
        '<label class="vpm-otf-label" for="' + id + '">' + esc(label) + '</label>' +
        '<input id="' + id + '" type="' + (shown ? 'text' : 'password') + '" data-in="' + kind + '" data-fk="' + kind + '" value="' + esc(value) + '"' +
          ' autocomplete="off" spellcheck="false" enterkeyhint="' + (first ? 'next' : 'done') + '" aria-invalid="' + (error ? 'true' : 'false') + '" aria-describedby="' + id + '-err"' + (first ? ' data-autofocus' : '') + '>' +
        iconBtn('export-eye', shown ? 'visibilityOff' : 'visibility', shown ? 'Hide password' : 'Show password', { fk: kind + '-eye' }) + '</div>' +
        '<p class="vpm-otf-error" id="' + id + '-err" data-otf-err="' + kind + '"' + (error ? '' : ' hidden') + '>' + esc(error) + '</p></div>';
    }

    // Typing updates the errors and the button in place, so the caret stays where it is
    function updateExportView() {
      var ex = exportState();
      Object.keys(ex.errors).forEach(function (kind) {
        var err = ex.errors[kind];
        var box = overlayEl.querySelector('[data-otf="' + kind + '"]');
        var msg = overlayEl.querySelector('[data-otf-err="' + kind + '"]');
        if (box) {
          box.classList.toggle('vpm-otf--error', !!err);
          var input = box.querySelector('input');
          if (input) input.setAttribute('aria-invalid', err ? 'true' : 'false');
        }
        if (msg) {
          msg.hidden = !err;
          msg.textContent = err;
        }
      });
      var go = overlayEl.querySelector('[data-act="export-confirm"]');
      if (go) go.disabled = !ex.canExport;
    }

    function choiceList(act, options, selected) {
      return '<div class="vpm-dialog-text vpm-dialog-text--plain" role="radiogroup">' + options.map(function (o, i) {
        var on = String(o[0]) === String(selected);
        return '<button type="button" role="radio" class="vpm-choice' + (on ? ' vpm-choice--on' : '') + ' vpm-ripple" aria-checked="' + (on ? 'true' : 'false') + '" data-act="' + act + '" data-value="' + esc(o[0]) + '" data-fk="choice-' + i + '"' + (on ? ' data-autofocus' : '') + '>' +
          '<span class="vpm-radio' + (on ? ' vpm-radio--on' : '') + '" aria-hidden="true"></span><span>' + esc(o[1]) + '</span></button>';
      }).join('') + '</div>';
    }

    function renderDialog() {
      var d = S.dialog;
      if (!d) return '';
      switch (d.type) {
        case 'autolock':
          return dialogShell({ title: 'Choose Auto Lock Time', body: choiceList('autolock-pick', AUTO_LOCK, S.autoLock), actions: textBtn('dialog-close', 'Cancel') });
        case 'export':
          return dialogShell({ title: 'Choose Export Format', body: choiceList('export-pick', EXPORT_FORMATS, S.exportFormat), actions: textBtn('dialog-close', 'Cancel') });
        case 'export-password': {
          var ex = exportState();
          return dialogShell({
            title: 'Secure Export',
            tight: true,
            text: 'Enter a password to encrypt this backup. You will need this password to import it later.',
            body: backupField('export-password', 'Backup Password', S.exportPassword, ex.errors['export-password'], true) +
              backupField('export-confirmation', 'Confirm Backup Password', S.exportConfirmation, ex.errors['export-confirmation'], false),
            actions: textBtn('dialog-close', 'Cancel') + textBtn('export-confirm', 'Export Vault', { disabled: !ex.canExport })
          });
        }
        case 'txt-inexact':
          return dialogShell({
            title: 'Not every entry fits in a TXT file',
            text: d.count + (d.count === 1 ? ' entry can\'t' : ' entries can\'t') + ' be saved exactly as TXT (for example a line break in a password, or a comma in a tag). For a backup, use the encrypted .vpex export.',
            actions: textBtn('dialog-close', 'Cancel') + textBtn('txt-anyway', 'Export TXT Anyway')
          });
        case 'delete-entry':
          return dialogShell({ title: 'Delete Entry', text: 'Are you sure you want to delete this password entry? It moves to the Recycle Bin, where you can restore it for 7 days.', alert: true,
            actions: textBtn('dialog-close', 'Cancel', { autofocus: true }) + textBtn('confirm-delete', 'Delete', { error: true }) });
        case 'discard':
          return dialogShell({ title: 'Discard Changes?', text: 'You have unsaved changes. Are you sure you want to go back?', alert: true,
            actions: textBtn('dialog-close', 'Cancel', { autofocus: true }) + textBtn('discard', 'Discard', { error: true }) });
        case 'purge': {
          var e = findEntry(d.uid);
          return dialogShell({ icon: 'warning', title: 'Delete Permanently?', alert: true,
            text: 'Are you sure you want to permanently delete ‘' + (e ? e.title : '') + '’? This action cannot be undone.',
            actions: textBtn('dialog-close', 'Cancel', { autofocus: true }) + textBtn('confirm-purge', 'Delete Permanently', { error: true }) });
        }
        case 'forget':
          return dialogShell({ title: 'Forget Device', alert: true,
            text: 'Are you sure you want to unpair from ' + peerName + '? You will need to scan a new QR code to sync with this device again.',
            actions: textBtn('dialog-close', 'Cancel', { autofocus: true }) + textBtn('confirm-forget', 'Forget', { filled: true }) });
        default:
          return '';
      }
    }

    // The system BiometricPrompt sheet (BiometricPromptManager.kt / SettingsScreen.kt texts)
    function renderSheet() {
      var s = S.sheet;
      if (!s) return '';
      var titleId = uidBase + '-sheet-title';
      return '<div class="vpm-sheet-wrap"><div class="vpm-scrim" data-act="sheet-cancel"></div>' +
        '<div class="vpm-sheet' + (s.scanning ? ' vpm-sheet--scanning' : '') + '" role="dialog" aria-modal="true" aria-labelledby="' + titleId + '">' +
          '<h2 class="vpm-sheet-title" id="' + titleId + '">' + esc(s.title) + '</h2>' +
          '<p class="vpm-sheet-sub">' + esc(s.subtitle) + '</p>' +
          '<div class="vpm-sheet-sensor" aria-hidden="true">' + icon('fingerprint') + '</div>' +
          '<p class="vpm-sheet-status">Touch the fingerprint sensor</p>' +
          '<div class="vpm-sheet-actions">' + textBtn('sheet-cancel', s.negative, { autofocus: true }) + '</div>' +
        '</div></div>';
    }

    function openSheet(kind) {
      var enabling = kind === 'enable';
      if (reduceMotion) {
        finishBiometric(kind);
        return;
      }
      S.sheet = {
        kind: kind,
        title: enabling ? 'Enable Biometric Unlock' : 'Unlock VaultPass',
        subtitle: enabling ? 'Authenticate to secure your vault' : 'Log in using your biometric credential',
        negative: enabling ? 'Cancel' : 'Use Master Password',
        scanning: true
      };
      if (enabling) S.biometricEnabling = true;
      dialogOpenerFk = focusKeyOf(document.activeElement);
      pendingFocus = 'dlg-sheet-cancel';
      render();
      S.sheet.timer = later(function () { finishBiometric(kind); }, 1100);
    }

    function finishBiometric(kind) {
      if (S.sheet && S.sheet.timer) cancel(S.sheet.timer);
      S.sheet = null;
      if (kind === 'enable') {
        S.biometricEnabling = false;
        S.biometric = true;
        pendingFocus = dialogOpenerFk;
        dialogOpenerFk = null;
        render();
      } else {
        dialogOpenerFk = null;
        unlockVault();
      }
    }

    function cancelSheet() {
      if (!S.sheet) return;
      if (S.sheet.timer) cancel(S.sheet.timer);
      if (S.sheet.kind === 'enable') S.biometricEnabling = false;
      S.sheet = null;
      pendingFocus = dialogOpenerFk || (S.locked ? 'lock-input' : null);
      dialogOpenerFk = null;
      render();
    }

    function openDialog(d) {
      dialogOpenerFk = focusKeyOf(document.activeElement);
      S.dialog = d;
      S.menu = null;
      pendingFocus = '__dialog__';
      render();
    }

    function closeDialog(keepFocus) {
      if (!S.dialog) return;
      S.dialog = null;
      if (!keepFocus) pendingFocus = dialogOpenerFk;
      dialogOpenerFk = null;
      render();
    }

    /* ------------------------------------------------------------------ render */

    function focusKeyOf(el) {
      if (!el || el === root || !root.contains(el)) return null;
      var k = el.getAttribute && el.getAttribute('data-fk');
      if (k) return k;
      var holder = el.closest && el.closest('[data-fk]');
      return holder && root.contains(holder) ? holder.getAttribute('data-fk') : null;
    }

    function screenHtml() {
      if (S.locked) return renderLock();
      switch (top().name) {
        case 'dashboard': return renderDashboard();
        case 'security': return renderSecurity();
        case 'generator': return renderGenerator();
        case 'settings': return renderSettings();
        case 'details': return renderDetails();
        case 'add':
        case 'edit': return renderForm();
        case 'recycle': return renderRecycle();
        case 'weak': return renderWeak();
        case 'reused': return renderReused();
        case 'missing': return renderMissing();
        case 'sync': return renderSync();
        default: return renderDashboard();
      }
    }

    function resolvedDark() {
      if (S.themeMode === 1) return false;
      if (S.themeMode === 2) return true;
      return !(window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches);
    }

    function render() {
      if (destroyed) return;
      // A screen whose entry is gone (deleted elsewhere, or by a sync) closes, like popBackStack().
      while (S.stack.length > 1 && (top().name === 'details' || top().name === 'edit') && !liveEntry(top().uid)) {
        var gone = S.stack.pop();
        delete scrollMemory[routeKey(gone)];
        if (gone.name === 'edit') S.form = null;
        // Its Delete Entry (or the editor's Discard Changes) dialog leaves with it.
        if (S.dialog && (S.dialog.type === 'delete-entry' || (gone.name === 'edit' && S.dialog.type === 'discard'))) {
          S.dialog = null;
          dialogOpenerFk = null;
        }
      }
      if (top().name === 'generator' && !S.gen) resetGenerator();

      var activeEl = document.activeElement;
      var hadFocus = !!activeEl && activeEl !== root && root.contains(activeEl);
      var fk = hadFocus ? focusKeyOf(activeEl) : null;
      var caret = null;
      if (hadFocus && activeEl.tagName !== 'BUTTON' && typeof activeEl.selectionStart === 'number' && activeEl.type !== 'range') {
        try { caret = [activeEl.selectionStart, activeEl.selectionEnd]; } catch (err) { caret = null; }
      }

      saveScroll();
      root.setAttribute('data-theme', resolvedDark() ? 'dark' : 'light');
      root.setAttribute('data-accent', S.accent);
      appEl.innerHTML = screenHtml();
      // The same dialog or sheet drawn again (e.g. the eye in Secure Export, or a sync arriving) keeps
      // still; only a newly opened one plays its entry animation.
      var overlayId = (S.dialog ? 'd:' + S.dialog.type + ':' + (S.dialog.uid || '') : '') + (S.sheet ? '|s:' + S.sheet.kind : '');
      if (overlayId && overlayId === lastOverlayId) overlayEl.setAttribute('data-static', '');
      else overlayEl.removeAttribute('data-static');
      lastOverlayId = overlayId;
      overlayEl.innerHTML = renderDialog() + renderSheet();
      var modal = !!(S.dialog || S.sheet);
      if (modal) appEl.setAttribute('inert', ''); else appEl.removeAttribute('inert');
      applySizes(appEl);
      appEl.querySelectorAll('[data-slider]').forEach(paintSlider);
      restoreScroll();
      if (revealMenu) {
        revealMenu = false;
        revealInScroll(appEl.querySelector('.vpm-menu'));
      }

      var want = pendingFocus;
      pendingFocus = null;
      var target = null;
      if (want === '__dialog__') {
        target = overlayEl.querySelector('[data-autofocus]') || firstFocusable(overlayEl);
      } else if (want) {
        target = root.querySelector('[data-fk="' + cssEscape(want) + '"]');
        if (!target && modal) target = overlayEl.querySelector('[data-autofocus]') || firstFocusable(overlayEl);
      } else if (hadFocus) {
        if (modal) target = overlayEl.querySelector('[data-fk="' + cssEscape(fk || '') + '"]') || overlayEl.querySelector('[data-autofocus]') || firstFocusable(overlayEl);
        else target = (fk && appEl.querySelector('[data-fk="' + cssEscape(fk) + '"]')) || appEl.querySelector('[data-fk="back"]') || firstFocusable(appEl);
      }
      if (target) {
        restoringFocus = true;
        try {
          target.focus({ preventScroll: true });
        } finally {
          restoringFocus = false;
        }
        if (caret && fk && target.getAttribute('data-fk') === fk && typeof target.setSelectionRange === 'function') {
          try { target.setSelectionRange(caret[0], caret[1]); } catch (err) { /* not a text field */ }
        }
      }
    }

    // ExposedDropdownMenu is a popup that always fits on screen; here the menu lives in the scrolling
    // content, so the content scrolls just enough to show every option above the navigation bar.
    // offsetTop is layout-based, so the site's scale() transform does not affect it.
    function revealInScroll(el) {
      if (!el) return;
      var sc = el.closest('[data-scroll]');
      if (!sc) return;
      var y = 0;
      var n = el;
      while (n && n !== sc) {
        y += n.offsetTop;
        n = n.offsetParent;
      }
      if (n !== sc) return;
      var bottom = y + el.offsetHeight + 8;
      if (bottom > sc.scrollTop + sc.clientHeight) sc.scrollTop = bottom - sc.clientHeight;
    }

    // Sizes that depend on data are set through the CSSOM, so the markup has no style attributes
    // (a page whose CSP has no 'unsafe-inline' for styles still renders the demo).
    function applySizes(scope) {
      scope.querySelectorAll('[data-width]').forEach(function (el) { el.style.width = el.getAttribute('data-width'); });
      scope.querySelectorAll('[data-left]').forEach(function (el) { el.style.left = el.getAttribute('data-left'); });
    }

    function cssEscape(v) {
      return window.CSS && CSS.escape ? CSS.escape(v) : String(v).replace(/["\\]/g, '\\$&');
    }

    function firstFocusable(container) {
      return container.querySelector('button:not([disabled]), input, textarea, a[href], [tabindex]:not([tabindex="-1"])');
    }

    function focusables(container) {
      return Array.prototype.filter.call(
        container.querySelectorAll('button:not([disabled]), input, textarea, a[href], [tabindex]:not([tabindex="-1"])'),
        function (el) { return !el.hidden && el.offsetParent !== null; }
      );
    }

    // Scroll positions survive while their screen is on the back stack (tab roots always keep theirs,
    // like navigation's saveState); a screen that was popped starts at the top next time.
    var TAB_KEYS = { dashboard: 1, search: 1, security: 1, generator: 1, settings: 1 };

    function saveScroll() {
      var sc = appEl.querySelector('[data-scroll]');
      if (!sc) return;
      var key = sc.getAttribute('data-scroll');
      var alive = TAB_KEYS[key] || S.stack.some(function (r) { return routeKey(r) === key; });
      if (alive) scrollMemory[key] = sc.scrollTop;
    }

    function restoreScroll() {
      var sc = appEl.querySelector('[data-scroll]');
      if (!sc) return;
      var y = scrollMemory[sc.getAttribute('data-scroll')];
      if (y) sc.scrollTop = y;
    }

    /* ------------------------------------------------------------------ actions */

    function setQuery(q) {
      S.search.query = q;
    }

    function copyFromDetails(key) {
      var e = liveEntry(top().uid);
      if (!e) return;
      if (key === 'username') copyText('Username', e.username);
      else if (key === 'password') copyText('Password', e.password);
      else if (key.indexOf('cf') === 0) {
        var f = e.customFields[+key.substring(2)];
        if (f) copyText(f.label, f.value);
      }
    }

    // True when an entry actually moved to the recycle bin.
    function softDelete(uid) {
      var e = liveEntry(uid);
      if (!e) return false;
      e.deletedAt = nowIso();
      return true;
    }

    function requestClose() {
      if (S.form && formDirty(S.form)) openDialog({ type: 'discard' });
      else pop();
    }

    function launchExport(format) {
      S.dialog = null;
      dialogOpenerFk = null;
      pendingFocus = 'row-export';
      render();
      // SettingsScreen.kt: TXT_EXPORT_FILE_NAME for a readable copy, "vaultpass_backup.<format>" for backups
      var fileName = format === 'txt' ? 'vaultpass_export.txt' : 'vaultpass_backup.' + format;
      toast('Demo: the app now asks where to save ' + fileName, true);
    }

    // domain/exportimport/TxtVaultFormat.kt isExact, on the record VaultViewModel.toTxtRecord() builds
    // (category is left out when it is "Personal").
    function isBlankLine(line) {
      return !/\S/.test(line);
    }

    function isLabelAfterNotes(line) {
      var label = line.trim();
      return label === 'Category:' || label === 'Tags:' || (label.indexOf('Custom.') === 0 && label.charAt(label.length - 1) === ':');
    }

    function txtInexactCount() {
      return active().filter(function (e) {
        var tags = S.tags[e.uid] || [];
        var category = e.category === 'Personal' ? null : e.category;
        var single = [e.title, e.username, e.password, e.website].concat(category === null ? [] : [category], tags);
        e.customFields.forEach(function (c) { single.push(c.label, c.value); });
        if (isBlankLine(e.title)) return true;
        if (single.some(function (s) { return /[\r\n]/.test(s); })) return true;
        if (tags.some(function (t) { return !t || t.indexOf(',') !== -1 || t !== t.trim(); })) return true;
        if (category !== null && isBlankLine(category)) return true;
        if (e.notes.indexOf('\r') !== -1) return true;
        var lines = e.notes.split('\n');
        for (var i = 0; i < lines.length; i++) {
          // A blank line and then a label would end the notes early.
          if (isBlankLine(lines[i]) && i + 1 < lines.length && isLabelAfterNotes(lines[i + 1])) return true;
          // "---" and then (after blank lines) a Title line would end the entry.
          if (lines[i].trim() === '---') {
            var m = i + 1;
            while (m < lines.length && isBlankLine(lines[m])) m++;
            if (m < lines.length && lines[m].indexOf('Title:') === 0) return true;
          }
        }
        return false;
      }).length;
    }

    var actions = {
      tab: function (el) { tab(el.getAttribute('data-tab')); },
      go: function (el) { push({ name: el.getAttribute('data-route') }); },
      back: function () { pop(); },
      open: function (el) { push({ name: 'details', uid: el.getAttribute('data-uid') }); },
      lock: function () { lockVault(); },
      noop: function () {},

      'search-open': function () { S.search.active = true; pendingFocus = 'search-input'; render(); },
      'search-close': function () { setQuery(''); S.search.active = false; pendingFocus = 'dash-search'; render(); },
      'search-clear': function () { setQuery(''); pendingFocus = 'dash-search'; render(); },
      'clear-search': function () { setQuery(''); S.search.active = false; pendingFocus = 'dash-search'; render(); },
      'stat-total': function () { setQuery(''); S.search.active = false; render(); },

      reveal: function (el) { var k = el.getAttribute('data-key'); S.reveal[k] = !S.reveal[k]; render(); },
      copy: function (el) { copyFromDetails(el.getAttribute('data-key')); },
      edit: function () { push({ name: 'edit', uid: top().uid }); },
      // PasswordDetailsScreen.kt: Delete asks first; the star saves the favorite right away
      'delete-entry': function () { openDialog({ type: 'delete-entry' }); },
      'detail-star': function () {
        var e = liveEntry(top().uid);
        if (!e) return;
        e.favorite = !e.favorite;
        e.updatedAt = nowIso();
        render();
        emitChange();
      },
      'web-open': function () {
        var e = liveEntry(top().uid);
        var url = e && browserUrl(e.website);
        if (url) toast('Demo: the app opens ' + url + ' in your browser', true);
      },

      'gen-copy': function () { copyText('Password', S.gen.password); },
      'gen-regen': function () { S.gen.password = generateSimple(S.gen.length, S.gen); updateGeneratorView(); },

      toggle: function (el) {
        var key = el.getAttribute('data-key');
        var genKeys = { genUpper: 'upper', genLower: 'lower', genNums: 'nums', genSyms: 'syms' };
        if (genKeys[key]) {
          S.gen[genKeys[key]] = !S.gen[genKeys[key]];
          S.gen.password = generateSimple(S.gen.length, S.gen);
          el.setAttribute('aria-checked', S.gen[genKeys[key]] ? 'true' : 'false');
          updateGeneratorView();
          return;
        }
        if (key === 'formFavorite') {
          S.form.favorite = !S.form.favorite;
          render();
          return;
        }
        if (key === 'biometric') {
          if (S.biometric || S.biometricEnabling) { S.biometric = false; S.biometricEnabling = false; render(); }
          else openSheet('enable');
          return;
        }
        if (key === 'hidePasswords') S.hidePasswords = !S.hidePasswords;
        else if (key === 'disableScreenshots') S.disableScreenshots = !S.disableScreenshots;
        else if (key === 'checkUpdates') S.checkUpdates = !S.checkUpdates;
        render();
      },

      appearance: function () { S.appearanceOpen = !S.appearanceOpen; S.menu = null; render(); },
      menu: function (el) {
        var key = el.getAttribute('data-menu');
        S.menu = S.menu === key ? null : key;
        if (S.menu) {
          var sel = key === 'theme' ? String(S.themeMode) : S.accent;
          var opts = key === 'theme' ? THEME_OPTIONS.map(function (t, i) { return String(i); }) : ACCENTS.map(function (a) { return a.key; });
          pendingFocus = 'opt-' + key + '-' + Math.max(0, opts.indexOf(sel));
          revealMenu = true;
        }
        render();
      },
      'menu-pick': function (el) {
        var key = el.getAttribute('data-menu');
        var v = el.getAttribute('data-value');
        if (key === 'theme') S.themeMode = clamp(parseInt(v, 10) || 0, 0, 2);
        else S.accent = v;
        S.menu = null;
        pendingFocus = 'sel-' + key;
        render();
      },
      'autolock-open': function () { openDialog({ type: 'autolock' }); },
      'autolock-pick': function (el) { S.autoLock = parseInt(el.getAttribute('data-value'), 10); closeDialog(); },
      autofill: function () {
        if (S.autofillEnabled) toast('VaultPass is already your Autofill provider!');
      },
      'export-open': function () { openDialog({ type: 'export' }); },
      'export-pick': function (el) {
        var fmt = el.getAttribute('data-value');
        S.exportFormat = fmt;
        if (fmt === 'vpex') {
          S.exportPassword = '';
          S.exportConfirmation = '';
          S.exportPasswordVisible = false;
          S.dialog = { type: 'export-password' };
          pendingFocus = '__dialog__';
          render();
        } else if (fmt === 'txt') {
          var n = txtInexactCount();
          if (n > 0) {
            S.dialog = { type: 'txt-inexact', count: n };
            pendingFocus = '__dialog__';
            render();
          } else {
            launchExport('txt');
          }
        } else {
          launchExport(fmt);
        }
      },
      'txt-anyway': function () { launchExport('txt'); },
      'export-eye': function () { S.exportPasswordVisible = !S.exportPasswordVisible; render(); },
      'export-confirm': function () {
        if (!exportState().canExport) return;
        S.exportPassword = '';
        S.exportConfirmation = '';
        launchExport('vpex');
      },
      import: function () { toast('Demo: the app opens the Android file picker here', true); },
      'check-now': function () { toast('Demo: the app asks GitHub for the latest release here', true); },

      'dialog-close': function () {
        if (S.dialog && S.dialog.type === 'export-password') {
          S.exportPassword = '';
          S.exportConfirmation = '';
        }
        closeDialog();
      },
      scrim: function () { actions['dialog-close'](); },

      unlock: function () {
        if (!S.lock.password) {
          S.lock.error = 'Incorrect master password';
          pendingFocus = 'lock-input';
          render();
          announce(S.lock.error);
        } else {
          unlockVault();
        }
      },
      'lock-eye': function () { S.lock.visible = !S.lock.visible; render(); },
      biometric: function () { openSheet('unlock'); },
      'sheet-cancel': function () { cancelSheet(); },

      'form-close': function () { requestClose(); },
      'form-save': function () { saveForm(); },
      category: function (el) { S.form.category = el.getAttribute('data-value'); render(); },
      'copy-form': function () { copyText('Username', S.form.username); },
      'pw-eye': function () { S.form.pwVisible = !S.form.pwVisible; updatePasswordField(); },
      'gen-pop': function () {
        var f = S.form;
        f.pop = !f.pop;
        if (f.pop && !f.password) genApply();
        render();
      },
      'genpop-regen': function () { genApply(); updatePasswordField(); },
      'genpop-chip': function (el) {
        var f = S.form;
        var k = el.getAttribute('data-key');
        var count = ['upper', 'lower', 'nums', 'syms'].filter(function (x) { return f.gen[x]; }).length;
        if (!f.gen[k] || count > 1) {
          f.gen[k] = !f.gen[k];
          genApply();
          render();
        }
      },
      'tag-add': function () { addTag(); },
      'tag-remove': function (el) {
        var i = +el.getAttribute('data-index');
        var t = S.form.tags[i];
        S.form.tags = S.form.tags.filter(function (x) { return x !== t; });
        pendingFocus = 'f-tag';
        render();
      },
      'cf-add': function () {
        var f = S.form;
        if (f.customFields.length >= LIMITS.MAX_CF) return;
        f.customFields.push({ key: '', value: '', hidden: true });
        pendingFocus = 'cf-key-' + (f.customFields.length - 1);
        render();
      },
      'cf-remove': function (el) {
        S.form.customFields.splice(+el.getAttribute('data-index'), 1);
        pendingFocus = 'cf-add';
        render();
      },
      'form-delete': function () { openDialog({ type: 'delete-entry' }); },
      'confirm-delete': function () {
        // From the editor or the details screen. Either way the list comes back: render() closes
        // a details screen under the editor, as its entry is in the Recycle Bin now.
        var uid = S.form ? S.form.uid : top().uid;
        S.dialog = null;
        dialogOpenerFk = null;
        var changed = softDelete(uid);
        pop();
        if (changed) emitChange();
      },
      discard: function () {
        S.dialog = null;
        dialogOpenerFk = null;
        pop();
      },

      restore: function (el) {
        var e = findEntry(el.getAttribute('data-uid'));
        if (!e) return;
        e.deletedAt = null;
        e.updatedAt = nowIso();
        pendingFocus = 'back';
        render();
        emitChange();
      },
      purge: function (el) { openDialog({ type: 'purge', uid: el.getAttribute('data-uid') }); },
      'confirm-purge': function () {
        var uid = S.dialog && S.dialog.uid;
        entries = entries.filter(function (e) { return e.uid !== uid; });
        if (uid) purged[uid] = true;
        delete S.tags[uid];
        S.dialog = null;
        dialogOpenerFk = null;
        pendingFocus = 'back';
        render();
        emitChange();
      },

      'scan-qr': function () { toast('Demo: the app opens the camera to scan the QR code on VaultPass Desktop', true); },
      'sync-now': function () {
        var evt;
        try {
          evt = new CustomEvent('vaultpass-demo-sync-request', { bubbles: true, cancelable: true, detail: { device: 'android' } });
        } catch (err) {
          evt = null;
        }
        var handled = evt ? !root.dispatchEvent(evt) : false;
        if (!handled) toast('Demo: use Sync now on this page to sync with ' + peerName, true);
      },
      forget: function () { openDialog({ type: 'forget' }); },
      'confirm-forget': function () {
        S.paired = false;
        S.dialog = null;
        dialogOpenerFk = null;
        pendingFocus = 'scan-qr';
        render();
      }
    };

    function addTag() {
      var f = S.form;
      var t = f.newTag.trim();
      if (!t) return;
      if (t.length > LIMITS.TAG) f.tagMsg = tooLong(LIMITS.TAG);
      else if (f.tags.indexOf(t) !== -1) f.tagMsg = 'That tag is already added';
      else if (f.tags.length >= LIMITS.MAX_TAGS) f.tagMsg = 'Max ' + LIMITS.MAX_TAGS + ' tags';
      else {
        f.tags.push(t);
        f.newTag = '';
        f.tagMsg = null;
      }
      pendingFocus = 'f-tag';
      render();
    }

    /* ------------------------------------------------------------------ events */

    function onClick(ev) {
      var t = ev.target;
      if (!t || !t.closest) return;
      var el = t.closest('[data-act]');
      if (el && (!root.contains(el) || el.disabled || el.tagName === 'A')) el = null;
      // Menus and the generator popover close on a click anywhere else in the demo.
      var dismissed = false;
      if (S.menu && !t.closest('.vpm-select')) {
        S.menu = null;
        dismissed = true;
      }
      if (S.form && S.form.pop && !t.closest('[data-genpop]') && !t.closest('[data-act="gen-pop"]')) {
        S.form.pop = false;
        dismissed = true;
      }
      if (dismissed) render();
      if (!el) return;
      var fn = actions[el.getAttribute('data-act')];
      if (fn) {
        ev.preventDefault();
        fn(el);
      }
    }

    function onInput(ev) {
      var t = ev.target;
      var kind = t.getAttribute && t.getAttribute('data-in');
      if (!kind) return;
      switch (kind) {
        case 'search': {
          setQuery(t.value);
          var box = appEl.querySelector('[data-results]');
          if (box) box.innerHTML = renderSearchList(filtered(active(), S.search.query));
          break;
        }
        case 'lock':
          S.lock.password = t.value;
          if (S.lock.error) {
            S.lock.error = null;
            var msg = appEl.querySelector('.vpm-lock-msg');
            if (msg) {
              msg.textContent = 'Demo: any password unlocks';
              msg.classList.remove('vpm-lock-msg--error');
            }
          }
          break;
        case 'gen-length': {
          S.gen.length = clamp(parseInt(t.value, 10) || 18, 8, 64);
          S.gen.password = generateSimple(S.gen.length, S.gen);
          t.setAttribute('aria-valuetext', S.gen.length + ' characters');
          paintSlider(t.closest('[data-slider]'));
          updateGeneratorView();
          break;
        }
        case 'genpop-length': {
          // Stops are 0.96 apart (8f..32f, steps = 24); the app shows and uses genLength.toInt().
          var pos = parseFloat(t.value);
          S.form.gen.value = clamp(isFinite(pos) ? pos : 16, 8, 32);
          S.form.gen.length = Math.floor(S.form.gen.value + 1e-6);
          t.setAttribute('aria-valuetext', S.form.gen.length + ' characters');
          genApply();
          paintSlider(t.closest('[data-slider]'));
          updatePasswordField();
          break;
        }
        case 'form':
          S.form[t.getAttribute('data-key')] = t.value;
          updateFormValidity();
          break;
        case 'tag':
          S.form.newTag = t.value;
          break;
        case 'cf': {
          var c = S.form.customFields[+t.getAttribute('data-index')];
          if (c) c[t.getAttribute('data-part')] = t.value;
          var errs = formErrors(S.form);
          var save = appEl.querySelector('[data-act="form-save"]');
          if (save) save.disabled = errs.any;
          var box2 = t.closest('.vpm-tf');
          if (box2) box2.classList.toggle('vpm-tf--error', t.getAttribute('data-part') === 'key' ? t.value.length > LIMITS.CF_KEY : t.value.length > LIMITS.CF_VALUE);
          break;
        }
        case 'export-password':
          S.exportPassword = t.value;
          updateExportView();
          break;
        case 'export-confirmation':
          S.exportConfirmation = t.value;
          updateExportView();
          break;
        default:
          break;
      }
    }

    // Focus modality for the text-field ring: a click or tap clears it, Tab sets it, and so does focus
    // arriving from outside the demo without a click (Tab from the page). Focus that render() puts
    // back after a re-render leaves it as it was.
    function setKeyboardNav(on) {
      if (on === keyboardNav) return;
      keyboardNav = on;
      if (on) root.setAttribute('data-kbd', '');
      else root.removeAttribute('data-kbd');
    }

    function onPointerDown() {
      lastPointerDown = Date.now();
      setKeyboardNav(false);
    }

    function onFocusIn(ev) {
      if (restoringFocus) return;
      var from = ev.relatedTarget;
      if ((!from || !root.contains(from)) && Date.now() - lastPointerDown > 600) setKeyboardNav(true);
    }

    function onKeydown(ev) {
      var t = ev.target;
      if (ev.key === 'Tab') setKeyboardNav(true);
      if (ev.key === 'Escape') {
        if (S.sheet) { ev.preventDefault(); cancelSheet(); return; }
        if (S.dialog) { ev.preventDefault(); actions['dialog-close'](); return; }
        if (S.menu) { ev.preventDefault(); var m = S.menu; S.menu = null; pendingFocus = 'sel-' + m; render(); return; }
        if (S.form && S.form.pop) { ev.preventDefault(); S.form.pop = false; pendingFocus = 'gen-pop'; render(); return; }
        if (!S.locked && top().name === 'dashboard' && S.search.active) { ev.preventDefault(); actions['search-close'](); return; }
        return;
      }
      if (ev.key === 'Tab' && (S.dialog || S.sheet)) {
        var list = focusables(overlayEl);
        if (!list.length) return;
        var first = list[0];
        var last = list[list.length - 1];
        var cur = document.activeElement;
        if (!overlayEl.contains(cur)) { ev.preventDefault(); first.focus(); }
        else if (ev.shiftKey && cur === first) { ev.preventDefault(); last.focus(); }
        else if (!ev.shiftKey && cur === last) { ev.preventDefault(); first.focus(); }
        return;
      }
      if (S.menu && (ev.key === 'ArrowDown' || ev.key === 'ArrowUp')) {
        var items = Array.prototype.slice.call(appEl.querySelectorAll('.vpm-menu-item'));
        if (!items.length) return;
        ev.preventDefault();
        var idx = items.indexOf(document.activeElement);
        idx = ev.key === 'ArrowDown' ? (idx + 1) % items.length : (idx <= 0 ? items.length - 1 : idx - 1);
        items[idx].focus();
        return;
      }
      if (ev.key === 'Enter') {
        var kind = t.getAttribute && t.getAttribute('data-in');
        if (kind === 'lock') { ev.preventDefault(); actions.unlock(); }
        // DashboardScreen.kt onSearch: keep the query and its results, only hide the keyboard
        else if (kind === 'search') { ev.preventDefault(); t.blur(); }
        else if (kind === 'tag') { ev.preventDefault(); addTag(); }
        else if (kind === 'export-password') {
          ev.preventDefault();
          var next = overlayEl.querySelector('[data-in="export-confirmation"]');
          if (next) next.focus();
        }
        else if (kind === 'export-confirmation') { ev.preventDefault(); actions['export-confirm'](); }
        else if (kind === 'form' && t.getAttribute('data-key') === 'password') { ev.preventDefault(); saveForm(); }
      }
    }

    root.addEventListener('click', onClick);
    root.addEventListener('input', onInput);
    root.addEventListener('keydown', onKeydown);
    root.addEventListener('pointerdown', onPointerDown, true);
    root.addEventListener('focusin', onFocusIn);

    /* ------------------------------------------------------------------ start */

    var initial0 = str(options.initialScreen);
    if (options.locked === true || initial0 === 'lock') S.locked = true;
    var startStack = stackFor(initial0);
    if (startStack) S.stack = startStack;
    enterRoute(top());
    render();

    /* ------------------------------------------------------------------ API */

    var api = {
      getVault: function () {
        return JSON.parse(JSON.stringify(entries));
      },
      applySync: function (result) {
        if (destroyed || !result || !Array.isArray(result.vault)) return;
        // A purged entry that comes back only as the other device's recycle-bin copy stays purged. If it
        // comes back live, the other device still uses it and it returns (the site's merge has no
        // tombstones to delete it there too).
        entries = result.vault.filter(function (e) {
          if (!e || !purged[e.uid]) return true;
          if (e.deletedAt) return false;
          delete purged[e.uid];
          return true;
        }).map(normEntry);
        var keep = {};
        entries.forEach(function (e) { keep[e.uid] = true; });
        Object.keys(S.tags).forEach(function (uid) { if (!keep[uid]) delete S.tags[uid]; });
        S.paired = true;
        S.lastSync = Date.now();
        if (result.peerName) peerName = str(result.peerName);
        if (S.dialog && S.dialog.type === 'purge' && !findEntry(S.dialog.uid)) S.dialog = null;
        render();
        var n = (+result.added || 0) + (+result.updated || 0) + (+result.removed || 0);
        toast('Devices are in sync (' + n + ' entries updated)');
      },
      isUnlocked: function () {
        return !S.locked;
      },
      onChange: function (cb) {
        if (typeof cb === 'function') changeCbs.push(cb);
      },
      onLockChange: function (cb) {
        if (typeof cb === 'function') lockCbs.push(cb);
      },
      showScreen: function (name) {
        if (destroyed) return;
        name = str(name);
        if (name === 'lock') {
          lockVault();
          return;
        }
        var stack = stackFor(name);
        if (!stack) return;
        S.dialog = null;
        dropSheet();
        S.form = null;
        S.menu = null;
        S.stack = stack;
        enterRoute(top());
        if (S.locked) {
          S.locked = false;
          S.lock = { password: '', visible: false, error: null };
          render();
          emitLock();
        } else {
          render();
        }
      },
      destroy: function () {
        if (destroyed) return;
        destroyed = true;
        timers.forEach(clearTimeout);
        timers = [];
        root.removeEventListener('click', onClick);
        root.removeEventListener('input', onInput);
        root.removeEventListener('keydown', onKeydown);
        root.removeEventListener('pointerdown', onPointerDown, true);
        root.removeEventListener('focusin', onFocusIn);
        changeCbs = [];
        lockCbs = [];
        hostEl.innerHTML = '';
      }
    };
    return api;
  }

  window.VaultPassDemos = window.VaultPassDemos || {};
  window.VaultPassDemos.android = { mount: mount };
})();
