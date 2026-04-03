import { ref } from 'vue';
import { useAuth } from './useAuth';

export interface SongRankingEntry {
    title: string;
    difficultyName: string;
    informalRank: string;
    userCount: number;
    avgBeatPt: number;
    maxBeatPt: number;
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
            const res = await fetch(`${API_BASE}/api/scores/song-ranking-aggregate`, {
                headers: authHeaders()
            });
            if (!res.ok) throw new Error('Failed to fetch song ranking aggregate');
            const entries: SongRankingEntry[] = await res.json();
            
            // Note: Since SQL groups only by users that played, totalUsers could be approximated 
            // from the ranking data or just left as a placeholder. We can fetch total users from another endpoint or omit.
            // For now we'll set it to 0 as it's not strictly necessary.
            totalUsers.value = 0;

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
