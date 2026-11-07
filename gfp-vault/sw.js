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

self.addEventListener('message', (e) => {
  if (e.data && e.data.type === 'GFP_SW_SAVE_SCHEDULE' && Array.isArray(e.data.schedule)) {
    e.waitUntil(
      caches.open(CACHE_NAME).then((cache) =>
        cache.put('/gfp-vault/__schedule.json', new Response(JSON.stringify(e.data.schedule)))
      )
    );
  }
});

async function evaluateCachedScheduleAndNotify() {
  try {
    const cache = await caches.open(CACHE_NAME);
    const res = await cache.match('/gfp-vault/__schedule.json');
    if (!res) return;
    const schedule = await res.json();
    if (!Array.isArray(schedule) || !schedule.length) return;

    const now = new Date();
    const todayISO = new Date(now.getTime() - (now.getTimezoneOffset() * 60000)).toISOString().slice(0, 10);
    const todayMs = new Date(todayISO + 'T12:00:00Z').getTime();

    const hits = [];
    schedule.forEach((t) => {
      const txMs = new Date(t.data_ocorrencia + 'T12:00:00Z').getTime();
      const diffDays = Math.round((txMs - todayMs) / (1000 * 60 * 60 * 24));
      const customDays = Array.isArray(t.customDays) ? t.customDays : [];
      if (customDays.includes(diffDays) || (t.status === 'PENDENTE' && (diffDays === 0 || diffDays < 0 || (t.isAutoDebit && diffDays === 1)))) {
        const tagQuando = diffDays === 0 ? 'HOJE' : (diffDays === 1 ? 'AMANHÃ' : (diffDays < 0 ? `ATRASADO (${Math.abs(diffDays)}d)` : `em ${diffDays}d`));
        hits.push(`${t.descricao} (${tagQuando})`);
      }
    });

    if (hits.length > 0) {
      await self.registration.showNotification(`🔔 GFP — ${hits.length} Lembrete(s) Hoje`, {
        body: hits.slice(0, 4).join(' • '),
        icon: '/gfp-vault/icon.png',
        badge: '/gfp-vault/icon.png',
        vibrate: [200, 100, 200],
        tag: 'gfp-daily-' + todayISO
      });
    }
  } catch (err) {}
}

self.addEventListener('sync', (e) => {
  if (e.tag === 'gfp-sync-queue') {
    e.waitUntil(evaluateCachedScheduleAndNotify());
  }
});

self.addEventListener('periodicsync', (e) => {
  if (e.tag === 'gfp-daily-check') {
    e.waitUntil(evaluateCachedScheduleAndNotify());
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
