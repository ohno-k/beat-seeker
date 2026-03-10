import { ref, computed, readonly } from 'vue';

export interface AuthUser {
    id: number;
    displayName: string;
    iidxId: string;
    danRank: string;
    arenaRank: string;
    playSide: string;
    privacyLevel: number;
    lastUploadedAt: string | null;
}

const user = ref<AuthUser | null>(null);
const isLoading = ref(true);

const TOKEN_KEY = 'beat-seeker-token';

// VITE_API_BASE should be explicitly configured in Render environment variables
export const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

function getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
}

function setToken(token: string) {
    localStorage.setItem(TOKEN_KEY, token);
}

function removeToken() {
    localStorage.removeItem(TOKEN_KEY);
}

/** Build standard headers. Include Authorization if a JWT is stored. */
function authHeaders(extra?: Record<string, string>): Record<string, string> {
    const headers: Record<string, string> = { ...extra };
    const token = getToken();
    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }
    return headers;
}

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
            user.value = await res.json();
        } else {
            user.value = null;
            removeToken(); // Token is invalid / expired
        }
    } catch {
        user.value = null;
    } finally {
        isLoading.value = false;
    }
}

export function useAuth() {
    if (isLoading.value && user.value === null) {
        fetchCurrentUser();
    }

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

    const logout = async () => {
        removeToken();
        user.value = null;
        window.location.href = '/';
    };

    const isLoggedIn = computed(() => !!user.value);

    return {
        user: readonly(user),
        isLoggedIn,
        isLoading: readonly(isLoading),
        login,
        logout,
        registerUser,
        updateProfile,
        fetchCurrentUser,
        authHeaders,
    };
}
