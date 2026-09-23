/**
 * 【ユーティリティの役割】 スコアロードマップのレベル達成判定（画面とプレイ成果レポートで共用）。
 *
 * サーバー側の ScoreRoadmapService.computeRanking と同じ規則。変えるときは 3 か所を揃えること:
 *  - 達成 = プレー済み目標 ≥ min(n, max(2, ⌈n/3⌉)) かつ 達成数 × 3 ≥ プレー済み × 2（n = レベルの目標数、n > 0）
 *  - 完全制覇 = レベルの全目標（未プレー含む）を達成
 *  - その人のレベル = 達成レベルの最大番号
 */

export type RoadmapLine = 'aaa' | 'maxMinus';
export const ROADMAP_LINES: RoadmapLine[] = ['aaa', 'maxMinus'];
/** ラインの桶（理論値の 1/180 単位）。160 以上 = AAA、170 以上 = MAX-。 */
export const ROADMAP_LINE_BUCKET: Record<RoadmapLine, number> = { aaa: 160, maxMinus: 170 };
export const ROADMAP_LINE_LABEL: Record<RoadmapLine, string> = { aaa: 'AAA', maxMinus: 'MAX-' };

/** API `/api/scores/score-roadmap` の charts[] のうち判定に要る部分。 */
export interface RoadmapChartLike {
  i: number;
  title: string;
  difficultyName: string;
  level: number;
  notes: number;
  /** 固定したレベル表での番号。表に無い譜面（プレー人数 200 人未満）は無し。 */
  levels?: Record<RoadmapLine, number>;
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
export interface RoadmapSummary { levels: RoadmapLevelState[]; myLevel: number; clearedCount: number; completeCount: number }

/**
 * 【関数の役割】 全レベルの達成状況をまとめて判定する。
 *
 * @param charts     譜面（levels の無い譜面は無視）
 * @param levelCount レベル表のレベル数（slots.length）
 * @param buckets    譜面 i → 歴代ベストの桶（未プレーは無し）
 */
export function summarizeRoadmap(charts: RoadmapChartLike[], levelCount: number, buckets: Map<number, number>): RoadmapSummary {
  const levels: RoadmapLevelState[] = Array.from({ length: levelCount }, (_, k) =>
    ({ no: k + 1, n: 0, played: 0, done: 0, cleared: false, complete: false }));
  for (const c of charts) {
    if (!c.levels) continue;
    const b = buckets.get(c.i);
    for (const line of ROADMAP_LINES) {
      const lv = levels[c.levels[line] - 1];
      if (!lv) continue;
      lv.n++;
      if (b == null) continue;
      lv.played++;
      if (b >= ROADMAP_LINE_BUCKET[line]) lv.done++;
    }
  }
  let myLevel = 0, clearedCount = 0, completeCount = 0;
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
