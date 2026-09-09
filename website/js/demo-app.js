/**
 * VaultPass — Interactive Mobile App Replica (1:1 Android Jetpack Compose Simulation)
 * Built directly from the Android Jetpack Compose codebase:
 * - Theme & Accents: Theme.kt, AccentColor.kt (Blue, Green, Purple, Amber)
 * - Navigation: VaultApp.kt (Material 3 NavigationBar)
 * - Dashboard: DashboardScreen.kt (Security Score, 3 StatCards, Favorites LazyRow, Entries, FAB)
 * - Security Center: SecurityScreen.kt (Vault Health 120dp circular score, Strength Distribution, Issues)
 * - Generator: PasswordGeneratorScreen.kt (Monospace display, VerifiedUser badge, 4-stat Analysis, Switches)
 * - Settings: SettingsScreen.kt (Appearance theme & accent dropdowns, Auto-lock dialog, Export formats)
 * - Lock Screen: LockScreen.kt (Biometric fingerprint sensor pulse, Master Password, E2E badge)
 * - Details & Add: PasswordDetailsScreen.kt, PasswordEntryScreen.kt
 * 
 * Strict 640px height with contained internal scrolling — 0 website layout shift.
 */
(function () {
  'use strict';

  // Dual-Palette Material 3 Accent Color System (UI-01) matching AccentColor.kt
  const ACCENTS = {
    blue: {
      title: 'Blue',
      preview: '#005FB0',
      dark: { primary: '#A4C8FF', container: '#004786', onContainer: '#D7E3FF', onPrimary: '#00315E' },
      light: { primary: '#005FB0', container: '#D7E3FF', onContainer: '#001B3E', onPrimary: '#FFFFFF' }
    },
    green: {
      title: 'Green',
      preview: '#006D44',
      dark: { primary: '#79D9A5', container: '#005232', onContainer: '#95F6C0', onPrimary: '#003921' },
      light: { primary: '#006D44', container: '#95F6C0', onContainer: '#002112', onPrimary: '#FFFFFF' }
    },
    purple: {
      title: 'Purple',
      preview: '#64558F',
      dark: { primary: '#CFBDFF', container: '#4B3F73', onContainer: '#E9DDFF', onPrimary: '#34265B' },
      light: { primary: '#64558F', container: '#E9DDFF', onContainer: '#201047', onPrimary: '#FFFFFF' }
    },
    amber: {
      title: 'Amber',
      preview: '#725C00',
      dark: { primary: '#E4C263', container: '#544400', onContainer: '#FFE088', onPrimary: '#3C3000' },
      light: { primary: '#725C00', container: '#FFE088', onContainer: '#231B00', onPrimary: '#FFFFFF' }
    }
  };

  // Compatibility aliases
  ACCENTS.BLUE = ACCENTS.blue;
  ACCENTS.GREEN = ACCENTS.green;
  ACCENTS.PURPLE = ACCENTS.purple;
  ACCENTS.AMBER = ACCENTS.amber;

  // Initial Vault Entries
  const INITIAL_ENTRIES = [
    {
      id: 1,
      title: 'Google',
      username: 'kay.dev@gmail.com',
      password: 'kP9#vL2$uWxR7*Qz',
      category: 'Personal',
      website: 'accounts.google.com',
      notes: 'Primary developer account with 2FA backup keys.',
      isFavorite: true,
      strength: 'strong'
    },
    {
      id: 2,
      title: 'GitHub',
      username: 'kay_codes',
      password: 'ghp_xT4Pz9vL2uW7Qz1',
      category: 'Personal',
      website: 'github.com',
      notes: 'Hardware security key + backup SSH passphrases.',
      isFavorite: true,
      strength: 'strong'
    },
    {
      id: 3,
      title: 'Proton Mail',
      username: 'kay.secure@proton.me',
      password: 'pM8$2Lp@8vW!9xK#',
      category: 'Personal',
      website: 'mail.proton.me',
      notes: 'Encrypted mailbox for financial recovery notifications.',
      isFavorite: true,
      strength: 'strong'
    },
    {
      id: 4,
      title: 'Netflix',
      username: 'kay.family@gmail.com',
      password: 'Summer2023!',
      category: 'Personal',
      website: 'netflix.com',
      notes: 'Family profile account.',
      isFavorite: false,
      strength: 'weak'
    },
    {
      id: 5,
      title: 'Spotify',
      username: 'kay_music',
      password: 'Summer2023!',
      category: 'Personal',
      website: 'spotify.com',
      notes: 'Reused password flagged by Security Analyzer.',
      isFavorite: false,
      strength: 'reused'
    },
    {
      id: 6,
      title: 'Amazon',
      username: 'kay.shopping@gmail.com',
      password: 'Amz#2024$SecureP@ss',
      category: 'Personal',
      website: 'amazon.com',
      notes: '1-click ordering profile.',
      isFavorite: false,
      strength: 'strong'
    }
  ];

  class VaultPassApp {
    constructor() {
      this.state = {
        // Active Screen: 'dashboard' | 'security' | 'generator' | 'settings' | 'entry_details' | 'add_entry'
        activeRoute: 'dashboard',
        isLocked: false,
        selectedEntryId: null,

        // Entries & Search state (STA-03)
        entries: JSON.parse(JSON.stringify(INITIAL_ENTRIES)),
        searchActive: false,
        searchOpen: false,
        searchQuery: '',

        // Theme settings (SettingsScreen.kt)
        themeMode: 0, // 0: System/Dark, 1: Light, 2: Dark
        accentKey: 'blue',

        // Settings switches (SettingsScreen.kt)
        hidePasswordsByDefault: true,
        biometricEnabled: true,
        disableScreenshots: true,
        autoLockTimer: 60000, // 1 min
        showAutoLockDialog: false,
        showExportDialog: false,

        // Generator (PasswordGeneratorScreen.kt)
        genLength: 18,
        useUppercase: true,
        useLowercase: true,
        useNumbers: true,
        useSymbols: true,
        generatedPassword: '',

        // UI Modals & Popups
        toastTimer: null,
        detailPasswordRevealed: false,
        lockPasswordInput: '',
        lockPasswordVisible: false,
        lockError: '',
        biometricScanning: false,

        // Add Entry Form state (PasswordEntryScreen.kt)
        newTitle: '',
        newWebsite: '',
        newUsername: '',
        newPassword: '',
        newPasswordVisible: false,
        newCategory: 'Login',
        newNotes: ''
      };

      this.root = document.getElementById('vaultpass-demo-app');
      if (!this.root) return;

      this.generatePassword();
      this.render();
      this.bindEvents();
    }

    // Generator logic matching PasswordGeneratorScreen.kt
    generatePassword() {
      const upper = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
      const lower = 'abcdefghijklmnopqrstuvwxyz';
      const numbers = '0123456789';
      const symbols = '!@#$%^&*()_+-=[]{}|;:,.<>?';

      let pool = '';
      if (this.state.useUppercase) pool += upper;
      if (this.state.useLowercase) pool += lower;
      if (this.state.useNumbers) pool += numbers;
      if (this.state.useSymbols) pool += symbols;

      if (!pool) {
        this.state.generatedPassword = '';
        return;
      }

      const len = Math.max(8, Math.min(64, parseInt(this.state.genLength, 10) || 18));
      const randBytes = new Uint32Array(len);
      window.crypto.getRandomValues(randBytes);

      let pwd = '';
      for (let i = 0; i < len; i++) {
        pwd += pool[randBytes[i] % pool.length];
      }
      this.state.generatedPassword = pwd;
    }

    // Entropy and strength analysis matching PasswordGeneratorScreen.kt
    getGeneratorAnalysis() {
      let poolSize = 0;
      if (this.state.useUppercase) poolSize += 26;
      if (this.state.useLowercase) poolSize += 26;
      if (this.state.useNumbers) poolSize += 10;
      if (this.state.useSymbols) poolSize += 32;
      if (poolSize === 0) poolSize = 1;

      const len = this.state.genLength;
      const entropy = poolSize > 1 ? Math.floor(len * Math.log2(poolSize)) : 0;

      let label = 'Strong';
      let colorClass = 'text-[var(--md-primary,#a4c8ff)] bg-[var(--md-primary-container,#004786)]/30 border-[var(--md-primary,#a4c8ff)]/40';
      let crackTime = 'Centuries';

      if (entropy < 50) {
        label = 'Weak';
        colorClass = 'text-[#ffb4ab] bg-[#93000a]/30 border-[#ffb4ab]/40';
        crackTime = entropy < 40 ? 'Instantly' : 'Hours';
      } else if (entropy < 80) {
        label = 'Good';
        colorClass = 'text-[#ffb4a9] bg-[#930006]/30 border-[#ffb4a9]/40';
        crackTime = entropy < 60 ? 'Hours' : 'Months';
      } else if (entropy < 100) {
        label = 'Strong';
        crackTime = 'Years';
      }

      return { entropy, label, colorClass, crackTime };
    }

    // Security stats calculation matching SecurityScreen.kt
    getSecurityStats() {
      const total = this.state.entries.length;
      let weak = 0;
      let reused = 0;
      let medium = 0;
      let strong = 0;

      this.state.entries.forEach(e => {
        if (e.strength === 'weak') weak++;
        else if (e.strength === 'reused') {
          reused++;
          weak++;
        } else if (e.password.length < 12) {
          medium++;
        } else {
          strong++;
        }
      });

      // Score 0-100
      let score = 100;
      if (weak > 0) score -= weak * 10;
      if (reused > 0) score -= reused * 8;
      score = Math.max(10, Math.min(100, score));

      let status = 'Excellent';
      if (score < 60) status = 'Needs Attention';
      else if (score < 85) status = 'Good';

      return { total, weak, reused, medium, strong, score, status };
    }

    // Theme Color Tokens with WCAG AA compliance (UI-01)
    getThemeColors() {
      const isLight = this.state.themeMode === 1;
      const key = (this.state.accentKey || 'blue').toLowerCase();
      const acc = ACCENTS[key] || ACCENTS.blue;
      const variant = isLight ? acc.light : acc.dark;

      return {
        isLight,
        primary: variant.primary,
        onPrimary: variant.onPrimary,
        primaryContainer: variant.container,
        onPrimaryContainer: variant.onContainer,
        bg: isLight ? '#FDFBFF' : '#1B1B1F',
        surface: isLight ? '#FDFBFF' : '#1B1B1F',
        surfaceVariant: isLight ? '#F3F3FA' : '#2A2B32',
        surfaceCard: isLight ? '#FFFFFF' : '#23242A',
        onSurface: isLight ? '#1A1C1E' : '#E2E2E6',
        onSurfaceVariant: isLight ? '#43474E' : '#C4C6D0',
        secondaryContainer: isLight ? variant.container : '#2F3138',
        onSecondaryContainer: isLight ? variant.onContainer : '#E2E2E6',
        outline: isLight ? '#74777F' : '#8E9099'
      };
    }

    // Decoupled in-place Toast notification without full re-render (UX-01)
    showToast(msg) {
      const toast = this.root.querySelector('#demo-toast');
      if (!toast) return;

      toast.innerHTML = `
        <div class="px-4 py-2 rounded-full text-xs font-semibold shadow-2xl flex items-center gap-2 whitespace-nowrap" style="background-color: var(--md-primary-container); color: var(--md-on-primary-container); border: 1px solid var(--md-primary);">
          <svg class="w-3.5 h-3.5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M5 13l4 4L19 7"/></svg>
          <span>${this.escapeHtml(msg)}</span>
        </div>
      `;
      toast.classList.remove('opacity-0', '-translate-y-2');
      toast.classList.add('opacity-100', 'translate-y-0');

      if (this.state.toastTimer) clearTimeout(this.state.toastTimer);
      this.state.toastTimer = setTimeout(() => {
        toast.classList.remove('opacity-100', 'translate-y-0');
        toast.classList.add('opacity-0', '-translate-y-2');
      }, 2200);
    }

    // Safe clipboard copy helper with execCommand fallback (SEC-02)
    async copyText(text, label = 'Password') {
      if (!text) return;
      let success = false;
      if (window.isSecureContext && navigator.clipboard && navigator.clipboard.writeText) {
        try {
          await navigator.clipboard.writeText(text);
          success = true;
        } catch {
          success = this.fallbackCopyText(text);
        }
      } else {
        success = this.fallbackCopyText(text);
      }

      if (success) {
        this.showToast(`${label} copied to clipboard`);
      }
    }

    fallbackCopyText(text) {
      try {
        const textArea = document.createElement('textarea');
        textArea.value = text;
        textArea.style.position = 'fixed';
        textArea.style.left = '-9999px';
        textArea.style.top = '-9999px';
        textArea.style.opacity = '0';
        document.body.appendChild(textArea);
        textArea.focus();
        textArea.select();
        const res = document.execCommand('copy');
        document.body.removeChild(textArea);
        return res;
      } catch {
        return false;
      }
    }

    // HTML sanitization with null/undefined protection (SEC-01)
    escapeHtml(str) {
      if (str === null || str === undefined) return '';
      const s = String(str);
      return s
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
    }

    // In-place DOM update for Generator Screen (UX-01)
    updateGeneratorDisplay() {
      const lenDisplay = this.root.querySelector('#gen-len-display');
      if (lenDisplay) lenDisplay.textContent = this.state.genLength;

      const outputVal = this.root.querySelector('#gen-output-val');
      if (outputVal) outputVal.textContent = this.state.generatedPassword || '—';

      const analysis = this.getGeneratorAnalysis();

      const badge = this.root.querySelector('#gen-strength-badge');
      if (badge) {
        badge.className = `text-[10px] font-bold px-2 py-0.5 rounded-full border ${analysis.colorClass}`;
        badge.textContent = analysis.label;
      }

      const bar = this.root.querySelector('#gen-strength-bar');
      if (bar) {
        bar.style.width = `${Math.min(100, Math.round((analysis.entropy / 120) * 100))}%`;
      }

      const entropyVal = this.root.querySelector('#gen-entropy-val');
      if (entropyVal) entropyVal.textContent = `${analysis.entropy} bits`;

      const crackVal = this.root.querySelector('#gen-crack-val');
      if (crackVal) crackVal.textContent = analysis.crackTime;
    }

    // Close any open modal dialogs (MOD-01)
    closeDialogs() {
      if (this.state.showAutoLockDialog || this.state.showExportDialog) {
        this.state.showAutoLockDialog = false;
        this.state.showExportDialog = false;
        this.render();
      }
    }

    // Render entries list HTML (used by Dashboard & in-place search)
    renderEntriesList(entries) {
      if (!entries || entries.length === 0) {
        return `
          <div class="p-6 text-center text-xs" style="color: var(--md-on-surface-variant);">
            No matching entries found
          </div>
        `;
      }

      return entries.map(entry => `
        <div data-entry-id="${entry.id}" class="p-3 rounded-xl flex items-center justify-between border border-white/5 cursor-pointer hover:border-[var(--md-primary)]/40 transition-all" style="background-color: var(--md-surface-card);">
          <div class="flex items-center gap-3 min-w-0">
            <div class="w-9 h-9 rounded-lg flex items-center justify-center font-bold text-xs flex-shrink-0" style="background-color: var(--md-surface-variant); color: var(--md-primary);">
              ${this.escapeHtml(Array.from(entry.title || ' ')[0])}
            </div>
            <div class="min-w-0">
              <div class="text-xs font-semibold truncate">${this.escapeHtml(entry.title)}</div>
              <div class="text-[10px] truncate font-mono" style="color: var(--md-on-surface-variant);">${this.escapeHtml(entry.username)}</div>
            </div>
          </div>
          
          <div class="flex items-center gap-1.5 flex-shrink-0">
            ${entry.isFavorite ? `
              <svg class="w-3.5 h-3.5 text-[var(--md-primary)]" fill="currentColor" viewBox="0 0 24 24"><path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"/></svg>
            ` : ''}
            <button data-action="copy-quick" data-pass="${this.escapeHtml(entry.password)}" title="Copy Password" class="p-1.5 rounded-lg hover:text-[var(--md-primary)] hover:bg-black/5 dark:hover:bg-white/5" style="color: var(--md-on-surface-variant);">
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"/></svg>
            </button>
          </div>
        </div>
      `).join('');
    }

    // Mock Frame container with fluid width & root overlays (RSP-01, MOD-01, UX-01)
    renderFrame() {
      const colors = this.getThemeColors();
      const themeCssVars = `
        --md-primary: ${colors.primary};
        --md-on-primary: ${colors.onPrimary};
        --md-primary-container: ${colors.primaryContainer};
        --md-on-primary-container: ${colors.onPrimaryContainer};
        --md-bg: ${colors.bg};
        --md-surface: ${colors.surface};
        --md-surface-variant: ${colors.surfaceVariant};
        --md-surface-card: ${colors.surfaceCard};
        --md-on-surface: ${colors.onSurface};
        --md-on-surface-variant: ${colors.onSurfaceVariant};
        --md-secondary-container: ${colors.secondaryContainer};
        --md-on-secondary-container: ${colors.onSecondaryContainer};
        --md-outline: ${colors.outline};
      `;

      return `
        <div 
          class="mobile-device-frame relative mx-auto w-[320px] sm:w-[340px] max-w-full h-[640px] rounded-[42px] border-[8px] border-[#182338] shadow-[0_25px_60px_-15px_rgba(0,0,0,0.9),0_0_40px_rgba(95,251,214,0.08)] flex flex-col overflow-hidden select-none"
          style="contain: layout paint; ${themeCssVars}; background-color: var(--md-bg); color: var(--md-on-surface);"
        >
          <!-- Android Status Bar -->
          <div class="h-7 px-5 flex items-center justify-between text-[10px] font-mono flex-shrink-0 z-30" style="background-color: var(--md-surface); color: var(--md-on-surface-variant); border-bottom: 1px solid var(--md-outline);">
            <span class="font-bold">9:41</span>
            
            <!-- Camera Punch-Hole -->
            <div class="w-3 h-3 rounded-full bg-black border border-white/10 flex items-center justify-center">
              <div class="w-1 h-1 rounded-full bg-[#111e33]"></div>
            </div>

            <div class="flex items-center gap-1.5 text-[9px]">
              <svg class="w-2.5 h-2.5" fill="currentColor" viewBox="0 0 24 24"><path d="M12 3c-4.97 0-9 4.03-9 9 0 2.12.74 4.07 1.97 5.61L4.35 19.4c-.39.39-.39 1.02 0 1.41.39.39 1.02.39 1.41 0l1.9-1.9C9.28 19.67 10.59 20 12 20c4.97 0 9-4.03 9-9s-4.03-9-9-9z"/></svg>
              <span>5G</span>
              <svg class="w-3 h-3 text-[var(--md-primary)]" fill="currentColor" viewBox="0 0 24 24"><path d="M15.67 4H14V2h-4v2H8.33C7.6 4 7 4.6 7 5.33v15.33C7 21.4 7.6 22 8.33 22h7.33c.74 0 1.34-.6 1.34-1.33V5.33C17 4.6 16.4 4 15.67 4z"/></svg>
            </div>
          </div>

          <!-- Main Internal Screen Viewport -->
          <div class="flex-1 overflow-hidden relative flex flex-col" style="background-color: var(--md-bg);">
            ${this.renderActiveScreen()}
          </div>

          <!-- Material 3 Bottom Navigation Bar (VaultApp.kt) -->
          ${!this.state.isLocked && !['entry_details', 'add_entry'].includes(this.state.activeRoute) ? this.renderBottomBar() : ''}

          <!-- Android Bottom Gesture Line -->
          <div class="h-3 flex items-center justify-center flex-shrink-0" style="background-color: var(--md-surface);">
            <div class="w-24 h-1 rounded-full" style="background-color: var(--md-on-surface-variant); opacity: 0.3;"></div>
          </div>

          <!-- Persistent Demo Toast Container (UX-01) -->
          <div id="demo-toast" class="absolute top-12 left-1/2 -translate-x-1/2 z-50 pointer-events-none transition-all duration-300 opacity-0 -translate-y-2"></div>

          <!-- Root Mounted Auto-Lock Dialog (MOD-01) -->
          ${this.state.showAutoLockDialog ? this.renderAutoLockDialog() : ''}

          <!-- Root Mounted Export Dialog (MOD-01) -->
          ${this.state.showExportDialog ? this.renderExportDialog() : ''}
        </div>
      `;
    }

    // MASTER RENDER
    render() {
      if (!this.root) return;
      this.root.innerHTML = this.renderFrame();
    }

    renderActiveScreen() {
      if (this.state.isLocked) {
        return this.renderLockScreen();
      }

      switch (this.state.activeRoute) {
        case 'dashboard':
          return this.renderDashboardScreen();
        case 'security':
          return this.renderSecurityScreen();
        case 'generator':
          return this.renderGeneratorScreen();
        case 'settings':
          return this.renderSettingsScreen();
        case 'entry_details':
          return this.renderDetailsScreen();
        case 'add_entry':
          return this.renderAddEntryScreen();
        default:
          return this.renderDashboardScreen();
      }
    }

    // 1. DASHBOARD SCREEN (DashboardScreen.kt)
    renderDashboardScreen() {
      const stats = this.getSecurityStats();
      const q = this.state.searchQuery.toLowerCase().trim();
      const filtered = this.state.entries.filter(e =>
        !q || e.title.toLowerCase().includes(q) || e.username.toLowerCase().includes(q)
      );
      const favorites = this.state.entries.filter(e => e.isFavorite);

      return `
        <!-- TopAppBar (DashboardScreen.kt line 92) -->
        <div class="px-4 py-2.5 flex items-center justify-between flex-shrink-0" style="background-color: var(--md-surface); border-bottom: 1px solid var(--md-outline);">
          <div class="flex items-center gap-2">
            <svg class="w-5 h-5 text-[var(--md-primary)]" fill="currentColor" viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 6c1.66 0 3 1.34 3 3v2h1v6H8v-6h1v-2c0-1.66 1.34-3 3-3zm1.5 5h-3v-2c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5v2z"/></svg>
            <span class="text-lg font-bold text-[var(--md-primary)] tracking-tight">VaultPass</span>
          </div>

          <div class="flex items-center gap-1">
            <button data-action="toggle-search" class="p-2 rounded-full hover:bg-black/5 dark:hover:bg-white/5 cursor-pointer" style="color: var(--md-on-surface-variant);">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/></svg>
            </button>
            <button data-action="lock-vault" title="Lock Vault" class="p-2 rounded-full hover:bg-black/5 dark:hover:bg-white/5 cursor-pointer" style="color: var(--md-on-surface-variant);">
              <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/></svg>
            </button>
          </div>
        </div>

        <!-- Search Bar Input if Active -->
        ${(this.state.searchActive || this.state.searchOpen) ? `
          <div class="px-4 pt-2 pb-1 flex items-center gap-2 flex-shrink-0" style="background-color: var(--md-surface);">
            <div class="relative flex-1">
              <input 
                type="text" 
                id="search-input" 
                placeholder="Search entries..." 
                value="${this.escapeHtml(this.state.searchQuery)}"
                class="w-full rounded-xl px-3 py-1.5 pl-8 text-xs focus:outline-none"
                style="background-color: var(--md-surface-variant); color: var(--md-on-surface); border: 1px solid var(--md-primary);"
              >
              <svg class="w-3.5 h-3.5 absolute left-2.5 top-2.5" style="color: var(--md-on-surface-variant);" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/></svg>
            </div>
            <button data-action="close-search" class="text-xs text-[var(--md-primary)] font-semibold p-1 cursor-pointer">Done</button>
          </div>
        ` : ''}

        <!-- Scrollable Body (LazyColumn) -->
        <div class="flex-1 overflow-y-auto px-4 py-3 space-y-4 pb-20 no-scrollbar">
          
          <!-- Welcome & Security Score (DashboardScreen.kt lines 128-161) -->
          <div>
            <h2 class="text-lg font-bold text-[var(--md-primary)] tracking-tight">Welcome back, Kay</h2>
          </div>

          <div data-nav="security" class="p-4 rounded-2xl flex items-center gap-4 cursor-pointer hover:border-[var(--md-primary)]/40 transition-all border border-transparent" style="background-color: var(--md-secondary-container);">
            <!-- Circular Progress Indicator (64dp) -->
            <div class="relative w-12 h-12 flex-shrink-0 flex items-center justify-center">
              <svg class="w-12 h-12 transform -rotate-90" viewBox="0 0 36 36">
                <path class="text-white/10" stroke-width="3.5" stroke="currentColor" fill="none" d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"/>
                <path class="text-[var(--md-primary)]" stroke-dasharray="${stats.score}, 100" stroke-width="3.5" stroke-linecap="round" stroke="currentColor" fill="none" d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"/>
              </svg>
              <span class="absolute text-xs font-bold text-[var(--md-primary)]">${stats.score}</span>
            </div>
            <div>
              <div class="text-xs font-bold" style="color: var(--md-on-secondary-container);">Security Score</div>
              <div class="text-[11px]" style="color: var(--md-on-surface-variant);">${stats.status}</div>
            </div>
          </div>

          <!-- Stats Row: Total, Weak, Reused (DashboardScreen.kt lines 163-176) -->
          <div class="grid grid-cols-3 gap-2.5 text-center">
            <div class="p-3 rounded-xl border border-white/5" style="background-color: var(--md-surface-card);">
              <div class="text-base font-bold text-[var(--md-primary)] leading-tight">${stats.total}</div>
              <div class="text-[9px] uppercase tracking-wider font-semibold mt-1" style="color: var(--md-on-surface-variant);">Total</div>
            </div>
            <div data-nav="security" class="p-3 rounded-xl border border-white/5 cursor-pointer hover:border-red-500/40" style="background-color: var(--md-surface-card);">
              <div class="text-base font-bold text-[#ffb4ab] leading-tight">${stats.weak}</div>
              <div class="text-[9px] uppercase tracking-wider font-semibold mt-1" style="color: var(--md-on-surface-variant);">Weak</div>
            </div>
            <div data-nav="security" class="p-3 rounded-xl border border-white/5 cursor-pointer hover:border-orange-500/40" style="background-color: var(--md-surface-card);">
              <div class="text-base font-bold text-[#ffb4a9] leading-tight">${stats.reused}</div>
              <div class="text-[9px] uppercase tracking-wider font-semibold mt-1" style="color: var(--md-on-surface-variant);">Reused</div>
            </div>
          </div>

          <!-- Favorites LazyRow (DashboardScreen.kt lines 178-225) -->
          ${favorites.length > 0 ? `
            <div>
              <div class="text-xs font-semibold mb-2" style="color: var(--md-on-surface);">Favorites</div>
              <div class="flex gap-2.5 overflow-x-auto pb-1 no-scrollbar">
                ${favorites.map(fav => `
                  <div data-entry-id="${fav.id}" class="p-3 rounded-2xl min-w-[96px] flex flex-col items-center text-center cursor-pointer hover:border-[var(--md-primary)]/40 border border-white/5 transition-all" style="background-color: var(--md-surface-card);">
                    <div class="w-10 h-10 rounded-full flex items-center justify-center font-bold text-sm mb-1.5 shadow-sm" style="background-color: var(--md-primary-container); color: var(--md-on-primary-container);">
                      ${this.escapeHtml(Array.from(fav.title || ' ')[0])}
                    </div>
                    <span class="text-[11px] font-semibold truncate max-w-[80px]">${this.escapeHtml(fav.title)}</span>
                    <span class="text-[9px]" style="color: var(--md-on-surface-variant);">Personal</span>
                  </div>
                `).join('')}
              </div>
            </div>
          ` : ''}

          <!-- Recently Accessed (DashboardScreen.kt lines 258-314) -->
          <div>
            <div class="text-xs font-semibold mb-2" style="color: var(--md-on-surface);">Recently Accessed</div>
            <div id="entries-container" class="space-y-2">
              ${this.renderEntriesList(filtered)}
            </div>
          </div>

        </div>

        <!-- Floating Action Button (+) (DashboardScreen.kt lines 114-122) -->
        <button 
          data-action="open-add" 
          title="Add Password"
          class="absolute bottom-16 right-4 w-12 h-12 rounded-2xl flex items-center justify-center shadow-lg cursor-pointer transition-transform hover:scale-105 z-30"
          style="background-color: var(--md-primary-container); color: var(--md-on-primary-container);"
        >
          <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M12 6v12m6-6H6"/></svg>
        </button>
      `;
    }

    // 2. SECURITY CENTER (SecurityScreen.kt)
    renderSecurityScreen() {
      const stats = this.getSecurityStats();
      const strongPct = stats.total > 0 ? Math.round((stats.strong / stats.total) * 100) : 0;
      const mediumPct = stats.total > 0 ? Math.round((stats.medium / stats.total) * 100) : 0;
      const weakPct = stats.total > 0 ? Math.round((stats.weak / stats.total) * 100) : 0;

      return `
        <!-- TopAppBar (SecurityScreen.kt lines 30-47) -->
        <div class="px-4 py-2.5 flex items-center justify-between flex-shrink-0" style="background-color: var(--md-surface); border-bottom: 1px solid var(--md-outline);">
          <div class="flex items-center gap-2">
            <svg class="w-5 h-5 text-[var(--md-primary)]" fill="currentColor" viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 6c1.66 0 3 1.34 3 3v2h1v6H8v-6h1v-2c0-1.66 1.34-3 3-3zm1.5 5h-3v-2c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5v2z"/></svg>
            <span class="text-lg font-bold text-[var(--md-primary)] tracking-tight">Security Center</span>
          </div>
          <button data-action="lock-vault" title="Lock Vault" class="p-2 rounded-full hover:opacity-80 cursor-pointer" style="color: var(--md-on-surface-variant);">
            <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/></svg>
          </button>
        </div>

        <!-- Scrollable Security Center Content -->
        <div class="flex-1 overflow-y-auto px-4 py-3 space-y-4 pb-20 no-scrollbar">
          
          <!-- Vault Health Header (SecurityScreen.kt lines 55-92) -->
          <div>
            <h2 class="text-base font-bold text-[var(--md-primary)]">Vault Health</h2>
            <p class="text-[11px] mt-0.5" style="color: var(--md-on-surface-variant);">Overall security rating of your stored credentials</p>
          </div>

          <!-- Large 120dp Circular Meter Card -->
          <div class="p-5 rounded-2xl text-center space-y-3" style="background-color: var(--md-secondary-container);">
            <div class="relative w-28 h-28 mx-auto flex items-center justify-center">
              <svg class="w-28 h-28 transform -rotate-90" viewBox="0 0 36 36">
                <path class="text-white/10" stroke-width="3" stroke="currentColor" fill="none" d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"/>
                <path class="text-[var(--md-primary)]" stroke-dasharray="${stats.score}, 100" stroke-width="3" stroke-linecap="round" stroke="currentColor" fill="none" d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"/>
              </svg>
              <div class="absolute text-center">
                <span class="text-3xl font-black text-[var(--md-primary)]">${stats.score}</span>
              </div>
            </div>
            <div class="text-sm font-semibold" style="color: var(--md-on-secondary-container);">${stats.status}</div>
          </div>

          <!-- Password Strength Distribution (SecurityScreen.kt lines 94-144) -->
          <div class="p-4 rounded-xl border border-white/5 space-y-3" style="background-color: var(--md-surface-card);">
            <div class="text-xs font-semibold" style="color: var(--md-on-surface);">Password Strength Distribution</div>
            
            <!-- Strong -->
            <div class="space-y-1">
              <div class="flex justify-between text-[11px]">
                <span class="font-semibold">Strong</span>
                <span style="color: var(--md-on-surface-variant);">${stats.strong} (${strongPct}%)</span>
              </div>
              <div class="w-full h-1.5 rounded-full bg-white/10 overflow-hidden">
                <div class="h-full rounded-full bg-[var(--md-primary)]" style="width: ${strongPct}%;"></div>
              </div>
            </div>

            <!-- Medium -->
            <div class="space-y-1">
              <div class="flex justify-between text-[11px]">
                <span class="font-semibold">Medium</span>
                <span style="color: var(--md-on-surface-variant);">${stats.medium} (${mediumPct}%)</span>
              </div>
              <div class="w-full h-1.5 rounded-full bg-white/10 overflow-hidden">
                <div class="h-full rounded-full bg-[#ffb4a9]" style="width: ${mediumPct}%;"></div>
              </div>
            </div>

            <!-- Weak -->
            <div class="space-y-1">
              <div class="flex justify-between text-[11px]">
                <span class="font-semibold">Weak</span>
                <span style="color: var(--md-on-surface-variant);">${stats.weak} (${weakPct}%)</span>
              </div>
              <div class="w-full h-1.5 rounded-full bg-white/10 overflow-hidden">
                <div class="h-full rounded-full bg-[#ffb4ab]" style="width: ${weakPct}%;"></div>
              </div>
            </div>
          </div>

          <!-- Security Issues (SecurityScreen.kt lines 146-227) -->
          <div class="space-y-2">
            <div class="text-xs font-semibold" style="color: var(--md-on-surface);">Security Issues</div>

            <!-- Weak Passwords Item -->
            <div class="p-3 rounded-xl border border-white/5 flex items-center justify-between" style="background-color: var(--md-surface-card);">
              <div class="flex items-center gap-3">
                <div class="w-8 h-8 rounded-full flex items-center justify-center bg-[#93000a] text-[#ffb4ab]">
                  <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg>
                </div>
                <div>
                  <div class="text-xs font-semibold">Weak Passwords</div>
                  <div class="text-[10px]" style="color: var(--md-on-surface-variant);">Passwords vulnerable to brute-force</div>
                </div>
              </div>
              <span class="text-xs font-bold text-[#ffb4ab]">${stats.weak}</span>
            </div>

            <!-- Reused Passwords Item -->
            <div class="p-3 rounded-xl border border-white/5 flex items-center justify-between" style="background-color: var(--md-surface-card);">
              <div class="flex items-center gap-3">
                <div class="w-8 h-8 rounded-full flex items-center justify-center bg-[#930006] text-[#ffb4a9]">
                  <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>
                </div>
                <div>
                  <div class="text-xs font-semibold">Reused Passwords: ${stats.reused}</div>
                  <div class="text-[10px]" style="color: var(--md-on-surface-variant);">Passwords used on multiple accounts</div>
                </div>
              </div>
              <span class="text-xs font-bold text-[#ffb4a9]">${stats.reused}</span>
            </div>

            <!-- Missing Passwords Item -->
            <div class="p-3 rounded-xl border border-white/5 flex items-center justify-between" style="background-color: var(--md-surface-card);">
              <div class="flex items-center gap-3">
                <div class="w-8 h-8 rounded-full flex items-center justify-center bg-[#93000a] text-[#ffb4ab]">
                  <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/></svg>
                </div>
                <div>
                  <div class="text-xs font-semibold">Missing Passwords: 0</div>
                  <div class="text-[10px]" style="color: var(--md-on-surface-variant);">Accounts without credentials</div>
                </div>
              </div>
              <span class="text-xs font-bold" style="color: var(--md-on-surface-variant);">0</span>
            </div>
          </div>

          <!-- Dynamic Recommendations (SecurityScreen.kt lines 229-286) -->
          <div class="p-3.5 rounded-xl border border-white/5 flex items-center gap-3" style="background-color: var(--md-surface-card);">
            <svg class="w-6 h-6 text-[var(--md-primary)] flex-shrink-0" fill="currentColor" viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16l-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z"/></svg>
            <div>
              <div class="text-xs font-semibold">Update Weak Passwords</div>
              <div class="text-[10px]" style="color: var(--md-on-surface-variant);">Strengthen your 2 flagged passwords to reach 100% health rating.</div>
            </div>
          </div>

        </div>
      `;
    }

    // 3. GENERATOR SCREEN (PasswordGeneratorScreen.kt)
    renderGeneratorScreen() {
      const analysis = this.getGeneratorAnalysis();

      return `
        <!-- TopAppBar (PasswordGeneratorScreen.kt lines 116-131) -->
        <div class="px-4 py-2.5 flex items-center justify-between flex-shrink-0" style="background-color: var(--md-surface); border-bottom: 1px solid var(--md-outline);">
          <div class="flex items-center gap-2">
            <button data-nav="dashboard" class="p-1 rounded-full hover:opacity-80 cursor-pointer" style="color: var(--md-on-surface-variant);">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18"/></svg>
            </button>
            <svg class="w-5 h-5 text-[var(--md-primary)]" fill="currentColor" viewBox="0 0 24 24"><path d="M12.65 10C11.83 7.67 9.61 6 7 6c-3.31 0-6 2.69-6 6s2.69 6 6 6c2.61 0 4.83-1.67 5.65-4H17v4h4v-4h2v-4H12.65zM7 14c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"/></svg>
            <span class="text-lg font-bold text-[var(--md-primary)] tracking-tight">Generator</span>
          </div>
        </div>

        <div class="flex-1 overflow-y-auto px-4 py-3 space-y-4 pb-20 no-scrollbar">
          
          <!-- Password Display Card (PasswordGeneratorScreen.kt lines 142-228) -->
          <div class="p-4 rounded-2xl border border-white/5 space-y-3" style="background-color: var(--md-surface-card);">
            <div class="flex items-center justify-between">
              <span class="text-[10px] uppercase font-bold tracking-wider" style="color: var(--md-on-surface-variant);">GENERATED PASSWORD</span>
              <span id="gen-strength-badge" class="text-[10px] font-bold px-2 py-0.5 rounded-full border ${analysis.colorClass}">
                ${analysis.label}
              </span>
            </div>

            <div id="gen-output-val" class="p-3 rounded-xl border border-white/10 text-center min-h-[64px] flex items-center justify-center font-mono font-bold text-sm tracking-wider break-all text-[var(--md-primary)]" style="background-color: var(--md-surface);">
              ${this.escapeHtml(this.state.generatedPassword || '—')}
            </div>

            <div class="flex items-center gap-2">
              <button data-action="copy-generated" class="flex-1 py-2.5 px-3 rounded-xl font-semibold text-xs flex items-center justify-center gap-2 cursor-pointer shadow-sm" style="background-color: var(--md-primary-container); color: var(--md-on-primary-container);">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"/></svg>
                <span>Copy Password</span>
              </button>

              <button data-action="regen-password" title="Regenerate" class="p-2.5 rounded-xl border hover:opacity-80 cursor-pointer" style="background-color: var(--md-surface); color: var(--md-on-surface-variant); border-color: var(--md-outline);">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/></svg>
              </button>
            </div>
          </div>

          <!-- Security Analysis Card (PasswordGeneratorScreen.kt lines 230-254) -->
          <div class="p-4 rounded-2xl border border-white/5 space-y-3" style="background-color: var(--md-surface-card);">
            <span class="text-[10px] uppercase font-bold tracking-wider" style="color: var(--md-on-surface-variant);">SECURITY ANALYSIS</span>
            
            <div class="w-full h-1.5 rounded-full bg-white/10 overflow-hidden">
              <div id="gen-strength-bar" class="h-full rounded-full bg-[var(--md-primary)]" style="width: ${Math.min(100, Math.round((analysis.entropy / 120) * 100))}%;"></div>
            </div>

            <div class="grid grid-cols-4 gap-1 text-center">
              <div>
                <div class="text-[9px]" style="color: var(--md-on-surface-variant);">Entropy</div>
                <div id="gen-entropy-val" class="text-[11px] font-bold text-[var(--md-primary)] mt-0.5">${analysis.entropy} bits</div>
              </div>
              <div>
                <div class="text-[9px]" style="color: var(--md-on-surface-variant);">Crack Time</div>
                <div id="gen-crack-val" class="text-[11px] font-bold text-[var(--md-primary)] mt-0.5">${analysis.crackTime}</div>
              </div>
              <div>
                <div class="text-[9px]" style="color: var(--md-on-surface-variant);">Pattern</div>
                <div class="text-[11px] font-bold text-[var(--md-primary)] mt-0.5">Random</div>
              </div>
              <div>
                <div class="text-[9px]" style="color: var(--md-on-surface-variant);">Pwned</div>
                <div class="text-[11px] font-bold text-[var(--md-primary)] mt-0.5">Clear</div>
              </div>
            </div>
          </div>

          <!-- Configuration Card (PasswordGeneratorScreen.kt lines 256-291) -->
          <div class="p-4 rounded-2xl border border-white/5 space-y-3" style="background-color: var(--md-surface-card);">
            <span class="text-[10px] uppercase font-bold tracking-wider text-[var(--md-primary)]">CONFIGURATION</span>
            
            <div class="space-y-1.5 pt-1">
              <div class="flex justify-between items-center text-xs font-semibold">
                <span>Length</span>
                <span id="gen-len-display" class="font-mono text-xs px-2 py-0.5 rounded border text-[var(--md-primary)]" style="background-color: var(--md-surface); border-color: var(--md-outline);">${this.state.genLength}</span>
              </div>
              <input 
                type="range" 
                id="gen-slider" 
                min="8" 
                max="64" 
                value="${this.state.genLength}" 
                class="m3-slider"
              >
            </div>

            <div class="space-y-2 pt-2">
              <!-- Uppercase -->
              <div class="flex items-center justify-between p-2.5 rounded-xl border border-white/5" style="background-color: var(--md-surface);">
                <div class="flex items-center gap-2.5">
                  <div class="w-7 h-7 rounded border border-white/10 flex items-center justify-center font-mono font-bold text-xs text-[var(--md-primary)]">A</div>
                  <span class="text-xs font-semibold">Uppercase</span>
                </div>
                <label class="m3-switch">
                  <input type="checkbox" id="toggle-upper" ${this.state.useUppercase ? 'checked' : ''}>
                  <span class="m3-switch-track"><span class="m3-switch-thumb"></span></span>
                </label>
              </div>

              <!-- Lowercase -->
              <div class="flex items-center justify-between p-2.5 rounded-xl border border-white/5" style="background-color: var(--md-surface);">
                <div class="flex items-center gap-2.5">
                  <div class="w-7 h-7 rounded border border-white/10 flex items-center justify-center font-mono font-bold text-xs text-[var(--md-primary)]">a</div>
                  <span class="text-xs font-semibold">Lowercase</span>
                </div>
                <label class="m3-switch">
                  <input type="checkbox" id="toggle-lower" ${this.state.useLowercase ? 'checked' : ''}>
                  <span class="m3-switch-track"><span class="m3-switch-thumb"></span></span>
                </label>
              </div>

              <!-- Numbers -->
              <div class="flex items-center justify-between p-2.5 rounded-xl border border-white/5" style="background-color: var(--md-surface);">
                <div class="flex items-center gap-2.5">
                  <div class="w-7 h-7 rounded border border-white/10 flex items-center justify-center font-mono font-bold text-xs text-[var(--md-primary)]">1</div>
                  <span class="text-xs font-semibold">Numbers</span>
                </div>
                <label class="m3-switch">
                  <input type="checkbox" id="toggle-numbers" ${this.state.useNumbers ? 'checked' : ''}>
                  <span class="m3-switch-track"><span class="m3-switch-thumb"></span></span>
                </label>
              </div>

              <!-- Symbols -->
              <div class="flex items-center justify-between p-2.5 rounded-xl border border-white/5" style="background-color: var(--md-surface);">
                <div class="flex items-center gap-2.5">
                  <div class="w-7 h-7 rounded border border-white/10 flex items-center justify-center font-mono font-bold text-xs text-[var(--md-primary)]">@</div>
                  <span class="text-xs font-semibold">Symbols</span>
                </div>
                <label class="m3-switch">
                  <input type="checkbox" id="toggle-symbols" ${this.state.useSymbols ? 'checked' : ''}>
                  <span class="m3-switch-track"><span class="m3-switch-thumb"></span></span>
                </label>
              </div>
            </div>

          </div>

        </div>
      `;
    }

    // 4. SETTINGS SCREEN (SettingsScreen.kt)
    renderSettingsScreen() {
      const autoLockLabels = {
        0: 'Immediately',
        30000: '30 Seconds',
        60000: '1 Minute',
        300000: '5 Minutes',
        900000: '15 Minutes',
        [-1]: 'Never'
      };

      const currentAccentKey = (this.state.accentKey || 'blue').toLowerCase();
      const currentAccent = ACCENTS[currentAccentKey] || ACCENTS.blue;

      return `
        <!-- TopAppBar (SettingsScreen.kt lines 60-80) -->
        <div class="px-4 py-2.5 flex items-center justify-between flex-shrink-0" style="background-color: var(--md-surface); border-bottom: 1px solid var(--md-outline);">
          <div class="flex items-center gap-2">
            <span class="text-lg font-bold text-[var(--md-primary)] tracking-tight">Settings</span>
          </div>
          <button data-action="lock-vault" title="Lock Vault" class="p-2 rounded-full hover:opacity-80 cursor-pointer" style="color: var(--md-on-surface-variant);">
            <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/></svg>
          </button>
        </div>

        <div class="flex-1 overflow-y-auto px-4 py-3 space-y-4 pb-20 no-scrollbar">
          
          <!-- SECTION 1: APPEARANCE (SettingsScreen.kt lines 440-551) -->
          <div class="space-y-2">
            <span class="text-[10px] uppercase font-bold tracking-wider text-[var(--md-primary)] px-1">APPEARANCE</span>
            <div class="rounded-2xl border border-white/5 divide-y divide-white/5 overflow-hidden" style="background-color: var(--md-surface-card);">
              
              <!-- Accent Color Live Selector -->
              <div class="p-3.5 flex items-center justify-between">
                <div class="flex items-center gap-3">
                  <div class="w-8 h-8 rounded-lg flex items-center justify-center bg-[var(--md-primary-container)] text-[var(--md-primary)]">
                    <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M12 3c-4.97 0-9 4.03-9 9 0 2.12.74 4.07 1.97 5.61L4.35 19.4c-.39.39-.39 1.02 0 1.41.39.39 1.02.39 1.41 0l1.9-1.9C9.28 19.67 10.59 20 12 20c4.97 0 9-4.03 9-9s-4.03-9-9-9z"/></svg>
                  </div>
                  <div>
                    <div class="text-xs font-semibold">Accent Color</div>
                    <div class="text-[10px]" style="color: var(--md-on-surface-variant);">${currentAccent.title}</div>
                  </div>
                </div>

                <div class="flex items-center gap-1.5">
                  ${['blue', 'green', 'purple', 'amber'].map(key => `
                    <button 
                      data-accent="${key}" 
                      title="${ACCENTS[key].title}" 
                      class="w-5 h-5 rounded-full border transition-transform ${currentAccentKey === key ? 'scale-125 border-white ring-2 ring-[var(--md-primary)]' : 'border-transparent opacity-60'}" 
                      style="background-color: ${ACCENTS[key].preview};"
                    ></button>
                  `).join('')}
                </div>
              </div>

              <!-- Theme Mode Selector -->
              <div class="p-3.5 flex items-center justify-between">
                <div class="flex items-center gap-3">
                  <div class="w-8 h-8 rounded-lg flex items-center justify-center bg-[var(--md-primary-container)] text-[var(--md-primary)]">
                    <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M20 8.69V4h-4.69L12 .69 8.69 4H4v4.69L.69 12 4 15.31V20h4.69L12 23.31 15.31 20H20v-4.69L23.31 12 20 8.69zM12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6z"/></svg>
                  </div>
                  <div>
                    <div class="text-xs font-semibold">Theme Mode</div>
                    <div class="text-[10px]" style="color: var(--md-on-surface-variant);">${this.state.themeMode === 1 ? 'Light Mode' : 'Dark Mode'}</div>
                  </div>
                </div>

                <button data-action="toggle-theme-mode" class="px-2.5 py-1 rounded-lg text-xs font-semibold border" style="background-color: var(--md-surface); color: var(--md-primary); border-color: var(--md-outline);">
                  ${this.state.themeMode === 1 ? 'Light' : 'Dark'}
                </button>
              </div>

              <!-- Hide Passwords by Default -->
              <div class="p-3.5 flex items-center justify-between">
                <div class="flex items-center gap-3">
                  <div class="w-8 h-8 rounded-lg flex items-center justify-center" style="background-color: var(--md-surface-variant); color: var(--md-on-surface-variant);">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l18 18"/></svg>
                  </div>
                  <div>
                    <div class="text-xs font-semibold">Hide Passwords</div>
                    <div class="text-[10px]" style="color: var(--md-on-surface-variant);">Mask credentials by default</div>
                  </div>
                </div>

                <label class="m3-switch">
                  <input type="checkbox" id="toggle-hide-pass" ${this.state.hidePasswordsByDefault ? 'checked' : ''}>
                  <span class="m3-switch-track"><span class="m3-switch-thumb"></span></span>
                </label>
              </div>

            </div>
          </div>

          <!-- SECTION 2: SECURITY (SettingsScreen.kt lines 553-780) -->
          <div class="space-y-2">
            <span class="text-[10px] uppercase font-bold tracking-wider text-[var(--md-primary)] px-1">SECURITY</span>
            <div class="rounded-2xl border border-white/5 divide-y divide-white/5 overflow-hidden" style="background-color: var(--md-surface-card);">
              
              <!-- Biometric Unlock Switch -->
              <div class="p-3.5 flex items-center justify-between">
                <div class="flex items-center gap-3">
                  <div class="w-8 h-8 rounded-lg flex items-center justify-center bg-[var(--md-primary-container)] text-[var(--md-primary)]">
                    <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M17.81 4.47c-.08 0-.16-.02-.23-.06C15.66 3.42 14 3 12.01 3c-1.98 0-3.86.47-5.57 1.41-.24.13-.54.04-.68-.2-.13-.24-.04-.55.2-.68C7.82 2.52 9.86 2 12.01 2c2.13 0 3.99.47 6.03 1.52.25.13.34.43.21.67-.09.18-.26.28-.44.28z"/></svg>
                  </div>
                  <div>
                    <div class="text-xs font-semibold">Biometric Unlock</div>
                    <div class="text-[10px]" style="color: var(--md-on-surface-variant);">Fingerprint and Face Unlock</div>
                  </div>
                </div>

                <label class="m3-switch">
                  <input type="checkbox" id="toggle-biometric" ${this.state.biometricEnabled ? 'checked' : ''}>
                  <span class="m3-switch-track"><span class="m3-switch-thumb"></span></span>
                </label>
              </div>

              <!-- Disable Screenshots Switch -->
              <div class="p-3.5 flex items-center justify-between">
                <div class="flex items-center gap-3">
                  <div class="w-8 h-8 rounded-lg flex items-center justify-center bg-[#93000a] text-[#ffb4ab]">
                    <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 6c1.66 0 3 1.34 3 3v2h1v6H8v-6h1v-2c0-1.66 1.34-3 3-3zm1.5 5h-3v-2c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5v2z"/></svg>
                  </div>
                  <div>
                    <div class="text-xs font-semibold">Disable Screenshots</div>
                    <div class="text-[10px]" style="color: var(--md-on-surface-variant);">FLAG_SECURE protection</div>
                  </div>
                </div>

                <label class="m3-switch">
                  <input type="checkbox" id="toggle-screenshot" ${this.state.disableScreenshots ? 'checked' : ''}>
                  <span class="m3-switch-track"><span class="m3-switch-thumb"></span></span>
                </label>
              </div>

              <!-- Auto-Lock Timer Row -->
              <div data-action="open-autolock-dialog" class="p-3.5 flex items-center justify-between cursor-pointer hover:bg-black/5 dark:hover:bg-white/5">
                <div class="flex items-center gap-3">
                  <div class="w-8 h-8 rounded-lg flex items-center justify-center bg-amber-900/40 text-amber-300">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>
                  </div>
                  <div>
                    <div class="text-xs font-semibold">Auto-Lock</div>
                    <div class="text-[10px]" style="color: var(--md-on-surface-variant);">${autoLockLabels[this.state.autoLockTimer] || '1 Minute'}</div>
                  </div>
                </div>

                <svg class="w-4 h-4" style="color: var(--md-on-surface-variant);" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"/></svg>
              </div>

              <!-- Autofill Provider Row -->
              <div class="p-3.5 flex items-center justify-between">
                <div class="flex items-center gap-3">
                  <div class="w-8 h-8 rounded-lg flex items-center justify-center bg-emerald-900/40 text-emerald-300">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"/></svg>
                  </div>
                  <div>
                    <div class="text-xs font-semibold">Autofill Service</div>
                    <div class="text-[10px] text-emerald-400 font-semibold">Provider Active</div>
                  </div>
                </div>
                <svg class="w-4 h-4" style="color: var(--md-on-surface-variant);" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"/></svg>
              </div>

            </div>
          </div>

          <!-- SECTION 3: DATA & VAULT (SettingsScreen.kt lines 781-833) -->
          <div class="space-y-2">
            <span class="text-[10px] uppercase font-bold tracking-wider text-[var(--md-primary)] px-1">DATA &amp; VAULT</span>
            <div class="rounded-2xl border border-white/5 divide-y divide-white/5 overflow-hidden" style="background-color: var(--md-surface-card);">
              
              <!-- Export Vault Row -->
              <div data-action="open-export-dialog" class="p-3.5 flex items-center justify-between cursor-pointer hover:bg-black/5 dark:hover:bg-white/5">
                <div class="flex items-center gap-3">
                  <div class="w-8 h-8 rounded-lg flex items-center justify-center" style="background-color: var(--md-surface-variant); color: var(--md-on-surface-variant);">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"/></svg>
                  </div>
                  <div>
                    <div class="text-xs font-semibold">Export Vault</div>
                    <div class="text-[10px]" style="color: var(--md-on-surface-variant);">Encrypted .vpex / JSON / TXT</div>
                  </div>
                </div>
                <svg class="w-4 h-4" style="color: var(--md-on-surface-variant);" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"/></svg>
              </div>

              <!-- Import Vault Row -->
              <div data-action="import-toast" class="p-3.5 flex items-center justify-between cursor-pointer hover:bg-black/5 dark:hover:bg-white/5">
                <div class="flex items-center gap-3">
                  <div class="w-8 h-8 rounded-lg flex items-center justify-center" style="background-color: var(--md-surface-variant); color: var(--md-on-surface-variant);">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/></svg>
                  </div>
                  <div>
                    <div class="text-xs font-semibold">Import Vault</div>
                    <div class="text-[10px]" style="color: var(--md-on-surface-variant);">Restore from backup file</div>
                  </div>
                </div>
                <svg class="w-4 h-4" style="color: var(--md-on-surface-variant);" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"/></svg>
              </div>

              <!-- Recycle Bin -->
              <div data-action="recycle-toast" class="p-3.5 flex items-center justify-between cursor-pointer hover:bg-black/5 dark:hover:bg-white/5">
                <div class="flex items-center gap-3">
                  <div class="w-8 h-8 rounded-lg flex items-center justify-center bg-red-900/40 text-red-300">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>
                  </div>
                  <div>
                    <div class="text-xs font-semibold">Recycle Bin</div>
                    <div class="text-[10px]" style="color: var(--md-on-surface-variant);">0 deleted items</div>
                  </div>
                </div>
                <svg class="w-4 h-4" style="color: var(--md-on-surface-variant);" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"/></svg>
              </div>

            </div>
          </div>

          <!-- SECTION 4: SYNC & DEVICES (SettingsScreen.kt lines 834-855) -->
          <div class="space-y-2">
            <span class="text-[10px] uppercase font-bold tracking-wider text-[var(--md-primary)] px-1">SYNC</span>
            <div class="rounded-2xl border border-white/5 overflow-hidden" style="background-color: var(--md-surface-card);">
              <div data-action="sync-toast" class="p-3.5 flex items-center justify-between cursor-pointer hover:bg-black/5 dark:hover:bg-white/5">
                <div class="flex items-center gap-3">
                  <div class="w-8 h-8 rounded-lg flex items-center justify-center bg-[var(--md-primary-container)] text-[var(--md-primary)]">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/></svg>
                  </div>
                  <div>
                    <div class="text-xs font-semibold">Device Sync</div>
                    <div class="text-[10px]" style="color: var(--md-on-surface-variant);">Sync offline over local WiFi LAN</div>
                  </div>
                </div>
                <svg class="w-4 h-4" style="color: var(--md-on-surface-variant);" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"/></svg>
              </div>
            </div>
          </div>

          <!-- Privacy Policy Footer (SettingsScreen.kt lines 857-873) -->
          <div class="pt-2 text-center">
            <a href="privacy.html" class="text-xs font-semibold text-[var(--md-primary)] hover:underline">
              Privacy Policy
            </a>
          </div>

        </div>
      `;
    }

    // 5. LOCK SCREEN (LockScreen.kt)
    renderLockScreen() {
      return `
        <div class="flex-1 px-6 py-8 flex flex-col items-center justify-center text-center space-y-6">
          
          <!-- Shield Header -->
          <div class="w-20 h-20 rounded-full flex items-center justify-center shadow-lg" style="background-color: var(--md-surface-card); border: 1px solid var(--md-outline);">
            <svg class="w-10 h-10 text-[var(--md-primary)]" fill="currentColor" viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 6c1.66 0 3 1.34 3 3v2h1v6H8v-6h1v-2c0-1.66 1.34-3 3-3zm1.5 5h-3v-2c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5v2z"/></svg>
          </div>

          <div>
            <h1 class="text-2xl font-black tracking-tight text-[var(--md-primary)]">VaultPass</h1>
            <p class="text-xs mt-1" style="color: var(--md-on-surface-variant);">Vault is Locked</p>
          </div>

          <!-- Glass Panel Card -->
          <div class="w-full p-5 rounded-3xl border border-white/5 space-y-4" style="background-color: var(--md-surface-card);">
            
            <!-- Biometric Visualizer Button -->
            ${this.state.biometricEnabled ? `
              <div class="flex flex-col items-center justify-center">
                <button 
                  data-action="biometric-unlock" 
                  title="Authenticate with fingerprint"
                  class="w-20 h-20 rounded-full flex items-center justify-center transition-all cursor-pointer ${this.state.biometricScanning ? 'scale-110 ring-4 ring-[var(--md-primary)]/50' : 'hover:scale-105'}"
                  style="background-color: var(--md-surface); border: 1px solid var(--md-outline);"
                >
                  <svg class="w-10 h-10 text-[var(--md-primary)]" fill="currentColor" viewBox="0 0 24 24"><path d="M17.81 4.47c-.08 0-.16-.02-.23-.06C15.66 3.42 14 3 12.01 3c-1.98 0-3.86.47-5.57 1.41-.24.13-.54.04-.68-.2-.13-.24-.04-.55.2-.68C7.82 2.52 9.86 2 12.01 2c2.13 0 3.99.47 6.03 1.52.25.13.34.43.21.67-.09.18-.26.28-.44.28zM3.51 9.72c-.1-.23-.04-.5.14-.68.16-.16.42-.2.62-.1 1.77.89 3.72 1.34 5.73 1.34 2.01 0 3.96-.45 5.73-1.34.21-.1.46-.07.62.1.18.17.24.44.14.68-.9 2.07-2.34 3.78-4.17 4.96-.75.48-1.57.85-2.42 1.09-.23.07-.47-.07-.54-.3-.07-.23.07-.47.3-.54.78-.22 1.53-.56 2.22-1 .01 0 .01-.01.02-.01 1.63-1.04 2.91-2.55 3.73-4.38-1.63.78-3.41 1.18-5.23 1.18s-3.6-.4-5.23-1.18c.82 1.83 2.1 3.34 3.73 4.38.01 0 .01.01.02.01.69.44 1.44.78 2.22 1 .23.07.37.31.3.54-.07.23-.31.37-.54.3-.85-.24-1.67-.61-2.42-1.09-1.83-1.18-3.27-2.89-4.17-4.96z"/></svg>
                </button>
                <span class="text-[10px] mt-2 font-semibold" style="color: var(--md-on-surface-variant);">Touch sensor to unlock</span>
              </div>
            ` : ''}

            <!-- Master Password Input -->
            <div class="relative">
              <input 
                type="${this.state.lockPasswordVisible ? 'text' : 'password'}" 
                id="lock-password" 
                placeholder="Master Password" 
                value="${this.escapeHtml(this.state.lockPasswordInput)}"
                class="w-full rounded-xl px-3 py-2.5 pl-9 pr-9 text-xs focus:outline-none"
                style="background-color: var(--md-surface); color: var(--md-on-surface); border: 1px solid var(--md-outline);"
              >
              <svg class="w-4 h-4 absolute left-3 top-3" style="color: var(--md-on-surface-variant);" fill="currentColor" viewBox="0 0 24 24"><path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/></svg>
              
              <button data-action="toggle-lock-eye" class="absolute right-3 top-2.5 p-0.5 cursor-pointer" style="color: var(--md-on-surface-variant);">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="${this.state.lockPasswordVisible ? 'M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l18 18' : 'M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z'}"/></svg>
              </button>
            </div>

            ${this.state.lockError ? `
              <div class="text-[11px] text-[#ffb4ab] font-semibold">${this.escapeHtml(this.state.lockError)}</div>
            ` : ''}

            <!-- Unlock Button -->
            <button data-action="submit-unlock" class="w-full py-2.5 rounded-xl font-bold text-xs flex items-center justify-center gap-2 cursor-pointer shadow-md transition-transform hover:scale-[1.02]" style="background-color: var(--md-primary-container); color: var(--md-on-primary-container);">
              <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M12 17c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm6-9h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm0 12H6V10h12v10z"/></svg>
              <span>Unlock Vault</span>
            </button>

          </div>

          <div class="flex items-center gap-1.5 text-[10px]" style="color: var(--md-on-surface-variant);">
            <svg class="w-3.5 h-3.5 text-[var(--md-primary)]" fill="currentColor" viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 6c1.66 0 3 1.34 3 3v2h1v6H8v-6h1v-2c0-1.66 1.34-3 3-3zm1.5 5h-3v-2c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5v2z"/></svg>
            <span>AES-256-GCM Hardware Encrypted</span>
          </div>

        </div>
      `;
    }

    // 6. PASSWORD DETAILS SCREEN (PasswordDetailsScreen.kt)
    renderDetailsScreen() {
      const entry = this.state.entries.find(e => e.id === this.state.selectedEntryId) || this.state.entries[0];
      if (!entry) return this.renderDashboardScreen();

      return `
        <!-- Top App Bar (PasswordDetailsScreen.kt lines 85-105) -->
        <div class="px-4 py-2.5 flex items-center justify-between flex-shrink-0" style="background-color: var(--md-surface); border-bottom: 1px solid var(--md-outline);">
          <div class="flex items-center gap-2">
            <button data-nav="dashboard" class="p-1 rounded-full hover:opacity-80 cursor-pointer" style="color: var(--md-on-surface-variant);">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18"/></svg>
            </button>
            <span class="text-base font-bold text-[var(--md-primary)]">VaultPass</span>
          </div>
          <button data-action="favorite-toggle" data-id="${entry.id}" class="p-1.5 rounded-full text-[var(--md-primary)] cursor-pointer">
            <svg class="w-5 h-5" fill="${entry.isFavorite ? 'currentColor' : 'none'}" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.197-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118l-3.976-2.888c-.784-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z"/></svg>
          </button>
        </div>

        <div class="flex-1 overflow-y-auto px-4 py-4 space-y-4 pb-20 no-scrollbar">
          
          <!-- Header Section -->
          <div class="flex items-center gap-3">
            <div class="w-12 h-12 rounded-2xl flex items-center justify-center font-black text-lg shadow-sm" style="background-color: var(--md-surface-card); border: 1px solid var(--md-outline); color: var(--md-primary);">
              ${this.escapeHtml(Array.from(entry.title || ' ')[0])}
            </div>
            <div>
              <h2 class="text-base font-bold text-[var(--md-primary)]">${this.escapeHtml(entry.title)}</h2>
              <span class="text-xs" style="color: var(--md-on-surface-variant);">${this.escapeHtml(entry.category)}</span>
            </div>
          </div>

          <!-- Credential Card (PasswordDetailsScreen.kt lines 170-207) -->
          <div class="p-4 rounded-2xl border border-white/5 space-y-3" style="background-color: var(--md-surface-card);">
            
            <!-- Username Field -->
            <div class="space-y-1">
              <span class="text-[10px] uppercase font-bold tracking-wider" style="color: var(--md-on-surface-variant);">USERNAME</span>
              <div class="p-2.5 rounded-xl border border-white/5 flex items-center justify-between font-mono text-xs" style="background-color: var(--md-surface);">
                <span class="truncate mr-2">${this.escapeHtml(entry.username)}</span>
                <button data-copy-val="${this.escapeHtml(entry.username)}" data-copy-label="Username" class="text-[var(--md-primary)] hover:opacity-80 p-1 cursor-pointer">
                  <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"/></svg>
                </button>
              </div>
            </div>

            <!-- Password Field -->
            <div class="space-y-1">
              <span class="text-[10px] uppercase font-bold tracking-wider" style="color: var(--md-on-surface-variant);">PASSWORD</span>
              <div class="p-2.5 rounded-xl border border-white/5 flex items-center justify-between font-mono text-xs" style="background-color: var(--md-surface);">
                <span class="truncate mr-2 text-[var(--md-primary)] font-bold">
                  ${this.state.detailPasswordRevealed ? this.escapeHtml(entry.password) : '••••••••••••••••'}
                </span>
                <div class="flex items-center gap-1">
                  <button data-action="toggle-detail-eye" class="p-1 cursor-pointer hover:opacity-80" style="color: var(--md-on-surface-variant);">
                    <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="${this.state.detailPasswordRevealed ? 'M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l18 18' : 'M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z'}"/></svg>
                  </button>
                  <button data-copy-val="${this.escapeHtml(entry.password)}" data-copy-label="Password" class="text-[var(--md-primary)] hover:opacity-80 p-1 cursor-pointer">
                    <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"/></svg>
                  </button>
                </div>
              </div>
            </div>

          </div>

          <!-- Website Card -->
          ${entry.website ? `
            <div class="p-3.5 rounded-2xl border border-white/5 space-y-1" style="background-color: var(--md-surface-card);">
              <span class="text-[10px] uppercase font-bold tracking-wider" style="color: var(--md-on-surface-variant);">WEBSITE</span>
              <div class="flex items-center justify-between text-xs">
                <span class="truncate font-mono" style="color: var(--md-on-surface);">${this.escapeHtml(entry.website)}</span>
                <span class="text-[11px] font-bold text-[var(--md-primary)]">Open</span>
              </div>
            </div>
          ` : ''}

          <!-- Notes Card -->
          ${entry.notes ? `
            <div class="p-3.5 rounded-2xl border border-white/5 space-y-1" style="background-color: var(--md-surface-card);">
              <span class="text-[10px] uppercase font-bold tracking-wider" style="color: var(--md-on-surface-variant);">NOTES</span>
              <p class="text-xs leading-relaxed" style="color: var(--md-on-surface-variant);">${this.escapeHtml(entry.notes)}</p>
            </div>
          ` : ''}

        </div>
      `;
    }

    // 7. ADD ENTRY SCREEN (PasswordEntryScreen.kt)
    renderAddEntryScreen() {
      return `
        <!-- Top App Bar (PasswordEntryScreen.kt lines 237-266) -->
        <div class="px-4 py-2.5 flex items-center justify-between flex-shrink-0" style="background-color: var(--md-surface); border-bottom: 1px solid var(--md-outline);">
          <button data-nav="dashboard" class="p-1 rounded-full hover:opacity-80 cursor-pointer" style="color: var(--md-on-surface-variant);">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/></svg>
          </button>
          <span class="text-base font-bold text-[var(--md-primary)]">New Entry</span>
          <button data-action="save-entry" class="text-xs font-bold text-[var(--md-primary)] flex items-center gap-1 cursor-pointer">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M5 13l4 4L19 7"/></svg>
            <span>Save</span>
          </button>
        </div>

        <div class="flex-1 overflow-y-auto px-4 py-3 space-y-4 pb-20 no-scrollbar">
          
          <!-- Category Chips Selector (PasswordEntryScreen.kt lines 277-304) -->
          <div class="flex items-center gap-2 overflow-x-auto no-scrollbar">
            ${['Login', 'Credit Card', 'Secure Note'].map(cat => `
              <button 
                data-select-category="${cat}"
                class="px-3 py-1.5 rounded-full text-xs font-semibold whitespace-nowrap border transition-all cursor-pointer ${this.state.newCategory === cat ? 'border-[var(--md-primary)] bg-[var(--md-primary-container)] text-[var(--md-on-primary-container)]' : 'hover:opacity-80'}"
                style="${this.state.newCategory === cat ? '' : 'border-color: var(--md-outline); color: var(--md-on-surface-variant);'}"
              >
                ${cat}
              </button>
            `).join('')}
          </div>

          <!-- Core Details Card -->
          <div class="p-4 rounded-2xl border border-white/5 space-y-3" style="background-color: var(--md-surface-card);">
            <div class="space-y-1">
              <label class="text-[10px] uppercase font-bold tracking-wider" style="color: var(--md-on-surface-variant);">Title</label>
              <input 
                type="text" 
                id="add-title" 
                placeholder="e.g. Google, Discord" 
                value="${this.escapeHtml(this.state.newTitle)}"
                class="w-full rounded-xl px-3 py-2 text-xs focus:outline-none"
                style="background-color: var(--md-surface); color: var(--md-on-surface); border: 1px solid var(--md-outline);"
              >
            </div>

            <div class="space-y-1">
              <label class="text-[10px] uppercase font-bold tracking-wider" style="color: var(--md-on-surface-variant);">Website</label>
              <input 
                type="text" 
                id="add-website" 
                placeholder="e.g. google.com" 
                value="${this.escapeHtml(this.state.newWebsite)}"
                class="w-full rounded-xl px-3 py-2 text-xs focus:outline-none"
                style="background-color: var(--md-surface); color: var(--md-on-surface); border: 1px solid var(--md-outline);"
              >
            </div>
          </div>

          <!-- Credentials Card -->
          <div class="p-4 rounded-2xl border border-white/5 space-y-3" style="background-color: var(--md-surface-card);">
            <div class="space-y-1">
              <label class="text-[10px] uppercase font-bold tracking-wider" style="color: var(--md-on-surface-variant);">Username / Email</label>
              <input 
                type="text" 
                id="add-username" 
                placeholder="username or email" 
                value="${this.escapeHtml(this.state.newUsername)}"
                class="w-full rounded-xl px-3 py-2 text-xs focus:outline-none"
                style="background-color: var(--md-surface); color: var(--md-on-surface); border: 1px solid var(--md-outline);"
              >
            </div>

            <div class="space-y-1">
              <div class="flex justify-between items-center">
                <label class="text-[10px] uppercase font-bold tracking-wider" style="color: var(--md-on-surface-variant);">Password</label>
                <button data-action="quick-gen-fill" class="text-[10px] font-bold text-[var(--md-primary)] hover:underline cursor-pointer">Generate</button>
              </div>
              <div class="relative">
                <input 
                  type="${this.state.newPasswordVisible ? 'text' : 'password'}" 
                  id="add-password" 
                  placeholder="Enter or generate password" 
                  value="${this.escapeHtml(this.state.newPassword)}"
                  class="w-full rounded-xl px-3 py-2 pr-9 text-xs font-mono focus:outline-none"
                  style="background-color: var(--md-surface); color: var(--md-on-surface); border: 1px solid var(--md-outline);"
                >
                <button data-action="toggle-add-eye" class="absolute right-2.5 top-2 p-0.5 cursor-pointer hover:opacity-80" style="color: var(--md-on-surface-variant);">
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="${this.state.newPasswordVisible ? 'M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l18 18' : 'M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z'}"/></svg>
                </button>
              </div>
            </div>
          </div>

          <!-- Notes Card -->
          <div class="p-4 rounded-2xl border border-white/5 space-y-1" style="background-color: var(--md-surface-card);">
            <label class="text-[10px] uppercase font-bold tracking-wider" style="color: var(--md-on-surface-variant);">Notes</label>
            <textarea 
              id="add-notes" 
              rows="2" 
              placeholder="Optional notes or security questions" 
              class="w-full rounded-xl p-2.5 text-xs focus:outline-none resize-none"
              style="background-color: var(--md-surface); color: var(--md-on-surface); border: 1px solid var(--md-outline);"
            >${this.escapeHtml(this.state.newNotes)}</textarea>
          </div>

        </div>
      `;
    }

    // Material 3 Bottom Navigation Bar (VaultApp.kt lines 158-212)
    renderBottomBar() {
      const tabs = [
        {
          id: 'dashboard',
          label: 'Vault',
          icon: '<svg class="w-5 h-5" fill="currentColor" viewBox="0 0 24 24"><path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/></svg>'
        },
        {
          id: 'security',
          label: 'Security',
          icon: '<svg class="w-5 h-5" fill="currentColor" viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 6c1.66 0 3 1.34 3 3v2h1v6H8v-6h1v-2c0-1.66 1.34-3 3-3zm1.5 5h-3v-2c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5v2z"/></svg>'
        },
        {
          id: 'generator',
          label: 'Generator',
          icon: '<svg class="w-5 h-5" fill="currentColor" viewBox="0 0 24 24"><path d="M12.65 10C11.83 7.67 9.61 6 7 6c-3.31 0-6 2.69-6 6s2.69 6 6 6c2.61 0 4.83-1.67 5.65-4H17v4h4v-4h2v-4H12.65zM7 14c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"/></svg>'
        },
        {
          id: 'settings',
          label: 'Settings',
          icon: '<svg class="w-5 h-5" fill="currentColor" viewBox="0 0 24 24"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>'
        }
      ];

      return `
        <div class="h-16 px-2 flex items-center justify-around flex-shrink-0 z-40" style="background-color: var(--md-surface); border-top: 1px solid var(--md-outline);">
          ${tabs.map(tab => {
            const isActive = this.state.activeRoute === tab.id;
            return `
              <button 
                data-nav="${tab.id}" 
                class="flex flex-col items-center justify-center flex-1 py-1 cursor-pointer transition-all"
              >
                <!-- Material 3 Active Pill Indicator -->
                <div class="px-4 py-1 rounded-full flex items-center justify-center transition-all ${isActive ? 'bg-[var(--md-primary-container)] text-[var(--md-on-primary-container)]' : ''}" style="${isActive ? '' : 'color: var(--md-on-surface-variant);'}">
                  ${tab.icon}
                </div>
                <span class="text-[10px] font-semibold mt-0.5" style="color: ${isActive ? 'var(--md-primary)' : 'var(--md-on-surface-variant)'}; font-weight: ${isActive ? '700' : '600'};">
                  ${tab.label}
                </span>
              </button>
            `;
          }).join('')}
        </div>
      `;
    }

    // Auto-lock Dialog Modal (SettingsScreen.kt lines 733-776) with backdrop dismiss (MOD-01)
    renderAutoLockDialog() {
      const options = [
        { val: 0, label: 'Immediately' },
        { val: 30000, label: '30 Seconds' },
        { val: 60000, label: '1 Minute' },
        { val: 300000, label: '5 Minutes' },
        { val: 900000, label: '15 Minutes' },
        { val: -1, label: 'Never' }
      ];

      return `
        <div data-dialog-scrim="true" class="absolute inset-0 bg-black/70 flex items-center justify-center p-4 z-50 animate-fade-in">
          <div class="w-full rounded-2xl p-5 space-y-3 shadow-2xl" style="background-color: var(--md-surface-card); border: 1px solid var(--md-outline);">
            <h3 class="text-sm font-bold text-[var(--md-primary)]">Choose Auto-Lock Time</h3>
            <div class="space-y-1">
              ${options.map(opt => `
                <div data-set-autolock="${opt.val}" class="p-2.5 rounded-xl flex items-center gap-3 cursor-pointer hover:bg-black/5 dark:hover:bg-white/5 transition-colors ${this.state.autoLockTimer === opt.val ? 'bg-[var(--md-primary-container)] text-[var(--md-on-primary-container)] font-bold' : ''}" style="${this.state.autoLockTimer === opt.val ? '' : 'color: var(--md-on-surface);'}">
                  <div class="w-4 h-4 rounded-full border border-current flex items-center justify-center">
                    ${this.state.autoLockTimer === opt.val ? '<div class="w-2 h-2 rounded-full bg-current"></div>' : ''}
                  </div>
                  <span class="text-xs">${opt.label}</span>
                </div>
              `).join('')}
            </div>
            <div class="text-right pt-2">
              <button data-action="close-dialog" class="text-xs font-semibold text-[var(--md-primary)] px-3 py-1 cursor-pointer">Cancel</button>
            </div>
          </div>
        </div>
      `;
    }

    // Export Format Dialog Modal (SettingsScreen.kt lines 185-247) with backdrop dismiss (MOD-01)
    renderExportDialog() {
      return `
        <div data-dialog-scrim="true" class="absolute inset-0 bg-black/70 flex items-center justify-center p-4 z-50 animate-fade-in">
          <div class="w-full rounded-2xl p-5 space-y-4 shadow-2xl" style="background-color: var(--md-surface-card); border: 1px solid var(--md-outline);">
            <h3 class="text-sm font-bold text-[var(--md-primary)]">Choose Export Format</h3>
            <div class="space-y-2">
              <div data-do-export="vpex" class="p-3 rounded-xl border border-white/5 cursor-pointer hover:border-[var(--md-primary)]" style="background-color: var(--md-surface);">
                <div class="text-xs font-bold text-[var(--md-primary)]">Encrypted Backup (.vpex)</div>
                <div class="text-[10px]" style="color: var(--md-on-surface-variant);">Password-protected backup package</div>
              </div>
              <div data-do-export="json" class="p-3 rounded-xl border border-white/5 cursor-pointer hover:border-[var(--md-primary)]" style="background-color: var(--md-surface);">
                <div class="text-xs font-bold" style="color: var(--md-on-surface);">Standard JSON (.json)</div>
                <div class="text-[10px]" style="color: var(--md-on-surface-variant);">Interoperable data export</div>
              </div>
              <div data-do-export="txt" class="p-3 rounded-xl border border-white/5 cursor-pointer hover:border-[var(--md-primary)]" style="background-color: var(--md-surface);">
                <div class="text-xs font-bold" style="color: var(--md-on-surface);">Plaintext (.txt)</div>
                <div class="text-[10px]" style="color: var(--md-on-surface-variant);">Readable human backup</div>
              </div>
            </div>
            <div class="text-right pt-1">
              <button data-action="close-dialog" class="text-xs font-semibold text-[var(--md-primary)] px-3 py-1 cursor-pointer">Cancel</button>
            </div>
          </div>
        </div>
      `;
    }

    // EVENT HANDLING
    bindEvents() {
      if (!this.root) return;

      // Global Escape listener for open dialogs within demo (MOD-01)
      document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && (this.state.showAutoLockDialog || this.state.showExportDialog)) {
          this.closeDialogs();
        }
      });

      this.root.addEventListener('click', (e) => {
        // Backdrop scrim click dismiss (MOD-01)
        if (e.target && typeof e.target.getAttribute === 'function' && e.target.getAttribute('data-dialog-scrim')) {
          this.closeDialogs();
          return;
        }

        const target = e.target.closest('button, [data-nav], [data-entry-id], [data-action], [data-accent], [data-set-autolock], [data-do-export], [data-select-category]');
        if (!target) return;

        // Navigation (STA-03: reset search on route transition)
        const navTarget = target.getAttribute('data-nav');
        if (navTarget) {
          e.preventDefault();
          this.state.activeRoute = navTarget;
          this.state.searchQuery = '';
          this.state.searchActive = false;
          this.state.searchOpen = false;
          this.render();
          return;
        }

        // Open Entry Details (STA-03)
        const entryId = target.getAttribute('data-entry-id');
        if (entryId) {
          e.preventDefault();
          this.state.selectedEntryId = parseInt(entryId, 10);
          this.state.activeRoute = 'entry_details';
          this.state.searchQuery = '';
          this.state.searchActive = false;
          this.state.searchOpen = false;
          this.state.detailPasswordRevealed = !this.state.hidePasswordsByDefault;
          this.render();
          return;
        }

        // Accent Color Switching (SettingsScreen.kt)
        const accentKey = target.getAttribute('data-accent');
        if (accentKey && ACCENTS[accentKey]) {
          this.state.accentKey = accentKey;
          this.showToast(`Accent set to ${ACCENTS[accentKey].title}`);
          this.render();
          return;
        }

        // Auto-lock selection
        const autoLockVal = target.getAttribute('data-set-autolock');
        if (autoLockVal !== null) {
          this.state.autoLockTimer = parseInt(autoLockVal, 10);
          this.state.showAutoLockDialog = false;
          this.showToast('Auto-lock timer updated');
          this.render();
          return;
        }

        // Export execution
        const exportFormat = target.getAttribute('data-do-export');
        if (exportFormat) {
          this.state.showExportDialog = false;
          this.showToast(`Exported vaultpass_backup.${exportFormat}`);
          this.render();
          return;
        }

        // Category selection in Add Entry
        const category = target.getAttribute('data-select-category');
        if (category) {
          this.state.newCategory = category;
          this.render();
          return;
        }

        // Copy button actions (SEC-02)
        const copyVal = target.getAttribute('data-copy-val');
        if (copyVal) {
          const label = target.getAttribute('data-copy-label') || 'Item';
          this.copyText(copyVal, label);
          return;
        }

        // Actions
        const action = target.getAttribute('data-action');
        if (!action) return;

        switch (action) {
          case 'toggle-search':
            this.state.searchOpen = !this.state.searchOpen;
            this.state.searchActive = this.state.searchOpen;
            if (!this.state.searchOpen) {
              this.state.searchQuery = '';
            }
            this.render();
            if (this.state.searchOpen) {
              setTimeout(() => {
                const inp = this.root.querySelector('#search-input');
                if (inp) inp.focus();
              }, 50);
            }
            break;

          case 'close-search':
            this.state.searchOpen = false;
            this.state.searchActive = false;
            this.state.searchQuery = '';
            this.render();
            break;

          case 'lock-vault':
            this.state.isLocked = true;
            this.state.lockError = '';
            this.state.lockPasswordInput = '';
            this.render();
            break;

          case 'biometric-unlock':
            this.state.biometricScanning = true;
            this.render();
            setTimeout(() => {
              this.state.biometricScanning = false;
              this.state.isLocked = false;
              this.showToast('Biometric unlock verified');
              this.render();
            }, 650);
            break;

          case 'toggle-lock-eye':
            this.state.lockPasswordVisible = !this.state.lockPasswordVisible;
            this.render();
            break;

          case 'submit-unlock': {
            const pwd = this.state.lockPasswordInput.trim();
            if (pwd.length === 0) {
              this.state.lockError = 'Please enter your master password';
              this.render();
            } else {
              this.state.isLocked = false;
              this.state.lockPasswordInput = '';
              this.showToast('Vault unlocked');
              this.render();
            }
            break;
          }

          case 'copy-quick': {
            const p = target.getAttribute('data-pass');
            if (p) this.copyText(p, 'Password');
            break;
          }

          case 'open-add':
            this.state.newTitle = '';
            this.state.newWebsite = '';
            this.state.newUsername = '';
            this.state.newPassword = '';
            this.state.newNotes = '';
            this.state.newCategory = 'Login';
            this.state.searchQuery = '';
            this.state.searchActive = false;
            this.state.searchOpen = false;
            this.state.activeRoute = 'add_entry';
            this.render();
            break;

          case 'save-entry': {
            const tInp = this.root.querySelector('#add-title');
            const uInp = this.root.querySelector('#add-username');
            const pInp = this.root.querySelector('#add-password');
            const wInp = this.root.querySelector('#add-website');
            const nInp = this.root.querySelector('#add-notes');

            const title = ((tInp && tInp.value) ? tInp.value : this.state.newTitle).trim();
            const username = ((uInp && uInp.value) ? uInp.value : this.state.newUsername).trim();
            const password = ((pInp && pInp.value) ? pInp.value : this.state.newPassword).trim();
            const website = ((wInp && wInp.value) ? wInp.value : this.state.newWebsite).trim();
            const notes = ((nInp && nInp.value) ? nInp.value : this.state.newNotes).trim();

            if (!title) {
              this.showToast('Please enter a title');
              return;
            }

            const newId = Date.now();
            this.state.entries.unshift({
              id: newId,
              title,
              username: username || 'user@example.com',
              password: password || 'Secure#Pass99!',
              category: this.state.newCategory,
              website,
              notes,
              isFavorite: false,
              strength: password.length < 10 ? 'weak' : 'strong'
            });

            this.state.activeRoute = 'dashboard';
            this.showToast('Entry saved to vault');
            this.render();
            break;
          }

          case 'quick-gen-fill':
            this.generatePassword();
            this.state.newPassword = this.state.generatedPassword;
            this.state.newPasswordVisible = true;
            this.render();
            break;

          case 'toggle-add-eye':
            this.state.newPasswordVisible = !this.state.newPasswordVisible;
            this.render();
            break;

          case 'toggle-detail-eye':
            this.state.detailPasswordRevealed = !this.state.detailPasswordRevealed;
            this.render();
            break;

          case 'favorite-toggle': {
            const id = parseInt(target.getAttribute('data-id'), 10);
            const found = this.state.entries.find(e => e.id === id);
            if (found) {
              found.isFavorite = !found.isFavorite;
              this.showToast(found.isFavorite ? 'Added to favorites' : 'Removed from favorites');
              this.render();
            }
            break;
          }

          case 'regen-password':
            this.generatePassword();
            this.updateGeneratorDisplay();
            break;

          case 'copy-generated':
            if (this.state.generatedPassword) {
              this.copyText(this.state.generatedPassword, 'Generated password');
            }
            break;

          case 'toggle-theme-mode':
            this.state.themeMode = this.state.themeMode === 1 ? 2 : 1;
            this.showToast(this.state.themeMode === 1 ? 'Light theme applied' : 'Dark theme applied');
            this.render();
            break;

          case 'open-autolock-dialog':
            this.state.showAutoLockDialog = true;
            this.render();
            break;

          case 'open-export-dialog':
            this.state.showExportDialog = true;
            this.render();
            break;

          case 'close-dialog':
            this.closeDialogs();
            break;

          case 'import-toast':
            this.showToast('Encrypted backup importer ready');
            break;

          case 'recycle-toast':
            this.showToast('Recycle bin is empty');
            break;

          case 'sync-toast':
            this.showToast('Offline local LAN pairing active');
            break;
        }
      });

      // In-place Slider Dragging & Search Keystrokes (UX-01)
      this.root.addEventListener('input', (e) => {
        if (e.target.id === 'search-input') {
          // Search Keystroke: Update state and #entries-container directly, preserving focus (UX-01)
          this.state.searchQuery = e.target.value;
          const container = this.root.querySelector('#entries-container');
          if (container) {
            const q = this.state.searchQuery.toLowerCase().trim();
            const filtered = this.state.entries.filter(ent =>
              !q || ent.title.toLowerCase().includes(q) || ent.username.toLowerCase().includes(q)
            );
            container.innerHTML = this.renderEntriesList(filtered);
          }
        } else if (e.target.id === 'gen-slider') {
          // Range Slider Dragging: Update DOM directly without render() to preserve pointer capture (UX-01)
          this.state.genLength = parseInt(e.target.value, 10);
          const lenDisplay = this.root.querySelector('#gen-len-display');
          if (lenDisplay) lenDisplay.textContent = this.state.genLength;
          this.generatePassword();
          this.updateGeneratorDisplay();
        } else if (e.target.id === 'lock-password') {
          this.state.lockPasswordInput = e.target.value;
          this.state.lockError = '';
        } else if (e.target.id === 'add-title') {
          this.state.newTitle = e.target.value;
        } else if (e.target.id === 'add-username') {
          this.state.newUsername = e.target.value;
        } else if (e.target.id === 'add-password') {
          this.state.newPassword = e.target.value;
        } else if (e.target.id === 'add-website') {
          this.state.newWebsite = e.target.value;
        } else if (e.target.id === 'add-notes') {
          this.state.newNotes = e.target.value;
        }
      });

      // Switch checkboxes (STA-01: prevent unchecking last pool)
      this.root.addEventListener('change', (e) => {
        const toggleIds = ['toggle-upper', 'toggle-lower', 'toggle-numbers', 'toggle-symbols'];
        if (toggleIds.includes(e.target.id)) {
          if (!e.target.checked) {
            const activeCount = (this.state.useUppercase ? 1 : 0) +
                                (this.state.useLowercase ? 1 : 0) +
                                (this.state.useNumbers ? 1 : 0) +
                                (this.state.useSymbols ? 1 : 0);
            if (activeCount <= 1) {
              e.target.checked = true;
              this.showToast('At least one character set required');
              return;
            }
          }

          if (e.target.id === 'toggle-upper') this.state.useUppercase = e.target.checked;
          else if (e.target.id === 'toggle-lower') this.state.useLowercase = e.target.checked;
          else if (e.target.id === 'toggle-numbers') this.state.useNumbers = e.target.checked;
          else if (e.target.id === 'toggle-symbols') this.state.useSymbols = e.target.checked;

          this.generatePassword();
          this.updateGeneratorDisplay();
        } else if (e.target.id === 'toggle-hide-pass') {
          this.state.hidePasswordsByDefault = e.target.checked;
          this.showToast(e.target.checked ? 'Passwords hidden by default' : 'Passwords visible by default');
        } else if (e.target.id === 'toggle-biometric') {
          this.state.biometricEnabled = e.target.checked;
          this.showToast(e.target.checked ? 'Biometrics enabled' : 'Biometrics disabled');
        } else if (e.target.id === 'toggle-screenshot') {
          this.state.disableScreenshots = e.target.checked;
          this.showToast(e.target.checked ? 'Screenshot protection enabled' : 'Screenshot protection disabled');
        }
      });

      // Keyboard navigation (MOD-01)
      this.root.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
          this.closeDialogs();
        }
        if (e.key === 'Enter' && e.target.id === 'lock-password') {
          const btn = this.root.querySelector('[data-action="submit-unlock"]');
          if (btn) btn.click();
        }
      });
    }
  }

  // Initialize once DOM is ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => new VaultPassApp());
  } else {
    new VaultPassApp();
  }
})();
