/* Iron Log PWA：缓存壳子 + 静态资源；记组走页面内离线队列，不在此伪造 API */
const CACHE = 'iron-log-ui30-v1';
const PRECACHE = [
  '/',
  '/index.html',
  '/app.js?v=20261102-ui30',
  '/food-db.js?v=20261030-ui26',
  '/vendor/app.css?v=20261102-ui30',
  '/vendor/vue.global.prod.js',
  '/vendor/echarts.min.js',
  '/manifest.webmanifest'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(PRECACHE).catch(() => undefined)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  // API / SSE 不缓存
  if (url.pathname.startsWith('/training')
      || url.pathname.startsWith('/agent')
      || url.pathname.startsWith('/ai')
      || url.pathname.startsWith('/auth')
      || url.pathname.startsWith('/knowledge')
      || url.pathname.startsWith('/api')) {
    return;
  }
  // HTML / 带版本号的 JS·CSS：网络优先，避免旧壳把新登录页卡死
  const networkFirst = url.pathname === '/'
      || url.pathname.endsWith('.html')
      || url.pathname.endsWith('.js')
      || url.pathname.endsWith('.css')
      || url.pathname.endsWith('sw.js');
  if (networkFirst) {
    event.respondWith(
      fetch(req).then((res) => {
        if (res && res.ok && url.origin === self.location.origin) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
        }
        return res;
      }).catch(() => caches.match(req))
    );
    return;
  }
  event.respondWith(
    caches.match(req).then((cached) => {
      const fetched = fetch(req).then((res) => {
        if (res && res.ok && url.origin === self.location.origin) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
        }
        return res;
      }).catch(() => cached);
      return cached || fetched;
    })
  );
});
