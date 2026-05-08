import { ref } from 'vue';
import { API_BASE } from './constants';
import { useAuth } from './useAuth';

/**
 * 【型】 共有トークンの 1 件分。
 *
 * `active` はサーバ側で「未失効 かつ 期限内」を判定した結果。
 */
export interface ShareTokenInfo {
    id: number;
    token: string;
    scopeDashboard: boolean;
    scopeScores: boolean;
    scopeHistory: boolean;
    scopeProfile: boolean;
    expiresAt: string | null;
    revokedAt: string | null;
    createdAt: string;
    active: boolean;
}

export interface ShareScopes {
    scopeDashboard: boolean;
    scopeScores: boolean;
    scopeHistory: boolean;
    scopeProfile: boolean;
}

/** 発行モーダルの「期限」プルダウン値。 */
export type ShareExpiresIn = '24h' | '1w' | '1m' | 'unlimited';

/**
 * 【Composable の役割】 共有トークンの発行・一覧・失効 API を一括提供する。
 *
 * すべて要ログイン API（`/api/share/tokens`）を叩く。
 * 公開ビュー側（`/api/share/{token}/...`）はこの composable を介さず直接 fetch する。
 */
export function useShareTokens() {
    const { authHeaders } = useAuth();
    const isLoading = ref(false);

    /** 自分が発行した共有トークン一覧を取得。 */
    const listTokens = async (): Promise<ShareTokenInfo[]> => {
        isLoading.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/share/tokens`, { headers: authHeaders() });
            if (!res.ok) return [];
            return await res.json();
        } finally {
            isLoading.value = false;
        }
    };

    /**
     * 新しい共有トークンを発行する。
     *
     * @param scopes    公開範囲フラグ群（ダッシュボード／スコア一覧／成長記録／プロフィール）
     * @param expiresIn 有効期限指定
     */
    const issueToken = async (
        scopes: ShareScopes,
        expiresIn: ShareExpiresIn,
    ): Promise<ShareTokenInfo> => {
        isLoading.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/share/tokens`, {
                method: 'POST',
                headers: authHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify({ ...scopes, expiresIn }),
            });
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                throw new Error(body.error || `発行に失敗しました (status=${res.status})`);
            }
            return await res.json();
        } finally {
            isLoading.value = false;
        }
    };

    /** 共有トークンを失効させる。 */
    const revokeToken = async (id: number): Promise<void> => {
        isLoading.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/share/tokens/${id}`, {
                method: 'DELETE',
                headers: authHeaders(),
            });
            if (!res.ok) {
                throw new Error(`失効に失敗しました (status=${res.status})`);
            }
        } finally {
            isLoading.value = false;
        }
    };

    /**
     * トークン文字列から共有 URL を組み立てる。
     *
     * SPA ルーター上の `/share/:token` を直接踏ませる前提。
     */
    const buildShareUrl = (token: string): string => {
        return `${window.location.origin}/share/${token}`;
    };

    return {
        isLoading,
        listTokens,
        issueToken,
        revokeToken,
        buildShareUrl,
    };
}
