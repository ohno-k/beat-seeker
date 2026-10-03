/**
 * randomEval.ts
 *
 * RANDOM の配置評価（譜面再生の「当たり乱探し」）。鍵盤の並べ方 7! = 5,040 通りそれぞれについて、
 * 押しにくい形（減点の形）に当たるノーツの数を数え、少ない順に並べる。
 *
 * RANDOM は鍵盤をレーンごと入れ替えるだけなので、同じ鍵盤の縦連打や総ノーツ数はどの並びでも変わらない。
 * 変わるのは「どの鍵盤がどちらの手に来るか」と「同じ手の中での位置関係」。
 *
 * 総合 = 減点の形ごとの該当ノーツ数の合計（2026-10-03 ユーザー判断で重み付けをやめた）。
 * 補正（難所ほど重い・16 分より速いほど重い・トリルが長いほど重い）や、譜面内の最良〜最悪への引き伸ばしはしない。
 * 1 つのノーツが複数の形に当たれば、形ごとに数える（トリルは「片手の速い移動」と「トリル」の両方に入る）。
 * 「両手にまたがる同時押し」は 2026-10-03 に廃止（「割れない」と意図が重なるため。重み無しで数えると両手で取る普通の和音まで
 * 減点し、正規譜面が 99% の譜面で全並びの平均より悪くなっていた）。
 *
 * 減点の形（{@link PENALTY_KEYS}）:
 * - 皿と同時なのに逆の手: 単発の皿と同じタイミングの鍵盤が、皿を回さない方の手に来た数（皿側の手なら皿＋1 のように一緒に取れる）
 * - 連皿中に皿側の手: 連続スクラッチ（BPM 140 の 16 分以上の間隔で 3 回以上続く皿）の最中の鍵盤が、皿側の手に来た数
 * - 皿の前後に皿側の手: 皿と同時ではないが前後 {@link SCRATCH_WINDOW} 秒に皿がある鍵盤が、皿側の手に来た数（連皿の最中は除く）
 * - 割れない（12 分以上）: 12 分以上の速さ（{@link FAST_TICKS}。2026-10-03 に 16 分から広げた）で続く 2 つの打鍵で、後ろの打鍵のうち、前の打鍵と同じ手に来た鍵盤の数。
 *   1P では交互の表（皿を回さない手の 45⇔67・57⇔46・47⇔56、皿側の手の 13⇔2・1⇔23・12⇔3）に当たる交互は割れたものとして数えない。
 *   階段（{@link markStairs}）も数えない。24 分以上の速さ（{@link FAST24_TICKS}）の打鍵は、同じ手が 3 打以上続いたときだけ数える
 *   （片手ずつ 2 打ずつの配置は悪くない）
 * - 片手の速い移動: 同じ手で 12 分以上の速さ（{@link FAST_TICKS}）で続く打鍵で、新しく別のレーンを押した鍵盤の数（同じレーンの縦連打は数えない）。
 *   階段（同じ手の単打が隣のレーンへ同じ向きに 3 打以上続く、指の転がし）は数えない。ただし 12 分以上で途切れない流れの中で
 *   階段のノーツが {@link STAIR_FREE_NOTES} 打を超えた分（繰り返す階段）は数える（{@link markStairs}）。
 *   1P は運指上やりやすい形を数えない: 皿を回さない手の親指（レーン 5）だけの打鍵と親指以外の打鍵の交互
 *   （{@link THUMB_EASY_GAP} = BPM 180 の 16 分より速いと数える）、交互の表に当たる交互
 * - トリル: 6⇔7・1⇔3 の単打の速い交互、47⇔56 の速い交互、BPM 180 の 16 分より速い親指の交互が 3 打鍵以上続いたときの、
 *   3 打鍵目以降の鍵盤の数
 * - 折り返し階段: 同じ手で 3 打以上の階段がすぐ引き返す（3-2-1-2）箇所の、引き返す点とその前後の 3 打（2-1-2。BPM 150 換算で 24 分以上の速さのとき。{@link countFoldStairs}）
 * - 16 分縦連の衝突: {@link CLASH_WINDOW} 秒ごとに、16 分縦連のある鍵盤と同じ手に来た別の連打鍵盤（一緒に押す和音を除く）の打鍵の数
 *   （その窓の 16 分縦連の数を上限に数える）
 * - CN 押しっぱなし中の同じ手: レーン 3（皿側の手）・4・6（皿を回さない手の人差し指・中指）の CN を押している間に、
 *   同じ手に来るほかの鍵盤（レーン 3 の CN なら皿も）の数
 *
 * 「12 分」「16 分」「8 分」などの間隔は、BPM 150 より速い譜面では実際の速さを BPM 150 に換算して見る（{@link gapTicksOf}）。
 * 手の分け方は皿側の手が皿に近い 3 レーン（1P なら 1〜3、2P なら 5〜7）を持つ形に固定。2P は 1P を左右反転して数える
 * （{@link SCRATCH_HAND_LANES}。1P の運指 4 人差し指・5 親指・6 中指・7 薬指/小指 と合わせてある）。
 * 1 つの手が自分のレーンの鍵盤をまとめて押す和音（白黒分けで 246 を片手など）は普通の押し方なので数えない。
 */
// 拡張子つき: scripts/build-random-ranking.mts（Node が .ts をそのまま読む）からも読み込めるように
import { groupCharges, CN_RELEASE_PAD, type ChartTimeline } from './chartPlayback.ts';

/** 皿側の手が持つレーンの数（皿に近い側から）。1P = レーン 1〜3、2P = レーン 5〜7 */
export const SCRATCH_HAND_LANES = 3;
/** 皿の前後とみなす皿との時間差（秒）。 */
export const SCRATCH_WINDOW = 0.1;
/**
 * 速さを見る減点（割れない・片手の速い移動・トリル・階段・繰り返す階段の流れ）の対象とする打鍵の間隔
 * （tick。4 分 = 96、12 分 = 32、16 分 = 24、24 分 = 16、32 分 = 12）。12 分と、それより速い打鍵が対象。
 * 間隔は {@link gapTicksOf}（BPM 150 以下の譜面は音符の長さ、速い譜面は実際の速さを BPM 150 に換算）で測る。
 * 2026-10-03 に 16 分（24）から 12 分（32）へ広げた（GRID KNIGHT のような BPM 160 の 12 分も対象。ユーザー判断）
 */
const FAST_MIN_TICKS = 1;
const FAST_TICKS = 32;
/** 速さを見る減点の対象となる間隔か */
function isFast(a: { tick: number; time: number }, b: { tick: number; time: number }): boolean {
  const g = gapTicksOf(a, b);
  return g >= FAST_MIN_TICKS && g <= FAST_TICKS + 1e-6;
}
/**
 * 24 分以上の速さとみなす間隔（tick。BPM 150 の 24 分 = 16）。この速さの打鍵では、片手ずつ 2 打ずつの配置（右右左左…）も
 * 悪くないので、同じ手が 3 打以上続いたときだけ「16 分が割れない」に数える（2026-10-03 ユーザー判断）
 */
const FAST24_TICKS = 16;
/**
 * 1P の皿を回さない手の、親指（レーン 5）だけの打鍵と親指以外の打鍵の交互を楽な形とみなす最短の間隔（秒。BPM 180 の 16 分）。
 * これより速いと親指が絡んでも押せないので、普通の速い移動として数え、3 打鍵目からはトリルにも数える（2026-10-03 ユーザー判断）
 */
const THUMB_EASY_GAP = 60 / 180 / 4 - 1e-6;
/**
 * 階段を減点しないのは、12 分以上で途切れずに続く流れの中で、階段のノーツ（各階段の 1 打目から、両手合わせて）が
 * この打数までのとき（一時的な階段は見やすく処理しやすいが、繰り返すと外れ配置。2026-10-03 ユーザー判断）
 */
const STAIR_FREE_NOTES = 7;
/**
 * BPM 150 で 1 秒あたりの tick 数（1 tick = 5 / (8 × BPM) 秒）。打鍵の間隔は、音符の長さ（tick）と
 * 実際の秒数を BPM 150 に換算した tick の短い方で見る。BPM 150 以下の譜面は音符の長さのまま、
 * 速い BPM の譜面は実際の速さで数える（MAX 300 の 8 分は BPM 150 の 16 分と同じ速さ）
 */
const TICKS_PER_SEC_150 = (8 * 150) / 5;
function gapTicksOf(a: { tick: number; time: number }, b: { tick: number; time: number }): number {
  return Math.min(b.tick - a.tick, (b.time - a.time) * TICKS_PER_SEC_150);
}
/** 連続スクラッチとみなす皿どうしの間隔（秒。BPM 140 の 16 分 = 60 / 140 / 4）と、続く回数 */
const STREAM_GAP = 60 / 140 / 4 + 1e-6;
const STREAM_MIN_NOTES = 3;
/** 16 分縦連とみなす同じ鍵盤の間隔（tick。16 分 = 24）と、連打とみなす間隔（4 分 = 96） */
const JACK16_TICKS = 24;
const REPEAT_TICKS = 96;
/** 16 分縦連の衝突を数える窓（秒） */
const CLASH_WINDOW = 1.0;
/** 1P で、押している間に同じ手のノーツが来ると押しにくい CN のレーン（3 = 皿側の手、4 人差し指・6 中指） */
const CN_HARD_LANES = [3, 4, 6];

/** 減点の形（表示順）。値は該当するノーツの数 */
export const PENALTY_KEYS = [
  'scratchSimulOff', 'streamSameHand', 'scratchNear', 'unsplit16', 'fastMove', 'trill', 'foldStair', 'jackClash', 'cnHold',
] as const;
export type PenaltyKey = typeof PENALTY_KEYS[number];
export const PENALTY_LABELS: Record<PenaltyKey, string> = {
  scratchSimulOff: '皿と同時なのに逆の手',
  streamSameHand: '連皿中に皿側の手',
  scratchNear: '皿の前後に皿側の手',
  unsplit16: '割れない（12 分以上）',
  fastMove: '片手の速い移動',
  trill: 'トリル（6⇔7・1⇔3・47⇔56）',
  foldStair: '折り返し階段（24 分以上）',
  jackClash: '16 分縦連の衝突',
  cnHold: 'CN 押しっぱなし中の同じ手',
};

/** 減点の形ごとの該当ノーツ数。 */
export type RandomMetrics = Record<PenaltyKey, number>;

export interface RandomCandidate {
  /** 左のレーンから元の鍵盤番号（例: "3726145"） */
  pattern: string;
  metrics: RandomMetrics;
  /** 総合 = 減点の形の該当ノーツ数の合計（少ないほど押しやすい） */
  score: number;
  /** 5,040 通り中の順位（1 始まり。同点は同じ順位） */
  rank: number;
}

export interface RandomEvaluation {
  /** 押しやすい順 */
  candidates: RandomCandidate[];
  /** 並び → 候補 */
  byPattern: Map<string, RandomCandidate>;
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
  /** 和音 c と c + 1 の間隔: 0 = 12 分より遅い、1 = 12 分〜16 分（24 分より遅い）、2 = 24 分以上の速さ（BPM 150 換算） */
  pairKind: Int8Array;
  /** 鍵盤の組 (a, b) の 16 分縦連の衝突の数（a に 16 分縦連、b に一緒に押さない連打）。並びによらない */
  clashPair: number[][];
  /** CN（区間をつないだ 1 本）ごとの鍵盤と、押している間に来る打鍵の数（鍵盤ごと。添字 0 = 皿） */
  holds: { key: number; during: number[] }[];
}

function prepare(tl: ChartTimeline): Prepared {
  const events: KeyEvent[] = [];
  const scratchTimes: number[] = [];
  const scratchTicks = new Set<number>();
  const scratchHits: { time: number; tick: number }[] = [];
  const push = (time: number, tick: number, key: number) => {
    if (key === 0) { scratchTimes.push(time); scratchTicks.add(Math.round(tick)); scratchHits.push({ time, tick }); }
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
  const gapOf = (c: number) => gapTicksOf(events[chords[c][0]], events[chords[c + 1][0]]);
  const pairs16: number[] = [];
  const pairKind = new Int8Array(chords.length);
  for (let c = 0; c + 1 < chords.length; c++) {
    const gap = gapOf(c);
    if (isFast(events[chords[c][0]], events[chords[c + 1][0]])) {
      pairs16.push(c);
      pairKind[c] = gap <= FAST24_TICKS + 1e-6 ? 2 : 1;
    }
  }

  // CN の押しっぱなし: 先頭の後〜離すタイミング（終端 + CN_RELEASE_PAD）の前に来る打鍵を鍵盤ごとに数える
  // （先頭と同じ tick の打鍵は一緒に押す和音なので数えない）
  const eventTicks = events.map(e => e.tick);
  const scratchTickList = scratchHits.map(h => h.tick).sort((a, b) => a - b);
  const holds: { key: number; during: number[] }[] = [];
  for (const g of groupCharges(tl).groups) {
    if (g.key === 0) continue;
    const during = new Array<number>(8).fill(0);
    const until = g.end + CN_RELEASE_PAD - 1e-6;
    for (let k = upperBoundNum(eventTicks, g.start + 1e-6); k < events.length && eventTicks[k] < until; k++) {
      if (events[k].key !== g.key) during[events[k].key]++;
    }
    for (let k = upperBoundNum(scratchTickList, g.start + 1e-6); k < scratchTickList.length && scratchTickList[k] < until; k++) {
      during[0]++;
    }
    if (during.some(v => v > 0)) holds.push({ key: g.key, during });
  }

  // 16 分縦連の衝突: 窓ごとに、鍵盤 a の 16 分縦連の数と、a と一緒に押さない鍵盤 b の連打の数を数え、
  // 組 (a, b) ごとに少ない方を足す
  type Win = { jack16: number[]; repApart: number[][] };
  const wins = new Map<number, Win>();
  const lastHit: { tick: number; time: number }[] = Array.from({ length: 8 }, () => ({ tick: -Infinity, time: -Infinity }));
  chords.forEach(([i, j]) => {
    const id = Math.floor(events[i].time / CLASH_WINDOW);
    let W = wins.get(id);
    if (!W) {
      W = { jack16: new Array(8).fill(0), repApart: Array.from({ length: 8 }, () => new Array(8).fill(0)) };
      wins.set(id, W);
    }
    const inChord = new Set<number>();
    for (let k = i; k < j; k++) inChord.add(events[k].key);
    for (let k = i; k < j; k++) {
      const key = events[k].key;
      const gap = gapTicksOf(lastHit[key], events[k]);
      if (gap <= JACK16_TICKS) W.jack16[key]++;
      if (gap <= REPEAT_TICKS) {
        for (let a = 1; a <= 7; a++) if (a !== key && !inChord.has(a)) W.repApart[a][key]++;
      }
      lastHit[key] = events[k];
    }
  });
  const clashPair = Array.from({ length: 8 }, () => new Array<number>(8).fill(0));
  for (const W of wins.values()) {
    for (let a = 1; a <= 7; a++) {
      if (W.jack16[a] <= 0) continue;
      for (let b = 1; b <= 7; b++) if (b !== a) clashPair[a][b] += Math.min(W.jack16[a], W.repApart[a][b]);
    }
  }
  return { events, chords, simulScratch, inStream, nearScratch, pairs16, pairKind, clashPair, holds };
}

/**
 * 【関数の役割】 5,040 通りすべてを評価して押しやすい順（減点の形の該当ノーツ数の合計が少ない順）に並べる。
 *
 * @param tl          譜面
 * @param side        1P（皿が左）/ 2P（皿が右）
 */
export function evaluateRandom(tl: ChartTimeline, side: 1 | 2): RandomEvaluation {
  const prep = prepare(tl);
  const candidates: RandomCandidate[] = allPatterns().map(pattern => {
    const metrics = measure(prep, pattern, side);
    let score = 0;
    for (const k of PENALTY_KEYS) score += metrics[k];
    return { pattern, metrics, score, rank: 0 };
  });
  candidates.sort((a, b) => a.score - b.score || a.pattern.localeCompare(b.pattern));
  // 同点は同じ順位
  candidates.forEach((c, i) => {
    c.rank = i > 0 && c.score === candidates[i - 1].score ? candidates[i - 1].rank : i + 1;
  });
  return { candidates, byPattern: new Map(candidates.map(c => [c.pattern, c])) };
}

/** 1P の皿を回さない手で親指が押すレーン。 */
const THUMB_LANE = 5;


/**
 * 1P の皿を回さない手（レーン 4〜7）の 2 鍵ずつの同時押しの交互。ユーザー（上級者）の案内による、やりやすい順:
 * 45⇔67（人差し指+親指 ⇔ 中指+薬指）→ 57⇔46 → 47⇔56。どれも楽な形として数えない（47⇔56 は 3 打鍵以上続くとトリル）
 */
const TWO_TWO: { a: number; b: number }[] = [
  { a: 0b0011, b: 0b1100 }, // 45 ⇔ 67
  { a: 0b1010, b: 0b0101 }, // 57 ⇔ 46
  { a: 0b1001, b: 0b0110 }, // 47 ⇔ 56
];
const TWO_TWO_4756 = TWO_TWO[2];
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
 * 13⇔2 → 1⇔23 → 12⇔3。どれも楽な形として数えない。
 */
const SCRATCH_ALT: { a: number; b: number }[] = [
  { a: 0b101, b: 0b010 }, // 13 ⇔ 2
  { a: 0b001, b: 0b110 }, // 1 ⇔ 23
  { a: 0b011, b: 0b100 }, // 12 ⇔ 3
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
/** 直前の打鍵に無いレーンの数（新しく押す鍵盤。同じレーンの縦連打は数えない） */
function newLanes(cur: number[], prev: number[]): number {
  return cur.filter(l => !prev.includes(l)).length;
}
const only = (s: number[], lane: number) => s.length === 1 && s[0] === lane;

/** 片手の速い移動の判定結果。fast = 片手の速い移動に数える鍵盤の数、trill = トリルに数える鍵盤の数、alt = 続けて数えるための交互の種類 */
interface Move { fast: number; trill: number; alt: '13' | '67' | '4756' | 'thumb' | null }

/** 1P の皿側の手の、直前の打鍵から今の打鍵への移動（交互の表に当たれば楽な形、1⇔3 の単打の交互は 3 打鍵目からトリル）。 */
function scratchHandMove(cur: number[], prev: number[], fast: boolean, afterAlt: Move['alt']): Move {
  if (!fast || prev.length === 0) return { fast: 0, trill: 0, alt: null };
  if ((only(cur, 1) && only(prev, 3)) || (only(cur, 3) && only(prev, 1))) {
    return { fast: 1, trill: afterAlt === '13' ? 1 : 0, alt: '13' };
  }
  if (scratchAlt(cur, prev)) return { fast: 0, trill: 0, alt: null };
  return { fast: newLanes(cur, prev), trill: 0, alt: null };
}

/**
 * 1P の皿を回さない手の、直前の打鍵から今の打鍵への移動。
 * 6⇔7 の単打の交互は 3 打鍵目からトリル。2-2 交互の表に当たれば楽な形（47⇔56 は 3 打鍵目から片手の速い移動とトリル）。
 * 親指（レーン 5）だけの打鍵と親指を使わない打鍵の交互は、{@link THUMB_EASY_GAP}（BPM 180 の 16 分）以上の間隔なら楽な形。
 * それより速いと普通の速い移動として数え、3 打鍵目からはトリルにも数える。
 */
function fingerMove(cur: number[], prev: number[], fast: boolean, gapSec: number, afterAlt: Move['alt']): Move {
  if (!fast || prev.length === 0) return { fast: 0, trill: 0, alt: null };
  if ((only(cur, 6) && only(prev, 7)) || (only(cur, 7) && only(prev, 6))) {
    return { fast: 1, trill: afterAlt === '67' ? 1 : 0, alt: '67' };
  }
  const tt = twoTwo(cur, prev);
  if (tt === TWO_TWO_4756) {
    return afterAlt === '4756' ? { fast: 2, trill: 2, alt: '4756' } : { fast: 0, trill: 0, alt: '4756' };
  }
  if (tt) return { fast: 0, trill: 0, alt: null };
  const disjoint = cur.every(l => !prev.includes(l));
  if (disjoint && ((only(cur, THUMB_LANE) && !prev.includes(THUMB_LANE)) || (only(prev, THUMB_LANE) && !cur.includes(THUMB_LANE)))) {
    if (gapSec >= THUMB_EASY_GAP) return { fast: 0, trill: 0, alt: null };
    const n = newLanes(cur, prev);
    return { fast: n, trill: afterAlt === 'thumb' ? n : 0, alt: 'thumb' };
  }
  return { fast: newLanes(cur, prev), trill: 0, alt: null };
}

/**
 * 1 つの並びの減点の形ごとの該当ノーツ数。
 * 2P は 1P を左右反転して数える（レーン L を 8 − L に置き換え、皿側の手 = 5〜7、親指 = 3 …）。
 * そのため 2P で並び p の順位は、1P で p を逆順にした並びの順位と同じになる（2026-10-03 ユーザー判断）。
 */
function measure(prep: Prepared, pattern: string, side: 1 | 2): RandomMetrics {
  const { events, chords, simulScratch, inStream, nearScratch, pairs16, pairKind, clashPair, holds } = prep;
  // 元の鍵盤 → 1P 換算のレーン（1〜7。2P は左右反転）と手（0 = 皿側の手 = レーン 1〜3、1 = もう一方の手）
  const laneOf = new Array<number>(8).fill(0);
  for (let lane = 1; lane <= 7; lane++) laneOf[Number(pattern[lane - 1])] = side === 1 ? lane : 8 - lane;
  const handOfKey = new Array<number>(8).fill(0);
  for (let key = 1; key <= 7; key++) handOfKey[key] = laneOf[key] <= SCRATCH_HAND_LANES ? 0 : 1;

  const m: RandomMetrics = {
    scratchSimulOff: 0, streamSameHand: 0, scratchNear: 0, unsplit16: 0, fastMove: 0, trill: 0, foldStair: 0, jackClash: 0, cnHold: 0,
  };
  // 和音ごとの、手ごとのレーン（[0] = 皿側の手、[1] = もう一方の手）
  const handLanes: [number[], number[]][] = new Array(chords.length);
  for (let c = 0; c < chords.length; c++) {
    const [i, j] = chords[c];
    const lanes: [number[], number[]] = [[], []];
    for (let k = i; k < j; k++) lanes[handOfKey[events[k].key]].push(laneOf[events[k].key]);
    handLanes[c] = lanes;
  }
  const heads = chords.map(([i]) => events[i]);
  const stair = markStairs(handLanes, heads, pairKind);
  m.foldStair = countFoldStairs(handLanes, heads);

  // 手ごとの直前の打鍵（速さの判定に使う）
  const lastHead: [KeyEvent | null, KeyEvent | null] = [null, null];
  // 両手とも打鍵単位（和音ごと）で数える: 皿を回さない手は運指（4 人差し指・5 親指・6 中指・7 薬指/小指）で、
  // 皿側の手は交互の表（13⇔2 など）で
  const prevLanes: [number[], number[]] = [[], []];
  const lastAlt: [Move['alt'], Move['alt']] = [null, null];

  for (let c = 0; c < chords.length; c++) {
    const [i, j] = chords[c];
    const lanes = handLanes[c];
    for (const hand of [0, 1] as const) {
      const cur = lanes[hand];
      if (cur.length === 0) continue;
      if (stair[c] & (1 << hand)) {
        // 階段（指の転がし）の途中は減点しない
        lastAlt[hand] = null;
        prevLanes[hand] = cur;
        continue;
      }
      const last = lastHead[hand];
      const fast = last !== null && isFast(last, events[i]);
      const move = hand === 0
        ? scratchHandMove(cur, prevLanes[hand], fast, lastAlt[hand])
        : fingerMove(cur, prevLanes[hand], fast, last ? events[i].time - last.time : Infinity, lastAlt[hand]);
      m.fastMove += move.fast;
      m.trill += move.trill;
      lastAlt[hand] = move.alt;
      prevLanes[hand] = cur;
    }
    for (let k = i; k < j; k++) {
      const hand = handOfKey[events[k].key];
      if (simulScratch[k] && hand === 1) m.scratchSimulOff++;
      if (inStream[k] && hand === 0) m.streamSameHand++;
      if (nearScratch[k] && hand === 0) m.scratchNear++;
    }
    for (let k = i; k < j; k++) lastHead[handOfKey[events[k].key]] = events[k];
  }

  // 16 分が割れない: 後ろの打鍵のうち、前の打鍵と同じ手に来た鍵盤。数えないもの:
  // 交互の表に当たる交互、階段（指の転がし）、24 分以上の速さで同じ手が 2 打目まで（右右左左… の 2 打ずつは悪くない）
  for (const c of pairs16) {
    for (const hand of [0, 1] as const) {
      const a = handLanes[c][hand], b = handLanes[c + 1][hand];
      if (a.length === 0 || b.length === 0) continue;
      if (hand === 0 ? scratchAlt(a, b) : twoTwo(a, b)) continue;
      if (stair[c + 1] & (1 << hand)) continue;
      if (pairKind[c] === 2 && !(c > 0 && pairKind[c - 1] > 0 && handLanes[c - 1][hand].length > 0)) continue;
      m.unsplit16 += b.length;
    }
  }

  // 16 分縦連の衝突: 同じ手に来た鍵盤の組の数を足す
  for (let a = 1; a <= 7; a++) for (let b = 1; b <= 7; b++) if (a !== b && handOfKey[a] === handOfKey[b]) m.jackClash += clashPair[a][b];

  // CN の押しっぱなし: レーン 3・4・6（1P 換算）の CN を押している間に、同じ手に来るほかの鍵盤（皿側の手なら皿も）
  for (const h of holds) {
    if (!CN_HARD_LANES.includes(laneOf[h.key])) continue;
    const hand = handOfKey[h.key];
    for (let key = 1; key <= 7; key++) if (key !== h.key && handOfKey[key] === hand) m.cnHold += h.during[key];
    if (hand === 0) m.cnHold += h.during[0];
  }
  return m;
}

/**
 * 階段（指の転がし）の判定。手ごとに、その手が押す打鍵を順にたどり、単打が 12 分以上の速さ（{@link FAST_TICKS}）で
 * 隣のレーンへ同じ向きに 3 打以上続く区間を探して、2 打目以降の和音に印を付ける（ビット 1 = 皿側の手、2 = もう一方の手）。
 * 階段は指を転がして取れるので「16 分が割れない」「片手の速い移動」に数えない（2026-10-03 ユーザー判断。1 つ飛ばしは含めない）。
 *
 * ただし減点しないのは一時的な階段だけ: 12 分以上の速さで途切れずに続く流れ（{@link Prepared.pairKind}。8 分以上空くと数え直し）の中で、
 * 階段になっているノーツ（各階段の 1 打目から、両手合わせて）を順に数え、{@link STAIR_FREE_NOTES} 打を超えた分は印を付けない
 * （階段を繰り返す配置は外れ。2026-10-03 ユーザー判断）。
 */
function markStairs(handLanes: [number[], number[]][], heads: { tick: number; time: number }[], pairKind: Int8Array): Uint8Array {
  const mark = new Uint8Array(handLanes.length);
  // 階段の一員（1 打目を含む）。流れの中で何打目の階段かを数えるのに使う
  const member = new Uint8Array(handLanes.length);
  for (const hand of [0, 1] as const) {
    let prevC = -1;
    // 今続いている階段: 向き（+1 / -1）と、その区間の和音（1 打目から）
    let dir = 0;
    let run: number[] = [];
    const flush = () => {
      if (run.length < 3) return;
      for (let n = 0; n < run.length; n++) {
        member[run[n]] |= 1 << hand;
        if (n > 0) mark[run[n]] |= 1 << hand;
      }
    };
    for (let c = 0; c < handLanes.length; c++) {
      const cur = handLanes[c][hand];
      if (cur.length === 0) continue;
      let step = 0;
      if (prevC >= 0 && cur.length === 1 && handLanes[prevC][hand].length === 1 && isFast(heads[prevC], heads[c])) {
        const d = cur[0] - handLanes[prevC][hand][0];
        if (d === 1 || d === -1) step = d;
      }
      if (step !== 0 && step === dir) {
        run.push(c);
      } else {
        flush();
        dir = step;
        run = step !== 0 ? [prevC, c] : [c];
      }
      prevC = c;
    }
    flush();
  }
  // 途切れない流れごとに階段のノーツを数え、STAIR_FREE_NOTES 打を超えたら階段扱い（減点しない印）を外す
  let inStream = 0;
  for (let c = 0; c < handLanes.length; c++) {
    if (c === 0 || pairKind[c - 1] === 0) inStream = 0;
    for (const hand of [0, 1] as const) {
      if (!(member[c] & (1 << hand))) continue;
      inStream++;
      if (inStream > STAIR_FREE_NOTES) mark[c] &= ~(1 << hand);
    }
  }
  return mark;
}

/**
 * 折り返し階段の数（2026-10-03 ユーザー判断）。手ごとに、その手が押す単打を順にたどり、
 * 隣のレーンへ同じ向きに 3 打以上続いた階段（例: 3-2-1）が、すぐ隣のレーンへ引き返す（3-2-1-2）箇所で、
 * 引き返す点の 1 つ前（1 回目の 2）・引き返す点（1）・引き返した直後（2 回目の 2）の 3 打を数える。
 * 対象は BPM 150 換算で 24 分以上の速さ（{@link FAST24_TICKS}）のときだけ（引き返す前後の 2 つの間隔とも）。
 * Mare Nectaris の正規のように、速い階段を片手の中で往復する形は押しにくいので、階段扱い（減点なし）とは別に数える。
 */
function countFoldStairs(handLanes: [number[], number[]][], chordHeads: { tick: number; time: number }[]): number {
  let count = 0;
  for (const hand of [0, 1] as const) {
    // その手の直前 3 打の和音（古い順）と、今の階段の打数（同じ向きに続いた打数）
    const prev: number[] = [];
    let runLen = 0;
    let dir = 0;
    for (let c = 0; c < handLanes.length; c++) {
      const cur = handLanes[c][hand];
      if (cur.length === 0) continue;
      const p = prev[prev.length - 1];
      let step = 0;
      if (p !== undefined && cur.length === 1 && handLanes[p][hand].length === 1
        && gapTicksOf(chordHeads[p], chordHeads[c]) <= FAST24_TICKS + 1e-6) {
        const d = cur[0] - handLanes[p][hand][0];
        if (d === 1 || d === -1) step = d;
      }
      if (step !== 0 && step === -dir && runLen >= 3) {
        // 3 打以上の階段がすぐ引き返した: 引き返す点の 1 つ前・引き返す点・今の打鍵（2-1-2 の 3 打）
        count += 3;
      }
      if (step !== 0 && step === dir) runLen++;
      else if (step !== 0) runLen = 2;
      else runLen = 1;
      dir = step;
      prev.push(c);
      if (prev.length > 3) prev.shift();
    }
  }
  return count;
}

function upperBoundNum(a: number[], x: number): number {
  let lo = 0, hi = a.length;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (a[mid] <= x) lo = mid + 1; else hi = mid; }
  return lo;
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
