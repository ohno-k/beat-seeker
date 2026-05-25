import { ref } from 'vue';
import { useAuth, API_BASE } from './useAuth';

/** タイムラインの 1 件分のスコア更新譜面（SCORE_UPDATE の payload 要素）。 */
export interface TimelineUpdatedSong {
    title: string;
    difficulty: string;
    oldScore: number;
    newScore: number;
    scoreIncrease: number;
    oldClearType?: string;
    newClearType?: string;
    clearTypeImproved?: boolean;
    oldBeatPt?: number;
    newBeatPt?: number;
    beatPtIncrease?: number;
    scoreRate?: number;
    maxScore?: number;
    newRatePt?: number;
    ratePtIncrease?: number;
    songRank?: number;
    songRankTotal?: number;
    informalRank?: string;
}

/** 譜面単位の抜き payload。 */
export interface OvertakeSongPayload {
    rivalId: number;
    isVirtual: boolean;
    rivalName: string;
    title: string;
    difficulty: string;
    rivalScore: number;
    myOldScore: number;
    myNewScore: number;
}

/** 総合 BEAT-PT の抜き payload。 */
export interface OvertakeTotalPayload {
    rivalId: number;
    isVirtual: boolean;
    rivalName: string;
    rivalBeatPt: number;
    myOldBeatPt: number;
    myNewBeatPt: number;
}

/** バックフィルジョブの進捗状態（GET /api/timeline/backfill-status のレスポンス）。 */
export interface BackfillStatus {
    /** ジョブが現在走行中かどうか。 */
    running: boolean;
    /** 開始時刻（ISO 8601）。未起動なら null。 */
    startedAt: string | null;
    /** 完了時刻（ISO 8601）。未完了なら null。 */
    finishedAt: string | null;
    /** 対象ユーザ総数。 */
    totalUsers: number;
    /** これまでに処理したユーザ数（成功 + スキップ）。 */
    processedUsers: number;
    /** これまでに生成したイベント件数。 */
    createdEvents: number;
    /** 既存イベント保有でスキップしたユーザ数。 */
    skippedUsers: number;
    /** 直近のエラー。成功時 null。 */
    lastError: string | null;
}

/** タイムライン 1 エントリ。type に応じて payload の中身が変わる。 */
export interface TimelineEntry {
    id: number;
    type: 'SCORE_UPDATE' | 'OVERTAKE_SONG' | 'OVERTAKE_TOTAL';
    userId: number;
    displayName: string;
    isMe: boolean;
    /** SCORE_UPDATE のときは配列、OVERTAKE_* のときはオブジェクト。 */
    payload: TimelineUpdatedSong[] | OvertakeSongPayload | OvertakeTotalPayload | null;
    /** ISO 8601 オフセット付き日時文字列（サーバが JST に変換済み）。 */
    createdAt: string;
}

/**
 * 【Composable の役割】 フレンド画面のタイムライン（自分 + フレンドの活動）を取得する。
 *
 * 機能:
 *  - `fetchTimeline(limit)`: バックエンド `/api/timeline` を叩いて最近のイベントを取得
 *
 * 使い方:
 * ```ts
 * const { entries, isLoading, fetchTimeline } = useTimeline();
 * await fetchTimeline();
 * ```
 */
export function useTimeline() {
    const { authHeaders } = useAuth();
    /** 取得済みタイムライン（新しい順）。 */
    const entries = ref<TimelineEntry[]>([]);
    /** フェッチ中フラグ。 */
    const isLoading = ref(false);
    /** エラーメッセージ。失敗時に UI で表示する。 */
    const error = ref<string | null>(null);

    /**
     * タイムラインを取得して `entries` に格納する。
     * @param limit 取得件数の希望（省略時はバックエンドの既定値 50 件）
     */
    const fetchTimeline = async (limit?: number) => {
        isLoading.value = true;
        error.value = null;
        try {
            const url = limit ? `${API_BASE}/api/timeline?limit=${limit}` : `${API_BASE}/api/timeline`;
            const res = await fetch(url, { headers: authHeaders() });
            if (!res.ok) throw new Error('Failed to fetch timeline');
            entries.value = await res.json();
        } catch (e: any) {
            error.value = e.message;
        } finally {
            isLoading.value = false;
        }
    };

    /** バックフィルジョブの進捗状態（サーバ側 TimelineBackfillService.getStatus() の戻り値）。 */
    const backfillStatus = ref<BackfillStatus | null>(null);

    /**
     * 管理者向け: 全ユーザの過去 ScoreHistoryLog から SCORE_UPDATE をバックフィルする。
     *
     * サーバ処理は数分かかるため、トリガ後はステータスポーリングで完了を待つ。
     * 完了時に `fetchTimeline()` を再実行して画面へ反映する。
     *
     * @param force すでに TimelineEvent を持つユーザもスキップせず再生成するか
     * @returns 最終ステータス（completed 状態の `BackfillStatus`）。失敗時 null。
     */
    const backfillAll = async (force = false): Promise<BackfillStatus | null> => {
        error.value = null;
        try {
            const url = `${API_BASE}/api/timeline/backfill-all${force ? '?force=true' : ''}`;
            const res = await fetch(url, { method: 'POST', headers: authHeaders() });
            if (!res.ok) throw new Error('Failed to start backfill all');
            const startBody: { started: boolean; alreadyRunning: boolean; status: BackfillStatus } = await res.json();
            backfillStatus.value = startBody.status;

            // 完了するまでサーバへポーリング（2 秒間隔、最大 30 分）。
            const POLL_INTERVAL_MS = 2000;
            const MAX_POLL_MS = 30 * 60 * 1000;
            const deadline = Date.now() + MAX_POLL_MS;
            // 起動直後にすぐ running=true になるとは限らないので 1 拍待ってから判定する。
            await new Promise(r => setTimeout(r, 500));
            while (Date.now() < deadline) {
                const s = await fetchBackfillStatus();
                if (!s) break;
                if (!s.running) {
                    // 完了したのでタイムラインを再取得して終了。
                    await fetchTimeline();
                    return s;
                }
                await new Promise(r => setTimeout(r, POLL_INTERVAL_MS));
            }
            error.value = 'バックフィルの完了確認がタイムアウトしました。後で更新ボタンを押してください。';
            return backfillStatus.value;
        } catch (e: any) {
            error.value = e.message;
            return null;
        }
    };

    /** サーバから現在のバックフィル進捗を取得して `backfillStatus` に格納する。 */
    const fetchBackfillStatus = async (): Promise<BackfillStatus | null> => {
        try {
            const res = await fetch(`${API_BASE}/api/timeline/backfill-status`, { headers: authHeaders() });
            if (!res.ok) return null;
            const data: BackfillStatus = await res.json();
            backfillStatus.value = data;
            return data;
        } catch {
            return null;
        }
    };

    /**
     * 過去の ScoreHistoryLog から自分の SCORE_UPDATE タイムラインをバックフィルする。
     * 成功時はそのまま `fetchTimeline()` を再実行して画面に反映する。
     *
     * @param force すでに TimelineEvent を持つ場合でも追記するか
     * @returns サーバが返した診断情報 { historyLogs, createdEvents, hadExistingEvents } など
     */
    const backfillMine = async (force = false): Promise<{ historyLogs: number; createdEvents: number; hadExistingEvents: boolean; skipped: boolean } | null> => {
        isLoading.value = true;
        error.value = null;
        try {
            const url = `${API_BASE}/api/timeline/backfill${force ? '?force=true' : ''}`;
            const res = await fetch(url, { method: 'POST', headers: authHeaders() });
            if (!res.ok) throw new Error('Failed to backfill');
            const data = await res.json();
            await fetchTimeline();
            return data;
        } catch (e: any) {
            error.value = e.message;
            return null;
        } finally {
            isLoading.value = false;
        }
    };

    return {
        /** 取得済みエントリ。 */
        entries,
        /** フェッチ中フラグ。 */
        isLoading,
        /** エラーメッセージ。 */
        error,
        /** タイムラインを取得する。 */
        fetchTimeline,
        /** 過去の ScoreHistoryLog から SCORE_UPDATE を再生成する。 */
        backfillMine,
        /** 管理者向け: 全ユーザの SCORE_UPDATE を再生成（非同期ジョブ + ポーリング）。 */
        backfillAll,
        /** バックフィルジョブの現在進捗（ポーリング更新）。 */
        backfillStatus,
        /** サーバから直近の進捗状態を 1 度取得する。 */
        fetchBackfillStatus,
    };
}
