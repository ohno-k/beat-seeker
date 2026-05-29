import { ref, computed, readonly } from 'vue';
import { currentLang } from './useI18n';
import { showRateTierRef } from './useRateTierVisibility';
import { showKenbanSaraTierRef } from './useKenbanSaraTierVisibility';
import { TOKEN_KEY, API_BASE } from './constants';

// 他の composable でも `API_BASE` を使うため再エクスポート（利便性のため）。
export { API_BASE };

/**
 * ログイン中ユーザーのプロフィール情報。
 *
 *  - `iidxId`: IIDX 本体のプレイヤー ID（5 桁 + ハイフン + 4 桁）
 *  - `privacyLevel`: 他ユーザーへの公開範囲（数値）
 *  - `isSupporter` / `showSupporterBorder` / `supporterToken`: サポーター機能関連
 */
export interface AuthUser {
    id: number;
    displayName: string;
    iidxId: string;
    danRank: string;
    arenaRank: string;
    playSide: string;
    privacyLevel: number;
    language: string;
    showRateTier: boolean;
    showKenbanSaraTier: boolean;
    isSupporter: boolean;
    showSupporterBorder: boolean;
    supporterToken: string;
    lastUploadedAt: string | null;
    email: string;
    /** アーケード（CSV）由来スコアを UI に表示するか。 */
    showArcadeScores: boolean;
    /** INFINITAS 画面取得由来スコアを UI に表示するか。 */
    showInfinitasScores: boolean;
}

/**
 * 現在ログイン中のユーザー情報（未ログインなら `null`）。
 *
 * モジュールトップに置くことで、アプリ全体で同じ ref を参照する（シングルトン）。
 */
const user = ref<AuthUser | null>(null);
/**
 * 認証情報の取得中フラグ。
 * 初期値は `true`（起動直後は /api/auth/me を叩いて解決待ち）。
 */
const isLoading = ref(true);

/** localStorage から JWT を取り出す。 */
function getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
}

/** JWT を localStorage に保存する。 */
function setToken(token: string) {
    localStorage.setItem(TOKEN_KEY, token);
}

/** JWT を localStorage から削除する（ログアウト・トークン失効時）。 */
function removeToken() {
    localStorage.removeItem(TOKEN_KEY);
}

/**
 * 認証付きリクエスト用の共通ヘッダを生成する。
 * 保存済み JWT があれば `Authorization: Bearer ...` を自動付与する。
 *
 * @param extra 追加で付けたいヘッダ（例: `{ 'Content-Type': 'application/json' }`）
 */
function authHeaders(extra?: Record<string, string>): Record<string, string> {
    const headers: Record<string, string> = { ...extra };
    const token = getToken();
    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }
    return headers;
}

/**
 * 現在のログインユーザー情報をサーバから取得し、グローバル state に反映する。
 *
 * 手順:
 *  1. トークン無しなら即 `user = null` でローディング解除
 *  2. `/api/auth/me` を叩く
 *  3. 成功なら user にセット + 言語・Rate-Tier 表示設定も同期
 *  4. 失敗（401 等）ならトークン削除（期限切れ / 不正扱い）
 */
async function fetchCurrentUser(): Promise<void> {
    isLoading.value = true;
    const token = getToken();
    if (!token) {
        user.value = null;
        isLoading.value = false;
        return;
    }
    try {
        const res = await fetch(`${API_BASE}/api/auth/me`, {
            headers: authHeaders(),
        });
        if (res.ok) {
            const data = await res.json();
            user.value = data;
            // サーバ側に保存された言語設定があればクライアントを合わせる（別端末ログイン時の復元）
            if (data.language) {
                currentLang.value = data.language;
                localStorage.setItem('beat-seeker-lang', data.language);
                document.documentElement.lang = data.language;
            }
            // Rate-Tier 表示設定も同期
            if (data.showRateTier !== undefined) {
                showRateTierRef.value = data.showRateTier;
                localStorage.setItem('showRateTier', String(data.showRateTier));
            }
            // KENBAN/SARA-Tier 表示設定も同期（サポーター限定機能）
            if (data.showKenbanSaraTier !== undefined) {
                showKenbanSaraTierRef.value = data.showKenbanSaraTier;
                localStorage.setItem('showKenbanSaraTier', String(data.showKenbanSaraTier));
            }
        } else {
            user.value = null;
            removeToken(); // トークンが無効 / 期限切れなので除去
        }
    } catch {
        // ネットワークエラー時はユーザー情報をクリア（楽観的更新は避ける）
        user.value = null;
    } finally {
        isLoading.value = false;
    }
}

/**
 * 【Composable の役割】 認証まわり（ログイン・登録・プロフィール更新・パスワードリセット）を一括提供する。
 *
 * 機能:
 *  - 初回呼び出し時に自動で `/api/auth/me` を叩いてユーザー情報を復元
 *  - 各 API を薄くラップした非同期関数を公開
 *
 * 使い方:
 * ```ts
 * const { user, isLoggedIn, login, logout } = useAuth();
 * await login('1234-5678', 'password');
 * ```
 */
export function useAuth() {
    // 初回呼び出し時（user が未取得でまだロード中）にだけ fetch する。
    // 複数コンポーネントから並行で呼ばれても、isLoading ガードで 1 回に抑制。
    if (isLoading.value && user.value === null) {
        fetchCurrentUser();
    }

    /**
     * ログイン API を叩き、返却された JWT を保存してユーザー情報を再取得する。
     * @throws ログイン失敗時にサーバの message を含む Error
     */
    const login = async (iidxId: string, password: string): Promise<void> => {
        const res = await fetch(`${API_BASE}/api/auth/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ iidxId, password })
        });

        if (!res.ok) {
            const errorData = await res.json().catch(() => ({}));
            throw new Error(errorData.message || 'ログインに失敗しました。');
        }
        const data = await res.json();
        setToken(data.token);
        await fetchCurrentUser();
    };

    /**
     * 新規ユーザー登録。成功時は自動でログイン状態になる（サーバが JWT を返す）。
     * @param payload iidxId, password, email などを含むオブジェクト
     */
    const registerUser = async (payload: any): Promise<void> => {
        const res = await fetch(`${API_BASE}/api/auth/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        if (!res.ok) {
            const errorData = await res.json().catch(() => ({}));
            throw new Error(errorData.message || 'ユーザー登録に失敗しました。');
        }
        const data = await res.json();
        setToken(data.token);
        await fetchCurrentUser();
    };

    /**
     * プロフィール更新 API。更新後は `fetchCurrentUser` で最新情報を取り直す。
     */
    const updateProfile = async (payload: any): Promise<void> => {
        const res = await fetch(`${API_BASE}/api/auth/me/profile`, {
            method: 'PUT',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(payload)
        });

        if (!res.ok) {
            const errorData = await res.json().catch(() => ({}));
            throw new Error(errorData.message || 'プロフィールの更新に失敗しました。');
        }
        await fetchCurrentUser();
    };

    /**
     * ログアウト。トークンを削除し、トップへリダイレクトする。
     * （SPA の再初期化を兼ねて `window.location.href` での遷移を採用）
     */
    const logout = async () => {
        removeToken();
        user.value = null;
        window.location.href = '/';
    };

    /**
     * パスワード忘れリクエスト。サーバがメール送信を実施する。
     * @returns サーバからのメッセージ（例: 「メールを送信しました」）
     */
    const forgotPassword = async (iidxId: string, email: string): Promise<string> => {
        const res = await fetch(`${API_BASE}/api/auth/forgot-password`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ iidxId, email })
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.message || 'リクエストに失敗しました。');
        return data.message as string;
    };

    /**
     * メール添付リンクから受け取った `token` と新パスワードで再設定する。
     */
    const resetPassword = async (token: string, newPassword: string): Promise<string> => {
        const res = await fetch(`${API_BASE}/api/auth/reset-password`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token, newPassword })
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.message || 'リセットに失敗しました。');
        return data.message as string;
    };

    /** ログイン状態を computed で表現（テンプレート `v-if` で使いやすいよう）。 */
    const isLoggedIn = computed(() => !!user.value);

    return {
        /** ログイン中ユーザー。readonly で公開して不用意な書き換えを防ぐ。 */
        user: readonly(user),
        /** ログイン状態の computed。 */
        isLoggedIn,
        /** 起動時ユーザー情報取得中フラグ。 */
        isLoading: readonly(isLoading),
        /** ログイン関数。 */
        login,
        /** ログアウト関数（リダイレクト付き）。 */
        logout,
        /** ユーザー登録関数。 */
        registerUser,
        /** プロフィール更新関数。 */
        updateProfile,
        /** 任意のタイミングで最新ユーザー情報を取り直す関数。 */
        fetchCurrentUser,
        /** 認証ヘッダ生成関数。ほかの composable の fetch でも共通利用。 */
        authHeaders,
        /** パスワード忘れリクエスト。 */
        forgotPassword,
        /** パスワード再設定（トークン経由）。 */
        resetPassword,
    };
}
