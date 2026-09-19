self.addEventListener('install', (event) => {
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(clients.claim());
});

self.addEventListener('push', (event) => {
    // サーバーは {title, body, url} の JSON を送るが、JSON でないペイロードが来ても
    // 通知を落とさない（userVisibleOnly なので「何も出さない」は許可の剥奪に繋がる）。
    let data = { title: 'beat-seeker', body: '新しい通知があります', url: '/' };
    if (event.data) {
        try {
            data = Object.assign(data, event.data.json());
        } catch (e) {
            data.body = event.data.text() || data.body;
        }
    }

    const options = {
        body: data.body,
        icon: '/icon-192.png',
        badge: '/favicon.svg',
        data: data.url || '/'
    };

    event.waitUntil(
        self.registration.showNotification(data.title, options)
    );
});

self.addEventListener('notificationclick', (event) => {
    event.notification.close();
    // 既に開いているタブがあればそれを前面に出して遷移させる。
    // 無条件に openWindow すると、アプリを開いたまま通知を押すたびにタブが増える。
    const target = new URL(event.notification.data || '/', self.location.origin);
    event.waitUntil((async () => {
        const windowClients = await clients.matchAll({ type: 'window', includeUncontrolled: true });
        for (const client of windowClients) {
            if (new URL(client.url).origin !== target.origin) continue;
            await client.focus();
            // navigate() は一部ブラウザで未対応 / 失敗しうるのでフォーカスだけは確保する。
            if ('navigate' in client) {
                await client.navigate(target.href).catch(() => undefined);
            }
            return;
        }
        await clients.openWindow(target.href);
    })());
});

// ============================================================
// Web Share Target (リザルト画像の共有受け取り)
// ------------------------------------------------------------
// manifest.json の share_target.action (/share-target) へ OS の「共有」から
// POST されてくる multipart/form-data を横取りし、添付画像を Cache に一時保存して、
// アプリ本体 (/?sharetarget=1) へリダイレクトする。アプリ側がその画像を読み出して
// 「曲名検索 → 該当譜面に保存」フローを開く。
//
// 注意: この fetch ハンドラは対象 POST のときだけ respondWith する。
// それ以外のリクエストは何もしない（= 通常どおりネットワーク処理）。
// ============================================================
self.addEventListener('fetch', (event) => {
    const url = new URL(event.request.url);
    if (event.request.method === 'POST' && url.pathname === '/share-target') {
        event.respondWith((async () => {
            try {
                const formData = await event.request.formData();
                // manifest の files[].name = "images" と一致させる。文字列パートは除外。
                const files = formData.getAll('images').filter((f) => f && typeof f !== 'string');
                const file = files[0];
                if (file) {
                    const cache = await caches.open('shared-images');
                    await cache.put('/__shared-image', new Response(file, {
                        headers: {
                            'Content-Type': file.type || 'application/octet-stream',
                            'X-Shared-Name': encodeURIComponent(file.name || 'shared'),
                        },
                    }));
                }
            } catch (e) {
                // 解析に失敗してもアプリへは遷移させる（アプリ側で「画像なし」を表示）。
            }
            // 303 See Other で GET 遷移させる（POST の再送を避ける）。
            // Response.redirect は絶対 URL を要求するため origin から組み立てる。
            return Response.redirect(new URL('/?sharetarget=1', self.location.origin).href, 303);
        })());
    }
    // 上記以外は respondWith しない → ブラウザ既定の処理に委ねる。
});
