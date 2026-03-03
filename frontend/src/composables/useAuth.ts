import { ref, computed, readonly } from 'vue';

export interface AuthUser {
    id: number;
    displayName: string;
    iidxId: string;
    danRank: string;
    arenaRank: string;
}

const user = ref<AuthUser | null>(null);
const isLoading = ref(true);

// VITE_API_BASE should be explicitly configured in Render environment variables
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

async function fetchCurrentUser(): Promise<void> {
    isLoading.value = true;
    try {
        const res = await fetch(`${API_BASE}/api/auth/me`, {
            credentials: 'include', // Send session cookie
        });
        if (res.ok) {
            user.value = await res.json();
        } else {
            user.value = null;
        }
    } catch {
        user.value = null;
    } finally {
        isLoading.value = false;
    }
}

export function useAuth() {
    // Note: To avoid multiple fetches on mount across components, we could do this once in App.vue, 
    // but doing it here with a simple check is ok for now.
    if (isLoading.value && user.value === null) {
        fetchCurrentUser();
    }

    const login = async (iidxId: string, password: string): Promise<void> => {
        const res = await fetch(`${API_BASE}/api/auth/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify({ iidxId, password })
        });

        if (!res.ok) {
            const data = await res.json().catch(() => ({}));
            throw new Error(data.message || 'ログインに失敗しました。');
        }
        await fetchCurrentUser();
    };

    const registerUser = async (payload: any): Promise<void> => {
        const res = await fetch(`${API_BASE}/api/auth/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify(payload)
        });

        if (!res.ok) {
            const data = await res.json().catch(() => ({}));
            throw new Error(data.message || '登録に失敗しました。');
        }
        await fetchCurrentUser();
    };

    const logout = async () => {
        try {
            await fetch(`${API_BASE}/api/auth/logout`, {
                method: 'POST',
                credentials: 'include',
            });
        } catch (e) {
            console.error('Logout failed:', e);
        } finally {
            user.value = null;
            // Force a reload to clear any residual state/cache
            window.location.href = '/';
        }
    };

    const isLoggedIn = computed(() => !!user.value);

    return {
        user: readonly(user),
        isLoading: readonly(isLoading),
        isLoggedIn,
        login,
        registerUser,
        logout,
        refresh: fetchCurrentUser,
    };
}
