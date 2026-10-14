// The "desk" on the home page: the Windows and Android demos side by side,
// joined by a sync link that merges the two sample vaults the way the real
// apps do over Wi-Fi. CSS reserves the desk's space from the first paint;
// this file is loaded right after the desk markup (not deferred) so it can
// scale the demos and reveal them as early as possible. It also loads each
// demo's script and stylesheet when that demo is needed (see need()).
(function () {
  'use strict';

  var desk = document.querySelector('[data-desk]');
  if (!desk) return;

  var hosts = {
    desktop: desk.querySelector('[data-demo-host="desktop"]'),
    android: desk.querySelector('[data-demo-host="android"]')
  };
  var switchButtons = Array.prototype.slice.call(desk.querySelectorAll('[data-device-tab]'));
  var syncButtons = Array.prototype.slice.call(desk.querySelectorAll('[data-sync]'));
  var statusNodes = Array.prototype.slice.call(desk.querySelectorAll('[data-sync-status]'));

  // Design widths in px; site.css holds the same numbers with the heights.
  // CSS sizes and reserves the space; this file only works out the scale.
  var SIZES = {
    dual: 1452,          // 960 window + 152 link + 340 phone
    desktop: 960,
    android: 340
  };
  var root = document.documentElement;
  // Same rule as boot.js, which set the first view before the page painted
  var deskView = window.VaultPassDeskView || function () {
    if (window.matchMedia('(min-width: 1212px)').matches) return 'dual';
    return window.matchMedia('(max-width: 671px)').matches ? 'android' : 'desktop';
  };
  var DUAL = window.matchMedia('(min-width: 1212px)');
  var INERT = window.matchMedia('(max-width: 631px)'); // the Windows window is a preview only on phones

  var view = root.getAttribute('data-desk-view') || deskView();
  var mode = view === 'dual' ? 'dual' : 'single';
  var active = view === 'dual' ? (root.getAttribute('data-platform') === 'android' ? 'android' : 'desktop') : view;
  var userPicked = false;
  var room = 0;          // the desk's content width, from the ResizeObserver

  // The demos load only when shown: both on wide screens, the one in view
  // on narrower ones, the other when it's picked or a sync needs it. On a
  // data-saving or 2G connection each one waits until it's asked for.
  var api = { desktop: null, android: null };
  var DEMO_FILES = {
    desktop: { css: 'css/demo-desktop.css', js: 'js/demo-desktop.js', kb: 57, name: 'Windows app' },
    android: { css: 'css/demo-mobile.css', js: 'js/demo-mobile.js', kb: 47, name: 'Android app' }
  };
  var waiting = { desktop: null, android: null };   // callbacks while a demo loads
  var retried = { desktop: false, android: false };
  var failed = { desktop: false, android: false };
  var offer = null;      // the "Load the demo" panel on data-saving connections
  // This file runs while the page is still being parsed (it isn't deferred)
  var parsed = false;
  var afterParse = [];
  document.addEventListener('DOMContentLoaded', function () {
    parsed = true;
    afterParse.splice(0).forEach(function (fn) { fn(); });
  });
  var connection = navigator.connection;
  var askFirst = !!(connection && (connection.saveData || /2g$/.test(connection.effectiveType || '')));

  // Applies the current view. Reads no layout, so it never forces one.
  function layout() {
    view = mode === 'dual' ? 'dual' : active;
    root.setAttribute('data-desk-view', view);
    var inert = view === 'desktop' && INERT.matches;

    switchButtons.forEach(function (btn) {
      btn.setAttribute('aria-pressed', String(btn.getAttribute('data-device-tab') === active));
    });
    desk.setAttribute('data-inert-desktop', String(inert));
    if (inert) hosts.desktop.setAttribute('inert', '');
    else hosts.desktop.removeAttribute('inert');
    updateSyncLabels();
    updateOffer();

    if (!room) return;
    desk.style.setProperty('--fit', String(Math.min(1, room / SIZES[view])));
    if (!desk.hasAttribute('data-ready')) desk.setAttribute('data-ready', '');
  }

  function onRoom(width) {
    if (!width) return;
    room = width;
    var nextMode = DUAL.matches ? 'dual' : 'single';
    if (nextMode !== mode) {
      mode = nextMode;
      if (mode === 'single' && !userPicked) active = deskView();
    }
    layout();
    wantDemos();
  }

  switchButtons.forEach(function (btn) {
    var id = btn.getAttribute('data-device-tab');
    // With a mouse or pen, start fetching on press; the click (also from
    // touch, a keyboard or a screen reader) shows it. Touch is left out: a
    // scroll that starts on the switch would fetch a demo nobody picked.
    btn.addEventListener('pointerdown', function (e) {
      if (!askFirst && e.pointerType !== 'touch') need(id);
    });
    btn.addEventListener('click', function () {
      active = id;
      userPicked = true;
      layout();
      wantDemos();
    });
  });

  if (askFirst) offerDemos();
  layout();
  wantDemos();
  // In the Windows view of a tablet or laptop, fetch the phone demo once the
  // page is idle, so "Sync now" inside the Windows app finds it ready
  window.addEventListener('load', function () {
    var idle = window.requestIdleCallback || function (fn) { setTimeout(fn, 2000); };
    idle(function () {
      if (!askFirst && view === 'desktop') need('android');
    }, { timeout: 5000 });
  });
  if ('ResizeObserver' in window) {
    // Called after layout, with the content-box width: no measuring needed
    new ResizeObserver(function (entries) {
      var width = entries[entries.length - 1].contentRect.width;
      if (width !== room) onRoom(width);
    }).observe(desk);
  } else {
    var measure = function () {
      var style = window.getComputedStyle(desk);
      onRoom(desk.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight));
    };
    window.addEventListener('resize', measure);
    measure();
  }

  // ---------------------------------------------------------------
  // Sync between the two demos
  // ---------------------------------------------------------------
  // demo-data.js is deferred, so read it when the demos mount, not now
  function data() { return window.VaultPassDemoData; }
  var names = { desktop: 'the PC', android: 'the phone' };
  var wire = desk.querySelector('.link__wire');
  var busy = false;
  var lastResult = '';

  function reducedMotion() {
    return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  function stamp(entry) {
    var t = Date.parse(entry.updatedAt) || 0;
    var d = entry.deletedAt ? Date.parse(entry.deletedAt) || 0 : 0;
    return Math.max(t, d);
  }

  function same(a, b) {
    return JSON.stringify(a) === JSON.stringify(b);
  }

  function byUid(list) {
    var map = {};
    list.forEach(function (e) { if (e && e.uid) map[e.uid] = e; });
    return map;
  }

  // Newest change wins per entry, as in the apps' merge; deletions travel as
  // recycle-bin records (deletedAt).
  function plan(desktopVault, androidVault) {
    var a = byUid(desktopVault);
    var b = byUid(androidVault);
    var uids = Object.keys(a).concat(Object.keys(b).filter(function (u) { return !(u in a); }));
    var merged = [];
    var toDesktop = { added: 0, updated: 0, removed: 0 };
    var toAndroid = { added: 0, updated: 0, removed: 0 };
    var onlyDesktop = [];
    var onlyAndroid = [];
    var edited = 0;

    function count(side, before, after) {
      if (!before) {
        if (!after.deletedAt) side.added++;
      } else if (!same(before, after)) {
        if (after.deletedAt && !before.deletedAt) side.removed++;
        else if (!after.deletedAt) side.updated++;
      }
    }

    uids.forEach(function (uid) {
      var x = a[uid];
      var y = b[uid];
      var winner;
      if (x && !y) {
        winner = x;
        if (!x.deletedAt) onlyDesktop.push(x.title);
      } else if (y && !x) {
        winner = y;
        if (!y.deletedAt) onlyAndroid.push(y.title);
      } else {
        winner = stamp(y) > stamp(x) ? y : x;
        if (!same(x, y)) edited++;
      }
      merged.push(winner);
      count(toDesktop, x, winner);
      count(toAndroid, y, winner);
    });

    var changes = toDesktop.added + toDesktop.updated + toDesktop.removed +
      toAndroid.added + toAndroid.updated + toAndroid.removed;
    return {
      merged: merged,
      toDesktop: toDesktop,
      toAndroid: toAndroid,
      onlyDesktop: onlyDesktop,
      onlyAndroid: onlyAndroid,
      edited: edited,
      changes: changes
    };
  }

  function listTitles(titles) {
    if (titles.length === 1) return titles[0];
    if (titles.length === 2) return titles[0] + ' and ' + titles[1];
    return titles[0] + ', ' + titles[1] + ' and ' + (titles.length - 2) + ' more';
  }

  function describe(p) {
    var parts = [];
    if (p.onlyDesktop.length) {
      parts.push(listTitles(p.onlyDesktop) + (p.onlyDesktop.length === 1 ? ' is' : ' are') + ' only on the PC.');
    }
    if (p.onlyAndroid.length) {
      parts.push(listTitles(p.onlyAndroid) + (p.onlyAndroid.length === 1 ? ' is' : ' are') + ' only on the phone.');
    }
    if (p.edited) {
      parts.push(p.edited === 1 ? 'One entry differs between them.' : p.edited + ' entries differ between them.');
    }
    return parts.slice(0, 2).join(' ');
  }

  function summarize(side) {
    var bits = [];
    if (side.added) bits.push(side.added + ' added');
    if (side.updated) bits.push(side.updated + ' updated');
    if (side.removed) bits.push(side.removed + ' moved to the recycle bin');
    return bits.join(', ');
  }

  // Only write real changes, so screen readers don't hear the same line again
  function setStatus(text) {
    statusNodes.forEach(function (node) {
      if (node.textContent !== text) node.textContent = text;
    });
  }

  function setButtonsDisabled(disabled, reason) {
    syncButtons.forEach(function (btn) {
      btn.setAttribute('aria-disabled', String(disabled));
      if (reason) btn.setAttribute('title', reason);
      else btn.removeAttribute('title');
    });
  }

  function updateSyncLabels() {
    var label = mode === 'single'
      ? (active === 'desktop' ? 'Sync with Kay’s phone' : 'Sync with Kay’s PC')
      : 'Sync now';
    syncButtons.forEach(function (btn) {
      var text = btn.querySelector('[data-sync-label]');
      if (text) text.textContent = label;
    });
  }

  // A demo that hasn't loaded yet still holds its starting vault
  function vaultOf(id) {
    return api[id] ? api[id].getVault() : data().vaultFor(id);
  }

  function setBusy(on) {
    busy = on;
    syncButtons.forEach(function (b) {
      if (on) b.setAttribute('aria-busy', 'true');
      else b.removeAttribute('aria-busy');
    });
  }

  function refreshStatus() {
    if (busy) return;
    var mounted = ['desktop', 'android'].filter(function (id) { return api[id]; });
    if (!data() || (!mounted.length && !askFirst)) {
      if (failed.desktop || failed.android) {
        setButtonsDisabled(true, 'The demo didn’t load');
        setStatus('The demo didn’t load. Check your connection and reload the page.');
      } else {
        setButtonsDisabled(true, 'The demos are still loading');
      }
      return;
    }
    var locked = mounted.filter(function (id) { return !api[id].isUnlocked(); }).map(function (id) { return names[id]; });
    if (locked.length) {
      setButtonsDisabled(true, 'Unlock both apps first');
      setStatus('Unlock VaultPass on ' + locked.join(' and on ') + ' to sync.');
      return;
    }
    var p = plan(vaultOf('desktop'), vaultOf('android'));
    setButtonsDisabled(false);
    if (!p.changes) setStatus(lastResult || 'Both devices have the same entries.');
    else setStatus(describe(p) || (p.changes + ' changes waiting.'));
  }

  function sendPackets(direction, count) {
    if (!wire || reducedMotion() || !count) return;
    var distance = wire.clientWidth - 10;
    for (var i = 0; i < Math.min(count, 3); i++) {
      var packet = document.createElement('span');
      packet.className = 'link__packet';
      wire.appendChild(packet);
      var from = direction > 0 ? 0 : distance;
      var to = direction > 0 ? distance : 0;
      var anim = packet.animate([
        { transform: 'translateX(' + from + 'px)', opacity: 0 },
        { opacity: 1, offset: 0.12 },
        { opacity: 1, offset: 0.88 },
        { transform: 'translateX(' + to + 'px)', opacity: 0 }
      ], { duration: 640, delay: i * 110, easing: 'cubic-bezier(0.16, 1, 0.3, 1)', fill: 'both' });
      anim.onfinish = (function (node) { return function () { node.remove(); }; })(packet);
    }
  }

  function syncNow(btn) {
    if (busy) return;
    if (btn && btn.getAttribute('aria-disabled') === 'true') {
      refreshStatus();
      return;
    }
    if (!api.desktop || !api.android) {
      // The other device's demo isn't loaded yet: load it, then sync
      setBusy(true);
      setStatus(api.desktop || api.android
        ? 'Connecting to ' + names[api.desktop ? 'android' : 'desktop'] + '…'
        : 'Loading the demos…');
      needBoth(function (ok) {
        setBusy(false);
        if (ok && api.desktop.isUnlocked() && api.android.isUnlocked()) syncNow(null);
        else refreshStatus();
        if (!ok) loadFailedStatus();
      });
      return;
    }
    var p = plan(api.desktop.getVault(), api.android.getVault());
    if (!p.changes) {
      lastResult = 'Already in sync. Add or edit an entry on one device, then sync again.';
      setStatus(lastResult);
      return;
    }

    setBusy(true);
    setStatus('Syncing over the local network…');

    var toPhone = p.toAndroid.added + p.toAndroid.updated + p.toAndroid.removed;
    var toPc = p.toDesktop.added + p.toDesktop.updated + p.toDesktop.removed;
    sendPackets(1, toPhone);
    sendPackets(-1, toPc);

    setTimeout(function () {
      var peerOfDesktop = data().devices.android.name;
      var peerOfAndroid = data().devices.desktop.name;
      api.desktop.applySync({
        vault: JSON.parse(JSON.stringify(p.merged)),
        peerName: peerOfDesktop,
        added: p.toDesktop.added,
        updated: p.toDesktop.updated,
        removed: p.toDesktop.removed
      });
      api.android.applySync({
        vault: JSON.parse(JSON.stringify(p.merged)),
        peerName: peerOfAndroid,
        added: p.toAndroid.added,
        updated: p.toAndroid.updated,
        removed: p.toAndroid.removed
      });

      var parts = [];
      if (toPc) parts.push('the PC: ' + summarize(p.toDesktop));
      if (toPhone) parts.push('the phone: ' + summarize(p.toAndroid));
      lastResult = 'Synced. ' + parts.join('; ').replace(/^t/, 'T') + '.';
      setBusy(false);
      refreshStatus();
    }, reducedMotion() ? 0 : 720);
  }

  syncButtons.forEach(function (btn) {
    btn.addEventListener('click', function () { syncNow(btn); });
  });

  // "Sync Now" inside either app asks the page to run the same merge
  function onAppSyncRequest(e) {
    if (busy) return;
    var missing = !api.desktop ? 'desktop' : !api.android ? 'android' : '';
    if (missing) {
      // The other device's demo isn't loaded yet: take the request, load it,
      // then sync. The Windows app stops waiting after 2.5 s and reports its
      // own result, so a later answer would contradict it.
      e.preventDefault();
      var started = Date.now();
      setBusy(true);
      setStatus('Connecting to ' + names[missing] + '…');
      need(missing, function (ok) {
        setBusy(false);
        if (!ok) {
          refreshStatus();
          loadFailedStatus();
        } else if (missing === 'android' && Date.now() - started > 2000) {
          refreshStatus();
          setStatus('The phone demo is ready now. Press Sync to bring both up to date.');
        } else {
          answerApp();
        }
      });
      return;
    }
    if (!api.desktop.isUnlocked() || !api.android.isUnlocked()) {
      refreshStatus();
      return; // let the app show its own message
    }
    e.preventDefault();
    answerApp();
  }

  function answerApp() {
    if (!api.desktop.isUnlocked() || !api.android.isUnlocked()) {
      refreshStatus();
      return;
    }
    var p = plan(api.desktop.getVault(), api.android.getVault());
    if (p.changes) {
      syncNow(null);
      return;
    }
    // Nothing to do: still answer, so the app's dialog closes with its own message
    var none = { added: 0, updated: 0, removed: 0 };
    api.desktop.applySync(Object.assign({ vault: api.desktop.getVault(), peerName: data().devices.android.name }, none));
    api.android.applySync(Object.assign({ vault: api.android.getVault(), peerName: data().devices.desktop.name }, none));
    lastResult = 'Already in sync. Add or edit an entry on one device, then sync again.';
    setStatus(lastResult);
  }
  desk.addEventListener('vaultpass-demo:sync-request', onAppSyncRequest);
  desk.addEventListener('vaultpass-demo-sync-request', onAppSyncRequest);

  function showFailure(id, message) {
    var host = hosts[id];
    var skeleton = host && host.querySelector('.demo-skeleton p');
    if (skeleton) skeleton.textContent = message;
  }

  function mount(id) {
    var registry = window.VaultPassDemos || {};
    var demo = registry[id];
    var DATA = data();
    if (!demo || typeof demo.mount !== 'function' || !DATA) {
      showFailure(id, 'The demo did not load. Reload the page to try again.');
      return null;
    }
    try {
      var instance = demo.mount(hosts[id], {
        vault: DATA.vaultFor(id),
        deviceName: DATA.devices[id].name,
        peerName: DATA.devices[id === 'desktop' ? 'android' : 'desktop'].name
      });
      instance.onChange(function () {
        lastResult = '';
        refreshStatus();
      });
      instance.onLockChange(refreshStatus);
      return instance;
    } catch (err) {
      if (window.console) console.error('VaultPass demo failed to start:', err);
      showFailure(id, 'The demo could not start in this browser.');
      return null;
    }
  }

  // ---------------------------------------------------------------
  // Loading the demos
  // ---------------------------------------------------------------
  // What the current view shows
  function wantDemos() {
    if (askFirst) return;
    if (view === 'dual') {
      need('desktop');
      need('android');
    } else {
      need(active);
    }
  }

  // Loads and mounts a demo once; calls back with true when it's running
  function need(id, done) {
    if (api[id]) {
      if (done) done(true);
      return;
    }
    if (waiting[id]) {
      if (done) waiting[id].push(done);
      return;
    }
    var callbacks = waiting[id] = done ? [done] : [];
    var files = DEMO_FILES[id];
    var left = 2;
    var ok = true;
    setSkeleton(id, 'Loading the ' + files.name + ' demo.');
    function arrived(success) {
      ok = ok && success;
      if (--left) return;
      whenParsed(function () {
        waiting[id] = null;
        if (!ok && !retried[id]) {
          // One more try: on a flaky connection the second request often gets through
          retried[id] = true;
          setTimeout(function () {
            if (callbacks.length) callbacks.forEach(function (fn) { need(id, fn); });
            else need(id);
          }, 1500);
          return;
        }
        api[id] = ok ? mount(id) : null;
        failed[id] = !api[id];
        if (!ok) showFailure(id, 'The demo didn’t load. Check your connection and reload the page.');
        refreshStatus();
        updateOffer(api[id] ? id : '');
        callbacks.forEach(function (fn) { fn(!!api[id]); });
      });
    }
    fetchFile('link', files.css, arrived);
    fetchFile('script', files.js, arrived);
  }

  function needBoth(done) {
    var left = 2;
    function one() {
      if (!--left) done(!!(api.desktop && api.android));
    }
    need('desktop', one);
    need('android', one);
  }

  function fetchFile(tag, url, done) {
    var node = document.createElement(tag);
    node.onload = function () { done(true); };
    node.onerror = function () { done(false); };
    if (tag === 'link') {
      node.rel = 'stylesheet';
      node.href = url;
      document.head.appendChild(node);
    } else {
      node.src = url;
      document.body.appendChild(node);
    }
  }

  // demo-data.js is deferred, so a demo mounts only after DOMContentLoaded.
  // (readyState turns "interactive" before deferred scripts have run.)
  function whenParsed(fn) {
    if (parsed) fn();
    else afterParse.push(fn);
  }

  function setSkeleton(id, text, button) {
    var skeleton = hosts[id] && hosts[id].querySelector('.demo-skeleton');
    if (!skeleton) return;
    skeleton.textContent = '';
    skeleton.appendChild(document.createElement('p')).textContent = text;
    if (button) skeleton.appendChild(button);
  }

  function loadFailedStatus() {
    setStatus('Couldn’t load the other device’s demo. Check your connection and try again.');
  }

  // Data saver or 2G: a panel over the demo's reserved space (outside the
  // scaled, possibly inert device) offers to load what the view shows
  function offerDemos() {
    ['desktop', 'android'].forEach(function (id) {
      setSkeleton(id, 'The ' + DEMO_FILES[id].name + ' demo isn’t loaded yet.');
    });
    offer = document.createElement('div');
    offer.className = 'desk__offer';
    var card = offer.appendChild(document.createElement('div'));
    card.className = 'desk__offer-card';
    var text = card.appendChild(document.createElement('p'));
    text.setAttribute('role', 'status');
    var btn = card.appendChild(document.createElement('button'));
    btn.type = 'button';
    btn.className = 'btn btn--gold btn--sm';
    btn.addEventListener('click', function () {
      if (btn.getAttribute('aria-disabled') === 'true') return;
      btn.setAttribute('aria-disabled', 'true');
      btn.textContent = 'Loading…';
      text.textContent = 'Loading the demo.';
      offerIds().forEach(function (id) { need(id); });
    });
    desk.querySelector('.desk__viewport').appendChild(offer);
  }

  // What the view shows that isn't running or loading yet
  function offerIds() {
    var ids = view === 'dual' ? ['desktop', 'android'] : [active];
    return ids.filter(function (id) { return !api[id] && !waiting[id]; });
  }

  function updateOffer(loadedId) {
    if (!offer) return;
    var btn = offer.querySelector('button');
    var hadFocus = document.activeElement === btn;
    var shown = view === 'dual' ? ['desktop', 'android'] : [active];
    var ids = offerIds();
    var loading = shown.some(function (id) { return waiting[id]; });
    offer.hidden = !ids.length && !loading;
    if (offer.hidden) {
      // The button that was pressed is gone, so move focus into the demo
      if (hadFocus && loadedId && hosts[loadedId]) {
        hosts[loadedId].setAttribute('tabindex', '-1');
        hosts[loadedId].focus();
      }
      return;
    }
    if (!ids.length) return; // still loading: keep the panel as it is
    var kb = ids.reduce(function (sum, id) { return sum + DEMO_FILES[id].kb; }, 0);
    offer.querySelector('p').textContent = 'Your connection is slow or saving data, so the demo loads only when you ask.';
    btn.removeAttribute('aria-disabled');
    btn.textContent = ids.length > 1
      ? 'Load the demos (about ' + kb + ' KB)'
      : 'Load the ' + DEMO_FILES[ids[0]].name + ' demo (about ' + kb + ' KB)';
  }

  whenParsed(refreshStatus);
})();
