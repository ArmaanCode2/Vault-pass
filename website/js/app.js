// VaultPass website script: release info, downloads, generator, menu, privacy page.

// The static HTML links to GitHub's "latest release" download of VaultPass.apk
// and shows no version, size or hash of its own. When the GitHub API answers,
// the link, version, file name, size, hash and VirusTotal lookup are all
// replaced together from the same API response. Without a published digest
// the page shows no hash and no VirusTotal link.
const REPO_URL = 'https://github.com/ArmaanCode2/Vault-pass';
const RELEASES_API = 'https://api.github.com/repos/ArmaanCode2/Vault-pass/releases/latest';
const RELEASE_CACHE_KEY = 'vaultpass_release_v3';
const RELEASE_CACHE_TTL = 60 * 60 * 1000; // 1 hour
const APK_ASSET_NAME = 'VaultPass.apk';

const release = {
  version: '', // Unknown until the API answers
  url: REPO_URL + '/releases/latest/download/' + APK_ASSET_NAME,
  fileName: APK_ASSET_NAME,
  size: '',
  sha256: ''
};

document.addEventListener('DOMContentLoaded', () => {
  initServiceWorker();
  initMobileMenu();
  initActiveNav();
  initRevealOnScroll();
  initPasswordGenerator();
  initCopyButtons();
  initDirectDownloadButtons();
  initGitHubRelease();
  initLivePrivacyPolicy();
});

// ---------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------

function prefersReducedMotion() {
  return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

// fetch() with a timeout that also works where AbortSignal.timeout is missing (Safari < 16)
function fetchWithTimeout(url, options = {}, timeoutMs = 4000) {
  if (typeof AbortController === 'undefined') {
    return fetch(url, options);
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  return fetch(url, Object.assign({}, options, { signal: controller.signal }))
    .finally(() => clearTimeout(timer));
}

function getLocalStorageCache(key, maxAgeMs) {
  try {
    const itemStr = localStorage.getItem(key);
    if (!itemStr) return null;
    const item = JSON.parse(itemStr);
    if (!item || typeof item.timestamp !== 'number') return null;
    if (Date.now() - item.timestamp > maxAgeMs) return null;
    return item.data;
  } catch (e) {
    return null;
  }
}

function setLocalStorageCache(key, data) {
  try {
    localStorage.setItem(key, JSON.stringify({ timestamp: Date.now(), data }));
  } catch (e) {
    // Storage full or disabled; the page works without it.
  }
}

// Copies text, with an execCommand fallback for older browsers / http.
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
  announce(copied ? 'Copied to clipboard' : 'Copy failed. Select the text and copy it manually.');

  setTimeout(() => {
    btn.dataset.isCopying = 'false';
  }, 2000);
}

// Screen-reader announcements through one shared live region
function announce(message) {
  let region = document.getElementById('sr-status');
  if (!region) {
    region = document.createElement('div');
    region.id = 'sr-status';
    region.className = 'sr-only';
    region.setAttribute('role', 'status');
    region.setAttribute('aria-live', 'polite');
    document.body.appendChild(region);
  }
  region.textContent = '';
  setTimeout(() => { region.textContent = message; }, 50);
}

// ---------------------------------------------------------------
// Service worker
// ---------------------------------------------------------------
function initServiceWorker() {
  const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
  if ('serviceWorker' in navigator && (window.location.protocol === 'https:' || isLocal)) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('sw.js').catch(err => {
        console.debug('Service worker registration skipped or failed:', err);
      });
    });
  }
}

// ---------------------------------------------------------------
// Mobile menu: toggle, Esc to close, focus management
// ---------------------------------------------------------------
function initMobileMenu() {
  const toggleBtn = document.getElementById('mobile-menu-btn');
  const mobileMenu = document.getElementById('mobile-menu');
  if (!toggleBtn || !mobileMenu) return;

  const iconOpen = toggleBtn.querySelector('[data-icon="open"]');
  const iconClose = toggleBtn.querySelector('[data-icon="close"]');

  const setOpen = (open, { returnFocus = false } = {}) => {
    mobileMenu.classList.toggle('hidden', !open);
    toggleBtn.setAttribute('aria-expanded', String(open));
    toggleBtn.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
    if (iconOpen) iconOpen.classList.toggle('hidden', open);
    if (iconClose) iconClose.classList.toggle('hidden', !open);
    if (open) {
      const first = mobileMenu.querySelector('a, button');
      if (first) first.focus();
    } else if (returnFocus) {
      toggleBtn.focus();
    }
  };

  const isOpen = () => !mobileMenu.classList.contains('hidden');

  toggleBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    setOpen(!isOpen());
  });

  // Close after choosing a link or the download button
  mobileMenu.querySelectorAll('a, button').forEach(el => {
    el.addEventListener('click', () => setOpen(false));
  });

  // Close on click outside
  document.addEventListener('click', (e) => {
    if (isOpen() && !mobileMenu.contains(e.target) && !toggleBtn.contains(e.target)) {
      setOpen(false);
    }
  });

  // Close on Escape and hand focus back to the toggle
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && isOpen()) {
      setOpen(false, { returnFocus: true });
    }
  });

  // Close when the layout switches to the desktop nav
  if (window.matchMedia) {
    const mq = window.matchMedia('(min-width: 768px)');
    const onChange = (ev) => { if (ev.matches && isOpen()) setOpen(false); };
    if (mq.addEventListener) mq.addEventListener('change', onChange);
    else if (mq.addListener) mq.addListener(onChange);
  }
}

// ---------------------------------------------------------------
// Highlight "Features" in the nav while that section is on screen
// (other pages mark their own link with aria-current="page")
// ---------------------------------------------------------------
function initActiveNav() {
  const section = document.getElementById('features');
  const links = document.querySelectorAll('.nav-link[href="#features"], .mobile-nav-link[href="#features"]');
  if (!section || !links.length || !('IntersectionObserver' in window)) return;

  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      links.forEach(link => link.classList.toggle('is-active', entry.isIntersecting));
    });
  }, { rootMargin: '-40% 0px -50% 0px' });
  observer.observe(section);
}

// ---------------------------------------------------------------
// Reveal sections as they scroll in (off for reduced motion / no IO)
// ---------------------------------------------------------------
function initRevealOnScroll() {
  if (prefersReducedMotion() || !('IntersectionObserver' in window)) return;

  const targets = Array.from(document.querySelectorAll('main > section, main > article'))
    .filter((el, i) => i > 0); // the first section is above the fold
  if (!targets.length) return;

  document.documentElement.classList.add('js-reveal');
  const observer = new IntersectionObserver((entries, obs) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        obs.unobserve(entry.target);
      }
    });
  }, { rootMargin: '0px 0px -8% 0px', threshold: 0.05 });

  targets.forEach(el => {
    el.classList.add('reveal');
    observer.observe(el);
  });

  // An anchor jump (e.g. index.html#download) should never land on a hidden section
  const showHashTarget = () => {
    const id = window.location.hash.slice(1);
    const el = id && document.getElementById(id);
    const section = el && el.closest('.reveal');
    if (section) section.classList.add('is-visible');
  };
  showHashTarget();
  window.addEventListener('hashchange', showHashTarget);
}

// ---------------------------------------------------------------
// Release info: download links, size, checksum, VirusTotal lookup
// ---------------------------------------------------------------
function isTrustedAssetUrl(url) {
  return typeof url === 'string' && url.indexOf(REPO_URL + '/releases/download/') === 0;
}

function isSha256(value) {
  return typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
}

function formatSize(bytes) {
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function applyRelease(info) {
  document.querySelectorAll('[data-direct-download]').forEach(el => {
    if (el.tagName === 'A') el.href = info.url;
  });
  // Keep the static wording when the API left a value out
  if (info.version) {
    document.querySelectorAll('[data-latest-version]').forEach(el => { el.textContent = info.version; });
  }
  if (info.size) {
    document.querySelectorAll('[data-latest-size]').forEach(el => { el.textContent = info.size; });
  }
  document.querySelectorAll('[data-latest-filename]').forEach(el => { el.textContent = info.fileName; });

  // Verification commands reference the downloaded file name
  document.querySelectorAll('[data-command-template]').forEach(el => {
    const cmd = el.getAttribute('data-command-template').replace('{file}', info.fileName);
    el.setAttribute('data-copy-target', cmd);
  });
  document.querySelectorAll('[data-command-text]').forEach(el => {
    el.textContent = el.getAttribute('data-command-text').replace('{file}', info.fileName);
  });

  // Hash and VirusTotal link always come from the same digest as the file.
  // If the release has no published digest, hide them instead of showing a stale hash.
  const hasHash = isSha256(info.sha256);
  document.querySelectorAll('[data-sha256-text]').forEach(el => { el.textContent = hasHash ? info.sha256 : ''; });
  document.querySelectorAll('[data-sha256-copy]').forEach(el => {
    el.setAttribute('data-copy-target', hasHash ? info.sha256 : '');
  });
  document.querySelectorAll('[data-checksum-block]').forEach(el => { el.hidden = !hasHash; });
  document.querySelectorAll('[data-checksum-missing]').forEach(el => { el.hidden = hasHash; });
  document.querySelectorAll('[data-virustotal-link]').forEach(el => {
    if (hasHash) {
      el.href = 'https://www.virustotal.com/gui/file/' + info.sha256;
      el.hidden = false;
    } else {
      el.hidden = true;
    }
  });
}

async function initGitHubRelease() {
  const cached = getLocalStorageCache(RELEASE_CACHE_KEY, RELEASE_CACHE_TTL);
  if (cached && isTrustedAssetUrl(cached.url)) {
    Object.assign(release, cached);
    applyRelease(release);
    return; // Fresh cache: skip the API (it allows 60 requests/hour per IP)
  }

  try {
    const res = await fetchWithTimeout(RELEASES_API, { headers: { Accept: 'application/vnd.github+json' } });
    if (!res.ok) return;
    const data = await res.json();
    const apkAsset = pickApkAsset(data.assets);
    if (!apkAsset || !isTrustedAssetUrl(apkAsset.browser_download_url)) return;

    const digest = typeof apkAsset.digest === 'string' && apkAsset.digest.indexOf('sha256:') === 0
      ? apkAsset.digest.slice(7).toLowerCase()
      : '';

    const next = {
      version: typeof data.tag_name === 'string' ? data.tag_name : release.version,
      url: apkAsset.browser_download_url,
      fileName: apkAsset.name,
      size: apkAsset.size ? formatSize(apkAsset.size) : release.size,
      sha256: isSha256(digest) ? digest : ''
    };

    Object.assign(release, next);
    setLocalStorageCache(RELEASE_CACHE_KEY, next);
    applyRelease(release);
  } catch (e) {
    // Offline, rate-limited or blocked: the links in the HTML already point at
    // the latest release's VaultPass.apk; no hash is shown without the API.
    console.warn('GitHub releases API unavailable; using the release linked in the page.', e);
  }
}

// Same choice as the app's updater: the asset named exactly VaultPass.apk,
// otherwise the release's only .apk (none when there are several).
function pickApkAsset(assets) {
  if (!Array.isArray(assets)) return null;
  const named = assets.filter(a => a && typeof a.name === 'string');
  const preferred = named.find(a => a.name === APK_ASSET_NAME);
  if (preferred) return preferred;
  const apks = named.filter(a => a.name.toLowerCase().endsWith('.apk'));
  return apks.length === 1 ? apks[0] : null;
}

// ---------------------------------------------------------------
// Download links: real <a href> elements (they work with JS off).
// JS only adds click feedback and blocks accidental double clicks.
// ---------------------------------------------------------------
function initDirectDownloadButtons() {
  document.querySelectorAll('[data-direct-download]').forEach(link => {
    link.addEventListener('click', (e) => {
      if (link.dataset.isDownloading === 'true') {
        e.preventDefault();
        return;
      }
      link.dataset.isDownloading = 'true';
      link.classList.add('is-busy');

      const statusSpan = link.querySelector('.download-status');
      const origContent = statusSpan ? statusSpan.innerHTML : null;
      if (statusSpan) statusSpan.textContent = 'Starting download…';
      announce(release.version
        ? 'Downloading VaultPass ' + release.version + ' from GitHub'
        : 'Downloading the latest VaultPass from GitHub');

      setTimeout(() => {
        if (statusSpan && origContent !== null) {
          statusSpan.innerHTML = origContent;
          const verSpan = statusSpan.querySelector('[data-latest-version]');
          if (verSpan && release.version) verSpan.textContent = release.version;
        }
        link.classList.remove('is-busy');
        link.dataset.isDownloading = 'false';
      }, 2500);
    });
  });
}

// ---------------------------------------------------------------
// Privacy policy: rendered from PRIVACY.md on GitHub.
// Fails closed: without DOMPurify, no fetched HTML is injected.
// ---------------------------------------------------------------
async function initLivePrivacyPolicy() {
  const container = document.getElementById('privacy-content');
  if (!container) return;

  const CACHE_KEY = 'vaultpass_privacy_md_v2';
  const CACHE_TTL = 6 * 60 * 60 * 1000; // 6 hours
  const sourceLabel = document.getElementById('privacy-source');

  const canRender = Boolean(window.marked && typeof window.marked.parse === 'function' &&
    window.DOMPurify && typeof window.DOMPurify.sanitize === 'function');

  const renderMarkdown = (markdown) => {
    if (!canRender) return false;
    try {
      const clean = window.DOMPurify.sanitize(window.marked.parse(markdown));
      container.innerHTML = clean;
      container.querySelectorAll('a[href^="http"]').forEach(a => {
        a.target = '_blank';
        a.rel = 'noopener noreferrer';
      });
      return true;
    } catch (err) {
      console.warn('Could not render PRIVACY.md:', err);
      return false;
    }
  };

  // Cache the markdown source (not HTML) so every render goes through DOMPurify
  const cachedMarkdown = getLocalStorageCache(CACHE_KEY, CACHE_TTL);
  if (typeof cachedMarkdown === 'string' && renderMarkdown(cachedMarkdown)) {
    if (sourceLabel) sourceLabel.textContent = 'From GitHub: PRIVACY.md';
    return;
  }

  if (canRender) {
    try {
      const res = await fetchWithTimeout(REPO_URL.replace('https://github.com/', 'https://raw.githubusercontent.com/') + '/master/PRIVACY.md', { cache: 'no-cache' });
      if (res.ok) {
        const markdown = await res.text();
        if (renderMarkdown(markdown)) {
          setLocalStorageCache(CACHE_KEY, markdown);
          if (sourceLabel) sourceLabel.textContent = 'From GitHub: PRIVACY.md';
          return;
        }
      }
    } catch (err) {
      console.warn('Could not fetch PRIVACY.md from GitHub, showing the built-in copy:', err);
    }
  }

  if (sourceLabel) sourceLabel.textContent = 'Built-in copy';
  container.innerHTML = PRIVACY_FALLBACK_HTML;
}

// Shown when GitHub or the sanitizer can't be reached.
const PRIVACY_FALLBACK_HTML = `
  <p class="text-xs text-slate-400 mb-4"><strong>Effective Date:</strong> October 1, 2026. This is the copy built into the website; the current policy is <a href="https://github.com/ArmaanCode2/Vault-pass/blob/master/PRIVACY.md" target="_blank" rel="noopener noreferrer">PRIVACY.md on GitHub</a>.</p>
  <h2>1. Introduction</h2>
  <p>VaultPass is a password manager for Android. It stores your vault on your phone and does not need an account or an internet connection to work. It has two optional network features: sync with VaultPass Desktop over your local network (Section 5), and a check for new versions on GitHub, which is off unless you turn it on (Section 6).</p>

  <h2>2. Information Stored by the App</h2>
  <p>VaultPass stores what you put in it: passwords, usernames, URLs, notes and custom fields. <strong>We do not collect, transmit, or have access to any of this information.</strong> There is no user database, and you are never asked for an email, phone number, or other identifier.</p>

  <h2>3. Local Device Storage and System Backups</h2>
  <p>Your vault is stored on your device, and on your own computer if you sync with VaultPass Desktop.</p>
  <ul>
    <li><strong>No cloud servers:</strong> VaultPass does not upload your data to any server. The developer operates no cloud infrastructure.</li>
    <li><strong>No telemetry or analytics:</strong> The app does not track usage, log your actions, or send crash reports.</li>
    <li><strong>Android system backups:</strong> The app turns Android Auto Backup off (<code>allowBackup="false"</code>), so Android does not copy VaultPass data to your Google account. The vault database and settings are also excluded from Android's device-to-device transfer.</li>
  </ul>

  <h2>4. Encryption and Security</h2>
  <p>Vault data is encrypted with AES-256-GCM before it is written to storage.</p>
  <ul>
    <li><strong>Master password:</strong> The encryption key is derived from your master password with PBKDF2-HMAC-SHA256 (300,000 iterations). The password never leaves your device.</li>
    <li><strong>No recovery backdoor:</strong> We don't have your master password, so a forgotten master password cannot be recovered.</li>
    <li><strong>Brute-force protection:</strong> Repeated wrong guesses trigger cooldowns of up to 15 minutes.</li>
  </ul>

  <h2>5. Sync with VaultPass Desktop (Local Network)</h2>
  <p>Optional, and off until you pair your phone with VaultPass Desktop by scanning a QR code. The two connect directly over your local network, only while the Sync screen is open; nothing passes through the internet or a server. Every sync is approved on the other device, and every message is encrypted end to end with AES-256-GCM. While the Sync screen is open, the app broadcasts a small announcement with no device name, identifier or vault data. The camera is used only to scan the QR code; the image is never stored or sent.</p>

  <h2>6. Update Checks</h2>
  <p>Off by default. If you turn on "Check for updates when the app opens" in Settings, or tap "Check now", the app asks <code>api.github.com</code> for the latest release. An update is downloaded, from <code>github.com</code> and the GitHub download server it redirects to, only after you tap the update offer.</p>
  <ul>
    <li><strong>What is sent:</strong> A normal HTTPS request whose User-Agent contains the app's version number. Nothing from your vault is sent. GitHub can see your IP address, as with any internet connection.</li>
    <li><strong>Checks before installing:</strong> The download must match the size and the SHA-256 checksum GitHub publishes for the release file; a release without a checksum is not downloaded. It must be VaultPass, newer than the installed version, and signed with the same key; otherwise it is deleted. Android installs it with its own installer.</li>
  </ul>

  <h2>7. Import and Export</h2>
  <p>You can import and export your vault as TXT, JSON, or encrypted VPEX files using Android's file picker. VPEX files are AES-GCM encrypted (and Base64-encoded). TXT and JSON exports are not encrypted.</p>

  <h2>8. Biometric Authentication</h2>
  <p>Biometric unlock uses the Android Keystore. Your fingerprint or face data is handled by Android and never reaches the app.</p>

  <h2>9. Android Autofill Service</h2>
  <p>When you enable it, the Autofill service finds the username and password fields on the login screen. In a browser VaultPass knows, it suggests entries on the same domain as the page; in other apps, only entries linked to that app. Matching happens on your phone and nothing about the screen is sent anywhere. When you pick an entry for an app with "Search VaultPass…", the app's package name and the entry are linked on your phone only: the link is not synced, exported or backed up.</p>

  <h2>10. Clipboard</h2>
  <p>When you copy a credential, VaultPass can clear the clipboard after a timer you choose in Settings. Other apps and keyboards may be able to read the clipboard while the text is on it.</p>

  <h2>11. Third-Party Services</h2>
  <p>VaultPass contains no ads and no analytics or tracking SDKs, and it does not share or sell data. The only third-party service it contacts is GitHub, for the optional update check.</p>

  <h2>12. Children's Privacy</h2>
  <p>VaultPass is not intended for children under 13 and collects no personal information from anyone.</p>

  <h2>13. Data Retention</h2>
  <p>You control retention. Items in the Recycle Bin are deleted permanently after 7 days, or sooner if you empty it. Clearing app data or uninstalling removes the local database. A computer you synced with keeps its own copy until you delete it there.</p>

  <h2>14. Your Privacy Rights (GDPR and CCPA)</h2>
  <p>Because your data never reaches our servers, you can access, export and delete it yourself on your device at any time.</p>

  <h2>15. User Responsibilities</h2>
  <p>Choose a strong master password, keep your phone locked, and store unencrypted exports somewhere safe.</p>

  <h2>16. Changes to This Policy</h2>
  <p>Updates are published in the repository's PRIVACY.md, which this page displays.</p>

  <h2>17. Contact</h2>
  <p>Questions or security reports: <strong>armaanweb100@gmail.com</strong>, or open an issue on the <a href="https://github.com/ArmaanCode2/Vault-pass" target="_blank" rel="noopener noreferrer">GitHub repository</a>.</p>
`;

// ---------------------------------------------------------------
// Password generator (site section)
// ---------------------------------------------------------------

// Uniform random index in [0, max) using rejection sampling (no modulo bias)
function secureRandomIndex(max) {
  const limit = Math.floor(0x100000000 / max) * max;
  const buf = new Uint32Array(1);
  let x;
  do {
    window.crypto.getRandomValues(buf);
    x = buf[0];
  } while (x >= limit);
  return x % max;
}

function initPasswordGenerator() {
  const lengthSlider = document.getElementById('pw-length');
  const lengthDisplay = document.getElementById('pw-length-val');
  const outputField = document.getElementById('pw-output');
  const regenerateBtn = document.getElementById('pw-regenerate-btn');
  const copyBtn = document.getElementById('pw-copy-btn');
  const entropyBadge = document.getElementById('pw-entropy');
  const strengthBar = document.getElementById('pw-strength-bar');
  const strengthLabel = document.getElementById('pw-strength-label');
  const strengthMeter = document.getElementById('pw-strength-meter');

  const chkUpper = document.getElementById('chk-upper');
  const chkLower = document.getElementById('chk-lower');
  const chkNumbers = document.getElementById('chk-numbers');
  const chkSymbols = document.getElementById('chk-symbols');

  if (!lengthSlider || !outputField || !window.crypto || !window.crypto.getRandomValues) return;

  // Look-alike characters (I, l, O, 0, 1) are left out
  const charSets = {
    upper: 'ABCDEFGHJKLMNPQRSTUVWXYZ',
    lower: 'abcdefghjkmnpqrstuvwxyz',
    numbers: '23456789',
    symbols: '!@#$%^&*()_+-=[]{}|;:,.<>?'
  };

  const checkboxes = [chkUpper, chkLower, chkNumbers, chkSymbols].filter(Boolean);

  function generatePassword() {
    let pool = '';
    const required = [];

    if (chkUpper && chkUpper.checked) { pool += charSets.upper; required.push(charSets.upper); }
    if (chkLower && chkLower.checked) { pool += charSets.lower; required.push(charSets.lower); }
    if (chkNumbers && chkNumbers.checked) { pool += charSets.numbers; required.push(charSets.numbers); }
    if (chkSymbols && chkSymbols.checked) { pool += charSets.symbols; required.push(charSets.symbols); }

    if (pool.length === 0) {
      if (chkLower) chkLower.checked = true;
      pool = charSets.lower;
      required.push(charSets.lower);
    }

    const length = parseInt(lengthSlider.value, 10);
    const passwordChars = [];

    // One character from each selected set, then fill from the whole pool
    for (let i = 0; i < required.length && i < length; i++) {
      passwordChars.push(required[i][secureRandomIndex(required[i].length)]);
    }
    for (let i = passwordChars.length; i < length; i++) {
      passwordChars.push(pool[secureRandomIndex(pool.length)]);
    }

    // Fisher-Yates shuffle
    for (let i = passwordChars.length - 1; i > 0; i--) {
      const j = secureRandomIndex(i + 1);
      const temp = passwordChars[i];
      passwordChars[i] = passwordChars[j];
      passwordChars[j] = temp;
    }

    outputField.value = passwordChars.join('');

    const entropy = Math.round(length * Math.log2(pool.length));
    if (entropyBadge) entropyBadge.textContent = `${entropy} bits`;

    let strength = 'Weak';
    let color = 'bg-rose-500';
    let width = 25;

    if (entropy >= 80) {
      strength = 'Very strong';
      color = 'bg-emerald-400';
      width = 100;
    } else if (entropy >= 60) {
      strength = 'Strong';
      color = 'bg-emerald-400';
      width = 80;
    } else if (entropy >= 45) {
      strength = 'Moderate';
      color = 'bg-amber-400';
      width = 55;
    }

    if (strengthBar) {
      strengthBar.className = `h-full rounded-full transition-all duration-300 ${color}`;
      strengthBar.style.width = width + '%';
    }
    if (strengthMeter) {
      strengthMeter.setAttribute('aria-valuenow', String(Math.min(entropy, 128)));
      strengthMeter.setAttribute('aria-valuetext', `${strength}, ${entropy} bits`);
    }
    if (strengthLabel) strengthLabel.textContent = strength;

    updateToggleChipClasses();
  }

  function updateToggleChipClasses() {
    checkboxes.forEach(chk => {
      const chip = chk.closest('.toggle-chip');
      if (chip) chip.classList.toggle('is-checked', chk.checked);
    });
  }

  let lengthRafId = null;
  lengthSlider.addEventListener('input', (e) => {
    if (lengthDisplay) lengthDisplay.textContent = e.target.value;
    if (!lengthRafId) {
      const raf = window.requestAnimationFrame ? window.requestAnimationFrame.bind(window) : (cb) => setTimeout(cb, 16);
      lengthRafId = raf(() => {
        lengthRafId = null;
        generatePassword();
      });
    }
  });

  checkboxes.forEach(chk => {
    chk.addEventListener('change', () => {
      if (checkboxes.filter(c => c.checked).length === 0) {
        chk.checked = true; // keep at least one set
        announce('At least one character set must stay on');
      }
      updateToggleChipClasses();
      generatePassword();
    });
  });

  if (regenerateBtn) {
    regenerateBtn.addEventListener('click', (e) => {
      e.preventDefault();
      generatePassword();
      if (!prefersReducedMotion()) {
        const icon = regenerateBtn.querySelector('svg');
        if (icon && icon.animate) {
          icon.animate([{ transform: 'rotate(0deg)' }, { transform: 'rotate(360deg)' }], { duration: 450, easing: 'ease-out' });
        }
      }
      announce('New password generated');
    });
  }

  if (copyBtn) {
    const label = copyBtn.querySelector('[data-copy-label]');
    copyBtn.addEventListener('click', () => {
      safeCopyToClipboard(outputField.value, copyBtn, () => {
        if (label) label.textContent = 'Copied';
        copyBtn.classList.add('is-done');
        setTimeout(() => {
          if (label) label.textContent = 'Copy';
          copyBtn.classList.remove('is-done');
        }, 2000);
      });
    });
  }

  generatePassword();
}

// ---------------------------------------------------------------
// Copy-to-clipboard buttons ([data-copy-target])
// ---------------------------------------------------------------
function initCopyButtons() {
  document.querySelectorAll('[data-copy-target]').forEach(btn => {
    btn.addEventListener('click', () => {
      // Read at click time: the target can change when release info updates
      const textToCopy = btn.getAttribute('data-copy-target');
      if (!textToCopy) return;

      const label = btn.querySelector('[data-copy-label]');
      safeCopyToClipboard(textToCopy, btn, () => {
        const original = label ? label.textContent : null;
        if (label) label.textContent = 'Copied';
        btn.classList.add('is-done');
        setTimeout(() => {
          if (label && original !== null) label.textContent = original;
          btn.classList.remove('is-done');
        }, 2000);
      });
    });
  });
}
