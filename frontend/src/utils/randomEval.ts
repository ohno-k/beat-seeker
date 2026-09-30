/**
 * randomEval.ts
 *
 * RANDOM の配置評価（譜面再生の「当たり乱探し」）。鍵盤の並べ方 7! = 5,040 通りそれぞれについて、
 * 手の負荷を数えて押しやすさの順に並べる。
 *
 * RANDOM は鍵盤をレーンごと入れ替えるだけなので、同じ鍵盤の縦連打や総ノーツ数はどの並びでも変わらない。
 * 変わるのは「どの鍵盤がどちらの手に来るか」と「同じ手の中での位置関係」。
 *
 * 密度の高いところほど配置を優先する: どの指標も、打鍵ごとに周り ±{@link DENSITY_HALF} 秒のノーツ数（皿を含む）を
 * 譜面内の最大で割って {@link HARD_POWER} 乗した重みで数える（難所の配置ほど順位に効き、スカスカの区間はほとんど効かない）。
 *
 * 当たり配置の決め手として重く見る指標（譜面内の最良の並びを 0、最悪を 1 にして重みをかける）:
 * - 皿と同時に取れる（重み {@link SIMUL_WEIGHT}。ほかより低い）: 単発の皿と同じタイミングの鍵盤が、皿側の手に来る割合
 *   （皿と一緒に同じ手で取れる。1P の皿＋1 など）
 * - 以下 4 つは重み {@link KEY_WEIGHT}
 * - 両手にまたがる同時押し: 2 鍵以上の同時押しが左右の手にまたがる回数（少ないほど良い）。密集部の同時押しを
 *   片手ずつに収められる配置が当たり（rage against usual の 25-36-47-36-25-14 で皿側に 1・4・7、嘆きの樹の白黒分けなど）
 * - 連皿中は逆の手: 連続スクラッチ（BPM 140 の 16 分以上の間隔で 3 回以上続く皿）の最中の鍵盤が、
 *   皿を回さない方の手に来る割合（連皿中は皿側の手が塞がるので、同じ手に来ない方が良い）
 * - 16 分が左右に割れる（難所重視）: 16 分間隔で続く 2 つの打鍵（和音）で、どちらの手も片方の打鍵にしか鍵盤が無い
 *   （手が形を切り替えずに済む。デニム配置などが「割れる」）割合。組ごとに周り ±{@link DENSITY_HALF} 秒のノーツ数の
 *   {@link HARD_POWER} 乗で重みをつけ、譜面の密度の高い区間（難所）ほど効かせる。嘆きの樹 59 小節からの密集地帯で
 *   黒黒黒白白白白（2461357 など）・白白白黒白黒黒（3572146 など）が上に来るのはこれ
 * - 片手の速い連打: 同じ手で {@link FAST_GAP} 秒未満に続く、別レーンへの打鍵（離れたレーンほど重く数える）。
 *   16 分の割れは「割れた組」しか見ず、階段やトリルが多い譜面では多くの並びが同点になるので、
 *   同じ手の中の動きの大きさで差をつける（嘆きの樹で 2461357 のような白黒分けが上に来るのはこれ）。
 *   1P の皿を回さない手は運指（4 人差し指・5 親指・6 中指・7 薬指/小指）で重さを変える:
 *   親指だけの打鍵と親指以外の打鍵の交互は軽く（{@link THUMB_ALT_COST}）、6・7 のトリルは重く（{@link TRILL67_COST}）。
 *   2 鍵ずつの同時押しの交互は、やりやすい順に 45⇔67・57⇔46・47⇔56 を軽く数える（{@link TWO_TWO}）。
 *   1P の皿側の手（レーン 1〜3）の交互も、やりやすい順に 13⇔2・1⇔23・12⇔3 を軽く数える（{@link SCRATCH_ALT}）。
 *   この 3 つは 16 分の割れでも、同じ手の続きでも（程度に応じて）割れたものとして数える
 *
 * - 16 分縦連の衝突（重み {@link KEY_WEIGHT}）: 16 分で続く縦連のある鍵盤と、それと一緒に動かない（同じ和音で出ない）
 *   別の連打鍵盤が同じ手に来る量（少ないほど良い）。冥の 55〜62 小節の 2 鍵・3 鍵の連打は左右に分けるのが当たり。
 *   rage against usual の 25⇔36 のように一緒に動く鍵盤や、16 分縦連がほとんど無い譜面には効かないよう、
 *   この指標だけは譜面内の最良〜最悪ではなく固定の大きさ {@link CLASH_SCALE} で割る
 * - きれいな形（重み {@link CLEAN_WEIGHT}）: 速く続く 3 打鍵が同じずらし幅で続く（階段・二重階段が崩れない）量（多いほど良い）
 *
 * ほかの負荷（同じく 0〜1 にそろえて重み 1 で足す）:
 * - 皿の前後: 皿と同時ではないが前後 {@link SCRATCH_WINDOW} 秒に、皿側の手へ来るノーツの数（連皿の最中は除く）
 * - 片手の最大密度: どちらかの手の 1 秒あたりのノーツ数の最大
 *
 * 1 つの手が自分のレーンの鍵盤をまとめて押す和音（白黒分けで 246 を片手など）は普通の押し方なので、負荷に数えない。
 *
 * 総合は小さいほど押しやすい。手の分け方は皿側の手が皿に近い 3 レーン（1P なら 1〜3、2P なら 5〜7）を持つ形に固定
 * （{@link SCRATCH_HAND_LANES}。1P の運指 4 人差し指・5 親指・6 中指・7 薬指/小指 と合わせてある）。
 * 評価は並びの目安で、指の置き方（運指）や CN の押しっぱなしは考えていない。
 */
import type { ChartTimeline } from './chartPlayback';

/** 皿側の手が持つレーンの数（皿に近い側から）。1P = レーン 1〜3、2P = レーン 5〜7 */
export const SCRATCH_HAND_LANES = 3;
/** 皿の前後とみなす皿との時間差（秒）。 */
export const SCRATCH_WINDOW = 0.1;
/** 片手の速い連打とみなす間隔（秒。BPM 150 の 16 分 = 0.1 秒）。 */
export const FAST_GAP = 0.105;
/** 難所重視の重み: 打鍵の周り ±DENSITY_HALF 秒のノーツ数（皿を含む）を譜面内の最大で割って HARD_POWER 乗 */
const DENSITY_HALF = 1.0;
const HARD_POWER = 4;
/** 16 分とみなす打鍵の間隔（tick。4 分 = 96、16 分 = 24。わずかに詰まった配置も含める） */
const SIXTEENTH_MIN = 20;
const SIXTEENTH_MAX = 24;
/** 連続スクラッチとみなす皿どうしの間隔（秒。BPM 140 の 16 分 = 60 / 140 / 4）と、続く回数 */
const STREAM_GAP = 60 / 140 / 4 + 1e-6;
const STREAM_MIN_NOTES = 3;
/** 「皿と同時に取れる」「連皿中は逆の手」「16 分の左右交互」「片手の速い連打」の重み（それぞれ譜面内の最良〜最悪を 0〜1 にした不足分にかける。ほかの負荷は中央値で 1 前後にそろえてある） */
export const KEY_WEIGHT = 4;
/** 16 分縦連の衝突を割る固定の大きさ（密度の重みつきの量。これより差の小さい譜面では重みが比例して小さくなる） */
const CLASH_SCALE = 5;
/** 16 分縦連とみなす同じ鍵盤の間隔（tick。16 分 = 24）と、連打とみなす間隔（4 分 = 96） */
const JACK16_TICKS = 24;
const REPEAT_TICKS = 96;
/** 16 分縦連の衝突を数える窓（秒） */
const CLASH_WINDOW = 1.0;
/** 「きれいな形」の重み */
export const CLEAN_WEIGHT = 2;
/** 「皿と同時に取れる」の重み（当たりの要素ではあるが優先度は高くない） */
export const SIMUL_WEIGHT = 1.5;
/** 片手の最大密度を数える窓（秒）。 */
const DENSITY_WINDOW = 1.0;

export interface RandomMetrics {
  /** 単発の皿と同時の鍵盤のうち、皿側の手に来る数 / 単発の皿と同時の鍵盤の総数（いずれも密度の重みつき） */
  scratchSimulOk: number;
  scratchSimulTotal: number;
  /** 連皿の最中の鍵盤のうち、皿を回さない方の手に来る数 / 連皿の最中の鍵盤の総数（いずれも密度の重みつき） */
  streamOk: number;
  streamTotal: number;
  /** 16 分で続く打鍵の組のうち、左右に割れる組の重み（難所重視）の合計 / 全部の組の重みの合計 */
  split16: number;
  split16Total: number;
  scratchNear: number;
  fastSameHand: number;
  /** 16 分縦連のある鍵盤と、一緒に動かない別の連打鍵盤が同じ手に来る量（密度の重みつき。少ないほど良い） */
  jackClash: number;
  /** 速く続く 3 打鍵が同じずらし幅で続く量 / 対象の 3 打鍵の総量（密度の重みつき。多いほど良い） */
  cleanShape: number;
  cleanTotal: number;
  /** 2 鍵以上の同時押しが左右の手にまたがる回数（密度の重みつき。少ないほど良い） */
  chordStraddle: number;
  /** 1P の皿を回さない手の、6 と 7 の速い交互（トリル）の回数（fastSameHand に重く含まれている。表示用） */
  trill67: number;
  peakHandDensity: number;
}

export interface RandomCandidate {
  /** 左のレーンから元の鍵盤番号（例: "3726145"） */
  pattern: string;
  metrics: RandomMetrics;
  /** 総合（小さいほど押しやすい） */
  score: number;
  /** 5,040 通り中の順位（1 始まり） */
  rank: number;
}

export interface RandomEvaluation {
  /** 押しやすい順 */
  candidates: RandomCandidate[];
  /** 並び → 候補 */
  byPattern: Map<string, RandomCandidate>;
  /** 各指標の全並びでの最良・最悪（総合の正規化に使った値） */
  ranges: Record<'scratchSimulOk' | 'streamOk' | 'split16' | 'chordStraddle' | 'jackClash' | 'cleanShape' | 'fastSameHand' | 'scratchNear' | 'peakHandDensity', [number, number]>;
}

/** 鍵盤の打鍵（皿を除く）。時刻順で、同じ時刻の和音は連続して並ぶ。 */
interface KeyEvent { time: number; tick: number; key: number }

/** 1〜7 の順列を全部作る（辞書順）。 */
export function allPatterns(): string[] {
  const out: string[] = [];
  const used = new Array(8).fill(false);
  const cur: number[] = [];
  const rec = () => {
    if (cur.length === 7) { out.push(cur.join('')); return; }
    for (let k = 1; k <= 7; k++) {
      if (used[k]) continue;
      used[k] = true; cur.push(k);
      rec();
      cur.pop(); used[k] = false;
    }
  };
  rec();
  return out;
}

/** 並びによらず決まる譜面側の情報（5,040 通りで共有する）。 */
interface Prepared {
  events: KeyEvent[];
  /** 和音ごとの events の範囲 [開始, 終了) */
  chords: [number, number][];
  /** 単発の皿と同じ tick の打鍵か（連皿の最中は含めない） */
  simulScratch: boolean[];
  /** 連皿の最中（連皿の最初の皿から最後の皿まで）の打鍵か */
  inStream: boolean[];
  /** 皿と同時ではないが前後 SCRATCH_WINDOW 秒に皿がある打鍵か（連皿の最中は含めない） */
  nearScratch: boolean[];
  /** 16 分間隔で続く和音の組（chords の添字 i と i + 1） */
  pairs16: number[];
  /** pairs16 と同じ並びの重み（難所重視。後ろの打鍵の chordWeights） */
  pairWeights: number[];
  /** 和音ごとの重み（難所重視） */
  chordWeights: number[];
  /** 鍵盤の組 (a, b) の 16 分縦連の衝突の量（a に 16 分縦連、b に連打、一緒に動かない分）。並びによらない */
  clashPair: number[][];
  /** 速く続く 3 打鍵（chords の添字 c-2, c-1, c）で、鍵盤の数がそろっているものの c と重み */
  triples: { c: number; w: number }[];
}

function prepare(tl: ChartTimeline): Prepared {
  const events: KeyEvent[] = [];
  const scratchTimes: number[] = [];
  const scratchTicks = new Set<number>();
  const push = (time: number, tick: number, key: number) => {
    if (key === 0) { scratchTimes.push(time); scratchTicks.add(Math.round(tick)); }
    else events.push({ time, tick, key });
  };
  for (let i = 0; i < tl.noteKeys.length; i++) push(tl.noteTimes[i], tl.noteTicks[i], tl.noteKeys[i]);
  for (let i = 0; i < tl.cnKeys.length; i++) if (tl.cnFlags[i] & 1) push(tl.cnStartTimes[i], tl.cnStartTicks[i], tl.cnKeys[i]);
  events.sort((a, b) => a.tick - b.tick || a.key - b.key);
  scratchTimes.sort((a, b) => a - b);

  // 連皿: STREAM_GAP 以内の間隔で STREAM_MIN_NOTES 回以上続く皿。最初の皿〜最後の皿の区間
  const streams: [number, number][] = [];
  for (let i = 0; i < scratchTimes.length;) {
    let j = i;
    while (j + 1 < scratchTimes.length && scratchTimes[j + 1] - scratchTimes[j] <= STREAM_GAP) j++;
    if (j - i + 1 >= STREAM_MIN_NOTES) streams.push([scratchTimes[i], scratchTimes[j]]);
    i = j + 1;
  }
  const inStream = events.map(e => streams.some(([a, b]) => e.time >= a - 1e-6 && e.time <= b + 1e-6));
  const simulScratch = events.map((e, k) => !inStream[k] && scratchTicks.has(Math.round(e.tick)));
  const nearScratch = events.map((e, k) => !inStream[k] && !scratchTicks.has(Math.round(e.tick))
    && hasNear(scratchTimes, e.time, SCRATCH_WINDOW));

  const chords: [number, number][] = [];
  for (let i = 0; i < events.length;) {
    let j = i;
    while (j < events.length && Math.abs(events[j].tick - events[i].tick) < 1e-6) j++;
    chords.push([i, j]);
    i = j;
  }
  const pairs16: number[] = [];
  for (let c = 0; c + 1 < chords.length; c++) {
    const gap = events[chords[c + 1][0]].tick - events[chords[c][0]].tick;
    if (gap >= SIXTEENTH_MIN && gap <= SIXTEENTH_MAX) pairs16.push(c);
  }
  // 和音ごとの重み: 周り ±DENSITY_HALF 秒のノーツ数（鍵盤と皿）。密度の高いところほど重く
  const times = [...events.map(e => e.time), ...scratchTimes].sort((a, b) => a - b);
  const density = chords.map(([i]) => {
    const t = events[i].time;
    return upperBoundNum(times, t + DENSITY_HALF) - lowerBoundNum(times, t - DENSITY_HALF);
  });
  const dMax = Math.max(1, ...density);
  const chordWeights = density.map(d => Math.pow(d / dMax, HARD_POWER));
  const pairWeights = pairs16.map(c => chordWeights[c + 1]);

  // 16 分縦連の衝突: 窓ごとに、鍵盤ごとの 16 分縦連・連打・打鍵の量と、鍵盤の組が同じ和音で出た量を数え、
  // 組 (a, b) ごとに min(a の 16 分縦連, b の連打) × (b が a と別に出る割合) を足す
  type Win = { jack16: number[]; rep: number[]; hits: number[]; together: number[][] };
  const wins = new Map<number, Win>();
  const lastTick = new Array<number>(8).fill(-Infinity);
  chords.forEach(([i, j], c) => {
    const id = Math.floor(events[i].time / CLASH_WINDOW);
    let W = wins.get(id);
    if (!W) {
      W = { jack16: new Array(8).fill(0), rep: new Array(8).fill(0), hits: new Array(8).fill(0),
        together: Array.from({ length: 8 }, () => new Array(8).fill(0)) };
      wins.set(id, W);
    }
    const w = chordWeights[c];
    for (let k = i; k < j; k++) {
      const key = events[k].key;
      const gap = events[k].tick - lastTick[key];
      if (gap <= JACK16_TICKS) W.jack16[key] += w;
      if (gap <= REPEAT_TICKS) W.rep[key] += w;
      W.hits[key] += w;
      lastTick[key] = events[k].tick;
      for (let k2 = i; k2 < j; k2++) if (k2 !== k) W.together[key][events[k2].key] += w;
    }
  });
  const clashPair = Array.from({ length: 8 }, () => new Array<number>(8).fill(0));
  for (const W of wins.values()) {
    for (let a = 1; a <= 7; a++) {
      if (W.jack16[a] <= 0) continue;
      for (let b = 1; b <= 7; b++) {
        if (b === a || W.rep[b] <= 0) continue;
        const apart = 1 - Math.min(1, W.together[a][b] / Math.max(W.hits[b], 1e-9));
        clashPair[a][b] += Math.min(W.jack16[a], W.rep[b]) * apart;
      }
    }
  }

  // きれいな形の対象: 速く続き（どちらの間隔も FAST_GAP 未満）、鍵盤の数がそろった 3 打鍵
  const triples: { c: number; w: number }[] = [];
  for (let c = 2; c < chords.length; c++) {
    const [a, b, cc] = [chords[c - 2], chords[c - 1], chords[c]];
    if (events[b[0]].time - events[a[0]].time >= FAST_GAP || events[cc[0]].time - events[b[0]].time >= FAST_GAP) continue;
    if (a[1] - a[0] !== b[1] - b[0] || b[1] - b[0] !== cc[1] - cc[0]) continue;
    triples.push({ c, w: chordWeights[c] });
  }
  return { events, chords, simulScratch, inStream, nearScratch, pairs16, pairWeights, chordWeights, clashPair, triples };
}

/**
 * 【関数の役割】 5,040 通りすべてを評価して押しやすい順に並べる。
 *
 * @param tl          譜面
 * @param side        1P（皿が左）/ 2P（皿が右）
 */
export function evaluateRandom(tl: ChartTimeline, side: 1 | 2): RandomEvaluation {
  const prep = prepare(tl);
  const patterns = allPatterns();
  const raw: RandomMetrics[] = patterns.map(p => measure(prep, p, side));

  // どの指標も、この譜面で一番良い並びを 0、一番悪い並びを 1 に引き伸ばしてから重みをかける
  // （譜面ごとに並びで動かせる幅が違う。両手の和音はどの並びでも交互にならない等）
  const range = (vals: number[], higherIsBetter: boolean): [number, number] => {
    const hi = Math.max(...vals), lo = Math.min(...vals);
    return higherIsBetter ? [hi, lo] : [lo, hi];
  };
  const ranges = {
    scratchSimulOk: range(raw.map(m => m.scratchSimulOk), true),
    streamOk: range(raw.map(m => m.streamOk), true),
    split16: range(raw.map(m => m.split16), true),
    chordStraddle: range(raw.map(m => m.chordStraddle), false),
    jackClash: range(raw.map(m => m.jackClash), false),
    cleanShape: range(raw.map(m => m.cleanShape), true),
    fastSameHand: range(raw.map(m => m.fastSameHand), false),
    scratchNear: range(raw.map(m => m.scratchNear), false),
    peakHandDensity: range(raw.map(m => m.peakHandDensity), false),
  };
  const shortfall = (v: number, [best, worst]: [number, number]) => (best !== worst ? (v - best) / (worst - best) : 0);

  const candidates: RandomCandidate[] = patterns.map((pattern, i) => {
    const m = raw[i];
    const score = SIMUL_WEIGHT * shortfall(m.scratchSimulOk, ranges.scratchSimulOk)
      + KEY_WEIGHT * (shortfall(m.streamOk, ranges.streamOk) + shortfall(m.split16, ranges.split16) + shortfall(m.fastSameHand, ranges.fastSameHand)
        + shortfall(m.chordStraddle, ranges.chordStraddle)
        + (m.jackClash - ranges.jackClash[0]) / Math.max(ranges.jackClash[1] - ranges.jackClash[0], CLASH_SCALE))
      + CLEAN_WEIGHT * shortfall(m.cleanShape, ranges.cleanShape)
      + shortfall(m.scratchNear, ranges.scratchNear) + shortfall(m.peakHandDensity, ranges.peakHandDensity);
    return { pattern, metrics: m, score, rank: 0 };
  });
  candidates.sort((a, b) => a.score - b.score || a.pattern.localeCompare(b.pattern));
  // 同点は同じ順位
  candidates.forEach((c, i) => {
    c.rank = i > 0 && Math.abs(c.score - candidates[i - 1].score) < 1e-9 ? candidates[i - 1].rank : i + 1;
  });
  return { candidates, byPattern: new Map(candidates.map(c => [c.pattern, c])), ranges };
}

/** 1P の皿を回さない手で親指が押すレーン。 */
const THUMB_LANE = 5;
/** 親指だけの打鍵と、親指を使わない打鍵の速い交互（467 と 5 の 16 分トリルなど）の重さ。ほかの速い移動は 1 以上 */
const THUMB_ALT_COST = 0.2;
/** 6 と 7（中指と薬指/小指）の速い交互の重さ。指の作り上いちばん押しにくいので、隣のレーンへの普通の移動（1.0）より大きく減点する */
const TRILL67_COST = 4;

/**
 * 1P の皿を回さない手（レーン 4〜7）の 2 鍵ずつの同時押しの交互。ユーザー（上級者）の案内による、やりやすい順:
 * 45⇔67（人差し指+親指 ⇔ 中指+薬指）→ 57⇔46 → 47⇔56。
 * cost = 速い移動の重さ（ほかの 2 レーンの移動は 2 以上）、split = 16 分の割れとしての扱い（1 = 割れたのと同じ）
 */
const TWO_TWO: { a: number; b: number; cost: number; split: number }[] = [
  { a: 0b0011, b: 0b1100, cost: 0.2, split: 1.0 }, // 45 ⇔ 67
  { a: 0b1010, b: 0b0101, cost: 0.5, split: 0.8 }, // 57 ⇔ 46
  { a: 0b1001, b: 0b0110, cost: 1.0, split: 0.5 }, // 47 ⇔ 56
];
/** レーン 4〜7 の集合 → ビット（4 = 1、5 = 2、6 = 4、7 = 8）。4〜7 以外を含むと -1 */
function rightMask(lanes: number[]): number {
  let m = 0;
  for (const l of lanes) { if (l < 4 || l > 7) return -1; m |= 1 << (l - 4); }
  return m;
}
/** 2 つの打鍵が 2-2 交互の表に当たれば、その行 */
function twoTwo(a: number[], b: number[]) {
  if (a.length !== 2 || b.length !== 2) return null;
  const ma = rightMask(a), mb = rightMask(b);
  if (ma < 0 || mb < 0) return null;
  return TWO_TWO.find(t => (t.a === ma && t.b === mb) || (t.a === mb && t.b === ma)) ?? null;
}

/**
 * 1P の皿側の手（レーン 1〜3）の交互。ユーザー（上級者）の案内による、やりやすい順:
 * 13⇔2 → 1⇔23 → 12⇔3。cost・split の意味は {@link TWO_TWO} と同じ。
 */
const SCRATCH_ALT: { a: number; b: number; cost: number; split: number }[] = [
  { a: 0b101, b: 0b010, cost: 0.2, split: 1.0 }, // 13 ⇔ 2
  { a: 0b001, b: 0b110, cost: 0.5, split: 0.8 }, // 1 ⇔ 23
  { a: 0b011, b: 0b100, cost: 1.0, split: 0.5 }, // 12 ⇔ 3
];
/** レーン 1〜3 の集合 → ビット（1 = 1、2 = 2、3 = 4）。1〜3 以外を含むと -1 */
function leftMask(lanes: number[]): number {
  let m = 0;
  for (const l of lanes) { if (l < 1 || l > 3) return -1; m |= 1 << (l - 1); }
  return m;
}
/** 2 つの打鍵が皿側の手の交互の表に当たれば、その行 */
function scratchAlt(a: number[], b: number[]) {
  const ma = leftMask(a), mb = leftMask(b);
  if (ma <= 0 || mb <= 0) return null;
  return SCRATCH_ALT.find(t => (t.a === ma && t.b === mb) || (t.a === mb && t.b === ma)) ?? null;
}
/** 新しく押すレーンごとに、直前の打鍵の一番近いレーンとの距離で数える（隣 1.0、1 つ飛ばし 1.3 …）。同じレーンは数えない */
function distanceCost(cur: number[], prev: number[]): number {
  let cost = 0;
  for (const lane of cur) {
    if (prev.includes(lane)) continue;
    const dist = Math.min(...prev.map(p => Math.abs(p - lane)));
    cost += 1 + 0.3 * (dist - 1);
  }
  return cost;
}
/** 1P の皿側の手の、直前の打鍵から今の打鍵への速い移動の重さ（表に当たれば軽く、ほかはレーンの距離） */
function scratchHandMoveCost(cur: number[], prev: number[], gap: number): number {
  if (!(gap > 1e-6 && gap < FAST_GAP) || prev.length === 0) return 0;
  const t = scratchAlt(cur, prev);
  return t ? t.cost : distanceCost(cur, prev);
}

/**
 * 1P の皿を回さない手の、直前の打鍵から今の打鍵への速い移動の重さ。
 * 6 と 7 の単打どうしの交互（トリル）は {@link TRILL67_COST}。
 * 片方が親指だけ・もう片方が親指を使わない（重ならない）交互はいちばん楽なので {@link THUMB_ALT_COST}。
 * それ以外は、新しく押すレーンごとに直前の打鍵の一番近いレーンとの距離で数える（隣 1.0、1 つ飛ばし 1.3 …）。
 * 同じレーンの連打（縦連）は並びで変わらないので数えない。
 */
function fingerMoveCost(cur: number[], prev: number[], gap: number): { cost: number; trill67: boolean } {
  if (!(gap > 1e-6 && gap < FAST_GAP) || prev.length === 0) return { cost: 0, trill67: false };
  const only = (s: number[], lane: number) => s.length === 1 && s[0] === lane;
  if ((only(cur, 6) && only(prev, 7)) || (only(cur, 7) && only(prev, 6))) return { cost: TRILL67_COST, trill67: true };
  const tt = twoTwo(cur, prev);
  if (tt) return { cost: tt.cost, trill67: false };
  const disjoint = cur.every(l => !prev.includes(l));
  if (disjoint && ((only(cur, THUMB_LANE) && !prev.includes(THUMB_LANE)) || (only(prev, THUMB_LANE) && !cur.includes(THUMB_LANE)))) {
    return { cost: THUMB_ALT_COST, trill67: false };
  }
  return { cost: distanceCost(cur, prev), trill67: false };
}

/** 1 つの並びの指標。 */
function measure(prep: Prepared, pattern: string, side: 1 | 2): RandomMetrics {
  const { events, chords, simulScratch, inStream, nearScratch, pairs16, pairWeights, chordWeights, clashPair, triples } = prep;
  // 元の鍵盤 → 手（0 = 皿側の手、1 = もう一方の手）とレーン（1〜7、左から）
  const laneOf = new Array<number>(8).fill(0);
  for (let lane = 1; lane <= 7; lane++) laneOf[Number(pattern[lane - 1])] = lane;
  const handOfKey = new Array<number>(8).fill(0);
  for (let key = 1; key <= 7; key++) {
    const lane = laneOf[key];
    handOfKey[key] = side === 1 ? (lane <= SCRATCH_HAND_LANES ? 0 : 1) : (lane >= 8 - SCRATCH_HAND_LANES ? 0 : 1);
  }

  let scratchSimulOk = 0;
  let scratchSimulTotal = 0;
  let streamOk = 0;
  let streamTotal = 0;
  let scratchNear = 0;
  let fastSameHand = 0;
  let trill67 = 0;
  let chordStraddle = 0;
  // 和音ごとの、1P の皿側の手・皿を回さない手のレーン（交互の表での割れの判定用）
  const leftLanes: number[][] = new Array(chords.length);
  const rightLanes: number[][] = new Array(chords.length);
  const lastTime = [-Infinity, -Infinity];
  const lastLane = [0, 0];
  const handTimes: number[][] = [[], []];
  // 和音ごとに使う手（ビット 1 = 皿側の手、2 = もう一方の手）
  const chordHands = new Int8Array(chords.length);
  // 1P は両手とも打鍵単位（和音ごと）で数える: 皿を回さない手は運指（4 人差し指・5 親指・6 中指・7 薬指/小指）で、
  // 皿側の手は交互の表（13⇔2 など）で。2P は運指がまちまちなので、音符ごとのレーンの距離で数える
  const fingerModel = side === 1;
  let lastSet: number[] = [];
  let lastSetLeft: number[] = [];

  for (let c = 0; c < chords.length; c++) {
    const [i, j] = chords[c];
    const w = chordWeights[c];
    const chordCount = [0, 0];
    if (fingerModel) {
      const curLeft: number[] = [];
      for (let k = i; k < j; k++) if (handOfKey[events[k].key] === 0) curLeft.push(laneOf[events[k].key]);
      leftLanes[c] = curLeft;
      if (curLeft.length > 0) {
        fastSameHand += scratchHandMoveCost(curLeft, lastSetLeft, events[i].time - lastTime[0]) * w;
        lastSetLeft = curLeft;
      }
      const cur: number[] = [];
      for (let k = i; k < j; k++) if (handOfKey[events[k].key] === 1) cur.push(laneOf[events[k].key]);
      rightLanes[c] = cur;
      if (cur.length > 0) {
        const move = fingerMoveCost(cur, lastSet, events[i].time - lastTime[1]);
        fastSameHand += move.cost * w;
        if (move.trill67) trill67++;
        lastSet = cur;
      }
    }
    for (let k = i; k < j; k++) {
      const key = events[k].key;
      const lane = laneOf[key];
      const hand = handOfKey[key];
      chordCount[hand]++;
      handTimes[hand].push(events[k].time);
      if (simulScratch[k]) {
        scratchSimulTotal += w;
        if (hand === 0) scratchSimulOk += w;
      }
      if (inStream[k]) {
        streamTotal += w;
        if (hand === 1) streamOk += w;
      }
      if (hand === 0 && nearScratch[k]) scratchNear += w;
      const gap = events[k].time - lastTime[hand];
      if (!fingerModel && gap > 1e-6 && gap < FAST_GAP && lastLane[hand] !== lane) {
        // 離れたレーンへの速い移動ほど重い（隣 1.0、1 つ飛ばし 1.3、2 つ飛ばし 1.6 …）
        fastSameHand += (1 + 0.3 * (Math.abs(lane - lastLane[hand]) - 1)) * w;
      }
    }
    for (let k = i; k < j; k++) {
      const hand = handOfKey[events[k].key];
      lastTime[hand] = events[k].time;
      lastLane[hand] = laneOf[events[k].key];
    }
    chordHands[c] = (chordCount[0] > 0 ? 1 : 0) | (chordCount[1] > 0 ? 2 : 0);
    if (j - i >= 2 && chordCount[0] > 0 && chordCount[1] > 0) chordStraddle += w;
  }

  // どちらの手も片方の打鍵にしか鍵盤が無ければ「割れる」（同じ手が続けて押す組は割れない）
  let split16 = 0;
  let split16Total = 0;
  pairs16.forEach((c, n) => {
    split16Total += pairWeights[n];
    const shared = chordHands[c] & chordHands[c + 1];
    if (shared === 0) {
      split16 += pairWeights[n];
    } else if (fingerModel) {
      // 同じ手が続くとき、交互の表（皿側 13⇔2 など・皿を回さない手 45⇔67 など）に当たれば程度に応じて割れたものとして数える
      let credit = 1;
      if (shared & 1) credit *= scratchAlt(leftLanes[c] ?? [], leftLanes[c + 1] ?? [])?.split ?? 0;
      if (shared & 2) credit *= twoTwo(rightLanes[c] ?? [], rightLanes[c + 1] ?? [])?.split ?? 0;
      split16 += pairWeights[n] * credit;
    }
  });

  // 16 分縦連の衝突: 同じ手に来た鍵盤の組の量を足す
  let jackClash = 0;
  for (let a = 1; a <= 7; a++) for (let b = 1; b <= 7; b++) if (a !== b && handOfKey[a] === handOfKey[b]) jackClash += clashPair[a][b];

  // きれいな形: 3 打鍵のレーンを並べ、1→2 と 2→3 のずらし幅が同じ（0 以外）なら足す
  const shiftOf = (x: [number, number], y: [number, number]): number | null => {
    const lx: number[] = [], ly: number[] = [];
    for (let k = x[0]; k < x[1]; k++) lx.push(laneOf[events[k].key]);
    for (let k = y[0]; k < y[1]; k++) ly.push(laneOf[events[k].key]);
    lx.sort((m, n) => m - n);
    ly.sort((m, n) => m - n);
    const d = ly[0] - lx[0];
    if (d === 0) return null;
    for (let n = 1; n < lx.length; n++) if (ly[n] - lx[n] !== d) return null;
    return d;
  };
  let cleanShape = 0;
  let cleanTotal = 0;
  for (const t of triples) {
    cleanTotal += t.w;
    const d1 = shiftOf(chords[t.c - 2], chords[t.c - 1]);
    if (d1 !== null && d1 === shiftOf(chords[t.c - 1], chords[t.c])) cleanShape += t.w;
  }

  const r3 = (v: number) => Math.round(v * 1000) / 1000;
  return {
    jackClash: r3(jackClash),
    cleanShape: r3(cleanShape),
    cleanTotal: r3(cleanTotal),
    scratchSimulOk: r3(scratchSimulOk),
    scratchSimulTotal: r3(scratchSimulTotal),
    streamOk: r3(streamOk),
    streamTotal: r3(streamTotal),
    split16: Math.round(split16 * 1000) / 1000,
    split16Total: Math.round(split16Total * 1000) / 1000,
    scratchNear: r3(scratchNear),
    fastSameHand: r3(fastSameHand),
    chordStraddle: r3(chordStraddle),
    trill67,
    peakHandDensity: Math.max(peakDensity(handTimes[0]), peakDensity(handTimes[1])),
  };
}

/** 窓 {@link DENSITY_WINDOW} 秒に入るノーツ数の最大（1 秒あたり）。times は昇順。 */
function peakDensity(times: number[]): number {
  let best = 0;
  let lo = 0;
  for (let hi = 0; hi < times.length; hi++) {
    while (times[hi] - times[lo] >= DENSITY_WINDOW) lo++;
    best = Math.max(best, hi - lo + 1);
  }
  return best / DENSITY_WINDOW;
}

function lowerBoundNum(a: number[], x: number): number {
  let lo = 0, hi = a.length;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (a[mid] < x) lo = mid + 1; else hi = mid; }
  return lo;
}
function upperBoundNum(a: number[], x: number): number {
  let lo = 0, hi = a.length;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (a[mid] <= x) lo = mid + 1; else hi = mid; }
  return lo;
}

// ── 白黒の配置 ────────────────────────────────────────────

/** 並びの白黒の配置（左のレーンから、元の白鍵 = "W"・元の黒鍵 = "B"。例: 2461357 → "BBBWWWW"） */
export function layoutOf(pattern: string): string {
  return [...pattern].map(d => ('1357'.includes(d) ? 'W' : 'B')).join('');
}

export interface LayoutRow {
  /** "BBBWWWW" など */
  layout: string;
  /** この配置の中で一番良い並び */
  best: RandomCandidate;
  /** 35 通りの配置の中の順位（一番良い並びで比べる） */
  rank: number;
}

/**
 * 【関数の役割】 5,040 通りを白黒の配置（黒鍵 3 つがどのレーンに来るか、35 通り）でまとめ、
 * それぞれの一番良い並びで順位をつける。プレーヤーは RANDOM を白黒の配置で語ることが多い（「黒黒黒白白白白」など）。
 */
export function rankLayouts(ev: RandomEvaluation): LayoutRow[] {
  const best = new Map<string, RandomCandidate>();
  for (const c of ev.candidates) { // 押しやすい順なので最初に出たものが最良
    const l = layoutOf(c.pattern);
    if (!best.has(l)) best.set(l, c);
  }
  return [...best.entries()].map(([layout, c], i) => ({ layout, best: c, rank: i + 1 }));
}

/** 昇順の times に t ± w 秒のものがあるか。 */
function hasNear(times: number[], t: number, w: number): boolean {
  let lo = 0, hi = times.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (times[mid] < t - w) lo = mid + 1; else hi = mid;
  }
  return lo < times.length && times[lo] <= t + w;
}

// ── RANDOM の判別 ─────────────────────────────────────────

/** 元の譜面の白鍵（1・3・5・7）。2・4・6 は黒鍵。 */
export const WHITE_KEYS = new Set([1, 3, 5, 7]);

/** 曲頭から並べた鍵盤の打鍵（同じ時刻は 1 つにまとめる。皿は除く）。 */
export interface OpeningEvent {
  time: number;
  /** 表示上の小節番号 */
  measure: number;
  /** 元の鍵盤番号（昇順） */
  keys: number[];
}

export interface Identification {
  /** 曲頭の打鍵（判別に使う分 + 少し先まで） */
  opening: OpeningEvent[];
  /** 白鍵のレーンが分かる打鍵の位置（opening の添字）。最後まで分からなければ null */
  whiteAt: number | null;
  /** 並び全体が分かる打鍵の位置。最後まで分からなければ null */
  fullAt: number | null;
}

/**
 * 【関数の役割】 曲頭から何番目の打鍵までで RANDOM の並びを判別できるかを求める。
 *
 * プレーヤーは各打鍵が光ったレーンを見るので、「同じ打鍵にいつも一緒に出てくる鍵盤」は見分けられない。
 * ここまでの打鍵それぞれに入っているか（出現の仕方）が同じ鍵盤を 1 組にすると、見分けられない入れ替えはその組の中だけ。
 * - 白鍵のレーンが分かる: どの組も「白鍵だけ」か「黒鍵だけ」（まだ出ていない鍵盤の組も含む）
 * - 並び全体が分かる: 組がすべて 1 鍵（6 鍵が分かれば残り 1 鍵も決まる）
 * 実機での判別の目安で、皿や CN の終端は数えていない。
 *
 * @param tl      譜面
 * @param extra   判別できた後に opening へ続けて入れる打鍵の数（見え方の参考用）
 */
export function identifyRandom(tl: ChartTimeline, extra = 4): Identification {
  const raw: { time: number; key: number }[] = [];
  for (let i = 0; i < tl.noteKeys.length; i++) if (tl.noteKeys[i] !== 0) raw.push({ time: tl.noteTimes[i], key: tl.noteKeys[i] });
  for (let i = 0; i < tl.cnKeys.length; i++) {
    if ((tl.cnFlags[i] & 1) && tl.cnKeys[i] !== 0) raw.push({ time: tl.cnStartTimes[i], key: tl.cnKeys[i] });
  }
  raw.sort((a, b) => a.time - b.time || a.key - b.key);

  // 同じ時刻の打鍵をまとめる
  const events: OpeningEvent[] = [];
  for (const r of raw) {
    const last = events[events.length - 1];
    if (last && Math.abs(last.time - r.time) < 1e-6) {
      if (!last.keys.includes(r.key)) last.keys.push(r.key);
    } else {
      events.push({ time: r.time, measure: measureAt(tl, r.time), keys: [r.key] });
    }
  }

  // 鍵盤ごとの「どの打鍵に出てきたか」
  const signature: string[] = new Array(8).fill('');
  let whiteAt: number | null = null;
  let fullAt: number | null = null;
  for (let e = 0; e < events.length && fullAt === null; e++) {
    for (const k of events[e].keys) signature[k] += e.toString(36) + ',';
    const groups = new Map<string, number[]>();
    for (let k = 1; k <= 7; k++) {
      const g = groups.get(signature[k]);
      if (g) g.push(k); else groups.set(signature[k], [k]);
    }
    const sets = [...groups.values()];
    if (whiteAt === null && sets.every(g => g.every(k => WHITE_KEYS.has(k)) || g.every(k => !WHITE_KEYS.has(k)))) whiteAt = e;
    if (sets.filter(g => g.length === 1).length >= 6) fullAt = e;
  }

  const last = Math.max(whiteAt ?? -1, fullAt ?? -1);
  const upto = last >= 0 ? Math.min(events.length, last + 1 + extra) : Math.min(events.length, 16);
  return { opening: events.slice(0, upto), whiteAt, fullAt };
}

function measureAt(tl: ChartTimeline, time: number): number {
  let lo = 0, hi = tl.measureTimes.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (tl.measureTimes[mid] <= time + 1e-9) lo = mid + 1; else hi = mid;
  }
  return tl.firstMeasure + Math.max(lo - 1, 0);
}
