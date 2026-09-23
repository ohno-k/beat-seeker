import type { ScoreData } from '../types/ScoreData';
import { useAuth } from './useAuth';

/** バックエンド API のベース URL。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/**
 * `upload()` が投げるエラー。`rejected` が true のときはサーバーが内容を理由に拒否しており
 * （前作データの判定など）、何も保存されていないことが確定している。`message` は利用者向けの文言。
 */
export type UploadError = Error & { status?: number; code?: string; rejected?: boolean; aborted?: boolean };

/**
 * `upload()` の打ち切り時間（ミリ秒）。
 *
 * 全譜面（2000〜3000 件）を 1 リクエストで送るため、初回取り込みや全曲再取り込みでは
 * サーバー側の集計・通知処理を含めて 1 分を超えることがある。ここで打ち切ると
 * 「サーバーは保存したのにブラウザだけ失敗扱い」になり、レポートも成長記録も出ない。
 */
const UPLOAD_TIMEOUT_MS = 180000;

/**
 * 【内部関数】 UI 側では「曲単位（1 行に beginner〜leggendaria が同居）」でスコアを保持しているが、
 * API 側は「譜面単位（難易度ごとに 1 レコード）」を要求する。ここでフラット化変換を行う。
 *
 * 'NO PLAY' や '---' のような未プレイ譜面はサーバへ送らない（サーバ側負荷削減）。
 *
 * @param sourceVersion ブックマークレットを実行したページの作品番号（例: 34）。CSV ファイル取り込みでは null。
 *                      前作のページで取った結果をサーバー（StaleUploadGuard）が弾くための材料。
 */
function flattenToUploadRecords(scores: ScoreData[], sourceVersion: number | null) {
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
                // 公式 CSV の「最終プレー日時」（曲単位）。記録が伸びなくても進む唯一の値で、
                // リーグモードの活動判定（課題曲をリーグ期間中に遊んだか）がこれを根拠にする。
                // ブックマークレット CSV は空欄のため、サーバー側で
                // 公式 CSV 書式（"YYYY-MM-DD HH:mm"）以外は無視される。
                lastPlayTime: song.lastPlayTime,
                source: 'arcade',
                ...(sourceVersion != null ? { sourceVersion } : {}),
            });
        });
    });

    return records;
}

/**
 * 【Composable の役割】 スコアデータをバックエンドへアップロードする。
 *
 * 機能:
 *  - `upload()`: CSV から読んだ `ScoreData[]` を一括送信（{@link UPLOAD_TIMEOUT_MS} でタイムアウト）
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
     *  - {@link UPLOAD_TIMEOUT_MS} で AbortController により強制中断（大量データで DB 処理が長引くため）。
     *    中断・通信断は `aborted = true` を付けて投げる（保存済みの可能性があるため確認が必要）
     *  - 非 2xx 応答は例外として投げる（呼び出し側で UI エラーハンドリング）。
     *    サーバーが内容を理由に拒否した場合（前作データの判定など: 400 + code）は、その文言を
     *    message に、`rejected = true` を付けて投げる。呼び出し側は「保存されていない」と確定して扱える。
     *
     * @param scores 曲単位のスコア配列
     * @param sourceVersion ブックマークレットを実行したページの作品番号。CSV ファイル取り込みでは null
     * @returns 更新件数・更新譜面一覧・サーバメッセージ
     */
    const upload = async (
        scores: ScoreData[],
        sourceVersion: number | null = null,
    ): Promise<{ updatedCount: number; updatedSongs: any[]; message: string }> => {
        const records = flattenToUploadRecords(scores, sourceVersion);
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), UPLOAD_TIMEOUT_MS);
        try {
            let res: Response;
            try {
                res = await fetch(`${API_BASE}/api/scores/upload`, {
                    method: 'POST',
                    headers: authHeaders({ 'Content-Type': 'application/json' }),
                    body: JSON.stringify(records),
                    signal: controller.signal,
                });
            } catch (e: any) {
                // 打ち切り・通信断は「サーバー側で保存が完了している可能性がある」ケース。
                // 呼び出し側がこれを見て、保存済みかどうかを確かめてから結果を出し分ける。
                const err = new Error(e?.message || 'Upload aborted') as UploadError;
                err.aborted = true;
                throw err;
            }

            if (!res.ok) {
                let message = `Upload failed: ${res.status}`;
                let code: string | undefined;
                try {
                    const body = await res.json();
                    if (body?.message) message = String(body.message);
                    if (body?.code) code = String(body.code);
                } catch {
                    // JSON でない応答（ゲートウェイのエラーページ等）はステータスだけ伝える
                }
                const err = new Error(message) as UploadError;
                err.status = res.status;
                err.code = code;
                err.rejected = res.status === 400 && !!code;
                throw err;
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
     *
     * @param leagueJson アップロード時点のリーグ進捗（utils/leagueReport.ts のスナップショット）。
     *                   今回の更新に課題曲が無ければ undefined。成長記録から開いたレポートで使う。
     */
    const saveHistoryLog = async (totalBeatPt: number, beatPtIncrease: number, updatedCount: number, diffJson: string, tierName?: string, prevTierName?: string, totalRatePt?: number, leagueJson?: string): Promise<void> => {
        const res = await fetch(`${API_BASE}/api/scores/save-history-log`, {
            method: 'POST',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ totalBeatPt, beatPtIncrease, updatedCount, diffJson, totalRatePt: totalRatePt ?? 0, tierName, prevTierName, leagueJson }),
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
