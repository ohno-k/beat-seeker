import { ref } from 'vue';
import { songData as songDataBodyRef, diffTable as diffTableRanksRef } from '../composables/useGameData';
import { useRateTierVisibility } from './useRateTierVisibility';

/**
 * Rate-Tier 用ランキングの 1 行分データ。
 *
 *  - `informalRank` は非公式難易度表記（クライアント側で付加）
 *  - `avgRatePt` は該当譜面の平均 RatePt
 */
export interface RateSongRankingEntry {
    title: string;
    difficultyName: string;
    informalRank: string;
    userCount: number;
    avgRatePt: number;
}

/** バックエンド API のベース URL。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/**
 * 【Composable の役割】 Rate-Tier モード用の「人気曲 / マイナー曲」ランキングを取得する。
 *
 * 特徴:
 *  - 通常の `useSongRanking` と異なり、クライアント側で計算した曲別最大スコアを
 *    バックエンドへ POST する（RatePt 計算のため）
 *  - 非公式難易度表のラベルはクライアント側で結合する
 *  - Rate-Tier 表示が OFF のときは何もしない
 *
 * 使い方:
 * ```ts
 * const { mostRanking, leastRanking, fetchRateSongRanking } = useRateSongRanking();
 * await fetchRateSongRanking();
 * ```
 */
export function useRateSongRanking() {
    /** 人気ランキング（userCount 降順）。 */
    const mostRanking = ref<RateSongRankingEntry[]>([]);
    /** マイナーランキング（userCount 昇順）。 */
    const leastRanking = ref<RateSongRankingEntry[]>([]);
    const isLoading = ref(false);
    const error = ref('');
    const totalUsers = ref(0);

    /** サーバーから Rate-Tier 別集計を取得する。 */
    const fetchRateSongRanking = async () => {
        const { showRateTier } = useRateTierVisibility();
        // Rate-Tier 非表示時は無駄にサーバを呼ばない
        if (!showRateTier.value) return;
        // 既に取得済みなら再フェッチしない（アクセス毎の負荷削減）
        if (mostRanking.value.length > 0) return;
        isLoading.value = true;
        error.value = '';
        try {
            // 手順1: 楽曲ごとの最大スコア（ノート数 × 2）をマップ化してサーバへ送る。
            //        キーは "title_diffCode" 形式で、バックエンドは RatePt 計算に利用する。
            const songMaxScores: Record<string, number> = {};
            if (songDataBodyRef.value && Array.isArray(songDataBodyRef.value)) {
                songDataBodyRef.value.forEach((s: any) => {
                    if (s.notes) songMaxScores[`${s.title}_${s.difficulty}`] = s.notes * 2;
                });
            }

            // 手順2: 非公式難易度表のランクを辞書化（表示用。ランク情報自体はサーバから返さない）。
            const informalDict = new Map<string, string>();
            if (diffTableRanksRef.value && Array.isArray(diffTableRanksRef.value)) {
                diffTableRanksRef.value.forEach((r: any) => {
                    r.songs.forEach((songTitle: string) => {
                        // 末尾 '[L]' は LEGGENDARIA 指定。それ以外はデフォルト ANOTHER 扱い。
                        if (songTitle.endsWith('[L]')) {
                            informalDict.set(`${songTitle.slice(0, -3)}_LEGGENDARIA`, r.rank);
                        } else {
                            informalDict.set(`${songTitle}_ANOTHER`, r.rank);
                        }
                    });
                });
            }

            // 手順3: クライアント側で作った最大スコアマップを POST し、Rate 計算結果を取得する。
            const res = await fetch(`${API_BASE}/api/scores/rate-song-ranking`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(songMaxScores),
            });
            if (!res.ok) throw new Error('Failed to fetch');
            const data: Array<{ title: string; difficultyName: string; userCount: number; avgRatePt: number }> = await res.json();

            // 手順4: サーバ結果に informalRank を結合して UI 用構造に変換。
            const entries: RateSongRankingEntry[] = data.map(e => ({
                title: e.title,
                difficultyName: e.difficultyName,
                informalRank: informalDict.get(`${e.title}_${e.difficultyName}`) ?? '',
                userCount: e.userCount,
                avgRatePt: e.avgRatePt,
            }));

            totalUsers.value = 0; // サーバは totalUsers を直接返していない
            // 人気 / マイナーの 2 ソートを生成
            mostRanking.value = [...entries].sort((a, b) => b.userCount - a.userCount || b.avgRatePt - a.avgRatePt);
            leastRanking.value = [...entries].sort((a, b) => a.userCount - b.userCount || a.avgRatePt - b.avgRatePt);
        } catch (e) {
            error.value = '楽曲ランキングの取得に失敗しました。';
            console.error(e);
        } finally {
            isLoading.value = false;
        }
    };

    return {
        /** 人気曲ランキング。 */
        mostRanking,
        /** マイナー曲ランキング。 */
        leastRanking,
        /** ロード中フラグ。 */
        isLoading,
        /** エラーメッセージ。 */
        error,
        /** 将来用 placeholder。 */
        totalUsers,
        /** 集計 API 呼び出しトリガ。 */
        fetchRateSongRanking
    };
}
