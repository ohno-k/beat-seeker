import type { ScoreData } from '../types/ScoreData';
import { useAuth } from './useAuth';

/** バックエンド API のベース URL。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/**
 * 【内部関数】 UI 側では「曲単位（1 行に beginner〜leggendaria が同居）」でスコアを保持しているが、
 * API 側は「譜面単位（難易度ごとに 1 レコード）」を要求する。ここでフラット化変換を行う。
 *
 * 'NO PLAY' や '---' のような未プレイ譜面はサーバへ送らない（サーバ側負荷削減）。
 *
 * @param source スコア取得元（"arcade" / "infinitas"）。各レコードに付与してサーバへ送る。
 */
function flattenToUploadRecords(scores: ScoreData[], source: 'arcade' | 'infinitas') {
    const difficulties = ['beginner', 'normal', 'hyper', 'another', 'leggendaria'] as const;
    // UI 側のキー（小文字） → API 側のラベル（大文字）への変換表
    const difficultyLabels: Record<string, string> = {
        beginner: 'BEGINNER',
        normal: 'NORMAL',
        hyper: 'HYPER',
        another: 'ANOTHER',
        leggendaria: 'LEGGENDARIA',
    };

    const records: object[] = [];

    scores.forEach(song => {
        difficulties.forEach(diff => {
            const stats = song[diff as keyof ScoreData] as any;
            // 未プレイ譜面はスキップ（転送量削減と、サーバ側の不要上書き防止）
            if (!stats || stats.clearType === 'NO PLAY' || stats.clearType === '---') return;

            records.push({
                title: song.title,
                artist: song.artist,
                genre: song.genre,
                difficultyName: difficultyLabels[diff],
                difficultyLevel: stats.difficulty,
                score: stats.score,
                clearType: stats.clearType,
                djLevel: stats.djLevel,
                pgreat: stats.pgreat,
                great: stats.great,
                missCount: stats.missCount,
                playCount: song.playCount,
                source,
            });
        });
    });

    return records;
}

/**
 * 【Composable の役割】 スコアデータをバックエンドへアップロードする。
 *
 * 機能:
 *  - `upload()`: CSV から読んだ `ScoreData[]` を一括送信（60 秒タイムアウト付き）
 *  - `saveHistoryLog()`: アップロード後の履歴ログを保存
 *
 * 使い方:
 * ```ts
 * const { upload, saveHistoryLog } = useScoreUpload();
 * const result = await upload(parsedScores);
 * await saveHistoryLog(result.totalBeatPt, ...);
 * ```
 */
export function useScoreUpload() {
    const { authHeaders } = useAuth();

    /**
     * スコアをまとめてアップロードする。
     *
     * 注意:
     *  - 60 秒で AbortController により強制中断（大量データで DB 処理が長引くため）
     *  - 非 2xx 応答は例外として投げる（呼び出し側で UI エラーハンドリング）
     *
     * @param scores 曲単位のスコア配列
     * @param source スコアの取得元。既定は "arcade"。INFINITAS 画面取得時は "infinitas" を渡す
     * @returns 更新件数・更新譜面一覧・サーバメッセージ
     */
    const upload = async (
        scores: ScoreData[],
        source: 'arcade' | 'infinitas' = 'arcade',
    ): Promise<{ updatedCount: number; updatedSongs: any[]; message: string; skippedInfinitasOnly?: number }> => {
        const records = flattenToUploadRecords(scores, source);
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 60000);
        try {
            const res = await fetch(`${API_BASE}/api/scores/upload`, {
                method: 'POST',
                headers: authHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify(records),
                signal: controller.signal,
            });

            if (!res.ok) {
                throw new Error(`Upload failed: ${res.status}`);
            }

            return res.json();
        } finally {
            // 正常/異常いずれの場合もタイマーを解除（メモリリーク防止）
            clearTimeout(timeoutId);
        }
    };

    /**
     * アップロード結果の履歴ログを保存する。
     *
     * BeatPt / RatePt の推移・Tier 変動をユーザー個別の履歴に残すため、
     * `upload()` 成功後に呼ぶ設計。
     */
    const saveHistoryLog = async (totalBeatPt: number, beatPtIncrease: number, updatedCount: number, diffJson: string, tierName?: string, prevTierName?: string, totalRatePt?: number, includesInfinitas?: boolean): Promise<void> => {
        const res = await fetch(`${API_BASE}/api/scores/save-history-log`, {
            method: 'POST',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ totalBeatPt, beatPtIncrease, updatedCount, diffJson, totalRatePt: totalRatePt ?? 0, tierName, prevTierName, includesInfinitas: includesInfinitas ?? false }),
        });

        if (!res.ok) {
            throw new Error(`Failed to save history log: ${res.status}`);
        }
    };

    return {
        /** スコア一括アップロード。 */
        upload,
        /** 履歴ログ保存。`upload` 成功後に呼ぶ。 */
        saveHistoryLog
    };
}
