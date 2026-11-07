const CACHE_NAME = 'gfp-ps-v3';
const ASSETS = [
  '/gfp-vault/',
  '/gfp-vault/index.html',
  '/gfp-vault/manifest.json',
  '/gfp-vault/icon.png'
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS)).catch(() => {})
  );
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.map((k) => (k !== CACHE_NAME ? caches.delete(k) : null)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        if (e.request.url.startsWith(self.location.origin)) {
          const clone = res.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(e.request, clone));
        }
        return res;
      })
      .catch(() => caches.match(e.request))
  );
});

self.addEventListener('push', (e) => {
  let data = { title: 'GFP — Alerta Financeiro', body: 'Você tem lembretes financeiros pendentes.' };
  try {
    if (e.data) data = Object.assign(data, e.data.json());
  } catch (err) {}
  e.waitUntil(
    self.registration.showNotification(data.title, {
      body: data.body,
      icon: '/gfp-vault/icon.png',
      badge: '/gfp-vault/icon.png',
      vibrate: [200, 100, 200]
    })
  );
});

self.addEventListener('sync', (e) => {
  if (e.tag === 'gfp-sync-queue') {
    e.waitUntil(Promise.resolve());
  }
});

self.addEventListener('periodicsync', (e) => {
  if (e.tag === 'gfp-daily-check') {
    e.waitUntil(Promise.resolve());
  }
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if (client.url.includes('/gfp-vault') && 'focus' in client) {
          return client.focus();
        }
      }
      if (self.clients.openWindow) {
        return self.clients.openWindow('/gfp-vault/');
      }
    })
  );
});
