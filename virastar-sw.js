const CACHE_NAME='virastar-offline-v4';
const PRECACHE_URLS=[
  './paknevis-online.html',
  './virastar.js',
  './virastar-diff.min.js',
  './virastar-jszip.min.js',
  './virastar-doc.js',
  './virastar-texttodoc.js',
  './fonts.css',
  './Vazirmatn%5Bwght%5D.woff2',
  './site-nav.css',
  './site-footer.js',
  './favicon.svg',
  './favicon.ico'
];
self.addEventListener('install',event=>{
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache=>cache.addAll(PRECACHE_URLS))
      .then(()=>self.skipWaiting())
  );
});
self.addEventListener('activate',event=>{
  event.waitUntil(
    caches.keys()
      .then(keys=>Promise.all(keys.filter(key=>key.startsWith('virastar-offline-')&&key!==CACHE_NAME).map(key=>caches.delete(key))))
      .then(()=>self.clients.claim())
  );
});
self.addEventListener('fetch',event=>{
  const request=event.request;
  if(request.method!=='GET')return;
  const url=new URL(request.url);
  if(url.origin!==self.location.origin)return;
  if(request.mode==='navigate'&&url.pathname.endsWith('/paknevis-online.html')){
    event.respondWith(
      fetch(request)
        .then(response=>{
          if(response.ok){const copy=response.clone();caches.open(CACHE_NAME).then(cache=>cache.put(request,copy));}
          return response;
        })
        .catch(()=>caches.match(request,{ignoreSearch:true}))
    );
    return;
  }
  event.respondWith(
    caches.match(request,{ignoreSearch:true}).then(cached=>cached||fetch(request))
  );
});
