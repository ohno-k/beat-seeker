import { ref } from 'vue';
import songDataRaw from '../data/song_data.json';
import diffTableRaw from '../data/difficulty_table.json';
import { useRateTierVisibility } from './useRateTierVisibility';

export interface RateSongRankingEntry {
    title: string;
    difficultyName: string;
    informalRank: string;
    userCount: number;
    avgRatePt: number;
}

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

export function useRateSongRanking() {
    const mostRanking = ref<RateSongRankingEntry[]>([]);
    const leastRanking = ref<RateSongRankingEntry[]>([]);
    const isLoading = ref(false);
    const error = ref('');
    const totalUsers = ref(0);

    const fetchRateSongRanking = async () => {
        const { showRateTier } = useRateTierVisibility();
        if (!showRateTier.value) return;
        if (mostRanking.value.length > 0) return;
        isLoading.value = true;
        error.value = '';
        try {
            // Build song max-score map to send to backend (title_diffCode -> maxScore)
            const songMaxScores: Record<string, number> = {};
            if (songDataRaw && Array.isArray(songDataRaw.body)) {
                songDataRaw.body.forEach((s: any) => {
                    if (s.notes) songMaxScores[`${s.title}_${s.difficulty}`] = s.notes * 2;
                });
            }

            // Build informal rank lookup (client-side only, for display)
            const informalDict = new Map<string, string>();
            if (diffTableRaw && Array.isArray(diffTableRaw.ranks)) {
                diffTableRaw.ranks.forEach((r: any) => {
                    r.songs.forEach((songTitle: string) => {
                        if (songTitle.endsWith('[L]')) {
                            informalDict.set(`${songTitle.slice(0, -3)}_LEGGENDARIA`, r.rank);
                        } else {
                            informalDict.set(`${songTitle}_ANOTHER`, r.rank);
                        }
                    });
                });
            }

            const res = await fetch(`${API_BASE}/api/scores/rate-song-ranking`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(songMaxScores),
            });
            if (!res.ok) throw new Error('Failed to fetch');
            const data: Array<{ title: string; difficultyName: string; userCount: number; avgRatePt: number }> = await res.json();

            const entries: RateSongRankingEntry[] = data.map(e => ({
                title: e.title,
                difficultyName: e.difficultyName,
                informalRank: informalDict.get(`${e.title}_${e.difficultyName}`) ?? '',
                userCount: e.userCount,
                avgRatePt: e.avgRatePt,
            }));

            totalUsers.value = 0; // server-side doesn't return totalUsers directly
            mostRanking.value = [...entries].sort((a, b) => b.userCount - a.userCount || b.avgRatePt - a.avgRatePt);
            leastRanking.value = [...entries].sort((a, b) => a.userCount - b.userCount || a.avgRatePt - b.avgRatePt);
        } catch (e) {
            error.value = '楽曲ランキングの取得に失敗しました。';
            console.error(e);
        } finally {
            isLoading.value = false;
        }
    };

    return { mostRanking, leastRanking, isLoading, error, totalUsers, fetchRateSongRanking };
}
