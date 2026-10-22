/*
 * The service worker of VeriQra.
 *
 * The application shell is precached so the PWA opens instantly and keeps working on a
 * weak connection. API responses are never cached: attendance decisions must always come
 * from the server, never from a stale copy.
 */

const CACHE_NAME = 'veriqra-shell-v4';

const SHELL_ASSETS = [
  '/',
  '/index.html',
  '/offline.html',
  '/manifest.webmanifest',
  '/css/tokens.css',
  '/css/base.css',
  '/css/components.css',
  '/css/layout.css',
  '/css/home.css',
  '/js/main.js',
  '/js/api.js',
  '/js/dom.js',
  '/js/icons.js',
  '/js/live.js',
  '/js/store.js',
  '/js/router.js',
  '/js/ui.js',
  '/js/camera.js',
  '/js/qr.js',
  '/js/views/auth.js',
  '/js/views/verify.js',
  '/js/views/student.js',
  '/js/views/faculty.js',
  '/js/views/classes.js',
  '/js/views/profile.js',
  '/js/views/home.js',
  '/vendor/qrcode.min.js',
  '/vendor/jsQR.min.js',
  '/fonts/Geist-Variable.woff2',
  '/fonts/GeistMono-Variable.woff2',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/icon-180.png',
  '/icons/logo.png',
  '/icons/favicon.svg'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(SHELL_ASSETS))
      .then(() => self.skipWaiting())
      .catch(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') {
    return;
  }

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) {
    return;
  }

  // The API always goes to the network. A cached attendance record would be a lie.
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(fetch(request).catch(() => new Response(
      JSON.stringify({ code: 'OFFLINE', message: 'You are offline. Reconnect to reach the server.', status: 503 }),
      { status: 503, headers: { 'Content-Type': 'application/json' } }
    )));
    return;
  }

  // Navigations fall back to the cached shell so the application opens offline.
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put('/index.html', copy));
          return response;
        })
        .catch(() => caches.match('/index.html')
          .then((cached) => cached || caches.match('/offline.html')))
    );
    return;
  }

  // Static assets are served from the cache and refreshed in the background.
  event.respondWith(
    caches.match(request).then((cached) => {
      const network = fetch(request).then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
        }
        return response;
      }).catch(() => cached);
      return cached || network;
    })
  );
});
