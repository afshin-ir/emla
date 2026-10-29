const CACHE_PREFIX = 'emla-';
const CACHE_NAME = 'emla-static-v2';

const PRECACHE_URLS = [
  './',
  './index.html',
  './book.json',
  './manifest.json',
  './assets/favicon.svg',
  './assets/book-cover.jpg',
  './articles/',
  './articles/index.html',
  './articles/dastur-khat.html'
];

self.addEventListener('install', event => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    await cache.addAll(PRECACHE_URLS);
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys
      .filter(key => key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME)
      .map(key => caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', event => {
  const request = event.request;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  // Leave analytics, fonts, and all other third-party requests to the browser.
  if (url.origin !== self.location.origin) return;

  // Prefer the network for page navigation; use the saved page when offline.
  if (request.mode === 'navigate') {
    event.respondWith((async () => {
      try {
        const response = await fetch(request);
        if (response.ok) {
          const cache = await caches.open(CACHE_NAME);
          cache.put(request, response.clone());
        }
        return response;
      } catch {
        const cache = await caches.open(CACHE_NAME);
        return (await cache.match(request)) ||
          (await cache.match('./articles/')) ||
          (await cache.match('./index.html'));
      }
    })());
    return;
  }

  // The book is essential: use the network when available, with an offline copy.
  if (url.pathname.endsWith('/book.json')) {
    event.respondWith((async () => {
      const cache = await caches.open(CACHE_NAME);
      try {
        const response = await fetch(request);
        if (response.ok) cache.put(request, response.clone());
        return response;
      } catch {
        const saved = await cache.match(request);
        if (saved) return saved;
        throw new Error('Book data is not available offline yet.');
      }
    })());
    return;
  }

  // For same-origin assets, return the cached copy and refresh it in the background.
  event.respondWith((async () => {
    const cache = await caches.open(CACHE_NAME);
    const cached = await cache.match(request);
    const refresh = fetch(request).then(response => {
      if (response.ok) cache.put(request, response.clone());
      return response;
    }).catch(() => null);
    if (cached) {
      event.waitUntil(refresh);
      return cached;
    }
    const response = await refresh;
    return response || Response.error();
  })());
});
