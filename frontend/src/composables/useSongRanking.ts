import { ref } from 'vue';
import { useAuth } from './useAuth';

/**
 * 楽曲ランキング 1 件のエントリ。
 *
 *  - `title` / `difficultyName` で譜面を特定
 *  - `informalRank` は非公式難易度表（例: 皆伝◯◯, ★10 など）の表記
 *  - `userCount` はこの譜面をプレイしているユーザー数（人気度）
 *  - `avgBeatPt` / `maxBeatPt` はプレイヤーの BeatPt 平均/最大値
 */
export interface SongRankingEntry {
    title: string;
    difficultyName: string;
    informalRank: string;
    userCount: number;
    avgBeatPt: number;
    maxBeatPt: number;
}

/** バックエンド API のベース URL。未設定時はローカル開発用のデフォルト値。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/**
 * 【Composable の役割】 「人気曲ランキング」「不人気曲ランキング」両方の一覧を取得・保持する。
 *
 * 機能:
 *  - `/api/scores/song-ranking-aggregate` から集計結果を取得
 *  - 取得結果をユーザー数の降順（人気）・昇順（マイナー）の 2 セットで保持
 *
 * 使い方:
 * ```ts
 * const { ranking, leastRanking, isLoading, fetchSongRanking } = useSongRanking();
 * await fetchSongRanking();
 * ```
 */
export function useSongRanking() {
    const { authHeaders } = useAuth();
    /** 人気ランキング（userCount 降順）。 */
    const ranking = ref<SongRankingEntry[]>([]);
    /** マイナーランキング（userCount 昇順）。 */
    const leastRanking = ref<SongRankingEntry[]>([]);
    /** フェッチ中フラグ。スピナー表示に使う。 */
    const isLoading = ref(false);
    /** 取得失敗時のエラーメッセージ。 */
    const error = ref('');
    /** 現在のところ常に 0（将来的に全プレイヤー数を表示する場合の placeholder）。 */
    const totalUsers = ref(0);

    /** サーバーから集計結果を取得し、2 種類の並び順を作成する。 */
    const fetchSongRanking = async () => {
        isLoading.value = true;
        error.value = '';
        try {
            const res = await fetch(`${API_BASE}/api/scores/song-ranking-aggregate`, {
                headers: authHeaders()
            });
            if (!res.ok) throw new Error('Failed to fetch song ranking aggregate');
            const entries: SongRankingEntry[] = await res.json();

            // 注意: SQL 側はプレイしたユーザーだけでグルーピングしているので、
            // 全ユーザー数を正確に算出したい場合は別エンドポイントから取る必要がある。
            // 現状は使っていないので 0 のまま。
            totalUsers.value = 0;

            // 人気ランキング: ユーザー数 → 平均 BeatPt の 2 段ソート（降順）
            ranking.value = [...entries].sort((a, b) => b.userCount - a.userCount || b.avgBeatPt - a.avgBeatPt);
            // マイナーランキング: 上記の逆順
            leastRanking.value = [...entries].sort((a, b) => a.userCount - b.userCount || a.avgBeatPt - b.avgBeatPt);
        } catch (e) {
            error.value = '楽曲ランキングの取得に失敗しました。';
            console.error(e);
        } finally {
            isLoading.value = false;
        }
    };

    return {
        /** 人気順ランキング。 */
        ranking,
        /** マイナー順ランキング。 */
        leastRanking,
        /** ロード中フラグ。 */
        isLoading,
        /** エラーメッセージ（空文字 = エラーなし）。 */
        error,
        /** 将来用の全ユーザー数。現在常に 0。 */
        totalUsers,
        /** 集計 API を呼ぶトリガ関数。 */
        fetchSongRanking
    };
}
