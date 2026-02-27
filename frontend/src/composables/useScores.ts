import { ref } from 'vue';
import type { ScoreData, DifficultyStats } from '../types/ScoreData';

// VITE_API_BASE should be explicitly set to 'http://localhost:8080' in local dev.
// In production, leaves it empty so it targets '/' (triggering the Render Rewrite).
const API_BASE = import.meta.env.VITE_API_BASE ?? '';

export function useScores() {
    const isFetching = ref(false);

    const fetchMyScores = async (): Promise<ScoreData[]> => {
        isFetching.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/scores/me`, {
                credentials: 'include'
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
                        genre: s.genre || '', // genre might not be returned in /me directly, but let's map what we have
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
            credentials: 'include',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ memo })
        });
        if (!res.ok) throw new Error('Failed to update memo');
        return res.json();
    };

    return { fetchMyScores, updateMemo, isFetching };
}
