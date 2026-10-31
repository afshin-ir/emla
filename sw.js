const CACHE_PREFIX = 'emla-';
const CACHE_NAME = 'emla-static-v10';
const PRECACHE_URLS = [
  './', './index.html', './book.json', './dictionary.html', './dictionary.json', './dictionary.webp',
  './manifest.json', './assets/favicon.svg', './assets/book-cover.jpg',
  './articles/', './articles/index.html', './articles/1/', './articles/2/', './articles/3/',
  './articles/3/assets/figure-1.png', './articles/3/assets/figure-2.png', './articles/3/assets/figure-3.png',
  './words.html', './approved-words.html', './approved-words-1.json.gz', './approved-words-2.json.gz',
  './approved-words-3.json.gz', './approved-words-4.json.gz'
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
    await Promise.all(keys.filter(key => key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME).map(key => caches.delete(key)));
    await self.clients.claim();
  })());
});
self.addEventListener('fetch', event => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (request.mode === 'navigate') {
    event.respondWith((async () => {
      try {
        const response = await fetch(request);
        if (response.ok) (await caches.open(CACHE_NAME)).put(request, response.clone());
        return response;
      } catch {
        const cache = await caches.open(CACHE_NAME);
        return (await cache.match(request)) || (await cache.match('./dictionary.html')) || (await cache.match('./articles/')) || (await cache.match('./index.html'));
      }
    })());
    return;
  }
  if (url.pathname.endsWith('/book.json') || url.pathname.endsWith('/dictionary.json')) {
    event.respondWith((async () => {
      const cache = await caches.open(CACHE_NAME);
      try {
        const response = await fetch(request);
        if (response.ok) cache.put(request, response.clone());
        return response;
      } catch {
        const saved = await cache.match(request);
        if (saved) return saved;
        throw new Error('Data is not available offline yet.');
      }
    })());
    return;
  }
  event.respondWith((async () => {
    const cache = await caches.open(CACHE_NAME), cached = await cache.match(request);
    const refresh = fetch(request).then(response => { if (response.ok) cache.put(request, response.clone()); return response; }).catch(() => null);
    if (cached) { event.waitUntil(refresh); return cached; }
    return (await refresh) || Response.error();
  })());
});


