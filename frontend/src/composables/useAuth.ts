import { ref, onMounted, computed } from 'vue';

export interface AuthUser {
    id: number;
    displayName: string;
    email: string;
    avatarUrl: string;
}

const user = ref<AuthUser | null>(null);
const isLoading = ref(true);

const API_BASE = 'http://localhost:8080';

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
        await fetch(`${API_BASE}/api/auth/logout`, {
            method: 'POST',
            credentials: 'include',
        });
        user.value = null;
    };

    return {
        user,
        isLoading,
        isLoggedIn: () => !!user.value,
        login,
        logout,
        refresh: fetchCurrentUser,
    };
}
