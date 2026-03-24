import { ref } from 'vue';
import songDataRaw from '../data/song_data.json';
import diffTableRaw from '../data/difficulty_table.json';
import { calculateScoreRateTierPoints } from '../utils/beatTier';
import { useAuth } from './useAuth';
import { useRateTierVisibility } from './useRateTierVisibility';

export interface RateSongRankingEntry {
    title: string;
    difficultyName: string;
    informalRank: string;
    userCount: number;
    avgRatePt: number;
}

interface RawUserScore {
    userId: number;
    title: string;
    difficultyName: string;
    difficultyLevel: number;
    score: number;
}

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

export function useRateSongRanking() {
    const { authHeaders } = useAuth();
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
            const res = await fetch(`${API_BASE}/api/scores/all-user-scores`, {
                headers: authHeaders()
            });
            if (!res.ok) throw new Error('Failed to fetch');
            const allScores: RawUserScore[] = await res.json();

            // Build song max-score lookup (title_diffCode -> notes*2)
            const songDict = new Map<string, number>();
            if (songDataRaw && Array.isArray(songDataRaw.body)) {
                songDataRaw.body.forEach((s: any) => {
                    if (s.notes) {
                        songDict.set(`${s.title}_${s.difficulty}`, s.notes * 2);
                    }
                });
            }

            // Build informal rank lookup
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

            // Group scores by userId, calculate ratePt for each
            const userScoresMap = new Map<number, Array<{ title: string; difficultyName: string; ratePt: number }>>();
            for (const s of allScores) {
                const diffCode = s.difficultyName === 'ANOTHER' ? '4' : '10';
                const maxScore = songDict.get(`${s.title}_${diffCode}`) ?? 0;
                const scoreRate = maxScore > 0 ? (s.score / maxScore) * 100 : 0;
                const ratePt = scoreRate > 0 ? calculateScoreRateTierPoints(scoreRate) : 0;

                if (!userScoresMap.has(s.userId)) {
                    userScoresMap.set(s.userId, []);
                }
                userScoresMap.get(s.userId)!.push({ title: s.title, difficultyName: s.difficultyName, ratePt });
            }

            totalUsers.value = userScoresMap.size;

            // For each user, take top-100 by ratePt, then aggregate per song
            const songCountMap = new Map<string, { count: number; totalRatePt: number; title: string; difficultyName: string; informalRank: string }>();
            for (const userScores of userScoresMap.values()) {
                const valid = userScores.filter(s => s.ratePt > 0);
                valid.sort((a, b) => b.ratePt - a.ratePt);
                const top100 = valid.slice(0, 100);

                for (const s of top100) {
                    const key = `${s.title}_${s.difficultyName}`;
                    const informalRank = informalDict.get(key) ?? '';
                    if (!songCountMap.has(key)) {
                        songCountMap.set(key, { count: 0, totalRatePt: 0, title: s.title, difficultyName: s.difficultyName, informalRank });
                    }
                    const entry = songCountMap.get(key)!;
                    entry.count++;
                    entry.totalRatePt += s.ratePt;
                }
            }

            const entries: RateSongRankingEntry[] = Array.from(songCountMap.values()).map(e => ({
                title: e.title,
                difficultyName: e.difficultyName,
                informalRank: e.informalRank,
                userCount: e.count,
                avgRatePt: e.count > 0 ? e.totalRatePt / e.count : 0,
            }));

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
