/**
 * 【ユーティリティの役割】 スコアロードマップのレベル達成判定（画面とプレイ成果レポートで共用）。
 *
 * サーバー側の ScoreRoadmapService.computeRanking と同じ規則。変えるときは 3 か所を揃えること:
 *  - 達成 = プレー済み目標 ≥ min(n, max(2, ⌈n/3⌉)) かつ 達成数 × 3 ≥ プレー済み × 2（n = レベルの目標数、n > 0）
 *  - 完全制覇 = レベルの全目標（未プレー含む）を達成
 *  - その人のレベル = 達成レベルの最大番号（どのレベルも未達成なら null）
 *
 * 2026-09-24: AA ラインを追加。Lv.1 より易しい AA は Lv.0, −1, −2 … の「負のレベル」に入るので、
 * レベル番号は minLevel（≤ 1）〜 maxLevel。レベル 0 は実在するレベルなので「未達成」は null で表す。
 */

export type RoadmapLine = 'aa' | 'aaa' | 'maxMinus';
export const ROADMAP_LINES: RoadmapLine[] = ['aa', 'aaa', 'maxMinus'];
/** ラインの桶（理論値の 1/180 単位）。140 以上 = AA、160 以上 = AAA、170 以上 = MAX-。 */
export const ROADMAP_LINE_BUCKET: Record<RoadmapLine, number> = { aa: 140, aaa: 160, maxMinus: 170 };
export const ROADMAP_LINE_LABEL: Record<RoadmapLine, string> = { aa: 'AA', aaa: 'AAA', maxMinus: 'MAX-' };

/** API の model.levelTable のうち、レベルの範囲に要る部分。 */
export interface RoadmapLevelTableLike {
  /** レベル番号 − 1 → 0.02 枠（Lv.1〜）。 */
  slots: number[];
  /** −レベル番号 → 0.02 枠（Lv.0, −1, …）。AA 追加前の土台には無い。 */
  negSlots?: number[];
}

/** 一番易しい / 難しいレベルの番号。 */
export const roadmapMinLevel = (t: RoadmapLevelTableLike) => 1 - (t.negSlots?.length ?? 0);
export const roadmapMaxLevel = (t: RoadmapLevelTableLike) => t.slots.length;
/** レベル番号 → 0.02 枠。 */
export const roadmapSlotOf = (t: RoadmapLevelTableLike, no: number) => (no >= 1 ? t.slots[no - 1] : t.negSlots![-no]);

/** レベルの表示（未達成 = null は「—」）。 */
export const formatRoadmapLevel = (no: number | null) => (no == null ? '—' : `Lv.${no}`);

/** API `/api/scores/score-roadmap` の charts[] のうち判定に要る部分。 */
export interface RoadmapChartLike {
  i: number;
  title: string;
  difficultyName: string;
  level: number;
  notes: number;
  /** 固定したレベル表での番号。表に無い譜面（プレー人数 200 人未満）は無し。 */
  levels?: Partial<Record<RoadmapLine, number>>;
}

/** 判定に必要な最低プレー数。 */
export const minPlayedFor = (n: number) => Math.min(n, Math.max(2, Math.ceil(n / 3)));

/** 1 レベル分の判定。 */
export const isLevelCleared = (n: number, played: number, done: number) =>
  n > 0 && played >= minPlayedFor(n) && done * 3 >= played * 2;

/** 次の達成に必要な残り件数（未達成のプレー済み or 未プレーの目標を達成した場合）。 */
export const remainingToClear = (n: number, played: number, done: number) =>
  Math.max(0, Math.ceil((Math.max(played, minPlayedFor(n)) * 2) / 3) - done);

export interface RoadmapLevelState { no: number; n: number; played: number; done: number; cleared: boolean; complete: boolean }
export interface RoadmapSummary { levels: RoadmapLevelState[]; myLevel: number | null; clearedCount: number; completeCount: number }

/**
 * 【関数の役割】 全レベルの達成状況をまとめて判定する。
 *
 * @param charts  譜面（levels の無い譜面は無視）
 * @param table   レベル表（slots / negSlots）
 * @param buckets 譜面 i → 歴代ベストの桶（未プレーは無し）
 * @returns levels は易しい順（minLevel から）
 */
export function summarizeRoadmap(charts: RoadmapChartLike[], table: RoadmapLevelTableLike, buckets: Map<number, number>): RoadmapSummary {
  const minLevel = roadmapMinLevel(table), maxLevel = roadmapMaxLevel(table);
  const levels: RoadmapLevelState[] = Array.from({ length: maxLevel - minLevel + 1 }, (_, k) =>
    ({ no: minLevel + k, n: 0, played: 0, done: 0, cleared: false, complete: false }));
  for (const c of charts) {
    if (!c.levels) continue;
    const b = buckets.get(c.i);
    for (const line of ROADMAP_LINES) {
      const no = c.levels[line];
      if (no == null) continue;
      const lv = levels[no - minLevel];
      if (!lv) continue;
      lv.n++;
      if (b == null) continue;
      lv.played++;
      if (b >= ROADMAP_LINE_BUCKET[line]) lv.done++;
    }
  }
  let myLevel: number | null = null, clearedCount = 0, completeCount = 0;
  for (const lv of levels) {
    lv.cleared = isLevelCleared(lv.n, lv.played, lv.done);
    lv.complete = lv.n > 0 && lv.done === lv.n;
    if (lv.cleared) { myLevel = lv.no; clearedCount++; }
    if (lv.complete) completeCount++;
  }
  return { levels, myLevel, clearedCount, completeCount };
}

/** EX スコア → 桶（理論値 = notes × 2 の 1/180 単位）。API の桶と同じ計算。 */
export const scoreToBucket = (score: number, notes: number) =>
  notes > 0 ? Math.min(180, Math.floor((score * 90) / notes)) : 0;
