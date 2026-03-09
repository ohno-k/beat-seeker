import { ref } from 'vue';
import { useAuth, API_BASE } from './useAuth';

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

export interface PendingRequest {
    id: number;
    senderId: number;
    senderName: string;
    senderIidxId: string;
    message: string | null;
    createdAt: string;
}

export function useFriends() {
    const { authHeaders } = useAuth();
    const friends = ref<Friend[]>([]);
    const pendingRequests = ref<PendingRequest[]>([]);
    const isLoading = ref(false);
    const error = ref<string | null>(null);

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

    const urlBase64ToUint8Array = (base64String: string) => {
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

    const requestNotificationPermission = async () => {
        if (!('Notification' in window)) {
            console.error('Notifications not supported');
            return;
        }

        const permission = await Notification.requestPermission();
        if (permission === 'granted' && 'serviceWorker' in navigator) {
            try {
                const registration = await navigator.serviceWorker.ready;
                const vapidPublicKey = 'BDGlranXpFZQs_QO3pNXvrNudlAgliWJFOILQZxXd8_kjGZyRqEJQtJWN6Jymd5PnlFe3ITpTBgRt8v6dLcXIvE';
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

    return {
        friends,
        pendingRequests,
        isLoading,
        error,
        fetchFriends,
        searchUsers,
        sendFriendRequest,
        fetchPendingRequests,
        acceptRequest,
        rejectRequest,
        removeFriend,
        updatePushSubscription,
        fetchFriendScores,
        requestNotificationPermission
    };
}
