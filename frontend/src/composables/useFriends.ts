import { ref } from 'vue';

export interface Friend {
    id: number;
    displayName: string;
    iidxId: string;
    lastUploadedAt: string | null;
    totalBeatPt: number;
    isFriend?: boolean;
    hasSentRequest?: boolean;
}

export interface PendingRequest {
    id: number;
    senderId: number;
    senderName: string;
    senderIidxId: string;
    createdAt: string;
}

export function useFriends() {
    const friends = ref<Friend[]>([]);
    const pendingRequests = ref<PendingRequest[]>([]);
    const isLoading = ref(false);
    const error = ref<string | null>(null);

    const fetchFriends = async () => {
        isLoading.value = true;
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('/api/friends', {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (!res.ok) throw new Error('Failed to fetch friends');
            friends.value = await res.ok ? await res.json() : [];
        } catch (e: any) {
            error.value = e.message;
        } finally {
            isLoading.value = false;
        }
    };

    const searchUsers = async (query: string): Promise<Friend[]> => {
        isLoading.value = true;
        try {
            const token = localStorage.getItem('token');
            const res = await fetch(`/api/friends/search?query=${encodeURIComponent(query)}`, {
                headers: { 'Authorization': `Bearer ${token}` }
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

    const sendFriendRequest = async (receiverId: number) => {
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('/api/friends/request', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`
                },
                body: JSON.stringify({ receiverId })
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
            const token = localStorage.getItem('token');
            const res = await fetch('/api/friends/requests/pending', {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (!res.ok) throw new Error('Failed to fetch requests');
            pendingRequests.value = await res.json();
        } catch (e: any) {
            error.value = e.message;
        }
    };

    const acceptRequest = async (requestId: number) => {
        try {
            const token = localStorage.getItem('token');
            const res = await fetch(`/api/friends/requests/${requestId}/accept`, {
                method: 'POST',
                headers: { 'Authorization': `Bearer ${token}` }
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
            const token = localStorage.getItem('token');
            const res = await fetch(`/api/friends/requests/${requestId}/reject`, {
                method: 'POST',
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (!res.ok) throw new Error('Failed to reject');
            await fetchPendingRequests();
        } catch (e: any) {
            error.value = e.message;
            throw e;
        }
    };

    const updatePushSubscription = async (subscription: string) => {
        try {
            const token = localStorage.getItem('token');
            await fetch('/api/friends/push-subscription', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`
                },
                body: JSON.stringify({ subscription })
            });
        } catch (e: any) {
            console.error('Push subscription failed', e);
        }
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
        updatePushSubscription
    };
}
