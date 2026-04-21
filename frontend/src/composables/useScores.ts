import { ref } from 'vue';
import type { ScoreData, DifficultyStats } from '../types/ScoreData';
import { useAuth } from './useAuth';

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

export function useScores() {
    const isFetching = ref(false);
    const { authHeaders } = useAuth();

    const fetchMyScores = async (): Promise<ScoreData[]> => {
        isFetching.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/scores/me`, {
                headers: authHeaders()
            });

            if (!res.ok) {
                if (res.status === 401) {
                    return [];
                }
                throw new Error(`Fetch failed: ${res.status}`);
            }

            const flatScores = await res.json();

            // Group by title
            const grouped = new Map<string, any>();

            const emptyDiff = (): DifficultyStats => ({
                difficulty: null,
                score: 0,
                pgreat: 0,
                great: 0,
                missCount: null,
                clearType: 'NO PLAY',
                djLevel: '---',
                memo: undefined,
                id: undefined
            });

            flatScores.forEach((s: any) => {
                const title = s.title;
                if (!grouped.has(title)) {
                    grouped.set(title, {
                        version: '0',
                        title: title,
                        genre: s.genre || '',
                        artist: s.artist || '',
                        playCount: s.playCount || 0,
                        lastPlayTime: '',
                        beginner: emptyDiff(),
                        normal: emptyDiff(),
                        hyper: emptyDiff(),
                        another: emptyDiff(),
                        leggendaria: emptyDiff(),
                    });
                }

                const entry = grouped.get(title);
                const diffKey = s.difficultyName.toLowerCase() as keyof ScoreData;

                if (entry[diffKey]) {
                    entry[diffKey] = {
                        id: s.id,
                        difficulty: s.difficultyLevel,
                        score: s.score,
                        pgreat: s.pgreat || 0,
                        great: s.great || 0,
                        missCount: s.missCount,
                        clearType: s.clearType,
                        djLevel: s.djLevel,
                        memo: s.memo || undefined
                    };
                }
            });

            return Array.from(grouped.values());
        } finally {
            isFetching.value = false;
        }
    };


    const fetchAllUsers = async () => {
        isFetching.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/admin/users`, {
                headers: authHeaders()
            });

            if (!res.ok) {
                throw new Error(`Fetch failed: ${res.status}`);
            }
            return await res.json();
        } finally {
            isFetching.value = false;
        }
    };

    const fetchUserScores = async (
        userId: number,
        mode: 'admin' | 'friend' | 'public' = 'admin'
    ): Promise<ScoreData[]> => {
        isFetching.value = true;
        try {
            // 管理者 → admin / フレンド閲覧 → friend / 全公開ユーザ → public
            const url =
                mode === 'friend' ? `${API_BASE}/api/friends/${userId}/scores`
                : mode === 'public' ? `${API_BASE}/api/users/${userId}/scores`
                : `${API_BASE}/api/admin/users/${userId}/scores`;
            const res = await fetch(url, {
                headers: authHeaders()
            });

            if (!res.ok) {
                if (res.status === 401 || res.status === 403) {
                    return [];
                }
                throw new Error(`Fetch failed: ${res.status}`);
            }

            const flatScores = await res.json();
            const grouped = new Map<string, any>();

            const emptyDiff = (): DifficultyStats => ({
                difficulty: null,
                score: 0,
                pgreat: 0,
                great: 0,
                missCount: null,
                clearType: 'NO PLAY',
                djLevel: '---',
                memo: undefined,
                id: undefined
            });

            flatScores.forEach((s: any) => {
                const title = s.title;
                if (!grouped.has(title)) {
                    grouped.set(title, {
                        version: '0',
                        title: title,
                        genre: s.genre || '',
                        artist: s.artist || '',
                        playCount: s.playCount || 0,
                        lastPlayTime: '',
                        beginner: emptyDiff(),
                        normal: emptyDiff(),
                        hyper: emptyDiff(),
                        another: emptyDiff(),
                        leggendaria: emptyDiff(),
                    });
                }

                const entry = grouped.get(title);
                const diffKey = s.difficultyName.toLowerCase() as keyof ScoreData;

                if (entry[diffKey]) {
                    entry[diffKey] = {
                        id: s.id,
                        difficulty: s.difficultyLevel,
                        score: s.score,
                        pgreat: s.pgreat || 0,
                        great: s.great || 0,
                        missCount: s.missCount,
                        clearType: s.clearType,
                        djLevel: s.djLevel,
                        memo: s.memo || undefined
                    };
                }
            });

            return Array.from(grouped.values());
        } finally {
            isFetching.value = false;
        }
    };

    const fetchTopRankerProfile = async (
        versionNum: number,
        prefectureFileNum: number
    ): Promise<{ profile: any | null; scores: ScoreData[] }> => {
        isFetching.value = true;
        try {
            const res = await fetch(
                `${API_BASE}/api/scores/top-ranker-profile?versionNum=${versionNum}&prefectureFileNum=${prefectureFileNum}`
            );
            if (!res.ok) return { profile: null, scores: [] };
            const data = await res.json();

            const grouped = new Map<string, any>();
            const emptyDiff = (): DifficultyStats => ({
                difficulty: null,
                score: 0,
                pgreat: 0,
                great: 0,
                missCount: null,
                clearType: 'NO PLAY',
                djLevel: '---',
                memo: undefined,
                id: undefined
            });

            (data.scores ?? []).forEach((s: any) => {
                const title = s.title;
                if (!grouped.has(title)) {
                    grouped.set(title, {
                        version: '0',
                        title,
                        genre: '',
                        artist: '',
                        playCount: 0,
                        lastPlayTime: '',
                        djName: s.djName ?? '',
                        beginner: emptyDiff(),
                        normal: emptyDiff(),
                        hyper: emptyDiff(),
                        another: emptyDiff(),
                        leggendaria: emptyDiff(),
                    });
                }
                const entry = grouped.get(title);
                const diffKey = s.difficultyName.toLowerCase() as keyof ScoreData;
                if (entry[diffKey]) {
                    entry[diffKey] = {
                        id: undefined,
                        difficulty: s.difficultyLevel,
                        score: s.score,
                        pgreat: 0,
                        great: 0,
                        missCount: null,
                        clearType: s.clearType ?? 'NO PLAY',
                        djLevel: s.djLevel ?? '---',
                        memo: undefined,
                        djName: s.djName ?? undefined,
                    } as any;
                }
            });

            return {
                profile: {
                    versionNum: data.versionNum,
                    versionName: data.versionName,
                    prefectureFileNum: data.prefectureFileNum,
                    prefectureName: data.prefectureName,
                },
                scores: Array.from(grouped.values()),
            };
        } finally {
            isFetching.value = false;
        }
    };

    const fetchPublicProfile = async (userId: number): Promise<any | null> => {
        const res = await fetch(`${API_BASE}/api/users/${userId}/profile`);
        if (!res.ok) return null;
        return await res.json();
    };

    const fetchFriendStatus = async (userId: number): Promise<string> => {
        const res = await fetch(`${API_BASE}/api/users/${userId}/friend-status`, {
            headers: authHeaders(),
        });
        if (!res.ok) return 'none';
        const data = await res.json();
        return data.status ?? 'none';
    };

    const updateMemo = async (id: number, memo: string) => {
        const res = await fetch(`${API_BASE}/api/scores/${id}/memo`, {
            method: 'PUT',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ memo })
        });
        if (!res.ok) throw new Error('Failed to update memo');
        return res.json();
    };

    return {
        fetchMyScores,
        fetchUserScores,
        fetchAllUsers,
        fetchTopRankerProfile,
        fetchPublicProfile,
        fetchFriendStatus,
        updateMemo,
        isFetching,
    };
}
