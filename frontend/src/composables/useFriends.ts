import { ref } from 'vue';
import { useAuth, API_BASE } from './useAuth';

/**
 * フレンド一覧に表示される 1 ユーザー分の情報。
 *
 *  - `privacyLevel`: 他ユーザーへの公開レベル（プロフィール検索結果で使用）
 *  - `isFriend` / `hasSentRequest`: 検索結果画面での関係状態表示用フラグ
 */
export interface Friend {
    id: number;
    displayName: string;
    iidxId: string;
    lastUploadedAt: string | null;
    totalBeatPt: number;
    privacyLevel?: number;
    isFriend?: boolean;
    hasSentRequest?: boolean;
    /** 前作の最終 BEAT-PT（ティアアイコンの外枠用）。記録が無ければ null。 */
    previousBeatPt?: number | null;
    /** 前作の最終 RATE-PT。 */
    previousRatePt?: number | null;
}

/**
 * バーチャルライバル（過去バージョンの都道府県トップ）1 件分のデータ。
 * フレンドではない「仮想のライバル」をランキングに追加する機能で使用。
 */
export interface VirtualRival {
    id: number;
    versionNum: number;
    versionName: string;
    prefectureFileNum: number;
    prefectureName: string;
    totalBeatPt: number;
    totalRatePt: number;
    createdAt: string | null;
}

/** 自分宛に届いている「フレンド申請」1 件。 */
export interface PendingRequest {
    id: number;
    senderId: number;
    senderName: string;
    senderIidxId: string;
    message: string | null;
    createdAt: string;
}

/** アプリ内通知 1 件（フレンド承認通知などの汎用タイプ）。 */
export interface AppNotificationItem {
    id: number;
    type: string;
    message: string;
    read: boolean;
    createdAt: string;
}

// ── モジュールレベルの共有 state ──────────────────────────────
// App.vue と NotificationBox.vue など複数箇所から参照される state は、
// モジュールトップに置いて「同じ ref インスタンス」を共有させる（シングルトン）。
/** 自分宛に届いているフレンド申請。ヘッダのバッジ表示等で使う。 */
const pendingRequests = ref<PendingRequest[]>([]);
/** アプリ内通知リスト。通知アイコンをクリックして開くパネルで表示。 */
const appNotifications = ref<AppNotificationItem[]>([]);
/** 未読通知件数。ヘッダアイコンの赤バッジ等で使う。 */
const appUnreadCount = ref(0);

/** ブラウザ通知（Web Push）のサーバー側 / クライアント側の状態。 */
export interface PushStatus {
    /** サーバーに VAPID 鍵が投入され、送信できる状態か。false なら誰にも通知は届かない。 */
    serverEnabled: boolean;
    /** クライアントが subscribe に使うべき VAPID 公開鍵（base64url）。 */
    publicKey: string;
    /** このユーザーの購読がサーバーに保存されているか。 */
    subscribed: boolean;
}

/**
 * 直近に取得した Push の稼働状態。プロフィール画面が「サーバー側で無効」を
 * 出し分けるために参照する（全 UI で同じ値を見るようモジュールレベルに置く）。
 */
const pushStatus = ref<PushStatus | null>(null);

/**
 * 【Composable の役割】 フレンド機能と通知機能を包括的に提供する。
 *
 * 大きく 4 つの領域:
 *  1. フレンドリスト（`fetchFriends`, `searchUsers`, `removeFriend`）
 *  2. フレンド申請（`sendFriendRequest`, `fetchPendingRequests`, `acceptRequest`, `rejectRequest`）
 *  3. バーチャルライバル（`fetchVirtualRivals`, `addVirtualRival`, `removeVirtualRival`）
 *  4. 通知（Web Push 購読 / アプリ内通知一覧 / 既読化）
 *
 * 使い方:
 * ```ts
 * const { friends, fetchFriends, pendingRequests } = useFriends();
 * await fetchFriends();
 * ```
 */
export function useFriends() {
    const { authHeaders } = useAuth();
    /** 自分のフレンド一覧（コンポーネント単位のローカル state）。 */
    const friends = ref<Friend[]>([]);
    /** フェッチ中フラグ（スピナー用）。 */
    const isLoading = ref(false);
    /** エラーメッセージ。失敗時に UI で表示する。 */
    const error = ref<string | null>(null);

    /** フレンド一覧を取得して `friends` に格納する。 */
    const fetchFriends = async () => {
        isLoading.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/friends`, {
                headers: authHeaders()
            });
            if (!res.ok) throw new Error('Failed to fetch friends');
            friends.value = await res.json();
        } catch (e: any) {
            error.value = e.message;
        } finally {
            isLoading.value = false;
        }
    };

    /**
     * ユーザーを検索する（IIDX ID / 表示名で部分一致）。
     * 結果には `isFriend` `hasSentRequest` フラグが入り、UI でボタン状態を出し分ける。
     */
    const searchUsers = async (query: string): Promise<Friend[]> => {
        isLoading.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/friends/search?query=${encodeURIComponent(query)}`, {
                headers: authHeaders()
            });
            if (!res.ok) throw new Error('Search failed');
            return await res.json();
        } catch (e: any) {
            error.value = e.message;
            return [];
        } finally {
            isLoading.value = false;
        }
    };

    /**
     * フレンド申請を送信する。
     * @param receiverId 申請先ユーザー ID
     * @param message 添付メッセージ（任意）
     */
    const sendFriendRequest = async (receiverId: number, message?: string) => {
        try {
            const res = await fetch(`${API_BASE}/api/friends/request`, {
                method: 'POST',
                headers: authHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify({ receiverId, message })
            });
            if (!res.ok) {
                const data = await res.json();
                throw new Error(data.message || 'Failed to send request');
            }
        } catch (e: any) {
            error.value = e.message;
            throw e;
        }
    };

    /** 自分宛ペンディング中のフレンド申請を取得し、共有 state `pendingRequests` に格納する。 */
    const fetchPendingRequests = async () => {
        try {
            const res = await fetch(`${API_BASE}/api/friends/requests/pending`, {
                headers: authHeaders()
            });
            if (!res.ok) throw new Error('Failed to fetch requests');
            pendingRequests.value = await res.json();
        } catch (e: any) {
            error.value = e.message;
        }
    };

    /**
     * フレンド申請を承認する。
     * 承認後は申請リストとフレンド一覧の両方を再取得して UI を最新化する。
     */
    const acceptRequest = async (requestId: number) => {
        try {
            const res = await fetch(`${API_BASE}/api/friends/requests/${requestId}/accept`, {
                method: 'POST',
                headers: authHeaders()
            });
            if (!res.ok) throw new Error('Failed to accept');
            await fetchPendingRequests();
            await fetchFriends();
        } catch (e: any) {
            error.value = e.message;
            throw e;
        }
    };

    /** フレンド申請を拒否する。拒否後は申請リストのみ再取得。 */
    const rejectRequest = async (requestId: number) => {
        try {
            const res = await fetch(`${API_BASE}/api/friends/requests/${requestId}/reject`, {
                method: 'POST',
                headers: authHeaders()
            });
            if (!res.ok) throw new Error('Failed to reject');
            await fetchPendingRequests();
        } catch (e: any) {
            error.value = e.message;
            throw e;
        }
    };

    /** 既にフレンドであるユーザーをフレンド解除する。 */
    const removeFriend = async (friendId: number) => {
        try {
            const res = await fetch(`${API_BASE}/api/friends/${friendId}`, {
                method: 'DELETE',
                headers: authHeaders()
            });
            if (!res.ok) throw new Error('Failed to remove friend');
            await fetchFriends();
        } catch (e: any) {
            error.value = e.message;
            throw e;
        }
    };

    /** フレンドのスコアを取得する。`useScores.fetchUserScores('friend')` と役割は近いが、ここでは生配列を返す。 */
    const fetchFriendScores = async (friendId: number) => {
        isLoading.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/friends/${friendId}/scores`, {
                headers: authHeaders()
            });
            if (!res.ok) throw new Error('Failed to fetch friend scores');
            return await res.json();
        } catch (e: any) {
            error.value = e.message;
            throw e;
        } finally {
            isLoading.value = false;
        }
    };

    /**
     * 登録済みバーチャルライバル一覧を取得する。
     * 失敗時は空配列を返す（エラー表示の必要がない UI なので throw せず静かに失敗）。
     */
    const fetchVirtualRivals = async (): Promise<VirtualRival[]> => {
        try {
            const res = await fetch(`${API_BASE}/api/friends/virtual-rivals`, {
                headers: authHeaders()
            });
            if (!res.ok) return [];
            return await res.json();
        } catch {
            return [];
        }
    };

    /**
     * 特定のバージョン・都道府県について、自分が既にバーチャルライバル登録しているか確認する。
     * @returns 登録済みなら `true`
     */
    const fetchVirtualRivalStatus = async (versionNum: number, prefectureFileNum: number): Promise<boolean> => {
        try {
            const url = `${API_BASE}/api/friends/virtual-rivals/status?versionNum=${versionNum}&prefectureFileNum=${prefectureFileNum}`;
            const res = await fetch(url, { headers: authHeaders() });
            if (!res.ok) return false;
            const data = await res.json();
            return !!data.registered;
        } catch {
            return false;
        }
    };

    /**
     * バーチャルライバルを登録する。バージョン名・県名はサーバで補完できるが
     * UI 側でキャッシュしている値を送ることで追加のクエリを削減している。
     */
    const addVirtualRival = async (payload: {
        versionNum: number;
        prefectureFileNum: number;
        versionName?: string;
        prefectureName?: string;
    }) => {
        const res = await fetch(`${API_BASE}/api/friends/virtual-rivals`, {
            method: 'POST',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(payload)
        });
        if (!res.ok) {
            const data = await res.json().catch(() => ({}));
            throw new Error(data.message || 'ライバル登録に失敗しました');
        }
    };

    /** バーチャルライバルを解除する。 */
    const removeVirtualRival = async (versionNum: number, prefectureFileNum: number) => {
        const url = `${API_BASE}/api/friends/virtual-rivals?versionNum=${versionNum}&prefectureFileNum=${prefectureFileNum}`;
        const res = await fetch(url, {
            method: 'DELETE',
            headers: authHeaders()
        });
        if (!res.ok) {
            const data = await res.json().catch(() => ({}));
            throw new Error(data.message || 'ライバル解除に失敗しました');
        }
    };

    /**
     * Web Push 購読情報をサーバに登録する。
     * サーバは保存された購読を使ってプッシュ通知を送信する。
     */
    const updatePushSubscription = async (subscription: string) => {
        try {
            const res = await fetch(`${API_BASE}/api/friends/push-subscription`, {
                method: 'POST',
                headers: authHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify({ subscription })
            });
            // 保存に失敗したままだとサーバーに購読が残らず、以後まったく通知が届かない。
            // 呼び出し元では握り潰すが、原因追跡できるようログには必ず出す。
            if (!res.ok) {
                console.error('Push subscription save failed', res.status);
                return false;
            }
            return true;
        } catch (e: any) {
            console.error('Push subscription failed', e);
            return false;
        }
    };

    /** デバッグ / 動作確認用のテストプッシュ送信をサーバに依頼する。 */
    const sendTestNotification = async () => {
        try {
            const res = await fetch(`${API_BASE}/api/friends/push-test`, {
                method: 'POST',
                headers: authHeaders()
            });
            if (!res.ok) {
                let msg = 'テスト送信に失敗しました (' + res.status + ')';
                try {
                    const data = await res.json();
                    msg = data.error || data.message || msg;
                } catch (e) {}
                throw new Error(msg);
            }
        } catch (e: any) {
            throw e;
        }
    };

    /**
     * Web Push の公開鍵（base64url）を `Uint8Array` に変換する。
     *
     * `pushManager.subscribe()` の `applicationServerKey` が Uint8Array を要求するため、
     * VAPID 公開鍵をデコードしてバイト列化する必要がある。
     */
    const urlBase64ToUint8Array = (base64String: string) => {
        // base64url → base64 の変換と、4 文字単位へのパディング復元
        const padding = '='.repeat((4 - base64String.length % 4) % 4);
        const base64 = (base64String + padding)
            .replace(/\-/g, '+')
            .replace(/_/g, '/');
        const rawData = window.atob(base64);
        const outputArray = new Uint8Array(rawData.length);
        for (let i = 0; i < rawData.length; ++i) {
            outputArray[i] = rawData.charCodeAt(i);
        }
        return outputArray;
    };

    /** `ArrayBuffer`（既存購読の applicationServerKey）を base64url 文字列に戻す。 */
    const arrayBufferToUrlBase64 = (buffer: ArrayBuffer) => {
        let binary = '';
        const bytes = new Uint8Array(buffer);
        for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
        return window.btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    };

    /**
     * ブラウザ通知の稼働状態をサーバーから取得する。
     *
     * 「通知が来ない」の原因がサーバー側（VAPID 鍵未設定）かクライアント側（未許可・未購読）かを
     * 切り分けるために使う。取得結果は `pushStatus` にも保持してプロフィール画面が参照する。
     *
     * @returns 状態。取得に失敗した場合は null
     */
    const fetchPushStatus = async (): Promise<PushStatus | null> => {
        try {
            const res = await fetch(`${API_BASE}/api/notifications/push-status`, { headers: authHeaders() });
            if (!res.ok) return null;
            const data = await res.json();
            pushStatus.value = {
                serverEnabled: !!data.serverEnabled,
                publicKey: data.publicKey ?? '',
                subscribed: !!data.subscribed
            };
            return pushStatus.value;
        } catch {
            return null;
        }
    };

    /**
     * Service Worker の有効化を待つ。
     *
     * `navigator.serviceWorker.ready` は「登録が 1 つも無い」場合に永久に解決しない。
     * index.html の登録が何らかの理由で失敗していると購読処理がここで無言のまま止まり、
     * 「通知を有効にする」を押しても何も起きない状態になるため、自前で登録を試み、
     * タイムアウトも付けて必ず結果を返す。
     *
     * @returns 有効化済みの登録。取得できなければ null
     */
    const ensureServiceWorker = async (): Promise<ServiceWorkerRegistration | null> => {
        if (!('serviceWorker' in navigator)) return null;
        try {
            // index.html でも登録しているが、register は同一スクリプトなら冪等なので二重呼び出しで問題ない。
            await navigator.serviceWorker.register('/sw.js');
        } catch (e) {
            console.error('Service Worker の登録に失敗しました', e);
        }
        const timeout = new Promise<null>(resolve => setTimeout(() => resolve(null), 10000));
        return await Promise.race([navigator.serviceWorker.ready, timeout]);
    };

    /**
     * 許可済み（`Notification.permission === 'granted'`）を前提に、購読を作って（または作り直して）
     * サーバーへ保存する。ダイアログは一切出さない。
     *
     * 手順:
     *  1. Service Worker の有効化を待つ
     *  2. サーバーが配る VAPID 公開鍵を取得する（フロントに鍵は持たない）
     *  3. 既存購読が別の公開鍵で作られていれば unsubscribe してから購読し直す
     *  4. 購読情報をサーバに保存（`updatePushSubscription`）
     *
     * 手順 3 が無いと、鍵をローテーションしたときに `subscribe()` が InvalidStateError で
     * 失敗し続け、以後そのブラウザには通知が一切届かなくなる（古い購読が残ったまま）。
     *
     * @returns 購読までできたら `true`
     */
    const subscribeAndSave = async () => {
        if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
            console.error('This browser does not support Web Push');
            return false;
        }

        try {
            const registration = await ensureServiceWorker();
            if (!registration) {
                console.error('Service Worker が有効になりませんでした');
                return false;
            }

            // VAPID 公開鍵はサーバーが配る値を正とする（鍵ローテーション時のズレを防ぐ）。
            // 取得できないときだけビルド時の env にフォールバックする。
            // 鍵をここにハードコードしてはいけない: サーバーで鍵を差し替えたときに古い鍵で
            // 購読を作ってしまい、「購読済みなのに一通も届かない」状態が固定化する。
            const status = await fetchPushStatus();
            const vapidPublicKey = status?.publicKey || import.meta.env.VITE_VAPID_PUBLIC_KEY || '';
            if (!vapidPublicKey) {
                // 鍵が分からない状態で購読しても無駄なので、あえて何もしない。
                // サーバー側が未設定（serverEnabled=false）なら UI がその旨を表示する。
                console.error('VAPID 公開鍵を取得できないため購読をスキップしました');
                return false;
            }

            // 既存購読が今の公開鍵と別の鍵で作られていたら、作り直さないと送信できない。
            const existing = await registration.pushManager.getSubscription();
            if (existing) {
                const existingKey = existing.options?.applicationServerKey;
                const sameKey = existingKey
                    ? arrayBufferToUrlBase64(existingKey) === vapidPublicKey
                    : false;
                if (!sameKey) {
                    console.warn('VAPID 公開鍵が変わっているため購読を作り直します');
                    await existing.unsubscribe().catch(() => undefined);
                }
            }

            const subscription = await registration.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: urlBase64ToUint8Array(vapidPublicKey)
            });
            // 毎回保存し直す。ブラウザ側で購読が作り直された場合や、
            // 管理者の「Push通知リセット」でサーバー側が空になった場合もこれで復旧する。
            await updatePushSubscription(JSON.stringify(subscription));
            if (pushStatus.value) pushStatus.value.subscribed = true;
            return true;
        } catch (e) {
            console.error('Failed to subscribe to push notifications', e);
            return false;
        }
    };

    /**
     * 既に許可済みの端末だけ、購読を最新の状態に貼り直す。**ダイアログは絶対に出さない。**
     *
     * アプリ起動時・ログイン時に呼ぶ想定。鍵をローテーションした後や、ブラウザ側で購読が
     * 作り直された後の復旧はこの経路で自動的に行われる。
     *
     * 未回答（'default'）のユーザーに起動時いきなり許可を求めない（求めるのは
     * {@link requestNotificationPermission} を呼ぶ画面側の責務）。
     *
     * @returns 購読を保存できたら `true`
     */
    const syncPushSubscription = async () => {
        if (!('Notification' in window)) return false;
        if (Notification.permission !== 'granted') return false;
        return await subscribeAndSave();
    };

    /**
     * ブラウザに Web Push の許可を求め、許可されれば購読登録する。
     *
     * **ユーザー操作（ボタン押下）から呼ぶこと。** Safari（iOS のホーム画面アプリを含む）は
     * ユーザー操作起点でない `requestPermission()` を拒否する。
     *
     * 許可済みの場合はダイアログを出さずに購読の貼り直しだけ行う。
     * 拒否済みの場合はブラウザが再要求を禁止しているので、何もせず false を返す。
     *
     * @returns 購読までできたら `true`
     */
    const requestNotificationPermission = async () => {
        if (!('Notification' in window)) {
            console.error('Notifications not supported');
            return false;
        }

        let permission = Notification.permission;
        if (permission === 'default') {
            try {
                permission = await Notification.requestPermission();
            } catch (e) {
                console.warn('通知の許可要求はユーザー操作から行う必要があります', e);
                return false;
            }
        }
        if (permission !== 'granted') return false;
        return await subscribeAndSave();
    };

    /**
     * アプリ内通知一覧と未読件数を取得する。
     * 失敗時は静かに無視（UI に不要な赤文字を出さない意図）。
     */
    const fetchAppNotifications = async () => {
        try {
            const res = await fetch(`${API_BASE}/api/notifications`, { headers: authHeaders() });
            if (!res.ok) return;
            const data = await res.json();
            appNotifications.value = data.notifications ?? [];
            appUnreadCount.value = data.unreadCount ?? 0;
        } catch {
            // 静かに失敗
        }
    };

    /**
     * すべての通知を既読にする。サーバ反映と同時にローカル state も即更新して
     * UI のレスポンス性を上げている（楽観的更新）。
     */
    const markAllNotificationsRead = async () => {
        try {
            await fetch(`${API_BASE}/api/notifications/read-all`, {
                method: 'POST',
                headers: authHeaders()
            });
            appNotifications.value = appNotifications.value.map(n => ({ ...n, read: true }));
            appUnreadCount.value = 0;
        } catch {
            // 静かに失敗
        }
    };

    return {
        /** フレンド一覧（コンポーネントローカル state）。 */
        friends,
        /** 自分宛フレンド申請リスト（全コンポーネント共有）。 */
        pendingRequests,
        /** アプリ内通知リスト（全コンポーネント共有）。 */
        appNotifications,
        /** 未読通知件数（全コンポーネント共有）。 */
        appUnreadCount,
        /** フェッチ中フラグ。 */
        isLoading,
        /** エラーメッセージ。 */
        error,
        /** フレンド一覧取得。 */
        fetchFriends,
        /** ユーザー検索。 */
        searchUsers,
        /** フレンド申請送信。 */
        sendFriendRequest,
        /** 受信フレンド申請の取得。 */
        fetchPendingRequests,
        /** フレンド申請承認。 */
        acceptRequest,
        /** フレンド申請拒否。 */
        rejectRequest,
        /** フレンド解除。 */
        removeFriend,
        /** Web Push 購読情報のサーバ保存。 */
        updatePushSubscription,
        /** テストプッシュ送信。 */
        sendTestNotification,
        /** フレンドスコア取得。 */
        fetchFriendScores,
        /** ブラウザに通知許可を求めて購読する高レベル関数（ユーザー操作から呼ぶこと）。 */
        requestNotificationPermission,
        /** 許可済み端末の購読だけを黙って貼り直す（起動時用・ダイアログを出さない）。 */
        syncPushSubscription,
        /** ブラウザ通知の稼働状態（サーバー側の有効/無効・購読の有無）。 */
        pushStatus,
        /** 上記をサーバーから取得する。 */
        fetchPushStatus,
        /** アプリ内通知一覧・未読件数取得。 */
        fetchAppNotifications,
        /** 全通知を既読化。 */
        markAllNotificationsRead,
        /** バーチャルライバル一覧取得。 */
        fetchVirtualRivals,
        /** 特定バージョン・都道府県の登録状態確認。 */
        fetchVirtualRivalStatus,
        /** バーチャルライバル登録。 */
        addVirtualRival,
        /** バーチャルライバル解除。 */
        removeVirtualRival,
    };
}
