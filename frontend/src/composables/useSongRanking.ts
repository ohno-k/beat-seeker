import { ref } from 'vue';
import songDataRaw from '../data/song_data.json';
import diffTableRaw from '../data/difficulty_table.json';
import { calculatePoints } from '../utils/beatTier';
import { useAuth } from './useAuth';

export interface SongRankingEntry {
    title: string;
    difficultyName: string;
    informalRank: string;
    userCount: number;
    avgBeatPt: number;
    maxBeatPt: number;
}

interface RawUserScore {
    userId: number;
    title: string;
    difficultyName: string;
    difficultyLevel: number;
    score: number;
}

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

export function useSongRanking() {
    const { authHeaders } = useAuth();
    const ranking = ref<SongRankingEntry[]>([]);
    const leastRanking = ref<SongRankingEntry[]>([]);
    const isLoading = ref(false);
    const error = ref('');
    const totalUsers = ref(0);

    const fetchSongRanking = async () => {
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

            // Build informal rank lookup (title_difficultyName -> rank)
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

            // Group scores by userId, calculate beatTierPoints for each
            const userScoresMap = new Map<number, Array<{ title: string; difficultyName: string; beatTierPoints: number }>>();
            for (const s of allScores) {
                const diffCode = s.difficultyName === 'ANOTHER' ? '4' : '10';
                const maxScore = songDict.get(`${s.title}_${diffCode}`) ?? 0;
                const scoreRate = maxScore > 0 ? (s.score / maxScore) * 100 : -1;
                const informalRank = informalDict.get(`${s.title}_${s.difficultyName}`);
                const beatTierPoints = calculatePoints(scoreRate, informalRank);

                if (!userScoresMap.has(s.userId)) {
                    userScoresMap.set(s.userId, []);
                }
                userScoresMap.get(s.userId)!.push({ title: s.title, difficultyName: s.difficultyName, beatTierPoints });
            }

            totalUsers.value = userScoresMap.size;

            // For each user, take top-100 by beatTierPoints, then aggregate per song
            const songCountMap = new Map<string, { count: number; totalBeatPt: number; maxBeatPt: number; title: string; difficultyName: string; informalRank: string }>();
            for (const userScores of userScoresMap.values()) {
                const valid = userScores.filter(s => s.beatTierPoints > 0);
                valid.sort((a, b) => b.beatTierPoints - a.beatTierPoints);
                const top100 = valid.slice(0, 100);

                for (const s of top100) {
                    const key = `${s.title}_${s.difficultyName}`;
                    const informalRank = informalDict.get(key) ?? '';
                    if (!songCountMap.has(key)) {
                        songCountMap.set(key, { count: 0, totalBeatPt: 0, maxBeatPt: 0, title: s.title, difficultyName: s.difficultyName, informalRank });
                    }
                    const entry = songCountMap.get(key)!;
                    entry.count++;
                    entry.totalBeatPt += s.beatTierPoints;
                    if (s.beatTierPoints > entry.maxBeatPt) entry.maxBeatPt = s.beatTierPoints;
                }
            }

            const entries: SongRankingEntry[] = Array.from(songCountMap.values()).map(e => ({
                title: e.title,
                difficultyName: e.difficultyName,
                informalRank: e.informalRank,
                userCount: e.count,
                avgBeatPt: e.count > 0 ? e.totalBeatPt / e.count : 0,
                maxBeatPt: e.maxBeatPt,
            }));

            ranking.value = [...entries].sort((a, b) => b.userCount - a.userCount || b.avgBeatPt - a.avgBeatPt);
            leastRanking.value = [...entries].sort((a, b) => a.userCount - b.userCount || a.avgBeatPt - b.avgBeatPt);
        } catch (e) {
            error.value = '楽曲ランキングの取得に失敗しました。';
            console.error(e);
        } finally {
            isLoading.value = false;
        }
    };

    return { ranking, leastRanking, isLoading, error, totalUsers, fetchSongRanking };
}
