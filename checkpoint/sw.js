// 앱 화면은 네트워크 우선(업데이트 반영), 끊기면 캐시로 오프라인 실행
const CACHE = "save-checkpoint-v1";
const SHELL = ["./", "./index.html", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)));
  self.skipWaiting();
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k.startsWith("save-checkpoint-") && k !== CACHE).map(k => caches.delete(k)))));
  self.clients.claim();
});
self.addEventListener("fetch", e => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  // 공유로 열린 주소(?url=...)도 같은 앱 화면을 쓴다
  const key = url.pathname.endsWith("/") ? new Request(url.origin + url.pathname) : e.request;
  e.respondWith(fetch(e.request).then(res => {
    const copy = res.clone(); caches.open(CACHE).then(c => c.put(key, copy));
    return res;
  }).catch(() => caches.match(key)));
});
