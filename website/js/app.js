// VaultPass Website JavaScript - Utilities, Direct GitHub Release & Live Markdown Sync

let latestApkUrl = 'downloads/vaultpass-v1.0.0.apk';
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

// 1. Mobile Menu Toggle
function initMobileMenu() {
  const toggleBtn = document.getElementById('mobile-menu-btn');
  const mobileMenu = document.getElementById('mobile-menu');

  if (toggleBtn && mobileMenu) {
    toggleBtn.addEventListener('click', () => {
      const isExpanded = toggleBtn.getAttribute('aria-expanded') === 'true';
      toggleBtn.setAttribute('aria-expanded', !isExpanded);
      mobileMenu.classList.toggle('hidden');
    });

    mobileMenu.querySelectorAll('a').forEach(link => {
      link.addEventListener('click', () => {
        mobileMenu.classList.add('hidden');
        toggleBtn.setAttribute('aria-expanded', 'false');
      });
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
    const res = await fetch('https://raw.githubusercontent.com/ArmaanCode2/Vault-pass/master/README.md');
    if (res.ok) {
      const markdown = await res.text();
      const match = markdown.match(/https?:\/\/(?:www\.)?virustotal\.com[^\s\)"']+/);
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
    const res = await fetch(`https://raw.githubusercontent.com/ArmaanCode2/Vault-pass/master/PRIVACY.md?_=${cacheBuster}`);
    if (res.ok) {
      const markdown = await res.text();
      if (window.marked && window.marked.parse) {
        container.innerHTML = window.marked.parse(markdown);
        return;
      }
    }
  } catch (err) {
    console.warn('Could not fetch live PRIVACY.md from GitHub, using fallback:', err);
  }

  // Fallback for offline viewing
  container.innerHTML = `
    <h2>1. The Core Commitment</h2>
    <p>VaultPass is designed from the ground up to be an <strong>offline-first</strong> password manager. Your personal data belongs exclusively to you and operates locally on your device without requiring an internet connection or account creation.</p>
    <h2>2. Information Stored by the App</h2>
    <p>VaultPass allows you to store personal credentials, passwords, usernames, URLs, notes, and custom fields. <strong>We do not collect, transmit, or have access to any of this information.</strong></p>
    <h2>3. Zero Developer Servers & Zero Telemetry</h2>
    <p>The application runs no cloud servers, has no central database, and includes no analytics or crash tracking SDKs.</p>
    <h2>4. Cryptographic Protection</h2>
    <p>Credentials are protected via AES-256-GCM and PBKDF2-HMAC-SHA256 (300,000 rounds). Lost master passwords cannot be recovered because no backdoors exist.</p>
  `;
}

// 5. Direct Download Triggers (Directly download APK on site without navigating to GitHub)
function initDirectDownloadButtons() {
  document.querySelectorAll('[data-direct-download]').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();

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
        const oldStatus = statusSpan.textContent;
        statusSpan.textContent = 'Downloading...';
        setTimeout(() => {
          statusSpan.textContent = oldStatus;
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

  [chkUpper, chkLower, chkNumbers, chkSymbols].forEach(chk => {
    if (chk) {
      chk.addEventListener('change', () => {
        updateToggleChipClasses();
        generatePassword();
      });
    }
  });

  if (regenerateBtn) {
    regenerateBtn.addEventListener('click', (e) => {
      e.preventDefault();
      generatePassword();
    });
  }

  if (copyBtn) {
    copyBtn.addEventListener('click', () => {
      navigator.clipboard.writeText(outputField.value).then(() => {
        const origText = copyBtn.innerHTML;
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

      navigator.clipboard.writeText(textToCopy).then(() => {
        const originalContent = btn.innerHTML;
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
