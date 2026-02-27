import { ref, onMounted, computed } from 'vue';

export interface AuthUser {
    id: number;
    displayName: string;
    email: string;
    avatarUrl: string;
    iidxId: string | null;
    danRank: string | null;
    arenaRank: string | null;
}

const user = ref<AuthUser | null>(null);
const isLoading = ref(true);

// VITE_API_BASE should be explicitly set to 'http://localhost:8080' in local dev.
// In production, leaves it empty so it targets '/' (triggering the Render Rewrite).
const API_BASE = import.meta.env.VITE_API_BASE ?? '';

async function fetchCurrentUser(): Promise<void> {
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
    onMounted(() => {
        if (isLoading.value) {
            fetchCurrentUser();
        }
    });

    const login = () => {
        window.location.href = `${API_BASE}/oauth2/authorization/google`;
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
        user,
        isLoading,
        isLoggedIn,
        login,
        logout,
        refresh: fetchCurrentUser,
    };
}
