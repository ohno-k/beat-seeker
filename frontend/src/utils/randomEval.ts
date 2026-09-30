/**
 * randomEval.ts
 *
 * RANDOM の配置評価（譜面再生の「当たり乱探し」）。鍵盤の並べ方 7! = 5,040 通りそれぞれについて、
 * 手の負荷を数えて押しやすさの順に並べる。
 *
 * RANDOM は鍵盤をレーンごと入れ替えるだけなので、同じ鍵盤の縦連打や総ノーツ数はどの並びでも変わらない。
 * 変わるのは「どの鍵盤がどちらの手に来るか」と「同じ手の中での位置関係」なので、次の 4 つを数える。
 *
 * - 皿複合: 皿の前後 {@link SCRATCH_WINDOW} 秒に、皿側の手が担当する鍵盤へ来るノーツの数
 * - 片手の速い連打: 同じ手で {@link FAST_GAP} 秒未満に続く、別レーンへの打鍵（離れたレーンほど重く数える）
 * - 片手の多鍵同時押し: 1 つの手が 3 鍵以上を同時に押す回数
 * - 片手の最大密度: どちらかの手の 1 秒あたりのノーツ数の最大
 *
 * 総合は、各指標を全 5,040 通りの中央値で割って（譜面ごとの規模をそろえて）足したもの。小さいほど押しやすい。
 * 手の分け方は「皿側の手が皿に近い鍵盤を何個持つか」（1P なら 1〜n 番、2P なら 7〜(8-n) 番のレーン）で決める。
 * 評価は並びの目安で、指の置き方（運指）や CN の押しっぱなしは考えていない。
 */
import type { ChartTimeline } from './chartPlayback';

/** 皿複合とみなす皿との時間差（秒）。 */
export const SCRATCH_WINDOW = 0.1;
/** 片手の速い連打とみなす間隔（秒。BPM 150 の 16 分 = 0.1 秒）。 */
export const FAST_GAP = 0.105;
/** 片手の最大密度を数える窓（秒）。 */
const DENSITY_WINDOW = 1.0;

export interface RandomMetrics {
  scratchCombo: number;
  fastSameHand: number;
  bigChords: number;
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
  /** 各指標の全並びでの中央値（総合の正規化に使った値） */
  medians: RandomMetrics;
}

/** 鍵盤の打鍵（皿を除く）。時刻順で、同じ時刻の和音は連続して並ぶ。 */
interface KeyEvent { time: number; key: number }

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

/**
 * 【関数の役割】 5,040 通りすべてを評価して押しやすい順に並べる。
 *
 * @param tl          譜面
 * @param side        1P（皿が左）/ 2P（皿が右）
 * @param scratchKeys 皿側の手が担当する鍵盤の数（皿に近いレーンから数える。1〜6）
 */
export function evaluateRandom(tl: ChartTimeline, side: 1 | 2, scratchKeys: number): RandomEvaluation {
  const events: KeyEvent[] = [];
  const scratchTimes: number[] = [];
  const push = (time: number, key: number) => {
    if (key === 0) scratchTimes.push(time);
    else events.push({ time, key });
  };
  for (let i = 0; i < tl.noteKeys.length; i++) push(tl.noteTimes[i], tl.noteKeys[i]);
  for (let i = 0; i < tl.cnKeys.length; i++) if (tl.cnFlags[i] & 1) push(tl.cnStartTimes[i], tl.cnKeys[i]);
  events.sort((a, b) => a.time - b.time || a.key - b.key);
  scratchTimes.sort((a, b) => a - b);

  // 皿の近くに来るノーツ（どの鍵盤でも、並びによらず決まる）
  const nearScratch = events.map(e => hasNear(scratchTimes, e.time, SCRATCH_WINDOW));

  const patterns = allPatterns();
  const raw: RandomMetrics[] = patterns.map(p => measure(events, nearScratch, p, side, scratchKeys));

  const medians: RandomMetrics = {
    scratchCombo: median(raw.map(m => m.scratchCombo)),
    fastSameHand: median(raw.map(m => m.fastSameHand)),
    bigChords: median(raw.map(m => m.bigChords)),
    peakHandDensity: median(raw.map(m => m.peakHandDensity)),
  };
  const norm = (v: number, med: number) => (med > 0 ? v / med : v > 0 ? 1 + v : 0);

  const candidates: RandomCandidate[] = patterns.map((pattern, i) => {
    const m = raw[i];
    const score = norm(m.scratchCombo, medians.scratchCombo) + norm(m.fastSameHand, medians.fastSameHand)
      + norm(m.bigChords, medians.bigChords) + norm(m.peakHandDensity, medians.peakHandDensity);
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
function measure(events: KeyEvent[], nearScratch: boolean[], pattern: string, side: 1 | 2, scratchKeys: number): RandomMetrics {
  // 元の鍵盤 → レーン（1〜7、左から）
  const laneOf = new Array<number>(8).fill(0);
  for (let lane = 1; lane <= 7; lane++) laneOf[Number(pattern[lane - 1])] = lane;
  // レーン → 手（0 = 皿側の手、1 = もう一方の手）
  const handOfLane = (lane: number) => (side === 1 ? (lane <= scratchKeys ? 0 : 1) : (lane >= 8 - scratchKeys ? 0 : 1));

  let scratchCombo = 0;
  let fastSameHand = 0;
  let bigChords = 0;
  const lastTime = [-Infinity, -Infinity];
  const lastLane = [0, 0];
  const handTimes: number[][] = [[], []];

  let i = 0;
  while (i < events.length) {
    // 同じ時刻（和音）をまとめて扱う
    let j = i;
    const chordCount = [0, 0];
    while (j < events.length && events[j].time - events[i].time < 1e-6) j++;
    for (let k = i; k < j; k++) {
      const lane = laneOf[events[k].key];
      const hand = handOfLane(lane);
      chordCount[hand]++;
      handTimes[hand].push(events[k].time);
      if (hand === 0 && nearScratch[k]) scratchCombo++;
      const gap = events[k].time - lastTime[hand];
      if (gap > 1e-6 && gap < FAST_GAP && lastLane[hand] !== lane) {
        // 離れたレーンへの速い移動ほど重い（隣 1.0、1 つ飛ばし 1.3、2 つ飛ばし 1.6 …）
        fastSameHand += 1 + 0.3 * (Math.abs(lane - lastLane[hand]) - 1);
      }
    }
    for (let k = i; k < j; k++) {
      const lane = laneOf[events[k].key];
      const hand = handOfLane(lane);
      lastTime[hand] = events[k].time;
      lastLane[hand] = lane;
    }
    if (chordCount[0] >= 3) bigChords++;
    if (chordCount[1] >= 3) bigChords++;
    i = j;
  }

  return {
    scratchCombo,
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
