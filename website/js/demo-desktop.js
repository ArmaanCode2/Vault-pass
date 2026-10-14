/*
 * VaultPass for Windows: interactive replica for the website.
 *
 * Rebuilt screen by screen from the desktop app's Compose source
 * (com.vaultpass.desktop.ui.*): Sidebar, TopBar, VaultScreen, EntryDialog, DashboardScreen,
 * GeneratorScreen, SecurityCenterScreen, RecycleBinScreen, LockScreen and SettingsScreen.
 * Strength, security report and generator logic follow PasswordStrength.kt, SecurityAnalyzer.kt
 * and SecurePasswordGeneratorImpl.kt.
 *
 * The app's client area is laid out at its default window width (1024 px, Main.kt) and scaled
 * to fit the 960 x 568 area under the title bar, so its own layout rules (LayoutCoordinator,
 * ResizableSplitPane) give the same panes as the real window.
 *
 * Registers window.VaultPassDemos.desktop = { mount(hostEl, options) }. Does not auto-mount.
 * Icons: Material Icons path data (Apache-2.0), the same Icons.Filled.* the app uses.
 */
(function () {
  'use strict';

  var APP_W = 1024;
  var APP_H = 606;

  var P = {
    add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
    arrowBack: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
    check: 'M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
    checkCircle: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z',
    close: 'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
    contentCopy: 'M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z',
    cropSquare: 'M18 4H6c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 14H6V6h12v12z',
    dashboard: 'M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z',
    del: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
    deleteForever: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zm2.46-7.12l1.41-1.41L12 12.59l2.12-2.12 1.41 1.41L13.41 14l2.12 2.12-1.41 1.41L12 15.41l-2.12 2.12-1.41-1.41L10.59 14l-2.13-2.12zM15.5 4l-1-1h-5l-1 1H5v2h14V4z',
    deleteOutline: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM8 9h8v10H8V9zm7.5-5l-1-1h-5l-1 1H5v2h14V4z',
    deleteSweep: 'M15 16h4v2h-4zm0-8h7v2h-7zm0 4h6v2h-6zM3 18c0 1.1.9 2 2 2h6c1.1 0 2-.9 2-2V8H3v10zM14 5h-3l-1-1H6L5 5H2v2h12z',
    done: 'M9 16.2L4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4L9 16.2z',
    edit: 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z',
    email: 'M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z',
    info: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z',
    lock: 'M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z',
    lockClock: 'M14.5 14.2l2.9 1.7-.8 1.3L13 15v-5h1.5v4.2zM22 14c0 4.41-3.59 8-8 8-2.02 0-3.86-.76-5.27-2H4c-1.15 0-2-.85-2-2V9c0-1.12.89-1.96 2-2v-.5C4 4.01 6.01 2 8.5 2c2.34 0 4.24 1.79 4.46 4.08.34-.05.69-.08 1.04-.08 4.41 0 8 3.59 8 8zM6 7h5v-.74C10.88 4.99 9.8 4 8.5 4 7.12 4 6 5.12 6 6.5V7zm14 7c0-3.31-2.69-6-6-6s-6 2.69-6 6 2.69 6 6 6 6-2.69 6-6z',
    moreVert: 'M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
    openInNew: 'M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z',
    person: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
    phoneAndroid: 'M16 1H8C6.34 1 5 2.34 5 4v16c0 1.66 1.34 3 3 3h8c1.66 0 3-1.34 3-3V4c0-1.66-1.34-3-3-3zm-2 20h-4v-1h4v1zm3.25-3H6.75V4h10.5v14z',
    refresh: 'M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
    remove: 'M19 13H5v-2h14v2z',
    restore: 'M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z',
    search: 'M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
    searchOff: 'M15.5,14h-0.79l-0.28-0.27C15.41,12.59,16,11.11,16,9.5C16,5.91,13.09,3,9.5,3C6.08,3,3.28,5.64,3.03,9h2.02C5.3,6.75,7.18,5,9.5,5C11.99,5,14,7.01,14,9.5S11.99,14,9.5,14c-0.17,0-0.33-0.03-0.5-0.05v2.02C9.17,15.99,9.33,16,9.5,16c1.61,0,3.09-0.59,4.23-1.57L14,14.71v0.79l5,4.99L20.49,19L15.5,14z M6.47,10.82L4,13.29L1.53,10.82L0.82,11.53L3.29,14L0.82,16.47L1.53,17.18L4,14.71L6.47,17.18L7.18,16.47L4.71,14L7.18,11.53z',
    security: 'M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 10.99h7c-.53 4.12-3.28 7.79-7 8.94V12H5V6.3l7-3.11v8.8z',
    settings: 'M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.8,11.69,4.8,12s0.02,0.64,0.07,0.94l-2.03,1.58c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z',
    share: 'M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92s-1.31-2.92-2.92-2.92z',
    shield: 'M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z',
    sortByAlpha: 'M14.94 4.66h-4.72l2.36-2.36zm-4.69 14.71h4.66l-2.33 2.33zM6.1 6.27L1.6 17.73h1.84l.92-2.45h5.11l.92 2.45h1.84L7.74 6.27H6.1zm-1.13 7.37l1.94-5.18 1.94 5.18H4.97zm10.76 2.5h6.12v1.59h-8.53v-1.29l5.92-8.56h-5.88v-1.6h8.3v1.26l-5.93 8.6z',
    star: 'M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z',
    starBorder: 'M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z',
    sync: 'M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z',
    update: 'M21,10.12h-6.78l2.74-2.82c-2.73-2.7-7.15-2.8-9.88-0.1c-2.73,2.71-2.73,7.08,0,9.79s7.15,2.71,9.88,0C18.32,15.65,19,14.08,19,12.1h2c0,1.98-0.88,4.55-2.64,6.29c-3.51,3.48-9.21,3.48-12.72,0c-3.5-3.47-3.53-9.11-0.02-12.58s9.14-3.47,12.65,0L21,3V10.12z M12.5,8v4.25l3.5,2.08l-0.72,1.21L11,13V8H12.5z',
    visibility: 'M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z',
    visibilityOff: 'M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z',
    vpnKey: 'M12.65 10C11.83 7.67 9.61 6 7 6c-3.31 0-6 2.69-6 6s2.69 6 6 6c2.61 0 4.83-1.67 5.65-4H17v4h4v-4h2v-4H12.65zM7 14c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z',
    warning: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z'
  };

  // The window icon (src/main/resources/drawable/icon.png), 32 px.
  var APP_ICON = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAE6ElEQVR42rVWa0xURxQ+Z+ZelgUXFOVlEasVWbEvbZWkUNrwUjFVf2D6Q4uJQY2pptEmpn+sbdpEE5M2fVibNMaUaqJNTFMtxGjFVkAN6kJFQVQeGipSdQUWdvfunZnTH5ddUZaFNnp+nczM+b755px7zkUigmdp7L8G0LMjsLRi0HnKBFIqRLR8RJRSPR0CIpJSSak4Z16/sXjVjkWrPh70G5wza31sNRTOhJCmKYSUoZWzF1ucOeshoQgSip056+outoS2pJSmKYSQYaHCECj1yPf6/CfOuFas/RzseVMyS0/VNZyqa0jMLAV73vK1n5340+X1+YcFqpFo+IRGIkLEk7WNx09e+OvG7fqGG57Obnvq5A/Wrfhoa1m8DQCgz4BdX/701Q+/+O48mDA9JXve7Jdnpy8per0od54VPhzwMQLrresutuQu2QLRtpnpKdnzZi0rWrgkf2G8w95/9Vv3zV8BIeGF5XFzN/V5fMerLxw9WX++8Xr7rR7wG7VVX+QsyLJAwufANAUR7amoxImFl1s6huWkr+1w9tVvoKuypKuqpPlraPs5m0RfaP9ySwdOLNpTURkCCZk2Mu26phFjcRNiASAQMDiP+qdmW8B9xVnWDHFzACC1v+X6wQXdNduS3twrZSAqyhbniCGGGufjKlMr9aYQAKDrNiSv5+ah5Dd2uCGjbPOu1Zt2uiEzOedTz83DSH5dtwGAEBIgfMFqo5XvUK4QlBAi4HOkLqht7V5Tmg8Il5tb82bM7RF+aTzUYu3W8dFwtLG+EqUGmmLjHd57rrdfSYOohda6p7OdMcajHMHbwP8gkAAgHtSIK+XJM2bSgx8H7+3D6R9KPZMhBNxNAOS9ez7muXxkPML3rI3RNs0+ACQCQJ1xLbpr55222x4vMc6ZFt11rHhqSWXc8yVAcrQ2O3ovslQjD2Wea3CuI/HS36l2ux2YDYAhgjJ6Hzs/foKgakIAImAMzAAs251cVvGSIRjH4IWZNqSWxvNECAAQ74hBIfo83lAUIkgJ0RNUwYuG52FvvN3s9Wkch6oAAPo9XhRioiNmpBQ2sjSzMtKJwNXUZnVDyxQB53R/gLsHNJ0TEcIQARCRq6mNCOZkTHtU32EJGCIRzMlIS8pM33/kNCIyUACAyDhnpk/bvKh3Xd7dfp+ucURgljZE3H+kOml2etasaUTAIhAgopRS43xr+bJzx2rOuG5FTZ5qmgaIAZSDpuFbMb9nqbPDMAaVOSBNj2nSpKS02sbbZ4/WbSl/R9O4lDJSNx3qfQA+nzH3rY3KlI013yWwdsPr0XQdgKSEgN/PkADQNAMxsfEDtqxX894Hjs1nvo+x20Y+UZiBYw2ycw2tOKlofsGmbrdBRIJo+MSy/J5e87WCzTip8GxDayhw7IlmjUwiqvzjEk9anOhcWVVdH1w3hRmw/Krq+sTMUpa4+LfTl0Ih4yUIBbia252560HPXblhZ9O1Tmur6Vrnyg27QM9x5q53XW2LgB6JgIhMIYjI6ze27z4AKUvBkV+8ekfxe59AXAGkLN2++4DXb0RGDzOTnzClFGMMALq67++tqNp36HcCKH+3cOOakrTUKcMPjNpxiFSEbm5VgVJkjVmlCIAsRCkVY4iIkRv+2ARBKaRIWUNRSMmQMYbj+bMbL0FQDQDQmLeO9KE9dfsXeEsLPHgKt6sAAAAASUVORK5CYII=';

  function icon(name, size, cls) {
    return '<svg class="vpd-icon' + (cls ? ' ' + cls : '') + '" width="' + size + '" height="' + size +
      '" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="' + P[name] + '"/></svg>';
  }

  // M3 CircularProgressIndicator (indeterminate): a rotating arc whose length grows and shrinks.
  // Drawn in a 50-unit box; the stroke is scaled so it renders `stroke` px wide at `size` px.
  function spinner(size, stroke, cls) {
    var sw = stroke * 50 / size, r = (50 - sw) / 2;
    return '<svg class="vpd-cpi' + (cls ? ' ' + cls : '') + '" width="' + size + '" height="' + size + '" viewBox="0 0 50 50" aria-hidden="true" focusable="false">' +
      '<circle class="vpd-cpi-arc" cx="25" cy="25" r="' + r.toFixed(3) + '" fill="none" stroke="currentColor" stroke-width="' + sw.toFixed(3) + '" stroke-linecap="square" pathLength="100"/></svg>';
  }

  var ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  function esc(v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) { return ESC[c]; });
  }

  // Ids that go into data-fk / element ids: letters, digits, "-" and "_" only.
  function sid(v) {
    return String(v == null ? '' : v).replace(/[^A-Za-z0-9_-]/g, function (c) { return '_' + c.charCodeAt(0).toString(16); });
  }

  function isBlank(s) { return !s || String(s).trim() === ''; }

  // ---------------------------------------------------------------- dates (SimpleDateFormat patterns the app uses)
  var MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function p2(n) { return (n < 10 ? '0' : '') + n; }
  function fmtLong(ms) { var d = new Date(ms); return MON[d.getMonth()] + ' ' + p2(d.getDate()) + ', ' + d.getFullYear() + ' ' + p2(d.getHours()) + ':' + p2(d.getMinutes()); }
  function fmtShort(ms) { var d = new Date(ms); return MON[d.getMonth()] + ' ' + p2(d.getDate()) + ', ' + p2(d.getHours()) + ':' + p2(d.getMinutes()); }
  function fmtDay(ms) { var d = new Date(ms); return MON[d.getMonth()] + ' ' + p2(d.getDate()) + ', ' + d.getFullYear(); }
  function toMs(iso) { var t = Date.parse(iso); return isNaN(t) ? 0 : t; }
  function nowIso() { return new Date().toISOString(); }

  // ---------------------------------------------------------------- domain/security/PasswordStrength.kt
  function re(src, fallback) { try { return new RegExp(src, 'u'); } catch (e) { return fallback; } }
  var RE_LOWER = re('\\p{Lowercase}', /[a-z]/);
  var RE_UPPER = re('\\p{Uppercase}', /[A-Z]/);
  var RE_DIGIT = re('\\p{Nd}', /[0-9]/);
  var RE_SYMBOL = re('[^\\p{L}\\p{Nd}]', /[^A-Za-z0-9]/);
  var DICTIONARY = ['password', 'qwerty', '12345', 'admin', 'welcome', 'letmein', '123123'];

  function passwordScore(pw) {
    if (!pw) return 0;
    var score = 0;
    var len = pw.length;
    var pool = 0;
    if (RE_LOWER.test(pw)) pool += 26;
    if (RE_UPPER.test(pw)) pool += 26;
    if (RE_DIGIT.test(pw)) pool += 10;
    if (RE_SYMBOL.test(pw)) pool += 32;
    score += pool > 0 ? len * (Math.log(pool) / Math.log(2)) : 0;
    if (len >= 16) score += 10;
    if (len >= 24) score += 15;
    var i, consecutive = 0;
    for (i = 0; i < len - 1; i++) if (pw.charAt(i) === pw.charAt(i + 1)) consecutive++;
    score -= consecutive * 5;
    var lower = pw.toLowerCase();
    DICTIONARY.forEach(function (w) { if (lower.indexOf(w) >= 0) score -= 30; });
    var sequential = 0;
    for (i = 0; i < len - 2; i++) {
      var c1 = pw.charCodeAt(i), c2 = pw.charCodeAt(i + 1), c3 = pw.charCodeAt(i + 2);
      if ((c1 + 1 === c2 && c2 + 1 === c3) || (c1 - 1 === c2 && c2 - 1 === c3)) sequential++;
    }
    score -= sequential * 15;
    return score < 40 ? 0 : (score < 75 ? 1 : 2);
  }

  // ---------------------------------------------------------------- domain/security/SecurityAnalyzer.kt (the parts the UI shows)
  var DAY_MS = 24 * 60 * 60 * 1000;
  function analyze(entries) {
    if (!entries.length) return { score: 0, weak: [], reused: [] };
    var weak = entries.filter(function (e) { return passwordScore(e.password) === 0; });
    var bySecret = {}, byCore = {};
    entries.forEach(function (e) {
      (bySecret['k' + e.password] = bySecret['k' + e.password] || []).push(e);
      var core = JSON.stringify([e.title, e.username, e.password]);
      (byCore[core] = byCore[core] || []).push(e);
    });
    var reused = [], dup = [];
    Object.keys(bySecret).forEach(function (k) { if (bySecret[k].length > 1) reused = reused.concat(bySecret[k]); });
    Object.keys(byCore).forEach(function (k) { if (byCore[k].length > 1) dup = dup.concat(byCore[k]); });
    var now = Date.now();
    var old = entries.filter(function (e) { return now - toMs(e.updatedAt) > 90 * DAY_MS; });
    var noUrl = entries.filter(function (e) { return isBlank(e.website); });
    var noUser = entries.filter(function (e) { return isBlank(e.username); });
    var score = 100 - weak.length * 15 - reused.length * 10 - dup.length * 5 - old.length * 2 - noUrl.length - noUser.length * 2;
    score = Math.max(0, Math.min(100, score));
    // Same order as the app: entries in vault order, grouped by secret in first-seen order.
    return { score: score, weak: weak, reused: reused };
  }

  // ---------------------------------------------------------------- data/generator/SecurePasswordGeneratorImpl.kt
  var UPPER = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
  var LOWER = 'abcdefghijklmnopqrstuvwxyz';
  var NUMBERS = '0123456789';
  var SYMBOLS = '!@#$%^&*()_+-=[]{}|;:,.<>?';
  var UPPER_U = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
  var LOWER_U = 'abcdefghijkmnpqrstuvwxyz';
  var NUMBERS_U = '23456789';
  var SYMBOLS_U = '!@#$%^&*()_+-=[]{};:,.<>?';
  // The app draws passphrase words from the EFF Large Wordlist (Electronic Frontier Foundation, CC BY 3.0 US;
  // resources/wordlists/eff_large_wordlist.txt). To stay small the demo carries every 8th word of it (972),
  // and rates strength for the full 7,776-word list, as the app does: 5 words = 64.6 bits.
  var WORDS = ('abacus abnormal absentee accent acetone acrobat activist aerosol affiliate afloat afternoon agile agreeably aids ' +
    'algebra alive aloha alto ambiance amendment amiss ample amusement anchor angelfish angriness anime annuity antennae ' +
    'antiquely antler anyplace appealing applaud appraisal apron area armchair armory array ascend aside astonish atlas ' +
    'attain attire audible author available avert aware babbling backer backlight backside backup badass bagful baguette ' +
    'balmy bankbook banshee bargraph barrack barterer batboy battery blah blast blemish blinking blob bluff blush bobble ' +
    'bogus bondless bonsai booth boring botch bounding boxy breezy bridged briskly broadness bronco browse brute buckle ' +
    'buddy bulb bullfrog bully bunny buzz cackle cage caliber camera canary cannabis canopy capital captive carded caress ' +
    'carnation carried cartridge casing catalyst catching catlike catwalk caution celestial certainly chain change chapter ' +
    'charm chastity cheer cherub chewy chili chive choosing chuck cilantro circus civic clamshell clarity clay clerical ' +
    'clinic cloning clubbing clutter coat coeditor coherence coliseum collision come comment commuting compel composure ' +
    'conceal conclude conductor confiding confront conical console consult contently contusion coping corncob cornstalk ' +
    'corridor cosmetics cough courier cozy craftily cranium crayfish creamlike credible crescent crib crinkle critter ' +
    'crouton cruelness crunching crux cucumber cultivate curable curliness curtly custard cycle dab daisy danger darkish ' +
    'dastardly daybed dazzler dealt debtor deceased deceptive decline dedicate deepness defendant defile deflector degraded ' +
    'delegate delirious deluxe demotion density departure depraved derail designed despise detective detract device ' +
    'dexterity dial dictation dilation dimmed dingo dipper dirtiness disbelief discourse disinfect dismantle disparity ' +
    'displease distance district dividers divisive document dollop donated doorknob doozy douche dragonish dramatize ' +
    'dreamboat dreary driller driver drop-down drowsily ducking duh duo duress dweeb dynasty earful earring earwig ' +
    'eastcoast ebay ecologist edginess eel eggplant elastic elective eligible elliptic elusive ember emcee empathic ' +
    'employed emu enclosure endanger endorphin energize engraved enjoyably enquirer entangled entourage envelope epic ' +
    'epiphany equity errand escapade essay estranged ethics evasion evict exalted excitable excretory exerciser existing ' +
    'expansion expensive exploit express external exuberant facelift factoid falcon famished fantasize faucet federal ' +
    'feminine ferment fester fiction fiftieth filling finally finisher flaccid flaky flashback flatfoot flatware fleshy ' +
    'flirt flounder foe fondly footbath footman footwear foyer frail freckled freeing freewill frequent friend frisk ' +
    'frostily frugality gainfully gallon gamma garden gas gawk generic geography geriatric getaway giddy gigolo giving ' +
    'glamorous glaucoma glisten glorified glowworm goal golf goofball gorgeous grab grading grandkid grant gratified ' +
    'graveyard greedless grief griminess groggy grouped grudging grunt gulf gummy guts habitual half hamper handclap ' +
    'handheld handoff handstand hangover happiest harddisk hardness harmonica hash hatchery haven headband heading ' +
    'headscarf heat hefty hemlock herbs hesitancy huff humbly humorless hungrily hurled husband hydration hypnotic iciness ' +
    'ideally idly image immerse immovably impeding implicit impose imprison impulsive ipod irritant issue ivy jailer ' +
    'jasmine jaybird jester jitters joining joyfully judgingly juiciness junction jurist juvenile keenness kettle kimono ' +
    'king kite knickers labored ladybug landed landmark lankiness lard lather laurel left legislate length letter liable ' +
    'lifter lilac limping linoleum lisp lived lubricate lukewarm lunacy lure lustiness lyricist magazine magnetism majestic ' +
    'malformed manatee mangy manila manor marauding margin maroon marxism mastiff maternal matted maximum mobilize ' +
    'modulator molecular monetize monogamy monsieur mooing moonshine morbidly mortify motivate mounted mouth muck multiple ' +
    'mummify murkiness mushy mustiness mutiny myth nape national navigator nebula negligent nervy neutron nimble nuclei ' +
    'numeral nurture nuzzle obituary oboe obsessive obvious octagon okay omit only onyx operate oppose other outboard ' +
    'outdoors outing outnumber outright outsource ovary overblown overcrowd overexert overhang overlabor overnight ' +
    'overreach oversight overstock overture oxidant paced padding palace pampers panhandle pants parachute paralyze parched ' +
    'parmesan partition passcode passport pasture patient pavement paycheck pebble pedigree penalty penny perch perjurer ' +
    'persecute pester petticoat phoney placard plastic plausible playing plaything plethora pluck poach pointless policy ' +
    'polygraph popper porous portside postal postwar powdering pranker preamble predict preheated preoccupy preseason ' +
    'preteen prevalent primal prior prize problem produce profusely promenade pronounce proponent prototype provided ' +
    'prowess prune pug pumice punk pureness purity pursuit pushpin pyromania qualified quartered quickness quirk race ' +
    'radiantly rage railway ramrod ranking rasping reach reappear reassure reborn recant recital recognize record rectify ' +
    'reexamine refining reforest refresh refusing region regulate reiterate related reliable relish remark remission ' +
    'removal renegade rentable repackage repeated reporter reproach repulsion reroute reseller residence resisting respect ' +
    'retainer retiree retrain retying revenge reversing revival reward rhyme ricotta rigid riot rise riverbed robbing ' +
    'rockfish roman rotunda routing rubdown rumbling runt sabbath saddling saffron salad salt sampling sandblast sandstone ' +
    'sapling sassy saturday savior scale scanning scarily scheme scoff scorecard scoundrel scrambled scribing scruffy ' +
    'scuttle sector sediment selection semifinal senorita septic serpent setting severity shady shallot shaping sheath ' +
    'shelving shimmy shirt shore shortly showcase showpiece shrewdly shrubbery shun sierra silicon simile singing siren ' +
    'sixteen sizing skeptic skiing skincare skirmish skyward slam slather sliceable slighted slip sloppy slush smell smock ' +
    'smooth snack sneer snooper snowboard snowiness snub spearman spectator spendable spider spinner spirits splendor ' +
    'spoiler spoof sporty spouse spring spry squander squeamish squire stadium staining stamp stardust starlit startup ' +
    'stature steadily stegosaur sterility stiffly stimuli stinky stole stooge stopping stove strangely streak strewn ' +
    'striving structure stubbly stuffed stunner stylist subduing sublime subprime subsoil subtract suction suffocate suitor ' +
    'sulphuric supermom supreme surgery surrender sushi swagger sweep swimmer switch swung synergy tabasco tacking tactile ' +
    'talcum tank tapestry tarot tasting thank theology thicken think thirteen threaten throng thumping tidbit tightrope ' +
    'timing tinkling tipper track trailing translate trapper treachery tremble triangle trickily tried trimness trolling ' +
    'trousers trustable tubeless tumbling turkey tweak twice twistable tying umpire unaltered unbeaten unbolted unburned ' +
    'uncharted unclip uncommon uncross undated undercook undergrad underpay underwent undrafted uneasily unenvied unfasten ' +
    'unfixed unfrozen unguarded unheated unicycle uninstall unit unknown unleveled unlivable unloving unmasked unmovable ' +
    'unnerving unpaired unpleased unrated unrented unrobed unsalted unsecured unshackle unskilled unsorted unstirred ' +
    'unsuited untapped untimely untried unused unviable unweave unwitting unwrapped upfront upload upscale upswing urban ' +
    'usable utensil vacate valid vanquish varmint veal vending venus versus veto video villain violin viscous vista vixen ' +
    'volatile vowel wages walrus washbasin washing water wheat whole widen wikipedia wildly wimp winter wish wok womb ' +
    'worrier wow wriggle wronged yam yearly yield yoyo zestfully zodiac').split(' ');
  var EFF_WORD_COUNT = 7776;

  // Uniform integer in [0, n): crypto.getRandomValues with rejection sampling (no modulo bias).
  function randInt(n) {
    var buf = new Uint32Array(1);
    var limit = 4294967296 - (4294967296 % n);
    do { window.crypto.getRandomValues(buf); } while (buf[0] >= limit);
    return buf[0] % n;
  }

  var RATINGS = [
    { label: 'Very Weak', color: '#FF4D4D', crack: 'Instantly' },
    { label: 'Weak', color: '#FF6B6B', crack: 'Few hours to days' },
    { label: 'Medium', color: '#FFB900', crack: 'Several years' },
    { label: 'Strong', color: '#0EA5A1', crack: 'Centuries' },
    { label: 'Very Strong', color: '#10B981', crack: 'Millions of years' }
  ];
  function rating(bits) {
    return RATINGS[bits < 40 ? 0 : bits < 65 ? 1 : bits < 80 ? 2 : bits < 100 ? 3 : 4];
  }
  function log2(x) { return Math.log(x) / Math.log(2); }

  function generatePassword(c) {
    var sets = [];
    if (c.upper) sets.push(c.ambiguous ? UPPER_U : UPPER);
    if (c.lower) sets.push(c.ambiguous ? LOWER_U : LOWER);
    if (c.numbers) sets.push(c.ambiguous ? NUMBERS_U : NUMBERS);
    if (c.symbols) sets.push(c.ambiguous ? SYMBOLS_U : SYMBOLS);
    if (!sets.length) sets.push(c.ambiguous ? LOWER_U : LOWER);
    var pool = sets.join('');
    var length = Math.max(1, c.length | 0);
    var out = [], i;
    for (i = 0; i < sets.length && out.length < length; i++) out.push(sets[i].charAt(randInt(sets[i].length)));
    while (out.length < length) out.push(pool.charAt(randInt(pool.length)));
    for (i = out.length - 1; i > 0; i--) {
      var j = randInt(i + 1), t = out[i];
      out[i] = out[j];
      out[j] = t;
    }
    var bits = pool.length > 1 ? length * log2(pool.length) : 0;
    var r = rating(bits);
    return { value: out.join(''), bits: bits, rating: r };
  }

  function generatePassphrase(c) {
    var count = Math.max(1, c.words | 0);
    var words = [];
    for (var i = 0; i < count; i++) {
      var w = WORDS[randInt(WORDS.length)];
      words.push(c.cap ? w.charAt(0).toUpperCase() + w.slice(1) : w);
    }
    if (c.num) words[words.length - 1] += String(randInt(10));
    var bits = count * log2(EFF_WORD_COUNT) + (c.num ? log2(10) : 0);
    return { value: words.join(c.sep), bits: bits, rating: rating(bits) };
  }

  // ---------------------------------------------------------------- static copy from SettingsScreen.kt
  var SETTINGS_CATS = [
    ['Security', 'Security'], ['Vault', 'Vault'], ['Sync', 'LAN Sync'], ['Backup', 'Backup'],
    ['Privacy', 'Privacy'], ['Appearance', 'Appearance'], ['About', 'About']
  ];
  var EXPORT_FORMATS = [
    ['Vpex', 'Encrypted Backup (.vpex)', 'VaultPass Android &amp; Desktop'],
    ['Json', 'JSON (.json)', 'Compatible with Android VaultPass'],
    ['Csv', 'Standard CSV (.csv)', 'Excel, Google Sheets, 1Password'],
    ['PlainText', 'Plain Text (.txt)', 'Readable export, not a backup']
  ];
  var IMPORT_OPTIONS = [
    'Auto-Detect Format (Recommended)', 'VaultPass Encrypted (.vpex)', 'VaultPass JSON (.json)', 'Standard CSV (.csv)',
    'Bitwarden JSON (.json)', 'Bitwarden CSV (.csv)', 'KeePass 2.x XML (.xml)', 'KeePass CSV (.csv)', 'VaultPass Plain Text (.txt)'
  ];
  var DROPDOWNS = {
    autoLock: {
      title: 'Auto-Lock Inactivity Timeout', desc: 'Lock the vault automatically after a period of inactivity.',
      options: ['1 Minute', '5 Minutes', '15 Minutes (Default)', '30 Minutes', '60 Minutes', 'Never'],
      values: [1, 5, 15, 30, 60, 0], key: 'autoLockTimeoutMinutes'
    },
    clipboard: {
      title: 'Clipboard Clear Timeout', desc: 'Duration before copied passwords are erased from clipboard.',
      options: ['10 Seconds', '20 Seconds', '30 Seconds (Default)', '60 Seconds', '120 Seconds', 'Never Auto-Clear'],
      values: [10, 20, 30, 60, 120, 0], key: 'clearClipboardTimeoutSeconds'
    },
    doubleClick: {
      title: 'Double-Click Entry Action', desc: 'Choose what happens when double-clicking a vault entry.',
      options: ['Copy Password', 'Copy Username', 'Edit Entry', 'View Details'],
      values: ['Copy Password', 'Copy Username', 'Edit Entry', 'View Details'], key: 'doubleClickBehaviour'
    },
    retention: {
      title: 'Recycle Bin Auto-Delete Period', desc: 'Automatically and permanently purge deleted entries after this time.',
      options: ['7 Days', '14 Days', '30 Days (Default)', '60 Days', '90 Days', 'Never Auto-Delete'],
      values: [7, 14, 30, 60, 90, 0], key: 'recycleBinRetentionDays'
    }
  };
  var PRIVACY_SECTIONS = [
    ['1. Information Stored & Local Device Storage', [
      ['Zero Access to Stored Information', 'VaultPass Desktop allows you to manage sensitive information including account logins, passwords, usernames, web URLs, secure notes, and custom fields. We do not collect, transmit, store, or have access to any of this information.'],
      ['No Accounts or User Registration', 'VaultPass Desktop requires no user registration, email address, phone number, or personal identifiers. There is no account creation process or identity tracking.'],
      ['Zero Telemetry, Analytics, or Tracking', 'The Application contains no tracking code, telemetry SDKs, analytics suites, or behavioral monitoring tools. We do not track how you use the Application.'],
      ['No Developer Cloud Infrastructure', "We do not operate cloud databases, sync servers, or proprietary relay backends. Your data never touches any server owned or operated by us. All encrypted vault databases, encryption metadata, and configurations reside locally on your computer (within your chosen installation folder's 'data' directory or user application directory)."],
      ['Zero Telemetry or Remote Crash Reports', 'Operational logs and crash traces remain local to your machine and are never transmitted automatically to us or any third party.']
    ]],
    ['2. Cryptography & Security Architecture', [
      ['Master Password & Key Derivation', 'All vault records are secured using AES-256-GCM before being written to disk. Encryption keys are derived locally from your Master Password with PBKDF2-HMAC-SHA256. Your Master Password is never transmitted off your machine and is never stored in plain text.'],
      ['Session Protection (DPAPI & Locking)', 'VaultPass drops the vault key from memory when the vault locks. If you turn off "Require Master Password on Startup", it also keeps the vault key on disk, protected with the Windows Data Protection API (DPAPI) for your Windows account, so the vault opens after a restart. Any program running under your Windows account can read that key. Locking VaultPass deletes it.'],
      ['Zero Knowledge & No Recovery Backdoors', 'Because encryption occurs locally and we have zero knowledge of your Master Password, we cannot reset your password or recover your data if you lose your Master Password.'],
      ['Guessing Your Master Password', "Every unlock attempt runs PBKDF2-HMAC-SHA256 with 300,000 iterations, so each guess takes time. VaultPass Desktop doesn't lock you out after wrong passwords, and anyone with a copy of your vault files can try passwords on their own computer, so a long, unique Master Password is what protects your vault."]
    ]],
    ['3. Network Communication & Software Updates', [
      ['A. Software Update Checks (GitHub Releases)', 'VaultPass Desktop maintains a strict separation between your local encrypted vault and external network requests:\n\u2022 Public Endpoint Queried: When checking for updates, the Application sends a standard HTTPS GET request to GitHub\'s public API or web fallback (api.github.com / github.com/ArmaanCode2/vault-pass-desktop/releases/latest) and downloads updated installation packages from GitHub Releases.\n\u2022 What Data is Sent: In accordance with standard Internet protocol communication, this request transmits only generic technical network headers (your IP address as visible to GitHub, and a standard User-Agent header such as VaultPass-Desktop/1.0.2).\n\u2022 What Data is NEVER Sent: Vault records, master passwords, encryption keys, usernames, URLs, machine identifiers, hardware fingerprints, and usage statistics are NEVER included in update requests.\n\u2022 Third-Party Infrastructure: Update binaries and release metadata are hosted on GitHub (Microsoft Corporation) and subject to the GitHub Privacy Statement. VaultPass operates no intermediate proxy or custom server.\n\u2022 Air-Gapped Operation: Update checks are off for new installs and can be turned on or off at any time under Settings -> About. VaultPass Desktop operates with complete feature parity in air-gapped or firewall-blocked environments.'],
      ['B. Local Network Synchronization (LAN Sync)', 'VaultPass Desktop can synchronize credentials directly with the VaultPass Android companion app:\n\u2022 Local Subnet Only: Synchronization occurs directly between devices over your local Wi-Fi subnet using end-to-end encrypted sockets.\n\u2022 Zero Cloud Relays: Sync data is never transmitted over the public internet, external proxies, or third-party relay servers. Both devices must reside on the same local subnet to communicate.']
    ]],
    ['4. Import/Export, Clipboard & Third-Party Boundaries', [
      ['Import and Export Features', 'VaultPass Desktop provides tools to import credentials from external formats (CSV, Bitwarden, KeePass) and export your vault data (unencrypted CSV/JSON or encrypted .vpex backups). Exported files are written directly to your chosen local directory path. You are solely responsible for securing exported plain text files.'],
      ['Clipboard Security & History Exclusion', "When you copy a password, VaultPass marks it with Windows' clipboard history exclusion flags (CanIncludeInClipboardHistory = 0, CanUploadToCloudClipboard = 0) and clears it after the clipboard timer (configurable in Settings). Locking or closing VaultPass clears it too, unless something else was copied since. Usernames and URLs are copied without these protections."],
      ['Third-Party Services: Zero Ads & Zero Brokerage', 'VaultPass Desktop contains no advertisements or advertising SDKs, no analytics or user profiling services, and no data brokerage. Because we do not collect or possess your information, we do not (and cannot) sell, lease, or distribute your data to any third party.']
    ]],
    ['5. Data Sovereignty & User Responsibilities', [
      ['Data Retention and Deletion', "Because your data is stored exclusively on your computer, you have complete control over data retention and deletion. To delete all stored credentials and configuration files, delete the 'data' directory in your VaultPass installation folder or uninstall the application and remove the data files. Soft-deleted items in the Recycle Bin are automatically purged after 7 days, or can be permanently deleted manually at any time."],
      ['Privacy Rights (GDPR & CCPA Autonomous Sovereignty)', 'Under regulations such as GDPR and CCPA, individuals have rights regarding access, rectification, portability, and erasure of personal information. Because VaultPass Desktop does not collect, process, or transmit personal data to any server, you maintain direct, autonomous sovereignty:\n\u2022 Access & Portability: View, search, and export all stored records at any time.\n\u2022 Rectification: Edit or update any entry directly in the vault interface.\n\u2022 Erasure: Immediately and irrevocably delete any credential, folder, or the entire vault file directly on your local system without submitting requests to any company.'],
      ['User Security Responsibilities', 'The privacy and security of your vault also rely on your local computing practices. By using VaultPass Desktop, you acknowledge responsibility for:\n\u2022 Selecting a strong, complex Master Password and memorizing it, as it cannot be recovered by anyone if lost.\n\u2022 Maintaining physical and administrative security over your personal computer.\n\u2022 Protecting any unencrypted export files created on your local disk.\n\u2022 Keeping your operating system secure against malware, keyloggers, and unauthorized physical access.']
    ]],
    ['6. Policy Updates & Contact Information', [
      ['Changes to This Privacy Policy', 'We may update this Privacy Policy periodically to reflect enhancements in application features or security practices. Because we do not collect contact details, updates are published directly in the open-source repository. We encourage users to review this document as new releases are issued.'],
      ['Contact Information', 'If you have questions, inquiries, or feedback concerning this Privacy Policy or the security architecture of VaultPass Desktop, please contact us:\n\u2022 GitHub Issues: https://github.com/ArmaanCode2/vault-pass-desktop\n\u2022 Email: armaanweb100@gmail.com']
    ]]
  ];

  var TITLES = { dashboard: 'Dashboard', vault: 'Vault', security: 'Security Center', generator: 'Generator', trash: 'Recycle Bin', settings: 'Settings' };
  var SCREENS = { vault: 1, dashboard: 1, security: 1, generator: 1, trash: 1, settings: 1 };
  var LIMITS = { title: 200, username: 300, password: 1000, url: 500, notes: 20000, cfKey: 100, cfValue: 2000, maxCf: 50, tag: 50, maxTags: 25 };

  function reducedMotion() {
    try { return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches); } catch (e) { return false; }
  }

  function str(v) { return v == null ? '' : String(v); }

  var mountCount = 0;

  // ================================================================= mount
  function mount(hostEl, options) {
    if (!hostEl) throw new Error('VaultPass desktop demo: no host element');
    options = options || {};
    var n = ++mountCount;
    var idp = 'vpd' + n + '-';
    var demoData = window.VaultPassDemoData || null;
    var owner = (demoData && demoData.owner) || 'Kay';
    var peerName = str(options.peerName) || (demoData && demoData.devices && demoData.devices.android && demoData.devices.android.name) || 'VaultPass Android';
    var appVersion = (typeof options.appVersion === 'string' && /^v?[0-9A-Za-z.+-]{1,32}$/.test(options.appVersion)) ? options.appVersion.replace(/^v/, '') : '1.1.0';
    var tagsByUid = {};

    function normEntry(e) {
      e = e || {};
      var created = e.createdAt || nowIso();
      return {
        uid: str(e.uid) || newUid(),
        title: str(e.title),
        username: str(e.username),
        password: str(e.password),
        website: str(e.website),
        category: str(e.category),
        notes: str(e.notes),
        favorite: !!e.favorite,
        customFields: (Array.isArray(e.customFields) ? e.customFields : []).map(function (f) {
          f = f || {};
          return { label: str(f.label), value: str(f.value), hidden: f.hidden !== false };
        }),
        createdAt: created,
        updatedAt: e.updatedAt || created,
        deletedAt: e.deletedAt || null
      };
    }
    function publicEntry(e) {
      return {
        uid: e.uid, title: e.title, username: e.username, password: e.password, website: e.website,
        category: e.category, notes: e.notes, favorite: e.favorite,
        customFields: e.customFields.map(function (f) { return { label: f.label, value: f.value, hidden: f.hidden }; }),
        createdAt: e.createdAt, updatedAt: e.updatedAt, deletedAt: e.deletedAt
      };
    }
    var uidSeq = 0;
    function newUid() { return 'u' + Date.now().toString(36) + (uidSeq++).toString(36); }

    var source = Array.isArray(options.vault) ? options.vault : (demoData && typeof demoData.vaultFor === 'function' ? demoData.vaultFor('desktop') : []);

    var initial = typeof options.initialScreen === 'string' ? options.initialScreen : '';
    var startLocked = options.locked === true || initial === 'lock';

    var S = {
      locked: startLocked,
      screen: SCREENS[initial] ? initial : 'vault',
      pendingScreen: startLocked && SCREENS[initial] ? initial : null,
      entries: source.map(normEntry),
      settings: {
        requireMasterPasswordOnStartup: true, lockWhenMinimized: false, autoLockTimeoutMinutes: 15,
        lockOnWorkstationLock: true, clearSearchOnLock: true, clearClipboardTimeoutSeconds: 30,
        hidePasswordsByDefault: true, doubleClickBehaviour: 'View Details', recycleBinRetentionDays: 30,
        showClipboardHistoryWarning: true, checkForUpdatesOnOpen: false, confirmBeforeDelete: true,
        defaultViewRememberLastSelected: true, lastSelectedEntryId: null
      },
      vaultQuery: '', dashQuery: '', binQuery: '',
      vault: { selectedId: null, sortAsc: true, pwVisible: false, cfVisible: {} },
      bin: { selected: {} },
      gen: null,
      sec: { shown: 0 },
      settingsCat: 'Security',
      exportFormat: 'Vpex',
      csvProtect: false,
      unlockedWith: null,      // the password last typed at the lock screen; the demo has no other
      importOption: 0,
      update: { state: 'idle', checkedAt: 0 },
      paired: [{ name: peerName, ip: '192.168.1.42', port: 53853, lastSyncAt: toMs('2026-10-08T20:30:00Z'), online: true }],
      dialogs: [],
      menu: null,
      snack: { main: null, screen: null },
      clip: { active: false, remaining: 0, total: 0 },
      lock: { pw: '', visible: false, error: null, loading: false, shakeNow: false },
      lastUnlock: Date.now(),
      // Sync tombstones (SQLiteVaultDataSource.deleteWithTombstones): uid -> the purged record, stamped with
      // the purge time. getVault() reports them as recycle-bin records so the site's merge carries the
      // deletion to the phone instead of copying the phone's entry back.
      tombs: {}
    };
    // Sample state: "Remember last selected entry" is on in the app; the demo opens with Google selected.
    if (S.entries.some(function (e) { return e.uid === 'google' && !e.deletedAt; })) S.settings.lastSelectedEntryId = 'google';

    // Entries the paired phone is known to hold: the shared sample entries until the first sync, then
    // everything the last sync exchanged. Only purging one of these needs a tombstone.
    var peerKnown = {};
    (function () {
      var peerVault = demoData && typeof demoData.vaultFor === 'function' ? demoData.vaultFor('android') : null;
      (Array.isArray(peerVault) ? peerVault : S.entries).forEach(function (e) { if (e && e.uid) peerKnown[e.uid] = true; });
    })();
    var syncSeq = 0;

    var timers = {};
    var rafId = 0;
    var changeCbs = [], lockCbs = [];
    var snackSeq = 0;
    var destroyed = false;

    // ---------------------------------------------------------------- DOM skeleton
    while (hostEl.firstChild) hostEl.removeChild(hostEl.firstChild);
    var root = document.createElement('div');
    root.className = 'vpd';
    root.setAttribute('role', 'region');
    root.setAttribute('aria-label', 'Interactive demo of VaultPass for Windows (sample data)');
    root.innerHTML =
      '<div class="vpd-titlebar" aria-hidden="true">' +
        '<img class="vpd-titlebar-icon" src="' + APP_ICON + '" alt="" width="16" height="16">' +
        '<span class="vpd-titlebar-title">VaultPass</span>' +
        '<span class="vpd-caption">' + icon('remove', 14) + '</span>' +
        '<span class="vpd-caption">' + icon('cropSquare', 14) + '</span>' +
        '<span class="vpd-caption">' + icon('close', 14) + '</span>' +
      '</div>' +
      '<div class="vpd-client"><div class="vpd-app"><div class="vpd-main"></div><div class="vpd-layer"></div></div></div>' +
      '<div class="vpd-sr vpd-live" role="status" aria-live="polite"></div>';
    hostEl.appendChild(root);
    var appEl = root.querySelector('.vpd-app');
    var mainEl = root.querySelector('.vpd-main');
    var layerEl = root.querySelector('.vpd-layer');
    var liveEl = root.querySelector('.vpd-live');

    // ---------------------------------------------------------------- helpers
    function active() { return S.entries.filter(function (e) { return !e.deletedAt; }); }
    function deleted() { return S.entries.filter(function (e) { return !!e.deletedAt; }); }
    function byId(id) { for (var i = 0; i < S.entries.length; i++) if (S.entries[i].uid === id) return S.entries[i]; return null; }
    function displayTitle(e) { return isBlank(e.title) ? 'Untitled' : e.title; }
    function initialOf(e) { return displayTitle(e).charAt(0).toUpperCase(); }
    function lc(s) { return String(s).toLowerCase(); }
    function cmp(a, b) { return a < b ? -1 : (a > b ? 1 : 0); }
    function matches(e, q, fields) {
      if (!q) return true;
      q = lc(q);
      return fields.some(function (f) { return lc(e[f]).indexOf(q) >= 0; });
    }
    function setTimer(name, fn, ms) { clearTimer(name); timers[name] = setTimeout(function () { delete timers[name]; if (!destroyed) fn(); }, ms); }
    function clearTimer(name) { if (timers[name]) { clearTimeout(timers[name]); delete timers[name]; } }
    function fire(list, arg) { list.slice().forEach(function (cb) { try { cb(arg); } catch (e) { /* the host's callback must not break the demo */ } }); }
    function changed() { fire(changeCbs); }
    function announce(msg) {
      liveEl.textContent = '';
      setTimer('live', function () { liveEl.textContent = msg; }, 50);
    }
    function copyToClipboard(text) {
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).catch(function () {});
      } catch (e) { /* the copy feedback still shows */ }
    }

    // Position of an element inside the app, in the app's own (unscaled) pixels. Uses layout offsets only.
    function posInApp(el) {
      var x = 0, y = 0, node = el;
      while (node && node !== appEl) {
        x += node.offsetLeft;
        y += node.offsetTop;
        var parent = node.offsetParent;
        if (parent && parent !== appEl) {
          x += parent.clientLeft - parent.scrollLeft;
          y += parent.clientTop - parent.scrollTop;
        }
        node = parent;
      }
      return { x: x, y: y };
    }

    // ---------------------------------------------------------------- focus + scroll preservation across renders
    function captureFocus() {
      var a = document.activeElement;
      if (!a || !root.contains(a)) return null;
      var r = { fk: a.getAttribute('data-fk') };
      if (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA') {
        try { r.s = a.selectionStart; r.e = a.selectionEnd; } catch (e) { /* not a text input */ }
      }
      return r;
    }
    function focusFk(fk, sel) {
      if (!fk) return false;
      var el = root.querySelector('[data-fk="' + fk + '"]');
      if (!el || el.disabled) return false;
      try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
      if (sel && sel.s != null && el.setSelectionRange) { try { el.setSelectionRange(sel.s, sel.e); } catch (e) { /* not a text input */ } }
      return document.activeElement === el;
    }
    function restoreFocus(r) {
      if (!r || !r.fk) return;
      var a = document.activeElement;
      if (a && root.contains(a) && a.getAttribute('data-fk') === r.fk) return;
      focusFk(r.fk, r);
    }
    function captureScroll() {
      var out = {};
      Array.prototype.forEach.call(root.querySelectorAll('[data-sk]'), function (el) { out[el.getAttribute('data-sk')] = el.scrollTop; });
      return out;
    }
    function restoreScroll(m) {
      Array.prototype.forEach.call(root.querySelectorAll('[data-sk]'), function (el) {
        var k = el.getAttribute('data-sk');
        if (m[k] != null) el.scrollTop = m[k];
      });
    }
    function focusFallback() {
      if (S.locked) { focusFk('lock-pw'); return; }
      if (!focusFk('nav-' + S.screen)) focusFk('nav-dashboard');
    }

    // ---------------------------------------------------------------- render
    function render() {
      if (destroyed) return;
      var f = captureFocus(), sc = captureScroll();
      mainEl.innerHTML = S.locked ? (S.lock.loading ? unlockingHtml() : lockHtml()) : shellHtml();
      layerEl.innerHTML = layerHtml();
      mainEl.inert = S.dialogs.length > 0;
      if (S.dialogs.length) mainEl.setAttribute('aria-hidden', 'true'); else mainEl.removeAttribute('aria-hidden');
      restoreScroll(sc);
      restoreFocus(f);
      placeMenu();
      paintScore();
    }
    function renderLayer() {
      var f = captureFocus(), sc = captureScroll();
      layerEl.innerHTML = layerHtml();
      mainEl.inert = S.dialogs.length > 0;
      if (S.dialogs.length) mainEl.setAttribute('aria-hidden', 'true'); else mainEl.removeAttribute('aria-hidden');
      restoreScroll(sc);
      restoreFocus(f);
      placeMenu();
    }

    // ================================================================= shell: Sidebar.kt + TopBar.kt + Main.kt
    function shellHtml() {
      return '<div class="vpd-shell">' + sidebarHtml() +
        '<div class="vpd-split" aria-hidden="true"></div>' +
        '<div class="vpd-right">' + topBarHtml() +
          '<div class="vpd-content">' + screenHtml() + '</div>' +
          '<div class="vpd-snackhost-main">' + snackHtml('main') + '</div>' +
        '</div></div>';
    }

    function navItem(screen, ic, label) {
      var sel = S.screen === screen;
      return '<button type="button" class="vpd-nav-item' + (sel ? ' is-selected' : '') + '" data-act="nav" data-screen="' + screen +
        '" data-fk="nav-' + screen + '"' + (sel ? ' aria-current="page"' : '') + '>' + icon(ic, 18) + '<span class="vpd-nav-label">' + label + '</span></button>';
    }

    function sidebarHtml() {
      return '<nav class="vpd-sidebar" aria-label="VaultPass">' +
        '<div class="vpd-logo"><span class="vpd-logo-mark">' + icon('shield', 22) + '</span>' +
          '<span class="vpd-logo-text"><span class="vpd-logo-name">VaultPass</span><span class="vpd-logo-sub">Offline Manager</span></span></div>' +
        '<div class="vpd-nav">' +
          navItem('dashboard', 'dashboard', 'Dashboard') + navItem('vault', 'lock', 'Vault') +
          navItem('security', 'security', 'Security Center') + navItem('generator', 'vpnKey', 'Generator') +
          navItem('trash', 'del', 'Recycle Bin') +
        '</div>' +
        '<div class="vpd-nav vpd-nav-bottom">' + navItem('settings', 'settings', 'Settings') +
          '<button type="button" class="vpd-nav-item" data-act="lock" data-fk="nav-lock">' + icon('lockClock', 18) + '<span class="vpd-nav-label">Lock Vault</span></button>' +
        '</div></nav>';
    }

    function topBarHtml() {
      var s = '<header class="vpd-topbar"><div class="vpd-topbar-title">' + TITLES[S.screen] + '</div>' +
        '<span class="vpd-topbar-pill">' + pillHtml() + '</span><span class="vpd-topbar-actions">';
      if (S.screen === 'dashboard') s += '<button type="button" class="vpd-topbar-lock" data-act="lock" data-fk="top-lock" aria-label="Lock Vault">' + icon('lock', 18) + '</button>';
      if (S.screen === 'dashboard' || S.screen === 'vault') s += '<button type="button" class="vpd-topbar-add" data-act="add" data-fk="top-add" aria-label="Add Entry">' + icon('add', 18) + '</button>';
      return s + '</span></header>';
    }

    function ring(size, stroke, frac, color, track, cls) {
      var r = (size - stroke) / 2, c = 2 * Math.PI * r, h = size / 2;
      return '<svg class="vpd-ring' + (cls ? ' ' + cls : '') + '" width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '" aria-hidden="true" focusable="false">' +
        '<circle cx="' + h + '" cy="' + h + '" r="' + r + '" fill="none" stroke="' + track + '" stroke-width="' + stroke + '"/>' +
        '<circle class="vpd-ring-arc" cx="' + h + '" cy="' + h + '" r="' + r + '" fill="none" stroke="' + color + '" stroke-width="' + stroke +
        '" stroke-dasharray="' + c.toFixed(3) + '" stroke-dashoffset="' + (c * (1 - Math.max(0, Math.min(1, frac)))).toFixed(3) + '" transform="rotate(-90 ' + h + ' ' + h + ')"/></svg>';
    }

    // TopBar clipboard countdown pill (ClipboardCountdown, label "Password").
    function pillHtml() {
      if (!S.clip.active) return '';
      return '<span class="vpd-pill">' + icon('lock', 14) +
        '<span class="vpd-pill-text">Password copied (' + S.clip.remaining + 's)</span>' +
        ring(14, 2, S.clip.remaining / S.clip.total, '#0EA5A1', '#2E2E2E', 'vpd-pill-ring') +
        '<button type="button" class="vpd-pill-clear" data-act="clip-clear" data-fk="pill-clear">Clear Now</button></span>';
    }
    function updatePill() {
      var slot = mainEl.querySelector('.vpd-topbar-pill');
      if (!slot) return;
      var pill = slot.querySelector('.vpd-pill');
      if (S.clip.active && pill) {
        pill.querySelector('.vpd-pill-text').textContent = 'Password copied (' + S.clip.remaining + 's)';
        var arc = pill.querySelector('.vpd-ring-arc');
        var c = parseFloat(arc.getAttribute('stroke-dasharray'));
        arc.setAttribute('stroke-dashoffset', (c * (1 - S.clip.remaining / S.clip.total)).toFixed(3));
        return;
      }
      var hadFocus = pill && pill.contains(document.activeElement);
      slot.innerHTML = pillHtml();
      if (hadFocus) focusFallback();
    }
    // VaultViewModel.copyPasswordToClipboard -> SecureClipboardManager.copySensitive. The demo writes the
    // sample value once, on the click, and never clears or changes the visitor's clipboard afterwards.
    function copySecret(text) {
      copyToClipboard(text);
      // The countdown pill sits outside the live region; a snackbar that follows replaces this message.
      announce('Password copied');
      var ttl = S.settings.clearClipboardTimeoutSeconds;
      clearTimer('clip');
      if (ttl <= 0) { S.clip.active = false; updatePill(); return; }
      S.clip = { active: true, remaining: ttl, total: ttl };
      updatePill();
      clipTick();
    }
    function clipTick() {
      setTimer('clip', function () {
        S.clip.remaining--;
        if (S.clip.remaining <= 0) S.clip.active = false; else clipTick();
        updatePill();
      }, 1000);
    }

    // ---------------------------------------------------------------- snackbars (M3 Snackbar)
    function snackHtml(host) {
      var s = S.snack[host];
      if (!s) return '';
      var html = '<div class="vpd-snackbar' + (s.fresh ? ' is-entering' : '') + '"><div class="vpd-snackbar-text">' + esc(s.msg) + '</div></div>';
      s.fresh = false;
      return html;
    }
    function snack(host, msg) {
      S.snack[host] = { msg: msg, id: ++snackSeq, fresh: true };
      setTimer('snack-' + host, function () { S.snack[host] = null; paintSnack(host); }, 4000);
      paintSnack(host);
      announce(msg);
    }
    function paintSnack(host) {
      var el = mainEl.querySelector(host === 'main' ? '.vpd-snackhost-main' : '.vpd-snackhost-screen');
      if (el) el.innerHTML = snackHtml(host);
    }

    function screenHtml() {
      switch (S.screen) {
        case 'dashboard': return dashboardHtml();
        case 'security': return securityHtml();
        case 'generator': return generatorHtml();
        case 'trash': return binHtml();
        case 'settings': return settingsHtml();
        default: return vaultHtml();
      }
    }

    function searchBox(kind, value, placeholder, cls) {
      return '<label class="vpd-search vpd-sqb' + (cls ? ' ' + cls : '') + '">' + icon('search', 16) +
        '<input type="text" class="vpd-search-input" data-field="search-' + kind + '" data-fk="search-' + kind + '" value="' + esc(value) +
        '" placeholder="' + placeholder + '" aria-label="' + placeholder.replace('...', '') + '" spellcheck="false" autocomplete="off"></label>';
    }

    // ================================================================= VaultScreen.kt
    function vaultFiltered() {
      var q = S.vaultQuery.trim();
      var list = active().filter(function (e) { return matches(e, q, ['title', 'username', 'website', 'notes']); });
      list.sort(function (a, b) { return cmp(lc(a.title), lc(b.title)); });
      if (!S.vault.sortAsc) list.reverse();
      return list.slice(0, 50);
    }
    function resolveSelection(list) {
      var has = function (id) { return list.some(function (e) { return e.uid === id; }); };
      var sel = S.vault.selectedId && has(S.vault.selectedId) ? S.vault.selectedId : null;
      if (!sel && S.settings.defaultViewRememberLastSelected && S.settings.lastSelectedEntryId && has(S.settings.lastSelectedEntryId)) sel = S.settings.lastSelectedEntryId;
      if (sel !== S.vault.selectedId) { S.vault.pwVisible = !S.settings.hidePasswordsByDefault; S.vault.cfVisible = {}; }
      S.vault.selectedId = sel;
    }

    function vaultHtml() {
      var list = vaultFiltered();
      resolveSelection(list);
      return '<div class="vpd-vault">' +
        '<div class="vpd-vault-listpane">' +
          '<div class="vpd-vault-toolbar">' + searchBox('vault', S.vaultQuery, 'Search vault...') +
            '<button type="button" class="vpd-sortbtn' + (S.vault.sortAsc ? ' is-on' : '') + '" data-act="sort" data-fk="vault-sort" aria-label="Sort" aria-pressed="' + S.vault.sortAsc + '">' + icon('sortByAlpha', 18) + '</button>' +
          '</div>' +
          '<div class="vpd-vault-list" data-sk="vault-list">' + vaultListHtml(list) + '</div>' +
        '</div>' +
        '<div class="vpd-pane-divider" aria-hidden="true"></div>' +
        '<div class="vpd-vault-detail" data-sk="vault-detail-' + sid(S.vault.selectedId || 'none') + '">' + detailHtml() + '</div>' +
        '<div class="vpd-snackhost-screen">' + snackHtml('screen') + '</div>' +
        '</div>';
    }

    function vaultListHtml(list) {
      if (!list.length) {
        return '<div class="vpd-empty">' + icon('searchOff', 48) + '<div class="vpd-empty-text">' +
          (S.vaultQuery.trim() ? 'No entries found' : 'Vault is empty') + '</div></div>';
      }
      return '<div class="vpd-rows" role="list">' + list.map(function (e) {
        var sel = e.uid === S.vault.selectedId, k = sid(e.uid);
        return '<div class="vpd-row' + (sel ? ' is-selected' : '') + '" role="listitem">' +
          '<button type="button" class="vpd-row-hit" data-act="row-select" data-row="vault" data-id="' + esc(e.uid) + '" data-fk="row-' + k + '"' +
            (sel ? ' aria-current="true"' : '') + ' aria-label="' + esc(displayTitle(e) + (isBlank(e.username) ? '' : ', ' + e.username)) + '"></button>' +
          '<span class="vpd-avatar">' + esc(initialOf(e)) + '</span>' +
          '<span class="vpd-row-text"><span class="vpd-row-title">' + esc(displayTitle(e)) + '</span>' +
            (isBlank(e.username) ? '' : '<span class="vpd-row-sub">' + esc(e.username) + '</span>') + '</span>' +
          '<span class="vpd-row-actions">' +
            '<button type="button" class="vpd-ib32 is-teal" data-act="copy-pw" data-id="' + esc(e.uid) + '" data-fk="row-copy-' + k + '" aria-label="Copy password for ' + esc(displayTitle(e)) + '">' + icon('contentCopy', 16) + '</button>' +
            '<button type="button" class="vpd-ib32" data-act="edit" data-id="' + esc(e.uid) + '" data-fk="row-edit-' + k + '" aria-label="Edit ' + esc(displayTitle(e)) + '">' + icon('edit', 16) + '</button>' +
          '</span></div>';
      }).join('') + '</div>';
    }

    function detailField(o) {
      var masked = o.secret && !o.visible;
      var s = '<div class="vpd-df"><div class="vpd-df-label">' + esc(o.label) + '</div><div class="vpd-df-box vpd-sqb">' +
        '<span class="vpd-df-value">' + (masked ? '<span aria-hidden="true">\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022</span><span class="vpd-sr">hidden</span>' : esc(o.value)) + '</span>';
      if (o.secret) {
        s += '<button type="button" class="vpd-ib32 is-muted" data-act="' + o.toggleAct + '"' + (o.index != null ? ' data-index="' + o.index + '"' : '') +
          ' data-fk="' + o.fk + '-vis" aria-label="Toggle Visibility" aria-pressed="' + !!o.visible + '">' + icon(o.visible ? 'visibility' : 'visibilityOff', 16) + '</button>';
      }
      s += '<button type="button" class="vpd-ib32 is-teal" data-act="' + o.copyAct + '"' + (o.index != null ? ' data-index="' + o.index + '"' : '') +
        ' data-fk="' + o.fk + '-copy" aria-label="Copy ' + esc(o.label) + '">' + icon('contentCopy', 16) + '</button>';
      if (o.open) s += '<button type="button" class="vpd-ib32 is-teal" data-act="open-site" data-fk="' + o.fk + '-open" aria-label="Open website">' + icon('openInNew', 16) + '</button>';
      return s + '</div></div>';
    }

    function detailHtml() {
      var e = byId(S.vault.selectedId);
      if (!e) return '<div class="vpd-detail-empty">Select an item to view details</div>';
      var s = '<div class="vpd-detail">' +
        '<div class="vpd-detail-head vpd-sqb"><span class="vpd-avatar vpd-avatar-48">' + esc(initialOf(e)) + '</span>' +
          '<div class="vpd-detail-head-text"><div class="vpd-detail-title">' + esc(displayTitle(e)) + '</div><div class="vpd-detail-kind">Login Item</div></div>' +
          '<button type="button" class="vpd-btn vpd-btn-teal vpd-detail-edit" data-act="edit" data-id="' + esc(e.uid) + '" data-fk="detail-edit">' + icon('edit', 16) + '<span>Edit</span></button>' +
          '<button type="button" class="vpd-ib vpd-detail-more" data-act="detail-more" data-fk="detail-more" aria-label="More options" aria-haspopup="menu" aria-expanded="' + !!(S.menu && S.menu.type === 'detailMore') + '">' + icon('moreVert', 24) + '</button>' +
        '</div>' +
        detailField({ label: 'Username', value: e.username, copyAct: 'copy-user-detail', fk: 'df-user' }) +
        detailField({ label: 'Password', value: e.password, secret: true, visible: S.vault.pwVisible, toggleAct: 'toggle-pw', copyAct: 'copy-pw-detail', fk: 'df-pw' }) +
        detailField({ label: 'Website', value: e.website, copyAct: 'copy-url', open: true, fk: 'df-url' });
      e.customFields.forEach(function (f, i) {
        var vis = S.vault.cfVisible[i] != null ? S.vault.cfVisible[i] : !S.settings.hidePasswordsByDefault;
        s += detailField({ label: f.label, value: f.value, secret: true, visible: vis, toggleAct: 'toggle-cf', copyAct: 'copy-cf', index: i, fk: 'df-cf' + i });
      });
      s += '<div class="vpd-df"><div class="vpd-df-label">Notes</div><div class="vpd-notes vpd-sqb' + (isBlank(e.notes) ? ' is-empty' : '') + '">' +
        (isBlank(e.notes) ? 'No notes provided.' : esc(e.notes)) + '</div></div>';
      return s + '</div>';
    }

    function refreshVaultBody() {
      var listEl = mainEl.querySelector('.vpd-vault-list');
      var detailEl = mainEl.querySelector('.vpd-vault-detail');
      if (!listEl || !detailEl) { render(); return; }
      var f = captureFocus();
      var list = vaultFiltered();
      resolveSelection(list);
      listEl.innerHTML = vaultListHtml(list);
      detailEl.setAttribute('data-sk', 'vault-detail-' + sid(S.vault.selectedId || 'none'));
      detailEl.innerHTML = detailHtml();
      restoreFocus(f);
    }

    // A click on a row repaints the selection without replacing the rows: a replaced row would stop the
    // browser from firing dblclick on the second click, and the app's double-click action needs it.
    function paintVaultSelection() {
      var listEl = mainEl.querySelector('.vpd-vault-list'), detailEl = mainEl.querySelector('.vpd-vault-detail');
      if (!listEl || !detailEl) { render(); return; }
      var shown = vaultFiltered();
      if (S.vault.selectedId && !shown.some(function (e) { return e.uid === S.vault.selectedId; })) { refreshVaultBody(); return; }
      Array.prototype.forEach.call(listEl.querySelectorAll('.vpd-row'), function (row) {
        var hit = row.querySelector('.vpd-row-hit'), on = !!hit && hit.getAttribute('data-id') === S.vault.selectedId;
        row.classList.toggle('is-selected', on);
        if (!hit) return;
        if (on) hit.setAttribute('aria-current', 'true'); else hit.removeAttribute('aria-current');
      });
      var f = captureFocus(), sk = 'vault-detail-' + sid(S.vault.selectedId || 'none');
      if (detailEl.getAttribute('data-sk') !== sk) { detailEl.setAttribute('data-sk', sk); detailEl.scrollTop = 0; }
      detailEl.innerHTML = detailHtml();
      restoreFocus(f);
    }

    function selectEntry(id) {
      if (S.vault.selectedId !== id) { S.vault.pwVisible = !S.settings.hidePasswordsByDefault; S.vault.cfVisible = {}; }
      S.vault.selectedId = id;
      if (S.settings.defaultViewRememberLastSelected) S.settings.lastSelectedEntryId = id;
    }

    // Copy actions in VaultScreen go through the clipboard-history warning while it is on. The warning's
    // buttons always say "Password copied"; `directMessage` is only for copies that skip the warning.
    function vaultCopySecret(secret, fk, directMessage) {
      if (S.settings.showClipboardHistoryWarning) {
        openDialog({ type: 'clipWarn', secret: secret }, fk);
        return;
      }
      copySecret(secret);
      snack('screen', directMessage || 'Password copied');
    }

    // ================================================================= DashboardScreen.kt
    function dashboardHtml() {
      var entries = active();
      var report = analyze(entries);
      var q = S.dashQuery;
      var filtered = q.trim() ? entries.filter(function (e) { return matches(e, q, ['title', 'username', 'website', 'notes']); }) : entries;
      var score = report.score, weak = report.weak.length, reused = report.reused.length;
      var stat = function (title, value, desc, ic, color) {
        return '<div class="vpd-stat vpd-sqb"><div class="vpd-stat-head"><span class="vpd-stat-title">' + title + '</span><span class="vpd-stat-icon" style="color:' + color + '">' + icon(ic, 18) + '</span></div>' +
          '<div class="vpd-stat-value">' + value + '</div><div class="vpd-stat-desc">' + desc + '</div></div>';
      };
      return '<div class="vpd-dash">' +
        '<div class="vpd-dash-head"><div><div class="vpd-screen-title">Dashboard</div><div class="vpd-screen-sub">Last Unlock: ' + fmtLong(S.lastUnlock) + '</div></div>' +
          searchBox('dash', S.dashQuery, 'Search vault...', 'is-320') + '</div>' +
        '<div class="vpd-stats">' +
          stat('Security Score', score + '%', 'Overall health score', 'shield', score >= 90 ? '#10B981' : score >= 70 ? '#FFB900' : '#FF6B6B') +
          stat('Total Items', entries.length, 'Secured passwords', 'lock', '#0EA5A1') +
          stat('Weak Passwords', weak, 'Vulnerable credentials', 'warning', weak === 0 ? '#10B981' : '#FF6B6B') +
          stat('Reused Passwords', reused, 'Shared secrets found', 'refresh', reused === 0 ? '#10B981' : '#FFB900') +
        '</div>' +
        '<div class="vpd-dash-cols">' +
          '<div class="vpd-dash-col is-wide"><div class="vpd-section-title">Recent Activity</div><div class="vpd-panel vpd-sqb" data-sk="dash-activity">' + dashActivityHtml(filtered) + '</div></div>' +
          '<div class="vpd-dash-col"><div class="vpd-section-title">Favorites</div><div class="vpd-panel vpd-sqb" data-sk="dash-favorites">' + dashFavoritesHtml(filtered) + '</div></div>' +
        '</div></div>';
    }
    function dashActivityHtml(filtered) {
      var recent = filtered.slice().sort(function (a, b) { return toMs(b.updatedAt) - toMs(a.updatedAt); }).slice(0, 3);
      if (!recent.length) return '<div class="vpd-panel-empty">No recent activity.</div>';
      return '<div class="vpd-panel-list">' + recent.map(function (e) {
        var added = toMs(e.createdAt) === toMs(e.updatedAt);
        return '<button type="button" class="vpd-activity" data-act="edit" data-id="' + esc(e.uid) + '" data-fk="act-' + sid(e.uid) + '">' +
          '<span class="vpd-activity-icon">' + icon(added ? 'add' : 'update', 16) + '</span>' +
          '<span class="vpd-activity-text"><span class="vpd-row-title">' + esc(displayTitle(e)) + '</span><span class="vpd-row-sub">' + (added ? 'Added to vault' : 'Updated password') + '</span></span>' +
          '<span class="vpd-activity-time">' + fmtShort(toMs(e.updatedAt)) + '</span></button>';
      }).join('') + '</div>';
    }
    function dashFavoritesHtml(filtered) {
      var favs = filtered.filter(function (e) { return e.favorite; }).slice(0, 6);
      if (!favs.length) return '<div class="vpd-panel-empty">No favorites yet.</div>';
      return '<div class="vpd-panel-list">' + favs.map(function (e) {
        return '<div class="vpd-fav">' +
          '<button type="button" class="vpd-fav-hit" data-act="edit" data-id="' + esc(e.uid) + '" data-fk="fav-' + sid(e.uid) + '" aria-label="' + esc(displayTitle(e)) + '"></button>' +
          '<span class="vpd-avatar">' + esc(displayTitle(e).charAt(0).toUpperCase()) + '</span>' +
          '<span class="vpd-activity-text"><span class="vpd-row-title">' + esc(displayTitle(e)) + '</span>' + (isBlank(e.username) ? '' : '<span class="vpd-row-sub">' + esc(e.username) + '</span>') + '</span>' +
          '<button type="button" class="vpd-ib32 is-teal" data-act="fav-copy" data-id="' + esc(e.uid) + '" data-fk="fav-copy-' + sid(e.uid) + '" aria-label="Copy Password">' + icon('contentCopy', 16) + '</button>' +
          '</div>';
      }).join('') + '</div>';
    }
    function refreshDashLists() {
      var a = mainEl.querySelector('[data-sk="dash-activity"]'), b = mainEl.querySelector('[data-sk="dash-favorites"]');
      if (!a || !b) { render(); return; }
      var entries = active(), q = S.dashQuery;
      var filtered = q.trim() ? entries.filter(function (e) { return matches(e, q, ['title', 'username', 'website', 'notes']); }) : entries;
      a.innerHTML = dashActivityHtml(filtered);
      b.innerHTML = dashFavoritesHtml(filtered);
    }

    // ================================================================= SecurityCenterScreen.kt
    function scoreColor(total, score) { return total === 0 ? '#888888' : score >= 90 ? '#10B981' : score >= 70 ? '#FFB900' : '#FF6B6B'; }
    function securityHtml() {
      var entries = active(), report = analyze(entries), score = report.score;
      var color = scoreColor(entries.length, score);
      var issues = [], seen = {};
      report.weak.forEach(function (e) { if (!seen[e.uid]) { seen[e.uid] = 1; issues.push([e, true]); } });
      report.reused.forEach(function (e) { if (!seen[e.uid]) { seen[e.uid] = 1; issues.push([e, false]); } });
      var desc = entries.length === 0 ? 'Add some passwords to generate a report.' :
        score === 100 ? 'Your vault meets all security recommendations.' : 'Review and fix weak or reused passwords to secure your vault.';
      var shown = S.sec.shown;
      var s = '<div class="vpd-sec">' +
        '<div class="vpd-sec-card vpd-sqb"><div class="vpd-score" style="color:' + color + '">' +
          ring(72, 6, shown / 100, color, hexAlpha(color, 0.1), 'vpd-score-ring') +
          '<span class="vpd-score-num">' + Math.round(shown) + '</span></div>' +
          '<div class="vpd-sec-card-text"><div class="vpd-section-title is-flat">Security Score</div><div class="vpd-sec-desc">' + desc + '</div></div></div>' +
        '<div class="vpd-section-title">Vulnerabilities &amp; Issues</div>' +
        '<div class="vpd-sec-box vpd-sqb">';
      if (!issues.length) {
        s += '<div class="vpd-sec-ok">' + icon('checkCircle', 48) + '<div>No security issues detected</div></div>';
      } else {
        s += '<div class="vpd-sec-list" data-sk="sec-list">' + issues.map(function (it) {
          var e = it[0], weak = it[1];
          return '<div class="vpd-issue vpd-sqb"><div class="vpd-issue-text"><div class="vpd-issue-head"><span class="vpd-row-title">' + esc(displayTitle(e)) + '</span>' +
            '<span class="vpd-badge ' + (weak ? 'is-weak' : 'is-reused') + '">' + (weak ? 'Weak' : 'Reused') + '</span></div>' +
            '<div class="vpd-issue-desc">' + (weak ? 'This password is easy to guess. Update it with a strong, randomized password.' :
              'This password is used across multiple accounts. Reusing passwords increases breach risks.') + '</div></div>' +
            '<button type="button" class="vpd-btn vpd-btn-dim vpd-btn-sm" data-act="fix" data-id="' + esc(e.uid) + '" data-fk="fix-' + sid(e.uid) + '">Fix</button></div>';
        }).join('') + '</div>';
      }
      return s + '</div></div>';
    }
    function hexAlpha(hex, a) {
      var v = parseInt(hex.slice(1), 16);
      return 'rgba(' + ((v >> 16) & 255) + ',' + ((v >> 8) & 255) + ',' + (v & 255) + ',' + a + ')';
    }
    // Animatable(0f).animateTo(score, tween(1500, FastOutSlowInEasing)) on every visit.
    function startScoreAnim(from) {
      if (rafId) { cancelAnimationFrame(rafId); rafId = 0; }
      var target = analyze(active()).score;
      if (reducedMotion()) { S.sec.shown = target; paintScore(); return; }
      var t0 = 0, start = from;
      var ease = cubicBezier(0.4, 0, 0.2, 1);
      var step = function (ts) {
        if (destroyed) return;
        if (!t0) t0 = ts;
        var p = Math.min(1, (ts - t0) / 1500);
        S.sec.shown = start + (target - start) * ease(p);
        paintScore();
        rafId = p < 1 ? requestAnimationFrame(step) : 0;
      };
      rafId = requestAnimationFrame(step);
    }
    function paintScore() {
      if (S.locked || S.screen !== 'security') return;
      var num = mainEl.querySelector('.vpd-score-num'), arc = mainEl.querySelector('.vpd-score-ring .vpd-ring-arc');
      if (!num || !arc) return;
      num.textContent = String(Math.round(S.sec.shown));
      var c = parseFloat(arc.getAttribute('stroke-dasharray'));
      arc.setAttribute('stroke-dashoffset', (c * (1 - S.sec.shown / 100)).toFixed(3));
    }
    function cubicBezier(x1, y1, x2, y2) {
      var bx = function (t) { return 3 * x1 * t * (1 - t) * (1 - t) + 3 * x2 * t * t * (1 - t) + t * t * t; };
      var by = function (t) { return 3 * y1 * t * (1 - t) * (1 - t) + 3 * y2 * t * t * (1 - t) + t * t * t; };
      return function (x) {
        var lo = 0, hi = 1, t = x;
        for (var i = 0; i < 24; i++) { t = (lo + hi) / 2; if (bx(t) < x) lo = t; else hi = t; }
        return by(t);
      };
    }

    // ================================================================= GeneratorScreen.kt
    function newGen() {
      var g = { mode: 'password', length: 16, upper: true, lower: true, numbers: true, symbols: true, ambiguous: false,
        words: 5, sep: '-', cap: true, num: true, current: null, history: [] };
      g.current = makeSecret(g);
      return g;
    }
    function makeSecret(g) { return g.mode === 'password' ? generatePassword(g) : generatePassphrase(g); }
    function regenerate() {
      var g = S.gen, next = makeSecret(g);
      if (g.current && g.current.value && g.current.value !== next.value) {
        g.history.unshift(g.current.value);
        if (g.history.length > 10) g.history.pop();
      }
      g.current = next;
    }
    function genSwitch(label, key, on) {
      return '<button type="button" class="vpd-optrow vpd-sqb" role="switch" aria-checked="' + on + '" data-act="gen-toggle" data-key="' + key + '" data-fk="gen-' + key + '">' +
        '<span class="vpd-optrow-label">' + label + '</span>' + switchVisual(on) + '</button>';
    }
    function switchVisual(on) { return '<span class="vpd-switch' + (on ? ' is-on' : '') + '" aria-hidden="true"><span class="vpd-switch-thumb"></span></span>'; }
    function slider(key, value, min, max, label) {
      var steps = max - min, f = (value - min) / steps, ticks = '';
      for (var i = 0; i <= steps; i++) ticks += '<span class="vpd-tick' + (i / steps <= f ? ' is-active' : '') + '" style="--p:' + (i / steps).toFixed(5) + '"></span>';
      return '<div class="vpd-slider" style="--f:' + f.toFixed(5) + '"><div class="vpd-slider-track"><span class="vpd-slider-active"></span>' + ticks + '</div>' +
        '<input type="range" class="vpd-slider-input" min="' + min + '" max="' + max + '" step="1" value="' + value + '" data-field="gen-' + key + '" data-fk="gen-slider-' + key + '" aria-label="' + label + '"></div>';
    }
    function genPreviewHtml() {
      var g = S.gen, c = g.current, frac = Math.max(0.05, Math.min(1, c.bits / 128));
      return '<div class="vpd-gen-label">' + (g.mode === 'password' ? 'Generated Password' : 'Generated Passphrase') + '</div>' +
        '<div class="vpd-gen-value">' + esc(c.value) + '</div>' +
        '<div class="vpd-entropy"><div class="vpd-entropy-row"><span class="vpd-entropy-label" style="color:' + c.rating.color + '">' + Math.floor(c.bits) + ' bits \u2014 ' + c.rating.label + '</span>' +
          '<span class="vpd-entropy-crack">Crack time: ' + c.rating.crack + '</span></div>' +
          '<div class="vpd-bar"><span class="vpd-bar-fill" style="width:' + (frac * 100).toFixed(2) + '%;background:' + c.rating.color + '"></span></div></div>' +
        '<div class="vpd-gen-actions">' +
          '<button type="button" class="vpd-gen-refresh" data-act="gen-regen" data-fk="gen-regen" aria-label="Regenerate">' + icon('refresh', 18) + '</button>' +
          '<button type="button" class="vpd-btn vpd-btn-dim vpd-btn-lg" data-act="gen-copy" data-fk="gen-copy">' + icon('contentCopy', 16) + '<span>Copy</span></button>' +
          '<button type="button" class="vpd-btn vpd-btn-teal vpd-btn-lg" data-act="gen-use" data-fk="gen-use">' + icon('done', 16) + '<span>' + (g.mode === 'password' ? 'Use Password' : 'Use Passphrase') + '</span></button>' +
        '</div>';
    }
    function genHistoryHtml() {
      var h = S.gen.history;
      if (!h.length) return '<div class="vpd-gen-nohistory">No history yet.</div>';
      return '<div class="vpd-gen-hlist">' + h.map(function (v, i) {
        return '<div class="vpd-hrow vpd-sqb"><span class="vpd-hrow-value">' + esc(v) + '</span>' +
          '<button type="button" class="vpd-ib32 is-muted" data-act="gen-hcopy" data-index="' + i + '" data-fk="gen-h' + i + '" aria-label="Copy">' + icon('contentCopy', 14) + '</button></div>';
      }).join('') + '</div>';
    }
    function generatorHtml() {
      if (!S.gen) S.gen = newGen();
      var g = S.gen, pw = g.mode === 'password';
      var s = '<div class="vpd-gen" data-sk="gen">' +
        '<div class="vpd-gen-head"><div><div class="vpd-screen-title">Password &amp; Passphrase Generator</div>' +
          '<div class="vpd-screen-sub vpd-gen-sub">Generate cryptographically secure passwords and Diceware passphrases.</div></div>' +
          '<div class="vpd-seg vpd-sqb" role="radiogroup" aria-label="Generator mode">' +
            '<button type="button" class="vpd-seg-item' + (pw ? ' is-on' : '') + '" role="radio" aria-checked="' + pw + '" tabindex="' + (pw ? 0 : -1) + '" data-act="gen-mode" data-mode="password" data-fk="gen-mode-password">Password</button>' +
            '<button type="button" class="vpd-seg-item' + (!pw ? ' is-on' : '') + '" role="radio" aria-checked="' + !pw + '" tabindex="' + (pw ? -1 : 0) + '" data-act="gen-mode" data-mode="passphrase" data-fk="gen-mode-passphrase">Passphrase</button>' +
          '</div></div>' +
        '<div class="vpd-gen-card vpd-sqb">' + genPreviewHtml() + '</div>' +
        '<div class="vpd-section-title vpd-gen-config">Configuration</div>';
      if (pw) {
        s += '<div class="vpd-gen-slidercard vpd-sqb"><div class="vpd-gen-sliderhead"><span>Length</span><span class="vpd-gen-count vpd-sqb">' + g.length + '</span></div>' +
          slider('length', g.length, 8, 64, 'Length') + '</div>' +
          '<div class="vpd-optlist">' +
            genSwitch('Uppercase Letters (A-Z)', 'upper', g.upper) + genSwitch('Lowercase Letters (a-z)', 'lower', g.lower) +
            genSwitch('Numbers (0-9)', 'numbers', g.numbers) + genSwitch('Symbols (!@#$%)', 'symbols', g.symbols) +
            genSwitch('Avoid Ambiguous Characters (0/O, 1/l/I, |)', 'ambiguous', g.ambiguous) +
          '</div>';
      } else {
        s += '<div class="vpd-gen-slidercard vpd-sqb"><div class="vpd-gen-sliderhead"><span>Word Count</span><span class="vpd-gen-count vpd-sqb">' + g.words + ' words</span></div>' +
          slider('words', g.words, 3, 8, 'Word Count') + '</div>' +
          '<div class="vpd-sepcard vpd-sqb"><span class="vpd-optrow-label">Word Separator</span><span class="vpd-chips" role="radiogroup" aria-label="Word Separator">' +
          [['-', '-'], ['.', '.'], ['_', '_'], [' ', 'Space']].map(function (p, i) {
            var on = g.sep === p[0];
            return '<button type="button" class="vpd-chip vpd-sqb' + (on ? ' is-on' : '') + '" role="radio" aria-checked="' + on + '" tabindex="' + (on ? 0 : -1) + '" data-act="gen-sep" data-index="' + i + '" data-fk="gen-sep' + i + '"' +
              (p[0] === ' ' ? '' : ' aria-label="' + ['Hyphen', 'Period', 'Underscore'][i] + '"') + '>' + p[1] + '</button>';
          }).join('') + '</span></div>' +
          '<div class="vpd-optlist">' + genSwitch('Capitalize Words', 'cap', g.cap) + genSwitch('Include Random Number', 'num', g.num) + '</div>';
      }
      s += '<div class="vpd-section-title vpd-gen-historytitle">History</div><div class="vpd-gen-history vpd-sqb">' + genHistoryHtml() + '</div></div>';
      return s;
    }
    function refreshGenPartial() {
      var card = mainEl.querySelector('.vpd-gen-card'), hist = mainEl.querySelector('.vpd-gen-history');
      if (!card || !hist) { render(); return; }
      var f = captureFocus();
      card.innerHTML = genPreviewHtml();
      hist.innerHTML = genHistoryHtml();
      restoreFocus(f);
    }

    // ================================================================= RecycleBinScreen.kt
    function binFiltered() {
      var q = S.binQuery.trim();
      return deleted().filter(function (e) { return matches(e, q, ['title', 'username']); })
        .sort(function (a, b) { return toMs(b.deletedAt || b.updatedAt) - toMs(a.deletedAt || a.updatedAt); });
    }
    // The selection only keeps entries the current search still shows.
    function pruneBinSelection(list) {
      var sel = {};
      Object.keys(S.bin.selected).forEach(function (id) { if (list.some(function (e) { return e.uid === id; })) sel[id] = true; });
      S.bin.selected = sel;
    }
    function binEmptyButtonHtml(list) {
      return list.length ? '<button type="button" class="vpd-btn vpd-btn-dim vpd-btn-danger vpd-bin-empty" data-act="bin-empty" data-fk="bin-empty">' + icon('deleteSweep', 16) + '<span>Empty Bin</span></button>' : '';
    }
    function binHtml() {
      var list = binFiltered(), days = S.settings.recycleBinRetentionDays;
      pruneBinSelection(list);
      return '<div class="vpd-bin">' +
        '<div class="vpd-bin-head"><div class="vpd-bin-headtext"><div class="vpd-screen-title">Recycle Bin</div><div class="vpd-screen-sub">' +
          (days > 0 ? 'Items deleted from your vault. Entries older than ' + days + ' days are automatically purged forever.' : 'Items deleted from your vault. Auto-delete is disabled.') + '</div></div>' +
          searchBox('bin', S.binQuery, 'Search bin...', 'is-320') +
          '<span class="vpd-bin-emptyslot">' + binEmptyButtonHtml(list) + '</span>' +
        '</div>' +
        '<div class="vpd-bin-body">' + binBodyHtml(list) + '</div>' +
        '<div class="vpd-snackhost-screen">' + snackHtml('screen') + '</div></div>';
    }
    function binBodyHtml(list) {
      var days = S.settings.recycleBinRetentionDays;
      var count = Object.keys(S.bin.selected).length;
      var s = '';
      if (count) {
        var all = list.length && count === list.length;
        s += '<div class="vpd-binbar"><span class="vpd-binbar-count">' + count + ' item(s) selected</span>' +
          '<button type="button" class="vpd-btn vpd-btn-outline vpd-btn-xs" data-act="bin-selectall" data-fk="bin-selectall">' + (all ? 'Deselect All' : 'Select All') + '</button>' +
          '<span class="vpd-flex"></span>' +
          '<button type="button" class="vpd-btn vpd-btn-teal vpd-btn-xs" data-act="bin-restore-sel" data-fk="bin-restore-sel">' + icon('restore', 16) + '<span>Restore (' + count + ')</span></button>' +
          '<button type="button" class="vpd-btn vpd-btn-xs vpd-btn-darkred" data-act="bin-delete-sel" data-fk="bin-delete-sel">' + icon('deleteForever', 16) + '<span>Delete Forever (' + count + ')</span></button>' +
          '</div>';
      }
      if (!list.length) {
        s += '<div class="vpd-empty">' + icon('deleteOutline', 48) + '<div class="vpd-empty-text">Recycle Bin is empty</div></div>';
      } else {
        var now = Date.now();
        s += '<div class="vpd-bin-list" data-sk="bin-list" role="list">' + list.map(function (e) {
          var on = !!S.bin.selected[e.uid], k = sid(e.uid), del = toMs(e.deletedAt);
          var badge = '';
          if (days > 0 && e.deletedAt) {
            var left = Math.max(0, days - Math.floor((now - del) / DAY_MS));
            badge = left === 0 ? '<span class="vpd-exp is-today">Expires today</span>' :
              left === 1 ? '<span class="vpd-exp is-tomorrow">Expires tomorrow</span>' : '<span class="vpd-exp">Expires in ' + left + ' days</span>';
          } else if (days === 0) {
            badge = '<span class="vpd-noexp">No auto-expiry</span>';
          }
          return '<div class="vpd-binrow' + (on ? ' is-selected' : '') + '" role="listitem">' +
            '<button type="button" class="vpd-row-hit" role="checkbox" aria-checked="' + on + '" data-act="bin-toggle" data-row="bin" data-id="' + esc(e.uid) + '" data-fk="bin-' + k + '" aria-label="' + esc(displayTitle(e)) + '"></button>' +
            '<span class="vpd-checkbox' + (on ? ' is-on' : '') + '" aria-hidden="true">' + (on ? icon('check', 16) : '') + '</span>' +
            '<span class="vpd-binrow-main"><span class="vpd-binrow-icon">' + icon('del', 16) + '</span><span class="vpd-row-text"><span class="vpd-row-title">' + esc(displayTitle(e)) + '</span>' +
              (isBlank(e.username) ? '' : '<span class="vpd-row-sub">' + esc(e.username) + '</span>') + '</span></span>' +
            '<span class="vpd-binrow-date"><span class="vpd-binrow-day">' + (e.deletedAt ? fmtDay(del) : 'Unknown') + '</span>' + badge + '</span>' +
            '<span class="vpd-binrow-actions">' +
              '<button type="button" class="vpd-ib32 vpd-ib-filled is-teal" data-act="bin-restore" data-id="' + esc(e.uid) + '" data-fk="bin-restore-' + k + '" aria-label="Restore ' + esc(displayTitle(e)) + '">' + icon('restore', 16) + '</button>' +
              '<button type="button" class="vpd-ib32 vpd-ib-filled is-red" data-act="bin-delete" data-id="' + esc(e.uid) + '" data-fk="bin-delete-' + k + '" aria-label="Delete Forever ' + esc(displayTitle(e)) + '">' + icon('deleteForever', 16) + '</button>' +
            '</span></div>';
        }).join('') + '</div>';
      }
      return s;
    }
    // Search and selection changes repaint the list, the selection bar and the Empty Bin button only,
    // so the search field (and any IME composition in it) stays in place.
    function refreshBinBody() {
      var slot = mainEl.querySelector('.vpd-bin-emptyslot'), body = mainEl.querySelector('.vpd-bin-body');
      if (!slot || !body) { render(); return; }
      var f = captureFocus(), sc = captureScroll();
      var list = binFiltered();
      pruneBinSelection(list);
      slot.innerHTML = binEmptyButtonHtml(list);
      body.innerHTML = binBodyHtml(list);
      restoreScroll(sc);
      restoreFocus(f);
    }

    // ================================================================= SettingsScreen.kt
    function toggleRow(title, desc, key, checked) {
      return '<div class="vpd-setrow"><div class="vpd-setrow-text"><div class="vpd-setrow-title" id="' + idp + 'set-' + key + '">' + title + '</div><div class="vpd-setrow-desc">' + desc + '</div></div>' +
        '<button type="button" class="vpd-switchbtn" role="switch" aria-checked="' + checked + '" aria-labelledby="' + idp + 'set-' + key + '" data-act="set-toggle" data-key="' + key + '" data-fk="set-' + key + '">' + switchVisual(checked) + '</button></div>';
    }
    function dropdownRow(name) {
      var d = DROPDOWNS[name], v = S.settings[d.key], idx = d.values.indexOf(v);
      var current = idx >= 0 ? d.options[idx] : (typeof v === 'number' ? v + (name === 'clipboard' ? ' Seconds' : name === 'retention' ? ' Days' : ' Minutes') : String(v));
      var open = S.menu && S.menu.type === 'settingsDrop' && S.menu.name === name;
      return '<div class="vpd-setrow"><div class="vpd-setrow-text"><div class="vpd-setrow-title" id="' + idp + 'dd-' + name + '">' + d.title + '</div><div class="vpd-setrow-desc">' + d.desc + '</div></div>' +
        '<button type="button" class="vpd-btn vpd-btn-dd" id="' + idp + 'ddb-' + name + '" data-act="set-dd" data-name="' + name + '" data-fk="dd-' + name + '" aria-haspopup="menu" aria-expanded="' + !!open + '" aria-labelledby="' + idp + 'dd-' + name + ' ' + idp + 'ddb-' + name + '">' + esc(current) + '</button></div>';
    }
    function infoRow(title, desc) {
      return '<div class="vpd-setrow-text"><div class="vpd-setrow-title">' + title + '</div><div class="vpd-setrow-desc">' + esc(desc) + '</div></div>';
    }
    function card(inner, cls) { return '<div class="vpd-setcard' + (cls ? ' ' + cls : '') + '">' + inner + '</div>'; }
    // Buttons that open a Windows file dialog or Explorer in the app: drawn as the app draws them (enabled);
    // in the demo they explain what the app does in a snackbar.
    var FILE_DIALOG_MSG = {
      save: 'Demo: the app opens a Windows save dialog here',
      open: 'Demo: the app opens a Windows file picker here',
      folder: 'Demo: the app opens the backup folder in File Explorer'
    };
    function actionRow(title, desc, button, kind, fk) {
      return '<div class="vpd-setrow"><div class="vpd-setrow-text"><div class="vpd-setrow-title">' + title + '</div><div class="vpd-setrow-desc">' + desc + '</div></div>' +
        '<button type="button" class="vpd-btn vpd-btn-grey vpd-btn-xs vpd-setrow-btn" data-act="file-dialog" data-kind="' + kind + '" data-fk="' + fk + '">' + button + '</button></div>';
    }

    function settingsHtml() {
      var cat = S.settingsCat;
      var catTitle = SETTINGS_CATS.filter(function (c) { return c[0] === cat; })[0][1];
      return '<div class="vpd-settings">' +
        '<div class="vpd-settings-nav"><div class="vpd-settings-navtitle">Settings</div><div role="tablist" aria-orientation="vertical" aria-label="Settings categories">' +
          SETTINGS_CATS.map(function (c) {
            var on = c[0] === cat;
            return '<button type="button" class="vpd-setcat' + (on ? ' is-selected' : '') + '" role="tab" aria-selected="' + on + '" tabindex="' + (on ? 0 : -1) + '" aria-controls="' + idp + 'setpanel" data-act="set-cat" data-cat="' + c[0] + '" data-fk="setcat-' + c[0] + '">' + c[1] + '</button>';
          }).join('') + '</div></div>' +
        '<div class="vpd-pane-divider" aria-hidden="true"></div>' +
        '<div class="vpd-settings-content" id="' + idp + 'setpanel" role="tabpanel" aria-label="' + catTitle + '">' +
          '<div class="vpd-settings-title">' + catTitle + '</div>' +
          '<div class="vpd-settings-list" data-sk="settings-' + cat + '">' + settingsCatHtml(cat) + '</div>' +
        '</div>' +
        '<div class="vpd-snackhost-screen">' + snackHtml('screen') + '</div></div>';
    }

    function settingsCatHtml(cat) {
      var st = S.settings;
      if (cat === 'Security') {
        return card(
          toggleRow('Require Master Password on Startup', 'Ask for the master password when VaultPass starts. When off, other programs running under your Windows account can open your vault until you lock it.', 'requireMasterPasswordOnStartup', st.requireMasterPasswordOnStartup) +
          toggleRow('Lock When Minimized', 'Automatically lock the vault immediately whenever the window is minimized.', 'lockWhenMinimized', st.lockWhenMinimized) +
          dropdownRow('autoLock') +
          toggleRow('Lock on System Lock (Win+L)', "Automatically lock the vault when Windows locks: Win+L, or Windows locking itself, for example on sleep if it's set to.", 'lockOnWorkstationLock', st.lockOnWorkstationLock) +
          toggleRow('Clear Search Filters on Lock', 'Reset active search queries and filters when the vault locks.', 'clearSearchOnLock', st.clearSearchOnLock) +
          '<div class="vpd-divider"></div>' +
          dropdownRow('clipboard') +
          infoRow('Windows Clipboard Protection', 'Copied passwords are marked so Windows 10/11 leaves them out of Clipboard History (Win+V) and Cloud Clipboard sync. Usernames and URLs are copied normally.'),
          'is-stack16');
      }
      if (cat === 'Vault') {
        return card(dropdownRow('doubleClick') + dropdownRow('retention'), 'is-stack16') +
          // AppStorageConfig: the installer's per-user {app}\data folder ({autopf} = AppData\Local\Programs).
          card(infoRow('Vault Database Location', 'C:\\Users\\' + owner + '\\AppData\\Local\\Programs\\VaultPass\\data\\vaultpass.db'));
      }
      if (cat === 'Sync') return lanSyncHtml();
      if (cat === 'Backup') {
        var ex = '<div class="vpd-card-title">Export Vault Data</div><div class="vpd-setrow-desc">Export your vault data to encrypted backup, Android-compatible JSON, standard CSV, or plain text.</div>' +
          '<div role="radiogroup" aria-label="Export format" class="vpd-radios">' + EXPORT_FORMATS.map(function (f) {
            var on = S.exportFormat === f[0];
            return '<button type="button" class="vpd-radiorow" role="radio" aria-checked="' + on + '" tabindex="' + (on ? 0 : -1) + '" data-act="set-export" data-format="' + f[0] + '" data-fk="exp-' + f[0] + '">' +
              '<span class="vpd-radio' + (on ? ' is-on' : '') + '" aria-hidden="true"></span><span class="vpd-radiorow-text"><span class="vpd-radiorow-title">' + f[1] + '</span><span class="vpd-radiorow-sub">' + f[2] + '</span></span></button>' +
              // Off by default, so other password managers import the values exactly
              (f[0] === 'Csv' && on ? '<button type="button" class="vpd-excel" role="checkbox" aria-checked="' + S.csvProtect + '" data-act="csv-protect" data-fk="csv-protect">' +
                '<span class="vpd-checkbox is-teal' + (S.csvProtect ? ' is-on' : '') + '" aria-hidden="true">' + (S.csvProtect ? icon('check', 16) : '') + '</span>' +
                '<span class="vpd-radiorow-text"><span class="vpd-excel-title">Protect for Excel</span>' +
                "<span class=\"vpd-excel-sub\">Starts cells such as =, +, - or @ with ' so a spreadsheet won't run them. Leave off to import into another password manager.</span></span></button>" : '');
          }).join('') + '</div>' +
          '<button type="button" class="vpd-btn vpd-btn-teal vpd-btn-pill vpd-btn-end" data-act="export" data-fk="export">Export</button>';
        var im = '<div class="vpd-card-title">Import Vault Data</div><div class="vpd-setrow-desc">Import passwords from VaultPass Android/Desktop, Bitwarden, KeePass, or standard CSV.</div>' +
          '<div role="radiogroup" aria-label="Import format" class="vpd-radios is-tight">' + IMPORT_OPTIONS.map(function (t, i) {
            var on = S.importOption === i;
            return '<button type="button" class="vpd-radiorow is-compact" role="radio" aria-checked="' + on + '" tabindex="' + (on ? 0 : -1) + '" data-act="set-import" data-index="' + i + '" data-fk="imp-' + i + '">' +
              '<span class="vpd-radio' + (on ? ' is-on' : '') + '" aria-hidden="true"></span><span class="vpd-radiorow-plain' + (on ? ' is-on' : '') + '">' + t + '</span></button>';
          }).join('') + '</div>' +
          '<button type="button" class="vpd-btn vpd-btn-teal vpd-btn-pill vpd-btn-end" data-act="file-dialog" data-kind="open" data-fk="import">Import</button>';
        var ops = '<div class="vpd-card-title is-14">Desktop Vault Backup Operations</div>' +
          actionRow('Create .vpb Encrypted Vault Backup', 'Export all vault entries to an encrypted desktop .vpb backup file.', 'Export Backup', 'save', 'backup-export') +
          actionRow('Restore from .vpb Vault Backup', 'Restore passwords from a desktop .vpb backup file.', 'Restore Backup', 'open', 'backup-restore') +
          actionRow('Open Backup Directory', 'Open the local folder where desktop backups are saved.', 'Open Folder', 'folder', 'backup-folder');
        return card(ex, 'is-stack16') + card(im, 'is-stack16') + card(ops, 'is-stack16');
      }
      if (cat === 'Privacy') {
        var head = '<div class="vpd-priv-head"><div><div class="vpd-priv-title">Privacy Policy for VaultPass Desktop</div><div class="vpd-priv-date">Effective Date: September 10, 2026</div></div>' +
          '<a class="vpd-btn vpd-btn-outline vpd-btn-xs is-teal" href="https://github.com/ArmaanCode2/vault-pass-desktop/blob/master/PRIVACY.md" target="_blank" rel="noopener noreferrer">' + icon('share', 14) + '<span>View on GitHub</span></a></div>' +
          '<p class="vpd-priv-lead">VaultPass Desktop is engineered from the ground up as a local-first, zero-cloud password manager. We believe your private credentials belong exclusively to you. The Application operates entirely on your personal machine and does not require a user account, cloud subscription, or continuous internet connection to function.</p>' +
          '<div class="vpd-priv-badges"><span>Zero Cloud</span><span>Zero Telemetry</span><span>100% Local Storage</span><span>Open Source</span></div>';
        var out = card(head, 'is-stack14');
        PRIVACY_SECTIONS.forEach(function (sec, si) {
          var inner = '<div class="vpd-card-title is-14">' + esc(sec[0]) + '</div><div class="vpd-priv-items">' + sec[1].map(function (it) {
            return '<div class="vpd-priv-item"><div class="vpd-priv-itemtitle">' + esc(it[0]) + '</div><div class="vpd-priv-itemdesc">' + esc(it[1]) + '</div></div>';
          }).join('<div class="vpd-priv-div"></div>');
          if (si === PRIVACY_SECTIONS.length - 1) {
            inner += '<div class="vpd-priv-contact">' +
              '<a class="vpd-btn vpd-btn-outline vpd-btn-plainout" href="https://github.com/ArmaanCode2/vault-pass-desktop/issues" target="_blank" rel="noopener noreferrer">' + icon('info', 14) + '<span>GitHub Issues</span></a>' +
              '<a class="vpd-btn vpd-btn-outline vpd-btn-plainout" href="mailto:armaanweb100@gmail.com?subject=VaultPass%20Desktop%20Privacy%20Inquiry">' + icon('email', 14) + '<span>Email Developer</span></a></div>';
          }
          out += card(inner + '</div>', 'is-stack14');
        });
        return out;
      }
      if (cat === 'Appearance') {
        return card('<div class="vpd-card-title is-14">Appearance</div><div class="vpd-appearance-note">Dark Mode is active.</div>', 'is-stack16');
      }
      // About
      var checking = S.update.state === 'checking';
      var about = '<div class="vpd-about-head"><div><div class="vpd-about-name">VaultPass Desktop</div><div class="vpd-about-version">Version ' + esc(appVersion) + '</div></div>' +
        '<button type="button" class="vpd-btn vpd-btn-teal vpd-btn-xs vpd-btn-wide" data-act="check-updates" data-fk="check-updates"' + (checking ? ' disabled' : '') + '>' +
          (checking ? spinner(14, 2, 'vpd-spin-black') + '<span>Checking...</span>' : icon('refresh', 15) + '<span>Check for Updates</span>') + '</button></div>' +
        '<div class="vpd-appearance-note">Zero Cloud. Zero Telemetry. 100% Local Vault.</div>' +
        '<div class="vpd-about-credit">Passphrases use the EFF Large Wordlist by the Electronic Frontier Foundation (eff.org), licensed under CC BY 3.0 US.</div><div class="vpd-divider"></div>' +
        toggleRow('Check for updates when VaultPass opens', 'Asks GitHub for a newer version. Nothing from your vault is sent.', 'checkForUpdatesOnOpen', st.checkForUpdatesOnOpen);
      var outAbout = '<div class="vpd-about">' + card(about, 'is-stack12');
      if (S.update.state === 'uptodate') {
        outAbout += '<div class="vpd-about-panel">' + icon('checkCircle', 20) + '<div><div class="vpd-about-paneltitle">You are using the latest version</div>' +
          '<div class="vpd-about-panelsub">Checked at ' + fmtLong(S.update.checkedAt) + '</div></div></div>';
      }
      return outAbout + '</div>';
    }

    function lanSyncHtml() {
      var head = '<div class="vpd-sync-head"><div class="vpd-sync-headtext"><div class="vpd-card-title">LAN Sync &amp; Paired Devices</div><div class="vpd-setrow-desc vpd-sync-desc">Synchronize your vault peer-to-peer over your local Wi-Fi or mobile hotspot with zero cloud dependencies.</div></div>' +
        '<div class="vpd-sync-buttons"><button type="button" class="vpd-btn vpd-btn-outline vpd-btn-plainout vpd-btn-xs8" data-act="sync-refresh" data-fk="sync-refresh">' + icon('refresh', 16) + '<span>Refresh</span></button>' +
        '<button type="button" class="vpd-btn vpd-btn-teal vpd-btn-xs8 is-bold" data-act="pair" data-fk="pair">' + icon('phoneAndroid', 16) + '<span>Pair Mobile Device</span></button></div></div><div class="vpd-divider"></div>';
      if (!S.paired.length) {
        return card(head + '<div class="vpd-sync-empty">' + icon('phoneAndroid', 40) + '<div class="vpd-sync-emptytitle">No mobile devices paired yet</div>' +
          '<div class="vpd-sync-emptytext">To sync without internet, connect your computer and phone to the same Wi-Fi network (or phone hotspot), click "Pair Mobile Device" above, and scan the QR code using VaultPass on your phone.</div></div>', 'is-stack16');
      }
      return card(head + '<div class="vpd-devices">' + S.paired.map(function (d, i) {
        return '<div class="vpd-device' + (d.online ? ' is-online' : '') + '"><div class="vpd-device-top"><span class="vpd-device-icon">' + icon('phoneAndroid', 22) + '</span>' +
          '<div class="vpd-device-text"><div class="vpd-device-namerow"><span class="vpd-device-name">' + esc(d.name) + '</span>' +
            '<span class="vpd-online"><span class="vpd-online-dot"></span>' + (d.online ? 'Online' : 'Offline') + '</span></div>' +
            '<div class="vpd-device-ip">IP: ' + esc(d.ip) + ':' + d.port + '</div>' +
            '<div class="vpd-device-last">Last sync: ' + (d.lastSyncAt > 0 ? fmtLong(d.lastSyncAt) : 'Never') + '</div></div></div>' +
          '<div class="vpd-device-buttons"><button type="button" class="vpd-btn vpd-btn-teal vpd-btn-xs8" data-act="sync-now" data-index="' + i + '" data-fk="sync-now-' + i + '">' + icon('sync', 15) + '<span>Sync Now</span></button>' +
            '<button type="button" class="vpd-btn vpd-btn-outline vpd-btn-redout vpd-btn-xs8" data-act="unpair" data-index="' + i + '" data-fk="unpair-' + i + '">Unpair</button></div></div>';
      }).join('') + '</div>', 'is-stack16');
    }

    // ================================================================= LockScreen.kt
    function lockHtml() {
      var L = S.lock;
      var hintId = idp + 'lock-hint', errId = idp + 'lock-err';
      var shaking = L.shakeNow;
      L.shakeNow = false;
      return '<div class="vpd-lock"><div class="vpd-lock-card' + (shaking ? ' is-shaking' : '') + '">' +
        '<div class="vpd-lock-brand">VaultPass</div><div class="vpd-lock-sub">Welcome back. Unlock your vault.</div>' +
        '<div class="vpd-otf vpd-otf-lock' + (L.error ? ' is-error' : '') + ' has-trailing-1">' +
          '<input type="' + (L.visible ? 'text' : 'password') + '" class="vpd-otf-input" value="' + esc(L.pw) + '" placeholder="Master Password" aria-label="Master Password" autocomplete="off" spellcheck="false" data-lpignore="true" data-1p-ignore="true" data-field="lock-pw" data-fk="lock-pw"' +
            ' aria-describedby="' + (L.error ? errId + ' ' : '') + hintId + '"' + (L.error ? ' aria-invalid="true"' : '') + '>' +
          '<span class="vpd-otf-outline" aria-hidden="true"></span>' +
          '<div class="vpd-otf-trailing"><button type="button" class="vpd-ib" data-act="lock-vis" data-fk="lock-vis" aria-label="' + (L.visible ? 'Hide password' : 'Show password') + '" aria-pressed="' + L.visible + '">' + icon(L.visible ? 'visibility' : 'visibilityOff', 24) + '</button></div>' +
        '</div>' +
        (L.error ? '<div class="vpd-lock-error" id="' + errId + '">' + esc(L.error) + '</div>' : '') +
        '<div class="vpd-lock-hint" id="' + hintId + '">Demo: any password unlocks</div>' +
        '<button type="button" class="vpd-lock-unlock" data-act="unlock" data-fk="lock-unlock">Unlock</button>' +
        '</div></div>';
    }

    // Main.kt, SessionState.Unlocking: SessionManagerImpl.unlock() switches state at once, so the whole
    // window shows a centred primary-colour spinner on the background while the key is derived.
    function unlockingHtml() {
      return '<div class="vpd-unlocking" role="progressbar" aria-label="Unlocking" tabindex="-1" data-fk="unlocking">' + spinner(40, 4, 'vpd-spin-primary') + '</div>';
    }

    // One-shot: the class is only on the render right after the error, so later renders do not shake again.
    function shakeLock() { S.lock.shakeNow = !reducedMotion(); }

    function tryUnlock() {
      var L = S.lock;
      if (L.loading) return;
      if (isBlank(L.pw)) {
        L.error = 'Master Password cannot be empty.';
        shakeLock();
        render();
        focusFk('lock-pw');
        return;
      }
      L.error = null;
      if (reducedMotion()) { unlock(); return; }
      var hadFocus = root.contains(document.activeElement);
      L.loading = true;
      render();
      if (hadFocus) focusFk('unlocking');
      setTimer('unlock', unlock, 650);
    }

    function unlock() {
      var hadFocus = root.contains(document.activeElement);
      S.locked = false;
      S.unlockedWith = S.lock.pw;
      S.lock = { pw: '', visible: false, error: null, loading: false, shakeNow: false };
      // Main.kt: after unlock the app opens the default startup page (Dashboard).
      var target = S.pendingScreen && SCREENS[S.pendingScreen] ? S.pendingScreen : 'dashboard';
      S.pendingScreen = null;
      S.lastUnlock = Date.now();
      enterScreen(target);
      render();
      if (hadFocus) focusFallback();
      fire(lockCbs, true);
    }

    function lockVault(moveFocus) {
      if (S.locked) return;
      S.locked = true;
      S.dialogs = [];
      S.menu = null;
      clearTimer('clip');
      clearTimer('sync');
      clearTimer('sync-wait');
      S.clip = { active: false, remaining: 0, total: 0 };
      // Main.kt on Locked: vaultViewModel.clearState() and dashboardViewModel.clearState() reset all three
      // searches (vault, Recycle Bin, dashboard) whatever "Clear Search Filters on Lock" says.
      S.vaultQuery = '';
      S.dashQuery = '';
      S.binQuery = '';
      S.vault.selectedId = null;
      S.snack = { main: null, screen: null };
      clearTimer('snack-main');
      clearTimer('snack-screen');
      S.lock = { pw: '', visible: false, error: null, loading: false, shakeNow: false };
      if (rafId) { cancelAnimationFrame(rafId); rafId = 0; }
      render();
      if (moveFocus) focusFk('lock-pw');
      fire(lockCbs, false);
    }

    // ================================================================= navigation
    function enterScreen(name) {
      S.screen = name;
      S.menu = null;
      S.snack.screen = null;
      clearTimer('snack-screen');
      S.vault.sortAsc = true;
      S.bin.selected = {};
      if (name === 'generator') S.gen = newGen();
      // The update check result lives in SettingsViewModel, so it survives leaving Settings.
      if (name === 'settings') S.settingsCat = 'Security';
      if (name === 'security') { S.sec.shown = 0; startScoreAnim(0); }
    }
    function navigate(name) {
      if (!SCREENS[name]) return;
      enterScreen(name);
      render();
    }

    // ================================================================= dialogs
    function openDialog(d, openerFk) {
      d.opener = openerFk || null;
      d.fresh = true;
      S.menu = null;
      S.dialogs.push(d);
      render();
      var top = topDialogEl();
      if (top) {
        var target = top.querySelector('[data-autofocus]') || focusables(top)[0];
        if (target) {
          try { target.focus({ preventScroll: true }); } catch (e) { target.focus(); }
          if (target.tagName === 'INPUT') { try { target.setSelectionRange(target.value.length, target.value.length); } catch (e) { /* not a text input */ } }
        }
      }
    }
    function closeDialog(restore) {
      var d = S.dialogs.pop();
      render();
      if (restore !== false && d) {
        var top = topDialogEl();
        if (top) { var t = focusables(top)[0]; if (t) t.focus(); return; }
        if (!focusFk(d.opener)) focusFallback();
      }
    }
    function topDialogEl() {
      var all = layerEl.querySelectorAll('[role="dialog"]');
      return all.length ? all[all.length - 1] : null;
    }
    function focusables(el) {
      return Array.prototype.filter.call(el.querySelectorAll('button, input, textarea, a[href], [tabindex]:not([tabindex="-1"])'), function (x) {
        return !x.disabled && x.getAttribute('tabindex') !== '-1' && (x.offsetWidth > 0 || x.offsetHeight > 0);
      });
    }

    function layerHtml() {
      var s = '';
      S.dialogs.forEach(function (d, i) {
        s += '<div class="vpd-modal' + (d.fresh ? ' is-entering' : '') + '"><div class="vpd-scrim" data-act="scrim"></div>' + dialogHtml(d, i) + '</div>';
        d.fresh = false;
      });
      if (S.menu) s += '<div class="vpd-menu-catcher" data-act="menu-dismiss"></div>' + menuHtml();
      return s;
    }

    function alertHtml(i, o) {
      var tid = idp + 'dlg' + i + '-t', did = idp + 'dlg' + i + '-d';
      // tabindex -1: a click on the dialog's text keeps focus in the dialog, so Escape and Tab still work.
      return '<div class="vpd-alert' + (o.cls ? ' ' + o.cls : '') + '" role="dialog" aria-modal="true" tabindex="-1" aria-labelledby="' + tid + '" aria-describedby="' + did + '">' +
        '<h2 class="vpd-alert-title" id="' + tid + '">' + o.title + '</h2><div class="vpd-alert-text" id="' + did + '">' + o.text + '</div>' +
        '<div class="vpd-alert-actions">' + o.actions + '</div></div>';
    }
    function textBtn(label, act, cls, extra) {
      return '<button type="button" class="vpd-textbtn' + (cls ? ' ' + cls : '') + '" data-act="' + act + '"' + (extra || '') + '>' + label + '</button>';
    }

    function dialogHtml(d, i) {
      switch (d.type) {
        case 'entry': return entryDialogHtml(d, i);
        case 'confirmDelete': {
          var e = byId(d.id);
          return alertHtml(i, { cls: 'is-1e is-r12', title: 'Delete Password',
            text: 'Are you sure you want to delete \'' + esc(e ? displayTitle(e) : 'Untitled') + '\'? It will be moved to the Recycle Bin.',
            actions: textBtn('Cancel', 'dlg-cancel', 'is-muted') + '<button type="button" class="vpd-btn vpd-btn-red vpd-btn-r8 is-semibold" data-act="confirm-delete">Delete</button>' });
        }
        case 'clipWarn':
          return alertHtml(i, { cls: 'is-tint', title: 'Clipboard History Warning',
            text: 'Windows clipboard history might retain copied passwords. Do you want to proceed?',
            actions: textBtn('Don\'t show again', 'clip-dontshow', 'is-muted') + textBtn('Copy anyway', 'clip-anyway', 'is-teal') });
        case 'entryDeleteConfirm':
          return alertHtml(i, { cls: 'is-tint is-plain', title: 'Delete Entry',
            text: 'Are you sure you want to delete this entry? It will be moved to the Recycle Bin.',
            actions: textBtn('Cancel', 'dlg-cancel') + textBtn('Delete', 'entry-delete-confirm', 'is-error') });
        case 'emptyBin':
          return alertHtml(i, { cls: 'is-tint', title: 'Empty Recycle Bin',
            text: 'Are you sure you want to permanently delete all ' + deleted().length + ' items in the Recycle Bin? This action cannot be undone.',
            actions: textBtn('Cancel', 'dlg-cancel', 'is-muted') + '<button type="button" class="vpd-btn vpd-btn-salmon vpd-btn-r8 is-semibold" data-act="bin-empty-confirm">Empty Bin</button>' });
        case 'binDeleteOne': {
          // RecycleBinScreen: deleting one entry forever asks first
          var be = byId(d.id);
          return alertHtml(i, { cls: 'is-tint', title: 'Delete Forever',
            text: 'Permanently delete "' + esc(be && !isBlank(be.title) ? be.title : 'Untitled') + '"? This cannot be undone.',
            actions: textBtn('Cancel', 'dlg-cancel', 'is-muted') + '<button type="button" class="vpd-btn vpd-btn-salmon vpd-btn-r8 is-semibold" data-act="bin-delete-confirm">Delete Forever</button>' });
        }
        case 'discard':
          // EntryDialog: Esc or a click outside, with typed changes
          return alertHtml(i, { cls: 'is-tint is-plain', title: 'Discard changes?',
            text: "What you typed in this entry hasn't been saved and will be lost.",
            actions: textBtn('Keep editing', 'dlg-cancel') + textBtn('Discard', 'discard-confirm', 'is-error') });
        case 'plainExport': {
          // SettingsScreen: JSON, CSV and TXT exports ask for the master password, every time
          var fmt = EXPORT_FORMATS.filter(function (f) { return f[0] === d.format; })[0];
          return alertHtml(i, { cls: 'is-tint', title: 'Export without encryption?',
            text: '<p>A ' + fmt[1] + ' file holds every password as readable text: anyone who gets the file can read them all. For a backup, use the encrypted .vpex export.</p>' +
              '<div class="vpd-plain-field">' + otf({ field: 'plain-pw', label: 'Master password', value: d.pw, password: true, error: d.wrong, support: d.wrong ? "That isn't your master password." : null, autofocus: true }) + '</div>' +
              '<p class="vpd-alert-hint">' + (S.unlockedWith == null ? 'Demo: any password works until you lock and unlock the vault' : 'Demo: the password you last unlocked with') + '</p>',
            actions: textBtn('Cancel', 'dlg-cancel', 'is-muted') + textBtn('Export Unencrypted', 'plain-export', 'is-red', d.pw ? '' : ' disabled') });
        }
        case 'batchDelete':
          return alertHtml(i, { cls: 'is-tint', title: 'Permanently Delete Selected Items',
            text: 'Are you sure you want to permanently delete ' + Object.keys(S.bin.selected).length + ' items? This cannot be undone.',
            actions: textBtn('Cancel', 'dlg-cancel', 'is-muted') + '<button type="button" class="vpd-btn vpd-btn-salmon vpd-btn-r8 is-semibold" data-act="bin-batch-confirm">Delete Forever</button>' });
        case 'startupKeyOff':
          return alertHtml(i, { cls: 'is-tint', title: 'Open VaultPass without a password?',
            text: 'VaultPass will keep your vault key in your Windows account so it opens after a restart without asking. Any program running under your Windows account can read that key and open your vault. Locking VaultPass deletes it until you next unlock.',
            actions: textBtn('Keep Asking', 'dlg-cancel', 'is-teal') + textBtn('Turn Off', 'startup-off', 'is-red') });
        case 'syncing':
          return alertHtml(i, { cls: 'is-1e is-r12', title: 'Synchronizing Vault',
            text: '<div class="vpd-syncing">' + spinner(28, 3, 'vpd-spin-teal') + '<span>Connecting to ' + esc(d.device) + '...<br>Waiting for other device to accept sync request.</span></div>',
            actions: textBtn('Cancel', 'sync-cancel', 'is-red') });
        case 'unpair':
          return alertHtml(i, { cls: 'is-tint', title: 'Unpair Mobile Device',
            text: 'Are you sure you want to unpair "' + esc(d.device) + '"? It will be removed from your paired devices list.',
            actions: textBtn('Cancel', 'dlg-cancel', 'is-muted') + '<button type="button" class="vpd-btn vpd-btn-salmon-white is-bold" data-act="unpair-confirm">Unpair</button>' });
      }
      return '';
    }

    // ---------------------------------------------------------------- EntryDialog.kt
    function draftFrom(e, pending) {
      var hide = S.settings.hidePasswordsByDefault;
      return {
        title: e ? e.title : '', username: e ? e.username : '', password: e ? e.password : (pending || ''),
        url: e ? e.website : '', notes: e ? e.notes : '', category: e ? e.category : '',
        tags: e ? (tagsByUid[e.uid] || []).join(', ') : '',
        pwVisible: !hide, titleError: false, showCfErrors: false,
        cf: e ? e.customFields.map(function (f) { return { key: f.label, value: f.value, visible: !hide, hidden: f.hidden }; }) : []
      };
    }
    function tooLong(v, limit) { return v.length > limit ? 'Max ' + limit + ' characters' : null; }
    function parsedTags(t) { return t.split(',').map(function (x) { return x.trim(); }).filter(function (x) { return x !== ''; }); }
    function tagsError(t) {
      var p = parsedTags(t);
      if (p.length > LIMITS.maxTags) return 'Max ' + LIMITS.maxTags + ' tags';
      if (p.some(function (x) { return x.length > LIMITS.tag; })) return 'Each tag: Max ' + LIMITS.tag + ' characters';
      return null;
    }
    function cfError(f) {
      if (isBlank(f.key)) return 'Enter a name for this field';
      if (f.key.trim().length > LIMITS.cfKey) return 'Name: Max ' + LIMITS.cfKey + ' characters';
      if (f.value.length > LIMITS.cfValue) return 'Value: Max ' + LIMITS.cfValue + ' characters';
      return null;
    }
    function entryErrors(dr) {
      return {
        title: tooLong(dr.title, LIMITS.title), username: tooLong(dr.username, LIMITS.username), url: tooLong(dr.url, LIMITS.url),
        password: tooLong(dr.password, LIMITS.password), notes: tooLong(dr.notes, LIMITS.notes), tags: tagsError(dr.tags)
      };
    }
    function errSignature(dr) {
      var e = entryErrors(dr);
      return [dr.titleError, e.title, e.username, e.url, e.password, e.notes, e.tags].join('|') + '|' +
        dr.cf.map(function (f) { return String(cfVisibleError(dr, f)) + (f.value.length > LIMITS.cfValue); }).join(',');
    }
    function cfVisibleError(dr, f) {
      var filled = !isBlank(f.key) || f.value !== '';
      var err = cfError(f);
      return err && (!isBlank(f.key) || (dr.showCfErrors && filled)) ? err : null;
    }

    function otf(o) {
      var id = idp + o.field;
      var cls = 'vpd-otf' + (o.multiline ? ' is-multiline' : '') + (o.error ? ' is-error' : '') + (o.trailingCount ? ' has-trailing-' + o.trailingCount : '');
      var described = o.support ? ' aria-describedby="' + id + '-s"' : '';
      var input = o.multiline ?
        '<textarea id="' + id + '" class="vpd-otf-input" data-field="' + o.field + '" data-fk="f-' + o.field + '" placeholder=" " rows="3" spellcheck="false"' + described + (o.error ? ' aria-invalid="true"' : '') + '>' + esc(o.value) + '</textarea>' :
        '<input id="' + id + '" type="' + (o.password ? 'password' : 'text') + '" class="vpd-otf-input" data-field="' + o.field + '" data-fk="f-' + o.field + '" value="' + esc(o.value) +
          '" placeholder=" " autocomplete="off" spellcheck="false" data-lpignore="true" data-1p-ignore="true"' + described + (o.error ? ' aria-invalid="true"' : '') + (o.autofocus ? ' data-autofocus="true"' : '') + '>';
      return '<div class="vpd-otf-wrap' + (o.wrapCls ? ' ' + o.wrapCls : '') + '"><div class="' + cls + '">' + input +
        '<label class="vpd-otf-label" for="' + id + '">' + esc(o.label) + '</label><span class="vpd-otf-outline" aria-hidden="true"></span>' +
        (o.trailing ? '<div class="vpd-otf-trailing">' + o.trailing + '</div>' : '') + '</div>' +
        (o.support ? '<div class="vpd-otf-support" id="' + id + '-s">' + esc(o.support) + '</div>' : '') + '</div>';
    }

    function entryDialogHtml(d, i) {
      var dr = d.draft, err = entryErrors(dr), tid = idp + 'dlg' + i + '-t';
      // The app scrolls the action row with the fields (Column.verticalScroll); its 768 px window leaves room
      // to show it. The demo's window is shorter, so the row is pinned under the scrolling fields instead,
      // with the same 32 dp gap once the fields are scrolled to the end.
      var s = '<div class="vpd-entry" role="dialog" aria-modal="true" tabindex="-1" aria-labelledby="' + tid + '"><div class="vpd-entry-scroll" data-sk="entry-dialog">' +
        '<h2 class="vpd-entry-title" id="' + tid + '">' + (d.mode === 'add' ? 'Add Password' : 'Edit Password') + '</h2>' +
        otf({ field: 'title', label: 'Title', value: dr.title, error: dr.titleError || !!err.title, support: err.title, autofocus: true }) +
        (dr.titleError ? '<div class="vpd-field-error">Title is required</div>' : '') +
        '<div class="vpd-entry-row">' +
          otf({ field: 'username', label: 'Username', value: dr.username, error: !!err.username, support: err.username }) +
          otf({ field: 'url', label: 'Website', value: dr.url, error: !!err.url, support: err.url }) +
        '</div>' +
        otf({ field: 'password', label: 'Password', value: dr.password, password: !dr.pwVisible, error: !!err.password, support: err.password, trailingCount: 2,
          trailing: '<button type="button" class="vpd-ib" data-act="entry-pwvis" data-fk="entry-pwvis" aria-label="Toggle Visibility" aria-pressed="' + dr.pwVisible + '">' + icon(dr.pwVisible ? 'visibility' : 'visibilityOff', 24) + '</button>' +
            '<button type="button" class="vpd-ib" data-act="entry-gen" data-fk="entry-gen" aria-label="Generate inline">' + icon('refresh', 24) + '</button>' }) +
        '<div class="vpd-entry-row">' +
          otf({ field: 'category', label: 'Category', value: dr.category }) +
          otf({ field: 'tags', label: 'Tags', value: dr.tags, error: !!err.tags, support: err.tags }) +
        '</div>' +
        otf({ field: 'notes', label: 'Notes', value: dr.notes, multiline: true, error: !!err.notes, support: err.notes }) +
        '<div class="vpd-cf"><div class="vpd-cf-head"><span class="vpd-cf-title">Custom fields</span><span class="vpd-cf-count">' + dr.cf.length + '/' + LIMITS.maxCf + '</span></div>';
      dr.cf.forEach(function (f, ci) {
        var e2 = cfVisibleError(dr, f);
        var keyErr = !!e2 && (isBlank(f.key) || f.key.trim().length > LIMITS.cfKey);
        s += '<div class="vpd-cf-row">' +
          otf({ field: 'cfk' + ci, label: 'Name', value: f.key, error: keyErr, wrapCls: 'is-name' }) +
          otf({ field: 'cfv' + ci, label: 'Value', value: f.value, password: !f.visible, error: f.value.length > LIMITS.cfValue, wrapCls: 'is-value', trailingCount: 1,
            trailing: '<button type="button" class="vpd-ib" data-act="cf-vis" data-index="' + ci + '" data-fk="cf-vis' + ci + '" aria-label="Toggle Visibility" aria-pressed="' + f.visible + '">' + icon(f.visible ? 'visibility' : 'visibilityOff', 24) + '</button>' }) +
          '<button type="button" class="vpd-ib vpd-cf-remove" data-act="cf-remove" data-index="' + ci + '" data-fk="cf-remove' + ci + '" aria-label="Remove field">' + icon('close', 24) + '</button></div>' +
          (e2 ? '<div class="vpd-field-error">' + esc(e2) + '</div>' : '');
      });
      var atMax = dr.cf.length >= LIMITS.maxCf;
      s += '<button type="button" class="vpd-textbtn vpd-cf-add" data-act="cf-add" data-fk="cf-add"' + (atMax ? ' disabled' : '') + '>' + icon('add', 18) + '<span>Add custom field</span></button>' +
        (atMax ? '<div class="vpd-cf-max">Max ' + LIMITS.maxCf + ' custom fields</div>' : '') + '</div></div>' +
        '<div class="vpd-entry-actions">' +
          (d.mode === 'edit' ? '<button type="button" class="vpd-ib is-error" data-act="entry-delete" data-fk="entry-delete" aria-label="Delete">' + icon('del', 24) + '</button>' : '') +
          '<span class="vpd-flex"></span>' + textBtn('Cancel', 'entry-cancel', 'is-onvariant', ' data-fk="entry-cancel"') +
          '<button type="button" class="vpd-btn vpd-btn-primary vpd-btn-r8" data-act="entry-save" data-fk="entry-save">' + (d.mode === 'add' ? 'Create Entry' : 'Save Changes') + '</button>' +
        '</div></div>';
      return s;
    }

    function openEntryDialog(mode, id, openerFk, pending) {
      var e = id ? byId(id) : null;
      if (mode === 'edit' && (!e || e.deletedAt)) return;
      openDialog({ type: 'entry', mode: mode, id: id || null, draft: draftFrom(e, pending), opened: draftKey(draftFrom(e)) }, openerFk);
    }

    // What the fields hold, compared to tell whether closing would lose typing (EntryDraft).
    // A custom field row added but left empty is dropped on save, so it isn't a change either.
    function draftKey(dr) {
      return JSON.stringify([dr.title, dr.username, dr.password, dr.url, dr.notes, dr.category, dr.tags,
        dr.cf.filter(function (f) { return !isBlank(f.key) || f.value !== ''; }).map(function (f) { return [f.key, f.value]; })]);
    }

    function continuePlainExport() {
      var d = S.dialogs[S.dialogs.length - 1];
      if (!d || d.type !== 'plainExport' || !d.pw) return;
      if (S.unlockedWith != null && d.pw !== S.unlockedWith) {
        d.wrong = true;
        renderLayer();
        focusFk('f-plain-pw');
        return;
      }
      S.dialogs.pop();
      render();
      if (!focusFk(d.opener)) focusFallback();
      snack('screen', FILE_DIALOG_MSG.save);
    }

    function saveEntry(d) {
      var dr = d.draft;
      if (isBlank(dr.title)) {
        dr.titleError = true;
        renderLayer();
        var sc = layerEl.querySelector('.vpd-entry-scroll');
        if (sc) sc.scrollTop = 0;
        focusFk('f-title');
        return;
      }
      var err = entryErrors(dr);
      if (err.title || err.username || err.url || err.password || err.notes || err.tags) return;
      var filled = dr.cf.filter(function (f) { return !isBlank(f.key) || f.value !== ''; });
      if (filled.some(function (f) { return cfError(f) != null; })) { dr.showCfErrors = true; renderLayer(); return; }
      var now = nowIso();
      var fields = filled.map(function (f) { return { label: f.key.trim(), value: f.value, hidden: f.hidden !== false }; });
      var tags = parsedTags(dr.tags);
      if (d.mode === 'add') {
        var e = normEntry({ uid: newUid(), title: dr.title, username: dr.username, password: dr.password, website: dr.url, notes: dr.notes,
          category: isBlank(dr.category) ? '' : dr.category.trim(), favorite: false, customFields: fields, createdAt: now, updatedAt: now, deletedAt: null });
        tagsByUid[e.uid] = tags;
        S.entries.push(e);
      } else {
        var t = byId(d.id);
        if (t) {
          t.title = dr.title; t.username = dr.username; t.password = dr.password; t.website = dr.url; t.notes = dr.notes;
          t.category = isBlank(dr.category) ? '' : dr.category.trim();
          t.customFields = fields;
          t.updatedAt = now;
          tagsByUid[t.uid] = tags;
        }
      }
      S.dialogs.pop();
      render();
      if (!focusFk(d.opener)) focusFallback();
      snack('main', d.mode === 'add' ? 'Password added successfully' : 'Password updated successfully');
      changed();
    }

    // ---------------------------------------------------------------- entry mutations (VaultViewModel)
    function softDelete(id) {
      var e = byId(id);
      if (!e || e.deletedAt) return;
      var now = nowIso();
      e.deletedAt = now;
      e.updatedAt = now;
      if (S.vault.selectedId === id) S.vault.selectedId = null;
      changed();
    }
    function restoreEntry(id) {
      var e = byId(id);
      if (!e || !e.deletedAt) return;
      e.deletedAt = null;
      e.updatedAt = nowIso();
    }
    // Purging writes a sync tombstone, as deleteWithTombstones() does, when the phone may hold the entry.
    function hardDelete(ids) {
      var set = {}, now = nowIso();
      ids.forEach(function (id) { set[id] = true; });
      S.entries = S.entries.filter(function (e) {
        if (!(set[e.uid] && e.deletedAt)) return true;
        if (peerKnown[e.uid]) {
          var t = publicEntry(e);
          t.deletedAt = now;
          t.updatedAt = now;
          S.tombs[e.uid] = t;
        }
        return false;
      });
    }
    function stampOf(e) { return Math.max(toMs(e.updatedAt), e.deletedAt ? toMs(e.deletedAt) : 0); }
    function toggleFavorite(id) {
      var e = byId(id);
      if (!e) return;
      e.favorite = !e.favorite;
      e.updatedAt = nowIso();
      changed();
    }

    // ================================================================= menus (M3 DropdownMenu / PointerContextMenu)
    function menuItem(o, idx) {
      return '<button type="button" class="vpd-menu-item' + (o.cls ? ' ' + o.cls : '') + '" role="menuitem" tabindex="' + (idx === 0 ? '0' : '-1') + '" data-act="' + o.act + '"' + (o.data || '') + '>' +
        (o.lead ? '<span class="vpd-menu-lead">' + o.lead + '</span>' : '') + '<span class="vpd-menu-text">' + o.label + '</span>' +
        (o.trail ? '<span class="vpd-menu-trail">' + o.trail + '</span>' : '') + '</button>';
    }
    function menuHtml() {
      var m = S.menu, items = [], bordered = false;
      if (m.type === 'rowCtx') {
        var e = byId(m.id);
        if (!e) return '';
        bordered = true;
        items = [
          { act: 'm-open', label: 'Open Details', lead: icon('openInNew', 24) },
          { act: 'm-edit', label: 'Edit', lead: icon('edit', 24) },
          { act: 'm-copyuser', label: 'Copy Username', lead: icon('person', 24) },
          { act: 'm-copypw', label: 'Copy Password', lead: icon('contentCopy', 24) },
          { act: 'm-fav', label: e.favorite ? 'Unfavorite' : 'Favorite', lead: icon(e.favorite ? 'starBorder' : 'star', 24) },
          { act: 'm-delete', label: 'Delete', lead: icon('del', 24) }
        ];
      } else if (m.type === 'binCtx') {
        bordered = true;
        items = [
          { act: 'm-bin-restore', label: 'Restore', lead: '<span class="is-teal">' + icon('restore', 24) + '</span>' },
          { act: 'm-bin-delete', label: 'Delete Forever', lead: '<span class="is-red">' + icon('deleteForever', 24) + '</span>', cls: 'is-red' }
        ];
      } else if (m.type === 'detailMore') {
        items = [{ act: 'm-delete', label: 'Delete' }];
      } else if (m.type === 'settingsDrop') {
        var d = DROPDOWNS[m.name], v = S.settings[d.key];
        items = d.options.map(function (o, i) {
          return { act: 'm-dd', label: o, data: ' data-index="' + i + '"', trail: d.values[i] === v ? '<span class="is-teal">' + icon('check', 24) + '</span>' : '' };
        });
      }
      return '<div class="vpd-menu' + (bordered ? ' is-bordered' : '') + (m.fresh ? ' is-entering' : '') + '" role="menu" tabindex="-1" aria-label="' + (m.type === 'settingsDrop' ? esc(DROPDOWNS[m.name].title) : 'Options') + '" style="visibility:hidden">' +
        '<div class="vpd-menu-inner">' + items.map(menuItem).join('') + '</div></div>';
    }
    function openMenu(m) {
      m.fresh = !reducedMotion();
      S.menu = m;
      renderLayer();
      if (m.type === 'detailMore') {
        var b = mainEl.querySelector('[data-fk="detail-more"]');
        if (b) b.setAttribute('aria-expanded', 'true');
      }
      var first = layerEl.querySelector('.vpd-menu [role="menuitem"]');
      if (first) { try { first.focus({ preventScroll: true }); } catch (e) { first.focus(); } }
      if (S.menu) S.menu.fresh = false;
    }
    function closeMenu(restore) {
      var m = S.menu;
      if (!m) return;
      S.menu = null;
      layerEl.innerHTML = layerHtml();
      var b = mainEl.querySelector('[data-fk="detail-more"]');
      if (b) b.setAttribute('aria-expanded', 'false');
      if (restore !== false && !focusFk(m.anchorFk)) focusFallback();
    }
    // DropdownMenuPositionProvider: below the anchor, flipped above when it does not fit, kept inside the window.
    function placeMenu() {
      var m = S.menu;
      var el = layerEl.querySelector('.vpd-menu');
      if (!m || !el) return;
      var w = el.offsetWidth, h = el.offsetHeight, ax, ay, ax2, ay2;
      if (m.point) { ax = ax2 = m.point.x; ay = ay2 = m.point.y; }
      else {
        var a = root.querySelector('[data-fk="' + m.anchorFk + '"]');
        if (!a) { closeMenu(false); return; }
        var p = posInApp(a);
        ax = p.x; ay = p.y; ax2 = p.x + a.offsetWidth; ay2 = p.y + a.offsetHeight;
      }
      var x = ax;
      if (x + w > APP_W) x = ax2 - w;
      if (x < 0) x = Math.max(0, APP_W - w);
      var margin = 48, y = ay2;
      if (y + h > APP_H - margin) y = ay - h;
      if (y < margin) y = Math.max(0, Math.min(APP_H - h, ay2));
      if (y + h > APP_H) y = Math.max(0, APP_H - h);
      el.style.left = Math.round(x) + 'px';
      el.style.top = Math.round(y) + 'px';
      el.style.visibility = '';
    }
    function menuEntryId() { return S.menu ? S.menu.id : null; }

    // ================================================================= events
    function onClick(ev) {
      var t = ev.target.closest ? ev.target.closest('[data-act]') : null;
      if (!t || !root.contains(t)) return;
      if (t.disabled || t.getAttribute('aria-disabled') === 'true') return;
      var act = t.getAttribute('data-act');
      var id = t.getAttribute('data-id');
      var fk = t.getAttribute('data-fk');
      var index = parseInt(t.getAttribute('data-index'), 10);
      var top = S.dialogs[S.dialogs.length - 1];
      var e;
      if (t.tagName === 'A') return;
      switch (act) {
        // ---- shell
        case 'nav': navigate(t.getAttribute('data-screen')); break;
        case 'lock': lockVault(true); break;
        case 'add': openEntryDialog('add', null, fk); break;
        case 'clip-clear': clearTimer('clip'); S.clip.active = false; updatePill(); break;
        // ---- lock
        case 'lock-vis': S.lock.visible = !S.lock.visible; render(); break;
        case 'unlock': tryUnlock(); break;
        // ---- vault
        case 'sort': S.vault.sortAsc = !S.vault.sortAsc; refreshVaultBody(); syncSortButton(); break;
        case 'row-select':
          if (t.getAttribute('data-row') === 'vault') { selectEntry(id); paintVaultSelection(); }
          break;
        case 'copy-pw': e = byId(id); if (e) vaultCopySecret(e.password, fk); break;
        case 'edit': openEntryDialog('edit', id, fk); break;
        case 'detail-more': openMenu({ type: 'detailMore', id: S.vault.selectedId, anchorFk: 'detail-more' }); break;
        case 'toggle-pw': S.vault.pwVisible = !S.vault.pwVisible; refreshVaultBody(); break;
        case 'toggle-cf': {
          var cur = S.vault.cfVisible[index] != null ? S.vault.cfVisible[index] : !S.settings.hidePasswordsByDefault;
          S.vault.cfVisible[index] = !cur;
          refreshVaultBody();
          break;
        }
        case 'copy-user-detail': e = byId(S.vault.selectedId); if (e) { copyToClipboard(e.username); snack('screen', 'Username copied'); } break;
        case 'copy-pw-detail': e = byId(S.vault.selectedId); if (e) vaultCopySecret(e.password, fk); break;
        case 'copy-cf': e = byId(S.vault.selectedId); if (e && e.customFields[index]) vaultCopySecret(e.customFields[index].value, fk); break;
        case 'copy-url': e = byId(S.vault.selectedId); if (e) copyToClipboard(e.website); break;
        case 'open-site': e = byId(S.vault.selectedId); if (e) openSite(e.website); break;
        // ---- context / dropdown menus
        case 'menu-dismiss': closeMenu(); break;
        case 'm-open': { var oid = menuEntryId(); closeMenu(false); selectEntry(oid); refreshVaultBody(); focusFk('row-' + sid(oid)); break; }
        case 'm-edit': { var eid = menuEntryId(), efk = S.menu.anchorFk; closeMenu(false); openEntryDialog('edit', eid, efk); break; }
        case 'm-copyuser': { e = byId(menuEntryId()); closeMenu(); if (e) { copyToClipboard(e.username); snack('screen', 'Username copied'); } break; }
        case 'm-copypw': { e = byId(menuEntryId()); var cfk = S.menu.anchorFk; closeMenu(); if (e) vaultCopySecret(e.password, cfk); break; }
        case 'm-fav': { var fid = menuEntryId(); closeMenu(); toggleFavorite(fid); render(); break; }
        case 'm-delete': { var did = menuEntryId(), dfk = S.menu.anchorFk; closeMenu(false); if (byId(did)) openDialog({ type: 'confirmDelete', id: did }, dfk); break; }
        case 'm-bin-restore': { var rid = menuEntryId(); closeMenu(false); restoreEntry(rid); render(); focusFallback(); snack('screen', 'Entry restored'); changed(); break; }
        case 'm-bin-delete': { var xid = menuEntryId(), xfk = S.menu.anchorFk; closeMenu(false); if (byId(xid)) openDialog({ type: 'binDeleteOne', id: xid }, xfk); break; }
        case 'm-dd': {
          var ddName = S.menu.name, dd = DROPDOWNS[ddName];
          S.settings[dd.key] = dd.values[index];
          closeMenu(false);
          render();
          focusFk('dd-' + ddName);
          break;
        }
        // ---- dialogs
        case 'scrim': dismissTop(); break;
        case 'dlg-cancel': closeDialog(); break;
        case 'confirm-delete': {
          var cd = top;
          S.dialogs.pop();
          softDelete(cd.id);
          render();
          if (!focusFk(cd.opener)) focusFallback();
          snack('screen', 'Entry moved to Recycle Bin');
          break;
        }
        case 'clip-anyway': { var cw = top; S.dialogs.pop(); copySecret(cw.secret); render(); if (!focusFk(cw.opener)) focusFallback(); snack('screen', 'Password copied'); break; }
        case 'clip-dontshow': { var cw2 = top; S.dialogs.pop(); S.settings.showClipboardHistoryWarning = false; copySecret(cw2.secret); render(); if (!focusFk(cw2.opener)) focusFallback(); snack('screen', 'Password copied'); break; }
        case 'entry-cancel': closeDialog(); break;
        case 'discard-confirm': { S.dialogs.pop(); var dx = S.dialogs.pop(); render(); if (!focusFk(dx.opener)) focusFallback(); break; }
        case 'entry-save': saveEntry(top); break;
        case 'entry-pwvis': top.draft.pwVisible = !top.draft.pwVisible; renderLayer(); break;
        case 'entry-gen': {
          var gs = generatePassword({ length: 16, upper: true, lower: true, numbers: true, symbols: true, ambiguous: false });
          top.draft.password = gs.value;
          top.draft.pwVisible = true;
          renderLayer();
          snack('main', 'Generated strong password');
          break;
        }
        case 'cf-vis': top.draft.cf[index].visible = !top.draft.cf[index].visible; renderLayer(); break;
        case 'cf-remove': top.draft.cf.splice(index, 1); renderLayer(); if (!focusFk('cf-remove' + Math.min(index, top.draft.cf.length - 1))) focusFk('cf-add'); break;
        case 'cf-add':
          if (top.draft.cf.length < LIMITS.maxCf) {
            top.draft.cf.push({ key: '', value: '', visible: !S.settings.hidePasswordsByDefault, hidden: true });
            renderLayer();
            focusFk('f-cfk' + (top.draft.cf.length - 1));
          }
          break;
        case 'entry-delete':
          if (S.settings.confirmBeforeDelete) openDialog({ type: 'entryDeleteConfirm' }, 'entry-delete');
          else { var ed = S.dialogs.pop(); softDelete(ed.id); render(); if (!focusFk(ed.opener)) focusFallback(); }
          break;
        case 'entry-delete-confirm': {
          S.dialogs.pop();
          var ent = S.dialogs.pop();
          softDelete(ent.id);
          render();
          if (!focusFk(ent.opener)) focusFallback();
          break;
        }
        // ---- dashboard
        case 'fav-copy': e = byId(id); if (e) copySecret(e.password); break;
        // ---- security
        case 'fix': navigate('vault'); openEntryDialog('edit', id, 'nav-vault'); break;
        // ---- generator
        // LaunchedEffect(mode, ..., separator): picking the option that is already on changes nothing.
        case 'gen-mode': { var gm = t.getAttribute('data-mode'); if (gm === S.gen.mode) break; S.gen.mode = gm; regenerate(); render(); break; }
        case 'gen-toggle': { var k = t.getAttribute('data-key'); S.gen[k] = !S.gen[k]; regenerate(); render(); break; }
        case 'gen-sep': { var gsep = ['-', '.', '_', ' '][index]; if (gsep == null || gsep === S.gen.sep) break; S.gen.sep = gsep; regenerate(); render(); break; }
        case 'gen-regen': regenerate(); refreshGenPartial(); break;
        case 'gen-copy': copySecret(S.gen.current.value); break;
        case 'gen-hcopy': if (S.gen.history[index] != null) copySecret(S.gen.history[index]); break;
        case 'gen-use': { var pending = S.gen.current.value; navigate('vault'); openEntryDialog('add', null, 'nav-vault', pending); break; }
        // ---- recycle bin
        case 'bin-toggle': if (S.bin.selected[id]) delete S.bin.selected[id]; else S.bin.selected[id] = true; refreshBinBody(); break;
        case 'bin-selectall': {
          var all = binFiltered(), allOn = all.length && Object.keys(S.bin.selected).length === all.length;
          S.bin.selected = {};
          if (!allOn) all.forEach(function (x) { S.bin.selected[x.uid] = true; });
          render();
          break;
        }
        case 'bin-restore': restoreEntry(id); delete S.bin.selected[id]; render(); focusFallback(); snack('screen', 'Entry restored'); changed(); break;
        case 'bin-delete': if (byId(id)) openDialog({ type: 'binDeleteOne', id: id }, fk); break;
        case 'bin-delete-confirm': {
          var bd = top;
          S.dialogs.pop();
          hardDelete([bd.id]);
          delete S.bin.selected[bd.id];
          render();
          focusFallback();
          snack('screen', 'Entry deleted forever');
          changed();
          break;
        }
        case 'bin-restore-sel': {
          var rs = Object.keys(S.bin.selected);
          rs.forEach(restoreEntry);
          S.bin.selected = {};
          render();
          focusFallback();
          snack('screen', 'Restored ' + rs.length + ' item(s)');
          changed();
          break;
        }
        case 'bin-delete-sel': openDialog({ type: 'batchDelete' }, fk); break;
        case 'bin-batch-confirm': {
          var bs = Object.keys(S.bin.selected);
          S.dialogs.pop();
          hardDelete(bs);
          S.bin.selected = {};
          render();
          focusFallback();
          snack('screen', 'Permanently deleted ' + bs.length + ' item(s)');
          changed();
          break;
        }
        case 'bin-empty': openDialog({ type: 'emptyBin' }, fk); break;
        case 'bin-empty-confirm': {
          // VaultRepository.emptyRecycleBin(): every deleted entry, not only the ones the search shows.
          S.dialogs.pop();
          hardDelete(deleted().map(function (x) { return x.uid; }));
          S.bin.selected = {};
          render();
          focusFallback();
          snack('screen', 'Recycle Bin emptied');
          changed();
          break;
        }
        // ---- settings
        case 'set-cat': S.settingsCat = t.getAttribute('data-cat'); S.menu = null; render(); break;
        case 'set-toggle': {
          var key = t.getAttribute('data-key');
          if (key === 'requireMasterPasswordOnStartup' && S.settings[key]) { openDialog({ type: 'startupKeyOff' }, fk); break; }
          S.settings[key] = !S.settings[key];
          render();
          break;
        }
        case 'startup-off': S.settings.requireMasterPasswordOnStartup = false; closeDialog(); break;
        case 'set-dd': openMenu({ type: 'settingsDrop', name: t.getAttribute('data-name'), anchorFk: fk }); break;
        case 'set-export': S.exportFormat = t.getAttribute('data-format'); render(); break;
        case 'csv-protect': S.csvProtect = !S.csvProtect; render(); focusFk('csv-protect'); break;
        case 'export':
          // .vpex goes straight to the save dialog; readable formats ask first
          if (S.exportFormat === 'Vpex') snack('screen', FILE_DIALOG_MSG.save);
          else openDialog({ type: 'plainExport', format: S.exportFormat, pw: '', wrong: false }, fk);
          break;
        case 'plain-export': continuePlainExport(); break;
        case 'set-import': S.importOption = index; render(); break;
        case 'check-updates':
          S.update = { state: 'checking', checkedAt: 0 };
          render();
          setTimer('update', function () { S.update = { state: 'uptodate', checkedAt: Date.now() }; render(); }, reducedMotion() ? 0 : 900);
          break;
        case 'sync-refresh': snack('screen', 'Scanning for local network devices...'); break;
        case 'sync-now': { var dev = S.paired[index]; if (dev) startSync(dev, fk); break; }
        case 'sync-cancel': cancelSync(); break;
        case 'file-dialog': snack('screen', FILE_DIALOG_MSG[t.getAttribute('data-kind')] || FILE_DIALOG_MSG.open); break;
        case 'pair':
          // The app shows a QR code for the phone to scan (QrPairingDialog). The demo shows no QR code: with
          // no phone paired it lands in the paired state the scan leads to; otherwise it says what the app does.
          if (!S.paired.length) {
            S.paired.push({ name: peerName, ip: '192.168.1.42', port: 53853, lastSyncAt: 0, online: true });
            render();
            focusFk('pair');
            snack('main', 'Device paired successfully');
          } else {
            snack('screen', 'Demo: the app shows a QR code here for your phone to scan');
          }
          break;
        case 'unpair': { var up = S.paired[index]; if (up) openDialog({ type: 'unpair', device: up.name, index: index }, fk); break; }
        case 'unpair-confirm': {
          var ud = top;
          S.dialogs.pop();
          S.paired.splice(ud.index, 1);
          render();
          focusFk('sync-refresh');
          snack('screen', 'Device unpaired successfully');
          break;
        }
      }
    }

    function syncSortButton() {
      var b = mainEl.querySelector('.vpd-sortbtn');
      if (!b) return;
      b.classList.toggle('is-on', S.vault.sortAsc);
      b.setAttribute('aria-pressed', String(S.vault.sortAsc));
    }

    function openSite(url) {
      if (isBlank(url)) return;
      var href = /^https?:\/\//i.test(url) ? url : 'https://' + url;
      try {
        var u = new URL(href);
        if (u.protocol !== 'https:' && u.protocol !== 'http:') return;
        window.open(u.href, '_blank', 'noopener,noreferrer');
      } catch (e) { /* not a URL: the app ignores it too */ }
    }

    function dismissTop() {
      var d = S.dialogs[S.dialogs.length - 1];
      if (!d) return;
      if (d.type === 'syncing') { cancelSync(); return; }
      // Esc or a click outside an entry with typed changes asks first; the Cancel button doesn't
      if (d.type === 'entry' && draftKey(d.draft) !== d.opened) { openDialog({ type: 'discard' }); return; }
      closeDialog();
    }

    // ---------------------------------------------------------------- in-app Sync Now (SyncViewModel.initiateSyncWith)
    // The demo cannot reach the phone demo itself, so it asks the page with a cancelable, bubbling
    // "vaultpass-demo:sync-request" event on the host element (detail: { from: 'desktop', peerName }).
    // A page that runs the sync calls event.preventDefault() and then api.applySync(), which closes the
    // dialog with the real counts. If nothing answers in time, the dialog closes on its own with the app's
    // result line for a sync that exchanged nothing, so the spinner never hangs.
    var SYNC_WAIT_UNHANDLED = 1200, SYNC_WAIT_HANDLED = 2500;
    function syncDialogOpen(token) {
      return S.dialogs.some(function (d) { return d.type === 'syncing' && (token == null || d.token === token); });
    }
    function startSync(dev, openerFk) {
      var token = ++syncSeq, name = dev.name;
      openDialog({ type: 'syncing', device: name, token: token }, openerFk);
      // SyncViewModel's status lines go to the window's snackbar (Main.kt), under the dialog's scrim.
      snack('main', 'Connecting to ' + name + '...');
      setTimer('sync-wait', function () { if (syncDialogOpen(token)) snack('main', 'Waiting for ' + name + ' to accept sync...'); }, 300);
      var handled = false;
      try {
        handled = !hostEl.dispatchEvent(new CustomEvent('vaultpass-demo:sync-request', { bubbles: true, cancelable: true, detail: { from: 'desktop', peerName: name } }));
      } catch (err) { /* no CustomEvent: the demo finishes on its own */ }
      if (!syncDialogOpen(token)) return; // the page synced at once
      setTimer('sync', function () { finishSync(token); }, handled ? SYNC_WAIT_HANDLED : SYNC_WAIT_UNHANDLED);
    }
    function finishSync(token) {
      var d = null;
      S.dialogs = S.dialogs.filter(function (x) { if (x.type === 'syncing' && x.token === token) { d = x; return false; } return true; });
      if (!d) return;
      clearTimer('sync');
      clearTimer('sync-wait');
      var hadFocus = root.contains(document.activeElement);
      S.paired.forEach(function (p) { if (p.name === d.device) p.lastSyncAt = Date.now(); });
      render();
      if (hadFocus && !S.dialogs.length && !focusFk(d.opener)) focusFallback();
      snack('main', 'Devices are in sync (0 entries updated)');
    }
    function cancelSync() {
      clearTimer('sync');
      clearTimer('sync-wait');
      var top = S.dialogs[S.dialogs.length - 1];
      if (!top || top.type !== 'syncing') return;
      closeDialog();
      snack('main', 'Sync cancelled');
    }

    function onInput(ev) {
      var t = ev.target;
      var field = t.getAttribute && t.getAttribute('data-field');
      if (!field) return;
      var v = t.value;
      if (field === 'search-vault') { S.vaultQuery = v; refreshVaultBody(); return; }
      if (field === 'search-dash') { S.dashQuery = v; refreshDashLists(); return; }
      if (field === 'search-bin') { S.binQuery = v; refreshBinBody(); return; }
      if (field === 'lock-pw') {
        S.lock.pw = v;
        if (S.lock.error) {
          S.lock.error = null;
          // Re-rendering replaces the field, which would cancel an IME composition: wait for its end.
          if (ev.isComposing) pendingRender = render; else render();
        }
        return;
      }
      if (field === 'gen-length' || field === 'gen-words') {
        var g = S.gen, key = field === 'gen-length' ? 'length' : 'words', num = parseInt(v, 10);
        if (isNaN(num) || g[key] === num) return;
        g[key] = num;
        regenerate();
        var wrap = t.parentNode, min = key === 'length' ? 8 : 3, max = key === 'length' ? 64 : 8, f = (num - min) / (max - min);
        wrap.style.setProperty('--f', f.toFixed(5));
        Array.prototype.forEach.call(wrap.querySelectorAll('.vpd-tick'), function (tk, i) { tk.classList.toggle('is-active', i / (max - min) <= f); });
        var count = mainEl.querySelector('.vpd-gen-count');
        if (count) count.textContent = key === 'length' ? String(num) : num + ' words';
        refreshGenPartial();
        return;
      }
      if (field === 'plain-pw') {
        var pd = S.dialogs[S.dialogs.length - 1];
        if (!pd || pd.type !== 'plainExport') return;
        var hadPw = !!pd.pw, wasWrong = pd.wrong;
        pd.pw = v;
        pd.wrong = false;
        if (hadPw !== !!v || wasWrong) { if (ev.isComposing) pendingRender = renderLayer; else renderLayer(); }
        return;
      }
      var top = S.dialogs[S.dialogs.length - 1];
      if (!top || top.type !== 'entry') return;
      var dr = top.draft, before = errSignature(dr), m;
      if (field === 'title') { dr.title = v; dr.titleError = false; }
      else if (field === 'username' || field === 'url' || field === 'password' || field === 'notes' || field === 'category' || field === 'tags') dr[field] = v;
      else if ((m = /^cf([kv])(\d+)$/.exec(field))) {
        var cf = dr.cf[parseInt(m[2], 10)];
        if (cf) { if (m[1] === 'k') cf.key = v; else cf.value = v; }
      }
      if (errSignature(dr) !== before) {
        if (ev.isComposing) pendingRender = renderLayer; else renderLayer();
      }
    }

    // A re-render held back during IME composition (see onInput) runs once the composition ends.
    var pendingRender = null;
    function onCompositionEnd() {
      var fn = pendingRender;
      pendingRender = null;
      if (fn && !destroyed) fn();
    }

    function onKeyDown(ev) {
      var key = ev.key;
      if (key === 'Escape') {
        if (S.menu) { closeMenu(); stop(ev); return; }
        if (S.dialogs.length) { dismissTop(); stop(ev); return; }
        return;
      }
      if (S.menu && (key === 'ArrowDown' || key === 'ArrowUp' || key === 'Home' || key === 'End' || key === 'Tab')) {
        var items = Array.prototype.slice.call(layerEl.querySelectorAll('.vpd-menu [role="menuitem"]'));
        if (key === 'Tab') { closeMenu(); stop(ev); return; }
        var i = items.indexOf(document.activeElement);
        var next = key === 'Home' ? 0 : key === 'End' ? items.length - 1 : key === 'ArrowDown' ? (i + 1) % items.length : (i - 1 + items.length) % items.length;
        if (items[next]) items[next].focus();
        stop(ev);
        return;
      }
      if (key === 'Tab' && S.dialogs.length) {
        var dlg = topDialogEl();
        if (!dlg) return;
        var f = focusables(dlg);
        if (!f.length) { ev.preventDefault(); return; }
        var a = document.activeElement;
        // Focus on the dialog itself (after a click on its text) counts as outside its controls.
        var outside = a === dlg || !dlg.contains(a);
        if (ev.shiftKey && (a === f[0] || outside)) { f[f.length - 1].focus(); ev.preventDefault(); }
        else if (!ev.shiftKey && (a === f[f.length - 1] || outside)) { f[0].focus(); ev.preventDefault(); }
        return;
      }
      var t = ev.target;
      // Radio groups and the Settings tab list: one Tab stop each; arrows move and select (ARIA APG).
      var role = t.getAttribute && t.getAttribute('role');
      if ((role === 'radio' || role === 'tab') && /^(ArrowUp|ArrowDown|ArrowLeft|ArrowRight|Home|End)$/.test(key)) {
        var group = t.closest('[role="radiogroup"], [role="tablist"]');
        if (group) {
          var opts = Array.prototype.filter.call(group.querySelectorAll('[role="' + role + '"]'), function (x) { return !x.disabled; });
          var at = opts.indexOf(t), n = opts.length;
          var to = key === 'Home' ? 0 : key === 'End' ? n - 1 : (key === 'ArrowDown' || key === 'ArrowRight') ? (at + 1) % n : (at - 1 + n) % n;
          var nx = opts[to];
          if (nx && nx !== t) {
            var nfk = nx.getAttribute('data-fk');
            nx.focus();
            nx.click();
            focusFk(nfk);
          }
          stop(ev);
          return;
        }
      }
      if (key === 'Enter' && t.getAttribute && t.getAttribute('data-field') === 'lock-pw') { tryUnlock(); ev.preventDefault(); return; }
      if (key === 'Enter' && t.getAttribute && t.getAttribute('data-field') === 'plain-pw') { continuePlainExport(); ev.preventDefault(); return; }
      if ((key === 'ContextMenu' || (key === 'F10' && ev.shiftKey)) && t.classList && t.classList.contains('vpd-row-hit')) {
        openRowMenu(t, null);
        stop(ev);
      }
    }
    function stop(ev) { ev.preventDefault(); ev.stopPropagation(); }

    function openRowMenu(hit, ev) {
      var kind = hit.getAttribute('data-row'), id = hit.getAttribute('data-id');
      var p = posInApp(hit), x, y;
      if (ev && typeof ev.offsetX === 'number' && ev.target === hit) { x = p.x + ev.offsetX; y = p.y + ev.offsetY; }
      else { x = p.x + 24; y = p.y + hit.offsetHeight / 2; }
      // PointerContextMenu offsets the menu 4 dp from the cursor, AppContextMenu (Recycle Bin) 8 dp.
      var off = kind === 'bin' ? 8 : 4;
      openMenu({ type: kind === 'bin' ? 'binCtx' : 'rowCtx', id: id, anchorFk: hit.getAttribute('data-fk'), point: { x: x + off, y: y + off } });
    }

    function onContextMenu(ev) {
      // A right-click outside an open menu only dismisses it (Compose consumes the click), and the
      // browser's own menu must not open on top of the demo's.
      if (ev.target.classList && ev.target.classList.contains('vpd-menu-catcher')) {
        ev.preventDefault();
        closeMenu();
        return;
      }
      var hit = ev.target.closest ? ev.target.closest('.vpd-row-hit, .vpd-row, .vpd-binrow') : null;
      if (!hit || !mainEl.contains(hit)) return;
      if (!hit.classList.contains('vpd-row-hit')) hit = hit.querySelector('.vpd-row-hit');
      if (!hit) return;
      ev.preventDefault();
      openRowMenu(hit, ev);
    }

    function onDblClick(ev) {
      var hit = ev.target.closest ? ev.target.closest('.vpd-row-hit') : null;
      if (!hit || hit.getAttribute('data-row') !== 'vault') return;
      var id = hit.getAttribute('data-id'), e = byId(id), fk = hit.getAttribute('data-fk');
      if (!e) return;
      switch (S.settings.doubleClickBehaviour) {
        case 'Edit Entry': openEntryDialog('edit', id, fk); break;
        // VaultScreen.kt: the direct copy's snackbar names 30 s whatever the timeout setting; the warning path says "Password copied".
        case 'Copy Password': vaultCopySecret(e.password, fk, 'Password copied (clears in 30s)'); break;
        case 'Copy Username': copyToClipboard(e.username); snack('screen', 'Username copied'); break;
        default: selectEntry(id); refreshVaultBody();
      }
    }

    root.addEventListener('click', onClick);
    root.addEventListener('input', onInput);
    root.addEventListener('keydown', onKeyDown);
    root.addEventListener('contextmenu', onContextMenu);
    root.addEventListener('dblclick', onDblClick);
    root.addEventListener('compositionend', onCompositionEnd);

    // ================================================================= first render
    if (S.screen === 'generator') S.gen = newGen();
    if (!S.locked && S.screen === 'security') { S.sec.shown = 0; startScoreAnim(0); }
    render();

    // ================================================================= API
    var api = {
      // Entries (recycle bin included) plus a recycle-bin record for each purged entry with a tombstone,
      // stamped with the purge time, so a merge by newest change carries the purge to the phone.
      getVault: function () {
        return S.entries.map(publicEntry).concat(Object.keys(S.tombs).map(function (k) { return publicEntry(S.tombs[k]); }));
      },
      applySync: function (result) {
        if (destroyed || !result || !Array.isArray(result.vault)) return;
        var keep = [];
        result.vault.forEach(function (raw) {
          var e = normEntry(raw), tomb = S.tombs[e.uid];
          if (tomb) {
            // Changed on the phone after the purge: newest change wins and the entry comes back.
            if (stampOf(e) > stampOf(tomb)) delete S.tombs[e.uid];
            else return;
          }
          keep.push(e);
        });
        S.entries = keep;
        peerKnown = {};
        result.vault.forEach(function (raw) { if (raw && raw.uid) peerKnown[String(raw.uid)] = true; });
        var live = {};
        S.entries.forEach(function (e) { live[e.uid] = true; });
        Object.keys(tagsByUid).forEach(function (k) { if (!live[k]) delete tagsByUid[k]; });
        Object.keys(S.bin.selected).forEach(function (k) { if (!live[k]) delete S.bin.selected[k]; });
        var name = str(result.peerName) || peerName;
        // An unpaired phone stays unpaired: the entries still update, as the page's sync asked.
        if (S.paired.length) {
          S.paired[0].name = name;
          S.paired[0].lastSyncAt = Date.now();
        }
        clearTimer('sync');
        clearTimer('sync-wait');
        // An entry dialog for an entry that is gone closes; the sync dialog closes once the sync is done.
        var syncDialog = S.dialogs.filter(function (d) { return d.type === 'syncing'; })[0] || null;
        var hadFocus = root.contains(document.activeElement);
        S.dialogs = S.dialogs.filter(function (d) {
          if (d.type === 'syncing') return false;
          if (d.type === 'entry' && d.mode === 'edit') { var x = byId(d.id); return !!(x && !x.deletedAt); }
          if (d.type === 'confirmDelete') return !!byId(d.id);
          return true;
        });
        if (S.menu && S.menu.id && !byId(S.menu.id)) S.menu = null;
        if (S.screen === 'security' && !S.locked) startScoreAnim(S.sec.shown);
        render();
        if (syncDialog && hadFocus && !S.dialogs.length && !focusFk(syncDialog.opener)) focusFallback();
        if (!S.locked) {
          var count = (result.added | 0) + (result.updated | 0) + (result.removed | 0);
          snack('main', 'Devices are in sync (' + count + ' entries updated)');
        }
      },
      isUnlocked: function () { return !S.locked; },
      onChange: function (cb) { if (typeof cb === 'function') changeCbs.push(cb); },
      onLockChange: function (cb) { if (typeof cb === 'function') lockCbs.push(cb); },
      showScreen: function (name) {
        if (destroyed) return;
        if (name === 'lock') { lockVault(false); return; }
        if (!SCREENS[name]) return;
        if (S.locked) { S.pendingScreen = name; return; }
        S.dialogs = [];
        clearTimer('sync');
        clearTimer('sync-wait');
        navigate(name);
      },
      destroy: function () {
        if (destroyed) return;
        destroyed = true;
        Object.keys(timers).forEach(clearTimer);
        if (rafId) cancelAnimationFrame(rafId);
        root.removeEventListener('click', onClick);
        root.removeEventListener('input', onInput);
        root.removeEventListener('keydown', onKeyDown);
        root.removeEventListener('contextmenu', onContextMenu);
        root.removeEventListener('dblclick', onDblClick);
        root.removeEventListener('compositionend', onCompositionEnd);
        pendingRender = null;
        changeCbs = [];
        lockCbs = [];
        while (hostEl.firstChild) hostEl.removeChild(hostEl.firstChild);
      }
    };
    return api;
  }

  window.VaultPassDemos = window.VaultPassDemos || {};
  window.VaultPassDemos.desktop = { mount: mount };
})();
