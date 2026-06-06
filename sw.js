const CACHE = 'music-v1';
const APP_FILES = ['/player.html', '/manifest.json'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(APP_FILES)));
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);

  // API calls and POST — always network
  if (url.pathname.startsWith('/api/') || e.request.method === 'POST') {
    return;
  }

  // MP3 files — serve from cache first, fallback to network
  if (url.pathname.endsWith('.mp3')) {
    e.respondWith(
      caches.open(CACHE).then(c =>
        c.match(e.request).then(r => r || fetch(e.request))
      )
    );
    return;
  }

  // App files — cache first, fallback network
  e.respondWith(
    caches.open(CACHE).then(c =>
      c.match(e.request).then(r => r || fetch(e.request).then(resp => {
        c.put(e.request, resp.clone());
        return resp;
      }))
    )
  );
});
