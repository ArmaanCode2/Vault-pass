// File checker: the visitor drops the APK or the Windows installer they
// downloaded; the browser computes its SHA-256 with Web Crypto and compares it
// with the digest GitHub publishes for that app's latest release. The file is
// read locally and never uploaded anywhere.
(function () {
  'use strict';

  var zone = document.querySelector('[data-checker]');
  if (!zone) return;

  var input = zone.querySelector('input[type="file"]');
  var resultBox = zone.querySelector('[data-checker-result]'); // the live region
  var SITE = window.VPSite;
  var GH = window.VPGitHub;
  var MAX_BYTES = 600 * 1024 * 1024;
  var current = 0; // only the newest file's result is shown

  var known = {}; // appId -> { release, stale } or { release: null } after a failure
  document.addEventListener('vp:release', function (e) {
    known[e.detail.app] = { release: e.detail.release, stale: !!e.detail.stale };
  });
  if (SITE && SITE.releases) {
    Object.keys(SITE.releases).forEach(function (id) {
      known[id] = { release: SITE.releases[id], stale: SITE.outcome && SITE.outcome[id] === 'stale' };
    });
  }

  function appFor(file) {
    if (/\.apk$/i.test(file.name)) return 'android';
    if (/\.(exe|msi)$/i.test(file.name)) return 'windows';
    return '';
  }

  function show(kind, iconName, title, detail, hash) {
    resultBox.textContent = '';
    var box = SITE.el('div', { className: 'result result--' + kind });
    box.appendChild(SITE.icon(iconName));
    var text = SITE.el('div');
    text.appendChild(SITE.el('strong', { text: title }));
    if (detail) text.appendChild(SITE.el('p', { text: detail }));
    box.appendChild(text);
    if (hash) {
      // Shown, but not read aloud: 64 characters of hex help nobody by ear
      box.appendChild(SITE.el('p', { className: 'result__hash', text: hash, translate: 'no', 'aria-hidden': 'true' }));
    }
    resultBox.appendChild(box);
  }

  function toHex(buffer) {
    var bytes = new Uint8Array(buffer);
    var out = '';
    for (var i = 0; i < bytes.length; i++) out += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
    return out;
  }

  // Light up each matching group of eight on the download panels, left to right
  function highlight(appId, sha) {
    var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    Array.prototype.forEach.call(document.querySelectorAll('[data-hash="' + appId + '"] .hash'), function (grid) {
      Array.prototype.forEach.call(grid.querySelectorAll('span'), function (span, i) {
        var chunk = sha.slice(i * 8, i * 8 + 8);
        setTimeout(function () {
          span.classList.toggle('is-match', span.textContent === chunk);
        }, reduce ? 0 : i * 70);
      });
    });
  }

  function clearHighlights() {
    Array.prototype.forEach.call(document.querySelectorAll('.hash span.is-match'), function (span) {
      span.classList.remove('is-match');
    });
  }

  function check(file) {
    if (!file) return;
    var run = ++current;
    clearHighlights();
    var name = file.name;

    if (!window.crypto || !window.crypto.subtle) {
      show('bad', 'warning', 'This browser can’t compute the checksum here.',
        'Web Crypto needs a secure (https) page. Use one of the commands on the download page instead.');
      return;
    }
    var appId = appFor(file);
    if (!appId) {
      show('bad', 'warning', name + ' isn’t a VaultPass download.', 'Pick the .apk file or the Windows installer (.exe).');
      return;
    }
    if (file.size > MAX_BYTES) {
      show('bad', 'warning', name + ' is larger than any VaultPass download.', 'Pick the file you downloaded from this page.');
      return;
    }

    show('wait', 'file-magnifying-glass', 'Computing the SHA-256 of ' + name + '…', 'This happens on your device. The file isn’t uploaded.');

    file.arrayBuffer().then(function (buffer) {
      return window.crypto.subtle.digest('SHA-256', buffer);
    }).then(function (digest) {
      if (run !== current) return; // a newer file was picked meanwhile
      var sha = toHex(digest);
      var app = GH.apps[appId];
      var entry = known[appId];
      var rel = entry && entry.release;
      var expected = rel && rel.asset && rel.asset.sha256;

      if (!expected) {
        show('wait', 'info', 'SHA-256 of ' + name + ' computed.',
          'The checksum of the latest ' + app.platform + ' release hasn’t loaded from GitHub, so there is nothing to compare it with. Compare it with the release page instead.', sha);
        return;
      }
      if (sha === expected) {
        show('ok', 'check-circle', name + ' is ' + app.name + ' ' + rel.tag + '.',
          'Its SHA-256 matches the checksum GitHub publishes for ' + rel.asset.name + (entry.stale ? ' (from a copy of the release details saved earlier).' : '.'), sha);
        highlight(appId, sha);
        return;
      }
      show('bad', 'x-circle', name + ' doesn’t match the latest ' + app.platform + ' release.',
        entry.stale
          ? 'The release details are a saved copy and a newer release may exist, so check again when GitHub is reachable. Until then, don’t install it.'
          : 'It may be an older version, an incomplete download, or a file from somewhere else. Don’t install it; download it again from this page.', sha);
    }).catch(function () {
      if (run !== current) return;
      show('bad', 'warning', name + ' couldn’t be read.', 'Try choosing it again.');
    });
  }

  input.addEventListener('change', function () {
    check(input.files && input.files[0]);
    input.value = '';
  });

  ['dragenter', 'dragover'].forEach(function (type) {
    zone.addEventListener(type, function (e) {
      e.preventDefault();
      zone.classList.add('is-over');
    });
  });
  ['dragleave', 'dragend'].forEach(function (type) {
    zone.addEventListener(type, function (e) {
      if (e.type === 'dragleave' && zone.contains(e.relatedTarget)) return;
      zone.classList.remove('is-over');
    });
  });
  zone.addEventListener('drop', function (e) {
    e.preventDefault();
    zone.classList.remove('is-over');
    var files = e.dataTransfer && e.dataTransfer.files;
    check(files && files[0]);
  });
})();
