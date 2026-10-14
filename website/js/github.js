// Everything on this site that changes between releases comes from GitHub,
// read in the visitor's browser. There is no server of our own:
//   - latest release of each app: api.github.com/repos/<repo>/releases/latest
//     (version, date, file, size, SHA-256 digest, release notes)
//   - VirusTotal report links: the README.md of each repository
//   - privacy policies: PRIVACY.md of each repository (see privacy.js)
// Edit those on GitHub and the site follows; nothing here needs a redeploy.
//
// Unauthenticated GitHub API calls are limited to 60 per hour per IP address,
// and every call counts, so a copy is kept in localStorage and reused for
// FRESH_MS before GitHub is asked again. The same copy lets a page render
// instantly and keeps it working when GitHub is rate-limited or unreachable.
// Cached data is validated exactly like fresh data before it is used.
(function () {
  'use strict';

  var APPS = {
    android: {
      id: 'android',
      repo: 'ArmaanCode2/Vault-pass',
      name: 'VaultPass for Android',
      platform: 'Android',
      extensions: /\.apk$/i
    },
    windows: {
      id: 'windows',
      repo: 'ArmaanCode2/vault-pass-desktop',
      name: 'VaultPass for Windows',
      platform: 'Windows',
      extensions: /\.(exe|msi)$/i
    }
  };

  var CACHE_PREFIX = 'vp:gh:v2:';
  var FRESH_MS = 10 * 60 * 1000;
  var TIMEOUT_MS = 8000;
  var MAX_TEXT = 200000; // longest README or PRIVACY.md we accept, in characters

  function repoUrl(app) {
    return 'https://github.com/' + app.repo;
  }

  function rawUrl(app, path) {
    // HEAD follows the repository's default branch, whatever it is called
    return 'https://raw.githubusercontent.com/' + app.repo + '/HEAD/' + path;
  }

  // ---------------------------------------------------------------
  // Validation, applied to network and cached data alike
  // ---------------------------------------------------------------
  function isSha256(value) {
    return typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
  }

  // A github.com URL whose normalised path starts with the given prefix
  function githubUrl(value, pathPrefix) {
    if (typeof value !== 'string') return '';
    try {
      var url = new URL(value);
      if (url.protocol !== 'https:' || url.host !== 'github.com' || url.username || url.password) return '';
      if (url.pathname.indexOf(pathPrefix) !== 0) return '';
      return url.href;
    } catch (e) {
      return '';
    }
  }

  function cleanRelease(app, r) {
    if (!r || typeof r !== 'object') return null;
    var releasesPath = '/' + app.repo + '/releases/';
    var tag = typeof r.tag === 'string' && /^[\w.+-]{1,40}$/.test(r.tag) ? r.tag : '';
    var asset = null;
    if (r.asset && typeof r.asset === 'object') {
      var url = githubUrl(r.asset.url, releasesPath + 'download/');
      var name = typeof r.asset.name === 'string' && /^[\w.+-]{1,120}$/.test(r.asset.name) ? r.asset.name : '';
      if (url && name) {
        asset = {
          name: name,
          url: url,
          size: typeof r.asset.size === 'number' && r.asset.size >= 0 ? r.asset.size : 0,
          sha256: isSha256(r.asset.sha256) ? r.asset.sha256 : ''
        };
      }
    }
    return {
      app: app.id,
      tag: tag,
      version: tag.replace(/^[^\d]*/, ''),
      published: typeof r.published === 'string' && !isNaN(Date.parse(r.published)) ? r.published : '',
      page: githubUrl(r.page, releasesPath) || repoUrl(app) + '/releases/latest',
      notes: typeof r.notes === 'string' ? r.notes.slice(0, 20000) : '',
      assetState: r.assetState === 'none' || r.assetState === 'ambiguous' ? r.assetState : (asset ? 'ok' : 'none'),
      asset: asset
    };
  }

  function cleanReadme(r) {
    if (!r || !Array.isArray(r.virustotal)) return { virustotal: [] };
    return {
      virustotal: r.virustotal.slice(0, 10).map(function (link) {
        if (!link || typeof link.url !== 'string') return null;
        try {
          var url = new URL(link.url);
          if (url.protocol !== 'https:' || !/^(www\.)?virustotal\.com$/.test(url.host)) return null;
          return {
            url: url.href,
            sha256: isSha256(link.sha256) ? link.sha256 : '',
            version: typeof link.version === 'string' && /^\d+(\.\d+){1,3}$/.test(link.version) ? link.version : ''
          };
        } catch (e) {
          return null;
        }
      }).filter(Boolean)
    };
  }

  function cleanText(t) {
    return typeof t === 'string' ? t.slice(0, MAX_TEXT) : null;
  }

  // ---------------------------------------------------------------
  // Cache
  // ---------------------------------------------------------------
  function readCache(key, clean) {
    try {
      var raw = localStorage.getItem(CACHE_PREFIX + key);
      if (!raw) return null;
      var item = JSON.parse(raw);
      if (!item || typeof item.t !== 'number') return null;
      // A timestamp from the future would look fresh forever
      if (item.t > Date.now() + 60000) return null;
      var data = clean(item.data);
      return data === null ? null : { t: item.t, data: data };
    } catch (e) {
      return null;
    }
  }

  function writeCache(key, data) {
    try {
      localStorage.setItem(CACHE_PREFIX + key, JSON.stringify({ t: Date.now(), data: data }));
    } catch (e) {
      // Private mode or storage full: the page still works without it
    }
  }

  function fetchWithTimeout(url, options) {
    if (typeof AbortController === 'undefined') return fetch(url, options);
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, TIMEOUT_MS);
    var opts = Object.assign({}, options, { signal: controller.signal });
    return fetch(url, opts).finally(function () { clearTimeout(timer); });
  }

  // Calls onData(data, info) with the cached copy first (if any), and again
  // after asking GitHub. info:
  //   { fromCache: true, ageMs, refreshing }  cached copy, GitHub may be asked next
  //   { fromCache: false, changed }           GitHub answered
  //   { fromCache: true, ageMs, failed: true } GitHub failed; data is the cached copy
  //   data null and { failed: true }          GitHub failed and nothing is cached
  function load(key, fetcher, clean, onData) {
    var cached = readCache(key, clean);
    var age = cached ? Math.max(0, Date.now() - cached.t) : 0;
    var fresh = cached && age < FRESH_MS;
    if (cached) onData(cached.data, { fromCache: true, ageMs: age, refreshing: !fresh });
    if (fresh) return;

    fetcher().then(function (data) {
      data = clean(data);
      if (data === null) throw new Error('Unexpected data from GitHub');
      writeCache(key, data);
      onData(data, { fromCache: false, changed: !cached || JSON.stringify(cached.data) !== JSON.stringify(data) });
    }).catch(function (err) {
      if (cached) onData(cached.data, { fromCache: true, ageMs: age, failed: true });
      else onData(null, { failed: true, error: err });
      if (window.console) console.warn('VaultPass: could not load ' + key + ' from GitHub.', err);
    });
  }

  // ---------------------------------------------------------------
  // Releases
  // ---------------------------------------------------------------
  // The file a visitor should get. Windows: the name the app's own updater
  // looks for (VaultPass-Setup-<tag>.exe), else the only .exe, else the only
  // .msi. Android: VaultPass.apk, else the only .apk, else a "universal" one.
  function pickAsset(app, tag, assets) {
    if (!Array.isArray(assets)) return { state: 'none', asset: null };
    var files = assets.filter(function (a) {
      return a && typeof a.name === 'string' && typeof a.browser_download_url === 'string' && app.extensions.test(a.name);
    });
    if (!files.length) return { state: 'none', asset: null };
    function named(test) { return files.filter(function (a) { return test(a.name); }); }
    var candidates = app.id === 'windows'
      ? [named(function (n) { return n === 'VaultPass-Setup-' + tag + '.exe'; }), named(function (n) { return /\.exe$/i.test(n); }), named(function (n) { return /\.msi$/i.test(n); })]
      : [named(function (n) { return n === 'VaultPass.apk'; }), files, named(function (n) { return /universal/i.test(n); })];
    for (var i = 0; i < candidates.length; i++) {
      if (candidates[i].length === 1) return { state: 'ok', asset: candidates[i][0] };
    }
    return { state: 'ambiguous', asset: null };
  }

  function fetchRelease(app) {
    return fetchWithTimeout('https://api.github.com/repos/' + app.repo + '/releases/latest', {
      headers: { Accept: 'application/vnd.github+json' }
    }).then(function (res) {
      if (!res.ok) {
        var limited = res.status === 403 || res.status === 429;
        throw new Error(limited ? 'GitHub rate limit reached' : 'GitHub API ' + res.status);
      }
      return res.json();
    }).then(function (data) {
      var tag = typeof data.tag_name === 'string' ? data.tag_name : '';
      var picked = pickAsset(app, tag, data.assets);
      var a = picked.asset;
      var digest = a && typeof a.digest === 'string' && a.digest.indexOf('sha256:') === 0
        ? a.digest.slice(7).toLowerCase()
        : '';
      return {
        tag: tag,
        published: data.published_at,
        page: data.html_url,
        notes: data.body,
        assetState: picked.state,
        asset: a ? { name: a.name, size: a.size, url: a.browser_download_url, sha256: digest } : null
      };
    });
  }

  // ---------------------------------------------------------------
  // README: VirusTotal report links
  // ---------------------------------------------------------------
  var VT_LINK = /https?:\/\/(?:www\.)?virustotal\.com\/(?:gui\/|#\/)?(file|file-analysis|url)\/([A-Za-z0-9_=-]+)[^\s)"'<>\]]*/gi;

  function parseVirusTotal(markdown) {
    var found = [];
    var seen = {};
    var text = String(markdown || '').slice(0, MAX_TEXT).replace(/<!--[\s\S]*?-->/g, '');
    text.split(/\r?\n/).forEach(function (line) {
      VT_LINK.lastIndex = 0;
      var m;
      while ((m = VT_LINK.exec(line))) {
        var url = m[0].replace(/[.,;:!?]+$/, '').replace(/^http:/i, 'https:');
        if (seen[url]) continue;
        seen[url] = true;
        var id = m[2].toLowerCase();
        // The version this report is for: a v-prefixed number on the line
        // before the link, else any three-part version number on the line
        var before = line.slice(0, m.index);
        var vs = before.match(/\bv(\d+\.\d+(?:\.\d+)?)\b/gi);
        var three = line.replace(m[0], '').match(/\b(\d+\.\d+\.\d+)\b/);
        var version = vs ? vs[vs.length - 1].slice(1) : (three ? three[1] : '');
        found.push({
          url: url,
          sha256: m[1].toLowerCase() === 'file' && isSha256(id) ? id : '',
          version: version
        });
      }
    });
    return { virustotal: found };
  }

  function fetchReadmeLinks(app) {
    return fetchText(app, 'README.md').then(parseVirusTotal);
  }

  function fetchText(app, path) {
    return fetchWithTimeout(rawUrl(app, path), { cache: 'no-cache' }).then(function (res) {
      if (!res.ok) throw new Error(path + ' ' + res.status);
      return res.text();
    });
  }

  // ---------------------------------------------------------------
  // Shared state, so several parts of a page can listen to one request
  // ---------------------------------------------------------------
  var state = {};
  var listeners = {};
  var started = {};

  function watch(key, starter, cb) {
    (listeners[key] = listeners[key] || []).push(cb);
    if (key in state) cb(state[key]);
    if (started[key]) return;
    started[key] = true;
    starter(function (data, info) {
      state[key] = { data: data, info: info || {} };
      (listeners[key] || []).forEach(function (fn) {
        try { fn(state[key]); } catch (e) { if (window.console) console.error(e); }
      });
    });
  }

  function onRelease(appId, cb) {
    var app = APPS[appId];
    watch('release:' + appId, function (set) {
      load('release:' + appId, function () { return fetchRelease(app); }, function (r) { return cleanRelease(app, r); }, set);
    }, cb);
  }

  function onReadme(appId, cb) {
    var app = APPS[appId];
    watch('readme:' + appId, function (set) {
      load('readme:' + appId, function () { return fetchReadmeLinks(app); }, cleanReadme, set);
    }, cb);
  }

  function onDocument(appId, path, cb) {
    var app = APPS[appId];
    var key = 'doc:' + appId + ':' + path;
    watch(key, function (set) {
      load(key, function () { return fetchText(app, path); }, cleanText, set);
    }, cb);
  }

  // ---------------------------------------------------------------
  // Helpers shared by the pages
  // ---------------------------------------------------------------
  function formatSize(bytes) {
    if (!bytes) return '';
    var mb = bytes / (1024 * 1024);
    return (mb >= 100 ? Math.round(mb) : mb.toFixed(1)) + ' MB';
  }

  // The site is in English. A fixed month list avoids the first
  // toLocaleDateString call, which loads locale data (45-60 ms on a slow phone).
  var MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July',
    'August', 'September', 'October', 'November', 'December'];

  function formatDate(iso) {
    if (!iso) return '';
    var d = new Date(iso);
    if (isNaN(d.getTime())) return '';
    return MONTHS[d.getMonth()] + ' ' + d.getDate() + ', ' + d.getFullYear();
  }

  function formatAge(ms) {
    var minutes = Math.round(ms / 60000);
    if (minutes < 1) return 'just now';
    if (minutes < 60) return minutes + (minutes === 1 ? ' minute ago' : ' minutes ago');
    var hours = Math.round(minutes / 60);
    if (hours < 48) return hours + (hours === 1 ? ' hour ago' : ' hours ago');
    return Math.round(hours / 24) + ' days ago';
  }

  // Compare dotted versions ("v2.7.0", "2.10.1-beta"). Returns <0, 0 or >0,
  // or NaN when either side has no version number at all.
  function compareVersions(a, b) {
    var ma = /(\d+(?:\.\d+)*)/.exec(String(a || ''));
    var mb = /(\d+(?:\.\d+)*)/.exec(String(b || ''));
    if (!ma || !mb) return NaN;
    var pa = ma[1].split('.');
    var pb = mb[1].split('.');
    for (var i = 0; i < Math.max(pa.length, pb.length); i++) {
      var x = parseInt(pa[i] || '0', 10);
      var y = parseInt(pb[i] || '0', 10);
      if (x !== y) return x - y;
    }
    return 0;
  }

  function releasesPage(appId) {
    return repoUrl(APPS[appId]) + '/releases';
  }

  function blobUrl(appId, path) {
    return repoUrl(APPS[appId]) + '/blob/HEAD/' + path;
  }

  window.VPGitHub = {
    apps: APPS,
    onRelease: onRelease,
    onReadme: onReadme,
    onDocument: onDocument,
    formatSize: formatSize,
    formatDate: formatDate,
    formatAge: formatAge,
    compareVersions: compareVersions,
    releasesPage: releasesPage,
    repoUrl: function (appId) { return repoUrl(APPS[appId]); },
    blobUrl: blobUrl,
    isSha256: isSha256,
    parseVirusTotal: parseVirusTotal
  };
})();
