import { ref } from 'vue';
import type { ScoreData, DifficultyStats } from '../types/ScoreData';
import { useAuth } from './useAuth';

export interface NearbyPlayerScore {
    title: string;
    difficultyName: string;
    difficultyLevel: number;
    score: number;
}

export interface NearbyPlayer {
    displayName: string;
    totalBeatPt: number;
    scores: NearbyPlayerScore[];
}

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

    const fetchUserScores = async (userId: number): Promise<ScoreData[]> => {
        isFetching.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/admin/users/${userId}/scores`, {
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

    const updateMemo = async (id: number, memo: string) => {
        const res = await fetch(`${API_BASE}/api/scores/${id}/memo`, {
            method: 'PUT',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ memo })
        });
        if (!res.ok) throw new Error('Failed to update memo');
        return res.json();
    };

    const fetchNearbyPlayersScores = async (pt: number, range = 200): Promise<NearbyPlayer[]> => {
        try {
            const res = await fetch(`${API_BASE}/api/scores/nearby-players-scores?pt=${pt}&range=${range}`, {
                headers: authHeaders()
            });
            if (!res.ok) return [];
            return await res.json();
        } catch {
            return [];
        }
    };

    return { fetchMyScores, fetchUserScores, fetchAllUsers, updateMemo, fetchNearbyPlayersScores, isFetching };
}
