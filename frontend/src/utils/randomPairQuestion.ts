/**
 * randomPairQuestion.ts
 *
 * 配置アンケート「どっちが押しやすい？」（components/RandomPairSurvey.vue）の 1 問を作る。
 * 譜面 1 つについて 2 つの並びと、見比べる区間（連続した数小節）を選ぶ。答えは減点の形ごとの係数の学習
 * （scripts/fit-random-weights.mts）に使うので、少ない問題数で係数が決まるよう次の 3 通りを混ぜる。
 *
 * - close（{@link CLOSE_SHARE}）: 今の評価では僅差なのに、減点の形の内訳が大きく違う 2 つ。今の評価が一番迷う組で、係数の比を決めるのに効く
 * - random（{@link RANDOM_SHARE}）: 無作為な 2 つ。偏りなく全体を見る（今の評価の当たり具合を測る基準にもなる）
 * - offmir（残り）: 正規か MIRROR と、無作為な並び。オプション投票（正規・MIRROR の票）と同じ物差しに乗せる
 *
 * 区間は、2 つの並びの減点の形ごとの差が一番大きい区間（の上位からくじ引き）。差の無い区間を見せても答えが学習に効かないため。
 * 重い計算（5,040 通りの評価）があるので、画面からは Web Worker（workers/randomPairWorker.ts）で呼ぶ。
 */
import type { ChartTimeline } from './chartPlayback.ts';
import { evaluateRandom, makeWindowScorer, scoreOf, emptyMetrics, PENALTY_KEYS, type RandomMetrics } from './randomEval.ts';

export type PairStrategy = 'close' | 'random' | 'offmir';

export interface PairQuestion {
  patternLeft: string;
  patternRight: string;
  /** 見せる区間（秒）と表示上の小節番号（終わりの小節を含む） */
  startTime: number;
  endTime: number;
  startMeasure: number;
  endMeasure: number;
  strategy: PairStrategy;
  /** 今の評価での、その区間の左・右の総合（少ないほど押しやすい） */
  modelLeft: number;
  modelRight: number;
}

const CLOSE_SHARE = 0.5;
const RANDOM_SHARE = 0.3;
/** close で B を探す範囲（A の順位の前後）と、その中から試す数 */
const CLOSE_NEIGHBORS = 150;
const CLOSE_TRIES = 40;
/** 区間は差の大きい上位この数からくじ引き（同じ譜面で同じ区間ばかりにならないように） */
const WINDOW_TOP = 3;

const OFF = '1234567';
const MIRROR = '7654321';

/**
 * 【関数の役割】 譜面 1 つの 1 問を作る。
 *
 * @param tl        譜面
 * @param side      プレイサイド（評価と表示をそろえる）
 * @param measures  見せる小節の数
 * @param rnd       0〜1 の乱数（テストで固定できるように渡す）
 * @returns 作れなければ null（小節が足りない・差のある区間が無い）
 */
export function makePairQuestion(tl: ChartTimeline, side: 1 | 2, measures: number, rnd: () => number = Math.random): PairQuestion | null {
  const ev = evaluateRandom(tl, side);
  const cands = ev.candidates;
  const pick = <T>(arr: readonly T[]) => arr[Math.floor(rnd() * arr.length)];
  const randomPattern = (not: string) => {
    for (;;) { const p = pick(cands).pattern; if (p !== not) return p; }
  };
  const l1 = (a: RandomMetrics, b: RandomMetrics) => PENALTY_KEYS.reduce((s, k) => s + Math.abs(a[k] - b[k]), 0);

  const roll = rnd();
  const strategy: PairStrategy = roll < CLOSE_SHARE ? 'close' : roll < CLOSE_SHARE + RANDOM_SHARE ? 'random' : 'offmir';
  let a: string;
  let b: string;
  if (strategy === 'close') {
    const ia = Math.floor(rnd() * cands.length);
    a = cands[ia].pattern;
    let best = -1;
    b = randomPattern(a);
    for (let t = 0; t < CLOSE_TRIES; t++) {
      const ib = Math.min(cands.length - 1, Math.max(0, ia + Math.round((rnd() * 2 - 1) * CLOSE_NEIGHBORS)));
      if (ib === ia) continue;
      const d = l1(cands[ia].metrics, cands[ib].metrics);
      if (d > best) { best = d; b = cands[ib].pattern; }
    }
  } else if (strategy === 'random') {
    a = pick(cands).pattern;
    b = randomPattern(a);
  } else {
    a = rnd() < 0.5 ? OFF : MIRROR;
    b = randomPattern(a);
  }

  // 小節ごとの減点を数え、連続した measures 小節の区間で差の大きいところを選ぶ
  const mt = tl.measureTimes;
  const bounds: number[] = Array.from(mt);
  if (bounds.length === 0 || bounds[bounds.length - 1] < tl.totalTime) bounds.push(tl.totalTime);
  const perMeasure: [number, number][] = [];
  for (let i = 0; i + 1 < bounds.length; i++) perMeasure.push([bounds[i], bounds[i + 1]]);
  if (perMeasure.length < measures) return null;
  const scorer = makeWindowScorer(tl, side);
  const ma = scorer(a, perMeasure);
  const mb = scorer(b, perMeasure);

  const windows: { s: number; diff: number; sumA: RandomMetrics; sumB: RandomMetrics }[] = [];
  for (let s = 0; s + measures <= perMeasure.length; s++) {
    const sumA = emptyMetrics();
    const sumB = emptyMetrics();
    for (let m = s; m < s + measures; m++) {
      for (const k of PENALTY_KEYS) { sumA[k] += ma[m][k]; sumB[k] += mb[m][k]; }
    }
    const diff = l1(sumA, sumB);
    if (diff > 0) windows.push({ s, diff, sumA, sumB });
  }
  if (windows.length === 0) return null;
  windows.sort((x, y) => y.diff - x.diff);
  const w = pick(windows.slice(0, WINDOW_TOP));

  // 左右はくじで決める（左ばかり選ぶ癖が学習に入らないように）
  const swap = rnd() < 0.5;
  const [pl, pr] = swap ? [b, a] : [a, b];
  const [sl, sr] = swap ? [w.sumB, w.sumA] : [w.sumA, w.sumB];
  return {
    patternLeft: pl,
    patternRight: pr,
    startTime: perMeasure[w.s][0],
    endTime: perMeasure[w.s + measures - 1][1],
    startMeasure: tl.firstMeasure + w.s,
    endMeasure: tl.firstMeasure + w.s + measures - 1,
    strategy,
    modelLeft: Math.round(scoreOf(sl) * 1000) / 1000,
    modelRight: Math.round(scoreOf(sr) * 1000) / 1000,
  };
}
