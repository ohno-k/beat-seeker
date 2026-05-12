import { ref } from 'vue';
import { API_BASE } from './constants';

/**
 * 【Composable の役割】 KENBAN-TIER / SARA-TIER 算出に使う「譜面ごとの皿率」を
 * `(title, difficulty_name) → scratchPct(%)` の Map で提供する。
 *
 * - 取得元: `GET /api/analysis/tendency-scratch-summary`
 * - 返却形式: `[{title, difficulty: "4"|"10", scratchPct}, ...]`
 * - "4" → "ANOTHER", "10" → "LEGGENDARIA" にマッピング
 *
 * モジュールトップに ref を置いているため、複数コンポーネントで呼んでも
 * 1 度の fetch を共有する（プロミスもキャッシュして並行リクエスト時の重複を防ぐ）。
 */

/** scratch summary 取得済みの Map。キーは `"<title>|ANOTHER"` 形式。 */
export const scratchPctMap = ref<Map<string, number>>(new Map());

/** ロード完了フラグ（UI が「未読み込みで 0pt 表示する間」を判別できるように公開）。 */
export const scratchPctLoaded = ref(false);

/** 並行 fetch を防ぐためのプロミスキャッシュ。 */
let fetchPromise: Promise<void> | null = null;

const DIFFICULTY_CODE_TO_NAME: Record<string, string> = {
    '4':  'ANOTHER',
    '10': 'LEGGENDARIA',
};

export function useScratchSummary() {
    /**
     * scratch summary を取得して `scratchPctMap` を更新する。
     * 取得済みなら即座に解決し、進行中なら同じプロミスを返す。
     */
    const loadScratchSummary = (): Promise<void> => {
        if (scratchPctLoaded.value) return Promise.resolve();
        if (fetchPromise) return fetchPromise;

        fetchPromise = fetch(`${API_BASE}/api/analysis/tendency-scratch-summary`)
            .then(res => {
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                return res.json() as Promise<Array<{ title: string; difficulty: string; scratchPct: number }>>;
            })
            .then(rows => {
                const m = new Map<string, number>();
                for (const r of rows) {
                    const diffName = DIFFICULTY_CODE_TO_NAME[r.difficulty];
                    if (!diffName) continue;
                    m.set(`${r.title}|${diffName}`, r.scratchPct);
                }
                scratchPctMap.value = m;
                scratchPctLoaded.value = true;
            })
            .catch(() => {
                // 失敗時は空 Map のまま。KENBAN/SARA-TIER は 0pt 表示になるだけで UI は壊れない。
                scratchPctLoaded.value = true;
            })
            .finally(() => {
                fetchPromise = null;
            });

        return fetchPromise;
    };

    return {
        scratchPctMap,
        scratchPctLoaded,
        loadScratchSummary,
    };
}
