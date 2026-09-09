// VaultPass Website JavaScript - Utilities, Direct GitHub Release & Live Markdown Sync

let latestApkUrl = 'v2.6.3.apk';
let latestVersion = 'v2.6.3';
let latestFileSize = '23.2 MB';

document.addEventListener('DOMContentLoaded', () => {
  initMobileMenu();
  initPasswordGenerator();
  initCopyButtons();
  initGitHubRelease();
  initVirusTotalLink();
  initDirectDownloadButtons();
  initLivePrivacyPolicy();
});

// Clipboard Helper with double-click guard and robust fallback
async function safeCopyToClipboard(textToCopy, btn, onSuccess) {
  if (!textToCopy || !btn) return;
  if (btn.dataset.isCopying === 'true') return;
  btn.dataset.isCopying = 'true';

  let copied = false;
  if (window.isSecureContext && navigator.clipboard && navigator.clipboard.writeText) {
    try {
      await navigator.clipboard.writeText(textToCopy);
      copied = true;
    } catch (err) {
      console.warn('navigator.clipboard failed, attempting fallback:', err);
    }
  }

  if (!copied) {
    try {
      const textarea = document.createElement('textarea');
      textarea.value = textToCopy;
      textarea.style.position = 'fixed';
      textarea.style.left = '-9999px';
      textarea.style.top = '-9999px';
      textarea.setAttribute('readonly', '');
      document.body.appendChild(textarea);
      textarea.select();
      copied = document.execCommand('copy');
      document.body.removeChild(textarea);
    } catch (err) {
      console.error('execCommand copy fallback failed:', err);
    }
  }

  if (copied && typeof onSuccess === 'function') {
    onSuccess();
  }

  setTimeout(() => {
    btn.dataset.isCopying = 'false';
  }, 2000);
}

// 1. Mobile Menu Toggle
function initMobileMenu() {
  const toggleBtn = document.getElementById('mobile-menu-btn');
  const mobileMenu = document.getElementById('mobile-menu');

  if (toggleBtn && mobileMenu) {
    const closeMobileMenu = () => {
      mobileMenu.classList.add('hidden');
      toggleBtn.setAttribute('aria-expanded', 'false');
    };

    toggleBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      const isExpanded = toggleBtn.getAttribute('aria-expanded') === 'true';
      toggleBtn.setAttribute('aria-expanded', !isExpanded);
      mobileMenu.classList.toggle('hidden');
    });

    // Close menu when clicking ANY link OR button inside #mobile-menu
    mobileMenu.querySelectorAll('a, button').forEach(el => {
      el.addEventListener('click', closeMobileMenu);
    });

    // Close menu on click/tap outside menu & button
    document.addEventListener('click', (e) => {
      if (!mobileMenu.classList.contains('hidden')) {
        if (!mobileMenu.contains(e.target) && !toggleBtn.contains(e.target)) {
          closeMobileMenu();
        }
      }
    });

    // Close menu on Escape key
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && !mobileMenu.classList.contains('hidden')) {
        closeMobileMenu();
      }
    });
  }
}

// 2. Fetch Latest GitHub Release Details (Zero Redirect, Direct File Download)
async function initGitHubRelease() {
  try {
    const res = await fetch('https://api.github.com/repos/ArmaanCode2/Vault-pass/releases/latest');
    if (res.ok) {
      const data = await res.json();
      if (data.tag_name) {
        latestVersion = data.tag_name;
      }

      const apkAsset = data.assets && data.assets.find(a => a.name && a.name.endsWith('.apk'));
      if (apkAsset && apkAsset.browser_download_url) {
        latestApkUrl = apkAsset.browser_download_url;
        if (apkAsset.size) {
          latestFileSize = (apkAsset.size / (1024 * 1024)).toFixed(1) + ' MB';
        }
      }

      document.querySelectorAll('[data-latest-version]').forEach(el => {
        el.textContent = latestVersion;
      });

      document.querySelectorAll('[data-latest-size]').forEach(el => {
        el.textContent = latestFileSize;
      });
    }
  } catch (e) {
    console.warn('Unable to query GitHub releases API, using fallback.', e);
  }
}

// 3. Dynamic VirusTotal Sync from GitHub's README.md
async function initVirusTotalLink() {
  try {
    const res = await fetch('https://raw.githubusercontent.com/ArmaanCode2/Vault-pass/master/README.md', {
      signal: AbortSignal.timeout(4000)
    });
    if (res.ok) {
      const markdown = await res.text();
      const match = markdown.match(/https?:\/\/(?:www\.)?virustotal\.com\/gui\/file\/[a-fA-F0-9]{64}(?:\?[^\s\)"']*)?/);
      if (match && match[0]) {
        const vtUrl = match[0];
        document.querySelectorAll('[data-virustotal-link]').forEach(el => {
          el.href = vtUrl;
        });
      }
    }
  } catch (err) {
    console.warn('Could not fetch dynamic VirusTotal link from README:', err);
  }
}

// 4. Live Sync of PRIVACY.md from GitHub (Zero-Backend Client-Side Parsing)
// Whenever you update PRIVACY.md on GitHub, visitors immediately see the latest version!
async function initLivePrivacyPolicy() {
  const container = document.getElementById('privacy-content');
  if (!container) return;

  try {
    const cacheBuster = Date.now();
    const res = await fetch(`https://raw.githubusercontent.com/ArmaanCode2/Vault-pass/master/PRIVACY.md?_=${cacheBuster}`, {
      signal: AbortSignal.timeout(4000)
    });
    if (res.ok) {
      const markdown = await res.text();
      if (window.marked && window.marked.parse) {
        const rawHtml = window.marked.parse(markdown);
        container.innerHTML = window.DOMPurify ? window.DOMPurify.sanitize(rawHtml) : rawHtml;
        return;
      }
    }
  } catch (err) {
    console.warn('Could not fetch live PRIVACY.md from GitHub, using fallback:', err);
  }

  // Fallback for offline viewing matching PRIVACY.md
  container.innerHTML = `
    <p class="text-xs text-slate-400 mb-4"><strong>Effective Date:</strong> June 9, 2026</p>
    <h2>1. Introduction</h2>
    <p>Welcome to VaultPass. We respect your privacy and are committed to protecting it. VaultPass is designed from the ground up to be an <strong>offline-first</strong> password manager. We believe your data belongs exclusively to you. VaultPass operates locally on your device without requiring an internet connection or account registration.</p>

    <h2>2. Information Stored by the App</h2>
    <p>VaultPass allows you to store personal information, including passwords, usernames, URLs, notes, and custom fields. <strong>We do not collect, transmit, or have access to any of this information.</strong> There is no central database of users, and you will never be asked for an email, phone number, or personal identifier.</p>

    <h2>3. Local Device Storage and System Backups</h2>
    <p>All data you input into VaultPass is stored strictly and entirely on your local device.</p>
    <ul>
      <li><strong>No Developer Cloud Servers:</strong> The App does not sync your data to any cloud servers. VaultPass operates zero cloud infrastructure.</li>
      <li><strong>No Remote Databases:</strong> No backend servers receive or process your data.</li>
      <li><strong>No External Telemetry or Analytics:</strong> The App does not monitor behavior, log actions, or send crash reports to anyone.</li>
      <li><strong>Android System Cloud Backups:</strong> If Android Auto-Backup or Google Drive backup is enabled on your device, the OS may include app data in your private Google account. You can manage or disable this in Android system settings under Google Backup.</li>
    </ul>

    <h2>4. Encryption and Security</h2>
    <p>All vault data is protected using standard AES-GCM encryption before writing to local storage.</p>
    <ul>
      <li><strong>Master Password:</strong> Derived via PBKDF2-HMAC-SHA256 (300,000 rounds). The key is never transmitted off your device.</li>
      <li><strong>No Recovery Backdoors:</strong> Because data is encrypted locally and we do not hold your master password, lost passwords cannot be recovered.</li>
      <li><strong>Local Brute-Force Protection:</strong> Progressive lockout cooldowns mitigate local automated guessing attempts.</li>
    </ul>

    <h2>5. Import and Export Features</h2>
    <p>VaultPass provides utilities to import and export your vault data (TXT, JSON, or encrypted VPEX format) using Android's file picker. Encrypted VPEX archives remain protected with Base64 AES-GCM packaging.</p>

    <h2>6. Biometric Authentication</h2>
    <p>VaultPass supports biometric unlock via the Android Keystore. Biometric data is managed entirely by your device hardware/OS; the App never collects, stores, or transmits biometric templates.</p>

    <h2>7. Android Autofill Service</h2>
    <p>When enabled, the Autofill service inspects the foreground app or website structure using temporary in-memory BFS heuristics to offer matching credentials. Screen information is never stored or transmitted.</p>

    <h2>8. Clipboard Safety</h2>
    <p>Credentials copied to the clipboard are temporarily held and can be automatically cleared based on configurable timer settings in the App.</p>

    <h2>9. Third-Party Services</h2>
    <p>VaultPass operates without third-party network services. No advertisements, no analytics SDKs, and no data sharing or selling.</p>

    <h2>10. Children's Privacy</h2>
    <p>VaultPass is not intended for children under 13 and collects zero personal information from any user.</p>

    <h2>11. Data Retention</h2>
    <p>You have sole control over data retention. Items in the Recycle Bin are permanently deleted after 7 days or on demand. Clearing app data or uninstalling removes all local database records.</p>

    <h2>12. Your Privacy Rights (GDPR and CCPA)</h2>
    <p>Because VaultPass does not store data on servers, you exercise full access, portability, and deletion directly on your device at any time.</p>

    <h2>13. User Responsibilities</h2>
    <p>Users are responsible for selecting strong master passwords, securing physical device access, and safely storing any exported unencrypted backup files.</p>

    <h2>14. Changes to This Privacy Policy</h2>
    <p>Any updates to this policy will be reflected in the repository PRIVACY.md and synchronized on this page.</p>

    <h2>15. Contact Information</h2>
    <p>For questions or security disclosures: <strong>armaanweb100@gmail.com</strong> or visit <a href="https://github.com/ArmaanCode2/Vault-pass" target="_blank" rel="noopener noreferrer" class="text-emerald-400 hover:underline">GitHub Repository</a>.</p>
  `;
}

// 5. Direct Download Triggers (Directly download APK on site without navigating to GitHub)
function initDirectDownloadButtons() {
  document.querySelectorAll('[data-direct-download]').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();

      if (btn.dataset.isDownloading === 'true') {
        return;
      }
      btn.dataset.isDownloading = 'true';

      const downloadLink = document.createElement('a');
      downloadLink.href = latestApkUrl;
      downloadLink.setAttribute('download', latestApkUrl.split('/').pop() || 'vaultpass.apk');
      downloadLink.target = '_blank';
      downloadLink.rel = 'noopener noreferrer';
      
      document.body.appendChild(downloadLink);
      downloadLink.click();
      document.body.removeChild(downloadLink);

      const statusSpan = btn.querySelector('.download-status');
      if (statusSpan) {
        const origContent = statusSpan.innerHTML;
        statusSpan.textContent = 'Downloading...';
        setTimeout(() => {
          statusSpan.innerHTML = origContent;
          const verSpan = statusSpan.querySelector('[data-latest-version]');
          if (verSpan) {
            verSpan.textContent = latestVersion;
          }
          btn.dataset.isDownloading = 'false';
        }, 2500);
      } else {
        setTimeout(() => {
          btn.dataset.isDownloading = 'false';
        }, 2500);
      }
    });
  });
}

// 6. Interactive Password Generator
function initPasswordGenerator() {
  const lengthSlider = document.getElementById('pw-length');
  const lengthDisplay = document.getElementById('pw-length-val');
  const outputField = document.getElementById('pw-output');
  const regenerateBtn = document.getElementById('pw-regenerate-btn');
  const copyBtn = document.getElementById('pw-copy-btn');
  const entropyBadge = document.getElementById('pw-entropy');
  const strengthBar = document.getElementById('pw-strength-bar');
  const strengthLabel = document.getElementById('pw-strength-label');

  const chkUpper = document.getElementById('chk-upper');
  const chkLower = document.getElementById('chk-lower');
  const chkNumbers = document.getElementById('chk-numbers');
  const chkSymbols = document.getElementById('chk-symbols');

  if (!lengthSlider || !outputField) return;

  const charSets = {
    upper: 'ABCDEFGHJKLMNPQRSTUVWXYZ',
    lower: 'abcdefghjkmnpqrstuvwxyz',
    numbers: '23456789',
    symbols: '!@#$%^&*()_+-=[]{}|;:,.<>?'
  };

  function generatePassword() {
    let pool = '';
    let required = [];

    if (chkUpper && chkUpper.checked) {
      pool += charSets.upper;
      required.push(charSets.upper);
    }
    if (chkLower && chkLower.checked) {
      pool += charSets.lower;
      required.push(charSets.lower);
    }
    if (chkNumbers && chkNumbers.checked) {
      pool += charSets.numbers;
      required.push(charSets.numbers);
    }
    if (chkSymbols && chkSymbols.checked) {
      pool += charSets.symbols;
      required.push(charSets.symbols);
    }

    if (pool.length === 0) {
      if (chkLower) chkLower.checked = true;
      pool = charSets.lower;
      required.push(charSets.lower);
    }

    const length = parseInt(lengthSlider.value, 10);
    const randomBuffer = new Uint32Array(length);
    window.crypto.getRandomValues(randomBuffer);

    let passwordChars = [];

    for (let i = 0; i < required.length && i < length; i++) {
      const set = required[i];
      const randIdx = randomBuffer[i] % set.length;
      passwordChars.push(set[randIdx]);
    }

    for (let i = passwordChars.length; i < length; i++) {
      const randIdx = randomBuffer[i] % pool.length;
      passwordChars.push(pool[randIdx]);
    }

    const shuffleBuffer = new Uint32Array(length);
    window.crypto.getRandomValues(shuffleBuffer);
    for (let i = length - 1; i > 0; i--) {
      const j = shuffleBuffer[i] % (i + 1);
      const temp = passwordChars[i];
      passwordChars[i] = passwordChars[j];
      passwordChars[j] = temp;
    }

    const password = passwordChars.join('');
    outputField.value = password;

    const poolSize = pool.length;
    const entropy = Math.round(length * Math.log2(poolSize));
    if (entropyBadge) {
      entropyBadge.textContent = `${entropy} bits entropy`;
    }

    let strength = 'Weak';
    let color = 'bg-rose-500';
    let width = '25%';

    if (entropy >= 80) {
      strength = 'Military Grade';
      color = 'bg-emerald-400';
      width = '100%';
    } else if (entropy >= 60) {
      strength = 'Very Strong';
      color = 'bg-emerald-400';
      width = '80%';
    } else if (entropy >= 45) {
      strength = 'Moderate';
      color = 'bg-amber-400';
      width = '55%';
    }

    if (strengthBar) {
      strengthBar.className = `h-full rounded-full transition-all duration-300 ${color}`;
      strengthBar.style.width = width;
    }
    if (strengthLabel) {
      strengthLabel.textContent = strength;
    }

    updateToggleChipClasses();
  }

  function updateToggleChipClasses() {
    [chkUpper, chkLower, chkNumbers, chkSymbols].forEach(chk => {
      if (!chk) return;
      const chip = chk.closest('.toggle-chip');
      if (chip) {
        chip.classList.toggle('is-checked', chk.checked);
      }
    });
  }

  lengthSlider.addEventListener('input', (e) => {
    if (lengthDisplay) lengthDisplay.textContent = e.target.value;
    generatePassword();
  });

  const checkboxes = [chkUpper, chkLower, chkNumbers, chkSymbols].filter(Boolean);
  checkboxes.forEach(chk => {
    chk.addEventListener('change', () => {
      const activeCount = checkboxes.filter(c => c.checked).length;
      if (activeCount === 0) {
        chk.checked = true; // Prevent unchecking last active option
      }
      updateToggleChipClasses();
      generatePassword();
    });
  });

  if (regenerateBtn) {
    regenerateBtn.addEventListener('click', (e) => {
      e.preventDefault();
      generatePassword();
    });
  }

  if (copyBtn) {
    copyBtn.addEventListener('click', () => {
      const origText = copyBtn.innerHTML;
      safeCopyToClipboard(outputField.value, copyBtn, () => {
        copyBtn.innerHTML = `
          <svg class="w-4 h-4 text-emerald-400 inline" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/>
          </svg>
          <span class="text-xs text-emerald-400 font-medium">Copied!</span>
        `;
        setTimeout(() => {
          copyBtn.innerHTML = origText;
        }, 2000);
      });
    });
  }

  generatePassword();
}

// 7. Copy-to-clipboard buttons
function initCopyButtons() {
  document.querySelectorAll('[data-copy-target]').forEach(btn => {
    btn.addEventListener('click', () => {
      const textToCopy = btn.getAttribute('data-copy-target');
      if (!textToCopy) return;

      const originalContent = btn.innerHTML;
      safeCopyToClipboard(textToCopy, btn, () => {
        btn.innerHTML = `
          <span class="text-xs text-emerald-400 font-medium flex items-center gap-1">
            <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/>
            </svg>
            Copied!
          </span>
        `;
        setTimeout(() => {
          btn.innerHTML = originalContent;
        }, 2000);
      });
    });
  });
}
