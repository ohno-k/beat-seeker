import { ref, computed } from 'vue';
import { useAuth } from './useAuth';
import { API_BASE, CLEAR_TYPE_RANK } from './constants';
import type { ScoreData } from '../types/ScoreData';
import type { ScoreRecord } from '../utils/scoreData';
import { getSongMaxScore } from '../utils/scoreData';
import { calculatePoints } from '../utils/beatTier';
import { CURRENT_VERSION } from '../utils/iidxVersions';

/**
 * 【Composable の役割】 過去作（IIDX 30 RESIDENT 〜 32 Pinky Crush）のスコアを
 * 取り込み・取得・削除し、現行作のスコアと突き合わせて「歴代自己ベスト」を組み立てる。
 *
 * 設計上の前提:
 *  - 過去作スコアはサーバ側でも別テーブル（`past_scores`）に保存され、
 *    ランキング・BEAT-PT・リーグ・大会等の集計には一切参加しない。
 *  - `/api/scores/past/best` は数千件規模になり得るため、歴代タブ／歴代モードを
 *    開いたときにだけ遅延取得し、モジュールスコープにキャッシュする（初期ロードには載せない）。
 *
 * 使い方:
 * ```ts
 * const { fetchPastBest, buildChartHistories } = usePastScores();
 * await fetchPastBest();
 * const histories = buildChartHistories(currentRecords);
 * ```
 */

/** サーバから返る過去スコア 1 行（ペイロード削減のためキーが 1 文字）。 */
interface PastScoreRow {
    /** version */ v: number;
    /** title */ t: string;
    /** difficultyName */ d: string;
    /** difficultyLevel */ l: number | null;
    /** score */ s: number | null;
    /** clearType */ c: string | null;
    /** djLevel */ j: string | null;
    /** missCount */ m: number | null;
    /** pgreat */ p: number | null;
    /** great */ g: number | null;
}

/** ある譜面における、ある 1 作品分のスコア。 */
export interface VersionEntry {
    version: number;
    score: number;
    scoreRate: number | null;
    clearType: string;
    djLevel: string;
    missCount: number | null;
    difficultyLevel: number | null;
    /** 現行作（CURRENT_VERSION）のスコアなら true。 */
    isCurrent: boolean;
}

/** 1 譜面の歴代推移と、そこから導いた歴代ベスト。 */
export interface ChartHistory {
    title: string;
    difficultyName: string;
    /** 作品昇順のスコア。データが無い作品は要素自体が存在しない。 */
    entries: VersionEntry[];
    /** 現行作のスコア。未プレー or 削除曲なら null。 */
    current: VersionEntry | null;
    /** 歴代ベストスコアの作品。 */
    bestScore: VersionEntry;
    /**
     * 現行 EX − 歴代ベスト EX。0 なら現行が歴代ベスト、負なら過去の自分に未到達。
     * 現行作のデータが無い譜面（削除曲など）は null。
     */
    scoreDelta: number | null;
    /** 現行作にデータがあるか。false なら削除曲・未プレー曲。 */
    existsInCurrent: boolean;
    /** 現行の曲マスタ由来の理論値（notes × 2）。マスタ未収録なら 0。 */
    maxScore: number;
}

/** 作品別の取り込み状況（過去データ管理 UI 用）。 */
export interface PastVersionSummary {
    version: number;
    versionName: string;
    chartCount: number;
    importedAt: string | null;
    lastPlayedAt: string | null;
}

// ── モジュールスコープの共有状態 ──────────────────────────────
// 複数コンポーネント（詳細モーダル / 歴代モード / 管理 UI）から同じデータを参照するため、
// ref をモジュールトップレベルに置いてシングルトン化する。
/** `/api/scores/past/best` の生レスポンス。 */
const pastRows = ref<PastScoreRow[]>([]);
/** 一度でも取得に成功したか（再取得を抑止するフラグ）。 */
const isLoaded = ref(false);
/** 取得中フラグ。 */
const isLoading = ref(false);
/** 作品別サマリ。 */
const summary = ref<PastVersionSummary[]>([]);

/** UI 側の難易度キー（小文字）→ API 側ラベル（大文字）。 */
const DIFFICULTY_LABELS: Record<string, string> = {
    beginner: 'BEGINNER',
    normal: 'NORMAL',
    hyper: 'HYPER',
    another: 'ANOTHER',
    leggendaria: 'LEGGENDARIA',
};

/** 譜面を一意に識別する lookup キー。★は作品間で変動するので含めない。 */
export function chartKey(title: string, difficultyName: string): string {
    return `${title}_${difficultyName}`;
}

/** ある譜面についての、過去作を通じた最良値。 */
export interface PastBest {
    /** 歴代ベストスコア（EX）。 */
    score: number;
    /** 上記スコアを出したときの DJ LEVEL。 */
    djLevel: string;
    /** 上記スコアを出したときの PGREAT 数。 */
    pgreat: number;
    /** 上記スコアを出したときの GREAT 数。 */
    great: number;
    /** ベストスコアを出した作品のバージョン番号。 */
    version: number;
    /** 全作品を通じた最良クリアランプ（ベストスコアの作品とは限らない）。 */
    clearType: string;
    /** 全作品を通じた最小 BP。未計測なら null。 */
    missCount: number | null;
}

export function usePastScores() {
    const { authHeaders } = useAuth();

    /**
     * 【関数の役割】 過去作スコアを全件取得してキャッシュする。
     *
     * 既に取得済みなら何もしない（歴代タブを開くたびに数千件を再取得しないため）。
     * 取り込み・削除の直後は `force = true` で明示的に更新する。
     *
     * @param force true なら取得済みでも再取得する
     */
    const fetchPastBest = async (force = false): Promise<void> => {
        if (isLoaded.value && !force) return;
        if (isLoading.value) return;

        isLoading.value = true;
        try {
            const res = await fetch(`${API_BASE}/api/scores/past/best`, { headers: authHeaders() });
            if (!res.ok) {
                // 未ログイン（401）は「過去データ無し」と同義なので握り潰して空配列のままにする。
                if (res.status === 401 || res.status === 403) {
                    pastRows.value = [];
                    isLoaded.value = true;
                    return;
                }
                throw new Error(`Failed to fetch past scores: ${res.status}`);
            }
            pastRows.value = await res.json();
            isLoaded.value = true;
        } finally {
            isLoading.value = false;
        }
    };

    /** 【関数の役割】 作品別の取り込み状況を取得する（過去データ管理 UI 用）。 */
    const fetchSummary = async (): Promise<void> => {
        const res = await fetch(`${API_BASE}/api/scores/past/summary`, { headers: authHeaders() });
        if (!res.ok) {
            if (res.status === 401 || res.status === 403) {
                summary.value = [];
                return;
            }
            throw new Error(`Failed to fetch past score summary: ${res.status}`);
        }
        summary.value = await res.json();
    };

    /**
     * 【関数の役割】 パース済み CSV を過去作スコアとして取り込む。
     *
     * 曲単位（1 行に 5 難易度が同居）の {@link ScoreData} を譜面単位に展開して送る。
     * 未プレー譜面は送らない（転送量削減。現行作の取り込みと同じ方針）。
     *
     * @param version 作品バージョン番号（30〜32）。CSV から自動判定した値
     * @param scores  パース済みスコア
     */
    const uploadPastScores = async (
        version: number,
        scores: ScoreData[],
    ): Promise<{ version: number; versionName: string; inserted: number; updated: number; totalCount: number; message: string }> => {
        const records: object[] = [];
        scores.forEach(song => {
            (Object.keys(DIFFICULTY_LABELS) as Array<keyof typeof DIFFICULTY_LABELS>).forEach(diff => {
                const stats = (song as any)[diff];
                if (!stats || stats.clearType === 'NO PLAY' || stats.clearType === '---') return;

                records.push({
                    title: song.title,
                    artist: song.artist,
                    genre: song.genre,
                    difficultyName: DIFFICULTY_LABELS[diff],
                    difficultyLevel: stats.difficulty,
                    score: stats.score,
                    clearType: stats.clearType,
                    djLevel: stats.djLevel,
                    pgreat: stats.pgreat,
                    great: stats.great,
                    missCount: stats.missCount,
                    playCount: song.playCount,
                    lastPlayedAt: song.lastPlayTime,
                });
            });
        });

        // 現行作の取り込みと同様、大量データで DB 処理が長引くケースに備えて 60 秒で打ち切る。
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 60000);
        try {
            const res = await fetch(`${API_BASE}/api/scores/past/upload`, {
                method: 'POST',
                headers: authHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify({ version, records }),
                signal: controller.signal,
            });
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                throw new Error(body.message || `Past score upload failed: ${res.status}`);
            }
            // 取り込み後はキャッシュが古くなるので破棄する。
            isLoaded.value = false;
            return res.json();
        } finally {
            clearTimeout(timeoutId);
        }
    };

    /** 【関数の役割】 指定作品の過去スコアを丸ごと削除する（取り込みミスのリカバリ）。 */
    const deletePastVersion = async (version: number): Promise<void> => {
        const res = await fetch(`${API_BASE}/api/scores/past/${version}`, {
            method: 'DELETE',
            headers: authHeaders(),
        });
        if (!res.ok) {
            const body = await res.json().catch(() => ({}));
            throw new Error(body.message || `Failed to delete past scores: ${res.status}`);
        }
        isLoaded.value = false;
    };

    /**
     * 【関数の役割】 現行作のスコアとキャッシュ済み過去スコアを突き合わせ、
     * 譜面ごとの歴代推移（{@link ChartHistory}）を組み立てる。
     *
     * 過去作にしか存在しない譜面（削除された版権曲など）も 1 件として返す。
     * 「あの削除曲のスコアが残っている」ことが過去作対応の主要な価値であるため、
     * 現行の曲マスタに無くても行として残す（その場合 `maxScore` は 0 で、レートは null になる）。
     *
     * @param currentRecords 現行作のフラットなスコア（`flattenScores()` の結果）
     * @returns 譜面キー（title_difficultyName）→ {@link ChartHistory}
     */
    const buildChartHistories = (currentRecords: ScoreRecord[]): Map<string, ChartHistory> => {
        /** 譜面キー → 各作品のスコア。 */
        const byChart = new Map<string, VersionEntry[]>();
        /** 譜面キー → 表示に必要な曲情報（過去にしか無い譜面の title/difficultyName 復元用）。 */
        const meta = new Map<string, { title: string; difficultyName: string }>();

        const push = (title: string, difficultyName: string, entry: VersionEntry) => {
            const key = chartKey(title, difficultyName);
            if (!byChart.has(key)) {
                byChart.set(key, []);
                meta.set(key, { title, difficultyName });
            }
            byChart.get(key)!.push(entry);
        };

        // 過去作ぶん
        pastRows.value.forEach(row => {
            push(row.t, row.d, {
                version: row.v,
                score: row.s ?? 0,
                scoreRate: null, // maxScore 確定後にまとめて埋める
                clearType: row.c || 'NO PLAY',
                djLevel: row.j || '---',
                missCount: row.m,
                difficultyLevel: row.l,
                isCurrent: false,
            });
        });

        // 現行作ぶん
        currentRecords.forEach(rec => {
            push(rec.title, rec.difficultyName, {
                version: CURRENT_VERSION,
                score: rec.score,
                scoreRate: null,
                clearType: rec.clearType,
                djLevel: rec.djLevel,
                missCount: rec.missCount,
                difficultyLevel: rec.difficultyLevel,
                isCurrent: true,
            });
        });

        const result = new Map<string, ChartHistory>();

        byChart.forEach((entries, key) => {
            const { title, difficultyName } = meta.get(key)!;

            // 作品昇順に並べる（歴代テーブル・バージョンストリップの表示順）。
            entries.sort((a, b) => a.version - b.version);

            // スコアレートは現行の曲マスタのノーツ数を分母に算出する。
            // 譜面改訂でノーツ数が変わった曲では過去作のレートが僅かにずれるが、
            // 当時のノーツ数は CSV から復元できないため現行基準で統一する。
            const maxScore = getSongMaxScore(title, difficultyName);
            if (maxScore > 0) {
                entries.forEach(e => { e.scoreRate = (e.score / maxScore) * 100; });
            }

            const current = entries.find(e => e.isCurrent) ?? null;

            // 歴代ベストスコアの作品を求める。
            // 同値の場合は新しい作品を優先する（entries は昇順なので > 比較でよい）。
            let bestScore = entries[0];
            entries.forEach(e => {
                if (e.score > bestScore.score) bestScore = e;
            });

            result.set(key, {
                title,
                difficultyName,
                entries,
                current,
                bestScore,
                scoreDelta: current ? current.score - bestScore.score : null,
                existsInCurrent: current !== null,
                maxScore,
            });
        });

        return result;
    };

    /**
     * 【関数の役割】 取得済みの過去作スコアを譜面単位の最良値に畳んで返す。
     *
     * IIDX 実機と同じく「ベストスコア / ベストランプ / ベスト BP」は独立に扱うため、
     * それぞれ全作品を通じた最良値を採る。`version` は *ベストスコアを出した作品* を指す。
     *
     * @returns 譜面キー（title_difficultyName）→ 過去作の最良値
     */
    const pastBestByChart = (): Map<string, PastBest> => {
        const bestByChart = new Map<string, PastBest>();

        pastRows.value.forEach(row => {
            const key = chartKey(row.t, row.d);
            const score = row.s ?? 0;
            const clearType = row.c || 'NO PLAY';
            const prev = bestByChart.get(key);

            if (!prev) {
                bestByChart.set(key, {
                    score,
                    djLevel: row.j || '---',
                    pgreat: row.p ?? 0,
                    great: row.g ?? 0,
                    version: row.v,
                    clearType,
                    missCount: row.m,
                });
                return;
            }
            if (score > prev.score) {
                prev.score = score;
                prev.djLevel = row.j || '---';
                prev.pgreat = row.p ?? 0;
                prev.great = row.g ?? 0;
                prev.version = row.v;
            }
            if (CLEAR_TYPE_RANK[clearType] > CLEAR_TYPE_RANK[prev.clearType]) prev.clearType = clearType;
            if (row.m != null && (prev.missCount === null || row.m < prev.missCount)) prev.missCount = row.m;
        });

        return bestByChart;
    };

    /**
     * 【関数の役割】 スコアレコード配列に過去作のベストを重ね、「歴代ベスト」版の配列を返す。
     *
     * 各譜面について、過去作のスコアが現行作を上回っていればそちらで上書きする。
     * IIDX 実機と同じく「ベストスコア / ベストランプ / ベスト BP」は独立に扱うため、
     * それぞれ全作品を通じた最良値を採用する（既存の {@link Score} 側の
     * ベストレコードマージと同じ考え方）。EX スコアが過去作由来になった行は
     * `allTimeVersion` にその作品番号が入り、UI 側でバッジ表示できる。
     *
     * スコアレートと BEAT-PT は上書き後のスコアで再計算する。
     * 現行の曲マスタに無い譜面（削除曲）は `maxScore` が 0 でレートを出せないため、
     * この変換の対象にならない（＝歴代 PT にも寄与しない）。
     *
     * 注意: この関数の結果を、サーバーへ保存される値（履歴ログの BEAT-PT など）に
     * 流し込んではいけない。あくまで表示用の重ね合わせである。
     *
     * @param records 現行作ベースのレコード（`flattenScores()` の結果など）
     * @returns 歴代ベストを反映した新しい配列（入力は変更しない）
     */
    const applyAllTimeBest = (records: ScoreRecord[]): ScoreRecord[] => {
        if (pastRows.value.length === 0) return records;

        const bestByChart = pastBestByChart();

        return records.map(rec => {
            const past = bestByChart.get(chartKey(rec.title, rec.difficultyName));
            if (!past) return rec;

            const scoreWins = past.score > rec.score;
            const clearWins = CLEAR_TYPE_RANK[past.clearType] > CLEAR_TYPE_RANK[rec.clearType];
            const missWins = past.missCount != null
                && (rec.missCount === null || rec.missCount === undefined || past.missCount < rec.missCount);
            if (!scoreWins && !clearWins && !missWins) return rec;

            const merged: ScoreRecord = { ...rec };

            if (scoreWins) {
                merged.score = past.score;
                merged.djLevel = past.djLevel;
                merged.pgreat = past.pgreat;
                merged.great = past.great;
                merged.allTimeVersion = past.version;

                // レートと BEAT-PT はスコアが変わったので再計算する。
                merged.scoreRate = rec.maxScore > 0 ? (past.score / rec.maxScore) * 100 : rec.scoreRate;
                // flattenScores と同じ除外規則: 高難度 HYPER は BEAT-Tier の対象外。
                const isHyperNonTarget = rec.difficultyName === 'HYPER'
                    && (rec.difficultyLevel ?? 0) >= 11;
                merged.beatTierPoints = isHyperNonTarget
                    ? 0
                    : calculatePoints(merged.scoreRate, rec.informalRank);
            }
            if (clearWins) merged.clearType = past.clearType;
            if (missWins) merged.missCount = past.missCount;

            return merged;
        });
    };

    return {
        /**
         * 過去作を 1 作でも取り込み済みか。「歴代ベストを反映」トグルの表示可否に使う。
         * 判定には軽量な {@link fetchSummary} の結果を使うので、
         * 重い `/past/best` を取りに行かずに済む。
         */
        hasPastImports: computed(() => summary.value.some(s => s.chartCount > 0)),
        applyAllTimeBest,
        pastBestByChart,
        /** `/api/scores/past/best` の生データ。通常は {@link buildChartHistories} 経由で使う。 */
        pastRows,
        /** 過去スコアを取得済みか。 */
        isLoaded,
        /** 取得中フラグ。 */
        isLoading,
        /** 作品別の取り込み状況。 */
        summary,
        fetchPastBest,
        fetchSummary,
        uploadPastScores,
        deletePastVersion,
        buildChartHistories,
    };
}
