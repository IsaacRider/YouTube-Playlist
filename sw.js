const CACHE = 'music-v5';
const APP_FILES = ['/player.html', '/manifest.json'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(APP_FILES)));
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(async names => {
      const oldCaches = names.filter(n => n !== CACHE);
      if (oldCaches.length > 0) {
        const newCache = await caches.open(CACHE);
        for (const oldName of oldCaches) {
          const oldCache = await caches.open(oldName);
          const keys = await oldCache.keys();
          for (const req of keys) {
            if (new URL(req.url).pathname.endsWith('.mp3')) {
              const resp = await oldCache.match(req);
              if (resp) await newCache.put(req, resp);
            }
          }
          await caches.delete(oldName);
        }
      }
    }).then(() => self.clients.claim())
  );
});

self.addEventListener('message', e => {
  if (e.data && e.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);

  // Let sync fetches, API calls, and mutations bypass the SW entirely
  if (url.searchParams.has('_sync') || url.pathname.startsWith('/api/') || e.request.method === 'POST' || e.request.method === 'DELETE') {
    return;
  }

  if (url.pathname.endsWith('.mp3')) {
    e.respondWith(
      caches.open(CACHE).then(c =>
        c.match(e.request).then(r => {
          if (r) return r;
          return fetch(e.request).then(resp => {
            if (resp.ok) {
              const clone = resp.clone();
              c.put(e.request, clone);
            }
            return resp;
          });
        })
      )
    );
    return;
  }

  // App files — network first, fall back to cache (so updates arrive quickly)
  // Also serve /player.html for root URL requests
  const cacheMatch = url.pathname === '/' ? '/player.html' : e.request;
  e.respondWith(
    fetch(e.request).then(resp => {
      const clone = resp.clone();
      caches.open(CACHE).then(c => c.put(e.request, clone));
      return resp;
    }).catch(() => caches.open(CACHE).then(c => c.match(cacheMatch)))
  );
});
