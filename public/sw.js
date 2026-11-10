const CACHE = 'kaffee-bestand-v2'
const APP_SHELL = ['./', './index.html', './manifest.webmanifest', './favicon.svg']

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(APP_SHELL)))
  self.skipWaiting()
})

self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))))
  self.clients.claim()
})

self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return
  // Nur die eigene App-Hülle cachen: nie TESS (/kaffee/v1, Sitzungs-Token), Google Drive oder andere fremde Adressen.
  const url = new URL(event.request.url)
  if (url.origin !== self.location.origin || url.pathname.includes('/kaffee/v1/')) return
  event.respondWith(
    fetch(event.request)
      .then(response => {
        const copy = response.clone()
        caches.open(CACHE).then(cache => cache.put(event.request, copy))
        return response
      })
      .catch(() => caches.match(event.request).then(hit => hit || caches.match('./index.html')))
  )
})
