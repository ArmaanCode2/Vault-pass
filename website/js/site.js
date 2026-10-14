// Shared page behaviour: navigation, live release info from GitHub,
// VirusTotal status, version-dependent text, copy buttons and the service worker.
(function () {
  'use strict';

  var GH = window.VPGitHub;
  var APP_IDS = ['android', 'windows'];
  var STALE_MS = 24 * 60 * 60 * 1000; // older saved copies stop linking straight to a file

  // ---------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------
  function $all(selector, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(selector));
  }

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (key) {
      if (key === 'text') node.textContent = attrs[key];
      else if (key === 'className') node.className = attrs[key];
      else node.setAttribute(key, attrs[key]);
    });
    (children || []).forEach(function (child) {
      if (child) node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
    });
    return node;
  }

  function icon(name, extraClass) {
    var ns = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('class', 'icon' + (extraClass ? ' ' + extraClass : ''));
    svg.setAttribute('aria-hidden', 'true');
    var use = document.createElementNS(ns, 'use');
    use.setAttribute('href', 'assets/icons.svg#' + name);
    svg.appendChild(use);
    return svg;
  }

  function externalLink(href, text) {
    return el('a', { href: href, target: '_blank', rel: 'noopener noreferrer' }, [
      text,
      el('span', { className: 'sr-only', text: ' (opens in a new tab)' })
    ]);
  }

  var liveRegion;
  function announce(message) {
    if (!liveRegion) {
      liveRegion = el('div', { className: 'sr-only', role: 'status', 'aria-live': 'polite' });
      document.body.appendChild(liveRegion);
    }
    liveRegion.textContent = '';
    setTimeout(function () { liveRegion.textContent = message; }, 40);
  }

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).then(function () { return true; }, fallback);
    }
    return Promise.resolve(fallback());

    function fallback() {
      try {
        var area = el('textarea', { readonly: '', 'aria-hidden': 'true' });
        area.value = text;
        area.style.position = 'absolute';
        area.style.left = '-9999px';
        document.body.appendChild(area);
        area.select();
        var ok = document.execCommand('copy');
        document.body.removeChild(area);
        return ok;
      } catch (e) {
        return false;
      }
    }
  }

  // Set text, falling back to the text the element had in the HTML
  function setText(selector, value) {
    $all(selector).forEach(function (node) {
      if (!node.hasAttribute('data-default')) node.setAttribute('data-default', node.textContent);
      node.textContent = value || node.getAttribute('data-default');
    });
  }

  // ---------------------------------------------------------------
  // Header: border once the page scrolls, mobile menu
  // ---------------------------------------------------------------
  function initHeader() {
    var head = document.querySelector('.site-head');
    if (head && 'IntersectionObserver' in window) {
      var sentinel = el('div', { 'aria-hidden': 'true' });
      sentinel.style.cssText = 'position:absolute;top:0;left:0;width:1px;height:8px;';
      document.body.insertBefore(sentinel, document.body.firstChild);
      new IntersectionObserver(function (entries) {
        head.classList.toggle('is-scrolled', !entries[0].isIntersecting);
      }).observe(sentinel);
    }

    var toggle = document.querySelector('.nav-toggle');
    var menu = document.getElementById('mobile-nav');
    if (!toggle || !menu) return;

    function setOpen(open, returnFocus) {
      menu.hidden = !open;
      toggle.setAttribute('aria-expanded', String(open));
      toggle.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
      var use = toggle.querySelector('use');
      if (use) use.setAttribute('href', 'assets/icons.svg#' + (open ? 'x' : 'list'));
      if (!open && returnFocus) toggle.focus();
    }

    toggle.addEventListener('click', function () {
      setOpen(menu.hidden);
    });
    menu.addEventListener('click', function (e) {
      if (e.target.closest('a')) setOpen(false);
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !menu.hidden) setOpen(false, true);
    });
    if (window.matchMedia) {
      var wide = window.matchMedia('(min-width: 861px)');
      var onChange = function () { if (wide.matches && !menu.hidden) setOpen(false); };
      if (wide.addEventListener) wide.addEventListener('change', onChange);
      else if (wide.addListener) wide.addListener(onChange);
    }
  }

  // ---------------------------------------------------------------
  // Offer the visitor's own platform first
  // ---------------------------------------------------------------
  function initPlatform() {
    var platform = document.documentElement.getAttribute('data-platform');
    $all('[data-platform-actions]').forEach(function (group) {
      var android = group.querySelector('[data-dl="android"]');
      var windows = group.querySelector('[data-dl="windows"]');
      if (!android || !windows) return;
      if (platform === 'android') {
        group.insertBefore(android, windows);
        android.classList.add('btn--gold');
        android.classList.remove('btn--line');
        windows.classList.add('btn--line');
        windows.classList.remove('btn--gold');
      }
    });
    $all('[data-platform-note]').forEach(function (note) {
      var show = note.getAttribute('data-platform-note').split(/\s+/).indexOf(platform) !== -1;
      note.hidden = !show;
    });
  }

  // ---------------------------------------------------------------
  // Release info
  // ---------------------------------------------------------------
  var releases = {};   // appId -> release (validated)
  var outcome = {};    // appId -> 'ok' | 'stale' | 'failed'
  var readmes = {};    // appId -> readme data, null on failure, undefined while loading

  function renderHash(container, appId, reason) {
    var release = releases[appId];
    container.textContent = '';
    var sha = release && release.asset && release.asset.sha256;
    if (sha && reason !== 'failed') {
      var grid = el('p', { className: 'hash', translate: 'no' });
      sha.match(/.{8}/g).forEach(function (chunk, i) {
        grid.appendChild(el('span', { 'data-i': String(i), text: chunk }));
      });
      container.appendChild(grid);
      return;
    }
    var page = release ? release.page : GH.releasesPage(appId);
    var text = reason === 'failed'
      ? 'Couldn’t load the checksum from GitHub. '
      : release && release.assetState === 'ambiguous'
        ? 'This release has more than one file to choose from. '
        : release && !release.asset
          ? 'This release has no installable file yet. '
          : 'GitHub hasn’t published a checksum for this file. ';
    container.appendChild(el('p', { className: 'hash-missing' }, [text, externalLink(page, 'Open the release page')]));
  }

  function renderVirusTotal(appId) {
    var release = releases[appId];
    var readme = readmes[appId];
    var sha = release && release.asset && release.asset.sha256;
    var lookup = sha ? 'https://www.virustotal.com/gui/file/' + sha : '';
    var found = (readme && readme.virustotal) || [];
    var exact = sha ? found.filter(function (l) { return l.sha256 === sha; })[0] : null;

    $all('[data-vt="' + appId + '"]').forEach(function (box) {
      box.textContent = '';
      var state;
      var message;
      var links = [];

      if (readme === undefined) {
        state = 'none';
        message = 'Checking the README for a VirusTotal report.';
      } else if (exact) {
        state = 'match';
        message = 'The README links a VirusTotal report for this exact file.';
        links.push(externalLink(exact.url, 'Open the VirusTotal report'));
      } else if (found.length) {
        var other = found[0];
        state = 'other';
        message = other.sha256 && sha
          ? 'The README links a VirusTotal report for ' + (other.version ? 'v' + other.version : 'another build') + ', not for this file.'
          : 'The README links a VirusTotal report' + (other.version ? ' for v' + other.version : '') + '.';
        links.push(externalLink(other.url, 'Open the README’s report'));
        if (lookup && other.sha256 !== sha) links.push(externalLink(lookup, 'Look up this file’s hash'));
      } else {
        state = 'none';
        message = readme === null
          ? 'Couldn’t read the README on GitHub.'
          : 'The README doesn’t link a VirusTotal report yet.';
        if (lookup) links.push(externalLink(lookup, 'Look up this file’s hash on VirusTotal'));
      }

      box.setAttribute('data-state', state);
      box.appendChild(icon(state === 'match' ? 'check-circle' : 'info'));
      box.appendChild(el('p', { text: message }));
      if (links.length) box.appendChild(el('p', { className: 'vt__links' }, links));
      // Still waiting for the README: keep the links' line, so it doesn't jump
      else if (readme === undefined) box.appendChild(el('p', { className: 'vt__links', 'aria-hidden': 'true', text: '\u00a0' }));
    });

    // Plain links (footer): only a report for this exact file, else a hash lookup
    $all('[data-vt-link="' + appId + '"]').forEach(function (a) {
      var target = exact ? exact.url : lookup;
      if (target) {
        a.href = target;
        a.hidden = false;
      }
    });
  }

  // Elements whose text depends on which versions are out:
  //   data-when-released="android:2.7.0 windows:1.1.0"   shown once all are out
  //   data-until-released="android:2.7.0 windows:1.1.0"  shown until all are out
  //   data-min-version="android:2.7.0"   a safety notice: shown when the version
  //                                      is out, and also when it can't be checked
  function released(spec) {
    return spec.split(/\s+/).filter(Boolean).every(function (pair) {
      var parts = pair.split(':');
      var rel = releases[parts[0]];
      return rel && GH.compareVersions(rel.version, parts[1]) >= 0;
    });
  }

  function applyVersionGates() {
    $all('[data-when-released]').forEach(function (node) {
      node.hidden = !released(node.getAttribute('data-when-released'));
    });
    $all('[data-until-released]').forEach(function (node) {
      node.hidden = released(node.getAttribute('data-until-released'));
    });
    $all('[data-min-version]').forEach(function (node) {
      var parts = node.getAttribute('data-min-version').split(':');
      var rel = releases[parts[0]];
      if (outcome[parts[0]] === 'failed' || !rel) {
        node.hidden = outcome[parts[0]] !== 'failed';
        return;
      }
      var cmp = GH.compareVersions(rel.version, parts[1]);
      node.hidden = !(isNaN(cmp) || cmp >= 0);
    });
  }

  function setStatus(appId, text) {
    $all('[data-rel-state="' + appId + '"]').forEach(function (node) {
      node.textContent = text;
      node.hidden = !text;
    });
  }

  function applyRelease(appId, release, info) {
    releases[appId] = release;
    var stale = info.fromCache && (info.failed || info.ageMs > STALE_MS) && info.ageMs > 0;
    outcome[appId] = info.failed && info.fromCache ? 'stale' : 'ok';
    var asset = release.asset;
    var size = asset ? GH.formatSize(asset.size) : '';
    var oldCopy = info.fromCache && info.ageMs > STALE_MS;

    // An old saved copy might name a file that has since been replaced
    $all('a[data-dl="' + appId + '"]').forEach(function (a) {
      a.href = asset && !oldCopy ? asset.url : GH.repoUrl(appId) + '/releases/latest';
    });
    $all('a[data-rel-page="' + appId + '"]').forEach(function (a) { a.href = release.page; });

    setText('[data-rel="' + appId + ':tag"]', release.tag);
    setText('[data-rel="' + appId + ':size"]', size);
    setText('[data-rel="' + appId + ':file"]', asset ? asset.name : '');
    setText('[data-rel="' + appId + ':meta"]', [release.tag, size].filter(Boolean).join(' · '));

    $all('time[data-rel="' + appId + ':date"]').forEach(function (t) {
      var text = GH.formatDate(release.published);
      if (!text) return;
      t.dateTime = release.published;
      t.textContent = text;
    });

    $all('[data-hash="' + appId + '"]').forEach(function (box) { renderHash(box, appId); });
    $all('[data-copy-hash="' + appId + '"]').forEach(function (btn) {
      btn.hidden = !(asset && asset.sha256);
      btn.setAttribute('data-copy', asset && asset.sha256 ? asset.sha256 : '');
    });

    $all('[data-cmd-app="' + appId + '"]').forEach(function (node) {
      var name = asset ? asset.name : node.getAttribute('data-cmd-default');
      var cmd = node.getAttribute('data-cmd-template').replace('{file}', name);
      if (node.tagName === 'BUTTON') node.setAttribute('data-copy', cmd);
      else node.textContent = cmd;
    });

    setStatus(appId, info.failed && info.fromCache
      ? 'GitHub didn’t answer, so these details are a copy saved ' + GH.formatAge(info.ageMs) + '. There may be a newer release.'
      : '');

    applyVersionGates();
    renderVirusTotal(appId);
    document.dispatchEvent(new CustomEvent('vp:release', {
      detail: { app: appId, release: release, stale: !!stale }
    }));

    // Release notes last: if they fail to render, nothing above is lost
    prepareNotes(appId, release.notes && release.notes.trim() ? release.notes : '');
  }

  // Release notes stay in place from the first paint (so nothing moves) and
  // the markdown is only rendered when a reader opens them.
  var notesFor = {};   // appId -> markdown, '' for none, null if GitHub failed

  function prepareNotes(appId, notes) {
    notesFor[appId] = notes;
    $all('[data-notes="' + appId + '"]').forEach(function (details) {
      details.removeAttribute('data-rendered');
      if (!details.hasAttribute('data-notes-ready')) {
        details.setAttribute('data-notes-ready', '');
        details.addEventListener('toggle', function () {
          if (details.open) renderNotes(appId, details);
        });
      }
      if (details.open) renderNotes(appId, details);
    });
  }

  function renderNotes(appId, details) {
    var body = details.querySelector('.notes__body');
    var notes = notesFor[appId];
    if (!body || notes === undefined || details.hasAttribute('data-rendered')) return;
    details.setAttribute('data-rendered', '');
    body.textContent = '';
    if (notes === '') {
      body.appendChild(el('p', { text: 'This release has no notes on GitHub.' }));
      return;
    }
    try {
      if (notes === null || !window.VPMarkdown) throw new Error('no notes to render');
      body.appendChild(window.VPMarkdown.render(notes, {
        baseUrl: GH.blobUrl(appId, ''),
        idPrefix: 'notes-' + appId + '-',
        headingOffset: parseInt(details.getAttribute('data-heading-offset') || '2', 10)
      }));
    } catch (e) {
      if (notes !== null && window.console) console.warn('VaultPass: release notes could not be shown.', e);
      body.textContent = '';
      body.appendChild(el('p', {}, [externalLink(GH.releasesPage(appId), 'Read the release notes on GitHub')]));
    }
  }

  function releaseFailed(appId) {
    outcome[appId] = 'failed';
    setStatus(appId, 'GitHub didn’t answer, so the version and checksum are missing. The download button opens the latest release page instead.');
    $all('[data-hash="' + appId + '"]').forEach(function (box) { renderHash(box, appId, 'failed'); });
    prepareNotes(appId, null);
    applyVersionGates();
    renderVirusTotal(appId);
    document.dispatchEvent(new CustomEvent('vp:release', { detail: { app: appId, release: null } }));
  }

  function initReleases() {
    if (!GH) return;
    APP_IDS.forEach(function (appId) {
      var wanted = document.querySelector(
        '[data-dl="' + appId + '"],[data-rel^="' + appId + ':"],[data-hash="' + appId + '"],[data-vt="' + appId + '"],' +
        '[data-vt-link="' + appId + '"],[data-notes="' + appId + '"],[data-min-version^="' + appId + ':"],[data-cmd-app="' + appId + '"],' +
        '[data-when-released*="' + appId + ':"],[data-until-released*="' + appId + ':"]'
      );
      if (!wanted) return;

      GH.onRelease(appId, function (entry) {
        if (entry.data) applyRelease(appId, entry.data, entry.info || {});
        else releaseFailed(appId);
      });

      if (document.querySelector('[data-vt="' + appId + '"],[data-vt-link="' + appId + '"]')) {
        GH.onReadme(appId, function (entry) {
          readmes[appId] = entry.data || null;
          renderVirusTotal(appId);
        });
      }
    });
  }

  // ---------------------------------------------------------------
  // Copy buttons: <button data-copy="text"> with a [data-copy-label] inside
  // ---------------------------------------------------------------
  function initCopy() {
    document.addEventListener('click', function (e) {
      var btn = e.target.closest('[data-copy]');
      if (!btn) return;
      var text = btn.getAttribute('data-copy');
      if (!text) return;
      copyText(text).then(function (ok) {
        var label = btn.querySelector('[data-copy-label]');
        if (ok) {
          btn.setAttribute('data-state', 'done');
          if (label) {
            if (!label.hasAttribute('data-original')) label.setAttribute('data-original', label.textContent);
            label.textContent = 'Copied';
          }
          announce('Copied to the clipboard');
          clearTimeout(btn._copyTimer);
          btn._copyTimer = setTimeout(function () {
            btn.removeAttribute('data-state');
            if (label) label.textContent = label.getAttribute('data-original');
          }, 2000);
        } else {
          announce('Copy failed. Select the text and copy it yourself.');
        }
      });
    });
  }

  // ---------------------------------------------------------------
  // Service worker (offline copy of the pages)
  // ---------------------------------------------------------------
  function initServiceWorker() {
    var local = location.hostname === 'localhost' || location.hostname === '127.0.0.1';
    if (!('serviceWorker' in navigator) || (location.protocol !== 'https:' && !local)) return;
    // The offline copy downloads the rest of the site's shell (about 28-39 KB
    // more than a page loads): skip it on data saver or 2G
    var connection = navigator.connection;
    if (connection && (connection.saveData || /2g$/.test(connection.effectiveType || ''))) return;
    window.addEventListener('load', function () {
      // ... and start it only once the page has settled
      var idle = window.requestIdleCallback || function (fn) { setTimeout(fn, 2000); };
      idle(function () {
        navigator.serviceWorker.register('sw.js').catch(function () { /* optional */ });
      }, { timeout: 5000 });
    });
  }

  window.VPSite = {
    announce: announce,
    icon: icon,
    el: el,
    externalLink: externalLink,
    releases: releases,
    outcome: outcome
  };

  function start() {
    initHeader();
    initPlatform();
    initReleases();
    initCopy();
    initServiceWorker();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
