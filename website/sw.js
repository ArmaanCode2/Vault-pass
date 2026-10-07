/**
 * VaultPass service worker
 * - Pages (navigations / HTML): network first, cached copy only when offline.
 *   A deploy is visible on the next visit instead of one visit later.
 * - Same-origin static files (CSS, JS, images): served from cache, refreshed
 *   in the background (the refresh is kept alive with waitUntil).
 * - Cross-origin requests (Tailwind CDN, fonts, GitHub API, APK downloads)
 *   are never intercepted or cached.
 *
 * Bump CACHE_VERSION on every deploy that changes a precached file.
 */

const CACHE_VERSION = 'v4-2026-10-02';
const CACHE_NAME = 'vaultpass-' + CACHE_VERSION;

const PRECACHE = [
  './',
  'index.html',
  'security.html',
  'download.html',
  'privacy.html',
  'css/styles.css',
  'js/app.js',
  'js/demo-app.js',
  'assets/icon.webp'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(PRECACHE))
      .catch((err) => console.warn('Precache incomplete:', err))
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
      .then(() => self.clients.claim())
  );
});

function isCacheable(response) {
  return response && response.ok && response.type === 'basic';
}

async function networkFirst(event) {
  const cache = await caches.open(CACHE_NAME);
  try {
    const response = await fetch(event.request);
    if (isCacheable(response)) {
      event.waitUntil(cache.put(event.request, response.clone()));
    }
    return response;
  } catch (err) {
    const cached = await cache.match(event.request, { ignoreSearch: true });
    if (cached) return cached;
    // Offline fallback: the home page, if it was cached
    const home = await cache.match('index.html');
    if (home) return home;
    return new Response('You are offline and this page is not cached yet.', {
      status: 503,
      headers: { 'Content-Type': 'text/plain; charset=utf-8' }
    });
  }
}

async function staleWhileRevalidate(event) {
  const cache = await caches.open(CACHE_NAME);
  const cached = await cache.match(event.request);
  const refresh = fetch(event.request)
    .then((response) => {
      if (isCacheable(response)) {
        return cache.put(event.request, response.clone()).then(() => response);
      }
      return response;
    })
    .catch(() => undefined);

  if (cached) {
    // Serve the cached copy now; keep the worker alive until the refresh is stored
    event.waitUntil(refresh);
    return cached;
  }
  const response = await refresh;
  return response || Response.error();
}

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return; // CDN, fonts, GitHub: browser handles these
  if (url.pathname.endsWith('.apk')) return;

  const accept = request.headers.get('accept') || '';
  const isPage = request.mode === 'navigate' || accept.includes('text/html') || url.pathname.endsWith('.html');

  event.respondWith(isPage ? networkFirst(event) : staleWhileRevalidate(event));
});
