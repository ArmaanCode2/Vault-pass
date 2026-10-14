/**
 * VaultPass service worker: an offline copy of the site.
 *
 * Everything same-origin is fetched from the network first; the cache is only
 * used when the network fails. This keeps HTML, scripts and styles in step
 * after a deploy, and because this origin (armaancode2.github.io) is shared
 * with other GitHub Pages sites, it means a cache entry planted by another
 * page could only ever be served offline, never in place of the real file.
 * Cross-origin requests (GitHub API, raw.githubusercontent.com, downloads)
 * are not intercepted.
 *
 * The list below is the site's shell: the pages, styles, small scripts,
 * icons and fonts (about 125 KB gzipped, of which the page being visited has
 * already loaded most; the extra download is about 28-39 KB). The two app
 * demos are left out: on install the worker keeps only the demo files the
 * browser already downloaded for this visit (from its HTTP cache, so no extra
 * bytes), and later visits store whatever demo is loaded. A phone that only
 * shows the Android demo never downloads the Windows one. With data saver on,
 * the worker skips the shell too.
 *
 * Bump CACHE_VERSION when the list below changes.
 */

const CACHE_VERSION = 'v9-2026-10-10';
const CACHE_NAME = 'vaultpass-' + CACHE_VERSION;

const PRECACHE = [
  './',
  'index.html',
  'download.html',
  'security.html',
  'privacy.html',
  'css/site.css',
  'js/boot.js',
  'js/github.js',
  'js/md.js',
  'js/site.js',
  'js/stage.js',
  'js/verify.js',
  'js/privacy.js',
  'js/demo-data.js',
  'assets/icons.svg',
  'assets/external.svg',
  'assets/icon-64.png',
  'assets/icon-128.webp',
  'assets/fonts/BigShoulders-Bold.woff2',
  'assets/fonts/WorkSans-Regular.woff2',
  'assets/fonts/WorkSans-Bold.woff2',
  'assets/fonts/RedHatMono-Regular.woff2'
];

const DEMO_FILES = [
  'css/demo-desktop.css',
  'js/demo-desktop.js',
  'css/demo-mobile.css',
  'js/demo-mobile.js'
];

// Demo files this browser already has: copied from its HTTP cache, never fetched
function keepCachedDemos(cache) {
  return Promise.allSettled(DEMO_FILES.map((url) =>
    fetch(url, { cache: 'only-if-cached', mode: 'same-origin' })
      .then((response) => (response.ok ? cache.put(url, response) : undefined))
  ));
}

self.addEventListener('install', (event) => {
  const connection = self.navigator && self.navigator.connection;
  const saveData = !!(connection && connection.saveData);
  event.waitUntil(
    caches.open(CACHE_NAME)
      // One missing file must not stop the rest from being saved
      .then((cache) => (saveData ? undefined : Promise.allSettled(PRECACHE.map((url) => cache.add(url)))
        .then(() => keepCachedDemos(cache))))
      .catch(() => undefined)
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((names) => Promise.all(
        names
          .filter((name) => name.startsWith('vaultpass-') && name !== CACHE_NAME)
          .map((name) => caches.delete(name))
      ))
      .catch(() => undefined)
      .then(() => self.clients.claim())
  );
});

async function fromCache(request, isPage) {
  try {
    const cache = await caches.open(CACHE_NAME);
    const cached = await cache.match(request, { ignoreSearch: isPage });
    if (cached) return cached;
    if (isPage) return (await cache.match('index.html')) || null;
  } catch (e) {
    // Storage unavailable: nothing to fall back to
  }
  return null;
}

async function networkFirst(event, isPage) {
  try {
    const response = await fetch(event.request);
    if (response && response.ok && response.type === 'basic') {
      const copy = response.clone();
      event.waitUntil(
        caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy)).catch(() => undefined)
      );
    }
    return response;
  } catch (err) {
    const cached = await fromCache(event.request, isPage);
    if (cached) return cached;
    if (isPage) {
      return new Response('You are offline and this page is not saved yet.', {
        status: 503,
        headers: { 'Content-Type': 'text/plain; charset=utf-8' }
      });
    }
    return Response.error();
  }
}

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  // Only this site's own files, not other Pages sites on the same origin
  const scope = new URL(self.registration.scope).pathname;
  if (!url.pathname.startsWith(scope)) return;

  const accept = request.headers.get('accept') || '';
  const isPage = request.mode === 'navigate' || accept.includes('text/html');
  event.respondWith(networkFirst(event, isPage));
});
