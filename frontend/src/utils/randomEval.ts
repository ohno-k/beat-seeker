/**
 * randomEval.ts
 *
 * RANDOM の配置評価（譜面再生の「当たり乱探し」）。鍵盤の並べ方 7! = 5,040 通りそれぞれについて、
 * 手の負荷を数えて押しやすさの順に並べる。
 *
 * RANDOM は鍵盤をレーンごと入れ替えるだけなので、同じ鍵盤の縦連打や総ノーツ数はどの並びでも変わらない。
 * 変わるのは「どの鍵盤がどちらの手に来るか」と「同じ手の中での位置関係」。
 *
 * 当たり配置の決め手として特に重く見る 3 つ（譜面内の最良の並びとの差を 0〜1 にして × {@link KEY_WEIGHT}）:
 * - 皿と同時に取れる: 単発の皿と同じタイミングの鍵盤が、皿側の手に来る割合（皿と一緒に同じ手で取れる。1P の皿＋1 など）
 * - 連皿中は逆の手: 連続スクラッチ（BPM 140 の 16 分以上の間隔で 3 回以上続く皿）の最中の鍵盤が、
 *   皿を回さない方の手に来る割合（連皿中は皿側の手が塞がるので、同じ手に来ない方が良い）
 * - 16 分の左右交互: 16 分間隔で続く打鍵が、左右の手で交互になる割合（1 つの手だけの打鍵どうしが別の手なら交互）
 *
 * ほかの負荷（全 5,040 通りの中央値で割ってそろえ、そのまま足す。同じくらいの並びの差をつける）:
 * - 皿の前後: 皿と同時ではないが前後 {@link SCRATCH_WINDOW} 秒に、皿側の手へ来るノーツの数（連皿の最中は除く）
 * - 片手の速い連打: 同じ手で {@link FAST_GAP} 秒未満に続く、別レーンへの打鍵（離れたレーンほど重く数える）
 * - 片手の多鍵同時押し: 1 つの手が 3 鍵以上を同時に押す回数
 * - 片手の最大密度: どちらかの手の 1 秒あたりのノーツ数の最大
 *
 * 総合は小さいほど押しやすい。手の分け方は「皿側の手が皿に近い鍵盤を何個持つか」
 * （1P なら 1〜n 番、2P なら 7〜(8-n) 番のレーン）で決める。
 * 評価は並びの目安で、指の置き方（運指）や CN の押しっぱなしは考えていない。
 */
import type { ChartTimeline } from './chartPlayback';

/** 皿の前後とみなす皿との時間差（秒）。 */
export const SCRATCH_WINDOW = 0.1;
/** 片手の速い連打とみなす間隔（秒。BPM 150 の 16 分 = 0.1 秒）。 */
export const FAST_GAP = 0.105;
/** 16 分とみなす打鍵の間隔（tick。4 分 = 96、16 分 = 24。わずかに詰まった配置も含める） */
const SIXTEENTH_MIN = 20;
const SIXTEENTH_MAX = 24;
/** 連続スクラッチとみなす皿どうしの間隔（秒。BPM 140 の 16 分 = 60 / 140 / 4）と、続く回数 */
const STREAM_GAP = 60 / 140 / 4 + 1e-6;
const STREAM_MIN_NOTES = 3;
/** 「皿と同時に取れる」「連皿中は逆の手」「16 分の左右交互」の重み（それぞれ譜面内の最良〜最悪を 0〜1 にした不足分にかける。ほかの負荷は中央値で 1 前後にそろえてある） */
export const KEY_WEIGHT = 4;
/** 片手の最大密度を数える窓（秒）。 */
const DENSITY_WINDOW = 1.0;

export interface RandomMetrics {
  /** 単発の皿と同時の鍵盤のうち、皿側の手に来る数 / 単発の皿と同時の鍵盤の総数 */
  scratchSimulOk: number;
  scratchSimulTotal: number;
  /** 連皿の最中の鍵盤のうち、皿を回さない方の手に来る数 / 連皿の最中の鍵盤の総数 */
  streamOk: number;
  streamTotal: number;
  /** 16 分で続く打鍵のうち、左右の手で交互になる組の数 / 16 分で続く組の総数 */
  alt16: number;
  sixteenthPairs: number;
  scratchNear: number;
  fastSameHand: number;
  bigChords: number;
  peakHandDensity: number;
}

/** 中央値で割ってそろえる負荷。 */
type LoadKey = 'scratchNear' | 'fastSameHand' | 'bigChords' | 'peakHandDensity';
const LOAD_KEYS: LoadKey[] = ['scratchNear', 'fastSameHand', 'bigChords', 'peakHandDensity'];

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
  /** 負荷の全並びでの中央値（総合の正規化に使った値） */
  medians: Record<LoadKey, number>;
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
  return { events, chords, simulScratch, inStream, nearScratch, pairs16 };
}

/**
 * 【関数の役割】 5,040 通りすべてを評価して押しやすい順に並べる。
 *
 * @param tl          譜面
 * @param side        1P（皿が左）/ 2P（皿が右）
 * @param scratchKeys 皿側の手が担当する鍵盤の数（皿に近いレーンから数える。1〜6）
 */
export function evaluateRandom(tl: ChartTimeline, side: 1 | 2, scratchKeys: number): RandomEvaluation {
  const prep = prepare(tl);
  const patterns = allPatterns();
  const raw: RandomMetrics[] = patterns.map(p => measure(prep, p, side, scratchKeys));

  const medians = Object.fromEntries(LOAD_KEYS.map(k => [k, median(raw.map(m => m[k]))])) as Record<LoadKey, number>;
  const norm = (v: number, med: number) => (med > 0 ? v / med : v > 0 ? 1 + v : 0);
  // 皿同時・16 分交互は、譜面ごとに並びで動かせる幅が違う（両手の和音はどの並びでも交互にならない等）ので、
  // この譜面で一番良い並びを 0、一番悪い並びを 1 に引き伸ばしてから重みをかける
  const shortfall = (v: number, best: number, worst: number) => (best > worst ? (best - v) / (best - worst) : 0);
  const range = (vals: number[]) => [Math.max(...vals), Math.min(...vals)];
  const [simulBest, simulWorst] = range(raw.map(m => m.scratchSimulOk));
  const [streamBest, streamWorst] = range(raw.map(m => m.streamOk));
  const [altBest, altWorst] = range(raw.map(m => m.alt16));

  const candidates: RandomCandidate[] = patterns.map((pattern, i) => {
    const m = raw[i];
    let score = 0;
    for (const k of LOAD_KEYS) score += norm(m[k], medians[k]);
    score += KEY_WEIGHT * shortfall(m.scratchSimulOk, simulBest, simulWorst);
    score += KEY_WEIGHT * shortfall(m.streamOk, streamBest, streamWorst);
    score += KEY_WEIGHT * shortfall(m.alt16, altBest, altWorst);
    return { pattern, metrics: m, score, rank: 0 };
  });
  candidates.sort((a, b) => a.score - b.score || a.pattern.localeCompare(b.pattern));
  // 同点は同じ順位
  candidates.forEach((c, i) => {
    c.rank = i > 0 && Math.abs(c.score - candidates[i - 1].score) < 1e-9 ? candidates[i - 1].rank : i + 1;
  });
  return { candidates, byPattern: new Map(candidates.map(c => [c.pattern, c])), medians };
}

/** 1 つの並びの指標。 */
function measure(prep: Prepared, pattern: string, side: 1 | 2, scratchKeys: number): RandomMetrics {
  const { events, chords, simulScratch, inStream, nearScratch, pairs16 } = prep;
  // 元の鍵盤 → 手（0 = 皿側の手、1 = もう一方の手）とレーン（1〜7、左から）
  const laneOf = new Array<number>(8).fill(0);
  for (let lane = 1; lane <= 7; lane++) laneOf[Number(pattern[lane - 1])] = lane;
  const handOfKey = new Array<number>(8).fill(0);
  for (let key = 1; key <= 7; key++) {
    const lane = laneOf[key];
    handOfKey[key] = side === 1 ? (lane <= scratchKeys ? 0 : 1) : (lane >= 8 - scratchKeys ? 0 : 1);
  }

  let scratchSimulOk = 0;
  let scratchSimulTotal = 0;
  let streamOk = 0;
  let streamTotal = 0;
  let scratchNear = 0;
  let fastSameHand = 0;
  let bigChords = 0;
  const lastTime = [-Infinity, -Infinity];
  const lastLane = [0, 0];
  const handTimes: number[][] = [[], []];
  // 和音ごとの手の使い方（0 = 皿側の手だけ、1 = もう一方の手だけ、2 = 両手）
  const chordHand = new Int8Array(chords.length);

  for (let c = 0; c < chords.length; c++) {
    const [i, j] = chords[c];
    const chordCount = [0, 0];
    for (let k = i; k < j; k++) {
      const key = events[k].key;
      const lane = laneOf[key];
      const hand = handOfKey[key];
      chordCount[hand]++;
      handTimes[hand].push(events[k].time);
      if (simulScratch[k]) {
        scratchSimulTotal++;
        if (hand === 0) scratchSimulOk++;
      }
      if (inStream[k]) {
        streamTotal++;
        if (hand === 1) streamOk++;
      }
      if (hand === 0 && nearScratch[k]) scratchNear++;
      const gap = events[k].time - lastTime[hand];
      if (gap > 1e-6 && gap < FAST_GAP && lastLane[hand] !== lane) {
        // 離れたレーンへの速い移動ほど重い（隣 1.0、1 つ飛ばし 1.3、2 つ飛ばし 1.6 …）
        fastSameHand += 1 + 0.3 * (Math.abs(lane - lastLane[hand]) - 1);
      }
    }
    for (let k = i; k < j; k++) {
      const hand = handOfKey[events[k].key];
      lastTime[hand] = events[k].time;
      lastLane[hand] = laneOf[events[k].key];
    }
    if (chordCount[0] >= 3) bigChords++;
    if (chordCount[1] >= 3) bigChords++;
    chordHand[c] = chordCount[0] > 0 && chordCount[1] > 0 ? 2 : chordCount[0] > 0 ? 0 : 1;
  }

  let alt16 = 0;
  for (const c of pairs16) {
    const a = chordHand[c];
    const b = chordHand[c + 1];
    if (a !== 2 && b !== 2 && a !== b) alt16++;
  }

  return {
    scratchSimulOk,
    scratchSimulTotal,
    streamOk,
    streamTotal,
    alt16,
    sixteenthPairs: pairs16.length,
    scratchNear,
    fastSameHand: Math.round(fastSameHand * 10) / 10,
    bigChords,
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

/** 昇順の times に t ± w 秒のものがあるか。 */
function hasNear(times: number[], t: number, w: number): boolean {
  let lo = 0, hi = times.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (times[mid] < t - w) lo = mid + 1; else hi = mid;
  }
  return lo < times.length && times[lo] <= t + w;
}

function median(v: number[]): number {
  const s = [...v].sort((a, b) => a - b);
  const n = s.length;
  if (n === 0) return 0;
  return n % 2 ? s[(n - 1) / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
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
