import { ref } from 'vue';
import { API_BASE } from './constants';
import { useAuth } from './useAuth';

/**
 * 【型】 外部 API トークンの 1 件分（一覧表示用）。
 *
 * `plainToken` は発行直後のレスポンスにのみ含まれる。一覧 GET では返らない。
 */
export interface IntegrationTokenInfo {
    id: number;
    name: string | null;
    partner: string | null;
    /** 表示用の末尾識別子（例: "8aF2"）。本物のトークンではない。 */
    tokenPrefix: string | null;
    expiresAt: string | null;
    revokedAt: string | null;
    lastUsedAt: string | null;
    createdAt: string;
    active: boolean;
    /** 発行直後のみ含まれる平文トークン。一覧 GET では undefined。 */
    plainToken?: string;
}

/** 発行モーダルの「期限」プルダウン値。 */
export type IntegrationExpiresIn = '30d' | '90d' | '1y' | 'unlimited';

/**
 * 【Composable の役割】 外部 API トークンの発行・一覧・失効 API を一括提供する。
 *
 * すべて要ログイン API（`/api/integrations/tokens`）を叩く。
 * 発行された平文トークンは「1 回だけ」レスポンスに含まれ、以降の取得手段は無い。
 */
export function useIntegrationTokens() {
    const { authHeaders } = useAuth();
    const isLoading = ref(false);

    /** 自分が発行したトークン一覧を取得（平文は含まれない）。 */
    const listTokens = async (): Promise<IntegrationTokenInfo[]> => {
        isLoading.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/integrations/tokens`, { headers: authHeaders() });
            if (!res.ok) return [];
            return await res.json();
        } finally {
            isLoading.value = false;
        }
    };

    /**
     * 新しいトークンを発行する。レスポンスには `plainToken` が含まれる。
     * 平文は二度と取得できないため、呼び出し側で必ずユーザーに表示・コピーさせること。
     */
    const issueToken = async (params: {
        name?: string | null;
        partner?: string | null;
        expiresIn: IntegrationExpiresIn;
    }): Promise<IntegrationTokenInfo> => {
        isLoading.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/integrations/tokens`, {
                method: 'POST',
                headers: authHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify({
                    name: params.name ?? null,
                    partner: params.partner ?? null,
                    expiresIn: params.expiresIn,
                }),
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

    /** トークンをソフト失効させる（DB レコードは保持）。 */
    const revokeToken = async (id: number): Promise<void> => {
        isLoading.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/integrations/tokens/${id}`, {
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

    /** トークンを DB から完全削除する。アクティブな場合はサーバが 400 を返す。 */
    const deleteToken = async (id: number): Promise<void> => {
        isLoading.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/integrations/tokens/${id}/permanent`, {
                method: 'DELETE',
                headers: authHeaders(),
            });
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                throw new Error(body.error || `削除に失敗しました (status=${res.status})`);
            }
        } finally {
            isLoading.value = false;
        }
    };

    return {
        isLoading,
        listTokens,
        issueToken,
        revokeToken,
        deleteToken,
    };
}
