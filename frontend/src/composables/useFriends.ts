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
            await fetch(`${API_BASE}/api/friends/push-subscription`, {
                method: 'POST',
                headers: authHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify({ subscription })
            });
        } catch (e: any) {
            console.error('Push subscription failed', e);
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

    /**
     * ブラウザに Web Push の許可を求め、許可されればサービスワーカーに購読登録する。
     *
     * 手順:
     *  1. Notification API が存在するか確認
     *  2. ユーザーに許可を求める（ネイティブダイアログ）
     *  3. 許可が下りたら SW 登録を待ち、VAPID 公開鍵で pushManager.subscribe
     *  4. 購読情報をサーバに保存（`updatePushSubscription`）
     *
     * @returns 許可が下りたなら `true`
     */
    const requestNotificationPermission = async () => {
        if (!('Notification' in window)) {
            console.error('Notifications not supported');
            return;
        }

        const permission = await Notification.requestPermission();
        if (permission === 'granted' && 'serviceWorker' in navigator) {
            try {
                const registration = await navigator.serviceWorker.ready;
                // VAPID 公開鍵。秘密鍵はサーバ側で保管。
                // 環境変数 VITE_VAPID_PUBLIC_KEY で上書き可能。未設定時は従来のハードコード値を使う
                //（.env を用意しなくても従来どおり動作する）。
                const vapidPublicKey = import.meta.env.VITE_VAPID_PUBLIC_KEY
                    ?? 'BK8nOI89kHqMXjG1Pz5MiOLMc7lX8zjgd-gd3KhfRfr3mD_pt_VgRBFPzPRvmPoDhz06o82fBbBmVLATrotGB0k';
                const convertedVapidKey = urlBase64ToUint8Array(vapidPublicKey);

                const subscription = await registration.pushManager.subscribe({
                    userVisibleOnly: true,
                    applicationServerKey: convertedVapidKey
                });
                console.log('Push Subscription successful');
                await updatePushSubscription(JSON.stringify(subscription));
                return true;
            } catch (e) {
                console.error('Failed to subscribe to push notifications', e);
                return false;
            }
        }
        return permission === 'granted';
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
        /** ブラウザに通知許可を求めて購読する高レベル関数。 */
        requestNotificationPermission,
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
