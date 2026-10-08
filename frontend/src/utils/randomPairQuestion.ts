/**
 * randomPairQuestion.ts
 *
 * 配置アンケート「どっちが押しやすい？」（components/RandomPairSurvey.vue）の 1 問を作る。
 * 譜面 1 つについて 2 つの並びと、見比べる区間（連続した数小節）を選ぶ。答えは減点の形ごとの係数の学習
 * （scripts/fit-random-weights.mts）に使うので、少ない問題数で係数が決まるよう次の 3 通りを混ぜる。
 * 加えて、適当に押していないかを見る確認問題を混ぜる（どれも画面上は普通の問題と見分けがつかない）。
 *
 * - close（{@link CLOSE_SHARE}）: 今の評価では僅差なのに、減点の形の内訳が大きく違う 2 つ。今の評価が一番迷う組で、係数の比を決めるのに効く
 * - random（{@link RANDOM_SHARE}）: 無作為な 2 つ。偏りなく全体を見る（今の評価の当たり具合を測る基準にもなる）
 * - offmir（{@link OFFMIR_SHARE}）: 正規か MIRROR と、無作為な並び。オプション投票（正規・MIRROR の票）と同じ物差しに乗せる
 * - obvious（残り）: 確認問題。5,040 通りの上位 1% と下位 1% の並びを、今の評価で差がはっきりする区間（{@link OBVIOUS_MIN_GAP}）で
 *   見せる。普通に見ていれば上位の方を選ぶはず。差のはっきりする区間が無ければ random として出す。
 *   答えは人ごとの信頼度を測るのにだけ使い、係数の学習には入れない（今の評価から作った答えなので）
 * - repeat: 確認問題。前に答えた問題の左右を入れ替えて出し直す（RandomPairSurvey.vue が作る。ここでは作らない）
 *
 * 区間は、2 つの並びの減点の形ごとの差が一番大きい区間（の上位からくじ引き）。差の無い区間を見せても答えが学習に効かないため。
 * 重い計算（5,040 通りの評価）があるので、画面からは Web Worker（workers/randomPairWorker.ts）で呼ぶ。
 */
import type { ChartTimeline } from './chartPlayback.ts';
import { evaluateRandom, makeWindowScorer, scoreOf, emptyMetrics, PENALTY_KEYS, type RandomMetrics } from './randomEval.ts';

export type PairStrategy = 'close' | 'random' | 'offmir' | 'obvious' | 'repeat';

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

const CLOSE_SHARE = 0.45;
const RANDOM_SHARE = 0.3;
const OFFMIR_SHARE = 0.2;
/** 確認問題（obvious）で、上位・下位とみなす割合 */
const OBVIOUS_TAIL = 0.01;
/** 確認問題で、区間の総合の差がこの割合（差 ÷ 平均）以上ある区間だけを使う */
const OBVIOUS_MIN_GAP = 0.3;
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
  let strategy: PairStrategy = roll < CLOSE_SHARE ? 'close'
    : roll < CLOSE_SHARE + RANDOM_SHARE ? 'random'
    : roll < CLOSE_SHARE + RANDOM_SHARE + OFFMIR_SHARE ? 'offmir' : 'obvious';
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
  } else if (strategy === 'obvious') {
    const tail = Math.max(1, Math.floor(cands.length * OBVIOUS_TAIL));
    a = cands[Math.floor(rnd() * tail)].pattern;
    b = cands[cands.length - 1 - Math.floor(rnd() * tail)].pattern;
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
  let w: (typeof windows)[number];
  if (strategy === 'obvious') {
    // a（上位）の方が b（下位）よりはっきり押しやすい区間。無ければ普通の問題として出す
    const gap = (x: (typeof windows)[number]) => {
      const sa = scoreOf(x.sumA), sb = scoreOf(x.sumB);
      return (sb - sa) / ((sa + sb) / 2 + 1);
    };
    const clear = windows.filter(x => gap(x) >= OBVIOUS_MIN_GAP).sort((x, y) => gap(y) - gap(x));
    if (clear.length > 0) {
      w = pick(clear.slice(0, WINDOW_TOP));
    } else {
      strategy = 'random';
      windows.sort((x, y) => y.diff - x.diff);
      w = pick(windows.slice(0, WINDOW_TOP));
    }
  } else {
    windows.sort((x, y) => y.diff - x.diff);
    w = pick(windows.slice(0, WINDOW_TOP));
  }

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
